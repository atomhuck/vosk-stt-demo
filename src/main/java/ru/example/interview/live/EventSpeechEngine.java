package ru.example.interview.live;

/** Хранит последнюю гипотезу STT для завершения ответа в аналитике. */
public final class EventSpeechEngine implements SpeechEngine {
    private String partial = "";

    public void observe(Result result) {
        if (result == null) return;
        partial = result.isFinal() ? "" : result.text();
    }

    public void reset() { partial = ""; }

    @Override
    public Result accept(byte[] pcm, int len) {
        throw new UnsupportedOperationException("Аналитика принимает события STT, а не PCM");
    }

    @Override
    public Result finish() {
        String text = partial;
        partial = "";
        return text.isBlank() ? null : new Result(true, text);
    }

    @Override
    public void close() {
        partial = "";
    }
}
