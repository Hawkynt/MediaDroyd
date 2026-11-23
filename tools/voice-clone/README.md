# Voice-clone helper

The original actors' voices for the desktop app's voice-over (#533): each person's lines spoken in an imitation of
their own voice, made from the film on this computer's graphics card. `server.py` is MediaDroyd's own (LGPL-3.0-or-later)
and listens on this computer only; the app talks to nothing else, so neither the film's voices nor the lines leave the
machine. Imitating real people's voices is the household's own choice, for its own copies: the app keeps it off until
it is switched on in Settings, and marks the voice-over as imitated whenever it plays.

## How it runs

The desktop app sets it up and starts it by itself (`VoiceCloneRuntime`, `VoiceCloneSetup` in `:core:player`): on a
computer with an NVIDIA graphics card, the first time an engine is chosen in Settings it fetches a portable Python
(python-build-standalone 3.10, digest-checked), makes the engine an environment of its own, installs its pinned packages
and fetches its weights — following each engine's own installation instructions — then runs this file, carried in the
app, on a free port of this computer (for GPT-SoVITS its `api_v2.py` beside it) and stops it when the app quits.
Everything lives in the app-data folder under `voice-clone/`; `setup.log` and `helper.log` there say what happened.

| Engine | Licence | What the app sets up | Languages spoken |
| --- | --- | --- | --- |
| OpenVoice V2 | MIT | PyTorch, OpenVoice and MeloTTS at pinned commits, unidic's dictionary, the V2 converter and base speakers from its Hugging Face repository | English, Spanish, French, Chinese, Japanese, Korean |
| GPT-SoVITS | MIT | its source at a pinned commit, PyTorch, its `extra-req.txt` and `requirements.txt`, its pretrained models, G2PW, NLTK's data and OpenJTalk's dictionary (as its `install.sh`) | Chinese, English, Japanese, Korean, Cantonese |
| XTTS-v2 | Coqui Public Model License: **non-commercial use only** | PyTorch and coqui-tts 0.28.0; the model on first use, the licence accepted by choosing XTTS-v2 in Settings | 17, German among them |

A line in a language its engine does not speak is spoken by the app's usual voice. While the voice-over runs, the app
cuts each person's longest clear lines — six to fifteen seconds of them — into `clones/`, hands the helper their paths,
and deletes them when it stops.

For development it can be run by hand: `python3 tools/voice-clone/server.py --port 9890`.

The protocol: `GET /health` → `{"cuda": …, "engines": {"openvoice": …, "gpt-sovits": …, "xtts": …}}`; `POST /speak` with
`{"engine", "text", "language", "references", "reference_language", "speed"}` → a 16-bit PCM WAV, 422 for what the
engine cannot speak. Its tests, with fake engines: `python3 -I -m unittest discover -s tools/voice-clone`.
