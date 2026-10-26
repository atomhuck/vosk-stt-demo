package ru.example.interview.export;

import ru.example.interview.engine.InterviewDetails;
import ru.example.interview.engine.InterviewDetails.AttemptDetail;
import ru.example.interview.engine.InterviewDetails.ConceptDetail;
import ru.example.interview.engine.InterviewDetails.QuestionDetail;
import ru.example.interview.engine.InterviewReport;
import ru.example.interview.engine.Level;
import ru.example.interview.engine.Reasons;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Excel-отчёт по собеседованию. Листы:
 *  1. «Сводка»   — итоговая оценка, 4 критерия (формулы), диаграммы, резюме, итоги по уровням и темам, частые пробелы;
 *  2. «Вопросы»  — по строке на вопрос: ответ кандидата, уточнения, что прозвучало / не прозвучало, оценки (фильтр, цвета);
 *  3. «Диалог»   — хронология: вопрос → ответ, тайминги, темп речи, источник текста, решение системы;
 *  4. «Понятия»  — каждое ключевое понятие каждого вопроса: прозвучало или нет (фильтр);
 *  5. «Эталоны»  — вопрос, эталонный ответ, критерии из банка, варианты уточнений;
 *  6. «Методика» — как считаются оценки и с какими настройками проходило интервью.
 * Итоговые оценки на «Сводке» — формулы над листом «Вопросы»: если интервьюер поправит оценку вручную, итоги пересчитаются.
 */
public final class InterviewExcelReport {

    private static final String NAVY = "1F3864", BLUE = "2F5597", LIGHT = "DDEBF7", GRAY = "F2F2F2", PALE = "EAF1FB";
    private static final String Q_SHEET = "Вопросы";

    // ----- стили -----
    private static final XlsxStyle TITLE = XlsxStyle.BASE.b().size(18).color("FFFFFF").fill(NAVY).v("center");
    private static final XlsxStyle SUB = XlsxStyle.BASE.i().color("595959");
    private static final XlsxStyle SECTION = XlsxStyle.BASE.b().size(12).color(NAVY).fill(LIGHT).v("center");
    private static final XlsxStyle HDR = XlsxStyle.BASE.b().color("FFFFFF").fill(BLUE).bd().h("center").v("center").w();
    private static final XlsxStyle LABEL = XlsxStyle.BASE.b().fill(GRAY).bd().v("center");
    private static final XlsxStyle TEXT = XlsxStyle.BASE.bd().w();
    private static final XlsxStyle TEXT_NOWRAP = XlsxStyle.BASE.bd().v("center");
    private static final XlsxStyle TEXT_C = XlsxStyle.BASE.bd().w().h("center");
    private static final XlsxStyle NUM2 = XlsxStyle.BASE.bd().fmt("0.00").h("center");
    private static final XlsxStyle PCT = XlsxStyle.BASE.bd().fmt("0%").h("center");
    private static final XlsxStyle INT = XlsxStyle.BASE.bd().fmt("0").h("center");
    private static final XlsxStyle NUM1 = XlsxStyle.BASE.bd().fmt("0.0").h("center");
    private static final XlsxStyle BIG = XlsxStyle.BASE.b().size(40).color(NAVY).fill(PALE).h("center").v("center").fmt("0.00").bd();
    private static final XlsxStyle BOX_LABEL = XlsxStyle.BASE.b().size(10).color("FFFFFF").fill(BLUE).h("center").v("center").bd();
    private static final XlsxStyle VERDICT = XlsxStyle.BASE.b().size(14).color(NAVY).fill(PALE).h("center").v("center").bd();
    private static final XlsxStyle BAR = XlsxStyle.BASE.size(12).color(BLUE).bd().v("center");
    private static final XlsxStyle SUMMARY = XlsxStyle.BASE.size(11).w().bd().fill("FFFBEA").v("top");
    private static final XlsxStyle SAYER_Q = XlsxStyle.BASE.bd().w().fill(LIGHT);
    private static final XlsxStyle SAYER_A = XlsxStyle.BASE.bd().w().fill("E2EFDA");
    private static final XlsxStyle MUTED = XlsxStyle.BASE.bd().w().i().color("7F7F7F");

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.ROOT);
    private static final DateTimeFormatter TM = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private InterviewExcelReport() {}

    public static byte[] build(InterviewDetails d) throws IOException {
        XlsxBook book = new XlsxBook();
        int n = d.questions().size();
        XlsxSheet summary = book.sheet("Сводка");
        XlsxSheet questions = book.sheet(Q_SHEET);
        XlsxSheet dialog = book.sheet("Диалог");
        XlsxSheet concepts = book.sheet("Понятия");
        XlsxSheet refs = book.sheet("Эталоны");
        XlsxSheet method = book.sheet("Методика");

        double[] totals = new double[n];
        questionsSheet(questions, d, totals);
        summarySheet(summary, d, totals);
        dialogSheet(dialog, d);
        conceptsSheet(concepts, d);
        refsSheet(refs, d);
        methodSheet(method, d);
        return book.toBytes();
    }

    // ================================================================== Вопросы

    private static final int QC_NUM = 0, QC_Q = 1, QC_LEVEL = 2, QC_TOPIC = 3, QC_ANS = 4, QC_FU = 5, QC_COV = 6,
            QC_SAID = 7, QC_MISS = 8, QC_TERMS = 9, QC_COMP = 10, QC_TECH = 11, QC_TERM = 12, QC_SPEED = 13,
            QC_TOTAL = 14, QC_COMMENT = 15;

    private static void questionsSheet(XlsxSheet s, InterviewDetails d, double[] totals) {
        double[] w = {5, 42, 11, 16, 62, 62, 10, 34, 34, 26, 9.5, 9.5, 9.5, 9.5, 10, 42};
        String[] head = {"№", "Вопрос", "Уровень", "Тема", "Ответ кандидата", "Уточнения: вопрос → ответ",
                "Покрытие эталона", "Прозвучало (★ — ядро)", "Не прозвучало (★ — ядро)", "IT-термины и сленг",
                "Полнота", "Техника", "Термины", "Скорость", "Итог по вопросу", "Комментарий системы"};
        for (int c = 0; c < w.length; c++) s.width(c, w[c]);
        for (int c = 0; c < head.length; c++) s.set(0, c, head[c], HDR);
        s.height(0, 34);
        s.tabColor(BLUE).freeze(1, 2).repeatHeaderRow();

        int r = 1;
        for (int i = 0; i < d.questions().size(); i++, r++) {
            QuestionDetail q = d.questions().get(i);
            AttemptDetail main = q.attempts().get(0);
            String answer = main.answered() ? orDash(main.answer()) : "— нет ответа —";
            StringBuilder fu = new StringBuilder();
            int k = 0;
            for (AttemptDetail a : q.attempts()) {
                if (!a.followUp()) continue;
                if (fu.length() > 0) fu.append("\n\n");
                k++;
                fu.append("Уточнение ").append(k).append(": ").append(a.prompt()).append("\n→ ")
                        .append(a.answered() ? orDash(a.answer()) : "— нет ответа —");
            }
            String said = conceptList(q.concepts(), true);
            String miss = conceptList(q.concepts(), false);
            String terms = joinTerms(q);
            double total = r2((q.completeness() + q.technical() + q.terminology() + q.speed()) / 4.0);
            totals[i] = total;

            s.set(r, QC_NUM, q.number(), INT).set(r, QC_LEVEL, q.level(), TEXT_C).set(r, QC_TOPIC, q.topic(), TEXT)
                    .set(r, QC_Q, q.question(), TEXT).set(r, QC_ANS, answer, main.answered() && !main.answer().isBlank() ? TEXT : MUTED)
                    .set(r, QC_FU, fu.length() == 0 ? "—" : fu.toString(), fu.length() == 0 ? MUTED : TEXT)
                    .set(r, QC_COV, q.coverage(), PCT).set(r, QC_SAID, said, TEXT).set(r, QC_MISS, miss, TEXT)
                    .set(r, QC_TERMS, terms, TEXT)
                    .set(r, QC_COMP, q.completeness(), NUM2).set(r, QC_TECH, q.technical(), NUM2)
                    .set(r, QC_TERM, q.terminology(), NUM2).set(r, QC_SPEED, q.speed(), NUM2)
                    .formula(r, QC_TOTAL, "ROUND(AVERAGE(" + XlsxSheet.addr(r, QC_COMP) + ":" + XlsxSheet.addr(r, QC_SPEED) + "),2)",
                            total, NUM2.b())
                    .set(r, QC_COMMENT, q.comment(), TEXT);
            s.height(r, rowHeight(new String[]{q.topic(), q.question(), answer, fu.toString(), said, miss, terms, q.comment()},
                    new double[]{w[3], w[1], w[4], w[5], w[7], w[8], w[9], w[15]}));
        }
        int last = Math.max(1, d.questions().size());
        s.filter(0, 0, last, head.length - 1);
        if (!d.questions().isEmpty()) {
            s.colorScale(1, QC_COMP, last, QC_TOTAL, 0, 5, 10);
            s.colorScale(1, QC_COV, last, QC_COV, 0, 0.4, 1);
        }
    }

    // ================================================================== Сводка

    private static void summarySheet(XlsxSheet s, InterviewDetails d, double[] totals) {
        InterviewReport rep = d.report();
        int n = d.questions().size();
        double[] w = {34, 13, 13, 13, 13, 17, 17, 17};
        for (int c = 0; c < w.length; c++) s.width(c, w[c]);
        s.hideGrid().tabColor(NAVY);

        s.merge(0, 0, 0, 7, "Отчёт по собеседованию — Java Backend", TITLE).height(0, 34);
        s.merge(1, 0, 1, 7, "Автоматическая оценка ответов по эталонам банка вопросов · файл создан "
                + DT.format(d.finishedAt().atZone(ZoneId.systemDefault())), SUB);

        // --- карточка с данными ---
        String[][] info = {
                {"Кандидат", d.candidate().isBlank() ? "не указан" : d.candidate()},
                {"Дата и время начала", DT.format(d.startedAt().atZone(ZoneId.systemDefault()))},
                {"Длительность", duration(Duration.between(d.startedAt(), d.finishedAt()))},
                {"Уровни вопросов", d.levels()},
                {"Вопросов", "отвечено " + d.answeredQuestions() + " из " + d.plannedQuestions()},
                {"Завершение", d.finishedEarly() ? "Досрочно — остановлено вручную" : "Полностью — пройден весь список"},
        };
        int r = 3;
        for (String[] row : info) {
            s.set(r, 0, row[0], LABEL);
            s.merge(r, 1, r, 4, row[1], TEXT_NOWRAP);
            r++;
        }
        int slangRow = r;       // r == 9
        s.set(slangRow, 0, "Слов сленга (различных)", LABEL);
        s.set(slangRow, 1, d.slangUsed().size(), INT);
        s.merge(slangRow, 2, slangRow, 4, d.slangUsed().isEmpty() ? "—" : String.join(", ", d.slangUsed()), TEXT_NOWRAP);

        // --- итоговый «бейдж» ---
        s.merge(3, 5, 3, 7, "ИТОГОВАЯ ОЦЕНКА (из 10)", BOX_LABEL);
        int critHeader = 12;
        int critFirst = critHeader + 1;
        int totalRow = critFirst + 4;
        String totalCell = XlsxSheet.addr(totalRow, 1);
        s.mergeFormula(4, 5, 7, 7, n > 0 ? totalCell : "0", rep.total(), BIG);
        s.mergeFormula(8, 5, 8, 7, "IF(" + XlsxSheet.addr(4, 5) + ">=8.5,\"Отлично\",IF(" + XlsxSheet.addr(4, 5)
                + ">=7,\"Хорошо\",IF(" + XlsxSheet.addr(4, 5) + ">=5,\"Удовлетворительно\",IF(" + XlsxSheet.addr(4, 5)
                + ">=3,\"Слабо\",\"Недостаточно\"))))", rep.verdict(), VERDICT);
        s.colorScale(4, 5, 7, 5, 0, 5, 10);
        for (int rr = 4; rr <= 8; rr++) s.height(rr, rr == 4 || rr == 5 ? 24 : 22);

        // --- критерии ---
        s.merge(critHeader - 1, 0, critHeader - 1, 4, "Оценки по критериям (шкала 0–10)", SECTION).height(critHeader - 1, 22);
        s.set(critHeader, 0, "Критерий", HDR).set(critHeader, 1, "Балл", HDR);
        s.merge(critHeader, 2, critHeader, 4, "Шкала", HDR);
        String[] names = {"Развёрнутость и полнота ответа", "Техническая грамотность", "Владение IT-терминами и сленгом", "Скорость ответа"};
        double[] vals = {rep.completeness(), rep.technical(), rep.terminology(), rep.speed()};
        int[] cols = {QC_COMP, QC_TECH, QC_TERM, QC_SPEED};
        int last = n + 1;     // последняя строка данных на листе «Вопросы» (1-based)
        for (int i = 0; i < 4; i++) {
            int row = critFirst + i;
            String range = "'" + Q_SHEET + "'!" + XlsxSheet.col(cols[i]) + "2:" + XlsxSheet.col(cols[i]) + last;
            String f;
            if (i == 2) {
                f = "ROUND(MIN(10,0.8*AVERAGE(" + range + ")+2*MIN(1," + XlsxSheet.addr(slangRow, 1) + "/3)),2)";
            } else {
                f = "ROUND(AVERAGE(" + range + "),2)";
            }
            s.set(row, 0, names[i], TEXT_NOWRAP);
            if (n > 0) s.formula(row, 1, f, vals[i], NUM2.b()); else s.set(row, 1, 0, NUM2.b());
            int filled = (int) Math.round(Math.max(0, Math.min(10, vals[i])));
            String barCached = "█".repeat(filled) + "░".repeat(10 - filled);
            String barF = "REPT(\"█\",ROUND(" + XlsxSheet.addr(row, 1) + ",0))&REPT(\"░\",10-ROUND(" + XlsxSheet.addr(row, 1) + ",0))";
            s.mergeFormula(row, 2, row, 4, barF, barCached, BAR);
            s.height(row, 22);
        }
        s.colorScale(critFirst, 1, critFirst + 3, 1, 0, 5, 10);
        s.set(totalRow, 0, "Итог (среднее по 4 критериям)", LABEL.fill(LIGHT));
        s.formula(totalRow, 1, "ROUND(AVERAGE(" + XlsxSheet.addr(critFirst, 1) + ":" + XlsxSheet.addr(critFirst + 3, 1) + "),2)",
                rep.total(), NUM2.b().fill(LIGHT));
        s.merge(totalRow, 2, totalRow, 4, "из 10 · среднее четырёх критериев", XlsxStyle.BASE.i().color("595959").fill(LIGHT).bd().v("center"));
        s.height(totalRow, 22);

        // диаграмма критериев справа от таблицы
        s.chart(new XlsxChart("Оценки по критериям", true, "Сводка", critFirst, critFirst + 3, 0, 1,
                List.of(names),
                List.of(vals[0], vals[1], vals[2], vals[3]), 10, BLUE, "0.00"), critHeader - 1, 5, totalRow, 7);

        // --- резюме ---
        int sumRow = totalRow + 2;
        s.merge(sumRow, 0, sumRow, 7, "Резюме", SECTION).height(sumRow, 22);
        String text = summaryText(d);
        int sumLines = 7;
        s.merge(sumRow + 1, 0, sumRow + sumLines, 7, text, SUMMARY);
        for (int i = 1; i <= sumLines; i++) s.height(sumRow + i, 21);

        // --- по уровням ---
        int levelRow = sumRow + sumLines + 2;
        s.merge(levelRow, 0, levelRow, 4, "Результаты по уровням вопросов", SECTION).height(levelRow, 22);
        s.set(levelRow + 1, 0, "Уровень", HDR).set(levelRow + 1, 1, "Вопросов", HDR).set(levelRow + 1, 2, "Покрытие", HDR)
                ;
        s.merge(levelRow + 1, 3, levelRow + 1, 4, "Итог", HDR);
        Map<String, List<Integer>> byLevel = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Level lv : Level.values()) order.add(lv.title);
        for (String lv : order) byLevel.put(lv, new ArrayList<>());
        for (int i = 0; i < n; i++) byLevel.computeIfAbsent(d.questions().get(i).level(), k -> new ArrayList<>()).add(i);
        int lr = levelRow + 2;
        int firstLevelRow = lr;
        String levelRange = qRange(QC_LEVEL, last);
        for (var e : byLevel.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            double cov = 0, tot = 0;
            for (int i : e.getValue()) {
                cov += d.questions().get(i).coverage();
                tot += totals[i];
            }
            cov /= e.getValue().size();
            tot /= e.getValue().size();
            s.set(lr, 0, e.getKey(), TEXT_NOWRAP);
            s.formula(lr, 1, "COUNTIF(" + levelRange + "," + XlsxSheet.addr(lr, 0) + ")", e.getValue().size(), INT);
            s.formula(lr, 2, "IFERROR(AVERAGEIFS(" + qRange(QC_COV, last) + "," + levelRange + "," + XlsxSheet.addr(lr, 0) + "),0)", cov, PCT);
            s.mergeFormula(lr, 3, lr, 4, "IFERROR(AVERAGEIFS(" + qRange(QC_TOTAL, last) + "," + levelRange + "," + XlsxSheet.addr(lr, 0) + "),0)", r2(tot), NUM2);
            lr++;
        }
        if (lr > firstLevelRow) {
            s.colorScale(firstLevelRow, 3, lr - 1, 3, 0, 5, 10);
            s.colorScale(firstLevelRow, 2, lr - 1, 2, 0, 0.4, 1);
        }

        // --- чаще всего не прозвучало ---
        s.merge(levelRow, 5, levelRow, 7, "Чаще всего не прозвучало", SECTION);
        s.merge(levelRow + 1, 5, levelRow + 1, 7, "ключевое понятие — в скольких вопросах", HDR);
        List<Map.Entry<String, Integer>> gaps = topGaps(d, 8);
        for (int i = 0; i < 8; i++) {
            int row = levelRow + 2 + i;
            if (i < gaps.size()) {
                s.merge(row, 5, row, 7, gaps.get(i).getKey() + " — " + gaps.get(i).getValue(), TEXT_NOWRAP);
            } else {
                s.merge(row, 5, row, 7, i == 0 ? "пробелов в ключевых понятиях нет" : "", TEXT_NOWRAP);
            }
        }

        // --- по темам ---
        int topicRow = Math.max(lr, levelRow + 10) + 1;
        s.merge(topicRow, 0, topicRow, 4, "Результаты по темам (от слабых к сильным)", SECTION).height(topicRow, 22);
        s.set(topicRow + 1, 0, "Тема", HDR).set(topicRow + 1, 1, "Вопросов", HDR).set(topicRow + 1, 2, "Покрытие", HDR)
                ;
        s.merge(topicRow + 1, 3, topicRow + 1, 4, "Итог", HDR);
        List<TopicStat> topics = topicStats(d, totals);
        int tr = topicRow + 2;
        int firstTopic = tr;
        String topicRange = qRange(QC_TOPIC, last);
        List<String> tCats = new ArrayList<>();
        List<Double> tVals = new ArrayList<>();
        for (TopicStat t : topics) {
            s.set(tr, 0, t.topic, TEXT_NOWRAP);
            s.formula(tr, 1, "COUNTIF(" + topicRange + "," + XlsxSheet.addr(tr, 0) + ")", t.count, INT);
            s.formula(tr, 2, "IFERROR(AVERAGEIFS(" + qRange(QC_COV, last) + "," + topicRange + "," + XlsxSheet.addr(tr, 0) + "),0)", t.coverage, PCT);
            s.mergeFormula(tr, 3, tr, 4, "IFERROR(AVERAGEIFS(" + qRange(QC_TOTAL, last) + "," + topicRange + "," + XlsxSheet.addr(tr, 0) + "),0)", r2(t.total), NUM2);
            tCats.add(t.topic);
            tVals.add(r2(t.total));
            tr++;
        }
        if (!topics.isEmpty()) {
            s.colorScale(firstTopic, 3, tr - 1, 3, 0, 5, 10);
            s.colorScale(firstTopic, 2, tr - 1, 2, 0, 0.4, 1);
            // горизонтальная диаграмма по темам — справа от таблицы
            int chartRows = Math.max(12, topics.size() + 4);
            s.chart(new XlsxChart("Итог по темам (0–10)", true, "Сводка", firstTopic, tr - 1, 0, 3, tCats, tVals, 10, BLUE, "0.0"),
                    topicRow, 5, topicRow + chartRows, 7);
        }
    }

    /** Абсолютный диапазон столбца листа «Вопросы» (строки 2..last). */
    private static String qRange(int col, int last) {
        return "'" + Q_SHEET + "'!$" + XlsxSheet.col(col) + "$2:$" + XlsxSheet.col(col) + "$" + last;
    }

    private record TopicStat(String topic, int count, double coverage, double total) {}

    private static List<TopicStat> topicStats(InterviewDetails d, double[] totals) {
        Map<String, double[]> m = new LinkedHashMap<>();     // {count, sumCoverage, sumTotal}
        for (int i = 0; i < d.questions().size(); i++) {
            QuestionDetail q = d.questions().get(i);
            double[] a = m.computeIfAbsent(q.topic(), k -> new double[3]);
            a[0]++;
            a[1] += q.coverage();
            a[2] += totals[i];
        }
        List<TopicStat> out = new ArrayList<>();
        m.forEach((k, a) -> out.add(new TopicStat(k, (int) a[0], a[1] / a[0], a[2] / a[0])));
        out.sort(Comparator.comparingDouble(TopicStat::total).thenComparing(TopicStat::topic));
        return out;
    }

    /** Ключевые понятия «ядра», которые чаще всего не прозвучали. */
    private static List<Map.Entry<String, Integer>> topGaps(InterviewDetails d, int limit) {
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (QuestionDetail q : d.questions()) {
            for (ConceptDetail c : q.concepts()) {
                if (c.core() && !c.said()) cnt.merge(c.label(), 1, Integer::sum);
            }
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>(cnt.entrySet());
        list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    private static String summaryText(InterviewDetails d) {
        InterviewReport rep = d.report();
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "• Итоговая оценка %s из 10 — «%s». Отвечено %d из %d вопросов%s.", rep.totalText(),
                rep.verdict(), d.answeredQuestions(), d.plannedQuestions(), d.finishedEarly() ? " (интервью остановлено досрочно)" : ""));

        double[] totals = new double[d.questions().size()];
        for (int i = 0; i < totals.length; i++) {
            QuestionDetail q = d.questions().get(i);
            totals[i] = r2((q.completeness() + q.technical() + q.terminology() + q.speed()) / 4.0);
        }
        List<TopicStat> topics = topicStats(d, totals);
        if (topics.size() >= 2) {
            int k = Math.min(2, topics.size() / 2);
            lines.add("• Сильнее всего: " + joinTopics(topics.subList(topics.size() - k, topics.size()), true)
                    + ". Слабее всего: " + joinTopics(topics.subList(0, k), false) + ".");
        } else if (topics.size() == 1) {
            lines.add("• Тема: " + topics.get(0).topic + " (итог " + String.format(Locale.ROOT, "%.2f", topics.get(0).total) + ").");
        }

        String[] names = {"развёрнутость и полнота", "техническая грамотность", "IT-термины и сленг", "скорость ответа"};
        double[] v = {rep.completeness(), rep.technical(), rep.terminology(), rep.speed()};
        int lo = 0, hi = 0;
        for (int i = 1; i < 4; i++) {
            if (v[i] < v[lo]) lo = i;
            if (v[i] > v[hi]) hi = i;
        }
        lines.add(String.format(Locale.ROOT, "• Лучший критерий — %s (%.2f), самый низкий — %s (%.2f).", names[hi], v[hi], names[lo], v[lo]));

        double lat = 0, wpm = 0;
        int latN = 0, wpmN = 0;
        for (QuestionDetail q : d.questions()) {
            for (AttemptDetail a : q.attempts()) {
                if (!a.answered() || a.words() == 0) continue;
                lat += a.latencyMs();
                latN++;
                if (a.wpm() != null) {
                    wpm += a.wpm();
                    wpmN++;
                }
            }
        }
        if (latN > 0) {
            lines.add(String.format(Locale.ROOT, "• Скорость: до начала речи кандидат в среднем думал %.1f с%s.", lat / latN / 1000.0,
                    wpmN > 0 ? String.format(Locale.ROOT, "; темп речи — около %d слов/мин", Math.round(wpm / wpmN)) : ""));
        }
        int nt = d.termsUsed().size(), ns = d.slangUsed().size();
        lines.add("• Терминология: использовано " + nt + " " + plural(nt, "различный IT-термин", "различных IT-термина", "различных IT-терминов")
                + (ns == 0 ? ", сленг не встретился" : " и " + ns + " " + plural(ns, "слово сленга", "слова сленга", "слов сленга")) + ".");
        List<Map.Entry<String, Integer>> gaps = topGaps(d, 3);
        if (!gaps.isEmpty()) {
            List<String> g = new ArrayList<>();
            for (var e : gaps) g.add(e.getKey());
            lines.add("• Чаще всего не прозвучало: " + String.join(", ", g) + ".");
        }
        lines.add("• Оценка автоматическая и лексическая: она не проверяет правильность сказанного. Решающее слово — за интервьюером.");
        return String.join("\n", lines);
    }

    private static String joinTopics(List<TopicStat> t, boolean desc) {
        List<TopicStat> l = new ArrayList<>(t);
        if (desc) java.util.Collections.reverse(l);
        List<String> out = new ArrayList<>();
        for (TopicStat x : l) out.add(x.topic + " (" + String.format(Locale.ROOT, "%.2f", x.total) + ")");
        return String.join(", ", out);
    }

    // ================================================================== Диалог

    private static void dialogSheet(XlsxSheet s, InterviewDetails d) {
        double[] w = {10, 13, 17, 90, 8, 13, 11, 11, 15, 12, 40};
        String[] head = {"Время", "Кто", "Тип", "Текст", "Слов", "До начала речи, с", "Речь, с", "Темп, слов/мин",
                "Источник текста", "Покрытие после ответа", "Решение системы"};
        for (int c = 0; c < w.length; c++) s.width(c, w[c]);
        for (int c = 0; c < head.length; c++) s.set(0, c, head[c], HDR);
        s.height(0, 34);
        s.tabColor("548235").freeze(1, 0).repeatHeaderRow();

        int r = 1;
        java.time.Instant last = d.startedAt();
        for (QuestionDetail q : d.questions()) {
            for (AttemptDetail a : q.attempts()) {
                String type = a.followUp() ? "Уточнение " + a.followUpNumber() : "Вопрос " + q.number();
                s.set(r, 0, TM.format(last.atZone(ZoneId.systemDefault())), TEXT_C).set(r, 1, "Интервьюер", SAYER_Q)
                        .set(r, 2, type, SAYER_Q).set(r, 3, a.prompt(), SAYER_Q);
                for (int c = 4; c <= 10; c++) s.set(r, c, "", SAYER_Q);
                s.height(r, rowHeight(new String[]{a.prompt()}, new double[]{w[3]}));
                r++;
                if (!a.answered()) {
                    s.set(r, 0, "", TEXT_C).set(r, 1, "Кандидат", SAYER_A).set(r, 2, "Ответ", SAYER_A)
                            .set(r, 3, "— интервью завершено до ответа —", MUTED);
                    for (int c = 4; c <= 10; c++) s.set(r, c, "", SAYER_A);
                    r++;
                    continue;
                }
                String ans = a.answer().isBlank() ? "— ответа не было —" : a.answer();
                if (a.answeredAt() != null) last = a.answeredAt();
                s.set(r, 0, a.answeredAt() == null ? "" : TM.format(a.answeredAt().atZone(ZoneId.systemDefault())), TEXT_C)
                        .set(r, 1, "Кандидат", SAYER_A).set(r, 2, "Ответ", SAYER_A)
                        .set(r, 3, ans, a.answer().isBlank() ? MUTED : SAYER_A)
                        .set(r, 4, a.words(), INT).set(r, 5, a.latencyMs() / 1000.0, NUM1).set(r, 6, a.speechMs() / 1000.0, NUM1);
                if (a.wpm() != null) s.set(r, 7, Math.round(a.wpm()), INT); else s.set(r, 7, "—", TEXT_C);
                s.set(r, 8, a.source(), TEXT_C).set(r, 9, a.coverageAfter(), PCT)
                        .set(r, 10, "→ " + Reasons.action(a.action()) + ": " + Reasons.text(a.reason()), TEXT);
                s.height(r, rowHeight(new String[]{ans, "→ " + Reasons.action(a.action()) + ": " + Reasons.text(a.reason())},
                        new double[]{w[3], w[10]}));
                r++;
            }
        }
    }

    // ================================================================== Понятия

    private static void conceptsSheet(XlsxSheet s, InterviewDetails d) {
        double[] w = {6, 18, 34, 9, 9, 12};
        String[] head = {"№", "Тема", "Ключевое понятие", "Вес", "Ядро", "Прозвучало"};
        for (int c = 0; c < w.length; c++) s.width(c, w[c]);
        for (int c = 0; c < head.length; c++) s.set(0, c, head[c], HDR);
        s.height(0, 30);
        s.tabColor("BF8F00").freeze(1, 0).repeatHeaderRow();
        int r = 1;
        for (QuestionDetail q : d.questions()) {
            for (ConceptDetail c : q.concepts()) {
                s.set(r, 0, q.number(), INT).set(r, 1, q.topic(), TEXT_NOWRAP).set(r, 2, c.label(), TEXT_NOWRAP)
                        .set(r, 3, c.weight(), NUM1).set(r, 4, c.core() ? "Да" : "Нет", TEXT_C)
                        .set(r, 5, c.said() ? "Да" : "Нет", TEXT_C);
                r++;
            }
        }
        s.filter(0, 0, Math.max(1, r - 1), 5);
        if (r > 1) {
            s.cellEquals(1, 5, r - 1, 5, "Да", "006100", "C6EFCE").cellEquals(1, 5, r - 1, 5, "Нет", "9C0006", "FFC7CE");
            s.cellEquals(1, 4, r - 1, 4, "Да", "7F6000", "FFF2CC");
        }
    }

    // ================================================================== Эталоны

    private static void refsSheet(XlsxSheet s, InterviewDetails d) {
        double[] w = {5, 11, 16, 40, 70, 40, 70};
        String[] head = {"№", "Уровень", "Тема", "Вопрос", "Эталонный ответ", "Критерии из банка вопросов", "Уточняющие вопросы (✓ — задавался)"};
        for (int c = 0; c < w.length; c++) s.width(c, w[c]);
        for (int c = 0; c < head.length; c++) s.set(0, c, head[c], HDR);
        s.height(0, 34);
        s.tabColor("7030A0").freeze(1, 0).repeatHeaderRow();
        int r = 1;
        for (QuestionDetail q : d.questions()) {
            StringBuilder fu = new StringBuilder();
            int i = 0;
            for (var f : q.followUps()) {
                i++;
                if (fu.length() > 0) fu.append("\n");
                fu.append(f.asked() ? "✓ " : "   ").append(i).append(". ").append(f.text());
            }
            s.set(r, 0, q.number(), INT).set(r, 1, q.level(), TEXT_C).set(r, 2, q.topic(), TEXT).set(r, 3, q.question(), TEXT)
                    .set(r, 4, q.reference(), TEXT).set(r, 5, orDash(q.criteria()), TEXT).set(r, 6, fu.toString(), TEXT);
            s.height(r, rowHeight(new String[]{q.topic(), q.question(), q.reference(), q.criteria(), fu.toString()},
                    new double[]{w[2], w[3], w[4], w[5], w[6]}));
            r++;
        }
        s.filter(0, 0, Math.max(1, r - 1), 6);
    }

    // ================================================================== Методика

    private static void methodSheet(XlsxSheet s, InterviewDetails d) {
        s.width(0, 34).width(1, 120).tabColor("7F7F7F");
        s.merge(0, 0, 0, 1, "Как читать отчёт и как считаются оценки", TITLE).height(0, 30);
        int r = 2;
        String[][] rows = {
                {"Общий принцип", "Каждый ответ сравнивается с эталоном из банка вопросов: ищутся ключевые понятия эталона (термины и значимые слова, "
                        + "взвешенные по редкости) и их синонимы из словаря. Оценка лексическая — она не проверяет, что сказанное верно."},
                {"Шкала", "Каждый критерий — от 0 до 10. Итог = (критерий 1 + 2 + 3 + 4) / 4, то есть сумма четырёх критериев, нормированная к максимуму 10, "
                        + "округление до 0,01. На листе «Сводка» это формулы над листом «Вопросы»."},
                {"1. Развёрнутость и полнота", "10 × (0,75 × покрытие эталона + 0,25 × длина ответа относительно целевой). Покрытие — доля (по весу) "
                        + "ключевых понятий эталона, которые прозвучали в ответе и в уточнениях."},
                {"2. Техническая грамотность", "10 × (0,6 × покрытие «ядра» понятий + 0,25 × доля технических слов по теме вопроса + 0,15 × покрытие) × "
                        + "(1 − штраф за слова-паразиты и неуверенные обороты, до 40 %)."},
                {"3. IT-термины и сленг", "По вопросу: 10 × (0,55 × разнообразие терминов относительно ожидаемого + 0,45 × плотность терминов на 100 слов, цель 12). "
                        + "По интервью: 80 % среднего + 20 % за разнообразие сленга (цель — 3 разных слова: деплой, прод, баг, рефакторинг...)."},
                {"4. Скорость ответа", "0,6 × быстрота начала ответа (до 1,5 с — без штрафа, к 19,5 с оценка падает до нуля) + 0,4 × темп речи "
                        + "(90–180 слов/мин — идеально). Умножается на коэффициент содержательности (0,3–1), чтобы быстрый «не знаю» не давал баллов."},
                {"Решение «уточнить / дальше»", "Уточнение задаётся, если покрытие ниже порога уровня, лимит уточнений не исчерпан, кандидат не сказал «не знаю» "
                        + "и уточнение выбирается из банка так, чтобы бить в ещё не названные понятия."},
                {"Столбцы «Прозвучало / Не прозвучало»", "Ключевые понятия эталона; ★ — «ядро» (самые весомые). Подробно по каждому понятию — лист «Понятия» (с фильтром)."},
                {"Источник текста", "Первичный текст и решение поступают из STT; уточнённый текст STT может обновить итоговую оценку "
                        + "(если включён). В итоговой оценке используется самый точный из доступных."},
                {"Ограничения", "Парафраз и редкие синонимы могут быть недооценены; ошибки в формулировках не обнаруживаются; английские термины Vosk слышит неточно "
                        + "(частично исправляется автоматически). Автоматическая оценка — подсказка, а не вердикт."},
        };
        for (String[] row : rows) {
            s.set(r, 0, row[0], LABEL.w());
            s.set(r, 1, row[1], TEXT);
            s.height(r, rowHeight(new String[]{row[0], row[1]}, new double[]{34, 120}));
            r++;
        }
        r++;
        s.merge(r, 0, r, 1, "Пороги достаточности ответа по уровням", SECTION).height(r, 22);
        r++;
        for (Level lv : Level.values()) {
            s.set(r, 0, lv.title, LABEL);
            s.set(r, 1, String.format(Locale.ROOT, "ответ достаточный при покрытии эталона от %d %%", Math.round(lv.sufficientCoverage * 100)), TEXT);
            r++;
        }
        r++;
        s.merge(r, 0, r, 1, "Настройки этого интервью", SECTION).height(r, 22);
        r++;
        for (var e : d.settings().entrySet()) {
            s.set(r, 0, e.getKey(), LABEL.w());
            s.set(r, 1, e.getValue(), TEXT);
            r++;
        }
    }

    // ================================================================== утилиты

    private static String conceptList(List<ConceptDetail> concepts, boolean said) {
        List<ConceptDetail> list = new ArrayList<>();
        for (ConceptDetail c : concepts) if (c.said() == said) list.add(c);
        list.sort(Comparator.comparingDouble(ConceptDetail::weight).reversed());
        List<String> out = new ArrayList<>();
        int shown = 0;
        for (ConceptDetail c : list) {
            if (shown++ >= 10) break;
            out.add((c.core() ? "★ " : "") + c.label());
        }
        String more = list.size() > 10 ? " … и ещё " + (list.size() - 10) + " (см. лист «Понятия»)" : "";
        return out.isEmpty() ? "—" : String.join(", ", out) + more;
    }

    private static String joinTerms(QuestionDetail q) {
        List<String> out = new ArrayList<>(q.termsUsed());
        for (String sl : q.slangUsed()) out.add(sl + " (сленг)");
        return out.isEmpty() ? "—" : String.join(", ", out);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String duration(Duration d) {
        long sec = Math.max(0, d.getSeconds());
        return (sec / 60) + " мин " + (sec % 60) + " с";
    }

    static String plural(long n, String one, String few, String many) {
        long m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    private static double r2(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** Высота строки по самому «длинному» столбцу: сколько строк займёт текст при данной ширине столбца. */
    static double rowHeight(String[] texts, double[] widths) {
        int maxLines = 1;
        for (int i = 0; i < texts.length; i++) {
            String t = texts[i];
            if (t == null || t.isEmpty()) continue;
            double perLine = Math.max(4, widths[i] * 1.05);
            int lines = 0;
            for (String p : t.split("\n", -1)) lines += Math.max(1, (int) Math.ceil(p.length() / perLine));
            maxLines = Math.max(maxLines, lines);
        }
        return Math.min(409, Math.max(20, maxLines * 13.5 + 6));
    }
}
