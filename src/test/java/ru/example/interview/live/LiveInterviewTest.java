package ru.example.interview.live;

import org.junit.jupiter.api.Test;
import ru.example.interview.engine.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Весь конвейер без Vosk: распознаватель подменён сценарием, звук синтетический. */
class LiveInterviewTest {

    /**
     * «Распознаёт» заданный текст так же, как Vosk: слова появляются по мере ГОЛОСОВЫХ кадров, в тишине ничего не меняется;
     * finish() отдаёт распознанное с прошлого finish() (граница фразы), а дальше распознавание идёт с чистого листа.
     */
    static final class ScriptedEngine implements SpeechEngine {
        private final String[] words;
        private final int totalVoiced;
        private final long sleepMs;
        private int voiced, shown, utterance;

        ScriptedEngine(String text, int totalVoiced) {
            this(text, totalVoiced, 0);
        }

        ScriptedEngine(String text, int totalVoiced, long sleepMs) {
            this.words = text.isBlank() ? new String[0] : text.split(" ");
            this.totalVoiced = totalVoiced;
            this.sleepMs = sleepMs;
        }

        @Override
        public Result accept(byte[] pcm, int len) {
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            double sum = 0;
            for (int i = 0; i < len / 2; i++) {
                int v = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
                sum += (double) v * v;
            }
            if (Math.sqrt(sum / (len / 2)) < 1500 || totalVoiced <= 0 || words.length == 0) return null;
            voiced++;
            int target = Math.min(words.length, Math.max(1, words.length * voiced / totalVoiced));
            if (target == shown) return null;
            shown = target;
            return new Result(false, String.join(" ", java.util.Arrays.copyOfRange(words, utterance, shown)));
        }

        @Override
        public Result finish() {
            String r = String.join(" ", java.util.Arrays.copyOfRange(words, utterance, shown));
            utterance = shown;
            return new Result(true, r);
        }

        @Override
        public void close() {}
    }

    private static final class Run {
        final List<Map<String, Object>> events = new ArrayList<>();
        final LiveInterview live;

        Run(String answer, int totalVoiced, List<Level> levels) {
            this(TestKb.get(), answer, totalVoiced, 0, levels);
        }

        Run(InterviewKnowledge kb, String answer, int totalVoiced, long sleepMs, List<Level> levels) {
            InterviewSession session = new InterviewSession(kb, levels, new DecisionPolicy(2, 0.08));
            live = new LiveInterview(new ScriptedEngine(answer, totalVoiced, sleepMs), session,
                    new EndpointDetector(new EndpointDetector.Config()), events::add);
        }

        Map<String, Object> last(String type) {
            for (int i = events.size() - 1; i >= 0; i--) if (type.equals(events.get(i).get("type"))) return events.get(i);
            return null;
        }

        int count(String type) {
            int n = 0;
            for (Map<String, Object> e : events) if (type.equals(e.get("type"))) n++;
            return n;
        }

        List<Map<String, Object>> all(String type) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> e : events) if (type.equals(e.get("type"))) out.add(e);
            return out;
        }
    }

    private static void feed(Run r, boolean voiced, int frames) {
        for (int i = 0; i < frames; i++) r.live.onAudio(Pcm.frame(voiced), Pcm.FRAME_SAMPLES * 2);
    }

    /** Тишина, пока не появится N-е событие данного типа; возвращает число кадров или -1. */
    private static int silenceUntil(Run r, String type, int n, int maxFrames) {
        for (int i = 1; i <= maxFrames; i++) {
            r.live.onAudio(Pcm.frame(false), Pcm.FRAME_SAMPLES * 2);
            if (r.count(type) >= n) return i;
        }
        return -1;
    }

    /** ready → небольшая тишина → речь → тишина до решения. */
    private static int speak(Run r, int speechFrames) {
        r.live.onReady();
        feed(r, false, 8);
        feed(r, true, speechFrames);
        return silenceUntil(r, "decision", 1, 100);
    }

    private static final String STRONG = "джей ви эм это виртуальная машина которая исполняет байткод джава джи эр и это среда выполнения для запуска приложений "
            + "а джи ди кей это комплект разработчика там компилятор джавак и инструменты разработки";

    // ===================================================================== базовое поведение

    @Test
    void strong_answer_gives_decision_to_move_on_fast() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        assertEquals("question", r.events.get(0).get("type"));

        int silenceFrames = speak(r, 60);

        Map<String, Object> d = r.last("decision");
        assertNotNull(d, "решение не принято");
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertEquals("sufficient", d.get("reason"));
        long silenceMs = (Long) ((Map<?, ?>) d.get("timing")).get("silenceMs");
        long micros = (Long) ((Map<?, ?>) d.get("timing")).get("decisionMicros");
        System.out.println("решение через " + silenceMs + " мс тишины (" + silenceFrames + " кадров), расчёт " + micros + " мкс");
        assertTrue(silenceMs >= 450 && silenceMs <= 700, "тишина до решения: " + silenceMs);
        assertTrue(micros < 50_000, "решение заняло " + micros + " мкс");
        assertEquals("question", r.events.get(r.events.size() - 1).get("type"));
    }

    @Test
    void weak_answer_triggers_followup_question_but_only_after_a_long_silence() {
        Run r = new Run("джава виртуальная машина она запускает программы а джи ди кей это для разработки там компилятор", 40,
                List.of(Level.INTERN));
        r.live.start();
        int frames = speak(r, 40);
        Map<String, Object> d = r.last("decision");
        assertEquals("ASK_FOLLOW_UP", d.get("action"));
        long silenceMs = (Long) ((Map<?, ?>) d.get("timing")).get("silenceMs");
        assertTrue(silenceMs >= 1900, "перебивать человека уточнением можно не раньше ~2 с тишины, было " + silenceMs + " мс");
        assertEquals(Boolean.TRUE, r.last("question").get("followUp"));
        assertTrue(frames * 50 >= 1900);
    }

    @Test
    void audio_is_ignored_until_client_is_ready() {
        Run r = new Run("привет", 10, List.of(Level.INTERN));
        r.live.start();
        feed(r, true, 30);
        assertNull(r.last("partial"));
        assertNull(r.last("decision"));
    }

    @Test
    void silence_leads_to_no_answer_and_next_question() {
        Run r = new Run("", 0, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        silenceUntil(r, "decision", 1, 400);
        assertEquals("no_answer", r.last("decision").get("reason"));
    }

    @Test
    void stop_returns_report_for_answered_questions() {
        Run r = new Run("не знаю", 20, List.of(Level.INTERN));
        r.live.start();
        speak(r, 20);
        r.live.onStop();
        Object rep = r.last("report").get("report");
        assertTrue(rep instanceof InterviewReport);
        assertTrue(((InterviewReport) rep).finishedEarly());
    }

    @Test
    void skip_moves_to_next_question() {
        Run r = new Run("", 0, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        r.live.onSkip();
        assertEquals("no_answer", r.last("decision").get("reason"));
        assertEquals(2, r.last("question").get("number"));
    }

    // ===================================================================== не перебивать

    @Test
    void a_pause_in_the_middle_of_an_answer_does_not_cut_it_off() {
        // регрессия: раньше после паузы 0,7 с система «слышала конец», бросала уточнение, а продолжение ответа терялось
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        feed(r, false, 8);
        feed(r, true, 20);            // 1 с речи: сказана только треть ответа
        feed(r, false, 14);           // пауза 0,7 с: человек думает
        assertEquals(0, r.count("decision"), "во время паузы решение принимать нельзя");
        feed(r, true, 40);            // продолжает отвечать
        assertEquals(0, r.count("decision"));
        silenceUntil(r, "decision", 1, 100);

        assertEquals(1, r.count("decision"));
        Map<String, Object> d = r.last("decision");
        assertEquals("NEXT_QUESTION", d.get("action"));
        String text = (String) d.get("recognized");
        assertTrue(text.contains("JVM") && text.contains("JRE") && text.contains("JDK"),
                "решение принято по ПОЛНОМУ ответу, а не по первой трети: " + text);
    }

    @Test
    void an_incomplete_answer_is_not_interrupted_by_a_follow_up_during_a_long_thinking_pause() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        feed(r, false, 8);
        feed(r, true, 20);            // сказана треть → предварительное решение «нужно уточнение»
        feed(r, false, 30);           // думает 1,5 с — для уточнения это ещё рано
        assertEquals(0, r.count("decision"));
        feed(r, true, 40);
        silenceUntil(r, "decision", 1, 100);
        assertEquals("NEXT_QUESTION", r.last("decision").get("action"));
        for (Map<String, Object> q : r.all("question")) {
            assertEquals(Boolean.FALSE, q.get("followUp"), "уточняющий вопрос задан не был");
        }
    }

    @Test
    void partial_text_is_completed_before_the_decision_is_made() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        speak(r, 60);
        // последний partial мог отставать, но к решению распознавание дожато (flush) и полный текст уже на экране
        String shown = (String) r.last("partial").get("text");
        assertTrue(shown.contains("JDK") && shown.contains("инструменты разработки"), shown);
    }

    // ===================================================================== окно отмены

    @Test
    void person_continuing_inside_the_grace_window_cancels_the_decision() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        feed(r, false, 8);
        feed(r, true, 20);                                   // треть ответа
        assertTrue(silenceUntil(r, "decision", 1, 100) > 0);
        assertEquals("ASK_FOLLOW_UP", r.last("decision").get("action"));
        Map<String, Object> fu = r.last("question");
        assertEquals(Boolean.TRUE, fu.get("followUp"));
        assertEquals(1000, fu.get("graceMs"));               // клиент показал бы его только после окна

        feed(r, false, 8);                                   // 0,4 с тишины — ещё в окне отмены
        assertEquals(0, r.count("retract"));
        feed(r, true, 40);                                   // человек продолжает ответ
        assertEquals(1, r.count("retract"), "решение должно быть отменено");
        @SuppressWarnings("unchecked")
        Map<String, Object> back = (Map<String, Object>) r.last("retract").get("question");
        assertEquals(Boolean.FALSE, back.get("followUp"));
        assertEquals(1, back.get("number"));

        silenceUntil(r, "decision", 2, 100);
        Map<String, Object> d = r.last("decision");
        assertEquals("NEXT_QUESTION", d.get("action"));
        assertTrue(((String) d.get("recognized")).contains("JDK"), "ответ договорён полностью");

        r.live.onStop();
        InterviewReport rep = (InterviewReport) r.last("report").get("report");
        assertEquals(1, rep.answeredQuestions());
        assertEquals(1, rep.questions().get(0).attempts(), "следа отменённого уточнения в отчёте быть не должно");
    }

    @Test
    void silence_inside_the_grace_window_keeps_the_decision() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        speak(r, 60);
        assertEquals(1000, r.last("question").get("graceMs"));
        feed(r, false, 40);                                  // 2 с тишины
        assertEquals(0, r.count("retract"));
        assertEquals(1, r.count("decision"));
    }

    @Test
    void skip_is_ignored_while_the_previous_question_is_still_on_screen() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        speak(r, 60);
        r.live.onSkip();                                     // окно отмены: на экране ещё прежний вопрос
        assertEquals(1, r.count("decision"));
    }

    @Test
    void last_question_report_waits_for_the_grace_window() {
        InterviewKnowledge kb = TestKb.single();
        String ref = kb.questions()[0].question().reference();
        Run r = new Run(kb, ref, 60, 0, null);
        r.live.start();
        speak(r, 60);
        assertEquals("FINISH", r.last("decision").get("action"));
        assertEquals(0, r.count("report"), "отчёт сразу после решения не отправляется");
        feed(r, false, 10);
        assertEquals(0, r.count("report"));
        feed(r, false, 15);                                  // окно отмены (1 с) прошло
        assertEquals(1, r.count("report"));
    }

    @Test
    void person_continuing_after_the_last_question_decision_reopens_it() {
        InterviewKnowledge kb = TestKb.single();
        String ref = kb.questions()[0].question().reference();
        Run r = new Run(kb, ref, 60, 0, null);
        r.live.start();
        speak(r, 60);
        assertEquals("FINISH", r.last("decision").get("action"));
        feed(r, false, 6);
        feed(r, true, 10);                                   // продолжил говорить — интервью не должно закрыться
        assertEquals(1, r.count("retract"));
        assertEquals(0, r.count("report"));

        silenceUntil(r, "decision", 2, 100);
        assertEquals("FINISH", r.last("decision").get("action"));
        feed(r, false, 25);
        assertEquals(1, r.count("report"));
    }

    // ===================================================================== «успевает ли распознавание»

    @Test
    void slow_recognizer_raises_a_warning() {
        Run r = new Run(TestKb.get(), STRONG, 60, 58, List.of(Level.INTERN));   // 58 мс на кадр 50 мс: не успевает
        r.live.start();
        r.live.onReady();
        feed(r, true, 30);
        Map<String, Object> w = r.last("warning");
        assertNotNull(w, "предупреждения о тормозящем распознавании нет");
        assertEquals("asr_slow", w.get("code"));
        assertTrue(((Double) w.get("rtf")) > 0.85);
    }

    @Test
    void fast_recognizer_does_not_raise_a_warning() {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        speak(r, 60);
        assertNull(r.last("warning"));
    }

    // ===================================================================== текст, озвучка, уточнения STT, Excel

    @Test
    void question_event_carries_spoken_form_with_russian_pronunciation() {
        Run r = new Run("", 0, List.of(Level.INTERN));
        r.live.start();
        Map<String, Object> q = r.events.get(0);
        assertEquals("Что такое JVM, JRE и JDK?", q.get("text"));
        assertEquals("Что такое джей-ви-эм, джей-ар-и и джей-ди-кей?", q.get("spoken"));
        assertEquals(0, q.get("graceMs"));
    }

    @Test
    void screen_text_shows_english_names_even_if_vosk_misheard_them() {
        Run r = new Run("это хешмеп который хранит пары ключ значение", 30, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        feed(r, false, 8);
        feed(r, true, 30);
        String shown = (String) r.last("partial").get("text");
        assertTrue(shown.contains("HashMap"), shown);
    }

    @Test
    void finishing_creates_excel_file_and_does_not_leak_reference_answers_to_the_browser() throws Exception {
        Run r = new Run("не знаю", 20, List.of(Level.INTERN));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("rep");
        r.live.setExport(d -> {
            byte[] xlsx = ru.example.interview.export.InterviewExcelReport.build(d);
            java.nio.file.Path f = ru.example.interview.export.ReportFiles.uniquePath(dir,
                    ru.example.interview.export.ReportFiles.fileName(d.candidate(), d.startedAt()));
            java.nio.file.Files.write(f, xlsx);
            return new ReportExporter.Result(f.getFileName().toString(), f.toString(), "/api/reports/x");
        }, "Иван Петров", Map.of("Порог тишины", "450 мс"));
        r.live.start();
        speak(r, 20);
        r.live.onStop();

        Map<String, Object> msg = r.last("report");
        @SuppressWarnings("unchecked")
        Map<String, Object> excel = (Map<String, Object>) msg.get("excel");
        assertNotNull(excel, "в сообщении нет сведений о файле; excelError=" + msg.get("excelError"));
        java.nio.file.Path file = java.nio.file.Path.of((String) excel.get("path"));
        assertTrue(java.nio.file.Files.size(file) > 5000, "файл пуст");
        String fn = file.getFileName().toString();
        assertTrue(fn.contains("Иван_Петров") || fn.contains("Ivan_Petrov"), fn);
        // эталонный ответ первого вопроса не должен попасть в то, что уходит в браузер
        assertFalse(String.valueOf(msg).contains("исполняющая байткод Java"), "утечка эталона в сообщение клиенту");
    }

    @Test
    void failing_export_does_not_break_the_report() {
        Run r = new Run("не знаю", 20, List.of(Level.INTERN));
        r.live.setExport(d -> {
            throw new java.io.IOException("диск недоступен");
        }, "Кандидат", Map.of());
        r.live.start();
        speak(r, 20);
        r.live.onStop();
        Map<String, Object> msg = r.last("report");
        assertNotNull(msg.get("report"));
        assertEquals("диск недоступен", msg.get("excelError"));
        assertNull(msg.get("excel"));
    }

    /** Озвучка вопроса длилась 10 с, человек начал отвечать на 10,4 с: считаем скорость ответа с учётом озвучки и без. */
    private double speedAfterTenSecondQuestion(boolean headphones) {
        Run r = new Run(STRONG, 60, List.of(Level.INTERN));
        r.live.start();
        r.live.onReady();
        if (headphones) r.live.onTtsEnd(10_000);
        feed(r, false, 208);                         // 10,4 с тишины
        feed(r, true, 60);
        silenceUntil(r, "decision", 1, 100);
        r.live.onStop();
        return ((InterviewReport) r.last("report").get("report")).questions().get(0).speed();
    }

    @Test
    void tts_duration_is_not_counted_as_thinking_time_in_headphones_mode() {
        double with = speedAfterTenSecondQuestion(true);
        double without = speedAfterTenSecondQuestion(false);
        assertTrue(with > without + 2, "с учётом озвучки " + with + " против " + without);
        assertTrue(with >= 7.5, "быстрый старт ответа должен давать высокую скорость: " + with);
    }
}
