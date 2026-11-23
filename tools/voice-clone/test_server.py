"""The voice-clone helper's requests and answers, with fake engines: python3 -I -m unittest discover -s tools/voice-clone"""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import server  # noqa: E402


class Fake:
    def __init__(self):
        self.asked = []

    @staticmethod
    def available():
        return True

    def speak(self, text, language, references, reference_language, speed):
        self.asked.append((text, language, references, reference_language, speed))
        if language == "xx":
            raise server.Unsupported("no xx")
        return [0.25] * 2400, 24000


class HelperTest(unittest.TestCase):
    def setUp(self):
        self.fake = Fake()
        self.helper = server.Helper({"xtts": self.fake}, cuda=True)
        self.httpd = server.serve(self.helper, 0)
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        self.reference = tempfile.NamedTemporaryFile(suffix=".wav", delete=False)
        self.reference.write(server.wav_bytes([0.1] * 1600, 16000))
        self.reference.close()

    def tearDown(self):
        self.httpd.shutdown()
        os.unlink(self.reference.name)

    def post(self, body):
        request = urllib.request.Request(self.base + "/speak", data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json"})
        return urllib.request.urlopen(request, timeout=5)

    def test_health_names_the_engines_and_the_graphics_card(self):
        with urllib.request.urlopen(self.base + "/health", timeout=5) as r:
            self.assertEqual({"cuda": True, "engines": {"xtts": True}}, json.load(r))

    def test_a_line_is_spoken_as_a_wav_in_the_persons_voice(self):
        with self.post({"engine": "xtts", "text": "Hallo", "language": "de", "references": [self.reference.name],
                        "reference_language": "en", "speed": 1.25}) as r:
            samples, rate = server.read_wav(r.read())
        self.assertEqual(24000, rate)
        self.assertEqual(2400, len(samples))
        self.assertEqual(("Hallo", "de", [self.reference.name], "en", 1.25), self.fake.asked[0])

    def test_what_an_engine_cannot_speak_is_said_so_the_app_speaks_it_itself(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.post({"engine": "xtts", "text": "x", "language": "xx", "references": [self.reference.name]})
        self.assertEqual(422, e.exception.code)

    def test_a_missing_reference_or_engine_is_refused(self):
        for body in ({"engine": "xtts", "text": "x", "references": ["/nowhere.wav"]},
                     {"engine": "nope", "text": "x", "references": [self.reference.name]}):
            with self.assertRaises(urllib.error.HTTPError) as e:
                self.post(body)
            self.assertEqual(400, e.exception.code)

    def test_without_a_graphics_card_nothing_is_imitated(self):
        self.helper.cuda = False
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.post({"engine": "xtts", "text": "x", "language": "de", "references": [self.reference.name]})
        self.assertEqual(422, e.exception.code)

    def test_it_listens_on_this_computer_only(self):
        with self.assertRaises(SystemExit):
            server.serve(self.helper, 0, host="0.0.0.0")


if __name__ == "__main__":
    unittest.main()
