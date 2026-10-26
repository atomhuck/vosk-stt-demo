param(
    [string]$NemotronHome = $env:NEMOTRON_HOME,
    [string]$VoskModelPath = $env:VOSK_MODEL_PATH,
    [string]$WhisperPython = $env:WHISPER_PYTHON,
    [string]$WhisperModel = "small",
    [string]$JavaExecutable = "",
    [ValidateSet("whisper", "fake")]
    [string]$WhisperBackend = "whisper",
    [string]$OutputDirectory = "",
    [switch]$DisableWhisper,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$Repository = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PublicData = Join-Path $env:PUBLIC "NemotronTest"
if ([string]::IsNullOrWhiteSpace($JavaExecutable)) {
    $PreparedJava = Join-Path $PublicData "java-runtime/bin/java.exe"
    $JavaExecutable = if (Test-Path -LiteralPath $PreparedJava) { $PreparedJava }
        else { (Get-Command java -ErrorAction Stop).Source }
} elseif (-not (Test-Path -LiteralPath $JavaExecutable -PathType Leaf)) {
    throw "Java executable not found: $JavaExecutable"
} else {
    $JavaExecutable = (Resolve-Path -LiteralPath $JavaExecutable).Path
}
if ([string]::IsNullOrWhiteSpace($NemotronHome)) { $NemotronHome = $PublicData }
if ([string]::IsNullOrWhiteSpace($VoskModelPath)) { $VoskModelPath = Join-Path $PublicData "vosk-model-small-ru-0.22" }
if ([string]::IsNullOrWhiteSpace($WhisperPython)) { $WhisperPython = Join-Path $PublicData "whisper-venv/Scripts/python.exe" }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $PublicData "integrated-runs" }

$VoskModelPath = [IO.Path]::GetFullPath($VoskModelPath)
$NemotronHome = [IO.Path]::GetFullPath($NemotronHome)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$AnalyticsJar = Join-Path $Repository "target/interview-analytics-service-1.0.0.jar"
$SttJar = Join-Path $Repository "stt-service/target/stt-diarization-service-1.0.0.jar"
$WhisperScript = Join-Path $Repository "stt-service/tools/whisper_server.py"
$NemoDll = Join-Path $NemotronHome "runtime/bin/nemo_speech_asr_c.dll"
$NemoModel = Join-Path $NemotronHome "model.gguf"
$SpeakerModel = Join-Path $NemotronHome "speaker_embedding_model.onnx"
$LogDirectory = Join-Path $env:TEMP "stt-diarization-service-logs"
New-Item -ItemType Directory -Path $LogDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

foreach ($Required in @((Join-Path $VoskModelPath "am/final.mdl"), $NemoDll, $NemoModel, $SpeakerModel)) {
    if (-not (Test-Path -LiteralPath $Required)) { throw "Не найден обязательный файл модели или runtime: $Required" }
}
if (-not $DisableWhisper -and -not (Test-Path -LiteralPath $WhisperPython)) {
    throw "Не найден Python-окружение Whisper: $WhisperPython. Установите faster-whisper или запустите с -DisableWhisper."
}

function Assert-PortFree([int]$Port) {
    $Listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
    try { $Listener.Start() } catch { throw "Порт $Port уже занят; остановите предыдущий локальный запуск." }
    finally { $Listener.Stop() }
}

function Start-HiddenProcess([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory) {
    $Quoted = $Arguments | ForEach-Object { '"' + ([string]$_).Replace('"', '\"') + '"' }
    $SafeName = $Name.ToLowerInvariant().Replace(' ', '-')
    $Process = Start-Process -FilePath $Executable -ArgumentList ($Quoted -join ' ') -WorkingDirectory $WorkingDirectory `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $LogDirectory "$SafeName.out.log") `
        -RedirectStandardError (Join-Path $LogDirectory "$SafeName.err.log")
    Write-Host "$Name запущен (PID $($Process.Id)); логи: $LogDirectory"
    return $Process
}

function Wait-Http([string]$Name, [string]$Url, [System.Diagnostics.Process]$Process, [int]$TimeoutSeconds) {
    $Until = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $Until) {
        if ($Process.HasExited) { throw "$Name завершился с кодом $($Process.ExitCode). Проверьте логи в $LogDirectory." }
        try {
            $Response = Invoke-WebRequest -Uri $Url -TimeoutSec 2 -UseBasicParsing
            if ($Response.StatusCode -lt 500) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "$Name не ответил за $TimeoutSeconds секунд. Проверьте логи в $LogDirectory."
}

$Processes = [Collections.Generic.List[System.Diagnostics.Process]]::new()
$OldVoskPath, $OldWhisperUrl, $OldCache = $env:VOSK_MODEL_PATH, $env:WHISPER_URL, $env:HF_HOME
$OldInternalToken = $env:INTERVIEW_EVENTS_TOKEN
try {
    Assert-PortFree 8080
    Assert-PortFree 9000
    Assert-PortFree 8765
    if (-not $SkipBuild) {
        Push-Location $Repository
        try {
            & mvn -B test package
            if ($LASTEXITCODE -ne 0) { throw "Сборка analytics service завершилась с кодом $LASTEXITCODE." }
            & mvn -B -f stt-service/pom.xml test package
            if ($LASTEXITCODE -ne 0) { throw "Сборка STT service завершилась с кодом $LASTEXITCODE." }
        } finally { Pop-Location }
    }
    foreach ($Jar in @($AnalyticsJar, $SttJar)) {
        if (-not (Test-Path -LiteralPath $Jar)) { throw "Не найден JAR: $Jar. Уберите -SkipBuild или соберите оба Maven-модуля." }
    }

    $env:VOSK_MODEL_PATH = $VoskModelPath
    $TokenBytes = [byte[]]::new(32)
    $Random = [Security.Cryptography.RandomNumberGenerator]::Create()
    $Random.GetBytes($TokenBytes)
    $env:INTERVIEW_EVENTS_TOKEN = [BitConverter]::ToString($TokenBytes).Replace("-", "").ToLowerInvariant()
    $Random.Dispose()
    $env:HF_HOME = Join-Path $PublicData "whisper-cache"
    $Analytics = Start-HiddenProcess "Analytics" $JavaExecutable `
        @("-Xmx1g", "-Dfile.encoding=UTF-8", "-jar", $AnalyticsJar, "--server.address=127.0.0.1", `
          "--interview.reports-dir=$OutputDirectory/reports") $Repository
    $Processes.Add($Analytics)
    Wait-Http "Analytics service" "http://127.0.0.1:8080/healthz" $Analytics 90

    if (-not $DisableWhisper) {
        $env:HF_HUB_DISABLE_XET = "1"
        $env:PYTHONIOENCODING = "utf-8"
        $Whisper = Start-HiddenProcess "Whisper helper" $WhisperPython `
            @($WhisperScript, "--model", $WhisperModel, "--device", "cpu", "--compute-type", "int8", `
              "--host", "127.0.0.1", "--backend", $WhisperBackend) $Repository
        $Processes.Add($Whisper)
        Wait-Http "Whisper helper" "http://127.0.0.1:9000/" $Whisper 600
        $env:WHISPER_URL = "http://127.0.0.1:9000/transcribe"
    } else {
        $env:WHISPER_URL = ""
    }

    $Stt = Start-HiddenProcess "STT and diarization" $JavaExecutable `
        @("-Xmx3g", "-Dfile.encoding=UTF-8", "-jar", $SttJar, "integrated", "--nemo-lib", $NemoDll, `
          "--nemo-model", $NemoModel, "--speaker-model", $SpeakerModel, "--output-dir", $OutputDirectory, `
          "--analytics-url", "ws://127.0.0.1:8080") $Repository
    $Processes.Add($Stt)
    Wait-Http "STT service" "http://127.0.0.1:8765/" $Stt 90
    Write-Host "Откройте страницу STT: http://127.0.0.1:8765"
    Write-Host "Сервисы работают. Для остановки нажмите Ctrl+C."
    while ($true) {
        foreach ($Process in $Processes) {
            if ($Process.HasExited) { throw "Один из процессов завершился с кодом $($Process.ExitCode). Проверьте $LogDirectory." }
        }
        Start-Sleep -Seconds 2
    }
} finally {
    foreach ($Process in $Processes) {
        if (-not $Process.HasExited) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
    }
    $env:VOSK_MODEL_PATH = $OldVoskPath
    $env:WHISPER_URL = $OldWhisperUrl
    $env:HF_HOME = $OldCache
    $env:INTERVIEW_EVENTS_TOKEN = $OldInternalToken
}
