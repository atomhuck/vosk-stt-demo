package ru.example.interview.live;

import ru.example.interview.engine.AnswerTiming;
import ru.example.interview.engine.EndpointDetector;
import ru.example.interview.engine.InterviewDetails;
import ru.example.interview.engine.InterviewSession;
import ru.example.interview.engine.SpokenText;
import ru.example.interview.engine.TermNormalizer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Склеивает поток распознанной речи, определение конца ответа, решение и переход к следующему вопросу.
 *
 * <h2>Синхронизация: не перебивать и не терять слова</h2>
 * Конец ответа проходит три стадии, и на каждой человек может продолжить говорить:
 * <ol>
 *   <li><b>Мягкий конец</b> (тишина ≈ 0,45 с): распознавание «дожимается» (flush), так что текст полный;
 *       по нему сразу считается <i>предварительное</i> решение — но не принимается.</li>
 *   <li><b>Жёсткий конец</b>: решение принимается после дополнительной паузы, зависящей от решения.
 *       Законченный ответ → почти сразу (+0,15 с, итого ≈ 0,6 с). Неполный ответ, после которого нужно уточнение, —
 *       намного дольше (+1,5 с): именно тут человек чаще всего просто думает, и перебивать его нельзя.
 *       Если речь возобновилась — предварительное решение отбрасывается, текст продолжает копиться.</li>
 *   <li><b>Окно отмены</b> (grace, ≈ 1 с после решения): микрофон остаётся открытым, а следующий вопрос
 *       клиент показывает и озвучивает только после окна. Если человек всё-таки продолжил — решение
 *       откатывается (retract), и он дозаканчивает ответ на прежний вопрос.</li>
 * </ol>
 * Решение считается за микросекунды; STT передаёт уточнённый текст отдельным событием после первичного решения.
 */
public final class LiveInterview {

    public interface Sender {
        void send(Map<String, Object> message);
    }

    /**
     * Паузы синхронизации, мс (сверх «мягкого конца» детектора).
     *
     * @param nextExtraMs      ответ достаточный → к следующему вопросу
     * @param otherNextExtraMs «не знаю», нет ответа, не по теме и т.п. → к следующему вопросу
     * @param followUpExtraMs  ответ неполный → уточняющий вопрос (самая осторожная пауза)
     * @param graceMs          окно отмены решения; следующий вопрос клиент показывает после него
     */
    public record Timing(int nextExtraMs, int otherNextExtraMs, int followUpExtraMs, int graceMs) {
        public static Timing defaults() {
            return new Timing(150, 600, 1500, 1000);
        }
    }

    private enum Phase { IDLE, LISTENING, TENTATIVE, GRACE, WAIT_READY }

    private final SpeechEngine speech;
    private final InterviewSession interview;
    private final EndpointDetector detector;
    private final Sender out;
    private final TermNormalizer normalizer;
    private final SpokenText spoken;

    private Timing timing = Timing.defaults();
    private long pendingWaitMs = 25_000;
    private boolean awaitExternalRefinement;
    private ReportExporter exporter;
    private String candidate = "";
    private java.time.Instant startedAt = java.time.Instant.now();
    private Map<String, String> settings = Map.of();

    private final StringBuilder committed = new StringBuilder();
    private String partial = "";
    private String current = "";          // для анализа
    private String currentDisplay = "";   // для экрана
    private int words;
    private boolean connector;

    private Phase phase = Phase.IDLE;
    private InterviewSession.Peek tentative;
    private long commitAtSilenceMs;
    private long graceStartAudioMs;
    private boolean pendingFinish;
    private long ttsEndMs;               // когда закончилась озвучка вопроса (режим «в наушниках»): это время не считается раздумьем
    private final java.util.Map<Integer, InterviewSession.AttemptRef> answerRefs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Integer> pendingRefinements = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Object refinementLock = new Object();
    private int answerId;
    private boolean finished;

    // контроль «успевает ли распознавание за речью»
    private double rtf;
    private int frames;
    private long lastRtfWarn;

    public LiveInterview(SpeechEngine speech, InterviewSession interview, EndpointDetector detector, Sender out) {
        this.speech = speech;
        this.interview = interview;
        this.detector = detector;
        this.out = out;
        this.normalizer = interview.knowledge().normalizer();
        this.spoken = interview.knowledge().spoken();
    }

    public void setTiming(Timing timing) {
        this.timing = timing;
    }

    public void setRefinementWait(long waitMs) {
        this.pendingWaitMs = Math.max(0, waitMs);
        this.awaitExternalRefinement = true;
    }

    /** Куда и от чьего имени сохранять итоговый отчёт (Excel). */
    public void setExport(ReportExporter exporter, String candidate, Map<String, String> settings) {
        this.exporter = exporter;
        this.candidate = candidate == null ? "" : candidate.trim();
        this.settings = settings == null ? Map.of() : settings;
    }

    public void start() {
        startedAt = java.time.Instant.now();
        out.send(question(interview.start(), 0));
    }

    /** Клиент показал/озвучил вопрос и готов слушать ответ: с этого момента идёт отсчёт «скорости ответа». */
    public void onReady() {
        if (finished) return;
        if (speech instanceof EventSpeechEngine eventSpeech) eventSpeech.reset();
        committed.setLength(0);
        partial = "";
        current = "";
        currentDisplay = "";
        words = 0;
        connector = false;
        tentative = null;
        pendingFinish = false;
        ttsEndMs = 0;
        detector.reset();
        phase = Phase.LISTENING;
    }

    /**
     * Режим «в наушниках»: микрофон открыт уже во время озвучки вопроса, и клиент сообщает, когда она закончилась
     * (мс после «готов»). Время озвучки не считается задержкой ответа: скорость оценивается от конца вопроса.
     */
    public void onTtsEnd(long afterReadyMs) {
        ttsEndMs = Math.max(0, afterReadyMs);
    }

    /** Возвращает подсказку без фиксации ответа и изменения текущего вопроса. */
    public void onPeek(String text, String requestId) {
        if (finished || phase != Phase.LISTENING) return;
        String normalized = normalizer.normalize(text == null ? "" : text, false);
        InterviewSession.Peek result = interview.peek(normalized);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "preview");
        message.put("requestId", requestId);
        message.put("action", result.followUp() ? "ASK_FOLLOW_UP" : "NEXT_QUESTION");
        message.put("reason", result.reason());
        message.put("coveragePercent", (int) Math.round(result.coverage() * 100));
        message.put("answerWords", result.words());
        message.put("recognized", normalizer.normalize(text == null ? "" : text, true));
        message.put("readOnly", true);
        out.send(message);
    }

    public void onAudio(byte[] pcm, int len) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY) return;
        long t0 = System.nanoTime();
        SpeechEngine.Result r = speech.accept(pcm, len);
        trackRealtime((System.nanoTime() - t0) / 1e6, len / 2);
        acceptResult(r);
        advance(detector.onFrame(pcm, len, words, connector));
    }

    /** Принимает результат STT и энергию кадра без передачи аудио в аналитику. */
    public void onSttFrame(String type, String text, int samples, double rms, double asrMs) {
        if (finished || phase == Phase.IDLE || phase == Phase.WAIT_READY) return;
        SpeechEngine.Result r = type == null || type.isBlank() ? null
                : new SpeechEngine.Result("final".equals(type), text == null ? "" : text);
        if (speech instanceof EventSpeechEngine eventSpeech) eventSpeech.observe(r);
        trackRealtime(asrMs, samples);
        acceptResult(r);
        advance(detector.onFrameRms(samples, rms, words, connector));
    }

    private void acceptResult(SpeechEngine.Result r) {
        if (r != null) {
            if (r.isFinal()) {
                appendCommitted(r.text());
                partial = "";
            } else {
                partial = r.text();
            }
            refreshCurrent();
            send("partial", "text", currentDisplay);
        }
    }

    private void advance(EndpointDetector.State st) {
        switch (phase) {
            case LISTENING -> {
                if (st == EndpointDetector.State.ENDPOINT) enterTentative();
                else if (st == EndpointDetector.State.NO_SPEECH) commit("", "", new AnswerTiming(detector.audioMs(), 0), false);
            }
            case TENTATIVE -> {
                if (st != EndpointDetector.State.ENDPOINT) cancelTentative();                 // человек продолжил
                else if (detector.silenceMs() >= commitAtSilenceMs) commitCurrent(true);       // жёсткий конец
            }
            case GRACE -> {
                if (st == EndpointDetector.State.SPEAKING) retract();                          // продолжил в окне отмены
                else if (detector.audioMs() - graceStartAudioMs >= timing.graceMs()) endGrace();
            }
            default -> { }
        }
    }

    /** Кнопка «Пропустить вопрос». */
    public void onSkip() {
        if (finished || phase == Phase.GRACE) return;   // в окне отмены на экране ещё прежний вопрос
        commit("", "", new AnswerTiming(0, 0), false);
    }

    /** Кнопка «Завершить»: отчёт по уже отвеченным вопросам. */
    public void onStop() {
        if (finished) return;
        finishInterview();
    }

    /** Применяет позднее уточнение STT к тому же ответу. */
    public void onRefined(int id, String text) {
        InterviewSession.AttemptRef ref = answerRefs.get(id);
        if (ref == null || text == null || text.isBlank()) return;
        interview.refine(ref, normalizer.normalize(text, false), normalizer.normalize(text, true), 2);
        send("refined", "answerId", id, "text", normalizer.normalize(text, true));
    }

    /** Завершает ожидание второго прохода для ответа, включая случай его отключения или ошибки. */
    public void onRefineDone(int id) {
        pendingRefinements.remove(id);
        synchronized (refinementLock) { refinementLock.notifyAll(); }
    }

    // ------------------------------------------------------------------ стадии конца ответа

    /** Мягкий конец: дожимаем распознавание, чтобы текст был полным, и готовим (не принимаем!) решение. */
    private void enterTentative() {
        SpeechEngine.Result fin = speech.finish();
        if (fin != null) appendCommitted(fin.text());
        partial = "";
        refreshCurrent();
        send("partial", "text", currentDisplay);          // человек видит полный текст

        tentative = interview.peek(current);
        int extra = tentative.followUp() ? timing.followUpExtraMs()
                : "sufficient".equals(tentative.reason()) ? timing.nextExtraMs() : timing.otherNextExtraMs();
        commitAtSilenceMs = detector.silenceMs() + extra;
        phase = Phase.TENTATIVE;
        if (extra <= 0) commitCurrent(true);
    }

    private void cancelTentative() {
        tentative = null;
        phase = Phase.LISTENING;
    }

    private void commitCurrent(boolean grace) {
        commit(current, currentDisplay,
                new AnswerTiming(Math.max(0, detector.speechStartMs() - ttsEndMs), detector.speechDurationMs()), grace);
    }

    /** Жёсткий конец: решение принято и отправлено. Дальше — окно отмены. */
    private void commit(String text, String display, AnswerTiming at, boolean grace) {
        long t0 = System.nanoTime();
        InterviewSession.Step step = interview.submit(text, display, at);   // ← горячий путь
        long micros = (System.nanoTime() - t0) / 1000;
        InterviewSession.AttemptRef ref = interview.lastAttempt();
        int committedAnswerId = ++answerId;
        if (ref != null) answerRefs.put(committedAnswerId, ref);
        if (awaitExternalRefinement && !text.isBlank()) pendingRefinements.add(committedAnswerId);
        tentative = null;

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "decision");
        decision.put("answerId", committedAnswerId);
        decision.put("action", step.action().name());
        decision.put("reason", step.reason());
        decision.put("coveragePercent", (int) Math.round(step.coverage() * 100));
        decision.put("answerWords", step.analysis().words());
        decision.put("recognized", display);
        Map<String, Object> timingInfo = new LinkedHashMap<>();
        timingInfo.put("silenceMs", detector.silenceMs());           // сколько ждали тишины до принятия решения
        timingInfo.put("decisionMicros", micros);                    // сколько заняло само решение
        timingInfo.put("serverMs", Math.round((detector.silenceMs() + micros / 1000.0) * 10) / 10.0);
        timingInfo.put("asrRealtimeFactor", Math.round(rtf * 100) / 100.0);
        decision.put("timing", timingInfo);
        out.send(decision);

        boolean finish = step.action() == InterviewSession.Action.FINISH;
        boolean withGrace = grace && timing.graceMs() > 0;
        if (finish) pendingFinish = true;
        else out.send(question(step.next(), withGrace ? timing.graceMs() : 0));

        // ---- вне критического пути ----
        // Первичный текст фиксируется сразу; уточнение STT сможет скорректировать оценку асинхронно.
        interview.refine(ref, text, display, 1);

        if (withGrace) {
            phase = Phase.GRACE;
            graceStartAudioMs = detector.audioMs();
        } else {
            phase = Phase.WAIT_READY;
            if (finish) finishInterview();
        }
    }

    private void endGrace() {
        phase = Phase.WAIT_READY;
        if (pendingFinish) finishInterview();
    }

    /** Человек продолжил отвечать в окне отмены: откатываем решение, он договаривает на прежний вопрос. */
    private void retract() {
        answerRefs.remove(answerId);
        onRefineDone(answerId);
        InterviewSession.Prompt p = interview.undoLast();
        pendingFinish = false;
        phase = Phase.LISTENING;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "retract");
        m.put("reason", "speech_resumed");
        if (p != null) m.put("question", question(p, 0));
        out.send(m);
    }

    private void finishInterview() {
        finished = true;
        phase = Phase.IDLE;
        sendReport();
    }

    // ------------------------------------------------------------------ вспомогательное

    private void appendCommitted(String text) {
        if (text == null || text.isBlank()) return;
        if (committed.length() > 0) committed.append(' ');
        committed.append(text);
    }

    /** Следим, успевает ли распознавание за речью: если нет, текст отстаёт, и решения принимаются по неполному тексту. */
    private void trackRealtime(double asrMs, int samples) {
        if (samples <= 0) return;
        double frameMs = samples * 1000.0 / 16000.0;
        double ratio = asrMs / frameMs;
        rtf = frames == 0 ? ratio : 0.9 * rtf + 0.1 * ratio;
        frames++;
        long now = System.currentTimeMillis();
        if (frames >= 20 && rtf > 0.85 && now - lastRtfWarn > 30_000) {
            lastRtfWarn = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "warning");
            m.put("code", "asr_slow");
            m.put("rtf", Math.round(rtf * 100) / 100.0);
            m.put("message", "STT не успевает за речью (обработка занимает " + Math.round(rtf * 100)
                    + " % времени звука): слова могут опаздывать. Проверьте настройки STT и нагрузку на процессор.");
            out.send(m);
        }
    }

    private void refreshCurrent() {
        String raw;
        if (committed.length() == 0) {
            raw = partial;
        } else {
            raw = partial.isBlank() ? committed.toString() : committed + " " + partial;
        }
        current = normalizer.normalize(raw, false);
        currentDisplay = normalizer.normalize(raw, true);
        words = Connectors.countWords(current);
        connector = Connectors.endsWithConnector(current);
    }

    private void sendReport() {
        if (!pendingRefinements.isEmpty()) {
            send("finalizing", "message", "Жду уточнения распознанного текста…");
            CompletableFuture.runAsync(() -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pendingWaitMs);
                synchronized (refinementLock) {
                    while (!pendingRefinements.isEmpty() && System.nanoTime() < deadline) {
                        long remaining = deadline - System.nanoTime();
                        try { TimeUnit.NANOSECONDS.timedWait(refinementLock, remaining); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                    }
                }
                sendReportNow();
            });
            return;
        }
        sendReportNow();
    }

    private void sendReportNow() {
        InterviewDetails details = interview.details(candidate, startedAt, settings);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "report");
        m.put("report", details.report());      // эталонные ответы в браузер не уходят — только оценки
        if (exporter != null) {
            try {
                ReportExporter.Result r = exporter.export(details);
                Map<String, Object> excel = new LinkedHashMap<>();
                excel.put("file", r.fileName());
                excel.put("path", r.path());
                excel.put("url", r.downloadUrl());
                m.put("excel", excel);
            } catch (Exception e) {
                m.put("excelError", String.valueOf(e.getMessage()));
            }
        }
        out.send(m);
    }

    private void send(String type, Object... fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (fields.length % 2 != 0) throw new IllegalArgumentException("Поля события должны идти парами");
        for (int i = 0; i < fields.length; i += 2)
            m.put(String.valueOf(fields[i]), fields[i + 1]);
        out.send(m);
    }

    private Map<String, Object> question(InterviewSession.Prompt p, int graceMs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "question");
        m.put("number", p.number());
        m.put("total", p.total());
        m.put("level", p.level());
        m.put("topic", p.topic());
        m.put("text", p.text());
        m.put("spoken", spoken.speak(p.text()));      // что реально читать вслух: «HashMap» → «хэшмэп»
        m.put("followUp", p.followUp());
        m.put("followUpNumber", p.followUpNumber());
        m.put("graceMs", graceMs);                    // клиент показывает и озвучивает вопрос только после этого окна
        return m;
    }
}
