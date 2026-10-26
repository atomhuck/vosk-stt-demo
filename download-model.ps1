param([string]$Destination = "$env:PUBLIC\NemotronTest\vosk-model-ru-0.42")

# Хранит модель вне каталога репозитория, чтобы избежать ограничений синхронизируемых папок.
$ErrorActionPreference = "Stop"
if (Test-Path (Join-Path $Destination "am/final.mdl")) { Write-Host "Модель уже установлена: $Destination"; exit 0 }
$parent = Split-Path -Parent $Destination
New-Item -ItemType Directory -Force -Path $parent | Out-Null
$zip = Join-Path $parent "vosk-model-ru-0.42.zip"
$archiveDirectory = Join-Path $parent "vosk-model-ru-0.42"
Invoke-WebRequest -Uri "https://alphacephei.com/vosk/models/vosk-model-ru-0.42.zip" -OutFile $zip
Expand-Archive -Path $zip -DestinationPath $parent -Force
Remove-Item $zip
if ($archiveDirectory -ne $Destination) { Move-Item -LiteralPath $archiveDirectory -Destination $Destination -Force }
Write-Host "Готово: $Destination"
