# Round 2 Elderly User Test Cases Template

## Participant Information

| Field | Value |
|---|---|
| Participant ID | P___ |
| Age range | 55-60 / 61-65 / 66-70 / 71+ |
| Gender |  |
| Main language | English / Malay / Mandarin / Other |
| Smartphone experience | Low / Medium / High |
| Owns Android phone | Yes / No |
| Consent obtained | Yes / No |
| Test date |  |

## Common Result Fields

Use these fields for every task.

| Field | Description |
|---|---|
| Condition | Without SmartHelp+ / With SmartHelp+ |
| Start time |  |
| End time |  |
| Completion time | Seconds |
| Task completed | Yes / No |
| Error count | Wrong taps, wrong pages, repeated failed attempts |
| Assistance requests | Number of times participant asks researcher for help |
| SmartHelp+ issues | Wrong arrow, wrong instruction, STT error, TTS error, delay, crash |
| Participant comments |  |
| Researcher notes |  |

## TC1: Send WhatsApp Message With Photo

| Item | Details |
|---|---|
| Test case ID | TC-E01 |
| Objective | Evaluate whether the participant can use SmartHelp+ to send a WhatsApp message with a photo. |
| App / area | WhatsApp |
| Pre-condition | WhatsApp is installed. A safe test contact and sample photo are prepared. |
| Test instruction | Send the prepared photo to the test contact through WhatsApp. |
| Success criteria | Participant opens WhatsApp, selects correct contact, attaches/selects the sample photo, and reaches the send step. |
| Privacy note | Use a test contact and sample image. Do not use private chats or personal photos. |

### TC-E01 Result Record

| Condition | Completed? | Time (s) | Errors | Assistance Requests | Notes |
|---|---|---:|---:|---:|---|
| Without SmartHelp+ |  |  |  |  |  |
| With SmartHelp+ |  |  |  |  |  |

## TC2: Increase Text Size or Display Size

| Item | Details |
|---|---|
| Test case ID | TC-E02 |
| Objective | Evaluate whether SmartHelp+ can guide the participant to increase text size or display size. |
| App / area | Android Settings |
| Pre-condition | Phone text/display size is set to default before testing. |
| Test instruction | Open Settings and increase the phone text size or display size. |
| Success criteria | Participant reaches the correct settings page and increases text size or display size. |
| Privacy note | No personal data involved. Restore setting after testing if needed. |

### TC-E02 Result Record

| Condition | Completed? | Time (s) | Errors | Assistance Requests | Notes |
|---|---|---:|---:|---:|---|
| Without SmartHelp+ |  |  |  |  |  |
| With SmartHelp+ |  |  |  |  |  |

## TC3: Set Calendar Reminder

| Item | Details |
|---|---|
| Test case ID | TC-E03 |
| Objective | Evaluate whether the participant can create a simple reminder with guidance. |
| App / area | Calendar app |
| Pre-condition | Calendar app is available. Test reminder title is prepared. |
| Test instruction | Create a reminder called "Doctor appointment" for tomorrow at 10:00 AM. |
| Success criteria | Participant creates or reaches the confirmation step for the reminder with correct title, date, and time. |
| Privacy note | Use a test reminder only. Delete it after testing. |

### TC-E03 Result Record

| Condition | Completed? | Time (s) | Errors | Assistance Requests | Notes |
|---|---|---:|---:|---:|---|
| Without SmartHelp+ |  |  |  |  |  |
| With SmartHelp+ |  |  |  |  |  |

## TC4: Navigate MySejahtera Health Page

| Item | Details |
|---|---|
| Test case ID | TC-E04 |
| Objective | Evaluate whether SmartHelp+ can guide the participant through a Malaysian health-related app workflow. |
| App / area | MySejahtera or selected health app |
| Pre-condition | App is installed and logged in only if necessary. Avoid private medical records. |
| Test instruction | Open MySejahtera and find a selected health-related page, such as appointment, help, or health records page. |
| Success criteria | Participant reaches the target page without exposing private information. |
| Privacy note | Do not collect screenshots of private health data. Use a non-sensitive page if possible. |

### TC-E04 Result Record

| Condition | Completed? | Time (s) | Errors | Assistance Requests | Notes |
|---|---|---:|---:|---:|---|
| Without SmartHelp+ |  |  |  |  |  |
| With SmartHelp+ |  |  |  |  |  |

## TC5: Search for Nearby Clinic in Google Maps

| Item | Details |
|---|---|
| Test case ID | TC-E05 |
| Objective | Evaluate whether SmartHelp+ can guide the participant to search for a practical location using Google Maps. |
| App / area | Google Maps |
| Pre-condition | Google Maps is installed. Location permission may be enabled, or the researcher may provide a starting area. |
| Test instruction | Open Google Maps and search for a nearby clinic. |
| Success criteria | Participant opens Google Maps, searches for "clinic near me" or a similar query, and reaches the clinic search results page. |
| Privacy note | Do not record or publish the participant's exact home address or private location history. The participant does not need to start navigation. |

### TC-E05 Result Record

| Condition | Completed? | Time (s) | Errors | Assistance Requests | Notes |
|---|---|---:|---:|---:|---|
| Without SmartHelp+ |  |  |  |  |  |
| With SmartHelp+ |  |  |  |  |  |

## Post-Test Interview Template

Ask after the participant finishes the tasks.

1. Which guidance was the most helpful?
2. Was any instruction confusing?
3. Was the arrow or highlight easy to see?
4. Was the voice guidance clear?
5. Did the app make the task feel easier?
6. Would you use this app in daily life?
7. What should be improved?

## SUS Questionnaire

Use a 1 to 5 scale:

1 = Strongly disagree  
2 = Disagree  
3 = Neutral  
4 = Agree  
5 = Strongly agree

| No. | Statement | Score |
|---:|---|---:|
| 1 | I think that I would like to use this system frequently. |  |
| 2 | I found the system unnecessarily complex. |  |
| 3 | I thought the system was easy to use. |  |
| 4 | I think that I would need help from a technical person to use this system. |  |
| 5 | I found the various functions in this system were well integrated. |  |
| 6 | I thought there was too much inconsistency in this system. |  |
| 7 | I would imagine that most people would learn to use this system very quickly. |  |
| 8 | I found the system very awkward to use. |  |
| 9 | I felt very confident using the system. |  |
| 10 | I needed to learn a lot of things before I could get going with this system. |  |

SUS scoring:

- For odd items 1, 3, 5, 7, 9: score contribution = response - 1
- For even items 2, 4, 6, 8, 10: score contribution = 5 - response
- Add all contributions and multiply by 2.5
- SUS above 68 is considered above average
- SUS above 80 is considered excellent
