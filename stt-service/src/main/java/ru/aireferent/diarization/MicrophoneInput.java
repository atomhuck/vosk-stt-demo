package ru.aireferent.diarization;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Источник моноаудио с частотой 16 кГц для живой сессии.
 *
 * @version 1.0.0
 */
interface SampleSource {
    /** Начинает чтение с новой временной шкалы. */
    void startFresh();
    /**
     * Читает заданное число сэмплов.
     *
     * @param outputFrames число выходных сэмплов
     * @return моноаудио с частотой 16 кГц
     */
    float[] readSamples(int outputFrames);
    /** Останавливает захват и удаляет непрочитанный звук. */
    void pause();
}

/**
 * Записывает PCM микрофона и преобразует его в моноаудио 16 кГц.
 *
 * @version 1.0.0
 */
final class MicrophoneInput implements SampleSource, AutoCloseable {
    private static final int[] SOURCE_RATES = {16000, 48000, 44100};
    private final TargetDataLine line;
    private final AudioFormat format;
    private final String name;
    private final double sourceFramesPerOutputFrame;
    private float[] decoded = new float[8192];
    private long firstDecodedFrame;
    private int decodedFrames;
    private double nextSourceFrame;

    private MicrophoneInput(TargetDataLine line, AudioFormat format, String name) {
        this.line = line;
        this.format = format;
        this.name = name;
        sourceFramesPerOutputFrame = format.getSampleRate() / Support.RATE;
    }

    static MicrophoneInput open(String requestedName) throws LineUnavailableException {
        if (requestedName == null || requestedName.isBlank()) {
            for (AudioFormat format : formats()) {
                TargetDataLine line = null;
                try {
                    line = AudioSystem.getTargetDataLine(format);
                    line.open(format, Math.max(format.getFrameSize() * (int) format.getSampleRate(), 4096));
                    return new MicrophoneInput(line, format, "системный микрофон по умолчанию");
                } catch (IllegalArgumentException | LineUnavailableException ignored) {
                    if (line != null) line.close();
                    // Формат не открылся: проверяем следующий формат или устройство.
                }
            }
        }
        List<String> available = new ArrayList<>();
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            boolean capture = false;
            for (AudioFormat format : formats()) {
                DataLine.Info dataLine = new DataLine.Info(TargetDataLine.class, format);
                if (!mixer.isLineSupported(dataLine)) continue;
                capture = true;
                if (requestedName != null && !info.getName().toLowerCase(Locale.ROOT)
                        .contains(requestedName.toLowerCase(Locale.ROOT))) continue;
                TargetDataLine line = null;
                try {
                    line = (TargetDataLine) mixer.getLine(dataLine);
                    line.open(format, Math.max(format.getFrameSize() * (int) format.getSampleRate(), 4096));
                    return new MicrophoneInput(line, format, info.getName());
                } catch (IllegalArgumentException | LineUnavailableException ignored) {
                    if (line != null) line.close();
                    // Устройство заявляет поддержку формата, но открыть его не смогло.
                }
            }
            if (capture) available.add(info.getName());
        }
        throw new LineUnavailableException("Не удалось открыть микрофон PCM16. Доступные устройства: "
                + available + ". Выполните режим live-devices и при необходимости укажите --mic-name.");
    }

    static void printDevices() {
        System.out.println("Устройства захвата, видимые Java Sound:");
        boolean found = false;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(info);
            List<String> supported = new ArrayList<>();
            for (AudioFormat format : formats()) {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format)))
                    supported.add((int) format.getSampleRate() + " Гц / " + format.getChannels() + " канал(а)");
            }
            if (!supported.isEmpty()) {
                found = true;
                System.out.println("- " + info.getName() + ": " + String.join(", ", supported));
            }
        }
        if (!found) System.out.println("Нет доступных устройств. Проверьте разрешение Windows на доступ к микрофону.");
    }

    private static List<AudioFormat> formats() {
        List<AudioFormat> formats = new ArrayList<>();
        for (int rate : SOURCE_RATES) {
            formats.add(new AudioFormat(rate, 16, 1, true, false));
            formats.add(new AudioFormat(rate, 16, 2, true, false));
        }
        return formats;
    }

    String name() { return name; }
    AudioFormat format() { return format; }

    @Override public void startFresh() {
        line.stop();
        line.flush();
        firstDecodedFrame = 0;
        decodedFrames = 0;
        nextSourceFrame = 0;
        line.start();
    }

    @Override public void pause() {
        line.stop();
        line.flush();
    }

    @Override public float[] readSamples(int outputFrames) {
        float[] output = new float[outputFrames];
        for (int i = 0; i < outputFrames; i++) {
            long frame = (long) Math.floor(nextSourceFrame);
            while (frame + 1 >= firstDecodedFrame + decodedFrames) readMore();
            int position = (int) (frame - firstDecodedFrame);
            double fraction = nextSourceFrame - frame;
            output[i] = (float) (decoded[position] * (1 - fraction) + decoded[position + 1] * fraction);
            nextSourceFrame += sourceFramesPerOutputFrame;
        }
        compact();
        return output;
    }

    private void readMore() {
        int frames = Math.max(1024, (int) Math.ceil(format.getSampleRate() * .10));
        byte[] bytes = new byte[frames * format.getFrameSize()];
        int got = 0;
        while (got < bytes.length) {
            int count = line.read(bytes, got, bytes.length - got);
            if (count < 0) throw new IllegalStateException("Поток микрофона завершился.");
            if (count == 0) continue;
            got += count;
        }
        ensureCapacity(decodedFrames + frames);
        int channels = format.getChannels();
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0;
            for (int channel = 0; channel < channels; channel++) {
                int offset = (frame * channels + channel) * 2;
                short pcm = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
                sum += pcm / 32768f;
            }
            decoded[decodedFrames++] = sum / channels;
        }
    }

    private void ensureCapacity(int size) {
        if (size > decoded.length) decoded = java.util.Arrays.copyOf(decoded, Math.max(size, decoded.length * 2));
    }

    private void compact() {
        long keepFrom = Math.max(firstDecodedFrame, (long) Math.floor(nextSourceFrame) - 1);
        int remove = (int) Math.min(decodedFrames, keepFrom - firstDecodedFrame);
        if (remove > 0) {
            System.arraycopy(decoded, remove, decoded, 0, decodedFrames - remove);
            decodedFrames -= remove;
            firstDecodedFrame += remove;
        }
    }

    @Override public void close() { line.close(); }
}
