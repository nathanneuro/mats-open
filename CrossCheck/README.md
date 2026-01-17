# CrossCheck Android App

CrossCheck is an Android application that leverages multiple AI models to provide cross-verified, scientifically-backed answers to your questions through a three-stage verification process.

## Features

- **Multi-Provider Support**: Configure API keys for multiple AI providers:
  - OpenRouter
  - Anthropic (Claude)
  - Google Gemini

- **Three-Stage Verification Process**:
  1. **Initial Answer**: Gets a scientific answer with citations
  2. **Cross-Check**: Reviews and flags potential issues in the first answer
  3. **Final Synthesis**: Provides a comprehensive, verified answer

- **Query Modes**: Choose which models to use for verification:
  - **ABC Mode**: All three models (full verification)
  - **AC Mode**: Skip model B (initial + synthesis only)
  - **BC Mode**: Skip model A (cross-check + synthesis only)

- **Chat System**: Organize your queries into separate chat sessions
  - Each chat is a separate conversation thread
  - JSONL format for accessible storage (one JSON per query line)
  - Auto-naming chats using a utility model after first query
  - View chat history with timestamps and query counts

- **Configurable Query Order**: Choose which AI model to use for each stage
- **Expandable Responses**: View the final answer with the option to expand and see raw responses from each stage
- **Persistent Settings**: API keys and configurations are securely stored locally
- **Dark Theme**: Beautiful dark interface with purple (#8B5CF6) highlights and light grey text (#B4B4B4)

### Reliability Features

**Designed for unreliable internet connections - your data is valuable!**

- **Aggressive Local Saving**: Every query and response is saved immediately to local storage
  - User questions are saved before making any API calls
  - Responses are saved after each successful stage
  - All data persisted as plaintext JSON (plaintext is cheap, data loss is expensive!)

- **Smart Retry**: When a stage fails due to network issues:
  - A "Retry" button appears automatically
  - Previous successful responses are preserved
  - Retry continues from the failed stage (doesn't restart from scratch)
  - Example: If stage 2 fails, retry only re-runs stage 2 and 3, keeping stage 1 response

- **Crash Recovery**: If the app crashes or is interrupted:
  - On next launch, incomplete queries are automatically detected
  - User can continue where they left off with one tap
  - No data or progress is lost

- **Chat History**: All chats and queries are saved locally with:
  - Full question and all responses
  - Timestamp and completion status
  - Organized by chat sessions in JSONL format
  - Accessible storage format (one line per query in chat_<id>.jsonl files)
  - Browse all previous chats from the History screen

## Architecture

### Project Structure

```
CrossCheck/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/crosscheck/app/
│   │       │   ├── api/
│   │       │   │   └── ApiClient.kt          # API client for multiple providers
│   │       │   ├── data/
│   │       │   │   ├── ChatRepository.kt     # JSONL-based chat storage
│   │       │   │   └── SettingsRepository.kt # DataStore settings persistence
│   │       │   ├── models/
│   │       │   │   ├── ApiProvider.kt        # Provider data models
│   │       │   │   ├── AppSettings.kt        # App settings and query modes
│   │       │   │   ├── Chat.kt               # Chat metadata model
│   │       │   │   ├── QueryHistory.kt       # Query history model
│   │       │   │   └── QueryResponse.kt      # Query response states
│   │       │   ├── query/
│   │       │   │   └── QueryManager.kt       # 3-stage query orchestration with auto-save
│   │       │   ├── service/
│   │       │   │   └── ChatNamingService.kt  # Auto-naming chats with utility model
│   │       │   ├── MainActivity.kt           # Main query interface with retry and modes
│   │       │   ├── SettingsActivity.kt       # Settings configuration with utility model
│   │       │   ├── HistoryActivity.kt        # Chat history browser
│   │       │   └── AboutActivity.kt          # About screen with credits
│   │       ├── res/
│   │       │   ├── layout/                   # UI layouts (dark theme)
│   │       │   ├── menu/                     # Menu resources
│   │       │   └── values/                   # Strings, colors, themes
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

### Key Components

#### ApiClient (`api/ApiClient.kt`)
Handles HTTP requests to different AI providers with proper authentication and request formatting for:
- Anthropic API (Messages API)
- OpenRouter API (OpenAI-compatible)
- Google Gemini API

#### QueryManager (`query/QueryManager.kt`)
Orchestrates the three-stage query workflow with automatic saving:
1. Sends initial query with template: "Please answer the following question scientifically, ideally with citations: {question}"
2. Cross-checks with template: "Please cross-check this answer, flagging anything you are unsure of or believe is incorrect..."
3. Synthesizes final answer: "Please check these answers, summarize them, and give your own best answer..."

**Key features:**
- Respects query mode (ABC/AC/BC) to skip stages as configured
- Saves user question immediately before making API calls
- Saves response after each successful stage
- Supports resuming from any stage on retry
- Can skip already-completed stages when retrying

#### ChatRepository (`data/ChatRepository.kt`)
Manages local persistence of chats and queries using JSONL format:
- Stores each chat in separate `chat_<id>.jsonl` files (one JSON per line per query)
- Maintains `chats.json` for chat metadata (name, timestamp, query count)
- Maintains separate `current_query.json` for crash recovery
- Provides flow-based access to chat history
- Accessible storage format for easy data export

#### ChatNamingService (`service/ChatNamingService.kt`)
Automatically generates descriptive chat names:
- Runs after first query in a chat completes
- Uses configured utility model to generate 3-5 word title
- Updates chat metadata with generated name
- Handles errors gracefully (leaves chat unnamed on failure)

#### SettingsRepository (`data/SettingsRepository.kt`)
Manages persistent storage using DataStore:
- API providers and query order configuration
- Query mode preference (ABC/AC/BC)
- Utility model configuration for chat auto-naming

## Setup Instructions

### Prerequisites

- Android Studio Arctic Fox or later
- Android SDK 26 (Android 8.0) or higher
- API keys for at least one of the supported providers:
  - [Anthropic API Key](https://console.anthropic.com/)
  - [OpenRouter API Key](https://openrouter.ai/)
  - [Google AI Studio API Key](https://makersuite.google.com/app/apikey)

### Building the App

1. Open the `CrossCheck` folder in Android Studio

2. Sync Gradle files (File → Sync Project with Gradle Files)

3. Build the project (Build → Make Project)

4. Run on emulator or physical device (Run → Run 'app')

### Configuration

1. Launch the app and tap the "Settings" button

2. Configure utility model (optional, for auto-naming chats):
   - Select provider type (OpenRouter, Anthropic, or Gemini)
   - Enter your API key
   - Enter a fast, cheap model name (e.g., `claude-3-haiku-20240307`)

3. Add at least 3 API providers (one for each stage):
   - Tap "Add Provider"
   - Select provider type (OpenRouter, Anthropic, or Gemini)
   - Enter your API key
   - Enter the model name (examples below)

4. Configure query order:
   - Select which provider to use for each stage
   - Each stage can use a different provider/model

5. Tap "Save"

### Example Model Names

**Anthropic:**
- `claude-3-5-sonnet-20241022`
- `claude-3-opus-20240229`

**OpenRouter:**
- `anthropic/claude-3.5-sonnet`
- `openai/gpt-4-turbo`
- `google/gemini-pro`

**Google Gemini:**
- `gemini-pro`
- `gemini-1.5-pro`

## Usage

### Normal Query Flow

1. Configure settings (see Configuration above)

2. Select query mode from the toolbar spinner:
   - **ABC**: All three models (full verification)
   - **AC**: Skip model B (models A and C only)
   - **BC**: Skip model A (models B and C only)

3. Enter your question in the text input field

4. Tap "Submit"

5. Wait for the processing (stages depend on query mode):
   - ABC Mode: Stage 1 → Stage 2 → Stage 3 (all saved locally)
   - AC Mode: Stage 1 → Stage 3 (skips stage 2, saved locally)
   - BC Mode: Stage 2 → Stage 3 (skips stage 1, saved locally)

6. View the final answer

7. Optionally tap "Show Raw Responses" to see the individual responses from earlier stages

### Chat Management

- **New Chat**: Tap the "New Chat" button in the toolbar to start a fresh conversation
- **History**: Tap the "History" button to view all previous chats
  - Chats are auto-named after the first query (if utility model configured)
  - View query count and last updated time for each chat
  - Delete old chats by tapping the delete button
- **About**: Access from the menu (three dots) to view credits and how it works

### Handling Network Failures

**If a stage fails due to network issues:**

1. An error message appears showing which stage failed
2. A "Retry" button appears automatically
3. All previous successful responses are preserved and shown
4. Tap "Retry" to continue from where it failed
5. Only the failed stage (and subsequent stages) will be re-executed
6. Your original question and all successful responses remain intact

**Example scenario:**
- Stage 1 completes successfully → saved
- Stage 2 fails due to network timeout → error shown, retry button appears
- User taps "Retry"
- Stage 1 is skipped (already completed)
- Stage 2 and 3 are executed
- All data preserved throughout the process

### Crash Recovery

**If the app crashes or is closed during a query:**

1. On next launch, the app automatically detects the incomplete query
2. A recovery message appears
3. Your question and any completed responses are restored
4. The error state and retry button are shown
5. Tap "Retry" to complete the query from where it left off

**No manual intervention needed - your data is safe!**

## Technical Details

### Storage Format

**JSONL (JSON Lines) for Chats:**
- Each chat is stored in a separate file: `chat_<uuid>.jsonl`
- One line per query (each line is a complete JSON object)
- Easy to parse, export, and process with standard tools
- Human-readable and accessible format

**Example chat file structure:**
```jsonl
{"id":"query-1","chatId":"chat-123","question":"What is...","firstResponse":"...","secondResponse":"...","thirdResponse":"...","timestamp":1234567890}
{"id":"query-2","chatId":"chat-123","question":"How does...","firstResponse":"...","secondResponse":"...","thirdResponse":"...","timestamp":1234567900}
```

**Chat Metadata (`chats.json`):**
```json
[
  {
    "id": "chat-123",
    "name": "Science Questions About...",
    "createdAt": 1234567890,
    "lastUpdatedAt": 1234567900,
    "queryCount": 2
  }
]
```

### Dependencies

- **AndroidX Core & AppCompat**: Modern Android components
- **Material Design Components**: UI components following Material Design
- **Kotlin Coroutines**: Asynchronous programming
- **OkHttp**: HTTP client for API requests
- **Gson**: JSON serialization/deserialization
- **DataStore**: Persistent key-value storage

### Permissions

- `INTERNET`: Required for API calls
- `ACCESS_NETWORK_STATE`: Check network connectivity

### Security Considerations

- API keys are stored locally using DataStore (encrypted on device)
- All API communications use HTTPS
- API keys are never logged or exposed in UI (password input type)
- Chat data stored in app's private storage (not accessible by other apps)

## Prompt Templates

### Stage 1: Initial Answer
```
Please answer the following question scientifically, ideally with citations: {user_question}
```

### Stage 2: Cross-Check
```
Please cross-check this answer, flagging anything you are unsure of or believe is incorrect. If incomplete, flesh it out.

User Question: {user_question}

First Response: {stage_1_response}
```

### Stage 3: Final Synthesis
```
Please check these answers, summarize them, and give your own best answer informed by research.

User Question: {user_question}

First Response: {stage_1_response}

Second Response: {stage_2_response}
```

## Customization

### Adding New AI Providers

To add support for a new AI provider:

1. Add the provider type to `ProviderType` enum in `models/ApiProvider.kt`
2. Add the base URL in `getBaseUrl()` method
3. Implement the API call logic in `api/ApiClient.kt`
4. Update the UI strings in `res/values/strings.xml`

### Modifying Prompt Templates

Prompt templates can be customized in `query/QueryManager.kt`:
- `buildPrompt1()`: Initial query
- `buildPrompt2()`: Cross-check query
- `buildPrompt3()`: Final synthesis query

## Troubleshooting

### "Please configure at least one API provider"
- Go to Settings and add at least 3 providers (one for each stage)
- Ensure all fields (API key and model name) are filled

### API Errors
- Verify your API keys are correct
- Check that model names match the provider's available models
- Ensure you have credits/quota remaining on your API accounts
- Check your internet connection

### App Won't Build
- Ensure you have Android SDK 34 installed
- Sync Gradle files
- Clean and rebuild (Build → Clean Project, then Build → Rebuild Project)

## Future Enhancements

Possible improvements for future versions:
- Support for additional AI providers (OpenAI direct, Cohere, etc.)
- Customizable prompt templates in UI
- Export chats to PDF or text
- Streaming responses for real-time feedback
- Offline caching of responses
- Chat search and filtering
- Response bookmarking and favorites

## License

This project is open source. Feel free to modify and distribute as needed.

## Contributing

Contributions are welcome! Please feel free to submit pull requests or open issues for bugs and feature requests.
