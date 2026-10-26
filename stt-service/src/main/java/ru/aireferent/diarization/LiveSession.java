package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Владеет сессией Nemotron, решениями о ролях и тремя основными файлами результата.
 *
 * @version 1.0.0
 */
final class LiveSession implements AutoCloseable {
    /** Счётчики захвата и причина остановки.
     *
     * @version 1.0.0
     */
    record Capture(long capturedSamples, int capacity, long maxDepth, long overflowCount,
                   long droppedChunks, long droppedSamples, String stopReason, String error) {}
    /** Результат обработки блока для интерфейса и моста интеграции.
     *
     * @version 1.0.0
     */
    record Update(String role, double audioSeconds, List<Map<String, Object>> segments,
                  List<Map<String, Object>> activeSegments) {}

    private final NemotronStream diarizer;
    private final OnlineRoles roles;
    private final Path outputDir, logPath, segmentsPath, summaryPath;
    private final String source, mode, enrollmentSource, micName, preset;
    private final int inputRate, inputChannels;
    private final double enrollmentSeconds, chunkSeconds, settleSeconds, startupSeconds;
    private final BufferedWriter log;
    private final AudioRing audio = new AudioRing(120 * Support.RATE);
    private final List<Map<String, Object>> emitted = new ArrayList<>();
    private final Map<String, Double> lastEnd = new LinkedHashMap<>();
    private final List<Double> processingMs = new ArrayList<>(), outputDelay = new ArrayList<>(), backlog = new ArrayList<>(), queueWait = new ArrayList<>();
    private final long runStart = System.nanoTime();
    private volatile String currentRole = "Ожидаем речь";
    private Capture capture;
    private boolean finished;

    LiveSession(NemotronStream diarizer, SpeakerEmbeddingExtractor extractor, float[] candidate,
                double threshold, Path outputDir, String source, String mode, String enrollmentSource, String micName,
                int inputRate, int inputChannels, double enrollmentSeconds, double chunkSeconds,
                double settleSeconds, double startupSeconds, String preset, Capture initial) throws Exception {
        this.diarizer = diarizer;
        this.roles = new OnlineRoles(candidate, extractor, threshold);
        this.outputDir = outputDir;
        this.logPath = outputDir.resolve("events.jsonl");
        this.segmentsPath = outputDir.resolve("segments.json");
        this.summaryPath = outputDir.resolve("summary.json");
        this.source = source;
        this.mode = mode;
        this.enrollmentSource = enrollmentSource;
        this.micName = micName;
        this.inputRate = inputRate;
        this.inputChannels = inputChannels;
        this.enrollmentSeconds = enrollmentSeconds;
        this.chunkSeconds = chunkSeconds;
        this.settleSeconds = settleSeconds;
        this.startupSeconds = startupSeconds;
        this.preset = preset;
        this.capture = initial;
        Files.createDirectories(outputDir);
        log = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8);
        writeSegments();
        emit(Support.map("type", "session_started", "audio", source,
                "chunk_seconds", chunkSeconds, "pace", "live", "preset", preset, "device", "cpu",
                "queue_capacity_chunks", initial.capacity(), "queue_overflow_policy", "stop_and_report",
                "enrollment", enrollment(), "model_load_s", Support.round(startupSeconds, 3)));
    }

    private double elapsed() { return (System.nanoTime() - runStart) / 1e9; }
    String currentRole() { return currentRole; }
    Path outputDir() { return outputDir; }
    boolean isFinished() { return finished; }
    List<Map<String, Object>> allSegments() { return new ArrayList<>(emitted); }

    private Map<String, Object> enrollment() {
        return Support.map("duration", Support.round(enrollmentSeconds, 3), "preloaded", false, "source", enrollmentSource);
    }

    private void emit(Map<String, Object> event) throws Exception {
        String line = Support.JSON.writeValueAsString(event);
        log.write(line);
        log.newLine();
        log.flush();
        System.out.println(line);
        System.out.flush();
    }

    private List<Map<String, Object>> completed(List<NemotronStream.Segment> raw, double cutoff, double fed) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<NemotronStream.Segment> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparingDouble((NemotronStream.Segment item) -> item.endTime)
                .thenComparingDouble(item -> item.startTime));
        for (NemotronStream.Segment segment : ordered) {
            String speaker = Integer.toString(segment.speaker);
            double start = Math.max(0, Math.max(segment.startTime, lastEnd.getOrDefault(speaker, 0.0)));
            double end = Math.min(segment.endTime, fed);
            if (end <= start + .01 || end > cutoff + .001) continue;
            float[] voice = audio.sliceTail(start, end, 15);
            Map<String, Object> row = roles.classify(voice, 0, voice.length / (double) Support.RATE, speaker);
            row.put("start", Support.round(start, 3));
            row.put("end", Support.round(end, 3));
            row.put("duration", Support.round(end - start, 3));
            row.put("emitted_wall_s", Support.round(elapsed(), 3));
            row.put("emitted_after_audio_s", Support.round(fed, 3));
            double delay = Math.max(0, elapsed() - end);
            row.put("output_delay_s", Support.round(delay, 3));
            outputDelay.add(delay);
            lastEnd.put(speaker, end);
            emitted.add(row);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> activePreviews(List<NemotronStream.Segment> raw, double processed, double fed) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NemotronStream.Segment segment : raw) {
            if (segment.endTime < processed - .16 || segment.endTime <= segment.startTime) continue;
            double end = Math.min(segment.endTime, fed);
            float[] voice = audio.sliceTail(segment.startTime, end, 2);
            Map<String, Object> preview = roles.preview(voice, 0, voice.length / (double) Support.RATE,
                    Integer.toString(segment.speaker));
            preview.put("start", Support.round(segment.startTime, 3));
            preview.put("observed_end", Support.round(end, 3));
            result.add(preview);
        }
        return result;
    }

    Update process(LiveMicrophone.AudioChunk chunk, int queueDepth, Capture metrics) throws Exception {
        if (finished) throw new IllegalStateException("Сессия уже завершена.");
        if (chunk.firstSample() != audio.totalSamples()) throw new IllegalStateException("Нарушен порядок чанков микрофона.");
        capture = metrics;
        double audioStart = chunk.firstSample() / (double) Support.RATE;
        double audioEnd = (chunk.firstSample() + chunk.samples().length) / (double) Support.RATE;
        double waitSeconds = Math.max(0, (System.nanoTime() - chunk.capturedAtNanos()) / 1e9);
        queueWait.add(waitSeconds);
        audio.append(chunk.samples());
        long pushStart = System.nanoTime();
        diarizer.push(chunk.samples());
        double pushMs = (System.nanoTime() - pushStart) / 1e6;
        double processed = diarizer.processedSeconds();
        List<NemotronStream.Segment> raw = diarizer.segments();
        List<Map<String, Object>> newRows = completed(raw, processed - settleSeconds, audioEnd);
        List<Map<String, Object>> active = activePreviews(raw, processed, audioEnd);
        double totalMs = (System.nanoTime() - pushStart) / 1e6;
        double wall = elapsed();
        processingMs.add(totalMs);
        backlog.add(Math.max(0, wall - audioEnd));
        emit(Support.map("type", "chunk", "index", chunk.index(),
                "audio_start_s", Support.round(audioStart, 3), "audio_end_s", Support.round(audioEnd, 3),
                "captured_wall_s", Support.round((chunk.capturedAtNanos() - runStart) / 1e9, 3),
                "queue_wait_s", Support.round(waitSeconds, 3), "queue_depth_chunks", queueDepth,
                "wall_elapsed_s", Support.round(wall, 3), "push_ms", Support.round(pushMs, 2),
                "total_processing_ms", Support.round(totalMs, 2), "model_processed_audio_s", Support.round(processed, 3),
                "backlog_s", Support.round(backlog.get(backlog.size() - 1), 3),
                "active_speakers", active, "segments", newRows));
        if (!newRows.isEmpty()) writeSegments();
        Support.writePretty(summaryPath, summary(0, false));
        if (!active.isEmpty()) currentRole = String.valueOf(active.get(active.size() - 1).get("label"));
        else if (!newRows.isEmpty()) currentRole = String.valueOf(newRows.get(newRows.size() - 1).get("label"));
        else currentRole = "Ожидаем речь";
        return new Update(currentRole, audioEnd, newRows, active);
    }

    Map<String, Object> finish(Capture metrics, boolean success) throws Exception {
        if (finished) return summary(0, success);
        capture = metrics;
        if (metrics.overflowCount() > 0 || metrics.error() != null) {
            emit(Support.map("type", "capture_stopped", "reason", metrics.stopReason(),
                    "overflow_count", metrics.overflowCount(), "dropped_chunks", metrics.droppedChunks(),
                    "dropped_audio_seconds", Support.round(metrics.droppedSamples() / (double) Support.RATE, 3),
                    "error", metrics.error()));
        }
        long flushStart = System.nanoTime();
        diarizer.finish();
        double duration = audio.totalSamples() / (double) Support.RATE;
        List<Map<String, Object>> finalRows = completed(diarizer.segments(), duration + 1, duration);
        double flushMs = (System.nanoTime() - flushStart) / 1e6;
        emit(Support.map("type", "stream_finished", "flush_ms", Support.round(flushMs, 2), "segments", finalRows));
        writeSegments();
        Map<String, Object> result = summary(flushMs, success);
        Support.writePretty(summaryPath, result);
        Map<String, Object> summaryEvent = Support.map("type", "summary");
        summaryEvent.putAll(result);
        emit(summaryEvent);
        finished = true;
        return result;
    }

    private Map<String, Object> summary(double flushMs, boolean done) {
        double duration = audio.totalSamples() / (double) Support.RATE;
        Map<String, Integer> roleCounts = new LinkedHashMap<>(), confidenceCounts = new LinkedHashMap<>();
        for (Map<String, Object> row : emitted) {
            roleCounts.merge((String) row.get("label"), 1, Integer::sum);
            confidenceCounts.merge((String) row.get("confidence"), 1, Integer::sum);
        }
        double compute = (processingMs.stream().mapToDouble(Double::doubleValue).sum() + flushMs) / 1000;
        Map<String, Object> timing = Support.map("mean", Support.round(processingMs.stream()
                        .mapToDouble(Double::doubleValue).average().orElse(0), 2),
                "p50", Support.round(Support.percentile(processingMs, .5), 2),
                "p95", Support.round(Support.percentile(processingMs, .95), 2),
                "max", Support.round(processingMs.stream().mapToDouble(Double::doubleValue).max().orElse(0), 2));
        Map<String, Object> delays = outputDelay.isEmpty() ? null : Support.map(
                "p50", Support.round(Support.percentile(outputDelay, .5), 3),
                "p95", Support.round(Support.percentile(outputDelay, .95), 3),
                "max", Support.round(outputDelay.stream().mapToDouble(Double::doubleValue).max().orElse(0), 3));
        Map<String, Object> queueWaitSeconds = queueWait.isEmpty() ? null : Support.map(
                "p50", Support.round(Support.percentile(queueWait, .5), 3),
                "p95", Support.round(Support.percentile(queueWait, .95), 3),
                "max", Support.round(queueWait.stream().mapToDouble(Double::doubleValue).max().orElse(0), 3));
        return Support.map("audio", source, "mode", mode, "device", "cpu",
                "microphone", Support.map("name", micName, "input_sample_rate_hz", inputRate,
                        "input_channels", inputChannels, "model_sample_rate_hz", Support.RATE),
                "nemotron_model_load_once", true, "nemotron_stream_open_once", true, "speaker_model_load_once", true,
                "enrollment", enrollment(), "audio_seconds", Support.round(duration, 3), "chunks", processingMs.size(),
                "chunk_seconds", chunkSeconds, "model_startup_seconds", Support.round(startupSeconds, 3),
                "run_wall_seconds", Support.round(elapsed(), 3), "compute_seconds_sum", Support.round(compute, 3),
                "compute_over_audio", duration == 0 ? null : Support.round(compute / duration, 3),
                "chunk_processing_ms", timing,
                "max_backlog_seconds", backlog.isEmpty() ? null : Support.round(backlog.stream()
                        .mapToDouble(Double::doubleValue).max().orElse(0), 3),
                "capture", Support.map("captured_audio_seconds", Support.round(capture.capturedSamples() / (double) Support.RATE, 3),
                        "queue_capacity_chunks", capture.capacity(), "max_queue_depth_chunks", capture.maxDepth(),
                        "queue_wait_seconds", queueWaitSeconds, "overflow_count", capture.overflowCount(),
                        "dropped_chunks", capture.droppedChunks(),
                        "dropped_audio_seconds", Support.round(capture.droppedSamples() / (double) Support.RATE, 3),
                        "stop_reason", capture.stopReason(), "error", capture.error()),
                "output_delay_seconds", delays, "flush_ms", Support.round(flushMs, 2),
                "role_counts", roleCounts, "confidence_counts", confidenceCounts,
                "segments_emitted", emitted.size(), "finished", done,
                "outputs", Support.map("events_jsonl", logPath.toString(),
                        "segments_json", segmentsPath.toString(), "summary_json", summaryPath.toString()),
                "note", "Роли назначены автоматически; точность на этой живой записи не измерена.");
    }

    private void writeSegments() throws Exception {
        List<Map<String, Object>> ordered = new ArrayList<>(emitted);
        ordered.sort(Comparator.comparingDouble((Map<String, Object> row) -> (double) row.get("start"))
                .thenComparingDouble(row -> (double) row.get("end")));
        Support.writePretty(segmentsPath, ordered);
    }

    @Override public void close() throws Exception { log.close(); }

    /** Кольцевой буфер последних сэмплов для извлечения голосовых сегментов.
     *
     * @version 1.0.0
     */
    private static final class AudioRing {
        private final float[] samples;
        private long total;
        AudioRing(int capacity) { samples = new float[capacity]; }
        long totalSamples() { return total; }
        void append(float[] chunk) { for (float sample : chunk) samples[(int) (total++ % samples.length)] = sample; }
        float[] sliceTail(double startSeconds, double endSeconds, double maxSeconds) {
            long end = Math.min(total, Math.round(endSeconds * Support.RATE));
            long start = Math.max(Math.round(startSeconds * Support.RATE), end - Math.round(maxSeconds * Support.RATE));
            start = Math.max(start, Math.max(0, total - samples.length));
            if (start >= end) return new float[0];
            float[] result = new float[(int) (end - start)];
            for (int i = 0; i < result.length; i++) result[i] = samples[(int) ((start + i) % samples.length)];
            return result;
        }
    }
}
