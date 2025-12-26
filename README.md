# SmartHelp+ MVP

AI-Based Smartphone Guidance App for Elderly Users

## Project Structure

```
SmartHelp/
├── server/           # Node.js Backend
│   ├── src/
│   │   ├── index.js              # Express server
│   │   ├── routes/analyze.js     # API endpoints
│   │   └── services/geminiService.js  # Gemini AI integration
│   ├── package.json
│   └── .env.example
│
└── android/          # Android App
    └── app/
        └── src/main/
            ├── java/com/smarthelp/app/
            │   ├── MainActivity.java        # Main UI
            │   ├── ApiClient.java           # HTTP client
            │   ├── ScreenCaptureService.java # Screen capture
            │   └── OverlayService.java      # Visual + TTS guidance
            ├── res/
            └── AndroidManifest.xml
```

## Setup Instructions

### 1. Backend Server Setup

```bash
# Navigate to server directory
cd SmartHelp/server

# Install dependencies
npm install

# Create .env file from template
copy .env.example .env

# Edit .env and add your Gemini API key
# GEMINI_API_KEY=your_api_key_here

# Start the server
npm start
```

Server will run on `http://localhost:3000`

### 2. Test Server Connection

```bash
# Test if server is running
curl http://localhost:3000/health

# Test Gemini API connection
curl http://localhost:3000/api/test
```

### 3. Android App Setup

1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to `SmartHelp/android` folder
4. Wait for Gradle sync to complete
5. Build the project (Build > Make Project)

### 4. Configure Server URL (Important!)

Edit `ApiClient.java` and update the `BASE_URL`:

```java
// For Android Emulator:
private static final String BASE_URL = "http://10.0.2.2:3000";

// For Physical Device (replace with your computer's IP):
private static final String BASE_URL = "http://192.168.x.x:3000";
```

To find your computer's IP:
- Windows: `ipconfig` in Command Prompt
- Mac/Linux: `ifconfig` in Terminal

### 5. Run the App

1. Connect Android device or start emulator
2. Make sure server is running
3. Click "Run" in Android Studio
4. Grant permissions when prompted:
   - Overlay permission (Display over other apps)
   - Screen capture permission

## Testing the MVP

1. Start the Node.js server
2. Install and open SmartHelp+ app
3. Tap "Start Help"
4. Grant permissions
5. Navigate to any app (WhatsApp, Settings, etc.)
6. SmartHelp+ will:
   - Capture your screen every 5 seconds
   - Analyze with Gemini AI
   - Show floating guidance overlay
   - Speak instructions via TTS

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Server health check |
| `/api/status` | GET | API status |
| `/api/test` | GET | Test Gemini connection |
| `/api/analyze` | POST | Analyze screenshot |

### Analyze Endpoint

**Request:**
```json
{
  "screenshot": "base64_encoded_image",
  "userQuery": "How do I send a message?" // optional
}
```

**Response:**
```json
{
  "success": true,
  "context": "WhatsApp chat screen",
  "guidance": {
    "voiceText": "Tap the text box at the bottom to type a message",
    "steps": ["Tap the text box", "Type your message", "Tap send"],
    "highlight": {
      "position": "bottom-center",
      "description": "Message input field"
    }
  }
}
```

## Requirements

### Server
- Node.js 18+
- Gemini API Key

### Android
- Android 8.0 (API 26) or higher
- Internet connection
- Permissions: Overlay, Screen Capture

## Troubleshooting

### Server Issues
- Check if port 3000 is available
- Verify Gemini API key in .env
- Check internet connection

### Android Issues
- Ensure overlay permission is granted
- Check if server URL is correct
- For physical device: ensure same WiFi network as server

### MediaProjection Issues
- Emulator may not support screen capture well
- Test on physical device for best results

## Next Steps

After MVP validation:
1. Add Chinese language support
2. Implement task-specific guidance
3. Add Google ADK for multi-agent orchestration
4. Create offline mode
5. User testing with elderly participants
