"""Gemini TTS helper for SmartHelp server-side voice output."""

from __future__ import annotations

import asyncio
import base64
import io
import logging
import os
import wave
from collections import OrderedDict
from typing import AsyncIterator, Iterator

from google import genai
from google.genai import types

# Gemini TTS native output format. Used by every caller publishing into a LiveKit
# AudioSource so frame timing stays aligned.
TTS_SAMPLE_RATE = 24000
TTS_NUM_CHANNELS = 1

logger = logging.getLogger("smarthelp.voice")

DEFAULT_TTS_MODELS = [
    "gemini-3.1-flash-tts-preview",
    "gemini-2.5-flash-preview-tts",
]

DEFAULT_VOICES = {
    "en": "Aoede",
    "zh": "Aoede",
    "ms": "Aoede",
}


def _detect_language(text: str) -> str:
    """Best-effort language detection used when the caller doesn't pass one.
    Any CJK Unified Ideograph in the text → 'zh'; otherwise 'en'."""
    if not text:
        return "en"
    for ch in text:
        if "一" <= ch <= "鿿":
            return "zh"
    return "en"


def _normalize_language(language: str | None) -> str:
    if not language:
        return ""
    lang = language.strip().lower().replace("_", "-")
    if lang.startswith("zh"):
        return "zh"
    if lang.startswith("ms"):
        return "ms"
    if lang.startswith("en"):
        return "en"
    return lang


class GeminiVoiceSynthesizer:
    """Generate natural assistant speech as base64 WAV audio."""

    def __init__(self) -> None:
        enabled = os.environ.get("SMARTHELP_AI_TTS", "true").lower() not in {"0", "false", "no", "off"}
        api_key = os.environ.get("GOOGLE_API_KEY")
        self.enabled = enabled and bool(api_key)
        self._client = genai.Client(api_key=api_key) if self.enabled else None
        # Backwards-compatible single voice override (applies to all languages if set).
        legacy_voice = os.environ.get("GEMINI_TTS_VOICE")
        self._voices: dict[str, str] = {
            "en": os.environ.get("GEMINI_TTS_VOICE_EN") or legacy_voice or DEFAULT_VOICES["en"],
            "zh": os.environ.get("GEMINI_TTS_VOICE_ZH") or legacy_voice or DEFAULT_VOICES["zh"],
            "ms": os.environ.get("GEMINI_TTS_VOICE_MS") or legacy_voice or DEFAULT_VOICES["ms"],
        }
        self._cache_limit = int(os.environ.get("SMARTHELP_TTS_CACHE_LIMIT", "64"))
        self._cache: OrderedDict[str, str] = OrderedDict()
        primary_model = os.environ.get("GEMINI_TTS_MODEL")
        fallback_model = os.environ.get("GEMINI_TTS_FALLBACK_MODEL")
        models = [primary_model, fallback_model, *DEFAULT_TTS_MODELS]
        self._models = list(dict.fromkeys(model for model in models if model))
        self._semaphore = asyncio.Semaphore(1)

        if not self.enabled:
            logger.info("Gemini TTS disabled; Android local TTS will be used as fallback")
        else:
            logger.info("Gemini TTS configured models=%s", ", ".join(self._models))

    def _voice_for_language(self, language: str | None, text: str) -> str:
        lang = _normalize_language(language) or _detect_language(text)
        return self._voices.get(lang, self._voices["en"])

    async def synthesize_wav_base64(self, text: str, language: str | None = None) -> str | None:
        message = (text or "").strip()
        if not message:
            return None

        async with self._semaphore:
            loop = asyncio.get_running_loop()
            return await loop.run_in_executor(
                None, self.synthesize_wav_base64_sync, message, language
            )

    def synthesize_wav_base64_sync(self, text: str, language: str | None = None) -> str | None:
        message = (text or "").strip()
        if not self.enabled or not self._client or not message:
            return None
        voice = self._voice_for_language(language, message)
        cache_key = f"{voice}\n{message}"
        if cache_key in self._cache:
            audio = self._cache.pop(cache_key)
            self._cache[cache_key] = audio
            return audio

        audio = self._synthesize_sync(message, voice)
        if audio and self._cache_limit > 0:
            self._cache[cache_key] = audio
            while len(self._cache) > self._cache_limit:
                self._cache.popitem(last=False)
        return audio

    def _synthesize_sync(self, text: str, voice: str) -> str | None:
        prompt = (
            "Read this text aloud in its original language, with a warm, natural, patient "
            "smartphone assistant tone. Do not translate, add, or remove any words:\n"
            f"{text}"
        )

        last_error: Exception | None = None
        for model in self._models:
            try:
                logger.info("Gemini TTS request: model=%s voice=%s text_len=%d", model, voice, len(text))
                response = self._client.models.generate_content(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        response_modalities=["AUDIO"],
                        speech_config=types.SpeechConfig(
                            voice_config=types.VoiceConfig(
                                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                                    voice_name=voice,
                                )
                            )
                        ),
                    ),
                )
                pcm = self._extract_pcm(response)
                if not pcm:
                    raise ValueError("Gemini TTS returned no audio data")
                return base64.b64encode(self._pcm_to_wav(pcm)).decode("ascii")
            except Exception as exc:
                last_error = exc
                logger.warning("Gemini TTS failed with model %s (voice=%s): %s", model, voice, exc)

        if last_error:
            logger.error("Gemini TTS unavailable after fallback attempts: %s", last_error)
        return None

    async def synthesize_pcm_stream(
        self, text: str, language: str | None = None
    ) -> AsyncIterator[bytes]:
        """Yield raw 24 kHz mono int16 PCM chunks as they arrive from Gemini.

        Used by the LiveKit audio-track path in agent.py — bypasses WAV wrapping
        and base64 so frames can be pushed directly into rtc.AudioSource."""
        message = (text or "").strip()
        if not self.enabled or not self._client or not message:
            return

        voice = self._voice_for_language(language, message)
        loop = asyncio.get_running_loop()
        # Bounded queue so the producer thread back-pressures if Android falls behind.
        queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=64)

        def _producer() -> None:
            try:
                for pcm in self._stream_pcm_sync(message, voice):
                    asyncio.run_coroutine_threadsafe(queue.put(pcm), loop).result()
            except Exception as exc:
                logger.error("PCM stream producer crashed: %s", exc)
            finally:
                asyncio.run_coroutine_threadsafe(queue.put(None), loop)

        producer_future = loop.run_in_executor(None, _producer)
        try:
            while True:
                chunk = await queue.get()
                if chunk is None:
                    break
                yield chunk
        finally:
            # Make sure the producer is fully drained before the generator returns.
            await asyncio.wrap_future(producer_future)

    def _stream_pcm_sync(self, text: str, voice: str) -> Iterator[bytes]:
        prompt = (
            "Read this text aloud in its original language, with a warm, natural, patient "
            "smartphone assistant tone. Do not translate, add, or remove any words:\n"
            f"{text}"
        )

        last_error: Exception | None = None
        for model in self._models:
            try:
                logger.info("Gemini streaming TTS request: model=%s voice=%s text_len=%d", model, voice, len(text))
                stream = self._client.models.generate_content_stream(
                    model=model,
                    contents=prompt,
                    config=types.GenerateContentConfig(
                        response_modalities=["AUDIO"],
                        speech_config=types.SpeechConfig(
                            voice_config=types.VoiceConfig(
                                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                                    voice_name=voice,
                                )
                            )
                        ),
                    ),
                )
                produced_any = False
                for chunk in stream:
                    pcm = self._extract_pcm(chunk)
                    if pcm:
                        produced_any = True
                        yield pcm
                if produced_any:
                    return
                raise ValueError("Gemini streaming TTS returned no audio data")
            except Exception as exc:
                last_error = exc
                logger.warning(
                    "Streaming TTS failed (%s/%s): %s", model, voice, exc
                )

        if last_error:
            logger.error("Streaming TTS unavailable after fallback attempts: %s", last_error)

    @staticmethod
    def _extract_pcm(response) -> bytes | None:
        candidates = getattr(response, "candidates", None) or []
        if not candidates:
            return None
        content = getattr(candidates[0], "content", None)
        parts = getattr(content, "parts", None) if content else None
        if not parts:
            return None
        inline_data = getattr(parts[0], "inline_data", None)
        data = getattr(inline_data, "data", None) if inline_data else None
        if data is None:
            return None
        if isinstance(data, str):
            return base64.b64decode(data)
        return bytes(data)

    @staticmethod
    def _pcm_to_wav(pcm: bytes, channels: int = 1, rate: int = 24000, sample_width: int = 2) -> bytes:
        buffer = io.BytesIO()
        with wave.open(buffer, "wb") as wf:
            wf.setnchannels(channels)
            wf.setsampwidth(sample_width)
            wf.setframerate(rate)
            wf.writeframes(pcm)
        return buffer.getvalue()
