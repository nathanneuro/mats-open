"""Fetch calendar events from Google Calendar API."""

from datetime import datetime, timezone
from typing import Iterator

from googleapiclient.discovery import Resource

from .models import CalendarEvent


def fetch_events(
    service: Resource,
    calendar_id: str,
    start_date: datetime,
    end_date: datetime,
    max_results: int = 2500,
) -> Iterator[CalendarEvent]:
    """
    Fetch calendar events within a date range.

    Args:
        service: Authenticated Google Calendar service
        calendar_id: Calendar ID (email or 'primary')
        start_date: Start of date range (inclusive)
        end_date: End of date range (exclusive)
        max_results: Maximum events to fetch

    Yields:
        CalendarEvent objects
    """
    time_min = start_date.isoformat()
    time_max = end_date.isoformat()

    if start_date.tzinfo is None:
        time_min = start_date.replace(tzinfo=timezone.utc).isoformat()
    if end_date.tzinfo is None:
        time_max = end_date.replace(tzinfo=timezone.utc).isoformat()

    page_token = None

    while True:
        events_result = service.events().list(
            calendarId=calendar_id,
            timeMin=time_min,
            timeMax=time_max,
            maxResults=min(max_results, 250),
            singleEvents=True,  # Expand recurring events
            orderBy="startTime",
            pageToken=page_token,
        ).execute()

        events = events_result.get("items", [])

        for event in events:
            parsed = _parse_event(event)
            if parsed:
                yield parsed

        page_token = events_result.get("nextPageToken")
        if not page_token:
            break


def _parse_event(event: dict) -> CalendarEvent | None:
    """Parse a raw Google Calendar event into a CalendarEvent."""
    # Skip all-day events (they have 'date' instead of 'dateTime')
    start_raw = event.get("start", {})
    end_raw = event.get("end", {})

    if "dateTime" not in start_raw or "dateTime" not in end_raw:
        return None

    start = datetime.fromisoformat(start_raw["dateTime"])
    end = datetime.fromisoformat(end_raw["dateTime"])

    attendees = []
    for attendee in event.get("attendees", []):
        email = attendee.get("email", "")
        if email and not attendee.get("resource", False):
            attendees.append(email)

    return CalendarEvent(
        id=event["id"],
        summary=event.get("summary", "(No title)"),
        start=start,
        end=end,
        attendees=attendees,
        description=event.get("description"),
        location=event.get("location"),
        recurring="recurringEventId" in event,
    )
