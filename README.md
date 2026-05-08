# Rokid Style Clawsses

Audio-first OpenClaw and ElevenLabs assistant for Rokid AI Glasses Style.

This Android/Kotlin port adapts the original Clawsses iOS companion into a standalone Style app. It connects to an OpenClaw gateway over WebSocket, captures voice commands, forwards them to the active session, and plays responses through ElevenLabs TTS.

## Features

- Foreground microphone service
- OpenClaw WebSocket client
- Session-aware voice command pipeline
- ElevenLabs TTS playback queue
- Minimal launcher and settings screens

## Build

Open the folder in Android Studio and build the `app` module.

## Notes

Configure the OpenClaw host, port, token, ElevenLabs API key, and voice ID before use.
