package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Сверяет готовую диаризацию с голосовым эталоном и сохраняет JSON/CSV.
 *
 * @version 1.0.0
 */
final class OfflineVerify {
    private static final String[] CSV_FIELDS = {"start", "end", "duration", "label", "candidate_similarity",
            "interviewer_similarity", "source_speaker_id", "confidence", "decision"};

    /** Изменяемый интервал для проверки и устранения неоднозначных наложений.
     *
     * @version 1.0.0
     */
    private static final class Interval {
        double start, end;
        String source, label = "", confidence = "низкая", decision = "";
        float[] embedding;
        Double candidateSimilarity, interviewerSimilarity;
        Integer clusterIndex;
        boolean overlapped;
        Interval(double start, double end, String source) { this.start = start; this.end = end; this.source = source; }
        double duration() { return Math.max(0, end - start); }
    }

    /** Группа эмбеддингов одного голоса.
     *
     * @version 1.0.0
     */
    private static final class Cluster {
        final List<float[]> embeddings = new ArrayList<>();
        double firstStart;
        Cluster(float[] initial, double firstStart) { embeddings.add(initial); this.firstStart = firstStart; }
        double similarity(float[] embedding) { return Support.dot(embedding, Support.meanPrototype(embeddings)); }
    }

    private final Map<String, String> options;
    OfflineVerify(Map<String, String> options) { this.options = options; }

    private static double value(Object item) { return ((Number) item).doubleValue(); }
    private static String source(Object item) { return item == null ? "?" : item.toString(); }
    private static Path suffix(Path audio, String suffix) {
        String name = audio.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return audio.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + suffix);
    }

    private Path diarization(Path audio, Path output) throws Exception {
        if (options.containsKey("diarization-json")) {
            Path ready = Support.path(options, "diarization-json");
            if (!Files.isRegularFile(ready)) throw new IllegalArgumentException("Не найден JSON диаризации: " + ready);
            return ready;
        }
        Path exe = Support.path(options, "nemo-exe"), gguf = Support.path(options, "nemo-model");
        if (!Files.isRegularFile(exe) || !Files.isRegularFile(gguf))
            throw new IllegalArgumentException("Не найдены nemo-speech или локальная GGUF-модель.");
        Path dir = Files.createTempDirectory("stt-verify-");
        try {
            Path staged = dir.resolve(audio.getFileName());
            Files.copy(audio, staged);
            Path json = dir.resolve(suffix(audio, ".nemotron.json").getFileName());
            System.out.println("Запускаю Nemotron на CPU для временных границ речи...");
            Process process = new ProcessBuilder(exe.toString(), "diarize", staged.toString(),
                    "--model", gguf.toString(), "--device", "cpu", "--format", "json",
                    "--output", json.toString(), "--force").inheritIO().start();
            if (process.waitFor() != 0) throw new IllegalStateException("Nemotron завершился с ошибкой.");
            Path persistent = output.resolveSibling(suffix(output, ".nemotron.tmp.json").getFileName());
            Files.copy(json, persistent, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return persistent;
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(file -> {
                    try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                });
            }
        }
    }

    private static String bestVote(Map<String, Double> votes) {
        String result = null;
        double best = Double.NEGATIVE_INFINITY;
        for (var entry : votes.entrySet()) if (entry.getValue() > best) {
            result = entry.getKey(); best = entry.getValue();
        }
        return result;
    }

    private static String csvCell(Object value) {
        if (value == null) return "";
        String text = value.toString();
        if (text.contains(",") || text.contains("\n") || text.contains("\r") || text.contains("\""))
            return "\"" + text.replace("\"", "\"\"") + "\"";
        return text;
    }

    @SuppressWarnings("unchecked")
    void run() throws Exception {
        Path audioPath = Support.path(options, "audio");
        Path output = options.containsKey("output") ? Support.path(options, "output") : suffix(audioPath, ".verified.json");
        Path csv = options.containsKey("csv") ? Support.path(options, "csv") : suffix(output, ".csv");
        Path model = options.containsKey("model") ? Support.path(options, "model") :
                Path.of(System.getenv().getOrDefault("SPEAKER_EMBEDDING_MODEL",
                        "models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx")).toAbsolutePath();
        Files.createDirectories(output.getParent());
        float[] audio = Support.readWav(audioPath);
        double duration = audio.length / (double) Support.RATE;
        double refStart = Support.number(options, "enroll-start", 20), refDuration = Support.number(options, "enroll-duration", 10);
        double refEnd = refStart + refDuration;
        if (refStart < 0 || refDuration <= 0 || refEnd > duration)
            throw new IllegalArgumentException("Окно эталона выходит за длительность записи.");
        Path diarizationPath = diarization(audioPath, output);
        Map<String, Object> diarization = Support.readMap(diarizationPath);
        List<Map<String, Object>> raw = (List<Map<String, Object>>) diarization.get("segments");
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("В JSON диаризации нет речевых сегментов.");
        double power = 0;
        for (float sample : Arrays.copyOfRange(audio, (int) (refStart * Support.RATE), (int) (refEnd * Support.RATE)))
            power += (double) sample * sample;
        if (Math.sqrt(power / Math.max(1, (int) (refDuration * Support.RATE))) < .003)
            throw new IllegalArgumentException("В выбранном окне эталона почти нет звука.");
        Set<String> refIds = new LinkedHashSet<>();
        for (Map<String, Object> segment : raw) if (Math.min(value(segment.get("end")), refEnd)
                - Math.max(value(segment.get("start")), refStart) >= 1)
            refIds.add(source(segment.get("speaker")));
        List<String> orderedRefIds = new ArrayList<>(refIds);
        orderedRefIds.sort(String::compareTo);
        List<String> warnings = new ArrayList<>();
        if (refIds.size() > 1) warnings.add("Окно эталона пересекает несколько временных speaker ID по Nemotron: "
                + String.join(", ", orderedRefIds) + ". Качество эталона может снизиться.");
        int threads = Integer.parseInt(options.getOrDefault("num-threads", "4"));
        double threshold = Support.number(options, "threshold", .6);
        double clusterThreshold = Support.number(options, "cluster-threshold", .6);
        double minClusterSeconds = Support.number(options, "min-cluster-seconds", 3);
        SpeakerEmbeddingExtractor extractor = Support.extractor(model, threads);
        try {
            float[] candidate = Support.embedding(extractor, audio, refStart, refEnd, 0);
            if (candidate == null) throw new IllegalArgumentException("Не удалось извлечь голосовой эталон.");
            List<Interval> intervals = new ArrayList<>();
            for (Map<String, Object> segment : raw) {
                double originalStart = value(segment.get("start"));
                double originalEnd = Math.min(value(segment.get("end")), duration);
                String source = source(segment.get("speaker"));
                for (double[] piece : Support.split(originalStart, originalEnd, refStart, refEnd)) {
                    if (piece[1] - piece[0] < .45) continue;
                    Interval item = new Interval(piece[0], piece[1], source);
                    item.embedding = Support.embedding(extractor, audio, item.start, item.end, .08);
                    if (item.embedding != null) item.candidateSimilarity = Support.dot(candidate, item.embedding);
                    intervals.add(item);
                }
            }
            for (Interval current : intervals) for (Interval other : intervals) {
                if (current != other && Math.min(current.end, other.end) - Math.max(current.start, other.start) > .25) {
                    current.overlapped = true; break;
                }
            }
            List<Cluster> clusters = new ArrayList<>();
            List<Interval> bootstrap = intervals.stream().filter(item -> item.embedding != null
                    && item.candidateSimilarity != null && item.candidateSimilarity < threshold
                    && item.duration() >= minClusterSeconds && !item.overlapped)
                    .sorted(Comparator.comparingDouble((Interval item) -> -item.duration()).thenComparingDouble(item -> item.start))
                    .toList();
            for (Interval item : bootstrap) {
                int best = -1; double score = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < clusters.size(); i++) {
                    double similarity = clusters.get(i).similarity(item.embedding);
                    if (similarity > score) { best = i; score = similarity; }
                }
                if (best >= 0 && score >= clusterThreshold) {
                    Cluster group = clusters.get(best);
                    group.embeddings.add(item.embedding);
                    group.firstStart = Math.min(group.firstStart, item.start);
                    item.clusterIndex = best;
                    item.interviewerSimilarity = score;
                } else {
                    clusters.add(new Cluster(item.embedding, item.start));
                    item.clusterIndex = clusters.size() - 1;
                    item.interviewerSimilarity = 1.0;
                }
            }
            if (clusters.isEmpty()) {
                Interval seed = intervals.stream().filter(item -> item.embedding != null
                        && item.candidateSimilarity != null && item.candidateSimilarity < threshold)
                        .max(Comparator.comparingDouble(Interval::duration).thenComparingDouble(item -> -item.start))
                        .orElse(null);
                if (seed != null) { clusters.add(new Cluster(seed.embedding, seed.start)); seed.clusterIndex = 0; }
            }
            for (Interval item : intervals) {
                if (item.embedding == null || clusters.isEmpty()) continue;
                int best = 0; double score = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < clusters.size(); i++) {
                    double similarity = clusters.get(i).similarity(item.embedding);
                    if (similarity > score) { best = i; score = similarity; }
                }
                item.interviewerSimilarity = score;
                if (score >= clusterThreshold) item.clusterIndex = best;
            }
            List<Integer> clusterOrder = new ArrayList<>();
            for (int i = 0; i < clusters.size(); i++) clusterOrder.add(i);
            clusterOrder.sort(Comparator.comparingDouble(index -> clusters.get(index).firstStart));
            Map<Integer, Integer> display = new LinkedHashMap<>();
            for (int i = 0; i < clusterOrder.size(); i++) display.put(clusterOrder.get(i), i + 1);
            for (Interval item : intervals) {
                Double candidateScore = item.candidateSimilarity, interviewerScore = item.interviewerSimilarity;
                if (candidateScore != null && candidateScore >= threshold
                        && (interviewerScore == null || candidateScore >= interviewerScore)) {
                    item.label = "Кандидат";
                    item.confidence = item.duration() >= 2.5 && !item.overlapped ? "высокая" : "средняя";
                    item.decision = "Сходство с эталоном выше порога";
                } else if (item.clusterIndex != null && interviewerScore != null
                        && interviewerScore >= clusterThreshold && (candidateScore == null || interviewerScore > candidateScore)) {
                    item.label = "Интервьюер " + display.get(item.clusterIndex);
                    item.confidence = item.duration() >= 2.5 && !item.overlapped ? "высокая" : "средняя";
                    item.decision = "Совпадение с группой другого голоса";
                }
            }
            Map<String, Map<String, Double>> sourceVotes = new LinkedHashMap<>();
            for (Interval item : intervals) if (!item.label.isEmpty())
                sourceVotes.computeIfAbsent(item.source, ignored -> new LinkedHashMap<>())
                        .merge(item.label, item.duration(), Double::sum);
            Map<String, String> sourceRoles = new LinkedHashMap<>();
            sourceVotes.forEach((source, votes) -> sourceRoles.put(source, bestVote(votes)));
            for (Interval item : intervals) {
                if (!item.label.isEmpty()) continue;
                String stable = sourceRoles.get(item.source);
                if (stable != null) {
                    item.label = stable;
                    item.decision = "Автоматически назначено по устойчивой роли speaker ID";
                } else if (item.candidateSimilarity != null && (item.interviewerSimilarity == null
                        || item.candidateSimilarity >= item.interviewerSimilarity)) {
                    item.label = "Кандидат";
                    item.decision = "Автоматически выбрано по максимальному сходству голосов";
                } else if (item.interviewerSimilarity != null && !clusters.isEmpty()) {
                    int best = 0; double score = Double.NEGATIVE_INFINITY;
                    for (int i = 0; i < clusters.size(); i++) {
                        double similarity = clusters.get(i).similarity(item.embedding);
                        if (similarity > score) { score = similarity; best = i; }
                    }
                    item.label = "Интервьюер " + display.get(best);
                    item.decision = "Автоматически выбрано по максимальному сходству голосов";
                } else if (!sourceRoles.isEmpty()) {
                    Interval nearest = intervals.stream().filter(other -> other != item && !other.label.isEmpty())
                            .min(Comparator.comparingDouble(other -> Math.min(Math.abs(item.start - other.end),
                                    Math.abs(item.end - other.start)))).orElseThrow();
                    item.label = nearest.label;
                    item.decision = "Автоматически назначено по ближайшей реплике";
                } else {
                    item.label = "Кандидат";
                    item.decision = "Голосовой эталон — единственный доступный ориентир";
                }
                item.confidence = "низкая";
            }
            List<Map<String, Object>> events = new ArrayList<>();
            events.add(Support.map("start", Support.round(refStart, 3), "end", Support.round(refEnd, 3),
                    "duration", Support.round(refDuration, 3), "label", "Кандидат (эталон)",
                    "candidate_similarity", null, "interviewer_similarity", null,
                    "source_speaker_id", String.join(",", orderedRefIds), "confidence", "эталон",
                    "confidence_percent", 100,
                    "decision", "Голос записан из заданного окна"));
            for (Interval item : intervals) {
                int confidencePercent = ConfidenceScore.percent(item.confidence, item.label, item.decision,
                        item.candidateSimilarity, item.interviewerSimilarity,
                        item.label.startsWith("Интервьюер") ? clusterThreshold : threshold);
                item.confidence = ConfidenceScore.label(confidencePercent);
                events.add(Support.map("start", Support.round(item.start, 3),
                    "end", Support.round(item.end, 3), "duration", Support.round(item.duration(), 3),
                    "label", item.label,
                    "candidate_similarity", item.candidateSimilarity == null ? null : Support.round(item.candidateSimilarity, 4),
                    "interviewer_similarity", item.interviewerSimilarity == null ? null : Support.round(item.interviewerSimilarity, 4),
                    "source_speaker_id", item.source, "confidence", item.confidence,
                    "confidence_percent", confidencePercent, "decision", item.decision));
            }
            events.sort(Comparator.comparingDouble((Map<String, Object> row) -> (double) row.get("start"))
                    .thenComparingDouble(row -> (double) row.get("end")));
            Map<String, Integer> summary = new LinkedHashMap<>();
            for (Map<String, Object> row : events) summary.merge((String) row.get("label"), 1, Integer::sum);
            String sourceName = options.containsKey("diarization-json") ? diarizationPath.getFileName().toString()
                    : "generated by NeMo-Speech.cpp";
            Map<String, Object> result = Support.map("audio", audioPath.getFileName().toString(),
                    "diarization_json", sourceName, "embedding_model", model.getFileName().toString(),
                    "embedding_model_source", Support.MODEL_URL, "device", "cpu", "sample_rate_hz", Support.RATE,
                    "enrollment", Support.map("start", refStart, "end", refEnd, "duration", refDuration),
                    "thresholds", Support.map("candidate_similarity", threshold,
                            "interviewer_cluster_similarity", clusterThreshold),
                    "summary", summary, "warnings", warnings, "segments", events);
            if (options.containsKey("reference-wav")) {
                Path reference = Support.path(options, "reference-wav");
                Support.writeWav(reference, Arrays.copyOfRange(audio, (int) (refStart * Support.RATE), (int) (refEnd * Support.RATE)));
                System.out.println("Эталон: " + reference);
            }
            Support.writePretty(output, result);
            Files.createDirectories(csv.getParent());
            StringBuilder content = new StringBuilder("\ufeff").append(String.join(",", CSV_FIELDS)).append("\r\n");
            for (Map<String, Object> row : events) {
                for (int i = 0; i < CSV_FIELDS.length; i++) {
                    if (i > 0) content.append(',');
                    content.append(csvCell(row.get(CSV_FIELDS[i])));
                }
                content.append("\r\n");
            }
            Files.writeString(csv, content.toString(), StandardCharsets.UTF_8);
            System.out.println("JSON:   " + output + "\nCSV:    " + csv + "\nИтог:");
            summary.forEach((label, count) -> System.out.println("  " + label + ": " + count + " сегм."));
            warnings.forEach(warning -> System.out.println("Предупреждение: " + warning));
            System.out.println("Всем речевым сегментам автоматически назначены роли.");
        } finally {
            extractor.release();
            if (!options.containsKey("diarization-json")) Files.deleteIfExists(diarizationPath);
        }
    }
}
