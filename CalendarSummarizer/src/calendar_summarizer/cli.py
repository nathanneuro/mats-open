"""CLI entry point for Calendar Summarizer."""

import argparse
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

from .auth import get_calendar_service
from .fetcher import fetch_events
from .categorizer import categorize_events
from .analyzer import analyze_weekly
from .reporter import generate_weekly_report, save_report

console = Console()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Summarize Google Calendar meetings for MATS Research Managers",
    )
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Path to Google service account JSON key file",
    )
    parser.add_argument(
        "--calendar",
        type=str,
        required=True,
        help="Calendar ID (email address) to analyze",
    )
    parser.add_argument(
        "--delegated-user",
        type=str,
        help="Email of user to impersonate (for domain-wide delegation)",
    )
    parser.add_argument(
        "--weeks",
        type=int,
        default=1,
        help="Number of weeks to analyze (default: 1, current week)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
        help="Output file path (default: outputs/report_YYYY-MM-DD.md)",
    )
    parser.add_argument(
        "--calendar-name",
        type=str,
        default=None,
        help="Display name for the calendar (default: calendar ID)",
    )

    args = parser.parse_args()

    if not args.service_account.exists():
        console.print(f"[red]Error:[/red] Service account file not found: {args.service_account}")
        return 1

    calendar_name = args.calendar_name or args.calendar.split("@")[0]

    # Calculate date range
    now = datetime.now(timezone.utc)
    # Start from beginning of current week
    days_since_monday = now.weekday()
    week_start = (now - timedelta(days=days_since_monday)).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    # Go back additional weeks if requested
    start_date = week_start - timedelta(weeks=args.weeks - 1)
    end_date = week_start + timedelta(days=7)

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
    ) as progress:
        # Authenticate
        progress.add_task("Authenticating with Google Calendar...", total=None)
        try:
            service = get_calendar_service(
                args.service_account,
                delegated_user=args.delegated_user,
            )
        except Exception as e:
            console.print(f"[red]Authentication failed:[/red] {e}")
            return 1

        # Fetch events
        task = progress.add_task(f"Fetching events from {start_date.date()} to {end_date.date()}...", total=None)
        try:
            events = list(fetch_events(service, args.calendar, start_date, end_date))
        except Exception as e:
            console.print(f"[red]Failed to fetch events:[/red] {e}")
            return 1
        progress.remove_task(task)

        console.print(f"[green]Found {len(events)} events[/green]")

        if not events:
            console.print("[yellow]No events found in the specified date range.[/yellow]")
            return 0

        # Categorize events
        task = progress.add_task("Categorizing meetings with AI...", total=None)
        try:
            categorized = categorize_events(events)
        except Exception as e:
            console.print(f"[red]Categorization failed:[/red] {e}")
            return 1
        progress.remove_task(task)

        console.print(f"[green]Categorized {len(categorized)} events[/green]")

    # Generate reports for each week
    all_reports = []
    current_week = start_date

    while current_week < end_date:
        summary = analyze_weekly(categorized, current_week)
        report = generate_weekly_report(summary, categorized, calendar_name)
        all_reports.append(report)
        current_week += timedelta(weeks=1)

    # Combine reports
    full_report = "\n\n---\n\n".join(all_reports)

    # Determine output path
    output_path = args.output
    if output_path is None:
        output_dir = Path(__file__).parent.parent.parent.parent / "outputs"
        output_path = output_dir / f"report_{datetime.now().strftime('%Y-%m-%d')}.md"

    save_report(full_report, output_path)
    console.print(f"[green]Report saved to:[/green] {output_path}")

    # Also print summary to console
    console.print("\n" + "=" * 60)
    for summary_line in full_report.split("\n")[:20]:
        console.print(summary_line)
    console.print("...")
    console.print(f"\n[dim]Full report: {output_path}[/dim]")

    return 0


if __name__ == "__main__":
    sys.exit(main())
