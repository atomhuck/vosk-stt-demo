package ru.example.vosk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Потоковое распознавание одного говорящего.
 * Принимает куски PCM (16 бит, little-endian, моно) и возвращает:
 *  - "partial" — текущая гипотеза, пока вы ещё говорите (меняется каждые ~100 мс);
 *  - "final"   — зафиксированный текст после паузы в речи.
 * @version 1.0.0
 */
public class SpeechSession implements AutoCloseable {

    /**
     * Хранит слово с временными метками и оценкой распознавания.
     *
     * @version 1.0.0
     */
    public record Word(String word, double start, double end, Double confidence) {}
    /**
     * Передаёт промежуточный или итоговый результат Vosk.
     *
     * @version 1.0.0
     */
    public record Event(String type, String text, List<Word> words) {
        /**
         * Создаёт событие без временных меток слов.
         *
         * @param type вид события
         * @param text текст ответа или документа
         */
        public Event(String type, String text) { this(type, text, List.of()); }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Recognizer recognizer;
    private String lastPartial = "";

    /**
     * Создаёт Vosk-распознаватель для заданной частоты PCM.
     *
     * @param model загруженная модель Vosk
     * @param sampleRate частота отсчётов аудио
     * @throws IOException при ошибке чтения, записи или обработки потока
     */
    public SpeechSession(Model model, float sampleRate) throws IOException {
        this.recognizer = new Recognizer(model, sampleRate);
        this.recognizer.setWords(true);
        this.recognizer.setPartialWords(true);
    }

    /**
     * Не отправляет клиенту одинаковые промежуточные гипотезы.
     *
     * @param data байты PCM
     * @param len число значимых байтов
     * @return новое событие или null, если гипотеза не изменилась
     */
    public Event accept(byte[] data, int len) {
        if (recognizer.acceptWaveForm(data, len)) {
            lastPartial = "";
            Event event = read(recognizer.getResult(), "final", "text", "result");
            return event.text().isEmpty() ? null : event;
        }
        Event event = read(recognizer.getPartialResult(), "partial", "partial", "partial_result");
        String partial = event.text();
        if (partial.equals(lastPartial)) {
            return null;
        }
        lastPartial = partial;
        return event;
    }

    /**
     * Дораспознаёт остаток после остановки записи.
     *
     * @return итоговое событие после сброса остатка распознавания
     */
    public Event finish() {
        lastPartial = "";
        return read(recognizer.getFinalResult(), "final", "text", "result");
    }

    private static Event read(String json, String type, String field, String wordsField) {
        try {
            var root = MAPPER.readTree(json);
            List<Word> words = new ArrayList<>();
            for (var item : root.path(wordsField)) {
                if (!item.has("start") || !item.has("end")) continue;
                words.add(new Word(item.path("word").asText(""),
                        item.path("start").asDouble(), item.path("end").asDouble(),
                        item.has("conf") ? item.path("conf").asDouble() : null));
            }
            return new Event(type, root.path(field).asText("").trim(), words);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Некорректный JSON от Vosk: " + json, e);
        }
    }

    /**
     * Освобождает ресурсы завершённой сессии.
     */
    @Override
    public void close() {
        recognizer.close();
    }
}
