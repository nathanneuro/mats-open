"""Generate markdown reports from calendar analysis."""

from datetime import datetime
from pathlib import Path
from typing import Sequence

from .aggregator import ExecSummary, TeamWeeklySummary
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


def generate_team_report(
    team_summary: TeamWeeklySummary,
) -> str:
    """Generate a team-level aggregate report."""
    lines = []

    week_str = (
        team_summary.week_start.strftime("%B %d") + " - " +
        team_summary.week_end.strftime("%B %d, %Y")
    )
    lines.append("# RM Team Weekly Summary")
    lines.append(f"**Week of {week_str}**")
    lines.append("")

    # Team overview
    lines.append("## Team Overview")
    num_rms = len(team_summary.rm_summaries)
    lines.append(f"- **Research Managers:** {num_rms}")
    lines.append(f"- **Total Team Meetings:** {team_summary.total_meetings}")
    lines.append(f"- **Total Team Hours:** {team_summary.total_hours:.1f}")
    lines.append(f"- **Avg Meetings per RM:** {team_summary.avg_meetings_per_rm:.1f}")
    lines.append(f"- **Avg Hours per RM:** {team_summary.avg_hours_per_rm:.1f}")
    lines.append(f"- **Avg Own-Fellow Hours per RM:** {team_summary.avg_own_fellow_hours:.1f}")
    lines.append("")

    # Per-RM breakdown
    lines.append("## Per-RM Summary")
    lines.append("")
    lines.append("| RM | Meetings | Hours | Own-Fellow Hours |")
    lines.append("|-----|----------|-------|------------------|")

    for rm in team_summary.rm_summaries:
        own_fellow_hours = sum(
            rm.summary.hours_by_category.get(cat, 0)
            for cat in [MeetingCategory.RM_OWN_FELLOW_1_1, MeetingCategory.RM_OWN_FELLOWS_GROUP]
        )
        lines.append(
            f"| {rm.rm_name} | {rm.summary.total_meetings} | "
            f"{rm.summary.total_hours:.1f} | {own_fellow_hours:.1f} |"
        )

    lines.append("")

    # Category breakdown (team-wide)
    lines.append("## Team Meetings by Category")
    lines.append("")
    lines.append("| Category | Meetings | Hours |")
    lines.append("|----------|----------|-------|")

    for category in MeetingCategory:
        count = team_summary.meetings_by_category.get(category, 0)
        hours = team_summary.hours_by_category.get(category, 0.0)
        if count > 0:
            label = CATEGORY_LABELS.get(category, category.value)
            lines.append(f"| {label} | {count} | {hours:.1f} |")

    lines.append("")
    lines.append("---")
    lines.append(f"*Generated on {datetime.now().strftime('%Y-%m-%d %H:%M')}*")

    return "\n".join(lines)


def generate_exec_report(
    exec_summary: ExecSummary,
) -> str:
    """Generate executive summary report."""
    lines = []

    week_str = (
        exec_summary.week_start.strftime("%B %d") + " - " +
        exec_summary.week_end.strftime("%B %d, %Y")
    )
    lines.append("# Executive Summary: RM Team")
    lines.append(f"**Week of {week_str}**")
    lines.append("")

    # Key metrics
    lines.append("## Key Metrics")
    team = exec_summary.team_summary
    num_rms = len(team.rm_summaries)
    lines.append(f"- **RMs Active:** {num_rms}")
    lines.append(f"- **Total Meeting Hours:** {exec_summary.total_rm_meeting_hours:.1f}")
    lines.append(f"- **Fellow-Facing Hours:** {exec_summary.total_fellow_facing_hours:.1f} ({exec_summary.fellow_facing_percentage:.0f}%)")
    lines.append(f"- **Avg Own-Fellow Hours/RM:** {team.avg_own_fellow_hours:.1f}")
    lines.append("")

    # Highlights
    if exec_summary.highlights:
        lines.append("## Highlights")
        for highlight in exec_summary.highlights:
            lines.append(f"- ✓ {highlight}")
        lines.append("")

    # Concerns
    if exec_summary.concerns:
        lines.append("## Areas of Attention")
        for concern in exec_summary.concerns:
            lines.append(f"- ⚠ {concern}")
        lines.append("")

    # Quick RM comparison
    lines.append("## RM Comparison")
    lines.append("")
    lines.append("| RM | Total Hours | Fellow Hours | % Fellow |")
    lines.append("|----|-------------|--------------|----------|")

    for rm in team.rm_summaries:
        fellow_hours = sum(
            rm.summary.hours_by_category.get(cat, 0)
            for cat in [
                MeetingCategory.RM_OWN_FELLOW_1_1,
                MeetingCategory.RM_OTHER_FELLOW_1_1,
                MeetingCategory.RM_OWN_FELLOWS_GROUP,
            ]
        )
        pct = (fellow_hours / rm.summary.total_hours * 100) if rm.summary.total_hours > 0 else 0
        lines.append(
            f"| {rm.rm_name} | {rm.summary.total_hours:.1f} | "
            f"{fellow_hours:.1f} | {pct:.0f}% |"
        )

    lines.append("")
    lines.append("---")
    lines.append(f"*Generated on {datetime.now().strftime('%Y-%m-%d %H:%M')}*")

    return "\n".join(lines)
