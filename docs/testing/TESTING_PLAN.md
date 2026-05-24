# SmartHelp+ Testing Plan

## 1. Purpose

This testing plan evaluates whether SmartHelp+ can help elderly users complete common smartphone tasks more easily, with fewer errors, and with better confidence. The evaluation follows the FYP1 report scope, especially the FYP2 requirement for usability testing with 5 elderly participants aged 55 and above.

## 2. Testing Rounds

| Round | Type | Participants | Purpose | Record Status |
|---|---|---:|---|---|
| Round 1 | Informal beta testing | Friends / early testers | Identify obvious bugs, UI issues, and flow problems before elderly testing | Retrospective summary only |
| Round 2 | Elderly usability testing | 5 elderly participants | Evaluate usability, task completion, errors, completion time, and satisfaction | Full recording template required |

## 3. Round 1: Informal Beta Testing

Round 1 was conducted as informal beta testing. Since detailed records were not collected during the test session, it should be reported as a pilot activity rather than formal evaluation data.

The beta testing summary should only include:

- Date range of testing
- Number of testers, if known
- Device used
- Main features tested
- Issues discovered
- Improvements made after beta testing

Do not invent numeric results such as completion time, SUS score, or success rate if they were not recorded.

## 4. Round 2: Elderly Usability Testing

### 4.1 Participants

Target participants:

- 5 elderly users aged 55 and above
- Own or have basic experience using Android smartphones
- Experience difficulty with at least some non-basic smartphone applications
- Able to provide informed consent
- Able to communicate in English, Malay, Mandarin, or Chinese dialects supported by the researcher

Exclusion criteria:

- Severe visual impairment that prevents viewing the smartphone screen
- Severe cognitive impairment that prevents informed consent
- Professional IT background

### 4.2 Test Design

Use a within-subject design. Each participant attempts selected tasks:

- Without SmartHelp+ assistance
- With SmartHelp+ assistance

If time is limited, the researcher may select 3 to 5 tasks per participant, but all five task categories should be covered across the full study.

To reduce learning bias, alternate the order:

| Participant | First Condition | Second Condition |
|---|---|---|
| P1 | Without SmartHelp+ | With SmartHelp+ |
| P2 | With SmartHelp+ | Without SmartHelp+ |
| P3 | Without SmartHelp+ | With SmartHelp+ |
| P4 | With SmartHelp+ | Without SmartHelp+ |
| P5 | Without SmartHelp+ | With SmartHelp+ |

### 4.3 Test Tasks

| ID | Task |
|---|---|
| T1 | Send a WhatsApp message with a photo |
| T2 | Open phone Settings and increase text size or display size |
| T3 | Set a calendar reminder |
| T4 | Navigate MySejahtera and find a health-related page |
| T5 | Open Google Maps and search for a nearby clinic |

Note: For Google Maps testing, participants only need to search for a nearby clinic and identify the search results. They do not need to start navigation.

### 4.4 Data to Collect

Quantitative data:

| Metric | Description |
|---|---|
| Task completion | Completed / Not completed |
| Completion time | Time in seconds from task start to success or abandonment |
| Error count | Number of wrong taps, wrong pages, repeated failed attempts, or wrong inputs |
| Assistance requests | Number of times participant asks researcher for help |
| SmartHelp+ guidance failures | Wrong instruction, wrong arrow, missing response, STT/TTS problem |
| SUS score | System Usability Scale score after testing |

Qualitative data:

- Participant comments during testing
- Observed confusion points
- Whether the visual overlay was clear
- Whether speech guidance was understandable
- Whether the participant trusted the instructions
- Suggested improvements

Technical data:

- STT recognition accuracy observations
- TTS output clarity
- AI response latency
- Screenshot / screen analysis success
- Overlay accuracy
- App crash or disconnection

## 5. Technical Edge Case Testing

In addition to elderly usability testing, technical edge case testing should be conducted by the researcher. These tests evaluate whether SmartHelp+ can handle realistic failure conditions discovered during beta testing, such as permission denial, unclear voice input, STT errors, inaccurate overlay guidance, and network interruption.

These tests do not need to be performed by elderly participants. They can be performed by the developer/researcher before or after the elderly user sessions.

| ID | Edge Case | Expected Result |
|---|---|---|
| TC-T01 | User denies screen capture permission | App shows a clear message and does not crash |
| TC-T02 | User denies overlay permission | App explains that overlay permission is required |
| TC-T03 | User gives unclear voice command | App asks a clarification question instead of executing the wrong task |
| TC-T04 | STT misrecognizes the command | App allows retry or asks clarification |
| TC-T05 | AI returns wrong coordinate or wrong arrow | App can re-analyze, retry, or allow user to request help |
| TC-T06 | Slow internet or AI API delay | App shows waiting/loading state and does not freeze |
| TC-T07 | Server disconnected | App shows disconnected or reconnect message |
| TC-T08 | User opens the wrong app during guidance | SmartHelp+ detects screen mismatch and redirects the user |
| TC-T09 | Sensitive screen appears | App avoids guiding through private, password, OTP, or banking steps |
| TC-T10 | User cancels task halfway | App stops guidance and clears overlay |

## 6. Test Procedure

1. Prepare the phone, server, network, and SmartHelp+ app.
2. Explain the study purpose to the participant.
3. Obtain consent.
4. Record participant profile using anonymous ID, such as P1.
5. Demonstrate only basic operation, not the exact solution steps.
6. Start the task timer.
7. Observe and record completion, errors, assistance requests, and comments.
8. Repeat the same task under the second condition if using within-subject comparison.
9. After all tasks, ask the participant to complete SUS.
10. Conduct a short post-test interview.
11. Save all records using participant ID only.

## 7. Success Criteria

Suggested evaluation targets:

| Area | Target |
|---|---|
| Task completion | Higher completion rate with SmartHelp+ than without assistance |
| Completion time | Shorter average completion time with SmartHelp+ |
| Error count | Fewer wrong taps or navigation mistakes with SmartHelp+ |
| User satisfaction | SUS score above 68 is acceptable; above 80 is excellent |
| Elderly suitability | Participants report that guidance is clear, useful, and easy to follow |
| Technical reliability | No major crash during test sessions; guidance can recover from minor failures |

## 8. Ethical and Privacy Considerations

- Use participant IDs instead of real names.
- Do not record passwords, OTPs, banking details, private chats, or personal photos.
- For WhatsApp photo testing, use a prepared sample image and a test contact where possible.
- For MySejahtera or similar apps, avoid pages containing private medical information.
- For Google Maps, avoid collecting or publishing the participant's exact home address or private location history.
- Participants may stop testing at any time.
- The researcher should assist immediately if the participant becomes uncomfortable or confused.

## 9. Final Testing Deliverables

The final report should include:

- Round 1 beta testing summary
- Round 2 elderly usability testing plan
- Completed test case sheets
- Technical edge case test results
- Quantitative result tables
- SUS score summary
- Interview feedback themes
- Identified usability issues
- Improvements made after testing
