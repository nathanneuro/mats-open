#!/usr/bin/env python3
"""
Reliability tests for conflict resolution framework.

Tests:
1. Basic functionality - tool creates analyses correctly
2. Conflict type identification - types are identified based on keywords
3. Strategy suggestions - appropriate strategies suggested for conflict types
4. Dialogue structure - dialogue format generated correctly
5. Edge cases - handles incomplete or unusual input
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'tools'))

from conflict_resolution import (
    ConflictType, ResolutionStrategy, Party, ConflictAnalysis,
    ConflictResolutionTool
)


class TestConflictType:
    """Test ConflictType enum."""

    def test_all_types_exist(self):
        """Test all expected conflict types are defined."""
        expected = [
            'RESOURCE', 'INTEREST', 'VALUE', 'IDENTITY',
            'STRUCTURAL', 'FACTUAL', 'RELATIONSHIP'
        ]
        for name in expected:
            assert hasattr(ConflictType, name)

    def test_type_values(self):
        """Test type values are strings."""
        for ctype in ConflictType:
            assert isinstance(ctype.value, str)


class TestResolutionStrategy:
    """Test ResolutionStrategy enum."""

    def test_all_strategies_exist(self):
        """Test all expected strategies are defined."""
        expected = [
            'EXPAND_PIE', 'LOGROLL', 'COMPROMISE', 'INTEGRATE',
            'SEPARATE', 'ACCEPT_DIFFERENCE', 'TRANSFORM', 'ADJUDICATE'
        ]
        for name in expected:
            assert hasattr(ResolutionStrategy, name)


class TestParty:
    """Test Party dataclass."""

    def test_party_creation(self):
        """Test party is created with correct fields."""
        party = Party(
            name="Test Party",
            stated_position="We want X"
        )
        assert party.name == "Test Party"
        assert party.stated_position == "We want X"
        assert party.underlying_interests == []
        assert party.needs == []
        assert party.fears == []
        assert party.constraints == []
        assert party.best_alternative == ""

    def test_party_with_all_fields(self):
        """Test party with all fields populated."""
        party = Party(
            name="Full Party",
            stated_position="Position",
            underlying_interests=["interest1", "interest2"],
            needs=["need1"],
            fears=["fear1"],
            constraints=["constraint1"],
            best_alternative="BATNA"
        )
        assert len(party.underlying_interests) == 2
        assert len(party.needs) == 1
        assert party.best_alternative == "BATNA"


class TestConflictAnalysis:
    """Test ConflictAnalysis dataclass."""

    def test_analysis_creation(self):
        """Test analysis is created correctly."""
        analysis = ConflictAnalysis(
            description="Test conflict",
            conflict_types=[ConflictType.RESOURCE],
            parties=[]
        )
        assert analysis.description == "Test conflict"
        assert ConflictType.RESOURCE in analysis.conflict_types
        assert analysis.shared_interests == []
        assert analysis.potential_strategies == []


class TestConflictResolutionTool:
    """Test ConflictResolutionTool functionality."""

    def test_tool_creation(self):
        """Test tool initializes correctly."""
        tool = ConflictResolutionTool()
        assert tool.analyses == []
        assert len(tool.UNIVERSAL_NEEDS) > 0
        assert len(tool.INTEREST_QUESTIONS) > 0

    def test_analyze_conflict_basic(self):
        """Test basic conflict analysis."""
        tool = ConflictResolutionTool()

        parties_info = [
            {
                "name": "Party A",
                "position": "We want more budget",
                "interests": ["grow team", "money"],
                "fears": ["falling behind"],
                "constraints": ["limited resources"],
                "batna": "Find other funding"
            },
            {
                "name": "Party B",
                "position": "We want more budget too",
                "interests": ["expand operations", "budget"],
                "fears": ["losing staff"],
                "constraints": ["same budget pool"],
                "batna": "Cut projects"
            }
        ]

        analysis = tool.analyze_conflict("Budget dispute", parties_info)

        assert analysis.description == "Budget dispute"
        assert len(analysis.parties) == 2
        assert analysis.parties[0].name == "Party A"
        assert len(analysis.conflict_types) > 0
        assert len(analysis.shared_interests) > 0

    def test_conflict_type_identification_resource(self):
        """Test resource conflict is identified."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["money", "budget", "resources"]},
            {"name": "B", "position": "Y", "interests": ["funding", "staff"]}
        ]

        analysis = tool.analyze_conflict("Money fight", parties_info)
        assert ConflictType.RESOURCE in analysis.conflict_types

    def test_conflict_type_identification_value(self):
        """Test value conflict is identified."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["values", "beliefs", "principles"]},
            {"name": "B", "position": "Y", "interests": ["ethics", "morals"]}
        ]

        analysis = tool.analyze_conflict("Values disagreement", parties_info)
        assert ConflictType.VALUE in analysis.conflict_types

    def test_conflict_type_identification_identity(self):
        """Test identity conflict is identified."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["respect", "recognition", "dignity"]},
            {"name": "B", "position": "Y", "interests": ["identity", "culture"]}
        ]

        analysis = tool.analyze_conflict("Identity conflict", parties_info)
        assert ConflictType.IDENTITY in analysis.conflict_types

    def test_conflict_type_identification_factual(self):
        """Test factual conflict is identified."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["facts", "evidence", "data"]},
            {"name": "B", "position": "Y", "interests": ["truth"]}
        ]

        analysis = tool.analyze_conflict("Factual dispute", parties_info)
        assert ConflictType.FACTUAL in analysis.conflict_types

    def test_default_to_interest_conflict(self):
        """Test default to interest conflict when no keywords match."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["something vague"]},
            {"name": "B", "position": "Y", "interests": ["another thing"]}
        ]

        analysis = tool.analyze_conflict("Generic conflict", parties_info)
        assert ConflictType.INTEREST in analysis.conflict_types


class TestStrategyGeneration:
    """Test strategy suggestion logic."""

    def test_resource_strategies(self):
        """Test strategies suggested for resource conflicts."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["money", "budget"]},
            {"name": "B", "position": "Y", "interests": ["resources"]}
        ]

        analysis = tool.analyze_conflict("Resource conflict", parties_info)

        strategy_types = [s[0] for s in analysis.potential_strategies]
        assert ResolutionStrategy.EXPAND_PIE in strategy_types or \
               ResolutionStrategy.LOGROLL in strategy_types

    def test_value_strategies(self):
        """Test strategies suggested for value conflicts."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["values", "beliefs"]},
            {"name": "B", "position": "Y", "interests": ["principles"]}
        ]

        analysis = tool.analyze_conflict("Value conflict", parties_info)

        strategy_types = [s[0] for s in analysis.potential_strategies]
        assert ResolutionStrategy.ACCEPT_DIFFERENCE in strategy_types or \
               ResolutionStrategy.SEPARATE in strategy_types

    def test_factual_strategies(self):
        """Test strategies suggested for factual conflicts."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["facts", "evidence"]},
            {"name": "B", "position": "Y", "interests": ["data"]}
        ]

        analysis = tool.analyze_conflict("Factual conflict", parties_info)

        strategy_types = [s[0] for s in analysis.potential_strategies]
        assert ResolutionStrategy.ADJUDICATE in strategy_types


class TestDialogueStructure:
    """Test dialogue structure generation."""

    def test_dialogue_generated(self):
        """Test dialogue structure is generated."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X"},
            {"name": "B", "position": "Y"}
        ]

        analysis = tool.analyze_conflict("Test", parties_info)
        dialogue = tool.generate_dialogue_structure(analysis)

        assert isinstance(dialogue, str)
        assert len(dialogue) > 0

    def test_dialogue_contains_phases(self):
        """Test dialogue contains all phases."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X"},
            {"name": "B", "position": "Y"}
        ]

        analysis = tool.analyze_conflict("Test", parties_info)
        dialogue = tool.generate_dialogue_structure(analysis)

        expected_phases = [
            "PHASE 1", "PHASE 2", "PHASE 3", "PHASE 4",
            "PHASE 5", "PHASE 6", "PHASE 7"
        ]

        for phase in expected_phases:
            assert phase in dialogue, f"Missing {phase}"

    def test_dialogue_includes_shared_interests(self):
        """Test dialogue includes identified shared interests."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["efficiency"]},
            {"name": "B", "position": "Y", "interests": ["quality"]}
        ]

        analysis = tool.analyze_conflict("Test", parties_info)
        dialogue = tool.generate_dialogue_structure(analysis)

        # Should mention shared interests if any exist
        if analysis.shared_interests:
            assert "shared interests" in dialogue.lower()


class TestEdgeCases:
    """Test edge cases and unusual inputs."""

    def test_single_party(self):
        """Test with single party (unusual but shouldn't crash)."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X", "interests": ["something"]}
        ]

        analysis = tool.analyze_conflict("One-sided", parties_info)
        assert len(analysis.parties) == 1

    def test_empty_parties(self):
        """Test with no parties."""
        tool = ConflictResolutionTool()

        analysis = tool.analyze_conflict("Empty", [])
        assert len(analysis.parties) == 0

    def test_minimal_party_info(self):
        """Test with minimal party information."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A"},
            {"name": "B"}
        ]

        analysis = tool.analyze_conflict("Minimal", parties_info)
        assert len(analysis.parties) == 2
        assert analysis.parties[0].stated_position == ""

    def test_missing_name(self):
        """Test with missing name field."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"position": "X"},
            {"position": "Y"}
        ]

        analysis = tool.analyze_conflict("No names", parties_info)
        assert analysis.parties[0].name == "Unknown"

    def test_empty_interests(self):
        """Test with empty interests lists."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "interests": []},
            {"name": "B", "interests": []}
        ]

        analysis = tool.analyze_conflict("No interests", parties_info)
        # Should default to INTEREST type
        assert ConflictType.INTEREST in analysis.conflict_types


class TestSharedInterestIdentification:
    """Test shared interest identification."""

    def test_potential_shared_interests_found(self):
        """Test that potential shared interests are identified."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "X"},
            {"name": "B", "position": "Y"}
        ]

        analysis = tool.analyze_conflict("Test", parties_info)

        # Should find some shared interests (built-in potential ones)
        assert len(analysis.shared_interests) > 0

    def test_incompatible_positions_noted(self):
        """Test incompatible positions are noted."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "A", "position": "We should expand"},
            {"name": "B", "position": "We should contract"}
        ]

        analysis = tool.analyze_conflict("Test", parties_info)

        # Positions should be noted as potentially incompatible
        assert len(analysis.incompatible_interests) > 0


class TestAnalysisStorage:
    """Test that analyses are stored correctly."""

    def test_analyses_stored(self):
        """Test analyses are stored in tool."""
        tool = ConflictResolutionTool()

        for i in range(3):
            parties_info = [{"name": f"A{i}"}, {"name": f"B{i}"}]
            tool.analyze_conflict(f"Conflict {i}", parties_info)

        assert len(tool.analyses) == 3

    def test_analysis_retrieval(self):
        """Test stored analysis can be retrieved."""
        tool = ConflictResolutionTool()

        parties_info = [
            {"name": "Engineering", "position": "Need developers"},
            {"name": "Sales", "position": "Need salespeople"}
        ]

        analysis = tool.analyze_conflict("Hiring dispute", parties_info)

        # Should be same object
        assert tool.analyses[-1] is analysis


class TestQuestionLists:
    """Test built-in question lists."""

    def test_interest_questions_exist(self):
        """Test interest surfacing questions exist."""
        tool = ConflictResolutionTool()
        assert len(tool.INTEREST_QUESTIONS) >= 5

    def test_common_ground_questions_exist(self):
        """Test common ground questions exist."""
        tool = ConflictResolutionTool()
        assert len(tool.COMMON_GROUND_QUESTIONS) >= 5

    def test_option_generation_questions_exist(self):
        """Test option generation questions exist."""
        tool = ConflictResolutionTool()
        assert len(tool.OPTION_GENERATION_QUESTIONS) >= 5

    def test_universal_needs_exist(self):
        """Test universal needs list exists."""
        tool = ConflictResolutionTool()
        assert len(tool.UNIVERSAL_NEEDS) >= 5
        assert "autonomy" in tool.UNIVERSAL_NEEDS
        assert "connection" in tool.UNIVERSAL_NEEDS


def run_all_tests():
    """Run all tests and report results."""
    import traceback

    test_classes = [
        TestConflictType,
        TestResolutionStrategy,
        TestParty,
        TestConflictAnalysis,
        TestConflictResolutionTool,
        TestStrategyGeneration,
        TestDialogueStructure,
        TestEdgeCases,
        TestSharedInterestIdentification,
        TestAnalysisStorage,
        TestQuestionLists,
    ]

    total_tests = 0
    passed_tests = 0
    failed_tests = []

    for test_class in test_classes:
        print(f"\n{test_class.__name__}")
        print("-" * 40)

        instance = test_class()
        methods = [m for m in dir(instance) if m.startswith('test_')]

        for method_name in methods:
            total_tests += 1
            try:
                getattr(instance, method_name)()
                print(f"  ✓ {method_name}")
                passed_tests += 1
            except AssertionError as e:
                print(f"  ✗ {method_name}: {e}")
                failed_tests.append((test_class.__name__, method_name, str(e)))
            except Exception as e:
                print(f"  ✗ {method_name}: {type(e).__name__}: {e}")
                failed_tests.append((test_class.__name__, method_name, traceback.format_exc()))

    print("\n" + "=" * 50)
    print(f"RESULTS: {passed_tests}/{total_tests} tests passed")
    print("=" * 50)

    if failed_tests:
        print("\nFailed tests:")
        for class_name, method, error in failed_tests:
            print(f"  - {class_name}.{method}")

    return len(failed_tests) == 0


if __name__ == "__main__":
    success = run_all_tests()
    sys.exit(0 if success else 1)
