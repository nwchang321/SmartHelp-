# Technical Edge Case Test Results

This file records the technical robustness testing performed for SmartHelp+ on 14 May 2026. It follows the structure of `TECHNICAL_EDGE_CASE_TEST_TEMPLATE.md`.

## Test Environment

| Item | Details |
|---|---|
| Tester | Codex automation, developer-supervised local workspace |
| Test date | 14 May 2026 |
| Android device | Serial `9d2d9bb6`, model `2410CRP4CG` |
| Android version | Android `16`, MIUI `V816` |
| SmartHelp+ build/version | Debug build, `versionName 1.0.0`, `applicationId com.smarthelp.app` |
| Server environment | Windows PowerShell, `server-python` virtual environment, Python `3.13.0` |
| Network | Not required for local unit tests. Live LiveKit/Gemini flow was not executed because device-side APK installation was restricted. |

## Automated Verification Summary

| Area | Command | Result |
|---|---|---|
| Python backend tests | `.\venv\Scripts\python.exe -m pytest -q` in `server-python` | Pass: `88 passed in 2.11s` |
| Android JVM unit tests | `.\gradlew.bat testDebugUnitTest --console=plain` with `JAVA_HOME=C:\Program Files (x86)\Android\openjdk\jdk-17.0.14` | Pass: `34 tests`, `0 failures`, `0 errors` |
| Android debug build | `.\gradlew.bat assembleDebug --console=plain` | Pass: `BUILD SUCCESSFUL` |
| Android instrumented tests | `.\gradlew.bat connectedDebugAndroidTest --console=plain` | Blocked: device rejected test APK install with `INSTALL_FAILED_USER_RESTRICTED`; `0 tests` executed |

Android unit test breakdown:

| Test Class | Tests | Failures | Errors |
|---|---:|---:|---:|
| `MessageProtocolTest` | 8 | 0 | 0 |
| `OverlayDemoLaunchTest` | 3 | 0 | 0 |
| `OverlayWindowHelperTest` | 8 | 0 | 0 |
| `TaskSessionLocalTest` | 3 | 0 | 0 |
| `SpeechStateTest` | 12 | 0 | 0 |

## Edge Case Test Results

| ID | Test Case | Steps | Expected Result | Actual Result | Status | Notes / Fix Required |
|---|---|---|---|---|---|---|
| TC-T01 | Screen capture permission denied | Attempted to prepare device-side testing; reviewed Android build and screen capture service compilation. | App shows a clear message and does not crash. | Manual permission denial flow was not executed because the connected Android device rejected APK/test APK installation. Build compiled successfully. | Blocked | Enable MIUI Developer Options > Install via USB, or use an emulator, then execute this flow manually. |
| TC-T02 | Overlay permission denied | Attempted device-side testing; verified overlay-related unit tests and debug build. | App explains that overlay permission is required. | Manual overlay denial flow was not executed due device install restriction. `OverlayWindowHelperTest` passed, covering overlay parameter/helper behavior. | Partial | Needs real-device permission denial verification. |
| TC-T03 | Unclear voice command | Ran backend state machine and planner heuristic tests. | App asks a clarification question. | Backend tests confirm clarification flow for unclear goal, empty plan failure handling, and `ask_user` step behavior. | Partial | Backend logic passed; live voice UI behavior still requires device run. |
| TC-T04 | STT misrecognition | Ran planner/FSM tests covering misheard WhatsApp-style command recovery path. | App allows retry or asks clarification. | Backend logic covers unclear/misheard goal clarification. Live microphone/STT retry flow was not executed. | Partial | Add direct unit tests for `_normalize_stt_transcript` and run live mic flow after device install restriction is resolved. |
| TC-T05 | Wrong arrow or coordinate | Ran accessibility-assisted guidance tests and Android highlight/session unit tests. | App can re-analyze, retry, or allow help request. | Tests passed for prompt accessibility context and WhatsApp onboarding known-button guidance before model call. Real screenshot coordinate accuracy was not manually verified. | Partial | Needs live screenshot/overlay validation on emulator or unlocked USB-install device. |
| TC-T06 | Slow internet / AI delay | Built Android app and checked message/state unit tests. | App shows waiting/loading state and remains usable. | Not network-simulated in this run. Relevant UI/state code compiled; no live delay test performed. | Blocked | Requires network throttling or artificial backend delay during connected session. |
| TC-T07 | Server disconnected | Built Android app and verified message protocol/unit tests. | App shows disconnected/reconnect message. | Live disconnect flow was not executed because app could not be installed/launched on the connected device. | Blocked | Requires installed app plus running token server/agent, then stop backend during session. |
| TC-T08 | Wrong app opened during guidance | Reviewed verification architecture and ran backend state machine tests. | SmartHelp+ detects mismatch and redirects user. | Backend VERIFY/RETRY states passed in unit tests. Real wrong-app screenshot flow was not executed. | Partial | Needs live Gemini vision verification test with deliberately wrong foreground app. |
| TC-T09 | Sensitive screen appears | Ran `SafetyGate` backend tests and Android build. | App avoids unsafe guidance and protects privacy. | Backend sensitive-screen handler and scam/safety rules passed. Android `PrivacySafety` compiled, but no real banking/OTP screen was opened. | Partial | Add Android JVM tests for `PrivacySafety.isSensitivePackage/isSensitiveQuery`; run manual sensitive-screen test later. |
| TC-T10 | User cancels task halfway | Ran backend state machine cancel tests. | App stops guidance and clears overlay. | Backend cancellation transitions from GUIDING/AWAITING_ACTION to IDLE passed. Overlay close/cancel UI was not manually executed. | Partial | Needs device UI test once instrumented install is allowed. |

## Issue Log

| Issue ID | Related Test | Problem Observed | Severity | Fix / Improvement | Status |
|---|---|---|---|---|---|
| E1 | Instrumented tests | `connectedDebugAndroidTest` failed before execution because MIUI blocked test APK installation: `INSTALL_FAILED_USER_RESTRICTED`. | Medium | Enable Developer Options > USB debugging (Security settings) / Install via USB, approve installation prompt, or run tests on an emulator. | Pending |
| E2 | TC-T01, TC-T02, TC-T06, TC-T07 | Several edge cases require a live installed app session and could not be executed in this run. | Medium | Re-run manual QA after resolving device installation restriction. | Pending |
| E3 | Android Gradle build | Build shows deprecated Android Gradle Plugin option warnings that may become incompatible with Gradle/AGP 10. | Low | Clean up deprecated properties in `gradle.properties` before final submission if time allows. | Pending |
| E4 | TC-T04, TC-T09 | Some backend behavior is covered indirectly but lacks direct unit tests for STT normalization and Android privacy helper keywords. | Low | Add focused tests for `_normalize_stt_transcript`, `PrivacySafety.isSensitivePackage`, and `PrivacySafety.isSensitiveQuery`. | Pending |

## Summary

Technical edge case testing was conducted to evaluate the robustness of SmartHelp+ under realistic failure conditions. Automated backend and Android JVM tests passed, confirming that the task state machine, safety gate, task planning heuristics, message protocol, local task session state, speech UI state, and accessibility-assisted guidance helpers behave as expected at unit-test level. The debug APK also built successfully.

The main limitation of this test run was device-side execution. The connected MIUI device rejected installation of the Android test APK with `INSTALL_FAILED_USER_RESTRICTED`, so instrumented tests and live permission/network/overlay flows could not be completed. These flows should be repeated on an emulator or on a device with USB installation permissions enabled before final elderly-user evaluation.
