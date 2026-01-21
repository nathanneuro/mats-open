"""Configuration for RM-Fellow assignments and organizational structure."""

from dataclasses import dataclass, field
from pathlib import Path
import json


@dataclass
class ReportDestination:
    """Configuration for a report destination."""
    doc_id: str | None = None  # Existing Google Doc ID (if known)
    doc_title: str = ""  # Title for creating/finding doc
    folder_id: str | None = None  # Google Drive folder ID
    viewers: list[str] = field(default_factory=list)  # Emails to share with


@dataclass
class MATSConfig:
    """
    Configuration for MATS organizational structure.

    Hierarchy:
    - RMs: Get their own personal report
    - Senior RMs: Get their report + aggregate of RMs they manage + access to direct reports
    - Head of RM Team: Gets overall aggregate + access to all personal reports
    - Executives: Gets overall aggregate + access to all personal reports
    """
    # Map of RM email -> list of assigned Fellow emails
    rm_fellow_assignments: dict[str, list[str]] = field(default_factory=dict)

    # All staff emails (for identifying "all staff" meetings)
    all_staff: list[str] = field(default_factory=list)

    # All RM emails (for identifying "RM community of practice" meetings)
    all_rms: list[str] = field(default_factory=list)

    # Subteam definitions: name -> list of member emails
    subteams: dict[str, list[str]] = field(default_factory=dict)

    # Display names for RMs (email -> name)
    rm_names: dict[str, str] = field(default_factory=dict)

    # Management hierarchy
    # Senior RM email -> list of RM emails they manage (Senior RMs are also in all_rms)
    senior_rm_direct_reports: dict[str, list[str]] = field(default_factory=dict)
    # Executive overview recipients (Head of RM team + Executives) - get full aggregate + all access
    executive_overview_recipients: list[str] = field(default_factory=list)

    # Report destinations
    # Individual RM reports: rm_email -> destination
    rm_report_destinations: dict[str, ReportDestination] = field(default_factory=dict)
    # Senior RM aggregate reports: senior_rm_email -> destination for their team aggregate
    senior_rm_aggregate_destinations: dict[str, ReportDestination] = field(default_factory=dict)
    # Executive overview report (full team aggregate for RM lead + execs)
    executive_overview_destination: ReportDestination | None = None

    def get_rm_for_fellow(self, fellow_email: str) -> str | None:
        """Get the RM assigned to a Fellow, or None if not found."""
        for rm, fellows in self.rm_fellow_assignments.items():
            if fellow_email.lower() in [f.lower() for f in fellows]:
                return rm
        return None

    def is_assigned_fellow(self, rm_email: str, fellow_email: str) -> bool:
        """Check if a Fellow is assigned to a specific RM."""
        assigned = self.rm_fellow_assignments.get(rm_email, [])
        return fellow_email.lower() in [f.lower() for f in assigned]

    def get_all_fellows(self) -> set[str]:
        """Get set of all Fellow emails."""
        fellows = set()
        for fellow_list in self.rm_fellow_assignments.values():
            fellows.update(f.lower() for f in fellow_list)
        return fellows

    def get_rm_name(self, rm_email: str) -> str:
        """Get display name for an RM, falling back to email prefix."""
        return self.rm_names.get(rm_email, rm_email.split("@")[0])

    def is_senior_rm(self, rm_email: str) -> bool:
        """Check if an RM is a Senior RM (manages other RMs)."""
        return rm_email in self.senior_rm_direct_reports

    def get_manager(self, rm_email: str) -> str | None:
        """Get the Senior RM who manages this RM, or None."""
        for senior_rm, reports in self.senior_rm_direct_reports.items():
            if rm_email.lower() in [r.lower() for r in reports]:
                return senior_rm
        return None

    def get_direct_reports(self, senior_rm_email: str) -> list[str]:
        """Get list of RMs managed by a Senior RM (includes the Senior RM themselves)."""
        reports = self.senior_rm_direct_reports.get(senior_rm_email, [])
        # Include the Senior RM in their own aggregate
        if senior_rm_email not in reports:
            return [senior_rm_email] + reports
        return reports

    def get_report_viewers(self, rm_email: str) -> list[str]:
        """
        Get list of emails who should have access to an RM's personal report.

        Includes: the RM, their manager (if any), executive overview recipients.
        """
        viewers = [rm_email]

        # Add manager (Senior RM)
        manager = self.get_manager(rm_email)
        if manager:
            viewers.append(manager)

        # Add executive overview recipients (RM lead + execs)
        viewers.extend(self.executive_overview_recipients)

        return list(set(viewers))  # Dedupe

    @classmethod
    def from_json(cls, path: Path) -> "MATSConfig":
        """Load config from JSON file."""
        with open(path) as f:
            data = json.load(f)

        def parse_dest(dest_data: dict | None) -> ReportDestination | None:
            if not dest_data:
                return None
            return ReportDestination(
                doc_id=dest_data.get("doc_id"),
                doc_title=dest_data.get("doc_title", ""),
                folder_id=dest_data.get("folder_id"),
                viewers=dest_data.get("viewers", []),
            )

        # Parse report destinations
        rm_report_destinations = {
            rm_email: parse_dest(dest_data)
            for rm_email, dest_data in data.get("rm_report_destinations", {}).items()
            if dest_data
        }

        senior_rm_aggregate_destinations = {
            rm_email: parse_dest(dest_data)
            for rm_email, dest_data in data.get("senior_rm_aggregate_destinations", {}).items()
            if dest_data
        }

        return cls(
            rm_fellow_assignments=data.get("rm_fellow_assignments", {}),
            all_staff=data.get("all_staff", []),
            all_rms=data.get("all_rms", []),
            subteams=data.get("subteams", {}),
            rm_names=data.get("rm_names", {}),
            senior_rm_direct_reports=data.get("senior_rm_direct_reports", {}),
            executive_overview_recipients=data.get("executive_overview_recipients", []),
            rm_report_destinations=rm_report_destinations,
            senior_rm_aggregate_destinations=senior_rm_aggregate_destinations,
            executive_overview_destination=parse_dest(data.get("executive_overview_destination")),
        )

    def to_json(self, path: Path) -> None:
        """Save config to JSON file."""

        def dest_to_dict(dest: ReportDestination | None) -> dict | None:
            if dest is None:
                return None
            return {
                "doc_id": dest.doc_id,
                "doc_title": dest.doc_title,
                "folder_id": dest.folder_id,
                "viewers": dest.viewers,
            }

        data = {
            "rm_fellow_assignments": self.rm_fellow_assignments,
            "all_staff": self.all_staff,
            "all_rms": self.all_rms,
            "subteams": self.subteams,
            "rm_names": self.rm_names,
            "senior_rm_direct_reports": self.senior_rm_direct_reports,
            "executive_overview_recipients": self.executive_overview_recipients,
            "rm_report_destinations": {
                rm: dest_to_dict(dest)
                for rm, dest in self.rm_report_destinations.items()
            },
            "senior_rm_aggregate_destinations": {
                rm: dest_to_dict(dest)
                for rm, dest in self.senior_rm_aggregate_destinations.items()
            },
            "executive_overview_destination": dest_to_dict(self.executive_overview_destination),
        }
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
