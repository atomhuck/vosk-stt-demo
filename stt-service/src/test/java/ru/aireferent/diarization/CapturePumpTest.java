package ru.aireferent.diarization;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CapturePumpTest {
    private static final int CHUNK = 10_240;

    private static final class FastSource implements SampleSource {
        boolean started;
        boolean paused;

        @Override public void startFresh() { started = true; }
        @Override public float[] readSamples(int count) { return new float[count]; }
        @Override public void pause() { paused = true; }
    }

    @Test void keepsCapturedChunksInOrderAndStopsAtExactAudioDuration() throws Exception {
        FastSource source = new FastSource();
        LiveMicrophone.CapturePump pump = new LiveMicrophone.CapturePump(
                source, CHUNK, 4, 1.28, new AtomicBoolean());
        pump.start();
        pump.join();

        LiveMicrophone.AudioChunk first = pump.poll();
        LiveMicrophone.AudioChunk second = pump.poll();
        assertEquals(0, first.index());
        assertEquals(0, first.firstSample());
        assertEquals(CHUNK, first.samples().length);
        assertEquals(1, second.index());
        assertEquals(CHUNK, second.firstSample());
        assertEquals(CHUNK, second.samples().length);
        assertTrue(second.capturedAtNanos() >= first.capturedAtNanos());
        assertEquals(2 * CHUNK, pump.capturedSamples());
        assertEquals(0, pump.overflowCount());
        assertEquals("max_seconds", pump.stopReason());
        assertTrue(source.started);
        assertTrue(source.paused);
    }

    @Test void stopsAndAccountsForRejectedChunkWhenQueueFills() throws Exception {
        FastSource source = new FastSource();
        AtomicBoolean stop = new AtomicBoolean();
        LiveMicrophone.CapturePump pump = new LiveMicrophone.CapturePump(source, CHUNK, 1, 10, stop);
        pump.start();
        pump.join();

        assertTrue(pump.done());
        assertTrue(stop.get());
        assertEquals("queue_overflow", pump.stopReason());
        assertEquals(1, pump.queueSize());
        assertEquals(1, pump.maxDepth());
        assertEquals(1, pump.overflowCount());
        assertEquals(1, pump.droppedChunks());
        assertEquals(CHUNK, pump.droppedSamples());
        assertEquals(2 * CHUNK, pump.capturedSamples());
        assertEquals(0, pump.poll().firstSample());
        assertFalse(pump.queueSize() > 0);
        assertTrue(source.paused);
    }
}
