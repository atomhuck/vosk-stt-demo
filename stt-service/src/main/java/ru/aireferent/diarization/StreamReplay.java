package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Воспроизводит WAV последовательными чанками в одной сессии Nemotron.
 *
 * @version 1.0.0
 */
final class StreamReplay {
    private final Map<String, String> options;
    private final Path audioPath, dll, gguf, speakerModel, outputDir;
    private final double chunkSeconds, settleSeconds, maxSeconds, refStart, refDuration, threshold;
    private final String pace, preset;
    private final int threads;
    private final boolean saveChunks;
    private float[] audio;
    private int replaySamples;
    private OnlineRoles roles;
    private final List<Map<String, Object>> emitted = new ArrayList<>();
    private final Map<String, Double> lastEnd = new LinkedHashMap<>();
    private boolean referenceEmitted;
    private long runStart;
    private final List<Double> outputDelay = new ArrayList<>();

    StreamReplay(Map<String, String> options) {
        this.options = options;
        audioPath = Support.path(options, "audio");
        dll = Support.path(options, "nemo-lib");
        gguf = Support.path(options, "nemo-model");
        speakerModel = Support.path(options, "speaker-model");
        outputDir = Support.path(options, "output-dir");
        chunkSeconds = Support.number(options, "chunk-seconds", .64);
        settleSeconds = Support.number(options, "settle-seconds", .64);
        maxSeconds = Support.number(options, "max-seconds", 0);
        refStart = Support.number(options, "enroll-start", 20);
        refDuration = Support.number(options, "enroll-duration", 10);
        threshold = Support.number(options, "threshold", .60);
        threads = Integer.parseInt(options.getOrDefault("num-threads", "4"));
        pace = options.getOrDefault("pace", "realtime");
        preset = options.getOrDefault("preset", "v3-streaming");
        saveChunks = options.containsKey("save-chunks");
        if (!pace.equals("realtime") && !pace.equals("fast")) throw new IllegalArgumentException("--pace: realtime или fast");
        if (chunkSeconds <= 0 || settleSeconds < 0 || maxSeconds < 0)
            throw new IllegalArgumentException("Размер буфера должен быть положительным; задержка и лимит — неотрицательными.");
    }

    private double elapsed() { return (System.nanoTime() - runStart) / 1e9; }

    private void emit(BufferedWriter writer, Map<String, Object> event) throws Exception {
        String line = Support.JSON.writeValueAsString(event);
        writer.write(line);
        writer.write(System.lineSeparator());
        writer.flush();
        System.out.println(line);
        System.out.flush();
    }

    private List<Map<String, Object>> completed(List<NemotronStream.Segment> raw, double cutoff, double now) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<NemotronStream.Segment> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparingDouble((NemotronStream.Segment item) -> item.endTime)
                .thenComparingDouble(item -> item.startTime));
        for (NemotronStream.Segment segment : ordered) {
            String source = Integer.toString(segment.speaker);
            for (double[] piece : Support.split(segment.startTime, segment.endTime, refStart, refStart + refDuration)) {
                double start = Math.max(Math.max(0, piece[0]), lastEnd.getOrDefault(source, 0.0));
                double end = Math.min(piece[1], replaySamples / (double) Support.RATE);
                if (end <= start + .01 || end > cutoff + .001) continue;
                float[] available = Arrays.copyOf(audio, Math.min(replaySamples, (int) Math.rint(now * Support.RATE)));
                Map<String, Object> row = roles.classify(available, start, end, source);
                row.put("emitted_wall_s", Support.round(elapsed(), 3));
                row.put("emitted_after_audio_s", Support.round(now, 3));
                if (pace.equals("realtime")) {
                    double delay = Math.max(0, (double) row.get("emitted_wall_s") - end);
                    row.put("output_delay_s", Support.round(delay, 3));
                    outputDelay.add(delay);
                }
                emitted.add(row);
                rows.add(row);
                lastEnd.put(source, end);
            }
        }
        if (!referenceEmitted && now >= refStart + refDuration) {
            referenceEmitted = true;
            Map<String, Object> reference = Support.map("start", Support.round(refStart, 3),
                    "end", Support.round(refStart + refDuration, 3), "duration", Support.round(refDuration, 3),
                    "label", "Кандидат (эталон)", "candidate_similarity", null,
                    "interviewer_similarity", null, "source_speaker_id", null,
                    "confidence", "эталон", "confidence_percent", 100,
                    "decision", "Эталон заранее взят из окна 20–30 с для воспроизведения файла",
                    "emitted_wall_s", Support.round(elapsed(), 3), "emitted_after_audio_s", Support.round(now, 3));
            emitted.add(reference);
            rows.add(reference);
        }
        return rows;
    }

    private List<Map<String, Object>> activePreviews(List<NemotronStream.Segment> raw, double processed, double fed) {
        List<Map<String, Object>> result = new ArrayList<>();
        float[] available = Arrays.copyOf(audio, Math.min(replaySamples, (int) Math.rint(fed * Support.RATE)));
        for (NemotronStream.Segment segment : raw) {
            if (segment.endTime >= processed - .16 && segment.endTime > segment.startTime)
                result.add(roles.preview(available, segment.startTime, Math.min(segment.endTime, fed),
                        Integer.toString(segment.speaker)));
        }
        return result;
    }

    Map<String, Object> run() throws Exception {
        audio = Support.readWav(audioPath);
        double audioDuration = audio.length / (double) Support.RATE;
        if (refStart < 0 || refDuration <= 0 || refStart + refDuration > audioDuration)
            throw new IllegalArgumentException("Окно эталона выходит за пределы записи.");
        replaySamples = maxSeconds > 0 ? Math.min(audio.length, (int) Math.rint(maxSeconds * Support.RATE)) : audio.length;
        int chunkSamples = (int) Math.rint(chunkSeconds * Support.RATE);
        if (chunkSamples <= 0 || replaySamples <= 0) throw new IllegalArgumentException("Размер буфера меньше одного сэмпла.");
        Files.createDirectories(outputDir);
        Path logPath = outputDir.resolve("events.jsonl");
        Path segmentsPath = outputDir.resolve("segments.json");
        Path summaryPath = outputDir.resolve("summary.json");
        long startupAt = System.nanoTime();
        try (NemotronStream diarizer = new NemotronStream(dll, gguf, preset)) {
            SpeakerEmbeddingExtractor extractor = Support.extractor(speakerModel, threads);
            try {
                float[] candidate = Support.embedding(extractor, audio, refStart, refStart + refDuration, 0);
                if (candidate == null) throw new IllegalArgumentException("Из окна эталона не удалось получить эмбеддинг кандидата.");
                double startupSeconds = (System.nanoTime() - startupAt) / 1e9;
                roles = new OnlineRoles(candidate, extractor, threshold);
                List<Double> processingMs = new ArrayList<>();
                List<Double> backlog = new ArrayList<>();
                runStart = System.nanoTime();
                try (BufferedWriter log = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8)) {
                    emit(log, Support.map("type", "session_started", "audio", audioPath.toString(),
                            "audio_duration_s", Support.round(audioDuration, 3),
                            "replay_duration_s", Support.round(replaySamples / (double) Support.RATE, 3),
                            "chunk_seconds", chunkSeconds, "pace", pace, "preset", preset, "device", "cpu",
                            "enrollment", Support.map("start", refStart, "end", refStart + refDuration, "preloaded", true),
                            "model_load_s", Support.round(startupSeconds, 3)));
                    int index = 0;
                    for (int first = 0; first < replaySamples; first += chunkSamples, index++) {
                        int last = Math.min(first + chunkSamples, replaySamples);
                        double audioStart = first / (double) Support.RATE, audioEnd = last / (double) Support.RATE;
                        if (pace.equals("realtime")) {
                            long wait = runStart + Math.round(audioEnd * 1e9) - System.nanoTime();
                            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
                        }
                        float[] chunk = Arrays.copyOfRange(audio, first, last);
                        if (saveChunks) Support.writeWav(outputDir.resolve("chunks")
                                .resolve(String.format(java.util.Locale.ROOT, "chunk_%04d_%08.3f.wav", index, audioStart)), chunk);
                        long pushStart = System.nanoTime();
                        diarizer.push(chunk);
                        double pushMs = (System.nanoTime() - pushStart) / 1e6;
                        double processed = diarizer.processedSeconds();
                        List<NemotronStream.Segment> raw = diarizer.segments();
                        List<Map<String, Object>> newRows = completed(raw, processed - settleSeconds, audioEnd);
                        List<Map<String, Object>> active = activePreviews(raw, processed, audioEnd);
                        double wall = elapsed();
                        double totalMs = (System.nanoTime() - pushStart) / 1e6;
                        processingMs.add(totalMs);
                        if (pace.equals("realtime")) backlog.add(Math.max(0, wall - audioEnd));
                        emit(log, Support.map("type", "chunk", "index", index,
                                "audio_start_s", Support.round(audioStart, 3), "audio_end_s", Support.round(audioEnd, 3),
                                "wall_elapsed_s", Support.round(wall, 3), "push_ms", Support.round(pushMs, 2),
                                "total_processing_ms", Support.round(totalMs, 2),
                                "model_processed_audio_s", Support.round(processed, 3),
                                "backlog_s", backlog.isEmpty() ? null : Support.round(backlog.get(backlog.size() - 1), 3),
                                "active_speakers", active, "segments", newRows));
                    }
                    long flushStart = System.nanoTime();
                    diarizer.finish();
                    List<Map<String, Object>> finalRows = completed(diarizer.segments(),
                            replaySamples / (double) Support.RATE + 1, replaySamples / (double) Support.RATE);
                    double flushMs = (System.nanoTime() - flushStart) / 1e6;
                    emit(log, Support.map("type", "stream_finished", "flush_ms", Support.round(flushMs, 2), "segments", finalRows));
                    double runWall = elapsed();
                    Map<String, Integer> roleCounts = new LinkedHashMap<>(), confidenceCounts = new LinkedHashMap<>();
                    for (Map<String, Object> row : emitted) {
                        roleCounts.merge((String) row.get("label"), 1, Integer::sum);
                        confidenceCounts.merge((String) row.get("confidence"), 1, Integer::sum);
                    }
                    double computeSeconds = (processingMs.stream().mapToDouble(Double::doubleValue).sum() + flushMs) / 1000;
                    double duration = replaySamples / (double) Support.RATE;
                    Map<String, Object> timing = Support.map("mean", Support.round(processingMs.stream().mapToDouble(Double::doubleValue).average().orElse(0), 2),
                            "p50", Support.round(Support.percentile(processingMs, .5), 2),
                            "p95", Support.round(Support.percentile(processingMs, .95), 2),
                            "max", Support.round(processingMs.stream().mapToDouble(Double::doubleValue).max().orElse(0), 2));
                    Map<String, Object> delays = outputDelay.isEmpty() ? null : Support.map(
                            "p50", Support.round(Support.percentile(outputDelay, .5), 3),
                            "p95", Support.round(Support.percentile(outputDelay, .95), 3),
                            "max", Support.round(outputDelay.stream().mapToDouble(Double::doubleValue).max().orElse(0), 3));
                    Map<String, Object> summary = Support.map("audio", audioPath.toString(),
                            "mode", pace.equals("realtime") ? "paced_file_replay" : "unpaced_file_replay",
                            "device", "cpu", "nemotron_model_load_once", true, "nemotron_stream_open_once", true,
                            "speaker_model_load_once", true,
                            "enrollment", Support.map("start", refStart, "end", refStart + refDuration,
                                    "duration", refDuration, "preloaded", true),
                            "audio_seconds", Support.round(duration, 3), "chunks", processingMs.size(),
                            "chunk_seconds", chunkSeconds, "model_startup_seconds", Support.round(startupSeconds, 3),
                            "run_wall_seconds", Support.round(runWall, 3),
                            "total_wall_seconds", Support.round(startupSeconds + runWall, 3),
                            "replay_wall_over_audio", Support.round(runWall / duration, 3),
                            "compute_seconds_sum", Support.round(computeSeconds, 3),
                            "compute_over_audio", Support.round(computeSeconds / duration, 3),
                            "chunk_processing_ms", timing,
                            "max_backlog_seconds", backlog.isEmpty() ? null : Support.round(backlog.stream().mapToDouble(Double::doubleValue).max().orElse(0), 3),
                            "output_delay_seconds", delays, "flush_ms", Support.round(flushMs, 2),
                            "role_counts", roleCounts, "confidence_counts", confidenceCounts,
                            "segments_emitted", emitted.size(),
                            "outputs", Support.map("events_jsonl", logPath.toString(),
                                    "segments_json", segmentsPath.toString(), "summary_json", summaryPath.toString()),
                            "note", "Точность ролей не измерена: для этого нужна эталонная разметка.");
                    emitted.sort(Comparator.comparingDouble((Map<String, Object> row) -> (double) row.get("start"))
                            .thenComparingDouble(row -> (double) row.get("end")));
                    Support.writePretty(segmentsPath, emitted);
                    Support.writePretty(summaryPath, summary);
                    Map<String, Object> summaryEvent = Support.map("type", "summary");
                    summaryEvent.putAll(summary);
                    emit(log, summaryEvent);
                    return summary;
                }
            } finally {
                extractor.release();
            }
        }
    }
}
