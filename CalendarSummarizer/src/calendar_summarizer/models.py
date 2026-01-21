"""Data models for calendar events and analysis."""

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum


class MeetingCategory(Enum):
    """Categories for meeting types, determined by LLM analysis."""
    RM_FELLOW_1_1 = "rm_fellow_1_1"  # Research Manager 1:1 with Fellow
    RM_TEAM_MEETING = "rm_team_meeting"  # RM meeting with multiple Fellows
    INTERNAL_MATS = "internal_mats"  # Internal MATS operations/staff meetings
    EXTERNAL = "external"  # External meetings (speakers, partners, etc.)
    SOCIAL = "social"  # Social events, coffee chats
    WORKSHOP = "workshop"  # Workshops, trainings, seminars
    INTERVIEW = "interview"  # Interview-related meetings
    ADMIN = "admin"  # Administrative tasks (blocked time, prep, etc.)
    OTHER = "other"  # Uncategorized


@dataclass
class CalendarEvent:
    """Represents a single calendar event."""
    id: str
    summary: str
    start: datetime
    end: datetime
    attendees: list[str] = field(default_factory=list)
    description: str | None = None
    location: str | None = None
    recurring: bool = False

    @property
    def duration_minutes(self) -> int:
        return int((self.end - self.start).total_seconds() / 60)

    @property
    def duration_hours(self) -> float:
        return self.duration_minutes / 60


@dataclass
class CategorizedEvent(CalendarEvent):
    """Calendar event with LLM-assigned category."""
    category: MeetingCategory = MeetingCategory.OTHER
    category_confidence: float = 0.0
    category_reasoning: str = ""


@dataclass
class DailySummary:
    """Summary statistics for a single day."""
    date: datetime
    total_meetings: int
    total_hours: float
    meetings_by_category: dict[MeetingCategory, int] = field(default_factory=dict)
    hours_by_category: dict[MeetingCategory, float] = field(default_factory=dict)


@dataclass
class WeeklySummary:
    """Summary statistics for a week."""
    week_start: datetime
    week_end: datetime
    daily_summaries: list[DailySummary] = field(default_factory=list)
    total_meetings: int = 0
    total_hours: float = 0.0
    meetings_by_category: dict[MeetingCategory, int] = field(default_factory=dict)
    hours_by_category: dict[MeetingCategory, float] = field(default_factory=dict)
    busiest_day: str = ""
    busiest_hour: int = 0  # 0-23
