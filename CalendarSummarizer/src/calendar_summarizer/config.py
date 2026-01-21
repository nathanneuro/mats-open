"""Configuration for RM-Fellow assignments and organizational structure."""

from dataclasses import dataclass, field
from pathlib import Path
import json


@dataclass
class MATSConfig:
    """
    Configuration for MATS organizational structure.

    Maps Research Managers to their assigned Fellows, and defines
    team groupings for meeting categorization.
    """
    # Map of RM email -> list of assigned Fellow emails
    rm_fellow_assignments: dict[str, list[str]] = field(default_factory=dict)

    # All staff emails (for identifying "all staff" meetings)
    all_staff: list[str] = field(default_factory=list)

    # All RM emails (for identifying "RM community of practice" meetings)
    all_rms: list[str] = field(default_factory=list)

    # Subteam definitions: name -> list of member emails
    subteams: dict[str, list[str]] = field(default_factory=dict)

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

    @classmethod
    def from_json(cls, path: Path) -> "MATSConfig":
        """Load config from JSON file."""
        with open(path) as f:
            data = json.load(f)
        return cls(
            rm_fellow_assignments=data.get("rm_fellow_assignments", {}),
            all_staff=data.get("all_staff", []),
            all_rms=data.get("all_rms", []),
            subteams=data.get("subteams", {}),
        )

    def to_json(self, path: Path) -> None:
        """Save config to JSON file."""
        data = {
            "rm_fellow_assignments": self.rm_fellow_assignments,
            "all_staff": self.all_staff,
            "all_rms": self.all_rms,
            "subteams": self.subteams,
        }
        with open(path, "w") as f:
            json.dump(data, f, indent=2)
