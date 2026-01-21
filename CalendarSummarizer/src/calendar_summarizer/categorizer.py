"""LLM-based meeting categorization using Claude Haiku."""

import json
from typing import Sequence

import anthropic

from .models import CalendarEvent, CategorizedEvent, MeetingCategory

CATEGORIZATION_PROMPT = """You are categorizing calendar meetings for MATS (ML Alignment Theory Scholars), an AI safety research program.

Given a meeting's title and attendee count, categorize it into exactly ONE of these categories:

- rm_fellow_1_1: Research Manager 1:1 meeting with a Fellow (their assigned mentee). Look for: "1:1", "check-in", names suggesting a mentor-mentee pair, "RM" + person name.
- rm_team_meeting: Research Manager meeting with multiple Fellows at once. Look for: "team meeting", "group sync", multiple fellows.
- internal_mats: Internal MATS operations/staff meetings. Look for: "MATS", "staff", "ops", "planning", "cohort", internal coordination.
- external: External meetings (speakers, partners, funders, collaborators outside MATS). Look for: organization names, "external", "call with [org]".
- social: Social events, coffee chats, casual meetings. Look for: "coffee", "lunch", "social", "happy hour", casual tone.
- workshop: Workshops, trainings, seminars, reading groups. Look for: "workshop", "seminar", "reading group", "training", educational.
- interview: Interview-related meetings. Look for: "interview", "candidate", screening terms.
- admin: Administrative tasks, blocked time, prep time. Look for: "prep", "blocked", "admin", "focus time", "no meetings".
- other: Anything that doesn't fit above categories.

Respond with JSON only:
{
  "category": "<category_name>",
  "confidence": <0.0-1.0>,
  "reasoning": "<brief explanation>"
}"""


def categorize_events(
    events: Sequence[CalendarEvent],
    anthropic_client: anthropic.Anthropic | None = None,
) -> list[CategorizedEvent]:
    """
    Categorize a batch of calendar events using Claude Haiku.

    Args:
        events: Events to categorize
        anthropic_client: Anthropic client (creates new one if not provided)

    Returns:
        List of CategorizedEvent with categories assigned
    """
    if not events:
        return []

    client = anthropic_client or anthropic.Anthropic()
    categorized = []

    # Batch events for efficiency (process in groups)
    batch_size = 20
    for i in range(0, len(events), batch_size):
        batch = events[i:i + batch_size]
        batch_results = _categorize_batch(client, batch)
        categorized.extend(batch_results)

    return categorized


def _categorize_batch(
    client: anthropic.Anthropic,
    events: Sequence[CalendarEvent],
) -> list[CategorizedEvent]:
    """Categorize a batch of events in a single API call."""
    if not events:
        return []

    # Build batch request
    events_text = "\n".join(
        f"{i+1}. \"{event.summary}\" (attendees: {len(event.attendees)})"
        for i, event in enumerate(events)
    )

    user_message = f"""Categorize each of these {len(events)} meetings.
Respond with a JSON array of objects, one per meeting, in order:

{events_text}

Response format:
[
  {{"category": "...", "confidence": 0.X, "reasoning": "..."}},
  ...
]"""

    response = client.messages.create(
        model="claude-haiku-4-20250414",
        max_tokens=2048,
        system=CATEGORIZATION_PROMPT,
        messages=[{"role": "user", "content": user_message}],
    )

    # Parse response
    response_text = response.content[0].text
    try:
        # Handle potential markdown code blocks
        if "```" in response_text:
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
        results = json.loads(response_text.strip())
    except json.JSONDecodeError:
        # Fallback: return all as OTHER
        results = [{"category": "other", "confidence": 0.0, "reasoning": "Parse error"}] * len(events)

    categorized = []
    for event, result in zip(events, results):
        category_str = result.get("category", "other").lower()
        try:
            category = MeetingCategory(category_str)
        except ValueError:
            category = MeetingCategory.OTHER

        categorized.append(CategorizedEvent(
            id=event.id,
            summary=event.summary,
            start=event.start,
            end=event.end,
            attendees=event.attendees,
            description=event.description,
            location=event.location,
            recurring=event.recurring,
            category=category,
            category_confidence=result.get("confidence", 0.0),
            category_reasoning=result.get("reasoning", ""),
        ))

    return categorized
