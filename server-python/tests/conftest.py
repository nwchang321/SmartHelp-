"""Pytest configuration for SmartHelp server tests.

Sets a dummy `GOOGLE_API_KEY` so that constructors which eagerly build a
google.genai client (IntentAgent, NavigationReactAgent) succeed under the
test harness. Tests stub the client itself; they never make real Gemini
calls.
"""

import os

os.environ.setdefault("GOOGLE_API_KEY", "test-key-not-used")
