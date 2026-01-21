"""Time analysis for calendar events."""

from collections import defaultdict
from datetime import datetime, timedelta
from typing import Sequence

from .models import CategorizedEvent, DailySummary, MeetingCategory, WeeklySummary


def analyze_daily(
    events: Sequence[CategorizedEvent],
    date: datetime,
) -> DailySummary:
    """Generate summary statistics for a single day."""
    day_start = date.replace(hour=0, minute=0, second=0, microsecond=0)
    day_end = day_start + timedelta(days=1)

    day_events = [e for e in events if day_start <= e.start < day_end]

    meetings_by_category: dict[MeetingCategory, int] = defaultdict(int)
    hours_by_category: dict[MeetingCategory, float] = defaultdict(float)

    for event in day_events:
        meetings_by_category[event.category] += 1
        hours_by_category[event.category] += event.duration_hours

    return DailySummary(
        date=day_start,
        total_meetings=len(day_events),
        total_hours=sum(e.duration_hours for e in day_events),
        meetings_by_category=dict(meetings_by_category),
        hours_by_category=dict(hours_by_category),
    )


def analyze_weekly(
    events: Sequence[CategorizedEvent],
    week_start: datetime,
) -> WeeklySummary:
    """Generate summary statistics for a week (Mon-Sun)."""
    # Normalize to start of week (Monday)
    days_since_monday = week_start.weekday()
    week_start = week_start.replace(hour=0, minute=0, second=0, microsecond=0)
    week_start = week_start - timedelta(days=days_since_monday)
    week_end = week_start + timedelta(days=7)

    week_events = [e for e in events if week_start <= e.start < week_end]

    # Generate daily summaries
    daily_summaries = []
    for i in range(7):
        day = week_start + timedelta(days=i)
        daily_summaries.append(analyze_daily(week_events, day))

    # Aggregate category stats
    meetings_by_category: dict[MeetingCategory, int] = defaultdict(int)
    hours_by_category: dict[MeetingCategory, float] = defaultdict(float)

    for event in week_events:
        meetings_by_category[event.category] += 1
        hours_by_category[event.category] += event.duration_hours

    # Find busiest day
    busiest_day_summary = max(daily_summaries, key=lambda d: d.total_hours, default=None)
    busiest_day = busiest_day_summary.date.strftime("%A") if busiest_day_summary else ""

    # Find busiest hour (most meetings starting in that hour)
    hour_counts: dict[int, int] = defaultdict(int)
    for event in week_events:
        hour_counts[event.start.hour] += 1
    busiest_hour = max(hour_counts, key=hour_counts.get, default=9) if hour_counts else 9

    return WeeklySummary(
        week_start=week_start,
        week_end=week_end,
        daily_summaries=daily_summaries,
        total_meetings=len(week_events),
        total_hours=sum(e.duration_hours for e in week_events),
        meetings_by_category=dict(meetings_by_category),
        hours_by_category=dict(hours_by_category),
        busiest_day=busiest_day,
        busiest_hour=busiest_hour,
    )


def compute_meeting_time_distribution(
    events: Sequence[CategorizedEvent],
) -> dict[int, float]:
    """
    Compute hours spent in meetings by hour of day.

    Returns dict mapping hour (0-23) to total hours of meetings
    starting in that hour.
    """
    distribution: dict[int, float] = defaultdict(float)
    for event in events:
        distribution[event.start.hour] += event.duration_hours
    return dict(distribution)


def compute_day_of_week_distribution(
    events: Sequence[CategorizedEvent],
) -> dict[str, float]:
    """
    Compute hours spent in meetings by day of week.

    Returns dict mapping day name to total hours.
    """
    days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    distribution: dict[str, float] = {day: 0.0 for day in days}

    for event in events:
        day_name = event.start.strftime("%A")
        distribution[day_name] += event.duration_hours

    return distribution
