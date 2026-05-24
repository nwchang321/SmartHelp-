"""
Small local HTTP token server for SmartHelp+ Android clients.

Run this on the development machine. The Android app calls /token when it
starts, receives a short-lived LiveKit access token, then connects to LiveKit.
LiveKit API secrets stay in server-python/.env and are never packaged in the
Android app.
"""

from __future__ import annotations

import json
import os
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from dotenv import load_dotenv

from generate_token import generate_token
from voice_synthesizer import GeminiVoiceSynthesizer

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

DEFAULT_HOST = os.environ.get("TOKEN_SERVER_HOST", "0.0.0.0")
DEFAULT_PORT = int(os.environ.get("TOKEN_SERVER_PORT", "8765"))
LIVEKIT_URL = os.environ.get("LIVEKIT_URL", "")
TOKEN_TTL_SECONDS = 60 * 60
MAX_TTS_TEXT_LENGTH = int(os.environ.get("SMARTHELP_MAX_TTS_TEXT_LENGTH", "600"))
VOICE_SYNTHESIZER = GeminiVoiceSynthesizer()


class TokenRequestHandler(BaseHTTPRequestHandler):
    server_version = "SmartHelpTokenServer/1.0"

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == "/health":
            self._send_json({"ok": True})
            return

        if parsed.path != "/token":
            self._send_json({"error": "not_found"}, HTTPStatus.NOT_FOUND)
            return

        if not LIVEKIT_URL:
            self._send_json(
                {"error": "LIVEKIT_URL must be set in server-python/.env"},
                HTTPStatus.INTERNAL_SERVER_ERROR,
            )
            return

        query = parse_qs(parsed.query)
        identity = self._first(query, "identity") or f"android-{uuid.uuid4().hex[:8]}"
        room = self._first(query, "room") or f"smarthelp-{uuid.uuid4().hex[:8]}"

        try:
            token = generate_token(identity=identity, room=room)
        except SystemExit:
            self._send_json(
                {"error": "LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be set in server-python/.env"},
                HTTPStatus.INTERNAL_SERVER_ERROR,
            )
            return
        except Exception as exc:
            self._send_json({"error": str(exc)}, HTTPStatus.INTERNAL_SERVER_ERROR)
            return

        self._send_json(
            {
                "url": LIVEKIT_URL,
                "token": token,
                "room": room,
                "identity": identity,
                "expiresIn": TOKEN_TTL_SECONDS,
            }
        )

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path != "/tts":
            self._send_json({"error": "not_found"}, HTTPStatus.NOT_FOUND)
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            content_length = 0
        if content_length <= 0:
            self._send_json({"error": "missing request body"}, HTTPStatus.BAD_REQUEST)
            return

        try:
            body = self.rfile.read(content_length).decode("utf-8")
            payload = json.loads(body)
        except Exception:
            self._send_json({"error": "invalid JSON body"}, HTTPStatus.BAD_REQUEST)
            return

        text = str(payload.get("text") or "").strip()
        if not text:
            self._send_json({"error": "text is required"}, HTTPStatus.BAD_REQUEST)
            return
        if len(text) > MAX_TTS_TEXT_LENGTH:
            text = text[:MAX_TTS_TEXT_LENGTH]

        language = payload.get("language")
        if language is not None and not isinstance(language, str):
            language = None

        audio_b64 = VOICE_SYNTHESIZER.synthesize_wav_base64_sync(text, language)
        if not audio_b64:
            self._send_json({"error": "tts_unavailable"}, HTTPStatus.SERVICE_UNAVAILABLE)
            return

        self._send_json({
            "mimeType": "audio/wav",
            "text": text,
            "data": audio_b64,
        })

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self._send_cors_headers()
        self.end_headers()

    def log_message(self, format: str, *args) -> None:
        print(f"{self.address_string()} - {format % args}")

    def _send_json(self, payload: dict, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self._send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    @staticmethod
    def _first(query: dict[str, list[str]], key: str) -> str | None:
        values = query.get(key)
        if not values:
            return None
        value = values[0].strip()
        return value or None


def main() -> None:
    server = ThreadingHTTPServer((DEFAULT_HOST, DEFAULT_PORT), TokenRequestHandler)
    print(f"SmartHelp+ token server listening on http://{DEFAULT_HOST}:{DEFAULT_PORT}")
    print("Android endpoint: http://127.0.0.1:8765/token with adb reverse, or http://10.0.2.2:8765/token on emulator")
    server.serve_forever()


if __name__ == "__main__":
    main()
