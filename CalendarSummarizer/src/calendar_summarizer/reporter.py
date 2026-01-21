"""Generate markdown reports from calendar analysis."""

from datetime import datetime
from pathlib import Path
from typing import Sequence

from .models import CategorizedEvent, MeetingCategory, WeeklySummary
from .analyzer import compute_day_of_week_distribution, compute_meeting_time_distribution


CATEGORY_LABELS = {
    # RM-Fellow meetings
    MeetingCategory.RM_OWN_FELLOW_1_1: "1:1s with Own Fellows",
    MeetingCategory.RM_OTHER_FELLOW_1_1: "1:1s with Other Fellows",
    MeetingCategory.RM_OWN_FELLOWS_GROUP: "Group Meetings with Own Fellows",
    # Team meetings
    MeetingCategory.ALL_STAFF: "All-Staff Meetings",
    MeetingCategory.RM_COMMUNITY: "RM Community of Practice",
    MeetingCategory.SUBTEAM: "Subteam Meetings",
    # Other
    MeetingCategory.INTERNAL_MATS: "Internal MATS",
    MeetingCategory.EXTERNAL: "External Meetings",
    MeetingCategory.SOCIAL: "Social Events",
    MeetingCategory.WORKSHOP: "Workshops/Seminars",
    MeetingCategory.INTERVIEW: "Interviews",
    MeetingCategory.ADMIN: "Admin/Blocked Time",
    MeetingCategory.OTHER: "Other",
}


def generate_weekly_report(
    summary: WeeklySummary,
    events: Sequence[CategorizedEvent],
    calendar_name: str,
) -> str:
    """Generate a markdown report for a week."""
    lines = []

    # Header
    week_str = summary.week_start.strftime("%B %d") + " - " + summary.week_end.strftime("%B %d, %Y")
    lines.append(f"# Weekly Calendar Summary: {calendar_name}")
    lines.append(f"**Week of {week_str}**")
    lines.append("")

    # Overview
    lines.append("## Overview")
    lines.append(f"- **Total Meetings:** {summary.total_meetings}")
    lines.append(f"- **Total Hours:** {summary.total_hours:.1f}")
    lines.append(f"- **Busiest Day:** {summary.busiest_day}")
    lines.append(f"- **Peak Hour:** {summary.busiest_hour}:00")
    lines.append("")

    # Category breakdown
    lines.append("## Meetings by Category")
    lines.append("")
    lines.append("| Category | Meetings | Hours |")
    lines.append("|----------|----------|-------|")

    for category in MeetingCategory:
        count = summary.meetings_by_category.get(category, 0)
        hours = summary.hours_by_category.get(category, 0.0)
        if count > 0:
            label = CATEGORY_LABELS.get(category, category.value)
            lines.append(f"| {label} | {count} | {hours:.1f} |")

    lines.append("")

    # Daily breakdown
    lines.append("## Daily Breakdown")
    lines.append("")
    lines.append("| Day | Meetings | Hours |")
    lines.append("|-----|----------|-------|")

    for daily in summary.daily_summaries:
        day_name = daily.date.strftime("%A")
        lines.append(f"| {day_name} | {daily.total_meetings} | {daily.total_hours:.1f} |")

    lines.append("")

    # Time distribution chart (ASCII)
    lines.append("## Time Distribution")
    lines.append("Hours of meetings by time of day:")
    lines.append("```")

    time_dist = compute_meeting_time_distribution(events)
    max_hours = max(time_dist.values()) if time_dist else 1

    for hour in range(7, 20):  # 7 AM to 7 PM
        hours = time_dist.get(hour, 0)
        bar_len = int((hours / max_hours) * 30) if max_hours > 0 else 0
        bar = "█" * bar_len
        lines.append(f"{hour:02d}:00 | {bar} {hours:.1f}h")

    lines.append("```")
    lines.append("")

    # Meeting list by category
    lines.append("## Meeting Details by Category")

    week_events = [
        e for e in events
        if summary.week_start <= e.start < summary.week_end
    ]

    for category in MeetingCategory:
        cat_events = [e for e in week_events if e.category == category]
        if cat_events:
            label = CATEGORY_LABELS.get(category, category.value)
            lines.append(f"\n### {label} ({len(cat_events)} meetings)")
            lines.append("")

            for event in sorted(cat_events, key=lambda e: e.start):
                time_str = event.start.strftime("%a %m/%d %H:%M")
                duration = f"{event.duration_minutes}min"
                lines.append(f"- **{time_str}** ({duration}): {event.summary}")

    lines.append("")
    lines.append("---")
    lines.append(f"*Generated on {datetime.now().strftime('%Y-%m-%d %H:%M')}*")

    return "\n".join(lines)


def save_report(
    content: str,
    output_path: Path,
) -> None:
    """Save report to file."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(content)
