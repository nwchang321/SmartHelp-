"""
Generate a LiveKit access token for the Android client.

Usage:
    python generate_token.py [identity] [room]

This creates a JWT token that the Android app uses to connect to the LiveKit room.
The token includes permissions for audio, video, and data publishing.

Requires the `livekit-api` package (pip install livekit-api).
"""

import os
import sys
import uuid
from datetime import timedelta

from dotenv import load_dotenv
from livekit.api import AccessToken, VideoGrants

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

API_KEY = os.environ.get("LIVEKIT_API_KEY", "")
API_SECRET = os.environ.get("LIVEKIT_API_SECRET", "")


def generate_token(identity: str = "android-user", room: str | None = None) -> str:
    """Generate a LiveKit-compatible JWT access token using the official SDK."""
    if not API_KEY or not API_SECRET:
        print("ERROR: LIVEKIT_API_KEY and LIVEKIT_API_SECRET must be set in .env")
        sys.exit(1)

    if not room:
        room = f"smarthelp-{uuid.uuid4().hex[:8]}"

    token = (
        AccessToken(API_KEY, API_SECRET)
        .with_identity(identity)
        .with_name(identity)
        .with_ttl(timedelta(hours=1))
        .with_grants(
            VideoGrants(
                room_join=True,
                room=room,
                can_publish=True,
                can_subscribe=True,
                can_publish_data=True,
            )
        )
    )

    return token.to_jwt()


if __name__ == "__main__":
    identity = sys.argv[1] if len(sys.argv) > 1 else "android-user"
    room = sys.argv[2] if len(sys.argv) > 2 else None
    token = generate_token(identity, room)
    room_name = room or "(auto-generated)"
    print(f"\n{'='*60}")
    print(f"LiveKit Access Token for: {identity}")
    print(f"Room: {room_name}")
    print(f"Expiry: 1 hour")
    print(f"{'='*60}")
    print(f"\n{token}\n")
    print(f"{'='*60}")
    print("Manual fallback: paste this token into SmartHelp+ only if the token server is unavailable.")
    print(f"{'='*60}\n")
