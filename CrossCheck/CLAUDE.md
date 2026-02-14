# CrossCheck - Claude Code Instructions

## Versioning Policy
Keep all build tooling at latest stable versions: AGP, Kotlin, Gradle, compileSdk/targetSdk, and AndroidX dependencies. When upgrading, update everything together.

## Project Overview
Android app that uses multiple AI models for cross-verified answers through a 3-stage verification process (initial answer, cross-check, synthesis).

## Architecture
- **Kotlin / Android SDK 35** (minSdk 26)
- **ViewBinding** (no Compose)
- **OkHttp** for API calls to Anthropic, OpenRouter, Google Gemini
- **JSONL** storage format for chat history

## TODOs
- [ ] Create cute pixel art app icon

## Build
```
cd CrossCheck && ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
```
