# Calendar Summarizer - Development Notes

## Overview

This tool analyzes Google Calendar data for MATS Research Managers, providing weekly summaries with AI-powered meeting categorization.

## Architecture

- **auth.py**: Google Calendar API authentication via service accounts. Supports domain-wide delegation for accessing user calendars.
- **fetcher.py**: Paginates through Calendar API to fetch events. Skips all-day events (no time data). Expands recurring events into instances.
- **categorizer.py**: Batch processes events through Claude Haiku for categorization. Uses structured JSON output parsing.
- **analyzer.py**: Pure functions for computing statistics. Handles daily/weekly aggregation, time distributions.
- **reporter.py**: Generates markdown reports. ASCII charts for time distribution.
- **cli.py**: Ties everything together with argparse CLI.

## Key Design Decisions

1. **Service account auth**: Chosen over OAuth for headless/automated operation. Requires GCP setup but enables running as a cron job.

2. **Batch categorization**: Events are categorized in batches of 20 to reduce API calls while keeping context manageable.

3. **Categories are MATS-specific**: The MeetingCategory enum and categorization prompt are tailored to MATS terminology (Research Managers, Fellows, etc.).

## Running Tests

```bash
uv run pytest
```

## Common Tasks

### Add a new meeting category

1. Add to `MeetingCategory` enum in `models.py`
2. Add description to `CATEGORIZATION_PROMPT` in `categorizer.py`
3. Add display label to `CATEGORY_LABELS` in `reporter.py`

### Change report format

Edit `reporter.py`. The `generate_weekly_report` function builds the markdown string.

### Support OAuth flow

Would need to add to `auth.py`:
- OAuth credentials flow
- Token storage/refresh
- Interactive browser auth

## Credentials

- **Google**: Service account JSON key (store in `data/` or external secure location)
- **Anthropic**: API key via `ANTHROPIC_API_KEY` env var
