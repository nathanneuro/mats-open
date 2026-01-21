"""LLM-based meeting categorization using Claude Haiku + assignment data."""

import json
from typing import Sequence

import anthropic

from .config import MATSConfig
from .models import CalendarEvent, CategorizedEvent, MeetingCategory

CATEGORIZATION_PROMPT = """You are categorizing calendar meetings for MATS (ML Alignment Theory Scholars), an AI safety research program.

You will receive meeting info including title, attendee analysis (which attendees are Fellows, staff, external, etc.), and detected patterns.

Based on this info, categorize into exactly ONE category:

- rm_own_fellow_1_1: RM 1:1 with one of their assigned Fellows
- rm_other_fellow_1_1: RM 1:1 with a Fellow assigned to a different RM
- rm_own_fellows_group: RM meeting with multiple of their assigned Fellows
- all_staff: All-staff meeting (most/all staff attending)
- rm_community: RM community of practice meeting (all-RM meeting)
- subteam: Subteam meeting
- internal_mats: Other internal MATS operations/coordination
- external: External meetings (speakers, partners, funders, outside collaborators)
- social: Social events, coffee chats, casual meetings
- workshop: Workshops, trainings, seminars, reading groups
- interview: Interview-related meetings
- admin: Administrative tasks, blocked time, prep time, focus time
- other: Anything that doesn't fit above

Use the attendee analysis provided - it tells you exactly who is a Fellow, whose Fellow they are, etc.

Respond with JSON only:
{
  "category": "<category_name>",
  "confidence": <0.0-1.0>,
  "reasoning": "<brief explanation>"
}"""


def categorize_events(
    events: Sequence[CalendarEvent],
    config: MATSConfig,
    calendar_owner: str,
    anthropic_client: anthropic.Anthropic | None = None,
) -> list[CategorizedEvent]:
    """
    Categorize calendar events using assignment data + Claude Haiku.

    Args:
        events: Events to categorize
        config: MATS organizational config with RM-Fellow assignments
        calendar_owner: Email of the calendar owner (the RM being analyzed)
        anthropic_client: Anthropic client (creates new one if not provided)

    Returns:
        List of CategorizedEvent with categories assigned
    """
    if not events:
        return []

    client = anthropic_client or anthropic.Anthropic()
    categorized = []

    # Batch events for efficiency
    batch_size = 20
    for i in range(0, len(events), batch_size):
        batch = events[i:i + batch_size]
        batch_results = _categorize_batch(client, batch, config, calendar_owner)
        categorized.extend(batch_results)

    return categorized


def _analyze_attendees(
    event: CalendarEvent,
    config: MATSConfig,
    calendar_owner: str,
) -> dict:
    """
    Analyze event attendees against MATS organizational structure.

    Returns dict with attendee breakdown for LLM context.
    """
    attendees = [a.lower() for a in event.attendees]
    owner_lower = calendar_owner.lower()

    # Get owner's assigned Fellows
    own_fellows = [f.lower() for f in config.rm_fellow_assignments.get(calendar_owner, [])]
    all_fellows = config.get_all_fellows()
    all_rms = [r.lower() for r in config.all_rms]
    all_staff = [s.lower() for s in config.all_staff]

    # Categorize each attendee
    own_fellow_attendees = []
    other_fellow_attendees = []
    rm_attendees = []
    staff_attendees = []
    external_attendees = []

    for attendee in attendees:
        if attendee == owner_lower:
            continue  # Skip the calendar owner

        if attendee in own_fellows:
            own_fellow_attendees.append(attendee)
        elif attendee in all_fellows:
            other_fellow_attendees.append(attendee)
        elif attendee in all_rms:
            rm_attendees.append(attendee)
        elif attendee in all_staff:
            staff_attendees.append(attendee)
        else:
            external_attendees.append(attendee)

    # Detect patterns
    patterns = []

    # Check for all-staff meeting
    if len(set(attendees) & set(all_staff)) >= len(all_staff) * 0.8:
        patterns.append("all_staff_meeting")

    # Check for RM community meeting
    if len(set(attendees) & set(all_rms)) >= len(all_rms) * 0.8:
        patterns.append("rm_community_meeting")

    # Check for subteam meetings
    for subteam_name, members in config.subteams.items():
        members_lower = [m.lower() for m in members]
        if len(set(attendees) & set(members_lower)) >= len(members_lower) * 0.7:
            patterns.append(f"subteam:{subteam_name}")

    return {
        "own_fellows": own_fellow_attendees,
        "other_fellows": other_fellow_attendees,
        "other_rms": rm_attendees,
        "staff": staff_attendees,
        "external": external_attendees,
        "patterns": patterns,
        "total_attendees": len(attendees),
    }


def _categorize_batch(
    client: anthropic.Anthropic,
    events: Sequence[CalendarEvent],
    config: MATSConfig,
    calendar_owner: str,
) -> list[CategorizedEvent]:
    """Categorize a batch of events in a single API call."""
    if not events:
        return []

    # Build batch request with attendee analysis
    event_descriptions = []
    analyses = []

    for i, event in enumerate(events):
        analysis = _analyze_attendees(event, config, calendar_owner)
        analyses.append(analysis)

        desc_parts = [
            f'{i+1}. "{event.summary}"',
            f"   Attendees: {analysis['total_attendees']} total",
        ]

        if analysis["own_fellows"]:
            desc_parts.append(f"   - Own Fellows: {len(analysis['own_fellows'])}")
        if analysis["other_fellows"]:
            desc_parts.append(f"   - Other RMs' Fellows: {len(analysis['other_fellows'])}")
        if analysis["other_rms"]:
            desc_parts.append(f"   - Other RMs: {len(analysis['other_rms'])}")
        if analysis["staff"]:
            desc_parts.append(f"   - Staff: {len(analysis['staff'])}")
        if analysis["external"]:
            desc_parts.append(f"   - External: {len(analysis['external'])}")
        if analysis["patterns"]:
            desc_parts.append(f"   - Detected patterns: {', '.join(analysis['patterns'])}")

        event_descriptions.append("\n".join(desc_parts))

    events_text = "\n\n".join(event_descriptions)

    user_message = f"""Categorize each of these {len(events)} meetings for an RM's calendar.

{events_text}

Respond with a JSON array of objects, one per meeting, in order:
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
        if "```" in response_text:
            response_text = response_text.split("```")[1]
            if response_text.startswith("json"):
                response_text = response_text[4:]
        results = json.loads(response_text.strip())
    except json.JSONDecodeError:
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
