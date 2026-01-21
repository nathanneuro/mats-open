# Calendar Summarizer

Analyze Google Calendar meeting patterns for MATS Research Managers. Generates weekly reports with AI-powered meeting categorization, automatically saved to Google Docs with hierarchical access control.

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
- **Hierarchical Reports**:
  - Individual RM reports (visible to RM + their manager + exec)
  - Senior RM aggregate reports (for managers of multiple RMs)
  - Executive overview (full team aggregate)
- **Google Docs Output**: Reports prepended to docs (newest first), auto-shared with appropriate viewers
- **Automated via GitHub Actions**: Runs weekly on schedule

## Report Hierarchy

```
Executive Overview Recipients (Head of RM + Execs)
    ├── Full team executive overview
    ├── Access to all Senior RM aggregates
    └── Access to all individual RM reports

Senior RMs
    ├── Their own individual report
    ├── Aggregate of RMs they manage (including themselves)
    └── Access to direct reports' individual reports

RMs
    └── Their own individual report
```

## Setup

### 1. Google Cloud Setup

1. Create a GCP project (or use existing)
2. Enable the **Google Calendar API** and **Google Docs API** and **Google Drive API**
3. Create a service account:
   - Go to IAM & Admin > Service Accounts
   - Create account with no special roles needed
   - Generate and download JSON key
4. For domain-wide delegation (accessing user calendars):
   - In Google Admin Console, go to Security > API Controls > Domain-wide delegation
   - Add the service account client ID
   - Add scopes:
     - `https://www.googleapis.com/auth/calendar.readonly`
     - `https://www.googleapis.com/auth/documents`
     - `https://www.googleapis.com/auth/drive.file`

### 2. Create MATS Config

Create `data/mats_config.json` defining your organizational structure. See `data/example_config.json`:

```json
{
  "rm_fellow_assignments": {
    "alice@mats.org": ["fellow1@uni.edu", "fellow2@gmail.com"],
    "bob@mats.org": ["fellow3@uni.edu"]
  },
  "all_rms": ["alice@mats.org", "bob@mats.org", "carol@mats.org"],
  "all_staff": ["alice@mats.org", "bob@mats.org", "carol@mats.org", "ops@mats.org"],
  "rm_names": {
    "alice@mats.org": "Alice",
    "bob@mats.org": "Bob"
  },
  "senior_rm_direct_reports": {
    "alice@mats.org": ["bob@mats.org"]
  },
  "executive_overview_recipients": ["director@mats.org"],
  "rm_report_destinations": {
    "alice@mats.org": {"doc_title": "Calendar Summary - Alice"},
    "bob@mats.org": {"doc_title": "Calendar Summary - Bob"}
  },
  "senior_rm_aggregate_destinations": {
    "alice@mats.org": {"doc_title": "Team Summary - Alice's Team"}
  },
  "executive_overview_destination": {
    "doc_title": "RM Team Executive Overview"
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

### Single RM (manual)

```bash
uv run calendar-summarizer \
  --service-account /path/to/service-account.json \
  --calendar alice@mats.org \
  --delegated-user alice@mats.org \
  --config data/mats_config.json
```

### Batch (all RMs)

```bash
uv run calendar-summarizer-batch \
  --service-account /path/to/service-account.json \
  --config data/mats_config.json \
  --weeks 1
```

Options:
- `--dry-run`: Generate reports without writing to Google Docs
- `--output-dir outputs/`: Save local markdown copies
- `--weeks 4`: Analyze multiple weeks

### GitHub Actions (Automated)

The workflow at `.github/workflows/calendar-summarizer.yml` runs weekly.

Required secrets:
- `GOOGLE_SERVICE_ACCOUNT_JSON`: Full JSON content of service account key
- `ANTHROPIC_API_KEY`: Anthropic API key

To trigger manually: Actions → Weekly Calendar Summary → Run workflow

## Output

Reports are prepended to Google Docs (newest week at top). Each report includes:

- Overview statistics (total meetings, hours, busiest day/hour)
- Category breakdown table
- Daily breakdown table
- Time distribution chart
- Meeting details by category

### Executive Overview

```markdown
# Executive Summary: RM Team
**Week of January 13 - January 19, 2025**

## Key Metrics
- **RMs Active:** 4
- **Total Meeting Hours:** 72.5
- **Fellow-Facing Hours:** 45.0 (62%)

## RM Comparison
| RM | Total Hours | Fellow Hours | % Fellow |
|----|-------------|--------------|----------|
| Alice | 18.5 | 12.0 | 65% |
| Bob | 16.0 | 10.5 | 66% |
...
```

## Project Structure

```
CalendarSummarizer/
├── src/calendar_summarizer/
│   ├── cli.py          # Single-RM CLI
│   ├── batch.py        # Batch processing CLI
│   ├── auth.py         # Google Calendar auth
│   ├── config.py       # Org structure config
│   ├── fetcher.py      # Fetch calendar events
│   ├── categorizer.py  # Haiku categorization
│   ├── analyzer.py     # Time analysis
│   ├── aggregator.py   # Multi-RM aggregation
│   ├── reporter.py     # Report generation
│   ├── google_docs.py  # Google Docs API
│   └── models.py       # Data models
├── data/
│   └── example_config.json
├── .github/workflows/
│   └── calendar-summarizer.yml
└── pyproject.toml
```
