"""Google Calendar authentication using service account."""

from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build, Resource

SCOPES = ["https://www.googleapis.com/auth/calendar.readonly"]


def get_calendar_service(
    service_account_path: Path,
    delegated_user: str | None = None,
) -> Resource:
    """
    Create an authenticated Google Calendar service.

    Args:
        service_account_path: Path to service account JSON key file
        delegated_user: Email of user to impersonate (for domain-wide delegation)

    Returns:
        Google Calendar API service resource
    """
    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_path),
        scopes=SCOPES,
    )

    if delegated_user:
        credentials = credentials.with_subject(delegated_user)

    service = build("calendar", "v3", credentials=credentials)
    return service
