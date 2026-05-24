# Technical Edge Case Test Template

This template is used to record system robustness testing. These tests are performed by the researcher/developer, not necessarily by elderly participants.

## Test Environment

| Item | Details |
|---|---|
| Tester |  |
| Test date |  |
| Android device |  |
| Android version |  |
| SmartHelp+ build/version |  |
| Server environment |  |
| Network |  |

## Edge Case Test Results

| ID | Test Case | Steps | Expected Result | Actual Result | Status | Notes / Fix Required |
|---|---|---|---|---|---|---|
| TC-T01 | Screen capture permission denied | Start SmartHelp+ and deny screen capture permission | App shows a clear message and does not crash |  | Pass / Fail / Partial |  |
| TC-T02 | Overlay permission denied | Start SmartHelp+ without overlay permission | App explains that overlay permission is required |  | Pass / Fail / Partial |  |
| TC-T03 | Unclear voice command | Say an unclear or incomplete command, such as "help me do this" | App asks a clarification question |  | Pass / Fail / Partial |  |
| TC-T04 | STT misrecognition | Say a phrase likely to be misrecognized, then retry | App allows retry or asks clarification |  | Pass / Fail / Partial |  |
| TC-T05 | Wrong arrow or coordinate | Trigger guidance on a screen where target may be ambiguous | App can re-analyze, retry, or allow help request |  | Pass / Fail / Partial |  |
| TC-T06 | Slow internet / AI delay | Use weak Wi-Fi or simulate delay before AI response | App shows waiting/loading state and remains usable |  | Pass / Fail / Partial |  |
| TC-T07 | Server disconnected | Stop the backend/server while app is connected | App shows disconnected/reconnect message |  | Pass / Fail / Partial |  |
| TC-T08 | Wrong app opened during guidance | During a task, open a different app manually | SmartHelp+ detects mismatch and redirects user |  | Pass / Fail / Partial |  |
| TC-T09 | Sensitive screen appears | Navigate to a password, OTP, banking, or private information screen | App avoids unsafe guidance and protects privacy |  | Pass / Fail / Partial |  |
| TC-T10 | User cancels task halfway | Start a task and press cancel/close halfway | App stops guidance and clears overlay |  | Pass / Fail / Partial |  |

## Issue Log

| Issue ID | Related Test | Problem Observed | Severity | Fix / Improvement | Status |
|---|---|---|---|---|---|
| E1 |  |  | Low / Medium / High |  | Fixed / Pending |
| E2 |  |  | Low / Medium / High |  | Fixed / Pending |
| E3 |  |  | Low / Medium / High |  | Fixed / Pending |

## Summary Paragraph Template

> Technical edge case testing was conducted to evaluate the robustness of SmartHelp+ under realistic failure conditions. These tests were added because informal beta testing revealed issues such as speech recognition errors, inaccurate overlay positioning, and connection interruptions. The edge case tests focused on permission handling, unclear voice input, STT misrecognition, network delay, server disconnection, wrong-app navigation, sensitive-screen handling, and task cancellation. The results were used to identify remaining system limitations and guide refinement before elderly user evaluation.
