package org.helios.infra;

import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import org.agrona.CloseHelper;
import org.agrona.concurrent.IdleStrategy;
import org.helios.AeronStream;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.agrona.UnsafeAccess.UNSAFE;

public abstract class InputMessageProcessor implements Processor
{
    private static final long SUCCESSFUL_READS_OFFSET;
    private static final long FAILED_READS_OFFSET;

    private volatile long successfulReads = 0;
    private volatile long failedReads = 0;

    private final InputMessageHandler handler;
    private final Subscription inputSubscription;
    private final int frameCountLimit;
    private final IdleStrategy idleStrategy;
    private final AtomicBoolean running;
    private final Thread processorThread;

    public InputMessageProcessor(final InputMessageHandler handler, final AeronStream stream, final IdleStrategy idleStrategy,
        final int frameCountLimit, final String threadName)
    {
        this.handler = handler;
        this.idleStrategy = idleStrategy;
        this.frameCountLimit = frameCountLimit;

        inputSubscription = stream.aeron.addSubscription(stream.channel, stream.streamId);

        running = new AtomicBoolean(false);
        processorThread = new Thread(this, threadName);
    }

    @Override
    public void start()
    {
        running.set(true);
        processorThread.start();
    }

    @Override
    public void run()
    {
        final FragmentAssembler dataHandler = new FragmentAssembler(handler);

        while (running.get())
        {
            final int fragmentsRead = inputSubscription.poll(dataHandler, frameCountLimit);
            if (0 == fragmentsRead)
            {
                UNSAFE.putOrderedLong(this, FAILED_READS_OFFSET, failedReads + 1);
                idleStrategy.idle(0);
            }
            else
            {
                UNSAFE.putOrderedLong(this, SUCCESSFUL_READS_OFFSET, successfulReads + 1);
                idleStrategy.idle(fragmentsRead);
            }
        }

        final double failureRatio = failedReads / (double)(successfulReads + failedReads);
        System.out.format(processorThread.getName() + " read failure ratio: %f\n", failureRatio);
    }

    @Override
    public void close() throws Exception
    {
        running.set(false);
        processorThread.join();

        CloseHelper.quietClose(inputSubscription);
        CloseHelper.quietClose(handler);
    }

    public long successfulReads()
    {
        return successfulReads;
    }

    public long failedReads()
    {
        return failedReads;
    }

    static
    {
        try
        {
            SUCCESSFUL_READS_OFFSET = UNSAFE.objectFieldOffset(InputMessageProcessor.class.getDeclaredField("successfulReads"));
            FAILED_READS_OFFSET = UNSAFE.objectFieldOffset(InputMessageProcessor.class.getDeclaredField("failedReads"));
        }
        catch (final Exception ex)
        {
            throw new RuntimeException(ex);
        }
    }
}