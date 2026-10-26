# STT и аналитика интервью

Репозиторий содержит два отдельно запускаемых сервиса:

- **STT service** (`stt-service/`) принимает звук браузера, распознаёт речь через Vosk, связывает слова с ролями Nemotron/CAM++ и передаёт речь кандидата аналитике. Whisper запускается как локальный помощник STT для уточнения текста.
- **Interview analytics service** (корень репозитория) управляет вопросами, состояниями ответа, предварительными подсказками, оценкой и XLSX-отчётом. Он получает JSON-события и не загружает аудиомодели.

## Быстрый запуск на Windows x64

Нужны JDK 17+, Maven, Node.js 22+, Python с `faster-whisper` и файлы моделей Vosk/Nemotron/CAM++. Локальный сценарий рассчитан на Windows и PowerShell.

1. Подготовьте модели вне OneDrive. По умолчанию сценарий ожидает `C:\Users\Public\NemotronTest\vosk-model-small-ru-0.22` и файлы Nemotron/CAM++ в `C:\Users\Public\NemotronTest`. Путь к Vosk можно задать через `VOSK_MODEL_PATH`; папка должна содержать `am/final.mdl`.
2. Если полной Vosk-модели ещё нет, её можно скачать вне репозитория: `./download-model.ps1`. Для компактной модели укажите свой путь вручную.
3. Запустите из корня:

   ```powershell
   .\scripts\run-local.ps1
   ```

   Скрипт проверит модели, соберёт и протестирует оба Maven-модуля, затем запустит аналитику, локальный Whisper helper и STT service. Откроется `http://127.0.0.1:8765`. Для тестового прогона канала Whisper без модели используйте `-WhisperBackend fake`; для запуска без Whisper используйте `-DisableWhisper`; готовые JAR можно использовать с `-SkipBuild`.

Расположение моделей, Python и Java можно переопределить параметрами `-NemotronHome`, `-VoskModelPath`, `-WhisperPython`, `-WhisperModel`, `-JavaExecutable` и `-OutputDirectory`. Если существует `C:\Users\Public\NemotronTest\java-runtime\bin\java.exe`, сценарий использует его; иначе берёт Java из `PATH`. Это позволяет запустить Nemotron с подготовленным совместимым Microsoft C++ Runtime. Сценарий генерирует общий секрет внутреннего WebSocket на время запуска и привязывает локальные сервисы к loopback.

## Сборка и тесты

```powershell
mvn -B test package javadoc:javadoc
mvn -B -f stt-service/pom.xml test package javadoc:javadoc
node --test stt-service/src/test/js/*.test.cjs
```

Проверка контракта API выполняется закреплённым Redocly CLI 2.71.1: `npx --yes @redocly/cli@2.71.1 lint docs/api/*.openapi.yaml`.

## Архитектура и контракты

- [Архитектура и объединение Vosk с диаризацией](docs/architecture.md)
- [Локальный запуск, конфигурация и диагностика](docs/setup-windows.md)
- [Контракт внутреннего WebSocket STT → аналитика](docs/api/interview-events.md)
- [OpenAPI STT service](docs/api/stt.openapi.yaml)
- [OpenAPI interview analytics](docs/api/analytics.openapi.yaml)
- [Тестирование и ограничения проверки](docs/testing.md)

Порты локального запуска: STT/browser `8765`, аналитика `8080`, внутренний Whisper helper `9000` (только `127.0.0.1`). Whisper не является третьим микросервисом и не принимает внешние запросы. Старые аналитические WebSocket-маршруты `/ws/stt` и `/ws/interview` удалены; интеграция использует только защищённый `/ws/interview-events`.
