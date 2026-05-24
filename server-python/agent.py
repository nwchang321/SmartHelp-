"""
SmartHelp+ LiveKit Agent
========================
Main entrypoint — connects to LiveKit Cloud, receives audio + video from Android,
pipes through Gemini for guidance, and sends results back via Data Channel.

This replaces the entire Node.js WebSocket server (index.js + SessionController.js + GeminiLiveAgent.js).

Usage:
    python agent.py dev          # Run in development mode
    python agent.py connect      # Connect to LiveKit Cloud
"""

from __future__ import annotations

# Fix aiodns/pycares DNS failures on Windows by forcing aiohttp to use
# Python's system resolver. aiohttp.connector imports its own DefaultResolver
# reference, so patch both modules before importing LiveKit.
import aiohttp.connector
import aiohttp.resolver

aiohttp.resolver.DefaultResolver = aiohttp.resolver.ThreadedResolver
aiohttp.connector.DefaultResolver = aiohttp.resolver.ThreadedResolver

import asyncio
import base64
import binascii
import io
import json
import logging
import os
import re
import time
import uuid
import wave

from dotenv import load_dotenv
from livekit import agents, rtc
from livekit.agents import AgentSession, JobContext, Agent

from google import genai
from google.genai import types as genai_types

from task_state_machine import TaskStateMachine
from task_executor import TaskExecutor
from prompt_registry import PromptRegistry
from voice_synthesizer import GeminiVoiceSynthesizer, TTS_SAMPLE_RATE, TTS_NUM_CHANNELS
from vision_grounding_tool import VisionGroundingTool

load_dotenv()

logger = logging.getLogger("smarthelp.agent")
MAX_DATA_MESSAGE_BYTES = int(os.environ.get("SMARTHELP_MAX_DATA_MESSAGE_BYTES", str(14 * 1024)))

# ── Constants ──────────────────────────────────────────────────────────────

HELP_REQUEST_TOKEN = "__NEED_HELP_FIND_TARGET__"
CANCEL_KEYWORDS = ["算了", "不要了", "取消", "不弄了", "停", "cancel", "never mind", "forget it", "stop", "quit"]
HELP_KEYWORDS = ["帮忙", "找不到", "看不到", "在哪", "在哪里", "help", "can't find", "cannot find", "where is", "where"]


WHATSAPP_MISHEAR_RE = re.compile(r"\b(?:wash\s+air\s+max(?:\s+\d+)?|was\s+app|what\s+app|what'?s\s+app)\b", re.IGNORECASE)
PHONE_ACTION_RE = re.compile(r"\b(?:want\s+to|open|use|send|message|text|call|chat|phone|app)\b", re.IGNORECASE)


def _normalize_stt_transcript(text: str) -> str:
    normalized = (text or "").strip()
    if not normalized:
        return ""

    normalized = re.sub(
        r"\buse\s+seeing\s+the\s+whats\s*app\b",
        "use WhatsApp",
        normalized,
        flags=re.IGNORECASE,
    )
    normalized = re.sub(
        r"\buse\s+seeing\s+the\s+whatsapp\b",
        "use WhatsApp",
        normalized,
        flags=re.IGNORECASE,
    )
    normalized = re.sub(
        r"\bwant\s+to\s+wash\s+air\s+max(?:\s+\d+)?\b",
        "want to use WhatsApp",
        normalized,
        flags=re.IGNORECASE,
    )
    if WHATSAPP_MISHEAR_RE.search(normalized) and PHONE_ACTION_RE.search(normalized):
        normalized = WHATSAPP_MISHEAR_RE.sub("WhatsApp", normalized)
    return normalized.strip()


def _select_google_stt_transcript(alternatives: list[dict]) -> tuple[str, float | None]:
    if not alternatives:
        return "", None

    first = alternatives[0]
    selected = first
    selected_text = _normalize_stt_transcript(str(first.get("transcript") or ""))

    for alternative in alternatives:
        candidate_text = _normalize_stt_transcript(str(alternative.get("transcript") or ""))
        if "whatsapp" in candidate_text.lower():
            selected = alternative
            selected_text = candidate_text
            break

    confidence = selected.get("confidence")
    if isinstance(confidence, (int, float)):
        return selected_text, float(confidence)
    return selected_text, None


def _is_cancel_intent(text: str) -> bool:
    lower = (text or "").lower().strip()
    return any(kw in lower for kw in CANCEL_KEYWORDS)


def _is_help_intent(text: str) -> bool:
    lower = (text or "").lower().strip()
    return any(kw in lower for kw in HELP_KEYWORDS)


def _generate_session_id() -> str:
    return f"session-{int(time.time())}-{uuid.uuid4().hex[:8]}"


# ── Agent class ────────────────────────────────────────────────────────────

class SmartHelpAgent:
    """
    One instance per Room session.
    Handles all inbound messages from the Android client and orchestrates
    task guidance via the TaskStateMachine + TaskExecutor pipeline.
    """

    def __init__(self, ctx: JobContext):
        self.ctx = ctx
        self.room = ctx.room
        self.session_id = _generate_session_id()
        self.prompt_registry = PromptRegistry()
        # Elderly users often need 20–30s to find and tap an icon, especially
        # the first time. Default FSM timeout (10s) fires HELPING too eagerly
        # and the user's eventual tap gets swallowed as a HELP-mode screenshot.
        self.tsm = TaskStateMachine(
            self.session_id,
            await_timeout_ms=int(os.environ.get("SMARTHELP_AWAIT_TIMEOUT_MS", "15000")),
        )
        self.executor = TaskExecutor(self.tsm, config={"promptRegistry": self.prompt_registry})
        self.executor.on_output = self._on_executor_output
        self.voice_synthesizer = GeminiVoiceSynthesizer()

        # LiveKit audio track for streaming TTS playback (replaces base64-over-data-channel).
        self._tts_audio_source: rtc.AudioSource | None = None
        self._tts_track_publication = None
        self._tts_pcm_buffer = bytearray()
        # Serialize TTS playback so two responses don't mix into the same track.
        self._tts_lock = asyncio.Lock()

        # Gemini Vision client for screenshot analysis
        google_api_key = os.environ.get("GOOGLE_API_KEY")
        self._gemini_client = genai.Client(api_key=google_api_key)
        self._vision_model = os.environ.get("GEMINI_VISION_MODEL", "gemini-3-flash-preview")
        self._vision_verify_model = os.environ.get("GEMINI_VISION_VERIFY_MODEL", "gemini-3-flash-preview")
        self._vision_tool = VisionGroundingTool(
            self._gemini_client,
            primary_model=self._vision_model,
            verify_model=self._vision_verify_model,
            session_id=self.session_id,
        )

        # Wire up the screenshot → Gemini callback
        self.executor.on_send_image_to_gemini = self._send_image_to_gemini

        # Gemini Live session via LiveKit AgentSession (if using Gemini Realtime)
        self.agent_session: AgentSession | None = None

        # Timeout checker
        self._timeout_task: asyncio.Task | None = None
        self._closed = False

        # Chunked screenshot reassembly
        self._pending_chunks: dict[str, dict] = {}  # chunkId -> {chunks: [], total: int, meta: dict}

        # Audio buffering for STT when no active guidance
        self._audio_buffer: list[str] = []
        self._audio_buffer_task: asyncio.Task | None = None
        self._audio_buffer_timeout: float = 2.0  # seconds of silence before processing
        self._audio_stream_active = True  # Track if audio stream is active
        self._audio_turn_id = None
        self._audio_end_in_progress = False
        self._ready_sent = False  # Send ready only once per session

        logger.info(
            "Session %s created; Gemini configured=%s vision_model=%s verify_model=%s",
            self.session_id,
            bool(google_api_key),
            self._vision_model,
            self._vision_verify_model,
        )

    async def _send_ready_once(self) -> None:
        """Send ready to Android, but only once per session (avoid late ready muting active mic)."""
        if self._ready_sent:
            return
        self._ready_sent = True
        await self._send_to_android({"type": "ready"})

    async def start(self) -> None:
        """Bind Room event listeners and start the timeout checker."""
        # Listen for data channel messages from Android
        self.room.on("data_received", self._on_data_received)
        self.room.on("track_subscribed", self._on_track_subscribed)
        self.room.on("participant_disconnected", self._on_participant_disconnected)
        self.room.on("participant_connected", self._on_participant_connected)

        # Publish the TTS audio track so Android can play streamed speech with
        # zero base64/JSON/data-channel overhead.
        await self._ensure_tts_track()

        # Start timeout checker
        self._timeout_task = asyncio.ensure_future(self._timeout_loop())

        self.executor.set_gemini_ready(True)
        await self.executor.on_gemini_ready()

        # If Android is already in the room, send ready now
        for p in self.room.remote_participants.values():
            if not p.identity.startswith("agent-"):
                await self._send_ready_once()
                logger.info("Session %s started, Android already present, sent ready", self.session_id)
                return

        logger.info("Session %s started, waiting for Android client to join", self.session_id)

    # ── Inbound: Android → Agent ───────────────────────────────────────────

    def _on_data_received(self, data: rtc.DataPacket) -> None:
        """Handle JSON messages from the Android client via Data Channel."""
        try:
            payload = json.loads(data.data.decode("utf-8"))
            msg_type = payload.get("type")
            if not msg_type:
                return

            # image_chunk fires 20-40x per screenshot; drop to DEBUG so the log
            # stays readable. image_end (one per screenshot) stays at INFO.
            if msg_type == "image_chunk":
                logger.debug("Data channel message received: type=%s bytes=%d", msg_type, len(data.data))
            else:
                logger.info("Data channel message received: type=%s bytes=%d", msg_type, len(data.data))
            asyncio.ensure_future(self._route_message(payload))
        except Exception as e:
            logger.error("Error parsing data: %s", e)

    async def _route_message(self, msg: dict) -> None:
        msg_type = msg.get("type")

        if msg_type == "text" or msg_type == "user_input":
            text = msg.get("text", "")
            language = msg.get("language")
            await self._handle_user_input(text, language)

        elif msg_type == "image" or msg_type == "screenshot":
            image = msg.get("data") or msg.get("image")
            width = msg.get("width")
            height = msg.get("height")
            query = msg.get("query")
            verify = msg.get("verify", False)
            language = msg.get("language")
            await self._handle_screenshot(image, width, height, {
                "query": query,
                "verify": verify,
                "language": language,
                "accessibility": msg.get("accessibility") or [],
            })

        elif msg_type == "image_chunk":
            self._handle_image_chunk(msg)

        elif msg_type == "image_end":
            await self._handle_image_end(msg)

        elif msg_type == "audio" or msg_type == "audio_chunk":
            audio_data = msg.get("data") or msg.get("audio")
            if audio_data:
                # If there's an active guidance request, use executor's audio handler
                if self.executor.active_guide_request:
                    self.executor.on_audio_chunk({"data": audio_data, "mimeType": "audio/pcm"})
                elif self._audio_stream_active:
                    # Buffer audio for STT when stream is active
                    self._audio_buffer.append(audio_data)
                    # Reset the silence timer
                    if self._audio_buffer_task:
                        self._audio_buffer_task.cancel()
                    self._audio_buffer_task = asyncio.ensure_future(self._process_audio_buffer_after_silence())

        elif msg_type == "listening_start":
            # Android signals start of new listening session
            logger.info("Received listening_start, resetting buffers")
            self._audio_turn_id = msg.get("turnId")
            self._audio_end_in_progress = False
            self._audio_buffer.clear()
            if hasattr(self, '_audio_track_buffer'):
                self._audio_track_buffer.clear()
            self._audio_stream_active = True
            if self._audio_buffer_task:
                self._audio_buffer_task.cancel()
                self._audio_buffer_task = None

        elif msg_type == "audio_end":
            # Android signals end of audio stream
            turn_id = msg.get("turnId")
            if turn_id is not None and self._audio_turn_id is not None and turn_id != self._audio_turn_id:
                logger.info("Ignoring stale audio_end turnId=%s current=%s", turn_id, self._audio_turn_id)
                return
            has_buffered_data = (self._audio_buffer or
                                 (hasattr(self, '_audio_track_buffer') and self._audio_track_buffer))
            if not self._audio_stream_active and not has_buffered_data:
                logger.info("Ignoring duplicate audio_end with no active audio turn")
                return
            if self._audio_end_in_progress:
                logger.info("Ignoring duplicate audio_end while previous end is processing")
                return
            logger.info("Received audio_end message, stopping audio stream...")
            self._audio_end_in_progress = True
            try:
                # Wait before closing the turn so the final WebRTC frames can arrive.
                await asyncio.sleep(0.5)
                self._audio_stream_active = False

                has_data = (self._audio_buffer or
                           (hasattr(self, '_audio_track_buffer') and self._audio_track_buffer))
                logger.info("Audio buffers status - data_channel: %d, audio_track: %d", 
                           len(self._audio_buffer),
                           len(self._audio_track_buffer) if hasattr(self, '_audio_track_buffer') else 0)
                if has_data:
                    await self._process_buffered_audio()
                else:
                    logger.warning("audio_end received but no audio data buffered")
            finally:
                # Reset buffer for next session (stream will be reactivated when Android starts new session)
                if hasattr(self, '_audio_track_buffer'):
                    self._audio_track_buffer.clear()
                self._audio_buffer.clear()
                self._audio_end_in_progress = False

        elif msg_type == "help_request":
            await self._handle_help_request()

        elif msg_type == "cancel":
            await self._handle_cancel()

        elif msg_type == "user_action":
            await self._handle_user_action()

        elif msg_type == "sensitive_screen":
            await self._handle_sensitive_screen(msg)

        elif msg_type == "ping":
            await self._send_to_android({"type": "pong"})

    async def _handle_user_input(self, text: str, language: str | None = None) -> None:
        message = (text or "").strip()
        if not message:
            return

        state = self.tsm.get_state()
        goal_payload = {"goal": message, "language": language} if language else message

        if message == HELP_REQUEST_TOKEN:
            await self._handle_help_request()
            return

        if _is_cancel_intent(message):
            self.tsm.dispatch("user_cancelled")
            await self.executor.run()
            return

        if _is_help_intent(message) and state == "AWAITING_ACTION":
            self.tsm.dispatch("help_requested")
            await self.executor.run()
            return

        if state == "IDLE":
            self.tsm.dispatch("user_goal_received", goal_payload)
            await self.executor.run()
        elif state in ("COMPLETED", "FAILED"):
            self.tsm.dispatch("new_goal", goal_payload)
            await self.executor.run()
        elif state == "CLARIFYING":
            self.tsm.dispatch("user_provided_info", message)
            await self.executor.run()
        elif state == "STUCK":
            self.tsm.dispatch("user_retry")
            await self.executor.run()

    async def _handle_screenshot(self, image: str, width: int | None, height: int | None, meta: dict) -> None:
        state = self.tsm.get_state()

        if meta.get("query") and state in ("IDLE", "COMPLETED", "FAILED"):
            await self.executor.on_screenshot(image, width, height, meta)
            goal_payload = {"goal": meta["query"], "language": meta.get("language")} if meta.get("language") else meta["query"]
            self.tsm.dispatch("user_goal_received" if state == "IDLE" else "new_goal", goal_payload)
            await self.executor.run()
            return

        if meta.get("verify") and state == "AWAITING_ACTION":
            await self.executor.on_screenshot(image, width, height, meta)
            self.tsm.dispatch("user_action_detected")
            await self.executor.run()
            return

        await self.executor.on_screenshot(image, width, height, meta)
        if not self.executor.pending_mode and self.tsm.get_state() in ("GUIDING", "VERIFYING", "RETRYING", "HELPING"):
            await self.executor.run()

    async def _handle_user_action(self) -> None:
        if self.tsm.get_state() != "AWAITING_ACTION":
            return
        self.tsm.dispatch("user_action_detected")
        await self.executor.run()

    async def _handle_help_request(self) -> None:
        if self.tsm.get_state() != "AWAITING_ACTION":
            return
        self.tsm.dispatch("help_requested")
        await self.executor.run()

    async def _handle_cancel(self) -> None:
        self.tsm.dispatch("user_cancelled")
        await self.executor.run()

    async def _handle_sensitive_screen(self, msg: dict) -> None:
        if not msg.get("detected", True):
            return
        if self.tsm.get_state() != "IDLE":
            self.tsm.dispatch("user_cancelled")

        lang = self.tsm.get_context().get("outputLanguage")
        result = self.executor.pause_for_sensitive_screen(msg.get("app"), lang)
        if result:
            await self._send_to_android({"type": "text", "text": result.get("message", "")})

    # ── Chunked screenshot reassembly ─────────────────────────────────────

    def _handle_image_chunk(self, msg: dict) -> None:
        chunk_id = msg.get("id")
        seq = msg.get("seq", 0)
        total = msg.get("total", 0)
        data = msg.get("data", "")
        if not chunk_id:
            return
        if chunk_id not in self._pending_chunks:
            self._pending_chunks[chunk_id] = {"chunks": [None] * total, "total": total, "meta": {}, "end_received": False}
        entry = self._pending_chunks[chunk_id]
        if 0 <= seq < entry["total"]:
            entry["chunks"][seq] = data
        if entry.get("end_received") and self._image_chunks_complete(entry):
            asyncio.ensure_future(self._process_image_entry(chunk_id))

    async def _handle_image_end(self, msg: dict) -> None:
        chunk_id = msg.get("id")
        total = msg.get("total", 0)
        if not chunk_id:
            return
        if chunk_id not in self._pending_chunks:
            self._pending_chunks[chunk_id] = {"chunks": [None] * total, "total": total, "meta": {}, "end_received": False}
        entry = self._pending_chunks[chunk_id]
        entry["meta"] = msg
        entry["end_received"] = True
        if self._image_chunks_complete(entry):
            await self._process_image_entry(chunk_id)
        else:
            missing = entry["chunks"].count(None)
            logger.info("image_end received before all chunks for %s; waiting for %d chunk(s)", chunk_id, missing)
            asyncio.ensure_future(self._process_image_entry_after_delay(chunk_id))

    def _image_chunks_complete(self, entry: dict) -> bool:
        return entry.get("total", 0) > 0 and all(chunk is not None for chunk in entry["chunks"])

    async def _process_image_entry_after_delay(self, chunk_id: str) -> None:
        await asyncio.sleep(1.0)
        if chunk_id in self._pending_chunks:
            await self._process_image_entry(chunk_id)

    async def _process_image_entry(self, chunk_id: str) -> None:
        if chunk_id not in self._pending_chunks:
            return
        entry = self._pending_chunks.pop(chunk_id)
        meta = entry.get("meta") or {}
        missing = entry["chunks"].count(None)
        if missing:
            logger.warning("Processing image %s with %d missing chunk(s)", chunk_id, missing)
        parts = [c for c in entry["chunks"] if c is not None]
        image = "".join(parts)
        await self._handle_screenshot(image, meta.get("width"), meta.get("height"), {
            "query": meta.get("query"),
            "verify": meta.get("verify", False),
            "language": meta.get("language"),
            "accessibility": meta.get("accessibility") or [],
        })

    # ── Audio buffering for STT ────────────────────────────────────────────

    async def _process_audio_buffer_after_silence(self) -> None:
        """Wait for silence then process buffered audio."""
        try:
            await asyncio.sleep(self._audio_buffer_timeout)
            if self._audio_buffer:
                await self._process_buffered_audio()
        except asyncio.CancelledError:
            pass

    async def _process_buffered_audio(self) -> None:
        """Transcribe buffered audio using Gemini and process as user input."""
        # Check both data channel buffer and audio track buffer
        audio_data = []
        if hasattr(self, '_audio_track_buffer') and self._audio_track_buffer:
            audio_data = self._audio_track_buffer.copy()
            self._audio_track_buffer.clear()
            logger.info("Using audio track buffer: %d chunks", len(audio_data))
        elif self._audio_buffer:
            audio_data = self._audio_buffer.copy()
            self._audio_buffer.clear()
            logger.info("Using data channel buffer: %d chunks", len(audio_data))
        else:
            logger.warning("No audio data in either buffer!")
            logger.info("_audio_track_buffer exists: %s, length: %d", 
                       hasattr(self, '_audio_track_buffer'), 
                       len(self._audio_track_buffer) if hasattr(self, '_audio_track_buffer') else 0)
            logger.info("_audio_buffer length: %d", len(self._audio_buffer))
            return

        if self._audio_buffer_task:
            self._audio_buffer_task.cancel()
            self._audio_buffer_task = None

        logger.info("Processing %d audio chunks for STT", len(audio_data))

        try:
            combined_audio = self._combine_audio_chunks_to_b64(audio_data)
            if not combined_audio:
                logger.warning("Unable to combine audio chunks for STT")
                return

            # Transcribe the final turn and normalize common app-name mishears.
            raw_transcript = await self._transcribe_audio(combined_audio)
            transcript = _normalize_stt_transcript(raw_transcript)
            if transcript != (raw_transcript or "").strip():
                logger.info("STT normalized: %s -> %s", raw_transcript[:100], transcript[:100])

            if transcript and transcript.strip():
                logger.info("STT result: %s", transcript[:100])
                # Send transcription to Android for display
                await self._send_to_android({"type": "transcription", "text": transcript})

                # Sanity check: if the user spoke for a long time but STT only
                # returned a few words, the recogniser almost certainly clipped
                # the tail. Asking the user to confirm before executing avoids
                # the "AI opens WhatsApp then dead-ends because the rest of the
                # sentence was lost" failure mode.
                audio_seconds = len(audio_data) * 0.01
                word_count = len(transcript.split())
                state = self.tsm.get_state()
                if state == "IDLE" and audio_seconds >= 4.0 and word_count < 5:
                    logger.warning(
                        "STT likely incomplete (%.1fs audio -> %d words); asking user to confirm before executing",
                        audio_seconds, word_count,
                    )
                    # SmartHelp+ is English-only — drop multi-language branching.
                    confirm = f"I only heard “{transcript}”. Is that everything? If there's more, please say it again."
                    await self._send_to_android({"type": "text", "text": confirm, "speak": True, "display": True})
                    return

                # Process as user input
                await self._handle_user_input(transcript)
            else:
                logger.info("STT returned empty transcript")
                await self._send_to_android({"type": "transcription", "text": ""})
                # Send a helpful message to the user (English-only build).
                msg = "I didn't catch that. Please speak again."
                await self._send_to_android({"type": "text", "text": msg, "speak": True, "display": True})
        except Exception as e:
            logger.error("STT processing failed: %s", e)
            await self._send_to_android({"type": "transcription", "text": ""})

    def _combine_audio_chunks_to_b64(self, chunks: list[str]) -> str:
        """Decode each base64 PCM frame and re-encode one valid base64 payload."""
        raw_pcm = bytearray()
        for chunk in chunks:
            if not chunk:
                continue
            try:
                raw_pcm.extend(base64.b64decode(chunk, validate=False))
            except (binascii.Error, ValueError) as exc:
                logger.debug("Skipping invalid audio chunk: %s", exc)

        if not raw_pcm:
            return ""

        logger.info("Combined PCM audio: %d bytes from %d chunk(s)", len(raw_pcm), len(chunks))
        return base64.b64encode(raw_pcm).decode("ascii")

    @staticmethod
    def _pcm16_to_wav_bytes(raw_pcm: bytes, sample_rate: int = 16000, channels: int = 1) -> bytes:
        """Wrap raw LINEAR16 PCM as WAV for Gemini audio input."""
        out = io.BytesIO()
        with wave.open(out, "wb") as wav:
            wav.setnchannels(channels)
            wav.setsampwidth(2)
            wav.setframerate(sample_rate)
            wav.writeframes(raw_pcm)
        return out.getvalue()

    async def _transcribe_audio(self, audio_b64: str) -> str:
        """Transcribe audio - try Google Cloud STT first, then Gemini, then return empty."""
        import aiohttp

        api_key = os.environ.get("GOOGLE_CLOUD_STT_KEY")
        
        # Try Google Cloud STT
        if api_key and api_key != "YOUR_KEY_HERE":
            try:
                raw_pcm = base64.b64decode(audio_b64, validate=False)
                if raw_pcm:
                    # Re-encode as single base64 for Google Cloud STT
                    combined_b64 = base64.b64encode(raw_pcm).decode("ascii")

                    url = f"https://speech.googleapis.com/v1/speech:recognize?key={api_key}"
                    payload = {
                        "config": {
                            "encoding": "LINEAR16",
                            "sampleRateHertz": 16000,
                            # English-only: no alternativeLanguageCodes. STT
                            # commits all decoding capacity to en-US, which
                            # measurably improves accuracy and removes the
                            # phantom-word insertions that appear when STT
                            # has to disambiguate between en-US / zh-CN / ms-MY.
                            "languageCode": "en-US",
                            "enableAutomaticPunctuation": True,
                            "maxAlternatives": 3,
                            # latest_long has lower phantom-word insertion than
                            # latest_short for English commands; override via
                            # GOOGLE_STT_MODEL env if you want a different model.
                            "model": os.environ.get("GOOGLE_STT_MODEL", "latest_long"),
                            "speechContexts": [{
                                "phrases": [
                                    # ── App names ──
                                    "WhatsApp", "What's App", "what app", "was app", "wash app",
                                    "WeChat", "Telegram", "Messenger", "Signal",
                                    "Facebook", "Instagram", "TikTok", "YouTube", "Gmail",
                                    "Settings", "Camera", "Phone", "Messages", "Contacts",
                                    "Maybank", "CIMB", "Public Bank", "Touch n Go", "Grab",

                                    # ── English verbs + common command patterns ──
                                    "open", "help me open", "I want to open", "please open",
                                    "send", "I want to send", "help me send", "please send",
                                    "call", "I want to call", "help me call",
                                    "find", "show", "go to", "close", "go back",
                                    "open WhatsApp", "open WeChat", "open Settings",
                                    "help me open WhatsApp", "I want to open WhatsApp",
                                    "send a message", "send a photo", "take a photo", "scan QR code",
                                    "send a photo to my son", "send a photo to my daughter",
                                    "send the photo", "send the photo to my son", "send the photo to my daughter",
                                    "send the message", "send the message to my son",
                                    "call my son", "call my daughter", "call my mother", "call my father",
                                    "send WhatsApp", "send on WhatsApp", "use WhatsApp to send",
                                    "using WhatsApp", "using the WhatsApp", "via WhatsApp",
                                    "with WhatsApp", "through WhatsApp", "on WhatsApp",
                                    "I want to send a photo to my son using WhatsApp",
                                    "I want to send the photo to my son using the WhatsApp",
                                    "WhatsApp my son", "WhatsApp my daughter",

                                    # ── Family / kinship terms (English only) ──
                                    "my son", "my daughter", "my mother", "my father",
                                    "my wife", "my husband", "my friend",
                                ],
                                "boost": 18.0
                            }]
                        },
                        "audio": {
                            "content": combined_b64
                        }
                    }

                    async def _call_api():
                        async with aiohttp.ClientSession() as session:
                            async with session.post(url, json=payload) as resp:
                                if resp.status != 200:
                                    error_text = await resp.text()
                                    raise Exception(f"STT API error {resp.status}: {error_text}")
                                return await resp.json()

                    result = await _call_api()
                    transcript = ""
                    confidence = None
                    results = result.get("results", [])
                    if results:
                        alternatives = results[0].get("alternatives", [])
                        if alternatives:
                            transcript, confidence = _select_google_stt_transcript(alternatives)

                    logger.info(
                        "Google STT result: %s%s",
                        transcript[:100] if transcript else "(empty)",
                        f" confidence={confidence:.2f}" if confidence is not None else "",
                    )
                    if transcript and confidence is not None and confidence < 0.55:
                        try:
                            fallback = await self._transcribe_audio_gemini(audio_b64)
                            if fallback.strip():
                                logger.info("Using Gemini STT fallback after low Google confidence")
                                return fallback.strip()
                        except Exception as e:
                            logger.info("Gemini STT fallback after low confidence failed: %s", e)
                    return transcript.strip()
            except Exception as e:
                logger.error("Google Cloud STT failed: %s", e)

        # Try Gemini fallback (if quota available)
        try:
            return await self._transcribe_audio_gemini(audio_b64)
        except Exception as e:
            logger.error("Gemini transcription failed: %s", e)

        # All methods failed
        logger.warning("All STT methods failed, returning empty")
        return ""

    async def _transcribe_audio_gemini(self, audio_b64: str) -> str:
        """Fallback: Use Gemini to transcribe audio."""
        try:
            raw_pcm = base64.b64decode(audio_b64, validate=False)
            audio_bytes = self._pcm16_to_wav_bytes(raw_pcm)

            audio_part = genai_types.Part(
                inline_data=genai_types.Blob(
                    mime_type="audio/wav",
                    data=audio_bytes
                )
            )

            prompt = "Transcribe this audio exactly as spoken. Return only the transcription text."

            loop = asyncio.get_running_loop()
            logger.info("Gemini STT fallback request: model=%s audio_bytes=%d", self._vision_model, len(audio_bytes))
            response = await loop.run_in_executor(
                None,
                lambda: self._gemini_client.models.generate_content(
                    model=self._vision_model,
                    contents=[audio_part, prompt],
                    config=genai_types.GenerateContentConfig(
                        temperature=0.1,
                    ),
                )
            )

            text = getattr(response, "text", "") or ""
            return text.strip()
        except Exception as e:
            logger.error("Gemini transcription fallback failed: %s", e)
            return ""

    # ── Outbound: Agent → Android ──────────────────────────────────────────

    def _on_executor_output(self, msg: dict) -> None:
        """Handle output from TaskExecutor and translate to legacy Android protocol."""
        asyncio.ensure_future(self._send_translated_output(msg))

    async def _send_translated_output(self, msg: dict) -> None:
        """Send translated messages with text + audio synchronized.

        For text-with-voice messages, the visible subtitle is sent SYNCHRONOUSLY
        (await) before moving on to the next message in the batch — this
        guarantees ordering when a guidance text is followed by a taskComplete
        signal. Without that, taskComplete races ahead of the guidance text,
        Android flips into its Done state and the user never sees / hears
        the final "Pick a photo. Tap the green Send button." instruction.

        Normally the actual TTS streaming runs in the background after the
        subtitle is delivered, so the next message is not blocked by ~3
        seconds of Gemini TTS synthesis. The exception is when the next
        message is a terminal state signal (taskComplete) — there we await
        the full TTS playout so the user hears the final guidance line
        BEFORE the floating ball flips into its Done animation.
        """
        translated = self._translate_legacy_outbound(msg)
        for i, legacy_msg in enumerate(translated):
            if self._should_generate_ai_voice(legacy_msg):
                next_msg = translated[i + 1] if i + 1 < len(translated) else None
                if next_msg and next_msg.get("type") == "taskComplete":
                    await self._send_text_with_synced_audio(legacy_msg)
                else:
                    await self._send_subtitle_then_background_tts(legacy_msg)
            else:
                await self._send_to_android(legacy_msg)

    async def _send_subtitle_then_background_tts(self, legacy_msg: dict) -> None:
        """Send the visible subtitle to Android, then fire TTS in background."""
        text = legacy_msg.get("text", "")
        language = legacy_msg.get("language") or self.tsm.get_context().get("outputLanguage")
        logger.info(
            "[TTS-DIAG] enter _send_subtitle_then_background_tts text=%r len=%d lang=%s",
            text[:40], len(text), language,
        )

        # 1. Subtitle goes out FIRST and is awaited — this preserves message
        #    ordering relative to taskComplete / highlight messages that follow.
        visual_msg = dict(legacy_msg)
        visual_msg["speak"] = False
        visual_msg["display"] = True
        await self._send_to_android(visual_msg)
        logger.info("[TTS-DIAG] subtitle delivered to Android; scheduling TTS in background")

        # 2. TTS playback runs in background — next message in the batch can
        #    be sent immediately without waiting for ~3s synthesis.
        asyncio.ensure_future(self._stream_tts_with_fallback(text, language, legacy_msg))

    async def _stream_tts_with_fallback(self, text: str, language: str | None, legacy_msg: dict) -> None:
        """Background coroutine: try LiveKit streaming TTS; fall back to local Android TTS on error."""
        try:
            await self._stream_tts_to_track(text, language)
            logger.info("[TTS-DIAG] _stream_tts_to_track completed normally")
        except Exception as e:
            logger.error("[TTS-DIAG] Streaming TTS to track failed: %s", e)
            fallback_msg = dict(legacy_msg)
            fallback_msg["speak"] = True
            fallback_msg["display"] = False
            await self._send_to_android(fallback_msg)
            logger.info("[TTS-DIAG] fallback message sent to Android with speak=True")

    async def _send_text_with_synced_audio(self, legacy_msg: dict) -> None:
        """Push subtitle to Android immediately, then stream TTS over the audio track."""
        text = legacy_msg.get("text", "")
        language = legacy_msg.get("language") or self.tsm.get_context().get("outputLanguage")
        logger.info("[TTS-DIAG] enter _send_text_with_synced_audio text=%r len=%d lang=%s", text[:40], len(text), language)

        # 1. Subtitle goes out immediately so the user can start reading while we synthesize.
        visual_msg = dict(legacy_msg)
        visual_msg["speak"] = False
        visual_msg["display"] = True
        await self._send_to_android(visual_msg)
        logger.info("[TTS-DIAG] subtitle sent to Android; about to call _stream_tts_to_track")

        # 2. Stream synthesized PCM directly into the LiveKit audio track.
        try:
            await self._stream_tts_to_track(text, language)
            logger.info("[TTS-DIAG] _stream_tts_to_track completed normally")
        except Exception as e:
            logger.error("[TTS-DIAG] Streaming TTS to track failed: %s", e)
            # Fall back to local Android TTS if the audio track path failed.
            fallback_msg = dict(legacy_msg)
            fallback_msg["speak"] = True
            fallback_msg["display"] = False
            await self._send_to_android(fallback_msg)
            logger.info("[TTS-DIAG] fallback message sent to Android with speak=True")

    def _should_generate_ai_voice(self, msg: dict) -> bool:
        if not self.voice_synthesizer.enabled:
            return False
        return (
            msg.get("type") == "text"
            and bool(msg.get("speak", True))
            and bool((msg.get("text") or "").strip())
        )

    @staticmethod
    def _is_action_guidance_text(text: str | None) -> bool:
        normalized = (text or "").strip().lower()
        if not normalized:
            return False
        return any(
            hint in normalized
            for hint in ("tap", "click", "press", "select", "choose", "open", "点", "点击", "选择", "打开")
        )

    async def _send_ai_voice(self, text: str) -> None:
        """Stream TTS over the audio track without the visual subtitle path."""
        language = self.tsm.get_context().get("outputLanguage")
        try:
            await self._stream_tts_to_track(text, language)
            return
        except Exception as e:
            logger.error("Streaming TTS to track failed: %s", e)

        await self._send_to_android({
            "type": "text",
            "text": text,
            "speak": True,
            "display": False,
        })

    # ── LiveKit audio track plumbing ───────────────────────────────────────

    async def _ensure_tts_track(self) -> None:
        """Create + publish the server-side TTS audio track once per session."""
        if self._tts_audio_source is not None:
            return
        if not self.room or not self.room.local_participant:
            logger.warning("_ensure_tts_track: room not ready, deferring")
            return

        self._tts_audio_source = rtc.AudioSource(TTS_SAMPLE_RATE, TTS_NUM_CHANNELS)
        track = rtc.LocalAudioTrack.create_audio_track("smarthelp-tts", self._tts_audio_source)
        options = rtc.TrackPublishOptions()
        options.source = rtc.TrackSource.SOURCE_UNKNOWN  # Server-generated, not mic input.
        try:
            self._tts_track_publication = await self.room.local_participant.publish_track(
                track, options
            )
            logger.info(
                "Published TTS audio track sid=%s",
                getattr(self._tts_track_publication, "sid", "?"),
            )
        except Exception as e:
            logger.error("Failed to publish TTS audio track: %s", e)
            self._tts_audio_source = None

    async def _stream_tts_to_track(self, text: str, language: str | None) -> None:
        """Synthesize streaming PCM and feed it into the audio track in 20ms frames."""
        logger.info("[TTS-DIAG] _stream_tts_to_track entry text=%r voice_enabled=%s track_ready=%s",
                    (text or "")[:40], self.voice_synthesizer.enabled, self._tts_audio_source is not None)
        if not (text or "").strip():
            logger.info("[TTS-DIAG] empty text — returning early")
            return
        await self._ensure_tts_track()
        if self._tts_audio_source is None:
            raise RuntimeError("TTS audio track is not available")
        logger.info("[TTS-DIAG] entering synth loop, about to iterate synthesize_pcm_stream")

        FRAME_DURATION_MS = 20
        samples_per_frame = TTS_SAMPLE_RATE * FRAME_DURATION_MS // 1000  # 480
        bytes_per_frame = samples_per_frame * TTS_NUM_CHANNELS * 2  # 960

        async with self._tts_lock:
            buffer = bytearray()
            try:
                async for pcm in self.voice_synthesizer.synthesize_pcm_stream(text, language):
                    buffer.extend(pcm)
                    while len(buffer) >= bytes_per_frame:
                        chunk = bytes(buffer[:bytes_per_frame])
                        del buffer[:bytes_per_frame]
                        frame = rtc.AudioFrame(
                            data=chunk,
                            sample_rate=TTS_SAMPLE_RATE,
                            num_channels=TTS_NUM_CHANNELS,
                            samples_per_channel=samples_per_frame,
                        )
                        await self._tts_audio_source.capture_frame(frame)
            finally:
                # Flush a final partial frame padded with silence so the last few
                # ms of speech don't get clipped.
                if buffer:
                    pad = bytes_per_frame - len(buffer)
                    if pad > 0:
                        buffer.extend(b"\x00" * pad)
                    frame = rtc.AudioFrame(
                        data=bytes(buffer[:bytes_per_frame]),
                        sample_rate=TTS_SAMPLE_RATE,
                        num_channels=TTS_NUM_CHANNELS,
                        samples_per_channel=samples_per_frame,
                    )
                    try:
                        await self._tts_audio_source.capture_frame(frame)
                    except Exception as e:
                        logger.warning("Final TTS frame flush failed: %s", e)
                # AudioSource has a ~1s internal queue: capture_frame() returns
                # as soon as frames are queued, NOT when they are played out.
                # Wait for the queue to drain so callers (e.g. the path that
                # sends taskComplete right after) only proceed once the user
                # has actually heard the line.
                try:
                    await self._tts_audio_source.wait_for_playout()
                except Exception as e:
                    logger.warning("wait_for_playout failed: %s", e)

    def _translate_legacy_outbound(self, msg: dict) -> list[dict]:
        """Translate internal message format to the Android client's expected protocol."""
        msg_type = msg.get("type")

        if msg_type == "connection_status":
            status = msg.get("status")
            if status == "connected":
                return [{"type": "ready"}]
            if status == "reconnecting":
                return [{"type": "connecting"}]
            return [{"type": "error", "message": "Connection lost"}]

        if msg_type == "guidance":
            messages = []
            if msg.get("text"):
                text = msg["text"]
                is_action_guidance = self._is_action_guidance_text(text)
                messages.append({
                    "type": "text",
                    "text": text,
                    "speak": bool(msg.get("speak", True)) and not is_action_guidance,
                    "display": not is_action_guidance,
                })
            if msg.get("highlight"):
                h = msg["highlight"]
                highlight_msg = {
                    "type": "highlight",
                    "x": h.get("x", 50), "y": h.get("y", 50),
                    "x1": h.get("x1"), "y1": h.get("y1"),
                    "x2": h.get("x2"), "y2": h.get("y2"),
                    "completed": bool(h.get("completed")),
                    "blockerDetected": bool(h.get("blockerDetected")),
                    "blockerReason": h.get("blockerReason"),
                    "direction": h.get("direction"),
                    "target": h.get("target"),
                    "matchHints": h.get("matchHints") or [],
                }
                messages.append(highlight_msg)
            return messages

        if msg_type == "chat":
            # Chat questions (e.g. "What name does your son use?") must be
            # spoken so the user knows SmartHelp+ is asking them something.
            return [{"type": "text", "text": msg.get("text", ""), "speak": True}]

        if msg_type == "completed":
            # Emit TWO messages:
            # 1. A "text" message so the final hand-off instruction
            #    ("Pick a photo. Tap the green Send button.") is both spoken
            #    (TTS via _should_generate_ai_voice) AND displayed in the
            #    chat bubble. Without this the user sees no completion text,
            #    triggers the Android "Please tap the orange dot" local
            #    fallback, and never hears what to do next.
            # 2. The "taskComplete" signal that flips the floating ball into
            #    its Done state and triggers the success animation.
            messages = []
            completion_text = (msg.get("text") or "").strip()
            if completion_text:
                messages.append({
                    "type": "text",
                    "text": completion_text,
                    "speak": True,
                })
            messages.append({"type": "taskComplete", "goal": msg.get("goal")})
            return messages

        if msg_type == "stuck":
            # Stuck messages must be spoken — that's how the user knows
            # SmartHelp+ has given up on this step. Without speak=True
            # the stuck text just sits silently on screen.
            return [{"type": "text", "text": msg.get("text", ""), "speak": True}]

        if msg_type == "screenshot_request":
            return [{"type": "requestScreenshot"}]

        if msg_type == "pong":
            return [{"type": "pong"}]

        if msg_type == "error":
            return [{"type": "error", "message": msg.get("message") or msg.get("text") or "Unknown error"}]

        return [msg]

    async def _send_to_android(self, msg: dict) -> None:
        """Send a JSON message to the Android client via Data Channel."""
        try:
            if msg.get("type") == "audio" and msg.get("data"):
                payload_size = len(json.dumps(msg).encode("utf-8"))
                if payload_size > MAX_DATA_MESSAGE_BYTES:
                    await self._send_chunked_audio(msg)
                    return

            payload = json.dumps(msg).encode("utf-8")
            await self.room.local_participant.publish_data(
                payload=payload,
                reliable=True,
            )
        except Exception as e:
            logger.error("Failed to send data: %s", e)

    async def _send_chunked_audio(self, msg: dict) -> None:
        audio_data = msg.get("data") or ""
        chunk_id = uuid.uuid4().hex[:12]
        chunk_size = max(1024, MAX_DATA_MESSAGE_BYTES - 400)
        total = (len(audio_data) + chunk_size - 1) // chunk_size

        for seq in range(total):
            start = seq * chunk_size
            chunk_msg = {
                "type": "audio_chunk",
                "id": chunk_id,
                "seq": seq,
                "total": total,
                "data": audio_data[start:start + chunk_size],
            }
            payload = json.dumps(chunk_msg).encode("utf-8")
            await self.room.local_participant.publish_data(payload=payload, reliable=True)

        end_msg = {
            "type": "audio_end",
            "id": chunk_id,
            "total": total,
            "mimeType": msg.get("mimeType", "audio/wav"),
            "text": msg.get("text", ""),
        }
        payload = json.dumps(end_msg).encode("utf-8")
        await self.room.local_participant.publish_data(payload=payload, reliable=True)

    # ── Gemini Vision (screenshot analysis) ────────────────────────────────
    # Vision schema + Gemini call live in vision_grounding_tool.VisionGroundingTool.
    # This shim keeps the existing executor callback signature.

    def _send_image_to_gemini(self, image_b64: str, prompt: str, mode: str | None = None) -> None:
        """Send screenshot + prompt to Gemini Vision and route the tool-call result back to the executor."""
        asyncio.ensure_future(self._run_vision(image_b64, prompt, mode))

    async def _run_vision(self, image_b64: str, prompt: str, mode: str | None = None) -> None:
        result = await self._vision_tool.ground(image_b64, prompt, mode)
        if result is not None:
            await self.executor.on_guide_tool_call(result)

    # ── Track handling ─────────────────────────────────────────────────────

    def _on_track_subscribed(
        self,
        track: rtc.Track,
        publication: rtc.RemoteTrackPublication,
        participant: rtc.RemoteParticipant,
    ) -> None:
        """Handle incoming tracks from the Android client."""
        if track.kind == rtc.TrackKind.KIND_VIDEO:
            logger.info("Video track subscribed from %s", participant.identity)
            # Video frames will be handled by the Gemini plugin automatically
            # if using AgentSession with video input support
        elif track.kind == rtc.TrackKind.KIND_AUDIO:
            logger.info("Audio track subscribed from %s", participant.identity)
            # Listen for audio frames
            self._setup_audio_frame_handler(track)

    def _on_participant_connected(self, participant: rtc.RemoteParticipant) -> None:
        logger.info("Participant %s connected", participant.identity)
        if participant.identity.startswith("agent-"):
            return
        # Android client joined — send ready (idempotent, only fires once per session)
        asyncio.ensure_future(self._send_ready_once())
        logger.info("Sent ready to Android client %s", participant.identity)

    def _setup_audio_frame_handler(self, track: rtc.Track) -> None:
        """Set up handler for incoming audio frames using AudioStream."""
        import base64

        self._audio_track_buffer = []
        frame_count = 0
        MAX_AUDIO_FRAMES = 1500
        self._last_partial_transcript_time = 0
        self._partial_transcript_interval = 1.5
        SILENCE_TIMEOUT = 5.0  # Stop processing after 5 seconds of silence
        self._last_speech_detected_time = time.time()

        logger.info("Setting up audio stream for track: %s", track.sid if hasattr(track, 'sid') else 'unknown')

        try:
            audio_stream = rtc.AudioStream(track, sample_rate=16000, num_channels=1)
            logger.info("AudioStream created successfully")
        except Exception as e:
            logger.error("Failed to create AudioStream: %s", e)
            return

        def _is_speech(audio_bytes: bytes, threshold: int = 500) -> bool:
            """Simple amplitude-based speech detection. Returns True if speech detected."""
            try:
                # Check if audio has enough energy to be speech
                # Sum of absolute values as a simple energy measure
                if len(audio_bytes) < 2:
                    return False
                energy = sum(abs(b - 128) for b in audio_bytes[:100])  # Sample first 100 bytes
                return energy > threshold * 10
            except Exception:
                # If anything fails, assume it's speech
                return True

        async def _process_audio_frames():
            nonlocal frame_count
            logger.info("Audio frame processor started")
            try:
                async for event in audio_stream:
                    try:
                        if not self._audio_stream_active:
                            # Check if silence timeout exceeded
                            if time.time() - self._last_speech_detected_time > SILENCE_TIMEOUT:
                                # Clear buffer after long silence
                                if self._audio_track_buffer:
                                    logger.info("Silence timeout, clearing buffer")
                                    self._audio_track_buffer.clear()
                            continue
                        
                        if event.frame and event.frame.data:
                            frame_count += 1
                            audio_bytes = event.frame.data
                            
                            # Check if this frame contains speech
                            if _is_speech(audio_bytes):
                                self._last_speech_detected_time = time.time()
                            
                            if len(self._audio_track_buffer) < MAX_AUDIO_FRAMES:
                                audio_b64 = base64.b64encode(audio_bytes).decode("ascii")
                                self._audio_track_buffer.append(audio_b64)
                            
                            # Send partial transcription every 1.5 seconds
                            current_time = time.time()
                            if (current_time - self._last_partial_transcript_time >= self._partial_transcript_interval 
                                and len(self._audio_track_buffer) > 50):
                                self._last_partial_transcript_time = current_time
                                asyncio.ensure_future(self._send_partial_transcript())
                            
                            if frame_count % 100 == 0:
                                logger.info("Received %d audio frames, buffer size: %d", frame_count, len(self._audio_track_buffer))
                            if self.executor.active_guide_request:
                                audio_b64 = base64.b64encode(audio_bytes).decode("ascii")
                                self.executor.on_audio_chunk({"data": audio_b64, "mimeType": "audio/pcm"})
                    except Exception as e:
                        logger.warning("Error processing audio frame: %s", e)
                        continue
            except Exception as e:
                logger.error("Audio frame processing error: %s", e)

        asyncio.ensure_future(_process_audio_frames())
        logger.info("Audio stream handler started for track")

    def _stop_audio_stream(self) -> None:
        """Stop the audio stream processing."""
        if hasattr(self, '_audio_stream_active'):
            self._audio_stream_active = False
            logger.info("Audio stream marked as inactive")

    def _reset_audio_stream(self) -> None:
        """Reset audio stream for new listening session."""
        self._audio_stream_active = True
        self._audio_track_buffer = []
        self._last_partial_transcript_time = 0
        logger.info("Audio stream reset for new session")

    async def _send_partial_transcript(self) -> None:
        """Send partial transcription while user is still speaking."""
        if not hasattr(self, '_audio_track_buffer') or not self._audio_track_buffer:
            return
        
        try:
            # Copy current buffer (don't clear it)
            audio_data = self._audio_track_buffer.copy()
            if len(audio_data) < 30:  # Too little audio
                return
            
            combined_audio = "".join(audio_data)
            transcript = await self._transcribe_audio(combined_audio)
            
            if transcript and transcript.strip():
                logger.info("Partial transcript: %s", transcript[:50])
                # Send partial result to Android
                await self._send_to_android({"type": "transcription", "text": transcript, "partial": True})
        except Exception as e:
            logger.debug("Partial transcription failed (non-critical): %s", e)

    def _stop_audio_stream(self) -> None:
        """Stop the audio stream processing."""
        if hasattr(self, '_audio_stream_active'):
            self._audio_stream_active = False
            logger.info("Audio stream marked as inactive")

    def _on_participant_disconnected(self, participant: rtc.RemoteParticipant) -> None:
        logger.info("Participant %s disconnected", participant.identity)
        if participant.identity.startswith("agent-"):
            return
        self._cleanup()

    # ── Timeout loop ───────────────────────────────────────────────────────

    async def _timeout_loop(self) -> None:
        while not self._closed:
            await asyncio.sleep(5)
            if self.tsm.is_awaiting_timeout():
                self.tsm.dispatch("timeout")
                try:
                    await self.executor.run()
                except Exception as e:
                    logger.error("Error in timeout handler: %s", e)

    def _cleanup(self) -> None:
        if self._closed:
            return
        self._closed = True
        if self._timeout_task:
            self._timeout_task.cancel()
        self.executor.cleanup()
        logger.info("Session %s cleaned up", self.session_id)


# ── LiveKit Agent Entrypoint ───────────────────────────────────────────────

async def entrypoint(ctx: JobContext) -> None:
    """Called by LiveKit when a new Room session starts."""
    await ctx.connect()

    agent = SmartHelpAgent(ctx)
    await agent.start()

    shutdown_event = asyncio.Event()

    def on_disconnected(*_args) -> None:
        shutdown_event.set()

    async def on_shutdown(_reason: str = "") -> None:
        shutdown_event.set()

    ctx.room.on("disconnected", on_disconnected)
    ctx.add_shutdown_callback(on_shutdown)

    try:
        # Keep the entrypoint coroutine alive until LiveKit disconnects or the job shuts down.
        await shutdown_event.wait()
    finally:
        agent._cleanup()


if __name__ == "__main__":
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
        force=True,
    )
    # Run jobs as threads in the same process. On Windows the default
    # PROCESS executor pipes subprocess stdout through multiprocessing, which
    # often hides logger.info output in dev terminals. THREAD keeps every
    # log line in this console at the cost of less crash isolation — fine
    # for SmartHelp+'s single-user-per-worker model.
    agents.cli.run_app(
        agents.WorkerOptions(
            entrypoint_fnc=entrypoint,
            job_executor_type=agents.JobExecutorType.THREAD,
        ),
    )
