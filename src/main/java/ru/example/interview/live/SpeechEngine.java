package ru.example.interview.live;

/** Интерфейс поточного распознавания, используемый аналитическим конвейером и модульными тестами. */
public interface SpeechEngine extends AutoCloseable {

    /** @param isFinal true — STT зафиксировал фрагмент; false — промежуточная гипотеза */
    record Result(boolean isFinal, String text) {}

    /** Подать кусок PCM 16 кГц / 16 бит / моно. null — ничего нового. */
    Result accept(byte[] pcm, int len);

    /** Дораспознать остаток и сбросить состояние для следующего ответа. */
    Result finish();

    @Override
    void close();
}
