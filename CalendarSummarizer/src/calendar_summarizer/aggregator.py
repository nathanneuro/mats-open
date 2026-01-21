"""Aggregate multiple RM calendars into team-level summaries."""

from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime

from .models import CategorizedEvent, MeetingCategory, WeeklySummary


@dataclass
class RMWeeklySummary:
    """Summary for a single RM's week."""
    rm_email: str
    rm_name: str
    summary: WeeklySummary
    events: list[CategorizedEvent] = field(default_factory=list)


@dataclass
class TeamWeeklySummary:
    """Aggregated summary across multiple RMs."""
    week_start: datetime
    week_end: datetime
    rm_summaries: list[RMWeeklySummary] = field(default_factory=list)

    # Aggregated stats
    total_meetings: int = 0
    total_hours: float = 0.0
    meetings_by_category: dict[MeetingCategory, int] = field(default_factory=dict)
    hours_by_category: dict[MeetingCategory, float] = field(default_factory=dict)

    # Per-RM breakdown
    meetings_by_rm: dict[str, int] = field(default_factory=dict)
    hours_by_rm: dict[str, float] = field(default_factory=dict)

    # Averages
    avg_meetings_per_rm: float = 0.0
    avg_hours_per_rm: float = 0.0
    avg_own_fellow_hours: float = 0.0


def aggregate_rm_summaries(
    rm_summaries: list[RMWeeklySummary],
) -> TeamWeeklySummary:
    """
    Aggregate multiple RM weekly summaries into a team summary.

    Args:
        rm_summaries: List of individual RM summaries for the same week

    Returns:
        TeamWeeklySummary with aggregated statistics
    """
    if not rm_summaries:
        raise ValueError("No RM summaries to aggregate")

    # Use first summary's date range
    first = rm_summaries[0]
    team = TeamWeeklySummary(
        week_start=first.summary.week_start,
        week_end=first.summary.week_end,
        rm_summaries=rm_summaries,
    )

    # Aggregate stats
    meetings_by_category: dict[MeetingCategory, int] = defaultdict(int)
    hours_by_category: dict[MeetingCategory, float] = defaultdict(float)

    for rm in rm_summaries:
        team.total_meetings += rm.summary.total_meetings
        team.total_hours += rm.summary.total_hours
        team.meetings_by_rm[rm.rm_email] = rm.summary.total_meetings
        team.hours_by_rm[rm.rm_email] = rm.summary.total_hours

        for cat, count in rm.summary.meetings_by_category.items():
            meetings_by_category[cat] += count
        for cat, hours in rm.summary.hours_by_category.items():
            hours_by_category[cat] += hours

    team.meetings_by_category = dict(meetings_by_category)
    team.hours_by_category = dict(hours_by_category)

    # Compute averages
    num_rms = len(rm_summaries)
    team.avg_meetings_per_rm = team.total_meetings / num_rms
    team.avg_hours_per_rm = team.total_hours / num_rms

    # Average hours spent with own Fellows
    own_fellow_categories = [
        MeetingCategory.RM_OWN_FELLOW_1_1,
        MeetingCategory.RM_OWN_FELLOWS_GROUP,
    ]
    total_own_fellow_hours = sum(
        hours_by_category.get(cat, 0) for cat in own_fellow_categories
    )
    team.avg_own_fellow_hours = total_own_fellow_hours / num_rms

    return team


@dataclass
class ExecSummary:
    """High-level executive summary."""
    week_start: datetime
    week_end: datetime
    team_summary: TeamWeeklySummary

    # Key metrics
    total_rm_meeting_hours: float = 0.0
    total_fellow_facing_hours: float = 0.0
    fellow_facing_percentage: float = 0.0

    # Flags / highlights
    highlights: list[str] = field(default_factory=list)
    concerns: list[str] = field(default_factory=list)


def generate_exec_summary(
    team_summary: TeamWeeklySummary,
) -> ExecSummary:
    """
    Generate executive summary with key metrics.

    Args:
        team_summary: Aggregated team summary

    Returns:
        ExecSummary with key metrics
    """
    exec_summary = ExecSummary(
        week_start=team_summary.week_start,
        week_end=team_summary.week_end,
        team_summary=team_summary,
        total_rm_meeting_hours=team_summary.total_hours,
    )

    # Calculate Fellow-facing time
    fellow_categories = [
        MeetingCategory.RM_OWN_FELLOW_1_1,
        MeetingCategory.RM_OTHER_FELLOW_1_1,
        MeetingCategory.RM_OWN_FELLOWS_GROUP,
    ]
    exec_summary.total_fellow_facing_hours = sum(
        team_summary.hours_by_category.get(cat, 0)
        for cat in fellow_categories
    )

    if exec_summary.total_rm_meeting_hours > 0:
        exec_summary.fellow_facing_percentage = (
            exec_summary.total_fellow_facing_hours /
            exec_summary.total_rm_meeting_hours * 100
        )

    # Highlight: cross-RM collaboration (if any)
    cross_rm_hours = team_summary.hours_by_category.get(MeetingCategory.RM_OTHER_FELLOW_1_1, 0)
    if cross_rm_hours > 0:
        exec_summary.highlights.append(
            f"{cross_rm_hours:.1f}h of cross-RM Fellow mentoring this week"
        )

    return exec_summary
