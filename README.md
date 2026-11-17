# mats-open

Open-source tools built for MATS (ML Alignment Theory Scholars) program operations.

## Projects

### [CalendarSummarizer](./CalendarSummarizer/)

Automated Google Calendar analysis for Research Managers. Fetches calendar data, uses Claude Haiku to categorize meetings (RM-Fellow 1:1s, team meetings, external, etc.), and generates weekly reports to Google Docs with hierarchical access control.

- Distinguishes meetings with own Fellows vs other RMs' Fellows
- 3-tier reporting: Individual RM → Senior RM aggregate → Executive overview
- Runs automatically via GitHub Actions

### [CrossCheck](./CrossCheck/)

Android app for cross-verified AI answers. Queries multiple AI models (OpenRouter, Anthropic, Gemini) in a three-stage verification process: initial answer → cross-check → final synthesis. Built for unreliable connections with aggressive local saving and smart retry.

### [sleep_and_mood_tracker](./sleep_and_mood_tracker/)

Android app for logging sleep, naps, mood and energy in a few taps. Offline only: it has no network permission, and the data leaves the phone only through Export, which produces a day-by-day sleep diary CSV and a full log.
