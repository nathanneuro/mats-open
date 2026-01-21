"""Batch processing CLI for running reports across all RMs."""

import argparse
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn, BarColumn, TaskProgressColumn

from .aggregator import RMWeeklySummary, aggregate_rm_summaries, generate_exec_summary
from .analyzer import analyze_weekly
from .auth import get_calendar_service
from .categorizer import categorize_events
from .config import MATSConfig
from .fetcher import fetch_events
from .google_docs import get_docs_service, get_drive_service, get_or_create_doc, prepend_to_doc, share_doc
from .reporter import generate_weekly_report, generate_team_report, generate_exec_report

console = Console()


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Run weekly calendar analysis for all MATS Research Managers",
    )
    parser.add_argument(
        "--service-account",
        type=Path,
        required=True,
        help="Path to Google service account JSON key file",
    )
    parser.add_argument(
        "--config",
        type=Path,
        required=True,
        help="Path to MATS config JSON",
    )
    parser.add_argument(
        "--weeks",
        type=int,
        default=1,
        help="Number of weeks to analyze (default: 1, current week)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Generate reports but don't write to Google Docs",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Save local copies of reports to this directory",
    )

    args = parser.parse_args()

    # Validate inputs
    if not args.service_account.exists():
        console.print(f"[red]Error:[/red] Service account file not found: {args.service_account}")
        return 1

    if not args.config.exists():
        console.print(f"[red]Error:[/red] Config file not found: {args.config}")
        return 1

    try:
        config = MATSConfig.from_json(args.config)
    except Exception as e:
        console.print(f"[red]Error loading config:[/red] {e}")
        return 1

    if not config.all_rms:
        console.print("[red]Error:[/red] No RMs defined in config")
        return 1

    # Calculate date range
    now = datetime.now(timezone.utc)
    days_since_monday = now.weekday()
    week_start = (now - timedelta(days=days_since_monday)).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    start_date = week_start - timedelta(weeks=args.weeks - 1)
    end_date = week_start + timedelta(days=7)

    console.print("[bold]MATS Calendar Summarizer - Batch Run[/bold]")
    console.print(f"Analyzing {len(config.all_rms)} RMs from {start_date.date()} to {end_date.date()}")
    console.print("")

    # Initialize services
    docs_service = None
    drive_service = None
    try:
        if not args.dry_run:
            docs_service = get_docs_service(args.service_account)
            drive_service = get_drive_service(args.service_account)
    except Exception as e:
        console.print(f"[red]Authentication failed:[/red] {e}")
        return 1

    # Process each RM
    rm_summaries: dict[str, RMWeeklySummary] = {}  # email -> summary

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:

        rm_task = progress.add_task("Processing RMs...", total=len(config.all_rms))

        for rm_email in config.all_rms:
            rm_name = config.get_rm_name(rm_email)
            progress.update(rm_task, description=f"Processing {rm_name}...")

            try:
                # Fetch events (impersonating the RM)
                cal_service = get_calendar_service(
                    args.service_account,
                    delegated_user=rm_email,
                )
                events = list(fetch_events(cal_service, rm_email, start_date, end_date))

                if not events:
                    console.print(f"[yellow]  {rm_name}: No events found[/yellow]")
                    progress.advance(rm_task)
                    continue

                # Categorize
                categorized = categorize_events(events, config, rm_email)

                # Analyze
                weekly_summary = analyze_weekly(categorized, week_start)

                rm_summary = RMWeeklySummary(
                    rm_email=rm_email,
                    rm_name=rm_name,
                    summary=weekly_summary,
                    events=categorized,
                )
                rm_summaries[rm_email] = rm_summary

                # Generate individual report
                report = generate_weekly_report(weekly_summary, categorized, rm_name)

                # Save to Google Docs (if not dry run)
                if not args.dry_run and rm_email in config.rm_report_destinations:
                    dest = config.rm_report_destinations[rm_email]
                    doc_title = dest.doc_title or f"Calendar Summary - {rm_name}"
                    doc_id = get_or_create_doc(docs_service, drive_service, doc_title, dest.folder_id)
                    prepend_to_doc(docs_service, doc_id, report)

                    # Share with appropriate viewers
                    viewers = config.get_report_viewers(rm_email)
                    for viewer in viewers:
                        if viewer != rm_email:  # Don't re-share with owner
                            try:
                                share_doc(drive_service, doc_id, viewer, role="reader")
                            except Exception:
                                pass  # May already be shared

                    console.print(f"[green]  {rm_name}: Updated Google Doc[/green]")

                # Save local copy
                if args.output_dir:
                    output_path = args.output_dir / f"{rm_email.split('@')[0]}_report.md"
                    output_path.parent.mkdir(parents=True, exist_ok=True)
                    output_path.write_text(report)

            except Exception as e:
                console.print(f"[red]  {rm_name}: Error - {e}[/red]")

            progress.advance(rm_task)

    if not rm_summaries:
        console.print("[yellow]No data collected for any RM[/yellow]")
        return 1

    # Generate Senior RM aggregate reports
    console.print("\n[bold]Generating Senior RM aggregate reports...[/bold]")
    for senior_rm_email in config.senior_rm_direct_reports:
        senior_rm_name = config.get_rm_name(senior_rm_email)
        direct_reports = config.get_direct_reports(senior_rm_email)

        # Get summaries for direct reports
        report_summaries = [
            rm_summaries[rm] for rm in direct_reports
            if rm in rm_summaries
        ]

        if not report_summaries:
            console.print(f"[yellow]  {senior_rm_name}: No data for direct reports[/yellow]")
            continue

        # Generate aggregate
        team_summary = aggregate_rm_summaries(report_summaries)
        team_report = generate_team_report(team_summary)

        if not args.dry_run and senior_rm_email in config.senior_rm_aggregate_destinations:
            dest = config.senior_rm_aggregate_destinations[senior_rm_email]
            doc_title = dest.doc_title or f"Team Summary - {senior_rm_name}"
            doc_id = get_or_create_doc(docs_service, drive_service, doc_title, dest.folder_id)
            prepend_to_doc(docs_service, doc_id, team_report)

            # Share with the Senior RM and exec overview recipients
            share_doc(drive_service, doc_id, senior_rm_email, role="reader")
            for viewer in config.executive_overview_recipients:
                try:
                    share_doc(drive_service, doc_id, viewer, role="reader")
                except Exception:
                    pass

            console.print(f"[green]  {senior_rm_name}: Team aggregate updated[/green]")

        if args.output_dir:
            output_path = args.output_dir / f"{senior_rm_email.split('@')[0]}_team_report.md"
            output_path.write_text(team_report)

    # Generate executive overview (full team aggregate)
    console.print("\n[bold]Generating executive overview...[/bold]")
    all_summaries = list(rm_summaries.values())
    full_team_summary = aggregate_rm_summaries(all_summaries)
    exec_summary = generate_exec_summary(full_team_summary)
    exec_report = generate_exec_report(exec_summary)

    if not args.dry_run and config.executive_overview_destination:
        dest = config.executive_overview_destination
        doc_title = dest.doc_title or "RM Team Executive Overview"
        doc_id = get_or_create_doc(docs_service, drive_service, doc_title, dest.folder_id)
        prepend_to_doc(docs_service, doc_id, exec_report)

        # Share with all exec overview recipients
        for viewer in config.executive_overview_recipients:
            try:
                share_doc(drive_service, doc_id, viewer, role="reader")
            except Exception:
                pass

        console.print("[green]Executive overview updated in Google Docs[/green]")

    if args.output_dir:
        output_path = args.output_dir / "executive_overview.md"
        output_path.write_text(exec_report)

    # Print summary
    console.print("\n" + "=" * 60)
    console.print("[bold]Run Complete[/bold]")
    console.print(f"- RMs processed: {len(rm_summaries)}/{len(config.all_rms)}")
    console.print(f"- Total meetings: {full_team_summary.total_meetings}")
    console.print(f"- Total hours: {full_team_summary.total_hours:.1f}")

    if exec_summary.highlights:
        console.print("\n[green]Highlights:[/green]")
        for h in exec_summary.highlights:
            console.print(f"  ✓ {h}")

    if exec_summary.concerns:
        console.print("\n[yellow]Attention needed:[/yellow]")
        for c in exec_summary.concerns:
            console.print(f"  ⚠ {c}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
