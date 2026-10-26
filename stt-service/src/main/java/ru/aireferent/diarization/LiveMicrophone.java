package ru.aireferent.diarization;

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Записывает эталон и интервью с одного микрофона в непрерывную сессию Nemotron.
 *
 * @version 1.0.0
 */
final class LiveMicrophone {
    private static final int ENROLL_SECONDS = 10;
    private final Path dll, gguf, speakerModel, outputDir;
    private final String preset, microphoneName;
    private final double chunkSeconds, settleSeconds, maxSeconds, threshold;
    private final int threads, queueChunks;
    private final AtomicBoolean stop = new AtomicBoolean();
    private CapturePump capture;

    LiveMicrophone(Map<String, String> options) {
        dll = Support.path(options, "nemo-lib");
        gguf = Support.path(options, "nemo-model");
        speakerModel = Support.path(options, "speaker-model");
        outputDir = Support.path(options, "output-dir");
        microphoneName = options.get("mic-name");
        preset = options.getOrDefault("preset", "v3-streaming");
        chunkSeconds = Support.number(options, "chunk-seconds", .64);
        settleSeconds = Support.number(options, "settle-seconds", .64);
        maxSeconds = Support.number(options, "max-seconds", 0);
        threshold = Support.number(options, "threshold", .60);
        threads = Integer.parseInt(options.getOrDefault("num-threads", "4"));
        queueChunks = Integer.parseInt(options.getOrDefault("queue-chunks", "32"));
        if (chunkSeconds <= 0 || settleSeconds < 0 || maxSeconds < 0 || threads < 1)
            throw new IllegalArgumentException("Проверьте --chunk-seconds, --settle-seconds, --max-seconds и --num-threads.");
        if (queueChunks < 1) throw new IllegalArgumentException("--queue-chunks должен быть не меньше 1.");
        if (Math.rint(chunkSeconds * Support.RATE) < 1)
            throw new IllegalArgumentException("--chunk-seconds меньше одного сэмпла.");
    }


    private static void countdown(String prefix, int seconds) throws InterruptedException {
        for (int remaining = seconds; remaining > 0; remaining--) {
            System.out.println(prefix + remaining);
            Thread.sleep(1000);
        }
    }

    private static float[] enroll(MicrophoneInput microphone) throws Exception {
        System.out.println("Через три секунды начнётся запись эталона кандидата с микрофона.");
        countdown("До начала записи: ", 3);
        System.out.println("Говорите своим обычным голосом 10 секунд. Во время записи говорите только вы.");
        float[] reference = new float[ENROLL_SECONDS * Support.RATE];
        microphone.startFresh();
        try {
            for (int second = 0; second < ENROLL_SECONDS; second++) {
                int remaining = ENROLL_SECONDS - second;
                if (remaining <= 3) System.out.println("До конца записи: " + remaining);
                float[] next = microphone.readSamples(Support.RATE);
                System.arraycopy(next, 0, reference, second * Support.RATE, next.length);
            }
        } finally {
            microphone.pause();
        }
        return reference;
    }

    private LiveSession.Capture metrics() {
        return new LiveSession.Capture(capture.capturedSamples(), queueChunks, capture.maxDepth(),
                capture.overflowCount(), capture.droppedChunks(), capture.droppedSamples(),
                capture.stopReason(), capture.failure() == null ? null : capture.failure().toString());
    }

    Map<String, Object> run() throws Exception {
        Files.createDirectories(outputDir);
        System.out.println("Открываю микрофон и загружаю модели на CPU...");
        long startupAt = System.nanoTime();
        try (MicrophoneInput microphone = MicrophoneInput.open(microphoneName);
             NemotronStream diarizer = new NemotronStream(dll, gguf, preset)) {
            SpeakerEmbeddingExtractor extractor = Support.extractor(speakerModel, threads);
            try {
                double startupSeconds = (System.nanoTime() - startupAt) / 1e9;
                System.out.println("Микрофон: " + microphone.name() + "; формат: " + microphone.format());
                float[] reference = enroll(microphone);
                float[] candidate = Support.embedding(extractor, reference, 0, ENROLL_SECONDS, 0);
                if (candidate == null) throw new IllegalStateException(
                        "Не удалось получить голосовой эталон. Проверьте микрофон и повторите запись, говоря все 10 секунд.");
                int chunkSamples = (int) Math.rint(chunkSeconds * Support.RATE);
                capture = new CapturePump(microphone, chunkSamples, queueChunks, maxSeconds, stop);
                try (LiveSession session = new LiveSession(diarizer, extractor, candidate, threshold, outputDir,
                        "microphone:" + microphone.name(), "live_microphone", "live_microphone", microphone.name(),
                        (int) microphone.format().getSampleRate(), microphone.format().getChannels(),
                        ENROLL_SECONDS, chunkSeconds, settleSeconds, startupSeconds, preset, metrics())) {
                    System.out.println("Эталон готов. Начинаю слушать микрофон. Для завершения нажмите Enter в окне Run.");
                    startStopListener();
                    capture.start();
                    try {
                        while (!capture.done() || capture.queueSize() > 0) {
                            AudioChunk chunk = capture.poll();
                            if (chunk != null) session.process(chunk, capture.queueSize(), metrics());
                        }
                    } finally {
                        stop.set(true);
                        capture.join();
                    }
                    Map<String, Object> result = session.finish(metrics(),
                            capture.overflowCount() == 0 && capture.failure() == null);
                    System.out.println("Файлы результата: " + outputDir);
                    if (capture.overflowCount() > 0)
                        throw new IllegalStateException("Очередь микрофона переполнена; запись остановлена. "
                                + "Потеря учтена в summary.json. Увеличьте --queue-chunks или ускорьте обработку CPU.");
                    if (capture.failure() != null)
                        throw new IllegalStateException("Ошибка захвата микрофона; подробности в summary.json.", capture.failure());
                    return result;
                }
            } finally {
                extractor.release();
            }
        }
    }

    private void startStopListener() {
        Thread listener = new Thread(() -> {
            try {
                if (System.in.read() >= 0) stop.set(true);
            } catch (Exception ignored) {
                // Закрытая консоль IDE не должна прерывать сеанс микрофона.
            }
        }, "stop-on-enter");
        listener.setDaemon(true);
        listener.start();
    }

    /** Кадр аудио с позицией во временной шкале и временем захвата.
     *
     * @version 1.0.0
     */
    record AudioChunk(long index, long firstSample, float[] samples, long capturedAtNanos) {}

    /**
     * Отделяет чтение микрофона от обработки Nemotron ограниченной очередью.
     * Только этот поток читает микрофон; основной поток владеет моделью и файлами.
     *
     * @version 1.0.0
     */
    static final class CapturePump {
        private final SampleSource microphone;
        private final int chunkSamples;
        private final long maxSamples;
        private final AtomicBoolean stop;
        private final ArrayBlockingQueue<AudioChunk> queue;
        private final AtomicLong capturedSamples = new AtomicLong();
        private final AtomicLong droppedSamples = new AtomicLong();
        private final AtomicLong droppedChunks = new AtomicLong();
        private final AtomicLong overflowCount = new AtomicLong();
        private final AtomicLong maxDepth = new AtomicLong();
        private final Thread thread;
        private volatile boolean done;
        private volatile Throwable failure;
        private volatile String stopReason = "running";

        CapturePump(SampleSource microphone, int chunkSamples, int capacity, double maxSeconds,
                    AtomicBoolean stop) {
            this.microphone = microphone;
            this.chunkSamples = chunkSamples;
            this.maxSamples = maxSeconds == 0 ? Long.MAX_VALUE : Math.round(maxSeconds * Support.RATE);
            this.stop = stop;
            this.queue = new ArrayBlockingQueue<>(capacity);
            this.thread = new Thread(this::capture, "microphone-capture");
        }

        void start() { thread.start(); }
        void join() throws InterruptedException { thread.join(); }
        AudioChunk poll() throws InterruptedException { return queue.poll(200, TimeUnit.MILLISECONDS); }
        boolean done() { return done; }
        int queueSize() { return queue.size(); }
        long capturedSamples() { return capturedSamples.get(); }
        long droppedSamples() { return droppedSamples.get(); }
        long droppedChunks() { return droppedChunks.get(); }
        long overflowCount() { return overflowCount.get(); }
        long maxDepth() { return maxDepth.get(); }
        Throwable failure() { return failure; }
        String stopReason() { return stopReason; }

        private void capture() {
            long index = 0;
            try {
                microphone.startFresh();
                while (true) {
                    if (stop.get()) { stopReason = "requested"; break; }
                    long first = capturedSamples.get();
                    if (first >= maxSamples) { stopReason = "max_seconds"; break; }
                    int count = (int) Math.min(chunkSamples, maxSamples - first);
                    float[] samples = microphone.readSamples(count);
                    long capturedAt = System.nanoTime();
                    capturedSamples.addAndGet(count);
                    if (!queue.offer(new AudioChunk(index++, first, samples, capturedAt))) {
                        overflowCount.incrementAndGet();
                        droppedChunks.incrementAndGet();
                        droppedSamples.addAndGet(count);
                        stopReason = "queue_overflow";
                        stop.set(true);
                        System.err.println("Очередь аудио заполнена: захват остановлен, потерян один чанк. "
                                + "Подробности будут в summary.json.");
                        break;
                    }
                    maxDepth.accumulateAndGet(queue.size(), Math::max);
                }
            } catch (Throwable error) {
                failure = error;
                stopReason = "capture_error";
                stop.set(true);
            } finally {
                microphone.pause();
                done = true;
            }
        }
    }


}
