# Voice-clone helper

The original actors' voices for the desktop app's voice-over (#533): each person's lines spoken in an imitation of
their own voice, made from the film on this computer's graphics card. `server.py` is MediaDroyd's own (LGPL-3.0-or-later)
and listens on this computer only; the app talks to nothing else, so neither the film's voices nor the lines leave the
machine. Imitating real people's voices is the household's own choice, for its own copies: the app keeps it off until
it is switched on in Settings, and marks the voice-over as imitated whenever it plays.

## What it needs

A computer with an NVIDIA graphics card (CUDA) and Python 3.10 or later, with PyTorch for CUDA. Without a graphics card
the helper says so and the app keeps its usual voices.

## The engines

Install the ones you want; the helper offers those it finds. Each comes under its own licence, which you accept when
you install it — MediaDroyd carries none of them.

| Engine | Licence | Install | Languages spoken |
| --- | --- | --- | --- |
| OpenVoice V2 | MIT | `pip install git+https://github.com/myshell-ai/OpenVoice git+https://github.com/myshell-ai/MeloTTS`, then `python -m unidic download`; its `checkpoints_v2` (from the OpenVoice README) beside the helper or named with `--checkpoints` | English, Spanish, French, Chinese, Japanese, Korean |
| GPT-SoVITS | MIT | its repository and models as its README says; run its own server: `python api_v2.py -a 127.0.0.1 -p 9880` (the helper asks it, `--gpt-sovits` names another address) | Chinese, English, Japanese, Korean, Cantonese |
| XTTS-v2 | Coqui Public Model License: **non-commercial use only** | `pip install coqui-tts`; the first run asks you to accept the licence and fetches the model | 17, German among them |

A line in a language its engine does not speak is spoken by the app's usual voice.

## Running it

```sh
python3 tools/voice-clone/server.py            # http://127.0.0.1:9890, what Settings expects
```

Then, in the desktop app's Settings: Original voices in the voice-over, the engine. The app cuts each person's longest
clear lines — six to fifteen seconds of them — into its app-data folder (`clones/`) while the voice-over runs, hands the
helper their paths, and deletes them when it stops.

The protocol: `GET /health` → `{"cuda": …, "engines": {"openvoice": …, "gpt-sovits": …, "xtts": …}}`; `POST /speak` with
`{"engine", "text", "language", "references", "reference_language", "speed"}` → a 16-bit PCM WAV, 422 for what the
engine cannot speak. Its tests, with fake engines: `python3 -I -m unittest discover -s tools/voice-clone`.
