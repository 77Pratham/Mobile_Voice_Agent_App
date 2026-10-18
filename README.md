# Mobile Voice Agent App

An Android voice-assistant prototype built with Kotlin. It supports spoken interaction in English and Hindi/Hinglish, using on-device speech components for speech recognition and speech synthesis, with a cloud LLM for conversational responses.


## Features

- **On-device speech recognition:** Sherpa-ONNX integration with local speech-recognition models.
- **Voice activity detection:** Silero VAD integration.
- **On-device text-to-speech:** Piper/VITS voice models for speech output.
- **Cloud LLM responses:** Groq API with the configured model `openai/gpt-oss-20b`.
- **English and Hindi/Hinglish interaction:** Designed for multilingual voice interaction; accuracy can vary by language, accent, and code-switching.
- **Weather lookup:** Uses the Open-Meteo forecast API.
- **Alarm action:** Uses Android alarm-related intents.
- **Calendar lookup:** Queries calendar information available to the app, subject to Android permissions and device configuration.

## Architecture overview

1. The app captures the user's speech.
2. Local speech components process audio and produce recognized text.
3. The recognized text is used to determine the request and, when needed, sent to the configured cloud LLM.
4. The app executes supported actions, such as a weather lookup or alarm request.
5. The response is spoken using local text-to-speech.

**Network note:** Speech recognition and speech synthesis are designed to run on-device when the required model files are present. LLM responses require internet access and a valid Groq API key. Weather requests also require internet access.

## Technology stack

- Kotlin
- Android SDK / Android Studio
- Sherpa-ONNX
- Whisper and Hindi speech-recognition model(s), depending on the configured model assets
- Silero Voice Activity Detection (VAD)
- Piper/VITS text-to-speech models
- Groq API
- Open-Meteo API

## Requirements

- Android Studio with a compatible Android SDK and Gradle/JDK setup.
- An Android device or emulator meeting the app's minimum SDK requirement (API 23).
- The model files expected by the app, placed in the configured asset/model locations.
- Internet connectivity for cloud LLM and weather features.
- A Groq API key for LLM responses.

## Setup

1. Clone the repository:

   ```bash
   git clone https://github.com/77Pratham/Mobile_Voice_Agent_App.git
   cd Mobile_Voice_Agent_App
   ```

2. Open the project in Android Studio and allow Gradle sync to complete.

3. Configure the Groq API key locally. The project reads `GROQ_API_KEY` from Gradle project properties or `local.properties` to generate `BuildConfig.GROQ_API_KEY`. Add the following line to the project's root `local.properties` file, preserving any existing Android SDK settings:

   ```properties
   GROQ_API_KEY=YOUR_NEW_GROQ_API_KEY
   ```

   Replace the placeholder with your own key. **Never commit `local.properties` or hardcode the key in source code.** The repository's `.gitignore` should exclude `local.properties`.

4. Ensure all speech-recognition, VAD, and TTS model files required by the app are available in the locations expected by the implementation. Model files may not be included in the Git repository. Check the model-loading code and asset setup before running the app.

5. Sync Gradle, build the app, then run it on a device or emulator. Grant any permissions requested for microphone access and supported calendar/alarm functionality.

## Configuration and security

- LLM model: `openai/gpt-oss-20b`
- API key: configured locally as `GROQ_API_KEY`
- Do not publish API keys in source files, screenshots, logs, demo videos, or commits.
- If a key was ever committed or exposed, revoke it and generate a replacement. Removing it from the latest version alone does not invalidate it.
- Do not put private credentials in the README.

## Evaluation notes and limitations

- This is a prototype, not a production-hardened voice assistant.
- English/Hindi/Hinglish recognition and code-switching should be tested with representative speakers and audio conditions.
- Behavior can vary depending on device performance, microphone quality, model files, permissions, and network availability.
- Cloud LLM calls send recognized user text to the configured provider. Avoid speaking sensitive personal information during testing.
- Check model licenses and usage terms before redistribution.

## Suggested manual test checklist

- [ ] Recognize a short English sentence.
- [ ] Recognize a Hindi sentence.
- [ ] Test a Hinglish sentence with English and Hindi mixed.
- [ ] Check spoken output for intelligibility and correct pronunciation.
- [ ] Test a weather request while online.
- [ ] Test an alarm request and verify Android handles it as expected.
- [ ] Test calendar lookup with and without the required permission.
- [ ] Test behavior when internet access is unavailable.
- [ ] Record observed latency and transcription errors rather than estimating them.

## Repository

https://github.com/77Pratham/Mobile_Voice_Agent_App
