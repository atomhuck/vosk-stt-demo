package ru.example.interview.engine;

/**
 * Определяет «мягкий» конец ответа по тишине (энергетический VAD с адаптивным порогом шума).
 * Время считается по числу обработанных сэмплов, а не по часам — поэтому поведение детерминировано и тестируется.
 *
 * Именно здесь живёт основная часть задержки решения: чем короче нужная тишина, тем быстрее реакция,
 * но тем выше риск прервать человека посреди паузы. Поэтому порог адаптивный:
 * после союзов («и», «потому что», «например») и после очень коротких фраз ждём дольше.
 */
public final class EndpointDetector {

    public enum State { WAITING, SPEAKING, ENDPOINT, NO_SPEECH }

    public static final class Config {
        public int sampleRate = 16000;
        public int baseSilenceMs = 450;
        public int connectorExtraMs = 350;
        public int shortAnswerExtraMs = 350;
        public int shortAnswerWords = 4;
        public int minSilenceMs = 300;
        public int maxSilenceMs = 1400;
        public int startVoicedFrames = 2;
        /** Сколько голосовых кадров подряд после «конца ответа» считать возобновлением речи (щелчок мыши — не речь). */
        public int resumeVoicedFrames = 3;
        public int noSpeechTimeoutMs = 15000;
        public int maxAnswerMs = 180000;
        public double absMinRms = 250;
        public double snrFactor = 2.5;
        public double floorAlpha = 0.08;
    }

    private final Config c;
    private long samples;
    private long speechStart;
    private long lastVoice;
    private long runStart;
    private int voicedRun;
    private int resumeRun;
    private double floor;
    private State state;

    public EndpointDetector(Config config) {
        this.c = config;
        reset();
    }

    public void reset() {
        samples = 0;
        speechStart = -1;
        lastVoice = -1;
        runStart = 0;
        voicedRun = 0;
        resumeRun = 0;
        floor = c.absMinRms / 2;
        state = State.WAITING;
    }

    /**
     * @param pcm              16 бит little-endian, моно
     * @param partialWords     сколько слов уже распознано (из промежуточного результата)
     * @param endsWithConnector последнее слово — союз/предлог (человек, скорее всего, продолжит)
     */
    public State onFrame(byte[] pcm, int len, int partialWords, boolean endsWithConnector) {
        int n = len / 2;
        if (n == 0) return state;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / n);
        return onFrameRms(n, rms, partialWords, endsWithConnector);
    }

    /** Обрабатывает измерение кадра без передачи сырого PCM в сервис аналитики. */
    public State onFrameRms(int n, double rms, int partialWords, boolean endsWithConnector) {
        if (state == State.NO_SPEECH || n == 0) return state;
        long frameStart = samples;
        samples += n;

        boolean voiced = rms > Math.max(c.absMinRms, floor * c.snrFactor);
        if (!voiced) {
            floor = Math.min(2000, Math.max(10, floor * (1 - c.floorAlpha) + rms * c.floorAlpha));
        }

        if (state == State.WAITING) {
            if (voiced) {
                if (voicedRun == 0) runStart = frameStart;
                voicedRun++;
                if (voicedRun >= c.startVoicedFrames) {
                    state = State.SPEAKING;
                    speechStart = runStart;
                    lastVoice = samples;
                }
            } else {
                voicedRun = 0;
            }
            if (state == State.WAITING && toMs(samples) >= c.noSpeechTimeoutMs) {
                state = State.NO_SPEECH;
            }
            return state;
        }

        // SPEAKING или ENDPOINT. ENDPOINT — «мягкий» конец: он НЕ окончателен, речь может возобновиться.
        if (voiced) {
            if (state == State.ENDPOINT) {
                resumeRun++;
                if (resumeRun >= c.resumeVoicedFrames) {      // человек продолжил говорить
                    state = State.SPEAKING;
                    resumeRun = 0;
                    lastVoice = samples;
                }
            } else {
                lastVoice = samples;
            }
        } else {
            resumeRun = 0;
            if (state == State.SPEAKING) {
                int need = c.baseSilenceMs;
                if (endsWithConnector) need += c.connectorExtraMs;
                if (partialWords < c.shortAnswerWords) need += c.shortAnswerExtraMs;
                need = Math.max(c.minSilenceMs, Math.min(c.maxSilenceMs, need));
                if (toMs(samples - lastVoice) >= need) {
                    state = State.ENDPOINT;
                }
            }
        }
        if (state == State.SPEAKING && toMs(samples - speechStart) >= c.maxAnswerMs) {
            state = State.ENDPOINT;
        }
        return state;
    }

    private long toMs(long s) {
        return s * 1000L / c.sampleRate;
    }

    public State state() {
        return state;
    }

    public long audioMs() {
        return toMs(samples);
    }

    public long speechStartMs() {
        return speechStart < 0 ? audioMs() : toMs(speechStart);
    }

    public long speechDurationMs() {
        return speechStart < 0 ? 0 : toMs(lastVoice - speechStart);
    }

    public long silenceMs() {
        return lastVoice < 0 ? 0 : toMs(samples - lastVoice);
    }
}
