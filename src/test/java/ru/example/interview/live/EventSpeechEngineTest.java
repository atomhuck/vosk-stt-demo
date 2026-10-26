package ru.example.interview.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventSpeechEngineTest {
    @Test
    void flushes_the_latest_partial_hypothesis_without_accepting_pcm() {
        EventSpeechEngine engine = new EventSpeechEngine();
        engine.observe(new SpeechEngine.Result(false, "первая гипотеза"));
        engine.observe(new SpeechEngine.Result(false, "обновлённая гипотеза"));

        assertEquals("обновлённая гипотеза", engine.finish().text());
        assertThrows(UnsupportedOperationException.class, () -> engine.accept(new byte[3200], 3200));
    }
}
