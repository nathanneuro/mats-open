# Calendar Summarizer

Analyze Google Calendar meeting patterns for MATS Research Managers. Generates weekly reports with AI-powered meeting categorization that distinguishes between own-Fellow and cross-RM Fellow meetings.

## Features

- **Google Calendar Integration**: Fetches events via service account authentication
- **Smart Categorization**: Uses Claude Haiku + RM-Fellow assignment data to classify meetings:
  - **1:1s with Own Fellows** - RM meeting with their assigned Fellows
  - **1:1s with Other Fellows** - RM meeting with Fellows assigned to other RMs
  - **Group Meetings with Own Fellows** - RM meeting with multiple of their Fellows
  - **All-Staff Meetings** - Full team meetings
  - **RM Community of Practice** - All-RM meetings
  - **Subteam Meetings** - Defined subteam gatherings
  - Plus: External, Workshops, Interviews, Social, Admin, Internal MATS
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

### 2. Create MATS Config

Create a JSON config file defining your organizational structure. See `data/example_config.json`:

```json
{
  "rm_fellow_assignments": {
    "alice.rm@mats.org": ["fellow1@uni.edu", "fellow2@gmail.com"],
    "bob.rm@mats.org": ["fellow3@uni.edu", "fellow4@gmail.com"]
  },
  "all_rms": ["alice.rm@mats.org", "bob.rm@mats.org"],
  "all_staff": ["alice.rm@mats.org", "bob.rm@mats.org", "ops@mats.org"],
  "subteams": {
    "leadership": ["alice.rm@mats.org", "director@mats.org"]
  }
}
```

### 3. Install

```bash
cd CalendarSummarizer
uv sync
```

### 4. Set Anthropic API Key

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
```

## Usage

Basic usage (current week):

```bash
uv run calendar-summarizer \
  --service-account /path/to/service-account.json \
  --calendar alice.rm@mats.org \
  --delegated-user alice.rm@mats.org \
  --config data/mats_config.json
```

Analyze multiple weeks:

```bash
uv run calendar-summarizer \
  --service-account /path/to/service-account.json \
  --calendar alice.rm@mats.org \
  --delegated-user alice.rm@mats.org \
  --config data/mats_config.json \
  --weeks 4 \
  --output outputs/alice_monthly.md \
  --calendar-name "Alice (Research Manager)"
```

### Arguments

| Argument | Required | Description |
|----------|----------|-------------|
| `--service-account` | Yes | Path to Google service account JSON key |
| `--calendar` | Yes | Calendar ID (email address of the RM) |
| `--config` | Yes | Path to MATS config JSON with RM-Fellow assignments |
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
| 1:1s with Own Fellows | 6 | 6.0 |
| 1:1s with Other Fellows | 2 | 2.0 |
| RM Community of Practice | 1 | 1.0 |
| All-Staff Meetings | 1 | 1.5 |
...
```

## Project Structure

```
CalendarSummarizer/
├── src/calendar_summarizer/
│   ├── __init__.py
│   ├── cli.py          # CLI entry point
│   ├── auth.py         # Google Calendar authentication
│   ├── config.py       # MATS organizational config
│   ├── fetcher.py      # Fetch calendar events
│   ├── categorizer.py  # Haiku + assignment-based categorization
│   ├── analyzer.py     # Time analysis
│   ├── reporter.py     # Markdown report generation
│   └── models.py       # Data models
├── data/
│   └── example_config.json  # Example MATS config
├── tests/
├── outputs/            # Generated reports
├── pyproject.toml
└── README.md
```
