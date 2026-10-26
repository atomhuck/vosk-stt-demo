package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import org.vosk.Model;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Принимает аудио браузера только через loopback; один сеанс владеет парой моделей и worker.
 *
 * @version 1.0.0
 */
final class BrowserMode {
    private static final int CHUNK_SAMPLES = 10240;
    private final Path dll, gguf, speakerModel, outputRoot;
    private final String preset;
    private final int threads, queueChunks;
    private final double threshold, settleSeconds;
    private final boolean integrated;
    private final String analyticsUrl;
    private volatile Interview interview;
    private volatile Model voskModel;

    BrowserMode(Map<String, String> options) { this(options, false); }

    BrowserMode(Map<String, String> options, boolean integrated) {
        this.integrated = integrated;
        this.analyticsUrl = options.getOrDefault("analytics-url", "ws://127.0.0.1:8080");
        dll = Support.path(options, "nemo-lib");
        gguf = Support.path(options, "nemo-model");
        speakerModel = Support.path(options, "speaker-model");
        outputRoot = Support.path(options, "output-dir");
        preset = options.getOrDefault("preset", "v3-streaming");
        threads = Integer.parseInt(options.getOrDefault("num-threads", "4"));
        queueChunks = Integer.parseInt(options.getOrDefault("queue-chunks", "32"));
        threshold = Support.number(options, "threshold", .60);
        settleSeconds = Support.number(options, "settle-seconds", .64);
        if (threads < 1 || queueChunks < 1 || settleSeconds < 0)
            throw new IllegalArgumentException("Проверьте --num-threads, --queue-chunks и --settle-seconds.");
    }

    void run() throws Exception {
        if (integrated) voskModel = VoskRuntime.load();
        Files.createDirectories(outputRoot);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8765), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "browser-watchdog"); t.setDaemon(true); return t;
        });
        watchdog.scheduleAtFixedRate(() -> {
            Interview active = interview;
            if (active != null) active.watchdog();
        }, 5, 5, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            watchdog.shutdownNow();
            Interview active = interview;
            if (active != null) active.requestStop("server_shutdown");
            server.stop(0);
        }));
        System.out.println((integrated ? "Объединённый" : "Браузерный") + " режим готов: http://localhost:8765");
        System.out.println("Выберите весь экран со звуком и разрешите микрофон; эталон берётся из звука компьютера.");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(URI.create("http://localhost:8765"));
        } catch (Exception failure) {
            System.err.println("Браузер не открылся автоматически: " + failure.getMessage());
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (origin != null && !origin.equals("http://localhost:8765") && !origin.equals("http://127.0.0.1:8765"))
                throw new HttpError(403, "Недопустимый источник запроса.");
            String path = exchange.getRequestURI().getPath();
            if (exchange.getRequestMethod().equals("GET")) {
                if (path.equals("/api/status")) {
                    String token = exchange.getRequestHeaders().getFirst("X-Session-Id");
                    reply(exchange, 200, status(token)); return;
                }
                if (path.equals("/") || path.equals("/app.js") || path.equals("/audio-worklet.js")
                        || path.equals("/integrated-app.js") || path.equals("/integrated-worklet.js")
                        || path.equals("/style.css")) {
                    asset(exchange, path.equals("/") ? (integrated ? "integrated.html" : "index.html") : path.substring(1)); return;
                }
            } else if (exchange.getRequestMethod().equals("POST")) {
                if (path.equals("/api/enrollment/start")) {
                    synchronized (this) {
                        if (interview != null && !interview.terminal() && !interview.retryable())
                            throw new HttpError(409, "Интервью уже запущено.");
                        if (interview != null) interview.dispose();
                        interview = new Interview();
                        reply(exchange, 200, interview.status());
                    }
                    return;
                }
                Interview active = interview;
                if (active == null) throw new HttpError(409, "Сначала начните запись эталона.");
                String token = exchange.getRequestHeaders().getFirst("X-Session-Id");
                if (!active.id.equals(token)) throw new HttpError(403, "Неверный идентификатор интервью.");
                if (path.equals("/api/enrollment/audio") || path.equals("/api/audio")) {
                    String sequence = exchange.getRequestHeaders().getFirst("X-Sequence");
                    if (sequence == null) throw new HttpError(400, "Отсутствует номер чанка.");
                    long number;
                    try { number = Long.parseLong(sequence); }
                    catch (NumberFormatException e) { throw new HttpError(400, "Неверный номер чанка."); }
                    int bytesPerSample = 2;
                    byte[] bytes = exchange.getRequestBody().readNBytes(bytesPerSample * CHUNK_SAMPLES + bytesPerSample);
                    if (bytes.length < bytesPerSample || bytes.length > bytesPerSample * CHUNK_SAMPLES
                            || bytes.length % bytesPerSample != 0)
                        throw new HttpError(400, "Ожидается PCM16 16 кГц, до 0,64 с на источник.");
                    if (path.equals("/api/enrollment/audio")) active.enrollAudio(number, bytes);
                    else active.liveAudio(number, bytes);
                    reply(exchange, 200, Support.map("ok", true)); return;
                }
                if (path.equals("/api/enrollment/finish")) {
                    active.finishEnrollment(); reply(exchange, 200, active.status()); return;
                }
                if (integrated && path.equals("/api/question/asked")) {
                    active.questionAsked(); reply(exchange, 200, active.status()); return;
                }
                if (integrated && path.equals("/api/question/skip")) {
                    active.questionSkipped(); reply(exchange, 200, active.status()); return;
                }
                if (integrated && path.equals("/api/tts/event")) {
                    var event = Support.JSON.readTree(exchange.getRequestBody());
                    active.ttsEvent(event.path("event").asText("unknown"), event.path("question").asText(""),
                            event.path("voice").asText(""), event.path("voice_lang").asText(""));
                    reply(exchange, 200, Support.map("ok", true)); return;
                }
                if (integrated && path.equals("/api/ui/seen")) {
                    active.suggestionSeen(exchange.getRequestHeaders().getFirst("X-Event-Id"));
                    reply(exchange, 200, Support.map("ok", true)); return;
                }
                if (path.equals("/api/finish")) {
                    String reason = exchange.getRequestHeaders().getFirst("X-Stop-Reason");
                    if (reason == null) reason = "requested";
                    if (!reason.equals("requested") && !reason.equals("system_audio_lost")
                            && !reason.equals("microphone_lost") && !reason.equals("capture_error"))
                        throw new HttpError(400, "Неверная причина завершения.");
                    active.requestStop(reason); reply(exchange, 200, active.status()); return;
                }
            }
            throw new HttpError(404, "Страница или действие не найдены.");
        } catch (HttpError error) {
            reply(exchange, error.code, Support.map("error", error.getMessage()));
        } catch (Exception error) {
            System.err.println("Ошибка браузерного режима: " + error);
            reply(exchange, 500, Support.map("error", error.getMessage() == null ? error.toString() : error.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private Map<String, Object> status(String token) {
        Interview active = interview;
        return active == null ? Support.map("phase", "idle") : active.status(token);
    }

    private static void reply(HttpExchange exchange, int code, Object data) throws IOException {
        byte[] bytes = Support.JSON.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void asset(HttpExchange exchange, String name) throws IOException {
        try (var resource = BrowserMode.class.getResourceAsStream("/web/" + name)) {
            if (resource == null) throw new HttpError(404, "Файл страницы не найден.");
            byte[] bytes = resource.readAllBytes();
            String type = name.endsWith(".html") ? "text/html" : name.endsWith(".css") ? "text/css" : "text/javascript";
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static float[] samples(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] result = new float[bytes.length / 2];
        for (int i = 0; i < result.length; i++) result[i] = buffer.getShort() / 32768f;
        return result;
    }

    /** Ошибка запроса с кодом ответа локального HTTP API.
     *
     * @version 1.0.0
     */
    private static final class HttpError extends RuntimeException {
        final int code;
        HttpError(int code, String message) { super(message); this.code = code; }
    }

    /**
     * Состояние единственного активного интервью и его ресурсов.
     *
     * @version 1.0.0
     */
    private final class Interview {
        final String id = UUID.randomUUID().toString();
        final SpeakerEmbeddingExtractor extractor;
        final NemotronStream diarizer;
        final long startupAt = System.nanoTime();
        final ByteArrayOutputStream reference = new ByteArrayOutputStream();
        final ArrayBlockingQueue<LiveMicrophone.AudioChunk> queue = new ArrayBlockingQueue<>(queueChunks);
        final Path outputDir;
        volatile String phase = "enrolling", role = "Ожидаем речь", error;
        volatile double audioSeconds;
        volatile long lastContact = System.nanoTime();
        long enrollmentSequence, liveSequence, acceptedSamples, droppedSamples, droppedChunks, overflowCount, maxDepth;
        String stopReason = "running";
        LiveSession session;
        IntegrationBridge bridge;
        Thread worker;
        boolean stopRequested, resourcesClosed;

        Interview() throws Exception {
            outputDir = outputRoot.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                    + "-" + id.substring(0, 8));
            diarizer = new NemotronStream(dll, gguf, preset);
            try { extractor = Support.extractor(speakerModel, threads); }
            catch (Throwable failure) { diarizer.close(); throw failure; }
            System.out.println("Новая браузерная сессия " + id + "; модели загружены один раз.");
        }

        synchronized boolean terminal() { return phase.equals("finished") || phase.equals("error"); }
        synchronized boolean retryable() { return phase.equals("retry"); }
        synchronized Map<String, Object> status() { return status(id); }

        synchronized Map<String, Object> status(String token) {
            if (id.equals(token)) lastContact = System.nanoTime();
            Map<String, Object> result = Support.map("session_id", id, "phase", phase, "role", role,
                    "audio_seconds", Support.round(audioSeconds, 3),
                    "enrollment_seconds", Support.round(reference.size() / (2.0 * Support.RATE), 3),
                    "queue_depth_chunks", queue.size(), "error", error,
                    "output_dir", phase.equals("finished") || phase.equals("error") ? outputDir.toString() : null);
            if (bridge != null) result.put("integration", bridge.status());
            return result;
        }

        synchronized void enrollAudio(long sequence, byte[] bytes) {
            if (!phase.equals("enrolling")) throw new HttpError(409, "Запись эталона сейчас недоступна.");
            if (sequence != enrollmentSequence) throw new HttpError(409, "Нарушен порядок чанков эталона.");
            if (reference.size() + bytes.length > 30L * Support.RATE * 2)
                throw new HttpError(413, "Эталон длиннее 30 секунд.");
            reference.writeBytes(bytes);
            enrollmentSequence++;
            lastContact = System.nanoTime();
        }

        synchronized void finishEnrollment() throws Exception {
            if (!phase.equals("enrolling")) throw new HttpError(409, "Эталон уже завершён.");
            phase = "preparing";
            float[] voice = samples(reference.toByteArray());
            double seconds = voice.length / (double) Support.RATE;
            float[] candidate = Support.embedding(extractor, voice, 0, seconds, 0);
            if (candidate == null) {
                phase = "retry";
                error = "Не удалось получить голосовой эталон. Говорите без длинных пауз около 10 секунд и повторите запись.";
                return;
            }
            try {
                session = new LiveSession(diarizer, extractor, candidate, threshold, outputDir,
                        integrated ? "browser_mixed_microphone_system_audio" : "browser_microphone_system_audio",
                        integrated ? "browser_mixed_stream" : "browser_microphone_system_audio",
                        integrated ? "browser_mixed_microphone_system_audio" : "browser_system_audio",
                        "browser microphone + system audio",
                        Support.RATE, 1,
                        seconds, .64, settleSeconds, (System.nanoTime() - startupAt) / 1e9, preset, metrics());
                if (integrated) bridge = new IntegrationBridge(outputDir, analyticsUrl, metrics(), id, voskModel);
                phase = "live";
                worker = new Thread(this::consume, "nemotron-browser-" + id.substring(0, 8));
                worker.start();
            } catch (Exception failure) {
                phase = "error"; error = failure.toString(); dispose(); throw failure;
            }
        }

        synchronized void liveAudio(long sequence, byte[] bytes) {
            if (!phase.equals("live")) throw new HttpError(409, "Анализ аудио сейчас недоступен.");
            if (sequence != liveSequence) throw new HttpError(409, "Нарушен порядок чанков интервью.");
            float[] chunk = samples(bytes);
            LiveMicrophone.AudioChunk item = new LiveMicrophone.AudioChunk(sequence, acceptedSamples, chunk, System.nanoTime());
            liveSequence++;
            lastContact = System.nanoTime();
            // Пропуск чанка нарушил бы временную шкалу одной сессии Nemotron.
            if (!queue.offer(item)) {
                overflowCount++;
                droppedChunks++;
                droppedSamples += chunk.length;
                error = "Очередь аудио переполнена; интервью остановлено. Результаты сохранены.";
                stopReason = "queue_overflow";
                stopRequested = true;
                phase = "stopping";
                notifyAll();
                throw new HttpError(409, error);
            }
            acceptedSamples += chunk.length;
            maxDepth = Math.max(maxDepth, queue.size());
            if (bridge != null) {
                try { bridge.submit(item.firstSample(), chunk); }
                catch (RuntimeException failure) {
                    error = "Передача звука в STT остановилась: " + failure.getMessage();
                    stopReason = "stt_overflow_or_bridge_error";
                    stopRequested = true;
                    phase = "stopping";
                    notifyAll();
                    throw new HttpError(409, error);
                }
            }
        }

        synchronized void questionAsked() {
            if (bridge == null || !phase.equals("live")) throw new HttpError(409, "Интервью ещё не началось.");
            bridge.questionAsked();
        }

        synchronized void questionSkipped() {
            if (bridge == null || !phase.equals("live")) throw new HttpError(409, "Интервью ещё не началось.");
            bridge.questionSkipped();
        }

        synchronized void suggestionSeen(String id) {
            if (bridge != null) bridge.suggestionSeen(id);
        }

        synchronized void ttsEvent(String event, String question, String voice, String voiceLang) {
            if (bridge != null) bridge.ttsEvent(event, question, voice, voiceLang);
        }

        synchronized void requestStop(String reason) {
            if (phase.equals("live")) phase = "stopping";
            if (phase.equals("enrolling") || phase.equals("retry")) {
                phase = "finished"; dispose(); return;
            }
            if (phase.equals("preparing")) return;
            if (reason.equals("system_audio_lost") || reason.equals("microphone_lost") || reason.equals("capture_error"))
                error = "Источник аудио прервался; обработанная часть интервью сохранена.";
            if (!stopRequested) { stopRequested = true; stopReason = reason; }
            notifyAll();
        }

        private synchronized LiveSession.Capture metrics() {
            return new LiveSession.Capture(acceptedSamples + droppedSamples, queueChunks, maxDepth,
                    overflowCount, droppedChunks, droppedSamples, stopReason, error);
        }

        private void consume() {
            boolean bridgeFinished = false;
            try {
                java.util.ArrayList<LiveMicrophone.AudioChunk> batch = new java.util.ArrayList<>(4);
                long modelChunkIndex = 0;
                while (true) {
                    LiveMicrophone.AudioChunk chunk = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        batch.add(chunk);
                        if (batch.size() >= 4) {
                            LiveMicrophone.AudioChunk merged = AudioFrames.mergeForNemotron(batch, modelChunkIndex++);
                            batch.clear();
                            processNemotronChunk(merged);
                        }
                    }
                    boolean shouldFinish;
                    synchronized (this) { shouldFinish = stopRequested && queue.isEmpty(); }
                    if (shouldFinish) {
                        if (!batch.isEmpty()) {
                            LiveMicrophone.AudioChunk merged = AudioFrames.mergeForNemotron(batch, modelChunkIndex++);
                            batch.clear();
                            processNemotronChunk(merged);
                        }
                        break;
                    }
                }
                session.finish(metrics(), overflowCount == 0 && error == null);
                if (bridge != null) {
                    bridge.finish(audioSeconds, session.allSegments(), metrics());
                    bridgeFinished = true;
                    Object bridgeError = bridge.status().get("bridge_error");
                    if (bridgeError != null && error == null) error = String.valueOf(bridgeError);
                }
                synchronized (this) { phase = error == null ? "finished" : "error"; }
                System.out.println("Файлы результата: " + outputDir);
            } catch (Throwable failure) {
                error = failure.toString(); phase = "error";
                System.err.println("Ошибка обработки интервью: " + failure);
                try { session.finish(metrics(), false); } catch (Exception finalError) { System.err.println(finalError); }
                if (bridge != null && !bridgeFinished) {
                    try { bridge.finish(audioSeconds, session.allSegments(), metrics()); }
                    catch (Throwable bridgeFinishError) { System.err.println("Ошибка завершения STT: " + bridgeFinishError); }
                }
            } finally { dispose(); }
        }

        private void processNemotronChunk(LiveMicrophone.AudioChunk chunk) throws Exception {
            LiveSession.Update update = session.process(chunk, queue.size(), metrics());
            role = update.role(); audioSeconds = update.audioSeconds();
            if (bridge != null) bridge.onDiarization(update.segments(), update.activeSegments(),
                    update.audioSeconds(), false, metrics());
        }

        synchronized void watchdog() {
            if (terminal()) return;
            if ((System.nanoTime() - lastContact) / 1e9 < 15) return;
            if (phase.equals("live")) {
                error = "Вкладка или микрофон перестали передавать звук; интервью остановлено.";
                requestStop("client_disconnected");
            } else if (phase.equals("enrolling") || phase.equals("retry")) {
                phase = "finished"; dispose();
            }
        }

        synchronized void dispose() {
            if (resourcesClosed) return;
            if (worker != null && worker.isAlive() && Thread.currentThread() != worker) return;
            resourcesClosed = true;
            try { if (session != null) session.close(); }
            catch (Exception failure) { System.err.println(failure); }
            if (bridge != null) bridge.close();
            extractor.release();
            diarizer.close();
        }
    }
}
