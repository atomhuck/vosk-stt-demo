package ru.aireferent.diarization;

import com.fasterxml.jackson.databind.JsonNode;
import org.vosk.Model;
import ru.example.vosk.service.SpeechSession;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Сопоставляет STT с диаризацией и передаёт аналитику только события речи кандидата.
 *
 * @version 1.0.0
 */
final class IntegrationBridge implements AutoCloseable {
    private static final int RATE = 16000;
    // Финальная разметка может высвободить много кадров по 160 мс сразу;
    // очередь должна вмещать ответ длиннее десяти секунд.
    private static final int CANDIDATE_QUEUE_CAPACITY = 512;
    private static final int HOLD_SAMPLES = RATE;
    private static final byte[] END = new byte[0];
    private static final byte[] SKIP = new byte[3];
    /** Блок смешанного аудио с позицией первого сэмпла.
     *
     * @version 1.0.0
     */
    private record AudioBlock(long first, byte[] mixed) {
        long end() { return first + mixed.length / 2; }
    }
    /** Интервал голоса с предварительной или подтверждённой ролью.
     *
     * @version 1.0.0
     */
    private record VoiceSpan(long start, long end, String label, String speaker, boolean preliminary) {}
    /** Фрагмент речи, ожидающий окончательного подтверждения роли.
     *
     * @version 1.0.0
     */
    private static final class PendingSpan {
        long start, lastSeen;
        PendingSpan(long start, long lastSeen) { this.start = start; this.lastSeen = lastSeen; }
    }

    private final Path directory;
    private final BufferedWriter sttLog, timelineLog;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ArrayBlockingQueue<byte[]> sttQueue = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<byte[]> candidateQueue = new ArrayBlockingQueue<>(CANDIDATE_QUEUE_CAPACITY);
    private final Map<byte[], Long> candidateFirstSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<byte[], Long> readyAudioSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private final ArrayDeque<AudioBlock> audio = new ArrayDeque<>();
    private final List<VoiceSpan> spans = new ArrayList<>();
    private final Map<String, PendingSpan> pendingSpans = new HashMap<>();
    private final Map<String, Integer> activePreviewVotes = new HashMap<>();
    private final Map<String, String> activePreviewLabels = new HashMap<>();
    private final Map<String, Long> candidatePreviewEnds = new HashMap<>();
    private final Map<String, VoiceSpan> provisionalSpans = new HashMap<>();
    private final Set<String> seenSegments = new HashSet<>();
    private final Set<String> seenUiEvents = new HashSet<>();
    private final List<Map<String, Object>> finalWords = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> preliminaryWords = new HashMap<>();
    private final Map<String, Integer> utteranceNumbers = new HashMap<>();
    private final List<Double> uiLatencies = new ArrayList<>();
    private final List<Double> decisionLatencies = new ArrayList<>();
    private final List<Double> diarizationLatencies = new ArrayList<>();
    private final List<Double> roleWaitLatencies = new ArrayList<>();
    private final List<Double> voskLatencies = new ArrayList<>();
    private final List<Double> partialLatencies = new ArrayList<>();
    private final List<Double> previewLatencies = new ArrayList<>();
    private final List<Double> endToPreliminaryHintLatencies = new ArrayList<>();
    private final List<Double> preliminaryHintLeadLatencies = new ArrayList<>();
    private final List<Double> peekComputeLatencies = new ArrayList<>();
    private final List<Double> roleLatencies = new ArrayList<>();
    private final List<Double> candidateDispatchLatencies = new ArrayList<>();
    private final List<Double> whisperLatencies = new ArrayList<>();
    private final Map<Integer, Long> answerBases = new HashMap<>();
    private final Map<Integer, Long> decisionTimes = new HashMap<>();
    private final Map<Integer, Long> decisionAudioEnds = new HashMap<>();
    private final Map<String, Long> previewEventSamples = new HashMap<>();
    private final Map<String, Long> previewEventVisibleNanos = new HashMap<>();
    private final Map<String, Long> peekRequestSamples = new HashMap<>();
    private final Map<String, Long> peekRequestNanos = new HashMap<>();
    private final Set<Integer> measuredHintAnswers = new HashSet<>();
    private final List<Double> cpuLoadPercent = new CopyOnWriteArrayList<>();
    private final List<Double> systemCpuLoadPercent = new CopyOnWriteArrayList<>();
    private final CountDownLatch reportReceived = new CountDownLatch(1);
    private final long startedNanos = System.nanoTime();
    private final Peer interview;
    private final Model voskModel;
    private final String sessionId;
    private final String analyticsToken = System.getenv().getOrDefault("INTERVIEW_EVENTS_TOKEN", "");
    private final String whisperUrl = System.getenv().getOrDefault("WHISPER_URL", "").trim();
    private final ByteArrayOutputStream candidateAnswerAudio = new ByteArrayOutputStream();
    private boolean capturingAnswer;
    private long nextAnalyticsSequence = 1;
    private final Thread sttWorker, candidateWorker;
    private final Thread cpuSampler;
    private long capturedSamples, gatedSamples, lastCapturedNanos, lastCandidateEnd;
    private long readySample, answerStartSample;
    private long questionAvailableNanos;
    private long nextEventId;
    private long nextPeekRequestId;
    private long lastAppliedPeekRequestId;
    private volatile String mixedPartial = "", whisperState = "выключен", failure;
    private volatile String currentRole = "Ожидаем речь";
    private final StringBuilder mixedTranscript = new StringBuilder();
    private volatile Map<String, Object> question, decision, refined, preview, finalReport, finalExcel;
    private String analyticsHttpBase;
    private volatile String decisionEventId;
    private volatile String previewEventId;
    private volatile long decisionAudioEnd, decisionAtNanos;
    private long lastPartialSample, lastRoleSample, lastCandidateDispatchSample;
    private long lastPeekSample;
    private long lastPeekAtNanos;
    private String lastPeekText = "";
    private volatile boolean closed;
    private volatile boolean finishing;
    private volatile boolean decisionRetracted;
    private volatile boolean lastPeekCandidateEvidence;
    private volatile LiveSession.Capture capture;
    private int sttEvents, candidateBlocks, unknownWords, lateCandidateSegments;
    private long sttQueueOverflowCount, candidateQueueOverflowCount, candidateQueueDroppedSamples;
    private long candidateQueueDroppedSpeechSamples, maxSttQueueDepth, maxCandidateQueueDepth;

    IntegrationBridge(Path directory, String baseUrl, LiveSession.Capture initialCapture,
                      String sessionId, Model voskModel) throws Exception {
        this.directory = directory;
        this.capture = initialCapture;
        this.sessionId = sessionId;
        this.voskModel = voskModel;
        Files.createDirectories(directory);
        sttLog = Files.newBufferedWriter(directory.resolve("stt-events.jsonl"), StandardCharsets.UTF_8);
        timelineLog = Files.newBufferedWriter(directory.resolve("timeline-events.jsonl"), StandardCharsets.UTF_8);
        String base = baseUrl.replaceAll("/+$", "");
        analyticsHttpBase = base.startsWith("wss://")
                ? "https://" + base.substring(6) : "http://" + base.substring(5);
        try {
            interview = connect(base + "/ws/interview-events", this::onInterview);
        } catch (Exception error) {
            sttLog.close();
            timelineLog.close();
            throw new IllegalStateException("Не удалось подключиться к сервису аналитики " + base
                    + ". Сначала запустите analytics service.", error);
        }
        sttWorker = worker("vosk-mixed", sttQueue, false);
        candidateWorker = worker("vosk-candidate", candidateQueue, true);
        cpuSampler = new Thread(this::sampleCpuLoad, "integration-cpu-load");
        cpuSampler.setDaemon(true);
        cpuSampler.start();
        interview.sendText(Support.JSON.writeValueAsString(Support.map("type", "start", "sessionId", sessionId,
                "levels", List.of())));
        writeTimeline();
        writeSummary(false);
    }

    private Peer connect(String url, Consumer<JsonNode> handler) {
        if (analyticsToken.length() < 32)
            throw new IllegalStateException("Задайте общий секрет INTERVIEW_EVENTS_TOKEN (не менее 32 символов).");
        Peer listener = new Peer(handler);
        listener.attach(client.newWebSocketBuilder().header("X-Internal-Token", analyticsToken)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), listener).join());
        return listener;
    }

    private Thread worker(String name, ArrayBlockingQueue<byte[]> queue, boolean candidate) {
        Thread thread = new Thread(() -> {
            SpeechSession speech = null;
            long lastCandidateSample = 0;
            try {
                speech = new SpeechSession(voskModel, RATE);
                while (true) {
                    byte[] block = queue.take();
                    if (block == END) {
                        SpeechSession.Event tail = speech.finish();
                        if (tail != null) {
                            if (candidate)
                                sendAnalytics("frame", Support.map("samples", 0, "rms", 0,
                                        "audioStartSample", lastCandidateSample, "audioEndSample", lastCandidateSample,
                                        "asrType", tail.type(), "text", tail.text()));
                            else onStt("mixed", voskEvent(tail));
                        }
                        break;
                    }
                    Long readyAt = readyAudioSamples.remove(block);
                    if (readyAt != null) {
                        speech.close();
                        speech = new SpeechSession(voskModel, RATE);
                        synchronized (candidateAnswerAudio) {
                            candidateAnswerAudio.reset();
                            capturingAnswer = true;
                        }
                        lastCandidateSample = readyAt;
                        sendAnalytics("ready", Support.map("audioStartSample", readyAt));
                        continue;
                    }
                    if (block == SKIP) {
                        synchronized (candidateAnswerAudio) {
                            capturingAnswer = false;
                            candidateAnswerAudio.reset();
                        }
                        sendAnalytics("skip", Support.map("audioStartSample", lastCandidateSample,
                                "audioEndSample", lastCandidateSample));
                        continue;
                    }
                    if (candidate) {
                        Long offset = candidateFirstSamples.remove(block);
                        long firstSample = offset == null ? lastCandidateSample : offset;
                        for (int from = 0; from < block.length; from += 1600) {
                            int size = Math.min(1600, block.length - from);
                            byte[] frame = java.util.Arrays.copyOfRange(block, from, from + size);
                            synchronized (candidateAnswerAudio) {
                                if (capturingAnswer && candidateAnswerAudio.size() < 180_000 * 32)
                                    candidateAnswerAudio.write(frame, 0, frame.length);
                            }
                            long asrStarted = System.nanoTime();
                            SpeechSession.Event result = speech.accept(frame, frame.length);
                            double asrMs = (System.nanoTime() - asrStarted) / 1e6;
                            long frameStartSample = firstSample + from / 2L;
                            long frameEndSample = frameStartSample + frame.length / 2L;
                            lastCandidateSample = frameEndSample;
                            sendAnalytics("frame", Support.map("audioStartSample", frameStartSample,
                                    "audioEndSample", frameEndSample, "samples", frame.length / 2,
                                    "rms", rms(frame), "asrMs", asrMs, "asrType", result == null ? "" : result.type(),
                                    "text", result == null ? "" : result.text()));
                        }
                    } else {
                        SpeechSession.Event result = speech.accept(block, block.length);
                        if (result != null) onStt("mixed", voskEvent(result));
                    }
                }
            } catch (Throwable error) {
                failure = name + ": " + error;
                logQuietly(Support.map("type", "bridge_error", "worker", name, "error", failure));
            } finally { if (speech != null) speech.close(); }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private JsonNode voskEvent(SpeechSession.Event result) {
        List<Map<String, Object>> words = result.words().stream().map(word -> Support.map(
                "word", word.word(), "start", word.start(), "end", word.end(), "confidence", word.confidence())).toList();
        return Support.JSON.valueToTree(Support.map("type", result.type(), "text", result.text(), "words", words));
    }

    private static double rms(byte[] pcm) {
        if (pcm.length < 2) return 0;
        double sum = 0;
        int count = pcm.length / 2;
        for (int i = 0; i < count; i++) {
            int sample = (short) ((pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8));
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / count);
    }

    private synchronized void sendAnalytics(String type, Map<String, ?> fields) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("sessionId", sessionId);
        event.put("sequence", nextAnalyticsSequence++);
        fields.forEach(event::put);
        try { interview.sendText(Support.JSON.writeValueAsString(event)); }
        catch (Exception error) { throw new IllegalStateException("Не удалось отправить событие аналитике", error); }
    }

    private static byte[] pcm(float[] samples) {
        byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int value = Math.max(-32768, Math.min(32767, Math.round(samples[i] * 32767)));
            result[i * 2] = (byte) value;
            result[i * 2 + 1] = (byte) (value >>> 8);
        }
        return result;
    }

    synchronized void submit(long firstSample, float[] mixed) {
        if (closed || finishing) return;
        if (firstSample != capturedSamples) throw new IllegalStateException("Разрыв общей шкалы аудиосэмплов.");
        byte[] block = pcm(mixed);
        if (!sttQueue.offer(block)) {
            sttQueueOverflowCount++;
            failure = "Очередь Vosk переполнена; STT отстаёт от захвата.";
            logQuietly(Support.map("type", "stt_overflow", "audio_start_s", firstSample / (double) RATE));
            throw new IllegalStateException(failure);
        }
        maxSttQueueDepth = Math.max(maxSttQueueDepth, sttQueue.size());
        audio.addLast(new AudioBlock(firstSample, block));
        capturedSamples += mixed.length;
        lastCapturedNanos = System.nanoTime();
    }

    synchronized void onDiarization(List<Map<String, Object>> segments,
                                     List<Map<String, Object>> activeSegments,
                                     double audioSeconds, boolean flush, LiveSession.Capture capture) {
        this.capture = capture;
        for (Map<String, Object> row : segments) {
            long start = Math.round(((Number) row.get("start")).doubleValue() * RATE);
            long end = Math.round(((Number) row.get("end")).doubleValue() * RATE);
            String label = String.valueOf(row.get("label"));
            String key = start + ":" + end + ":" + row.get("source_speaker_id");
            if (!seenSegments.add(key)) continue;
            String speaker = String.valueOf(row.get("source_speaker_id"));
            PendingSpan pending = pendingSpans.get(speaker);
            if (pending != null && Math.abs(start - pending.start) <= RATE / 2L)
                pendingSpans.remove(speaker);
            VoiceSpan priorPreview = provisionalSpans.get(speaker);
            if ("Не определено".equals(label) && priorPreview != null) {
                // Показываем предварительную роль по двум окнам CAM++ до накопления
                // 2,5 с речи, необходимых для окончательного подтверждения.
                currentRole = priorPreview.label() + " · предварительно";
            } else {
                spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                provisionalSpans.remove(speaker);
                spans.add(new VoiceSpan(start, end, label, speaker, false));
                currentRole = label;
                if (!"Не определено".equals(label))
                    logQuietly(Support.map("type", "role_confirmed", "speaker_id", speaker, "role", label,
                            "start_s", Support.round(start / (double) RATE, 3),
                            "end_s", Support.round(end / (double) RATE, 3), "source", "nemotron_campp"));
                if (!"Кандидат".equals(label) && priorPreview != null) {
                    preview = null;
                    lastPeekCandidateEvidence = false;
                    lastPeekText = "";
                    logQuietly(Support.map("type", "role_preview_retracted", "speaker_id", speaker,
                            "confirmed_role", label, "start_s", start / (double) RATE));
                }
            }
        if ("Кандидат".equals(label)) {
                lastCandidateEnd = Math.max(lastCandidateEnd, end);
                if (start < gatedSamples) {
                    lateCandidateSegments++;
                    logQuietly(Support.map("type", "late_candidate_role", "start_s", start / (double) RATE,
                            "end_s", end / (double) RATE,
                            "already_gated_to_s", gatedSamples / (double) RATE));
                }
            }
            double latency = Math.max(0, (System.nanoTime() - estimateCapturedAt(end)) / 1e6);
            diarizationLatencies.add(latency);
            logQuietly(Support.map("type", "diarization_segment", "start_s", start / (double) RATE,
                    "end_s", end / (double) RATE, "role", label,
                    "end_to_diarization_ms", Support.round(latency, 1)));
        }
        reviseRoles();
        if (!flush) {
            long now = Math.round(audioSeconds * RATE);
            for (Map<String, Object> active : activeSegments) {
                Object start = active.get("start");
                if (!(start instanceof Number number)) continue;
                String speaker = String.valueOf(active.get("source_speaker_id"));
                long sample = Math.max(0, Math.round(number.doubleValue() * RATE));
                long activeEnd = Math.round(((Number) active.getOrDefault("observed_end", audioSeconds)).doubleValue() * RATE);
                long previousPreviewEnd = candidatePreviewEnds.getOrDefault(speaker, 0L);
                if (activeEnd > previousPreviewEnd) {
                    candidatePreviewEnds.put(speaker, activeEnd);
                    String previewLabel = String.valueOf(active.getOrDefault("label", "Не определено"));
                    if (isSpeakerRole(previewLabel) && previewRoleMargin(active, previewLabel)) {
                        String priorLabel = activePreviewLabels.get(speaker);
                        int votes = previewLabel.equals(priorLabel) ? activePreviewVotes.getOrDefault(speaker, 0) + 1 : 1;
                        activePreviewLabels.put(speaker, previewLabel);
                        activePreviewVotes.put(speaker, votes);
                        currentRole = votes >= 2 ? previewLabel + " · предварительно" : "Проверяем: " + previewLabel;
                        if (votes >= 2) {
                            VoiceSpan prior = provisionalSpans.get(speaker);
                            long roleStart = prior != null && prior.label().equals(previewLabel) ? prior.start() : sample;
                            VoiceSpan provisional = new VoiceSpan(roleStart, activeEnd, previewLabel, speaker, true);
                            provisionalSpans.put(speaker, provisional);
                            spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                            spans.add(provisional);
                            if (votes == 2 && activeEnd > lastRoleSample) {
                                roleLatencies.add(Math.max(0, (System.nanoTime() - estimateCapturedAt(activeEnd)) / 1e6));
                                lastRoleSample = activeEnd;
                            }
                            if (votes == 2)
                                logQuietly(Support.map("type", "role_preliminary", "speaker_id", speaker,
                                        "role", previewLabel, "start_s", roleStart / (double) RATE,
                                        "observed_end_s", activeEnd / (double) RATE, "consecutive_matches", votes));
                            reviseRoles();
                        }
                    } else {
                        activePreviewVotes.put(speaker, 0);
                        activePreviewLabels.remove(speaker);
                        if (provisionalSpans.remove(speaker) != null) {
                            spans.removeIf(span -> span.preliminary() && span.speaker().equals(speaker));
                            preview = null;
                            lastPeekCandidateEvidence = false;
                            lastPeekText = "";
                            currentRole = "Не определено · проверяем голос";
                            logQuietly(Support.map("type", "role_preview_retracted", "speaker_id", speaker,
                                    "confirmed_role", active.get("label")));
                            reviseRoles();
                        }
                    }
                }
                PendingSpan pending = pendingSpans.get(speaker);
                if (pending == null)
                    pendingSpans.put(speaker, new PendingSpan(sample, now));
                else {
                    pending.start = Math.min(pending.start, sample);
                    pending.lastSeen = now;
                }
            }
            var iter = pendingSpans.entrySet().iterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                if (now - entry.getValue().lastSeen <= 5L * RATE) continue;
                logQuietly(Support.map("type", "unresolved_role_expired",
                        "speaker_id", entry.getKey(),
                        "start_s", entry.getValue().start / (double) RATE));
                iter.remove();
            }
        } else pendingSpans.clear();
        long target = flush ? capturedSamples : Math.min(capturedSamples,
                Math.max(0, Math.round(audioSeconds * RATE) - HOLD_SAMPLES));
        if (!flush) {
            for (PendingSpan pending : pendingSpans.values())
                target = Math.min(target, pending.start);
        }
        while (!audio.isEmpty() && audio.peekFirst().end() <= target) {
            AudioBlock block = audio.removeFirst();
            byte[] gated = new byte[block.mixed().length];
            for (VoiceSpan span : spans) {
                // Предварительная роль разрешает только read-only peek.
                // Анализ ответа с изменением состояния получает подтверждённую роль.
                if (!"Кандидат".equals(span.label()) || span.preliminary()) continue;
                long left = Math.max(block.first(), span.start());
                long right = Math.min(block.end(), span.end());
                if (right > left) System.arraycopy(block.mixed(), (int) ((left - block.first()) * 2),
                        gated, (int) ((left - block.first()) * 2), (int) ((right - left) * 2));
            }
            // При наложении голосов смешанный звук нельзя считать речью кандидата.
            for (VoiceSpan span : spans) {
                if ("Кандидат".equals(span.label())) continue;
                long left = Math.max(block.first(), span.start());
                long right = Math.min(block.end(), span.end());
                if (right > left) java.util.Arrays.fill(gated, (int) ((left - block.first()) * 2),
                        (int) ((right - block.first()) * 2), (byte) 0);
            }
            // Отсекаем звук до передачи текущего вопроса кандидату: иначе TTS
            // или предыдущий ответ могут попасть в детектор после маркера READY.
            long preAnswerEnd = Math.min(block.end(), answerStartSample);
            if (preAnswerEnd > block.first())
                java.util.Arrays.fill(gated, 0, (int) ((preAnswerEnd - block.first()) * 2), (byte) 0);
            candidateFirstSamples.put(gated, block.first());
            boolean enqueued = candidateQueue.offer(gated);
            if (!enqueued) candidateFirstSamples.remove(gated);
            boolean candidateSpeech = !isSilent(gated);
            if (!enqueued) {
                candidateQueueOverflowCount++;
                long dropped = gated.length / 2L;
                candidateQueueDroppedSamples += dropped;
                if (candidateSpeech) candidateQueueDroppedSpeechSamples += dropped;
                logQuietly(Support.map("type", "candidate_queue_overflow", "audio_start_s",
                        Support.round(block.first() / (double) RATE, 3),
                        "audio_end_s", Support.round(block.end() / (double) RATE, 3),
                        "action", "drop_newest_frame_continue",
                        "contains_candidate_speech", candidateSpeech,
                        "dropped_audio_s", Support.round(dropped / (double) RATE, 3),
                        "queue_capacity_chunks", CANDIDATE_QUEUE_CAPACITY));
            } else {
                candidateBlocks++;
                maxCandidateQueueDepth = Math.max(maxCandidateQueueDepth, candidateQueue.size());
            }
            if (enqueued && candidateSpeech && block.end() > lastCandidateDispatchSample) {
                candidateDispatchLatencies.add(Math.max(0,
                        (System.nanoTime() - estimateCapturedAt(block.end())) / 1e6));
                lastCandidateDispatchSample = block.end();
            }
            if (enqueued && candidateSpeech) roleWaitLatencies.add(Math.max(0,
                    (System.nanoTime() - estimateCapturedAt(block.end())) / 1e6));
            gatedSamples = block.end();
        }
        writeSummary(false);
    }

    private static boolean isSpeakerRole(String label) {
        return "Кандидат".equals(label) || label.startsWith("Интервьюер ");
    }

    private static boolean previewRoleMargin(Map<String, Object> active, String label) {
        if (!(active.get("candidate_similarity") instanceof Number candidate))
            return isSpeakerRole(label) && String.valueOf(active.get("basis")).contains("speaker ID");
        double candidateScore = candidate.doubleValue();
        double interviewer = active.get("interviewer_similarity") instanceof Number n
                ? n.doubleValue() : Double.NEGATIVE_INFINITY;
        if ("Кандидат".equals(label))
            return candidateScore >= .60 && candidateScore - interviewer >= .05;
        return interviewer >= .60 && interviewer - candidateScore >= .05;
    }

    private String currentPreviewRole() {
        return lastPeekCandidateEvidence ? "Кандидат" : "Не определено";
    }

    private static boolean isSilent(byte[] data) {
        for (byte value : data) if (value != 0) return false;
        return true;
    }

    synchronized void questionAsked() {
        if (question == null) throw new IllegalStateException("Сервис аналитики ещё не выдал вопрос.");
        readySample = capturedSamples;
        answerStartSample = readySample;
        preview = null;
        lastPeekCandidateEvidence = false;
        lastPeekText = "";
        lastPeekSample = readySample;
        byte[] readyMarker = new byte[]{1};
        readyAudioSamples.put(readyMarker, readySample);
        if (!candidateQueue.offer(readyMarker)) {
            readyAudioSamples.remove(readyMarker);
            throw new IllegalStateException("Очередь кандидата занята.");
        }
        logQuietly(Support.map("type", "question_asked", "question", question,
                "audio_s", capturedSamples / (double) RATE));
    }

    synchronized void questionSkipped() {
        if (!candidateQueue.offer(SKIP)) throw new IllegalStateException("Очередь кандидата занята.");
        logQuietly(Support.map("type", "question_skipped", "audio_s", capturedSamples / (double) RATE));
    }

    synchronized void ttsEvent(String event, String questionText, String voice, String voiceLang) {
        if (!List.of("start", "finish", "cancel", "error").contains(event)) return;
        logQuietly(Support.map("type", "tts_" + event, "question", questionText,
                "voice", voice, "voice_lang", voiceLang,
                "audio_sample", capturedSamples, "audio_s", Support.round(capturedSamples / (double) RATE, 3),
                "received_wall_s", Support.round((System.nanoTime() - startedNanos) / 1e9, 3)));
        if ("finish".equals(event))
            sendAnalytics("tts_end", Support.map("afterMs", Math.max(0, capturedSamples - readySample) * 1000L / RATE,
                    "audioEndSample", capturedSamples));
    }

    synchronized void suggestionSeen(String id) {
        if (id == null) return;
        if (id.equals(previewEventId)) {
            if (!seenUiEvents.add(id)) return;
            long sample = previewEventSamples.getOrDefault(id, lastPeekSample);
            long visibleAt = System.nanoTime();
            previewEventVisibleNanos.put(id, visibleAt);
            double previewLatency = Math.max(0, (visibleAt - estimateCapturedAt(sample)) / 1e6);
            previewLatencies.add(previewLatency);
            logQuietly(Support.map("type", "preliminary_hint_visible", "event_id", id,
                    "audio_end_s", Support.round(sample / (double) RATE, 3),
                    "end_to_ui_ack_ms", Support.round(previewLatency, 1)));
            for (int answerId : decisionAudioEnds.keySet()) recordPreliminaryHintLatency(answerId);
            writeSummary(false);
            return;
        }
        if (!id.equals(decisionEventId)) return;
        if (decisionAudioEnd <= readySample) return;
        if (!seenUiEvents.add(id)) return;
        double latency = Math.max(0, (System.nanoTime() - estimateCapturedAt(decisionAudioEnd)) / 1e6);
        uiLatencies.add(latency);
        logQuietly(Support.map("type", "suggestion_visible", "event_id", id,
                "answer_end_s", decisionAudioEnd / (double) RATE,
                "end_to_ui_ack_ms", Support.round(latency, 1),
                "decision_to_ui_ack_ms", Support.round((System.nanoTime() - decisionAtNanos) / 1e6, 1)));
        writeSummary(false);
    }

    private long estimateCapturedAt(long sample) {
        return lastCapturedNanos - Math.max(0, capturedSamples - sample) * 1_000_000_000L / RATE;
    }

    private synchronized void onStt(String source, JsonNode data) {
        if (closed) return;
        String type = data.path("type").asText();
        String text = data.path("text").asText("");
        if ("final".equals(type) && !text.isBlank()) {
            if (mixedTranscript.length() > 0) mixedTranscript.append(' ');
            mixedTranscript.append(text);
        }
        mixedPartial = "final".equals(type) ? "" : text;
        List<Map<String, Object>> words = new ArrayList<>();
        int utterance = utteranceNumbers.getOrDefault(source, 0);
        for (JsonNode word : data.path("words")) {
            long start = Math.round(word.path("start").asDouble() * RATE);
            long end = Math.round(word.path("end").asDouble() * RATE);
            Map<String, Object> row = Support.map("word", word.path("word").asText(""),
                    "word_id", source + ":" + utterance + ":" + words.size(),
                    "start", Support.round(start / (double) RATE, 3),
                    "end", Support.round(end / (double) RATE, 3),
                    "role", role(start, end), "role_status", roleStatus(start, end),
                    "source", "mixed", "model", "vosk",
                    "status", "final".equals(type) ? "final" : "preliminary",
                    "confidence", word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null);
            words.add(row);
            if ("final".equals(type)) finalWords.add(row);
            if ("final".equals(type)) voskLatencies.add(Math.max(0,
                    (System.nanoTime() - estimateCapturedAt(end)) / 1e6));
        }
        if (!text.isBlank()) {
            long sample = words.isEmpty() ? capturedSamples : Math.round(
                    ((Number) words.get(words.size() - 1).get("end")).doubleValue() * RATE);
            if (sample > lastPartialSample) {
                partialLatencies.add(Math.max(0, (System.nanoTime() - estimateCapturedAt(sample)) / 1e6));
                lastPartialSample = sample;
            }
        }
        if ("final".equals(type)) {
            preliminaryWords.remove(source);
            utteranceNumbers.put(source, utterance + 1);
        } else preliminaryWords.put(source, words);
        requestCandidatePeek();
        sttEvents++;
        Map<String, Object> event = Support.map("type", "stt_" + type, "source", "mixed",
                "text", text, "words", words, "received_wall_s",
                Support.round((System.nanoTime() - startedNanos) / 1e9, 3));
        try {
            write(sttLog, event);
            emit(event);
            writeTimeline();
        } catch (Exception error) { failure = error.toString(); }
    }

    private String role(long start, long end) {
        long best = 0, second = 0;
        String label = "Не определено";
        for (VoiceSpan span : spans) {
            long overlap = Math.max(0, Math.min(end, span.end()) - Math.max(start, span.start()));
            if (overlap > best) { second = best; best = overlap; label = span.label(); }
            else second = Math.max(second, overlap);
        }
        if (end <= start || best * 2 < end - start || second * 4 >= best * 3) return "Не определено";
        return label;
    }

    private String roleStatus(long start, long end) {
        long best = 0;
        boolean preliminary = false;
        for (VoiceSpan span : spans) {
            long overlap = Math.max(0, Math.min(end, span.end()) - Math.max(start, span.start()));
            if (overlap > best) { best = overlap; preliminary = span.preliminary(); }
        }
        return best == 0 ? "unknown" : preliminary ? "preliminary" : "confirmed";
    }

    private void reviseRoles() {
        boolean changed = false;
        for (Map<String, Object> word : currentWords()) {
            long start = Math.round(((Number) word.get("start")).doubleValue() * RATE);
            long end = Math.round(((Number) word.get("end")).doubleValue() * RATE);
            String updated = role(start, end);
            String status = roleStatus(start, end);
            if (!updated.equals(word.get("role")) || !status.equals(word.get("role_status"))) {
                word.put("role", updated);
                word.put("role_status", status);
                changed = true;
                logQuietly(Support.map("type", "word_role_revision", "word", word));
            }
        }
        if (changed) {
            writeTimeline();
            requestCandidatePeek();
        }
    }

    /** Собирает текст текущего ответа кандидата из общей временной шкалы Vosk. */
    private void requestCandidatePeek() {
        List<Map<String, Object>> answerWords = currentWords().stream()
                .filter(word -> "vosk".equals(word.get("model")) && "Кандидат".equals(word.get("role")))
                .filter(word -> ((Number) word.get("start")).doubleValue() * RATE >= readySample)
                .sorted((a, b) -> Double.compare(((Number) a.get("start")).doubleValue(),
                        ((Number) b.get("start")).doubleValue()))
                .toList();
        String candidateText = answerWords.stream().map(word -> String.valueOf(word.get("word")))
                .filter(word -> !word.isBlank()).collect(java.util.stream.Collectors.joining(" "));
        lastPeekCandidateEvidence = !candidateText.isBlank();
        if (!lastPeekCandidateEvidence) {
            if (preview != null) {
                preview = null;
                logQuietly(Support.map("type", "preliminary_hint_retracted", "reason", "candidate_role_not_currently_supported"));
            }
            lastPeekText = "";
            return;
        }
        lastPeekSample = answerWords.stream()
                .mapToLong(word -> Math.round(((Number) word.get("end")).doubleValue() * RATE))
                .max().orElse(readySample);
        long now = System.nanoTime();
        if (candidateText.equals(lastPeekText) || now - lastPeekAtNanos < 350_000_000L) return;
        lastPeekText = candidateText;
        lastPeekAtNanos = now;
        String requestId = "peek-" + (++nextPeekRequestId);
        peekRequestSamples.put(requestId, lastPeekSample);
        peekRequestNanos.put(requestId, now);
        try {
            sendAnalytics("peek", Support.map("requestId", requestId, "text", candidateText,
                    "audioStartSample", readySample, "audioEndSample", lastPeekSample));
            logQuietly(Support.map("type", "peek_requested", "request_id", requestId,
                    "text", candidateText, "audio_sample", lastPeekSample,
                    "audio_s", Support.round(lastPeekSample / (double) RATE, 3)));
        } catch (Exception error) {
            failure = "Не удалось передать предварительный текст в analysis peek: " + error;
        }
    }

    private synchronized void onInterview(JsonNode data) {
        if (closed) return;
        String type = data.path("type").asText();
        @SuppressWarnings("unchecked")
        Map<String, Object> event = Support.JSON.convertValue(data, Map.class);
        if ("question".equals(type)) {
            question = event;
            questionAvailableNanos = System.nanoTime()
                    + Math.max(0, data.path("graceMs").asLong()) * 1_000_000L;
        }
        if ("preview".equals(type)) {
            if (!"Кандидат".equals(currentPreviewRole())) return;
            String requestId = data.path("requestId").asText("");
            long requestOrdinal = parsePeekRequestOrdinal(requestId);
            if (requestOrdinal > 0 && requestOrdinal < lastAppliedPeekRequestId) return;
            long previewSample = peekRequestSamples.getOrDefault(requestId, lastPeekSample);
            if (isPreviewAtOrBeforeDecision(previewSample, decisionAudioEnd)) {
                logQuietly(Support.map("type", "preliminary_hint_ignored",
                        "reason", "answer_decision_already_received",
                        "audio_s", Support.round(previewSample / (double) RATE, 3),
                        "decision_audio_end_s", Support.round(decisionAudioEnd / (double) RATE, 3)));
                return;
            }
            lastAppliedPeekRequestId = Math.max(lastAppliedPeekRequestId, requestOrdinal);
            preview = event;
            long requestedAt = peekRequestNanos.getOrDefault(requestId, System.nanoTime());
            peekComputeLatencies.add(data.path("computeMs").isNumber()
                    ? data.path("computeMs").asDouble() : Math.max(0, (System.nanoTime() - requestedAt) / 1e6));
            event.put("event_id", "preview-" + (++nextEventId));
            previewEventId = String.valueOf(event.get("event_id"));
            previewEventSamples.put(previewEventId, previewSample);
            event.put("peek_compute_ms", Support.round(peekComputeLatencies.get(peekComputeLatencies.size() - 1), 1));
            logQuietly(Support.map("type", "preliminary_hint_shown", "preview", event,
                    "audio_s", Support.round(previewSample / (double) RATE, 3)));
        }
        if ("preview_retract".equals(type)) {
            preview = null;
            lastPeekText = "";
            logQuietly(Support.map("type", "preliminary_hint_retracted", "reason", data.path("reason").asText("updated")));
        }
        if ("retract".equals(type)) {
            decisionRetracted = true;
            decision = null;
            if (preview != null) {
                preview = null;
                lastPeekText = "";
                logQuietly(Support.map("type", "preliminary_hint_retracted", "reason", "speech_resumed"));
            }
            if (data.has("question")) {
                @SuppressWarnings("unchecked") Map<String, Object> restored =
                        Support.JSON.convertValue(data.path("question"), Map.class);
                question = restored;
            }
            questionAvailableNanos = System.nanoTime();
        }
        if ("decision".equals(type)) {
            decisionRetracted = false;
            int answerId = data.path("answerId").asInt();
            answerBases.put(answerId, readySample);
            decisionEventId = Long.toString(++nextEventId);
            decisionAudioEnd = lastCandidateEnd > readySample ? lastCandidateEnd : 0;
            if (lastPeekSample > readySample) decisionAudioEnd = Math.max(decisionAudioEnd, lastPeekSample);
            if (decisionAudioEnd > 0) decisionAudioEnds.put(answerId, decisionAudioEnd);
            decisionAtNanos = System.nanoTime();
            event.put("event_id", decisionEventId);
            if (decisionAudioEnd > 0) {
                event.put("answer_end_s", Support.round(decisionAudioEnd / (double) RATE, 3));
                event.put("end_to_decision_ms", Support.round(
                        (decisionAtNanos - estimateCapturedAt(decisionAudioEnd)) / 1e6, 1));
                decisionLatencies.add(((Number) event.get("end_to_decision_ms")).doubleValue());
            } else {
                event.put("answer_end_s", null);
                event.put("end_to_decision_ms", null);
            }
            decisionTimes.put(answerId, decisionAtNanos);
            recordPreliminaryHintLatency(answerId);
            if (preview != null) {
                preview = null;
                previewEventId = null;
                logQuietly(Support.map("type", "preliminary_hint_retracted", "reason", "final_decision_received"));
            }
            decision = event;
        }
        if ("refined".equals(type)) {
            refined = event;
            whisperState = "получен";
            Long decisionTime = decisionTimes.get(data.path("answerId").asInt());
            if (decisionTime != null) whisperLatencies.add((System.nanoTime() - decisionTime) / 1e6);
            long base = answerBases.getOrDefault(data.path("answerId").asInt(), 0L)
                    + Math.round(data.path("trimStartMs").asDouble() * RATE / 1000.0);
            long maxEnd = base;
            List<Map<String, Object>> refinedWords = new ArrayList<>();
            for (JsonNode word : data.path("words")) {
                long start = base + Math.round(word.path("start").asDouble() * RATE);
                long end = base + Math.round(word.path("end").asDouble() * RATE);
                maxEnd = Math.max(maxEnd, end);
                refinedWords.add(Support.map("word", word.path("word").asText(""),
                        "word_id", "whisper:" + data.path("answerId").asInt() + ":" + refinedWords.size(),
                        "start", Support.round(start / (double) RATE, 3),
                        "end", Support.round(end / (double) RATE, 3),
                        "role", "Кандидат", "role_status", "confirmed", "source", "candidate_gated",
                        "model", "whisper", "status", "final",
                        "answer_id", data.path("answerId").asInt(),
                        "confidence", word.hasNonNull("confidence") ? word.path("confidence").asDouble() : null));
            }
            if (!refinedWords.isEmpty()) {
                for (Map<String, Object> word : finalWords) {
                    if (!"vosk".equals(word.get("model")) || !"Кандидат".equals(word.get("role"))) continue;
                    long position = Math.round(((Number) word.get("start")).doubleValue() * RATE);
                    if (position >= base && position <= maxEnd) word.put("status", "replaced_by_whisper");
                }
                finalWords.addAll(refinedWords);
                writeTimeline();
            }
        }
        if ("service_status".equals(type))
            whisperState = "configured".equals(data.path("whisper").asText()) ? "готов" : "выключен";
        if ("whisper_error".equals(type))
            whisperState = "ошибка: " + data.path("message").asText("неизвестная ошибка");
        if ("finalizing".equals(type)) whisperState = "обработка";
        if ("error".equals(type)) failure = data.path("message").asText();
        event.put("source_service", "analytics");
        event.put("received_wall_s", Support.round((System.nanoTime() - startedNanos) / 1e9, 3));
        emit(event);
        if ("report".equals(type)) {
            Object reportObject = event.get("report");
            if (reportObject instanceof Map<?, ?> values) {
                Map<String, Object> safeReport = new LinkedHashMap<>();
                values.forEach((key, value) -> safeReport.put(String.valueOf(key), value));
                finalReport = safeReport;
            }
            Object excelObject = event.get("excel");
            if (excelObject instanceof Map<?, ?> values) {
                Map<String, Object> excel = new LinkedHashMap<>();
                values.forEach((key, value) -> excel.put(String.valueOf(key), value));
                Object url = excel.get("url");
                if (url instanceof String path && path.startsWith("/")) excel.put("url", analyticsHttpBase + path);
                finalExcel = excel;
            }
            writeSummary(true);
            reportReceived.countDown();
        }
        if ("decision".equals(type)) {
            int answerId = data.path("answerId").asInt(-1);
            byte[] answerAudio;
            synchronized (candidateAnswerAudio) {
                capturingAnswer = false;
                answerAudio = candidateAnswerAudio.toByteArray();
                candidateAnswerAudio.reset();
            }
            transcribeAnswer(answerId, answerAudio);
        }
    }

    private void transcribeAnswer(int answerId, byte[] audioBytes) {
        long audioStartSample = answerBases.getOrDefault(answerId, readySample);
        long audioEndSample = Math.max(audioStartSample, decisionAudioEnds.getOrDefault(answerId, audioStartSample));
        Map<String, Object> answerRange = Support.map("answerId", answerId,
                "audioStartSample", audioStartSample, "audioEndSample", audioEndSample);
        if (answerId < 0 || whisperUrl.isBlank() || audioBytes.length < RATE) {
            sendAnalytics("refine_done", answerRange);
            return;
        }
        whisperState = "обработка";
        CompletableFuture.runAsync(() -> {
            try {
                var request = java.net.http.HttpRequest.newBuilder(URI.create(whisperUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/octet-stream")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(audioBytes)).build();
                var response = client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300)
                    throw new IllegalStateException("Whisper вернул HTTP " + response.statusCode());
                String text = response.body().trim();
                if (!text.isBlank()) {
                    Map<String, Object> refinedEvent = new LinkedHashMap<>(answerRange);
                    refinedEvent.put("text", text);
                    sendAnalytics("refined", refinedEvent);
                }
                whisperState = "получен";
                sendAnalytics("refine_done", answerRange);
            } catch (Throwable error) {
                whisperState = "ошибка: " + error.getMessage();
                Map<String, Object> failedEvent = new LinkedHashMap<>(answerRange);
                failedEvent.put("error", error.toString());
                sendAnalytics("refine_done", failedEvent);
            }
        });
    }

    synchronized Map<String, Object> status() {
        List<Map<String, Object>> words = currentWords();
        int from = Math.max(0, words.size() - 80);
        return Support.map("question", question, "decision", decision, "refined", refined, "preview", preview,
                "report", finalReport, "excel", finalExcel,
                "current_role", currentRole,
                "decision_retracted", decisionRetracted,
                "question_wait_ms", Math.max(0, (questionAvailableNanos - System.nanoTime()) / 1_000_000L),
                "mixed_partial", mixedPartial, "mixed_transcript", mixedTranscript.toString(),
                "timeline_words", new ArrayList<>(words.subList(from, words.size())),
                "whisper_state", whisperState, "bridge_error", failure);
    }

    void finish(double audioSeconds, List<Map<String, Object>> finalSegments, LiveSession.Capture finalCapture) {
        synchronized (this) {
            if (closed || finishing) return;
            finishing = true;
            try {
                onDiarization(finalSegments, List.of(), audioSeconds, true, finalCapture);
            } catch (Throwable flushError) {
                failure = "Ошибка финального сброса хронологии: " + flushError;
                logQuietly(Support.map("type", "bridge_error", "worker", "final_flush", "error", failure));
            }
        }
        try {
            signalEnd(sttQueue, sttWorker, "Vosk");
            signalEnd(candidateQueue, candidateWorker, "анализатора кандидата");
            candidateWorker.join(30_000);
            sttWorker.join(30_000);
            if (candidateWorker.isAlive() || sttWorker.isAlive()) {
                failure = "Одна из очередей аудио не успела завершиться за 30 секунд.";
                candidateWorker.interrupt();
                sttWorker.interrupt();
            }
        } catch (Throwable error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            failure = "Ошибка завершения очередей STT: " + error;
        }
        try {
            sendAnalytics("stop", Map.of());
            if (!reportReceived.await(30, TimeUnit.SECONDS)) failure = "Отчёт сервиса аналитики не получен за 30 секунд.";
        }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); failure = "Ожидание отчёта сервиса аналитики прервано."; }
        catch (Throwable error) { failure = "Не удалось завершить сессию сервиса аналитики: " + error; }
        synchronized (this) { writeSummary(reportReceived.getCount() == 0 && failure == null); }
    }

    private void signalEnd(ArrayBlockingQueue<byte[]> queue, Thread worker, String name) throws InterruptedException {
        if (!worker.isAlive()) return;
        if (!queue.offer(END, 5, TimeUnit.SECONDS)) {
            failure = "Не удалось закрыть очередь " + name + ": worker не освобождает место.";
            worker.interrupt();
        }
    }

    private void writeTimeline() {
        try {
            List<Map<String, Object>> rows = currentWords();
            rows.sort((a, b) -> Double.compare(((Number) a.get("start")).doubleValue(),
                    ((Number) b.get("start")).doubleValue()));
            Support.writePretty(directory.resolve("timeline.json"), rows);
        }
        catch (Exception error) { failure = error.toString(); }
    }

    private List<Map<String, Object>> currentWords() {
        List<Map<String, Object>> rows = new ArrayList<>(finalWords);
        for (List<Map<String, Object>> partial : preliminaryWords.values()) rows.addAll(partial);
        return rows;
    }

    private void writeSummary(boolean finished) {
        try {
            unknownWords = (int) finalWords.stream().filter(w -> "Не определено".equals(w.get("role"))).count();
            LiveSession.Capture snapshot = capture;
            Support.writePretty(directory.resolve("integration-summary.json"), Support.map(
                    "finished", finished, "audio_seconds", Support.round(capturedSamples / (double) RATE, 3),
                    "vosk_events", sttEvents, "final_words", finalWords.size(), "unknown_role_words", unknownWords,
                    "candidate_audio_blocks", candidateBlocks,
                    "late_candidate_segments", lateCandidateSegments, "bridge_error", failure,
                    "capture_queue", snapshot == null ? null : Support.map(
                            "capacity_chunks", snapshot.capacity(), "max_depth_chunks", snapshot.maxDepth(),
                            "overflow_count", snapshot.overflowCount(), "dropped_chunks", snapshot.droppedChunks(),
                            "dropped_audio_seconds", Support.round(snapshot.droppedSamples() / (double) RATE, 3),
                            "stop_reason", snapshot.stopReason()),
                    "stt_queue", Support.map("capacity_chunks", 128, "max_depth_chunks", maxSttQueueDepth,
                            "overflow_count", sttQueueOverflowCount),
                    "candidate_analysis_queue", Support.map("capacity_chunks", CANDIDATE_QUEUE_CAPACITY,
                            "max_depth_chunks", maxCandidateQueueDepth,
                            "overflow_count", candidateQueueOverflowCount,
                            "dropped_audio_seconds", Support.round(candidateQueueDroppedSamples / (double) RATE, 3),
                            "dropped_candidate_speech_seconds", Support.round(candidateQueueDroppedSpeechSamples / (double) RATE, 3)),
                    "cpu_process_load_percent", distribution(cpuLoadPercent),
                    "cpu_system_load_percent", distribution(systemCpuLoadPercent),
                    "end_to_ui_ack_ms", distribution(uiLatencies),
                    "end_to_decision_ms", distribution(decisionLatencies),
                    "end_to_diarization_ms", distribution(diarizationLatencies),
                    "end_to_candidate_gate_ms", distribution(roleWaitLatencies),
                    "end_to_vosk_first_partial_ms", distribution(partialLatencies),
                    "end_to_vosk_final_word_ms", distribution(voskLatencies),
                    "end_to_role_ms", distribution(roleLatencies),
                    "end_to_candidate_dispatch_ms", distribution(candidateDispatchLatencies),
                    "partial_to_preliminary_hint_ui_ms", distribution(previewLatencies),
                    "end_to_preliminary_hint_ms", distribution(endToPreliminaryHintLatencies),
                    "preliminary_hint_lead_ms", distribution(preliminaryHintLeadLatencies),
                    "peek_compute_ms", distribution(peekComputeLatencies),
                    "decision_to_whisper_ms", distribution(whisperLatencies),
                    "outputs", Support.map("stt_events", directory.resolve("stt-events.jsonl").toString(),
                            "timeline_events", directory.resolve("timeline-events.jsonl").toString(),
                            "timeline", directory.resolve("timeline.json").toString())));
        } catch (Exception error) { failure = error.toString(); }
    }

    private void recordPreliminaryHintLatency(int answerId) {
        if (measuredHintAnswers.contains(answerId)) return;
        Long base = answerBases.get(answerId), end = decisionAudioEnds.get(answerId);
        if (base == null || end == null || end <= base) return;
        String latestEvent = null;
        long latestSample = Long.MIN_VALUE;
        for (var entry : previewEventSamples.entrySet()) {
            Long visible = previewEventVisibleNanos.get(entry.getKey());
            long sample = entry.getValue();
            if (visible == null || sample < base || sample > end || sample <= latestSample) continue;
            latestEvent = entry.getKey();
            latestSample = sample;
        }
        if (latestEvent == null) return;
        measuredHintAnswers.add(answerId);
        long speechEndNanos = estimateCapturedAt(end);
        long visibleAt = previewEventVisibleNanos.get(latestEvent);
        double delay = Math.max(0, (visibleAt - speechEndNanos) / 1e6);
        double lead = Math.max(0, (speechEndNanos - visibleAt) / 1e6);
        endToPreliminaryHintLatencies.add(delay);
        if (lead > 0) preliminaryHintLeadLatencies.add(lead);
        logQuietly(Support.map("type", "preliminary_hint_answer_latency", "answer_id", answerId,
                "answer_end_s", Support.round(end / (double) RATE, 3),
                "event_id", latestEvent, "end_to_hint_ms", Support.round(delay, 1),
                "hint_lead_before_end_ms", Support.round(lead, 1)));
    }

    private static long parsePeekRequestOrdinal(String requestId) {
        if (requestId == null || !requestId.startsWith("peek-")) return 0;
        try { return Long.parseLong(requestId.substring(5)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    static boolean isPreviewAtOrBeforeDecision(long previewSample, long decisionAudioEnd) {
        return decisionAudioEnd > 0 && previewSample <= decisionAudioEnd;
    }

    private void sampleCpuLoad() {
        java.lang.management.OperatingSystemMXBean bean = java.lang.management.ManagementFactory
                .getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean processBean)) return;
        while (!closed) {
            double load = processBean.getProcessCpuLoad();
            if (load >= 0 && Double.isFinite(load)) cpuLoadPercent.add(load * 100);
            double systemLoad = processBean.getSystemCpuLoad();
            if (systemLoad >= 0 && Double.isFinite(systemLoad)) systemCpuLoadPercent.add(systemLoad * 100);
            try { Thread.sleep(1000); }
            catch (InterruptedException done) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static Map<String, Object> distribution(List<Double> values) {
        if (values.isEmpty()) return Support.map("count", 0, "p50", null, "p95", null, "max", null);
        return Support.map("count", values.size(), "p50", Support.round(Support.percentile(values, .5), 1),
                "p95", Support.round(Support.percentile(values, .95), 1),
                "max", Support.round(values.stream().mapToDouble(Double::doubleValue).max().orElse(0), 1));
    }

    private void write(BufferedWriter out, Map<String, Object> data) throws Exception {
        String line = Support.JSON.writeValueAsString(data);
        out.write(line); out.newLine(); out.flush();
        System.out.println(line);
    }

    private synchronized void emit(Map<String, Object> data) {
        try { write(timelineLog, data); }
        catch (Exception error) { failure = error.toString(); }
    }

    private void logQuietly(Map<String, Object> data) { emit(data); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        cpuSampler.interrupt();
        try { interview.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) {}
        try { sttLog.close(); } catch (Exception ignored) {}
        try { timelineLog.close(); } catch (Exception ignored) {}
    }

    /** Принимает сообщения одного WebSocket-подключения и собирает их фрагменты.
     *
     * @version 1.0.0
     */
    static final class Peer implements WebSocket.Listener {
        private final Consumer<JsonNode> handler;
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch closed = new CountDownLatch(1);
        private WebSocket socket;
        private final Object sendLock = new Object();
        Peer(Consumer<JsonNode> handler) { this.handler = handler; }
        void attach(WebSocket socket) { this.socket = socket; }
        void sendAudio(byte[] bytes) {
            synchronized (sendLock) {
                socket.sendBinary(ByteBuffer.wrap(bytes), true).join();
            }
        }
        void sendText(String text) {
            synchronized (sendLock) {
                socket.sendText(text, true).join();
            }
        }
        void sendClose(int status, String reason) {
            synchronized (sendLock) {
                socket.sendClose(status, reason).join();
            }
        }
        boolean awaitClosed(int seconds) throws InterruptedException {
            return closed.await(seconds, TimeUnit.SECONDS);
        }
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.countDown();
            return null;
        }
        @Override public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("WebSocket интеграции: " + error);
            closed.countDown();
        }
        @Override public CompletableFuture<?> onText(WebSocket webSocket, CharSequence part, boolean last) {
            text.append(part);
            if (last) {
                try { handler.accept(Support.JSON.readTree(text.toString())); }
                catch (Exception error) { System.err.println("Ошибка события STT: " + error); }
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }
    }
}
