"""
MediaDroyd's voice-clone helper (#533): the original actors' voices for the desktop app's voice-over, imitated on this
computer's graphics card. It listens on this computer only (127.0.0.1) and the app talks to nothing else, so neither the
film's voices nor the lines ever leave the machine.

  GET  /health  -> {"cuda": bool, "engines": {"openvoice": bool, "gpt-sovits": bool, "xtts": bool}}
  POST /speak   {"engine", "text", "language", "references": [wav paths], "reference_language", "speed"}
                -> audio/wav, 16-bit PCM mono

Engines, each installed by the household under its own licence (see README.md beside this file):
  openvoice   OpenVoice V2 (MIT): MeloTTS speaks the line, OpenVoice's tone colour converter gives it the voice.
  gpt-sovits  GPT-SoVITS (MIT): its own api_v2.py server, running beside this one, asked for each line.
  xtts        XTTS-v2 through coqui-tts (Coqui Public Model License: non-commercial use only).

python3 server.py [--port 9890] [--gpt-sovits http://127.0.0.1:9880] [--checkpoints DIR]
"""
import argparse
import io
import ipaddress
import json
import os
import struct
import sys
import tempfile
import urllib.request
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ISO 639-1, as the app names languages, to each engine's own names.
XTTS_LANGUAGES = {"en": "en", "es": "es", "fr": "fr", "de": "de", "it": "it", "pt": "pt", "pl": "pl", "tr": "tr",
                  "ru": "ru", "nl": "nl", "cs": "cs", "ar": "ar", "zh": "zh-cn", "ja": "ja", "hu": "hu", "ko": "ko",
                  "hi": "hi"}
MELO_LANGUAGES = {"en": "EN_NEWEST", "es": "ES", "fr": "FR", "zh": "ZH", "ja": "JP", "ko": "KR"}
SOVITS_LANGUAGES = {"zh": "zh", "en": "en", "ja": "ja", "ko": "ko", "yue": "yue"}


class Unsupported(Exception):
    """The engine cannot speak this: the app speaks the line with its own voice instead."""


def wav_bytes(samples, rate):
    """Mono floats (-1 .. 1) as a 16-bit PCM WAV."""
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, float(s))) * 32767)) for s in samples))
    return out.getvalue()


def cuda_available():
    try:
        import torch
        return bool(torch.cuda.is_available())
    except ImportError:
        return False


class Xtts:
    """XTTS-v2 through coqui-tts; its licence is accepted by the household when coqui-tts first fetches the model."""

    def __init__(self):
        self.tts = None

    @staticmethod
    def available():
        try:
            import TTS.api  # noqa: F401
            return True
        except ImportError:
            return False

    def speak(self, text, language, references, reference_language, speed):
        code = XTTS_LANGUAGES.get(language)
        if code is None:
            raise Unsupported("XTTS-v2 does not speak %s" % language)
        if self.tts is None:
            from TTS.api import TTS
            self.tts = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to("cuda")
        samples = self.tts.tts(text=text, speaker_wav=references, language=code, speed=speed)
        return samples, self.tts.synthesizer.output_sample_rate


class OpenVoice:
    """OpenVoice V2: MeloTTS's base speaker, then the tone colour converter towards the references."""

    def __init__(self, checkpoints):
        self.checkpoints = checkpoints
        self.converter = None
        self.melo = {}
        self.voices = {}

    @staticmethod
    def available():
        try:
            import openvoice.api  # noqa: F401
            import melo.api  # noqa: F401
            return True
        except ImportError:
            return False

    def speak(self, text, language, references, reference_language, speed):
        code = MELO_LANGUAGES.get(language)
        if code is None:
            raise Unsupported("MeloTTS has no %s voice for OpenVoice" % language)
        import torch
        from melo.api import TTS
        from openvoice.api import ToneColorConverter
        if self.converter is None:
            folder = os.path.join(self.checkpoints, "converter")
            self.converter = ToneColorConverter(os.path.join(folder, "config.json"), device="cuda")
            self.converter.load_ckpt(os.path.join(folder, "checkpoint.pth"))
        key = tuple(references)
        if key not in self.voices:
            self.voices[key] = self.converter.extract_se(list(references))
        if code not in self.melo:
            self.melo[code] = TTS(language=code, device="cuda")
        melo = self.melo[code]
        speaker, speaker_id = next(iter(melo.hps.data.spk2id.items()))
        source = torch.load(os.path.join(self.checkpoints, "base_speakers", "ses",
                                         speaker.lower().replace("_", "-") + ".pth"), map_location="cuda")
        with tempfile.TemporaryDirectory() as work:
            spoken = os.path.join(work, "base.wav")
            converted = os.path.join(work, "voice.wav")
            melo.tts_to_file(text, speaker_id, spoken, speed=speed)
            self.converter.convert(audio_src_path=spoken, src_se=source, tgt_se=self.voices[key],
                                   output_path=converted, message="@MyShell")
            with open(converted, "rb") as f:
                return read_wav(f.read())


class GptSovits:
    """GPT-SoVITS: its own api_v2.py server (python api_v2.py -a 127.0.0.1 -p 9880), asked line by line."""

    def __init__(self, address):
        self.address = address.rstrip("/")

    def available(self):
        try:
            urllib.request.urlopen(self.address + "/docs", timeout=1).close()
            return True
        except Exception:  # noqa: BLE001 - any failure means it is not there
            return False

    def speak(self, text, language, references, reference_language, speed):
        if language not in SOVITS_LANGUAGES or reference_language not in SOVITS_LANGUAGES:
            raise Unsupported("GPT-SoVITS does not speak %s from %s" % (language, reference_language))
        body = json.dumps({
            "text": text, "text_lang": SOVITS_LANGUAGES[language],
            "ref_audio_path": references[0], "aux_ref_audio_paths": references[1:],
            "prompt_text": "", "prompt_lang": SOVITS_LANGUAGES[reference_language],
            "media_type": "wav", "streaming_mode": False, "speed_factor": speed,
        }).encode()
        request = urllib.request.Request(self.address + "/tts", data=body,
                                         headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=300) as response:
            return read_wav(response.read())


def read_wav(data):
    """A 16-bit PCM WAV's mono samples and rate."""
    with wave.open(io.BytesIO(data), "rb") as w:
        channels, rate, frames = w.getnchannels(), w.getframerate(), w.readframes(w.getnframes())
    values = struct.unpack("<%dh" % (len(frames) // 2), frames)
    samples = [sum(values[i:i + channels]) / channels / 32768.0 for i in range(0, len(values), channels)]
    return samples, rate


class Helper:
    """The engines, and what the requests ask of them."""

    def __init__(self, engines, cuda):
        self.engines = engines
        self.cuda = cuda

    def health(self):
        return {"cuda": self.cuda, "engines": {name: bool(e.available()) for name, e in self.engines.items()}}

    def speak(self, request):
        """The WAV for a /speak request; raises Unsupported or ValueError for one that cannot be answered."""
        if not self.cuda:
            raise Unsupported("the original voices need a graphics card (CUDA)")
        engine = self.engines.get(request.get("engine"))
        if engine is None:
            raise ValueError("no such engine: %r" % request.get("engine"))
        text = str(request.get("text", "")).strip()
        references = [str(r) for r in request.get("references", [])]
        if not text or not references:
            raise ValueError("a line needs its text and the person's references")
        for path in references:
            if not os.path.isfile(path):
                raise ValueError("no reference at %s" % path)
        speed = min(2.0, max(0.5, float(request.get("speed", 1.0))))
        samples, rate = engine.speak(text, str(request.get("language", "")), references,
                                     str(request.get("reference_language", "")), speed)
        return wav_bytes(samples, rate)


def handler_for(helper):
    class Handler(BaseHTTPRequestHandler):
        def _send(self, code, body, kind):
            self.send_response(code)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):  # noqa: N802 - the http.server convention
            if self.path == "/health":
                self._send(200, json.dumps(helper.health()).encode(), "application/json")
            else:
                self._send(404, b"", "text/plain")

        def do_POST(self):  # noqa: N802
            if self.path != "/speak":
                self._send(404, b"", "text/plain")
                return
            try:
                request = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
                self._send(200, helper.speak(request), "audio/wav")
            except Unsupported as e:
                self._send(422, str(e).encode(), "text/plain")
            except (ValueError, KeyError) as e:
                self._send(400, str(e).encode(), "text/plain")

        def log_message(self, *args):  # quiet: the lines spoken are the household's business
            pass

    return Handler


def serve(helper, port, host="127.0.0.1"):
    """The helper on [host]:[port] — this computer only; any other address is refused."""
    if host != "localhost" and not ipaddress.ip_address(host).is_loopback:
        raise SystemExit("the helper listens on this computer only, not on %s" % host)
    return ThreadingHTTPServer((host, port), handler_for(helper))


def main(argv):
    parser = argparse.ArgumentParser(description="MediaDroyd's voice-clone helper, on this computer only")
    parser.add_argument("--port", type=int, default=9890)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--gpt-sovits", default="http://127.0.0.1:9880")
    parser.add_argument("--checkpoints", default="checkpoints_v2")
    args = parser.parse_args(argv)
    helper = Helper({"openvoice": OpenVoice(args.checkpoints), "gpt-sovits": GptSovits(args.gpt_sovits),
                     "xtts": Xtts()}, cuda_available())
    server = serve(helper, args.port, args.host)
    print("voice-clone helper on http://%s:%d, graphics card: %s" % (args.host, args.port, helper.cuda))
    server.serve_forever()


if __name__ == "__main__":
    main(sys.argv[1:])
