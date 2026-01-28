#!/usr/bin/env python3
"""
Conflict Resolution Framework

A structured approach to analyzing and potentially resolving conflicts.
Not a magic solution, but a tool for thinking more clearly about disagreements.

Based on:
- Interest-based negotiation (Fisher & Ury's "Getting to Yes")
- Nonviolent Communication (Marshall Rosenberg)
- Mediation theory
- Game theory insights

This tool helps by:
1. Structuring the conflict (what's actually being disputed?)
2. Identifying interests vs. positions
3. Finding potential win-win solutions
4. Recognizing when resolution is/isn't possible
"""

from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple
from enum import Enum
import json


class ConflictType(Enum):
    """Types of conflicts - different types may need different approaches."""
    RESOURCE = "resource"  # Competing for limited resources
    INTEREST = "interest"  # Different goals or priorities
    VALUE = "value"  # Different fundamental values/beliefs
    IDENTITY = "identity"  # Threats to identity or dignity
    STRUCTURAL = "structural"  # Built into systems/institutions
    FACTUAL = "factual"  # Disagreement about facts
    RELATIONSHIP = "relationship"  # Personal history/dynamics


class ResolutionStrategy(Enum):
    """Possible approaches to resolution."""
    EXPAND_PIE = "expand_pie"  # Find more resources/options
    LOGROLL = "logroll"  # Trade across issues (I give on X, you give on Y)
    COMPROMISE = "compromise"  # Split the difference
    INTEGRATE = "integrate"  # Find solution that meets both underlying interests
    SEPARATE = "separate"  # Disengage (sometimes the right answer)
    ACCEPT_DIFFERENCE = "accept_difference"  # Agree to disagree
    TRANSFORM = "transform"  # Change the game itself
    ADJUDICATE = "adjudicate"  # Bring in third party to decide


@dataclass
class Party:
    """One party in a conflict."""
    name: str
    stated_position: str  # What they say they want
    underlying_interests: List[str] = field(default_factory=list)  # WHY they want it
    needs: List[str] = field(default_factory=list)  # Fundamental human needs at stake
    fears: List[str] = field(default_factory=list)  # What they're afraid of
    constraints: List[str] = field(default_factory=list)  # What limits their options
    best_alternative: str = ""  # BATNA: what happens if no agreement
    relationship_to_other: str = ""  # History, power dynamics


@dataclass
class ConflictAnalysis:
    """Structured analysis of a conflict."""
    description: str
    conflict_types: List[ConflictType]
    parties: List[Party]
    shared_interests: List[str] = field(default_factory=list)
    incompatible_interests: List[str] = field(default_factory=list)
    potential_strategies: List[Tuple[ResolutionStrategy, str]] = field(default_factory=list)
    factual_questions: List[str] = field(default_factory=list)
    structural_factors: List[str] = field(default_factory=list)
    power_asymmetries: List[str] = field(default_factory=list)
    notes: str = ""


class ConflictResolutionTool:
    """
    Tool for structured conflict analysis and resolution.

    This is not an automated solver - it's a thinking aid.
    It helps structure the problem, not magically solve it.
    """

    # Human needs (based on Maslow + others) that often underlie conflicts
    UNIVERSAL_NEEDS = [
        "physical_safety",
        "material_security",
        "autonomy",
        "competence",
        "connection",
        "respect",
        "meaning",
        "fairness",
        "predictability",
        "identity_recognition"
    ]

    # Questions to surface underlying interests
    INTEREST_QUESTIONS = [
        "Why do you want this particular outcome?",
        "What would having this give you that you don't have now?",
        "If you got exactly what you're asking for, what would be different?",
        "What are you afraid of if you don't get this?",
        "What need is this trying to meet?",
        "Is there another way to meet that need?",
        "What would you need to feel okay with a different outcome?",
        "What matters most to you about this issue?",
        "What would be the worst part of not getting what you want?",
        "What would you be willing to give up to get the most important thing?"
    ]

    # Questions to find shared ground
    COMMON_GROUND_QUESTIONS = [
        "What do both parties agree is a problem?",
        "What outcomes would both parties consider unacceptable?",
        "Are there interests that both parties share?",
        "Is there anything both parties are trying to protect?",
        "Do both parties value the relationship continuing?",
        "Are there facts both parties accept?",
        "Is there a shared principle both would endorse?"
    ]

    # Questions to generate options
    OPTION_GENERATION_QUESTIONS = [
        "What if both parties got everything they wanted - what would that look like?",
        "Are there resources that could be expanded?",
        "Is there a third option neither party has considered?",
        "What would an outside observer suggest?",
        "What would you suggest to a friend in this situation?",
        "Are there issues that could be traded across?",
        "Is the real conflict about something other than what's stated?",
        "What small agreement could build trust for larger ones?",
        "What would a creative solution that neither likes but both can live with look like?",
        "If this conflict didn't exist, what would people be doing instead?"
    ]

    def __init__(self):
        self.analyses: List[ConflictAnalysis] = []

    def analyze_conflict(self, description: str,
                         parties_info: List[Dict]) -> ConflictAnalysis:
        """
        Create a structured conflict analysis.

        parties_info should be a list of dicts with:
        - name: str
        - position: str (what they're demanding)
        - interests: list (why they want it)
        - fears: list (what they're afraid of)
        - constraints: list (what limits them)
        - batna: str (alternative to agreement)
        """
        parties = []
        for info in parties_info:
            party = Party(
                name=info.get("name", "Unknown"),
                stated_position=info.get("position", ""),
                underlying_interests=info.get("interests", []),
                fears=info.get("fears", []),
                constraints=info.get("constraints", []),
                best_alternative=info.get("batna", "")
            )
            parties.append(party)

        # Identify conflict types
        conflict_types = self._identify_conflict_types(parties)

        # Find shared and incompatible interests
        shared, incompatible = self._analyze_interests(parties)

        # Generate potential strategies
        strategies = self._suggest_strategies(conflict_types, shared, incompatible)

        analysis = ConflictAnalysis(
            description=description,
            conflict_types=conflict_types,
            parties=parties,
            shared_interests=shared,
            incompatible_interests=incompatible,
            potential_strategies=strategies
        )

        self.analyses.append(analysis)
        return analysis

    def _identify_conflict_types(self, parties: List[Party]) -> List[ConflictType]:
        """Identify what type(s) of conflict this is."""
        types = []

        # This is simplified - real identification would be more nuanced
        all_interests = []
        for party in parties:
            all_interests.extend(party.underlying_interests)

        keywords_to_types = {
            ConflictType.RESOURCE: ["money", "budget", "time", "space", "staff", "resources"],
            ConflictType.VALUE: ["values", "beliefs", "principles", "ethics", "morals"],
            ConflictType.IDENTITY: ["respect", "recognition", "dignity", "identity", "culture"],
            ConflictType.STRUCTURAL: ["policy", "rules", "system", "institution"],
            ConflictType.FACTUAL: ["facts", "evidence", "data", "truth"],
        }

        interest_text = " ".join(all_interests).lower()
        for ctype, keywords in keywords_to_types.items():
            if any(kw in interest_text for kw in keywords):
                types.append(ctype)

        if not types:
            types.append(ConflictType.INTEREST)  # Default

        return types

    def _analyze_interests(self, parties: List[Party]) -> Tuple[List[str], List[str]]:
        """Find shared and incompatible interests."""
        if len(parties) < 2:
            return [], []

        # Very simplified - real analysis would be more sophisticated
        shared = []
        incompatible = []

        # Common interests people often share even in conflict
        potential_shared = [
            "resolution of uncertainty",
            "not wasting more time/resources on conflict",
            "maintaining face/reputation",
            "being heard and understood",
            "fairness in process",
            "predictability going forward"
        ]

        for interest in potential_shared:
            shared.append(interest)

        # Positions are more likely to be incompatible than interests
        for i, p1 in enumerate(parties):
            for p2 in parties[i + 1:]:
                # Check if positions directly conflict
                if p1.stated_position and p2.stated_position:
                    incompatible.append(f"Position conflict: {p1.name} wants '{p1.stated_position}' "
                                        f"vs {p2.name} wants '{p2.stated_position}'")

        return shared, incompatible

    def _suggest_strategies(self, conflict_types: List[ConflictType],
                            shared: List[str],
                            incompatible: List[str]) -> List[Tuple[ResolutionStrategy, str]]:
        """Suggest resolution strategies based on conflict analysis."""
        strategies = []

        # Different strategies work for different conflict types
        if ConflictType.RESOURCE in conflict_types:
            strategies.append((ResolutionStrategy.EXPAND_PIE,
                               "Can resources be expanded? Are there untapped sources?"))
            strategies.append((ResolutionStrategy.LOGROLL,
                               "Are there multiple resources to trade across?"))

        if ConflictType.VALUE in conflict_types:
            strategies.append((ResolutionStrategy.ACCEPT_DIFFERENCE,
                               "Can parties coexist with different values? Agree to disagree?"))
            strategies.append((ResolutionStrategy.SEPARATE,
                               "Is separation or reduced interaction viable?"))

        if ConflictType.IDENTITY in conflict_types:
            strategies.append((ResolutionStrategy.TRANSFORM,
                               "Can the conflict be reframed so neither identity is threatened?"))
            strategies.append((ResolutionStrategy.INTEGRATE,
                               "Is there a way to affirm both identities?"))

        if ConflictType.FACTUAL in conflict_types:
            strategies.append((ResolutionStrategy.ADJUDICATE,
                               "Can an agreed expert/authority resolve the factual dispute?"))

        # Universal strategies
        if shared:
            strategies.append((ResolutionStrategy.INTEGRATE,
                               f"Build on shared interests: {', '.join(shared[:3])}"))

        return strategies

    def generate_dialogue_structure(self, analysis: ConflictAnalysis) -> str:
        """Generate a structured dialogue format for the conflict."""
        structure = []

        structure.append("STRUCTURED DIALOGUE FORMAT")
        structure.append("=" * 50)
        structure.append("")

        structure.append("PHASE 1: ESTABLISHING GROUND RULES")
        structure.append("-" * 40)
        structure.append("- Each party will have uninterrupted time to speak")
        structure.append("- No personal attacks or accusations")
        structure.append("- Focus on interests, not positions")
        structure.append("- Seek to understand before seeking to be understood")
        structure.append("")

        structure.append("PHASE 2: SHARING PERSPECTIVES")
        structure.append("-" * 40)
        structure.append("Each party answers:")
        structure.append("1. What is your understanding of the situation?")
        structure.append("2. What matters most to you here?")
        structure.append("3. What are you most concerned about?")
        structure.append("4. What would help you feel heard?")
        structure.append("")

        structure.append("PHASE 3: REFLECTION")
        structure.append("-" * 40)
        structure.append("Each party reflects back what they heard the other say:")
        structure.append("'What I heard you say is...'")
        structure.append("'Did I understand you correctly?'")
        structure.append("")

        structure.append("PHASE 4: INTEREST IDENTIFICATION")
        structure.append("-" * 40)
        structure.append("Facilitator asks each party:")
        for q in self.INTEREST_QUESTIONS[:5]:
            structure.append(f"- {q}")
        structure.append("")

        structure.append("PHASE 5: COMMON GROUND")
        structure.append("-" * 40)
        structure.append("Together identify:")
        structure.append("- What do we both agree is important?")
        structure.append("- What outcomes would neither of us want?")
        structure.append("- What shared interests do we have?")
        if analysis.shared_interests:
            structure.append(f"\nPotential shared interests identified:")
            for interest in analysis.shared_interests:
                structure.append(f"  - {interest}")
        structure.append("")

        structure.append("PHASE 6: OPTION GENERATION")
        structure.append("-" * 40)
        structure.append("Brainstorm options WITHOUT evaluating them yet:")
        for q in self.OPTION_GENERATION_QUESTIONS[:5]:
            structure.append(f"- {q}")
        structure.append("")

        structure.append("PHASE 7: EVALUATION AND AGREEMENT")
        structure.append("-" * 40)
        structure.append("For each option:")
        structure.append("- Does it address both parties' core interests?")
        structure.append("- Is it feasible given constraints?")
        structure.append("- Is it better than each party's BATNA?")
        structure.append("- What would make it acceptable to each party?")
        structure.append("")

        return "\n".join(structure)


def demonstrate_tool():
    """Demonstrate the conflict resolution tool."""
    print("=" * 70)
    print("CONFLICT RESOLUTION FRAMEWORK - DEMONSTRATION")
    print("=" * 70)
    print()

    tool = ConflictResolutionTool()

    # Example conflict: Workplace resource allocation
    print("EXAMPLE: Department Resource Allocation Conflict")
    print("-" * 50)

    parties_info = [
        {
            "name": "Engineering",
            "position": "We need three more developers",
            "interests": ["deliver features on roadmap", "reduce burnout", "stay competitive"],
            "fears": ["missing deadlines", "losing talent", "technical debt"],
            "constraints": ["budget cap", "hiring timeline"],
            "batna": "Outsource some development"
        },
        {
            "name": "Sales",
            "position": "We need two more sales reps",
            "interests": ["hit revenue targets", "cover new territory", "respond to leads faster"],
            "fears": ["missing quota", "losing deals to competitors", "team overwork"],
            "constraints": ["budget cap", "training time"],
            "batna": "Focus on existing accounts only"
        }
    ]

    analysis = tool.analyze_conflict(
        "Two departments competing for limited hiring budget",
        parties_info
    )

    print(f"\nConflict Types Identified: {[t.value for t in analysis.conflict_types]}")
    print(f"\nShared Interests:")
    for interest in analysis.shared_interests:
        print(f"  - {interest}")

    print(f"\nPotential Strategies:")
    for strategy, rationale in analysis.potential_strategies:
        print(f"  [{strategy.value}]: {rationale}")

    print("\n" + "=" * 70)
    print("STRUCTURED DIALOGUE FORMAT")
    print("=" * 70)
    print(tool.generate_dialogue_structure(analysis))

    print("\n" + "=" * 70)
    print("KEY PRINCIPLES")
    print("=" * 70)
    print("""
1. POSITIONS vs INTERESTS
   - Positions are what people SAY they want
   - Interests are WHY they want it
   - Interests are usually more compatible than positions

2. BATNA MATTERS
   - Best Alternative To Negotiated Agreement
   - Determines whether agreement is better than no agreement
   - Both parties should know their BATNA

3. EXPAND THE PIE BEFORE DIVIDING IT
   - Look for resources that could be created or discovered
   - Trade across issues (logrolling)
   - Change the framing of what's being contested

4. SEPARATE PEOPLE FROM PROBLEMS
   - Acknowledge emotions without letting them drive decisions
   - Attack the problem, not each other
   - Maintain relationship even during disagreement

5. SOME CONFLICTS SHOULDN'T BE RESOLVED
   - Value conflicts may need to be accepted, not resolved
   - Power imbalances may require external intervention
   - Some conflicts require structural change, not just agreement

6. PROCESS MATTERS AS MUCH AS OUTCOME
   - Fair process increases acceptance of outcomes
   - Being heard matters even when you don't get what you want
   - Sustainable agreements require buy-in
""")


if __name__ == "__main__":
    demonstrate_tool()
