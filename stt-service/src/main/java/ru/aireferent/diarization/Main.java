package ru.aireferent.diarization;

import java.util.Arrays;
import java.util.Map;

/**
 * Выбирает режим worker по первому аргументу командной строки.
 *
 * @version 1.0.0
 */
public final class Main {
    private Main() {}

    /**
     * Запускает выбранный режим или выводит справку.
     *
     * @param args режим и его параметры; пустой массив выводит справку
     */
    public static void main(String[] args) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
                System.out.println("Использование: java -jar diarization-worker-1.0.0.jar browser|integrated|live|live-devices|stream|verify [параметры]\n"
                        + "browser: --nemo-lib DLL --nemo-model GGUF --speaker-model ONNX --output-dir DIR [--queue-chunks N]\n"
                        + "integrated: те же параметры, плюс [--analytics-url ws://127.0.0.1:8080]\n"
                        + "live: --nemo-lib DLL --nemo-model GGUF --speaker-model ONNX --output-dir DIR [--mic-name NAME --queue-chunks N]\n"
                        + "live-devices: показать доступные микрофоны\n"
                        + "stream: --audio WAV --nemo-lib DLL --nemo-model GGUF --speaker-model ONNX --output-dir DIR\n"
                        + "verify: --audio WAV --diarization-json JSON --model ONNX [--output JSON --csv CSV]\n"
                        + "Подробности: stt-service/README.md");
                return;
            }
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            Map<String, String> options = Support.options(rest);
            switch (args[0]) {
                case "browser" -> new BrowserMode(options).run();
                case "integrated" -> new BrowserMode(options, true).run();
                case "live" -> new LiveMicrophone(options).run();
                case "live-devices" -> MicrophoneInput.printDevices();
                case "stream" -> new StreamReplay(options).run();
                case "verify" -> new OfflineVerify(options).run();
                default -> throw new IllegalArgumentException("Неизвестный режим: " + args[0]);
            }
        } catch (Exception | LinkageError error) {
            System.err.println("Ошибка: " + error.getMessage());
            System.exit(1);
        }
    }
}
