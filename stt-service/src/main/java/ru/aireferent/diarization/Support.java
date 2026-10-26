package ru.aireferent.diarization;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Общие операции чтения аудио, эмбеддингов, параметров и JSON.
 *
 * @version 1.0.0
 */
final class Support {
    static final int RATE = 16000;
    static final String MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
            + "speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx";
    static final ObjectMapper JSON = new ObjectMapper();

    private Support() {}

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("Неизвестный аргумент: " + args[i]);
            String key = args[i].substring(2);
            if (key.equals("save-chunks")) result.put(key, "true");
            else {
                if (++i == args.length) throw new IllegalArgumentException("Нет значения для --" + key);
                result.put(key, args[i]);
            }
        }
        return result;
    }

    static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) throw new IllegalArgumentException("Не указан --" + name);
        return value;
    }

    static Path path(Map<String, String> options, String name) {
        return Path.of(required(options, name)).toAbsolutePath().normalize();
    }

    static double number(Map<String, String> options, String name, double fallback) {
        return Double.parseDouble(options.getOrDefault(name, Double.toString(fallback)));
    }

    static float[] readWav(Path path) throws Exception {
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat f = stream.getFormat();
            if (f.getSampleRate() != RATE || f.getSampleSizeInBits() != 16
                    || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || f.isBigEndian()) {
                throw new IllegalArgumentException("Ожидается несжатый WAV PCM16 16 кГц, little-endian.");
            }
            int channels = f.getChannels();
            if (channels < 1) throw new IllegalArgumentException("В WAV нет каналов.");
            byte[] bytes = stream.readAllBytes();
            int frames = bytes.length / (2 * channels);
            float[] mono = new float[frames];
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int offset = 2 * (frame * channels + channel);
                    short sample = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
                    sum += sample;
                }
                mono[frame] = Math.max(-1f, Math.min(1f, sum / channels / 32768f));
            }
            return mono;
        }
    }

    static void writeWav(Path path, float[] samples) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        byte[] bytes = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            int pcm = (int) Math.max(-32768, Math.min(32767, samples[i] * 32767));
            bytes[i * 2] = (byte) pcm;
            bytes[i * 2 + 1] = (byte) (pcm >>> 8);
        }
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new java.io.ByteArrayInputStream(bytes), format, samples.length)) {
            AudioSystem.write(in, javax.sound.sampled.AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    static SpeakerEmbeddingExtractor extractor(Path model, int threads) {
        if (!Files.isRegularFile(model)) throw new IllegalArgumentException("Не найдена ONNX-модель эмбеддингов: " + model);
        SpeakerEmbeddingExtractorConfig cfg = SpeakerEmbeddingExtractorConfig.builder()
                .setModel(model.toString()).setNumThreads(threads).setDebug(false).build();
        return new SpeakerEmbeddingExtractor(cfg);
    }

    static float[] embedding(SpeakerEmbeddingExtractor extractor, float[] audio,
                             double start, double end, double trim) {
        if (end - start > 2 * trim + 0.2) { start += trim; end -= trim; }
        int first = Math.max(0, (int) Math.rint(start * RATE));
        int last = Math.min(audio.length, (int) Math.rint(end * RATE));
        if (last - first < (int) (.65 * RATE)) return null;
        float[] signal = Arrays.copyOfRange(audio, first, last);
        double power = 0;
        for (float sample : signal) power += (double) sample * sample;
        if (Math.sqrt(power / signal.length) < .003) return null;
        OnlineStream stream = extractor.createStream();
        try {
            stream.acceptWaveform(signal, RATE);
            stream.inputFinished();
            float[] result = extractor.compute(stream);
            if (result == null || result.length == 0) return null;
            for (float value : result) if (!Float.isFinite(value)) return null;
            return normalize(result);
        } finally {
            stream.release();
        }
    }

    static float[] normalize(float[] values) {
        double norm = 0;
        for (float value : values) norm += (double) value * value;
        norm = Math.max(Math.sqrt(norm), 1e-12);
        float[] result = values.clone();
        for (int i = 0; i < result.length; i++) result[i] = (float) (result[i] / norm);
        return result;
    }

    static double dot(float[] left, float[] right) {
        double result = 0;
        for (int i = 0; i < left.length; i++) result += (double) left[i] * right[i];
        return result;
    }

    static float[] meanPrototype(List<float[]> embeddings) {
        float[] mean = new float[embeddings.get(0).length];
        for (float[] embedding : embeddings) for (int i = 0; i < mean.length; i++) mean[i] += embedding[i];
        for (int i = 0; i < mean.length; i++) mean[i] /= embeddings.size();
        return normalize(mean);
    }

    static double round(double value, int digits) {
        double scale = Math.pow(10, digits);
        return Math.rint(value * scale) / scale;
    }

    static List<double[]> split(double start, double end, double refStart, double refEnd) {
        if (end <= refStart || start >= refEnd) return List.of(new double[]{start, end});
        List<double[]> pieces = new ArrayList<>();
        if (start < refStart) pieces.add(new double[]{start, refStart});
        if (end > refEnd) pieces.add(new double[]{refEnd, end});
        return pieces;
    }

    static double percentile(List<Double> values, double fraction) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        double position = (sorted.size() - 1) * fraction;
        int low = (int) Math.floor(position), high = (int) Math.ceil(position);
        return sorted.get(low) + (sorted.get(high) - sorted.get(low)) * (position - low);
    }

    static void writePretty(Path path, Object data) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        printer = printer.withSeparators(Separators.createDefaultInstance()
                .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                .withArrayEmptySeparator("").withObjectEmptySeparator(""));
        String pretty = JSON.writer(printer).writeValueAsString(data) + "\n";
        Files.writeString(path, pretty.replace("\n", System.lineSeparator()), StandardCharsets.UTF_8);
    }

    static Map<String, Object> readMap(Path path) throws IOException {
        return JSON.readValue(path.toFile(), new TypeReference<>() {});
    }
}
