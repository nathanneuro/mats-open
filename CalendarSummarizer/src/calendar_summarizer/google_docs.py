"""Google Docs API client for creating and updating reports."""

from pathlib import Path

from google.oauth2 import service_account
from googleapiclient.discovery import build, Resource

SCOPES = [
    "https://www.googleapis.com/auth/documents",
    "https://www.googleapis.com/auth/drive.file",
]


def get_docs_service(
    service_account_path: Path,
    delegated_user: str | None = None,
) -> Resource:
    """Create an authenticated Google Docs service."""
    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_path),
        scopes=SCOPES,
    )
    if delegated_user:
        credentials = credentials.with_subject(delegated_user)

    return build("docs", "v1", credentials=credentials)


def get_drive_service(
    service_account_path: Path,
    delegated_user: str | None = None,
) -> Resource:
    """Create an authenticated Google Drive service (for file operations)."""
    credentials = service_account.Credentials.from_service_account_file(
        str(service_account_path),
        scopes=SCOPES,
    )
    if delegated_user:
        credentials = credentials.with_subject(delegated_user)

    return build("drive", "v3", credentials=credentials)


def create_doc(
    docs_service: Resource,
    title: str,
) -> str:
    """
    Create a new Google Doc.

    Returns:
        Document ID
    """
    doc = docs_service.documents().create(body={"title": title}).execute()
    return doc["documentId"]


def get_or_create_doc(
    docs_service: Resource,
    drive_service: Resource,
    title: str,
    folder_id: str | None = None,
) -> str:
    """
    Get existing doc by title or create new one.

    Args:
        docs_service: Google Docs API service
        drive_service: Google Drive API service
        title: Document title to search for / create
        folder_id: Optional folder ID to search within / create in

    Returns:
        Document ID
    """
    # Search for existing doc
    query = f"name = '{title}' and mimeType = 'application/vnd.google-apps.document' and trashed = false"
    if folder_id:
        query += f" and '{folder_id}' in parents"

    results = drive_service.files().list(
        q=query,
        spaces="drive",
        fields="files(id, name)",
        pageSize=1,
    ).execute()

    files = results.get("files", [])
    if files:
        return files[0]["id"]

    # Create new doc
    doc_id = create_doc(docs_service, title)

    # Move to folder if specified
    if folder_id:
        drive_service.files().update(
            fileId=doc_id,
            addParents=folder_id,
            fields="id, parents",
        ).execute()

    return doc_id


def prepend_to_doc(
    docs_service: Resource,
    doc_id: str,
    content: str,
    add_separator: bool = True,
) -> None:
    """
    Prepend content to the beginning of a Google Doc.

    Args:
        docs_service: Google Docs API service
        doc_id: Document ID
        content: Markdown-ish content to prepend (will be converted to basic formatting)
        add_separator: Whether to add a horizontal line after the new content
    """
    # Get current doc to find insertion point
    doc = docs_service.documents().get(documentId=doc_id).execute()

    # Build requests to insert at beginning (index 1, after doc start)
    requests = []

    # Add separator if doc has existing content
    body_content = doc.get("body", {}).get("content", [])
    has_content = len(body_content) > 1  # More than just the empty paragraph

    if has_content and add_separator:
        content = content + "\n\n---\n\n"

    # Convert markdown-ish content to Google Docs requests
    requests = _markdown_to_docs_requests(content)

    if requests:
        docs_service.documents().batchUpdate(
            documentId=doc_id,
            body={"requests": requests},
        ).execute()


def _markdown_to_docs_requests(content: str) -> list[dict]:
    """
    Convert markdown-ish content to Google Docs API requests.

    Supports basic formatting:
    - # Heading 1, ## Heading 2, ### Heading 3
    - **bold**, *italic* (limited support)
    - Horizontal rules (---)
    - Tables (converted to plain text with spacing)
    """
    requests = []
    lines = content.split("\n")

    # We insert at position 1 (start of document body)
    # But we need to insert in reverse order since each insert shifts content
    insert_index = 1

    # Process content and build a single text insertion first
    # Then apply formatting
    full_text = ""
    formatting_ranges = []  # (start, end, style)

    current_pos = 0

    for line in lines:
        # Track position for formatting
        line_start = current_pos

        # Handle headings
        if line.startswith("### "):
            text = line[4:] + "\n"
            full_text += text
            formatting_ranges.append((line_start, line_start + len(text) - 1, "HEADING_3"))
        elif line.startswith("## "):
            text = line[3:] + "\n"
            full_text += text
            formatting_ranges.append((line_start, line_start + len(text) - 1, "HEADING_2"))
        elif line.startswith("# "):
            text = line[2:] + "\n"
            full_text += text
            formatting_ranges.append((line_start, line_start + len(text) - 1, "HEADING_1"))
        elif line.strip() == "---":
            # Horizontal rule - just add some spacing
            full_text += "─" * 50 + "\n"
        else:
            full_text += line + "\n"

        current_pos = len(full_text)

    if not full_text.strip():
        return []

    # Insert all text at once
    requests.append({
        "insertText": {
            "location": {"index": insert_index},
            "text": full_text,
        }
    })

    # Apply heading formatting (in reverse order to preserve indices)
    for start, end, style in reversed(formatting_ranges):
        if style.startswith("HEADING_"):
            requests.append({
                "updateParagraphStyle": {
                    "range": {
                        "startIndex": insert_index + start,
                        "endIndex": insert_index + end,
                    },
                    "paragraphStyle": {
                        "namedStyleType": style,
                    },
                    "fields": "namedStyleType",
                }
            })

    return requests


def share_doc(
    drive_service: Resource,
    doc_id: str,
    email: str,
    role: str = "reader",
) -> None:
    """
    Share a document with a user.

    Args:
        drive_service: Google Drive API service
        doc_id: Document ID
        email: Email to share with
        role: Permission role (reader, writer, commenter)
    """
    drive_service.permissions().create(
        fileId=doc_id,
        body={
            "type": "user",
            "role": role,
            "emailAddress": email,
        },
        sendNotificationEmail=False,
    ).execute()
