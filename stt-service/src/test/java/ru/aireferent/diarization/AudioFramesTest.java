package ru.aireferent.diarization;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioFramesTest {
    @Test
    void concatenates_four_160ms_frames_in_sample_order() {
        var frames = List.of(
                frame(0, 1), frame(2560, 2), frame(5120, 3), frame(7680, 4));
        LiveMicrophone.AudioChunk merged = AudioFrames.mergeForNemotron(frames, 7);
        assertEquals(7, merged.index());
        assertEquals(0, merged.firstSample());
        assertEquals(10_240, merged.samples().length);
        for (int i = 0; i < 4; i++) assertEquals(i + 1f, merged.samples()[i * 2560], 0.001f);
    }

    @Test
    void rejects_a_missing_or_reordered_frame() {
        assertThrows(IllegalStateException.class, () -> AudioFrames.mergeForNemotron(
                List.of(frame(0, 1), frame(5120, 3)), 0));
    }

    private static LiveMicrophone.AudioChunk frame(long start, float value) {
        float[] samples = new float[2560];
        java.util.Arrays.fill(samples, value);
        return new LiveMicrophone.AudioChunk(start / 2560, start, samples, 1);
    }
}
