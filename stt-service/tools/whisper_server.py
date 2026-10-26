#!/usr/bin/env python3
"""Внутренний loopback-помощник Whisper для уточнения текста внутри STT-сервиса.

Зачем: Vosk (русская модель) принципиально плохо знает английские слова. Whisper понимает смесь русского и английского
и, главное, принимает «подсказку» (initial_prompt) — мы кладём в неё термины из glossary.txt (HashMap, Spring Boot, JVM...),
и он пишет их латиницей. Работает ПОСЛЕ принятия решения, поэтому на скорость реакции не влияет.

Запуск:
    pip install faster-whisper
    python tools/whisper_server.py --model small            # CPU, int8
    python tools/whisper_server.py --model medium --device cuda --compute-type float16   # с видеокартой NVIDIA
Внешние клиенты используют STT-сервис; этот HTTP endpoint предназначен только для локального процесса STT.

Протокол: POST /transcribe, тело — сырой PCM (16 кГц, 16 бит, little-endian, моно); ответ — текст UTF-8.
При первом запуске модель скачивается (small ≈ 460 МБ, medium ≈ 1.5 ГБ).
"""
import argparse
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def build_prompt(glossary_path, limit=600):
    """Подсказка Whisper: контекст + канонические имена терминов (с заглавными латинскими буквами)."""
    names = []
    for line in Path(glossary_path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(";", 2)
        if len(parts) < 3 or parts[0].strip().lower() != "term":
            continue
        name = parts[1].strip()
        if re.search(r"[A-Z]", name) and name not in names:
            names.append(name)
    prompt = "Собеседование по Java Backend. Термины: "
    out, size = [], len(prompt)
    for n in names:
        if size + len(n) + 2 > limit:
            break
        out.append(n)
        size += len(n) + 2
    return prompt + ", ".join(out) + "."


class FakeBackend:
    """Для проверки связки с Java без модели."""

    def transcribe(self, pcm):
        return "[fake] %d мс аудио" % (len(pcm) // 32)


class FasterWhisperBackend:
    def __init__(self, model, device, compute_type, prompt, beam_size):
        from faster_whisper import WhisperModel   # импорт здесь, чтобы --backend fake работал без библиотеки

        print("Загрузка модели Whisper '%s' (%s, %s)..." % (model, device, compute_type), flush=True)
        self.model = WhisperModel(model, device=device, compute_type=compute_type)
        self.prompt = prompt
        self.beam_size = beam_size
        self.lock = threading.Lock()    # один распознавательный проход за раз

    def transcribe(self, pcm, vad=True):
        import numpy as np

        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        with self.lock:
            segments, _info = self.model.transcribe(
                audio,
                language="ru",
                beam_size=self.beam_size,
                initial_prompt=self.prompt,
                condition_on_previous_text=False,
                vad_filter=vad,
                vad_parameters=dict(min_silence_duration_ms=700),
                temperature=0.0,
            )
            return " ".join(s.text.strip() for s in segments).strip()

    def warmup(self):
        t = time.time()
        self.transcribe(b"\x00\x00" * 16000, vad=False)
        print("Прогрев: %.1f с" % (time.time() - t), flush=True)


def make_handler(backend):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            pass

        def _send(self, code, text):
            body = text.encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self._send(200, "ok")

        def do_POST(self):
            if self.path.split("?")[0] != "/transcribe":
                self._send(404, "not found")
                return
            n = int(self.headers.get("Content-Length", "0"))
            pcm = self.rfile.read(n)
            if len(pcm) % 2:
                pcm = pcm[:-1]
            t = time.time()
            try:
                text = backend.transcribe(pcm)
            except Exception as e:   # noqa: BLE001
                print("Ошибка распознавания:", repr(e), flush=True)
                self._send(500, str(e))
                return
            print("%.1f с аудио → %.2f с: %s" % (len(pcm) / 32000.0, time.time() - t, text[:120]), flush=True)
            self._send(200, text)

    return Handler


def main():
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default="small", help="tiny | base | small | medium | large-v3 (или путь к модели)")
    ap.add_argument("--device", default="auto", help="cpu | cuda | auto")
    ap.add_argument("--compute-type", default="int8", help="int8 (CPU) | float16 (GPU) | auto")
    ap.add_argument("--beam-size", type=int, default=5)
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--glossary", default=str(here.parents[1] / "src/main/resources/interview/glossary.txt"))
    ap.add_argument("--backend", default="whisper", choices=["whisper", "fake"])
    a = ap.parse_args()

    if a.backend == "fake":
        backend = FakeBackend()
    else:
        prompt = build_prompt(a.glossary)
        print("Подсказка Whisper:", prompt, flush=True)
        backend = FasterWhisperBackend(a.model, a.device, a.compute_type, prompt, a.beam_size)
        backend.warmup()

    srv = ThreadingHTTPServer((a.host, a.port), make_handler(backend))
    print("Whisper-сервер: http://%s:%d/transcribe" % (a.host, a.port), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
