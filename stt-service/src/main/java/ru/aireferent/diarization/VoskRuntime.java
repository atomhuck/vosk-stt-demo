package ru.aireferent.diarization;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;

import java.nio.file.Files;
import java.nio.file.Path;

/** Загружает единственную общую Vosk-модель в STT-процессе. */
final class VoskRuntime {
    private static Model model;

    private VoskRuntime() {}

    static synchronized Model load() throws Exception {
        if (model != null) return model;
        String configured = System.getenv("VOSK_MODEL_PATH");
        Path path = Path.of(configured == null || configured.isBlank()
                ? "./models/vosk-model-ru-0.42" : configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path.resolve("am/final.mdl")))
            throw new IllegalStateException("Модель Vosk не найдена: " + path);
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        model = new Model(path.toString());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (VoskRuntime.class) {
                if (model != null) model.close();
                model = null;
            }
        }, "vosk-model-shutdown"));
        return model;
    }
}
