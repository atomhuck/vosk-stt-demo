package ru.example.interview.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Состояние одного собеседования: порядок вопросов, уточнения, накопление ответов.
 * submit() — горячий путь (микросекунды): разбор + решение. Остальное (отчёт) считается по требованию.
 */
public final class InterviewSession {

    public enum Action { ASK_FOLLOW_UP, NEXT_QUESTION, FINISH }

    public record Prompt(int questionId, int number, int total, String level, String topic, String text,
                         boolean followUp, int followUpNumber) {}

    public record Step(Action action, String reason, Prompt next, AnswerAnalysis analysis, double coverage) {}

    static final class Attempt {
        final String promptText;
        String text = "";
        AnswerTiming timing = new AnswerTiming(0, 0);
        AnswerAnalysis analysis;
        boolean answered;
        int rank;          // 0 — промежуточный текст STT, 1 — финальный STT, 2 — уточнённый STT
        String display = "";            // текст «как видит человек» (HashMap латиницей, без подмен для анализа)
        String action = "";
        String reason = "";
        java.time.Instant answeredAt;

        Attempt(String promptText) {
            this.promptText = promptText;
        }
    }

    static final class QuestionRun {
        final CompiledQuestion cq;
        final List<Attempt> attempts = new ArrayList<>();
        final boolean[] followUpAsked;
        boolean[] hits;
        int followUpsUsed;
        double lastCoverage;

        QuestionRun(CompiledQuestion cq) {
            this.cq = cq;
            this.followUpAsked = new boolean[cq.followUps().length];
            this.hits = new boolean[cq.poolSize()];
            this.attempts.add(new Attempt(cq.question().text()));
        }

        void recompute(AnswerAnalyzer analyzer) {
            hits = new boolean[cq.poolSize()];
            for (Attempt a : attempts) {
                if (a.answered) a.analysis = analyzer.analyze(cq, a.text, hits);
            }
        }
    }

    private final InterviewKnowledge kb;
    private final AnswerAnalyzer analyzer;
    private final DecisionPolicy policy;
    private final List<CompiledQuestion> plan = new ArrayList<>();
    private final List<QuestionRun> runs = new ArrayList<>();
    private int pos = -1;
    private QuestionRun current;
    private QuestionRun lastRun;
    private Attempt lastAttempt;
    private boolean finished;
    private Snapshot undo;

    /** @param levels уровни вопросов; null/пусто — все. Порядок: Стажёр → Junior → Middle → Senior. */
    public InterviewSession(InterviewKnowledge kb, List<Level> levels, DecisionPolicy policy) {
        this.kb = kb;
        this.analyzer = kb.analyzer();
        this.policy = policy;
        for (CompiledQuestion cq : kb.questions()) {
            if (levels == null || levels.isEmpty() || levels.contains(cq.level())) plan.add(cq);
        }
        plan.sort(Comparator.comparingInt(cq -> cq.level().ordinal())); // стабильная сортировка
        if (plan.isEmpty()) throw new IllegalArgumentException("Нет вопросов для выбранных уровней");
    }

    public synchronized Prompt start() {
        pos = 0;
        current = new QuestionRun(plan.get(0));
        runs.add(current);
        return prompt(current, false, 0);
    }

    /** Горячий путь. */
    public synchronized Step submit(String text, AnswerTiming timing) {
        return submit(text, text, timing);
    }

    /** @param text текст для анализа (термины приведены к каноническим именам), @param display — текст для людей */
    public synchronized Step submit(String text, String display, AnswerTiming timing) {
        if (current == null) throw new IllegalStateException("Интервью не начато или завершено");
        QuestionRun run = current;
        Attempt att = run.attempts.get(run.attempts.size() - 1);
        undo = new Snapshot(run, att, this);
        att.text = text == null ? "" : text;
        att.display = display == null ? att.text : display;
        att.timing = timing;
        att.answered = true;
        att.answeredAt = java.time.Instant.now();
        AnswerAnalysis a = analyzer.analyze(run.cq, att.text, run.hits);
        att.analysis = a;
        double prev = run.lastCoverage;
        run.lastCoverage = a.coverage();
        lastRun = run;
        lastAttempt = att;

        DecisionPolicy.Decision d = policy.decide(run.cq, run.cq.level(), a, prev, run.followUpsUsed,
                run.followUpAsked, run.hits);
        att.reason = d.reason();
        if (d.followUp()) {
            att.action = "ASK_FOLLOW_UP";
            int idx = d.followUpIndex();
            run.followUpAsked[idx] = true;
            run.followUpsUsed++;
            run.attempts.add(new Attempt(run.cq.followUps()[idx].text()));
            Prompt p = new Prompt(run.cq.id(), pos + 1, plan.size(), run.cq.level().title,
                    run.cq.question().topic(), run.cq.followUps()[idx].text(), true, run.followUpsUsed);
            return new Step(Action.ASK_FOLLOW_UP, d.reason(), p, a, a.coverage());
        }
        pos++;
        if (pos >= plan.size()) {
            att.action = "FINISH";
            current = null;
            finished = true;
            return new Step(Action.FINISH, d.reason(), null, a, a.coverage());
        }
        att.action = "NEXT_QUESTION";
        current = new QuestionRun(plan.get(pos));
        runs.add(current);
        return new Step(Action.NEXT_QUESTION, d.reason(), prompt(current, false, 0), a, a.coverage());
    }

    // ------------------------------------------------------------------ предварительное решение и откат

    /** Что решила бы система по этому тексту; сессию не меняет. */
    public record Peek(boolean followUp, String reason, double coverage, int words) {}

    /**
     * «Примерка» решения: используется на мягком конце ответа — решение уже готово, но пока не принято,
     * и если человек продолжит говорить, ничего не нужно откатывать.
     */
    public synchronized Peek peek(String text) {
        if (current == null) return new Peek(false, "finished", 0, 0);
        QuestionRun run = current;
        boolean[] hits = run.hits.clone();
        AnswerAnalysis a = analyzer.analyze(run.cq, text == null ? "" : text, hits);
        DecisionPolicy.Decision d = policy.decide(run.cq, run.cq.level(), a, run.lastCoverage, run.followUpsUsed,
                run.followUpAsked, hits);
        return new Peek(d.followUp(), d.reason(), a.coverage(), a.words());
    }

    /** Состояние до последнего submit(): позволяет отменить решение, если человек всё-таки продолжил отвечать. */
    private static final class Snapshot {
        final QuestionRun run;
        final Attempt att;
        final boolean[] hits;
        final double lastCoverage;
        final QuestionRun lastRun;
        final Attempt lastAttempt;
        final int pos;
        final QuestionRun current;
        final boolean finished;
        final int runsSize;
        final int followUpsUsed;
        final boolean[] followUpAsked;
        final int attemptsSize;
        final String text, display, action, reason;
        final AnswerTiming timing;
        final boolean answered;
        final AnswerAnalysis analysis;
        final java.time.Instant answeredAt;
        final int rank;

        Snapshot(QuestionRun run, Attempt att, InterviewSession s) {
            this.run = run;
            this.att = att;
            this.hits = run.hits.clone();
            this.lastCoverage = run.lastCoverage;
            this.lastRun = s.lastRun;
            this.lastAttempt = s.lastAttempt;
            this.pos = s.pos;
            this.current = s.current;
            this.finished = s.finished;
            this.runsSize = s.runs.size();
            this.followUpsUsed = run.followUpsUsed;
            this.followUpAsked = run.followUpAsked.clone();
            this.attemptsSize = run.attempts.size();
            this.text = att.text;
            this.display = att.display;
            this.action = att.action;
            this.reason = att.reason;
            this.timing = att.timing;
            this.answered = att.answered;
            this.analysis = att.analysis;
            this.answeredAt = att.answeredAt;
            this.rank = att.rank;
        }
    }

    /**
     * Отменяет последнее решение (следующий вопрос или уточнение не задавались «по-настоящему»).
     * @return вопрос, на который кандидат продолжает отвечать, или null, если откатывать нечего
     */
    public synchronized Prompt undoLast() {
        Snapshot s = undo;
        if (s == null) return null;
        undo = null;
        while (runs.size() > s.runsSize) runs.remove(runs.size() - 1);
        pos = s.pos;
        current = s.current;
        finished = s.finished;
        QuestionRun run = s.run;
        while (run.attempts.size() > s.attemptsSize) run.attempts.remove(run.attempts.size() - 1);
        System.arraycopy(s.followUpAsked, 0, run.followUpAsked, 0, s.followUpAsked.length);
        run.followUpsUsed = s.followUpsUsed;
        run.hits = s.hits;
        run.lastCoverage = s.lastCoverage;
        lastRun = s.lastRun;
        lastAttempt = s.lastAttempt;
        Attempt att = s.att;
        att.text = s.text;
        att.display = s.display;
        att.timing = s.timing;
        att.answered = s.answered;
        att.analysis = s.analysis;
        att.action = s.action;
        att.reason = s.reason;
        att.answeredAt = s.answeredAt;
        att.rank = s.rank;
        int idx = run.attempts.indexOf(att);
        if (idx <= 0) return prompt(run, false, 0);
        return new Prompt(run.cq.id(), pos + 1, plan.size(), run.cq.level().title, run.cq.question().topic(),
                att.promptText, true, idx);
    }

    /** Ссылка на конкретный ответ — чтобы точный текст можно было подменить позже, в том числе из другого потока. */
    public static final class AttemptRef {
        final QuestionRun run;
        final Attempt attempt;

        AttemptRef(QuestionRun run, Attempt attempt) {
            this.run = run;
            this.attempt = attempt;
        }
    }

    public synchronized AttemptRef lastAttempt() {
        return lastAttempt == null ? null : new AttemptRef(lastRun, lastAttempt);
    }

    /**
     * Подменяет текст ответа более точным результатом STT и пересчитывает оценку вопроса.
     * Менее точный источник не затирает более точный.
     */
    public synchronized void refine(AttemptRef ref, String text, int rank) {
        refine(ref, text, text, rank);
    }

    public synchronized void refine(AttemptRef ref, String text, String display, int rank) {
        if (ref == null || text == null || text.isBlank() || rank < ref.attempt.rank) return;
        ref.attempt.text = text;
        ref.attempt.display = display == null || display.isBlank() ? text : display;
        ref.attempt.rank = rank;
        ref.run.recompute(analyzer);
    }

    public InterviewKnowledge knowledge() {
        return kb;
    }

    public synchronized InterviewReport report() {
        List<QuestionRun> answered = new ArrayList<>();
        for (QuestionRun r : runs) {
            if (r.attempts.get(0).answered) answered.add(r);
        }
        return InterviewScorer.score(kb, answered, plan.size(), !finished);
    }

    /** Полные данные для отчёта (Excel). Содержит эталонные ответы — в браузер не отправлять. */
    public synchronized InterviewDetails details(String candidate, java.time.Instant startedAt,
                                                 java.util.Map<String, String> settings) {
        List<QuestionRun> answered = new ArrayList<>();
        for (QuestionRun r : runs) {
            if (r.attempts.get(0).answered) answered.add(r);
        }
        InterviewReport rep = InterviewScorer.score(kb, answered, plan.size(), !finished);

        java.util.Set<String> levels = new java.util.LinkedHashSet<>();
        for (CompiledQuestion cq : plan) levels.add(cq.level().title);

        java.util.Set<Integer> termsAll = new java.util.LinkedHashSet<>();
        java.util.Set<Integer> slangAll = new java.util.LinkedHashSet<>();
        List<InterviewDetails.QuestionDetail> out = new ArrayList<>();
        int number = 0;
        for (QuestionRun r : answered) {
            InterviewScorer.RunScore rs = InterviewScorer.scoreRun(kb, r);
            if (rs == null) continue;
            number++;
            CompiledQuestion cq = r.cq;

            List<InterviewDetails.ConceptDetail> concepts = new ArrayList<>();
            for (int p = 0; p < cq.poolSize(); p++) {
                concepts.add(new InterviewDetails.ConceptDetail(kb.label(cq.poolIds()[p]), cq.poolW()[p], cq.core()[p], r.hits[p]));
            }
            List<InterviewDetails.FollowUpInfo> fus = new ArrayList<>();
            for (int j = 0; j < cq.followUps().length; j++) {
                fus.add(new InterviewDetails.FollowUpInfo(cq.followUps()[j].text(), r.followUpAsked[j]));
            }
            List<String> terms = new ArrayList<>();
            List<String> slang = new ArrayList<>();
            for (int id : rs.glossaryIds()) {
                (kb.kindOf(id) == InterviewKnowledge.SLANG ? slang : terms).add(kb.label(id));
                (kb.kindOf(id) == InterviewKnowledge.SLANG ? slangAll : termsAll).add(id);
            }

            List<InterviewDetails.AttemptDetail> attempts = new ArrayList<>();
            int fu = 0;
            for (int i = 0; i < r.attempts.size(); i++) {
                Attempt a = r.attempts.get(i);
                boolean isFu = i > 0;
                if (isFu) fu++;
                AnswerTiming t = a.timing;
                int w = a.analysis == null ? 0 : a.analysis.words();
                Double wpm = (a.answered && w > 0 && t.speechMs() >= 2000) ? w / (t.speechMs() / 60000.0) : null;
                double sp = a.answered ? InterviewScorer.speedOf(w, t) : 0;
                String src = a.rank >= 2 ? "Уточнённый STT" : a.rank == 1 ? "STT" : "STT (предварительный)";
                attempts.add(new InterviewDetails.AttemptDetail(isFu, isFu ? fu : 0, a.promptText,
                        a.display == null || a.display.isBlank() ? a.text : a.display, a.answered, src, w,
                        t.latencyMs(), t.speechMs(), wpm, sp, a.analysis == null ? 0 : a.analysis.coverage(),
                        a.action, a.reason, a.answeredAt));
            }
            out.add(new InterviewDetails.QuestionDetail(number, cq.level().title, cq.question().topic(),
                    cq.question().text(), cq.question().reference(), cq.question().notes(), fus, attempts, concepts,
                    rs.coverage(), rs.completeness(), rs.technical(), rs.terminology(), rs.speed(),
                    terms, slang, rs.hedge(), rs.words(), comment(r, rs)));
        }
        List<String> termNames = new ArrayList<>();
        for (int id : termsAll) termNames.add(kb.label(id));
        List<String> slangNames = new ArrayList<>();
        for (int id : slangAll) slangNames.add(kb.label(id));
        return new InterviewDetails(candidate, String.join(", ", levels), startedAt, java.time.Instant.now(),
                !finished, plan.size(), rep.answeredQuestions(), rep, out, termNames, slangNames,
                settings == null ? java.util.Map.of() : settings);
    }

    /** Короткий комментарий системы по вопросу: почему приняты такие решения и что бросилось в глаза. */
    private static String comment(QuestionRun r, InterviewScorer.RunScore rs) {
        List<String> parts = new ArrayList<>();
        Attempt lastAnswered = null;
        for (Attempt a : r.attempts) if (a.answered) lastAnswered = a;
        if (lastAnswered != null) parts.add("Итог: " + Reasons.text(lastAnswered.reason));
        if (r.followUpsUsed > 0) parts.add("уточняющих вопросов: " + r.followUpsUsed);
        Attempt first = r.attempts.get(0);
        if (first.answered && first.analysis != null && first.analysis.words() > 0 && first.timing.latencyMs() > 8000) {
            parts.add(String.format(java.util.Locale.ROOT, "долго начинал отвечать (%.1f с)", first.timing.latencyMs() / 1000.0));
        }
        if (rs.hedge() >= 4) parts.add("много слов-паразитов и неуверенных оборотов (" + rs.hedge() + ")");
        if (rs.last().ignorance()) parts.add("сказал, что не знает");
        return String.join("; ", parts);
    }

    public synchronized boolean isFinished() {
        return finished;
    }

    private Prompt prompt(QuestionRun r, boolean followUp, int n) {
        return new Prompt(r.cq.id(), pos + 1, plan.size(), r.cq.level().title, r.cq.question().topic(),
                r.cq.question().text(), followUp, n);
    }
}
