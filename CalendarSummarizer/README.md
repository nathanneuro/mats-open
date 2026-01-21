# Calendar Summarizer

Analyze Google Calendar meeting patterns for MATS Research Managers. Generates weekly reports with AI-powered meeting categorization.

## Features

- **Google Calendar Integration**: Fetches events via service account authentication
- **AI-Powered Categorization**: Uses Claude Haiku to classify meetings into categories:
  - RM-Fellow 1:1s
  - RM Team Meetings
  - Internal MATS meetings
  - External meetings
  - Workshops/Seminars
  - Interviews
  - Social events
  - Admin/blocked time
- **Time Analysis**: Hours per day/week, busiest times, category breakdowns
- **Markdown Reports**: Clean, shareable summaries saved to files

## Setup

### 1. Google Cloud Setup

1. Create a GCP project (or use existing)
2. Enable the Google Calendar API
3. Create a service account:
   - Go to IAM & Admin > Service Accounts
   - Create account with no special roles needed
   - Generate and download JSON key
4. For accessing user calendars (domain-wide delegation):
   - In Google Admin Console, go to Security > API Controls > Domain-wide delegation
   - Add the service account client ID
   - Add scope: `https://www.googleapis.com/auth/calendar.readonly`

### 2. Install

```bash
cd CalendarSummarizer
uv sync
```

### 3. Set Anthropic API Key

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

## Usage

Basic usage (current week):

```bash
uv run calendar-summarizer \
  --service-account /path/to/service-account.json \
  --calendar user@domain.com \
  --delegated-user user@domain.com
```

Analyze multiple weeks:

```bash
uv run calendar-summarizer \
  --service-account /path/to/service-account.json \
  --calendar user@domain.com \
  --delegated-user user@domain.com \
  --weeks 4 \
  --output outputs/monthly_report.md \
  --calendar-name "Research Manager Alice"
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `--service-account` | Yes | Path to Google service account JSON key |
| `--calendar` | Yes | Calendar ID (usually email address) |
| `--delegated-user` | No | Email to impersonate (for domain-wide delegation) |
| `--weeks` | No | Number of weeks to analyze (default: 1) |
| `--output` | No | Output file path (default: outputs/report_DATE.md) |
| `--calendar-name` | No | Display name for report header |

## Output

Reports are saved as markdown files with:

- Overview statistics (total meetings, hours, busiest day/hour)
- Category breakdown table
- Daily breakdown table
- Time distribution chart (ASCII)
- Meeting details by category

Example output excerpt:

```markdown
# Weekly Calendar Summary: Alice
**Week of January 13 - January 19, 2025**

## Overview
- **Total Meetings:** 23
- **Total Hours:** 18.5
- **Busiest Day:** Wednesday
- **Peak Hour:** 10:00

## Meetings by Category

| Category | Meetings | Hours |
|----------|----------|-------|
| RM-Fellow 1:1s | 8 | 8.0 |
| Internal MATS | 5 | 4.5 |
| External Meetings | 3 | 2.5 |
...
```

## Project Structure

```
CalendarSummarizer/
├── src/calendar_summarizer/
│   ├── __init__.py
│   ├── cli.py          # CLI entry point
│   ├── auth.py         # Google Calendar authentication
│   ├── fetcher.py      # Fetch calendar events
│   ├── categorizer.py  # Haiku-based categorization
│   ├── analyzer.py     # Time analysis
│   ├── reporter.py     # Markdown report generation
│   └── models.py       # Data models
├── tests/
├── outputs/            # Generated reports
├── pyproject.toml
└── README.md
```
