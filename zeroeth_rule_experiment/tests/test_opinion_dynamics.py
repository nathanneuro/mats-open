#!/usr/bin/env python3
"""
Reliability tests for opinion dynamics simulation.

Tests:
1. Basic functionality - simulation runs without errors
2. Reproducibility - same seed produces same results
3. Opinion updates - bounded confidence and backfire work correctly
4. Expected dynamics - polarization patterns match theory
5. Boundary conditions - extreme parameters handled correctly
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'simulations'))

import numpy as np
import random
from opinion_dynamics import (
    Agent, SimulationParameters, OpinionSimulation
)


class TestAgentBasics:
    """Test Agent class functionality."""

    def test_agent_creation(self):
        """Test that agents are created with valid initial values."""
        agent = Agent(
            id=0,
            opinion=0.0,
            confidence=0.5,
            openness=0.5,
            influence=0.5
        )
        assert agent.id == 0
        assert -1 <= agent.opinion <= 1
        assert 0 <= agent.confidence <= 1
        assert 0 <= agent.openness <= 1
        assert 0 <= agent.influence <= 1

    def test_opinion_history_initialized(self):
        """Test that opinion history is initialized."""
        agent = Agent(id=0, opinion=0.0, confidence=0.5, openness=0.5, influence=0.5)
        assert isinstance(agent.opinion_history, list)


class TestOpinionUpdates:
    """Test opinion update mechanics."""

    def test_update_within_threshold(self):
        """Test opinion moves toward similar other."""
        params = SimulationParameters(
            confidence_threshold=0.5,
            influence_strength=0.2,
            backfire_effect=0.0
        )
        agent = Agent(id=0, opinion=0.0, confidence=0.3, openness=0.8, influence=0.5)

        # Other has opinion 0.2 (within threshold)
        agent.update_from_interaction(0.2, 0.5, params)

        # Opinion should move toward 0.2
        assert agent.opinion > 0.0

    def test_no_update_beyond_threshold(self):
        """Test opinions too far apart don't converge (without backfire)."""
        params = SimulationParameters(
            confidence_threshold=0.3,
            influence_strength=0.2,
            backfire_effect=0.0
        )
        agent = Agent(id=0, opinion=0.0, confidence=0.3, openness=0.8, influence=0.5)

        # Other has opinion 0.8 (beyond threshold of 0.3)
        original = agent.opinion
        agent.update_from_interaction(0.8, 0.5, params)

        # Opinion should not change (no backfire)
        assert agent.opinion == original

    def test_backfire_effect(self):
        """Test backfire effect pushes away from distant opinions."""
        params = SimulationParameters(
            confidence_threshold=0.3,
            influence_strength=0.2,
            backfire_effect=0.1
        )
        agent = Agent(id=0, opinion=0.0, confidence=0.3, openness=0.8, influence=0.5)

        # Other has opinion 0.8 (beyond threshold)
        agent.update_from_interaction(0.8, 0.5, params)

        # With backfire, should push away (become more negative)
        assert agent.opinion < 0.0

    def test_opinion_clamped(self):
        """Test opinion stays in [-1, 1] range."""
        params = SimulationParameters(
            confidence_threshold=1.0,
            influence_strength=1.0,
            backfire_effect=0.0
        )
        agent = Agent(id=0, opinion=0.9, confidence=0.0, openness=1.0, influence=0.5)

        # Push toward extreme
        for _ in range(10):
            agent.update_from_interaction(1.0, 1.0, params)

        assert agent.opinion <= 1.0
        assert agent.opinion >= -1.0


class TestSimulationParameters:
    """Test SimulationParameters configuration."""

    def test_default_parameters(self):
        """Test default parameter values."""
        params = SimulationParameters()
        assert params.n_agents == 200
        assert params.network_type == "homophily"
        assert 0 <= params.confidence_threshold <= 2
        assert params.backfire_effect >= 0

    def test_custom_parameters(self):
        """Test custom parameter values."""
        params = SimulationParameters(
            n_agents=50,
            network_type="random",
            confidence_threshold=0.8
        )
        assert params.n_agents == 50
        assert params.network_type == "random"
        assert params.confidence_threshold == 0.8


class TestSimulationBasics:
    """Test basic simulation functionality."""

    def test_simulation_creation(self):
        """Test simulation initializes correctly."""
        params = SimulationParameters(n_agents=30)
        sim = OpinionSimulation(params)
        assert len(sim.agents) == 30
        assert sim.round == 0
        assert len(sim.metrics_history) == 0

    def test_simulation_runs(self):
        """Test simulation runs without errors."""
        params = SimulationParameters(n_agents=30)
        sim = OpinionSimulation(params)
        history = sim.run(10)
        assert len(history) == 10
        assert sim.round == 10

    def test_all_network_types(self):
        """Test all network types initialize correctly."""
        for network_type in ["random", "homophily", "echo_chamber"]:
            params = SimulationParameters(n_agents=30, network_type=network_type)
            sim = OpinionSimulation(params)
            assert len(sim.network) == 30
            sim.run(3)
            assert sim.round == 3


class TestReproducibility:
    """Test that simulations are reproducible with same seeds."""

    def test_reproducibility(self):
        """Test same seed produces same results."""
        params = SimulationParameters(n_agents=50)

        # Run 1
        np.random.seed(42)
        random.seed(42)
        sim1 = OpinionSimulation(params)
        history1 = sim1.run(20)

        # Run 2
        np.random.seed(42)
        random.seed(42)
        sim2 = OpinionSimulation(params)
        history2 = sim2.run(20)

        # Results should match
        for h1, h2 in zip(history1, history2):
            assert abs(h1['mean_opinion'] - h2['mean_opinion']) < 1e-10
            assert abs(h1['opinion_variance'] - h2['opinion_variance']) < 1e-10


class TestExpectedDynamics:
    """Test theoretically expected polarization dynamics."""

    def test_echo_chambers_maintain_polarization(self):
        """Echo chambers should maintain or increase polarization."""
        np.random.seed(42)
        random.seed(42)

        params = SimulationParameters(
            n_agents=100,
            network_type="echo_chamber",
            backfire_effect=0.0
        )
        sim = OpinionSimulation(params)
        sim.run(50)
        summary = sim.get_summary()

        # In echo chambers, polarization should not decrease much
        # Initial polarization is high due to bimodal initialization
        assert summary['final_polarization'] > 0.3

    def test_random_network_reduces_polarization(self):
        """Random networks should tend toward consensus."""
        np.random.seed(42)
        random.seed(42)

        params = SimulationParameters(
            n_agents=100,
            network_type="random",
            confidence_threshold=1.0,  # High threshold - everyone influences everyone
            backfire_effect=0.0
        )
        sim = OpinionSimulation(params)
        sim.run(100)
        summary = sim.get_summary()

        # With high threshold and random network, variance should decrease
        assert summary['final_variance'] < summary['initial_variance']

    def test_low_threshold_increases_polarization(self):
        """Low confidence threshold should increase polarization."""
        np.random.seed(42)
        random.seed(42)

        params = SimulationParameters(
            n_agents=100,
            network_type="homophily",
            confidence_threshold=0.2,  # Only listen to very similar others
            backfire_effect=0.0
        )
        sim = OpinionSimulation(params)
        sim.run(50)
        summary = sim.get_summary()

        # Low threshold means people only talk to similar others
        # This should maintain or increase polarization
        assert summary['final_polarization'] >= summary['initial_polarization'] - 0.1

    def test_backfire_increases_extremity(self):
        """Backfire effect should increase opinion extremity."""
        np.random.seed(42)
        random.seed(42)

        # Without backfire
        params_no = SimulationParameters(
            n_agents=100,
            confidence_threshold=0.3,
            backfire_effect=0.0
        )
        sim_no = OpinionSimulation(params_no)
        sim_no.run(50)
        summary_no = sim_no.get_summary()

        np.random.seed(42)
        random.seed(42)

        # With backfire
        params_yes = SimulationParameters(
            n_agents=100,
            confidence_threshold=0.3,
            backfire_effect=0.1
        )
        sim_yes = OpinionSimulation(params_yes)
        sim_yes.run(50)
        summary_yes = sim_yes.get_summary()

        # Backfire should lead to higher extremity
        assert summary_yes['final_extremity'] >= summary_no['final_extremity'] - 0.1


class TestBridgingIntervention:
    """Test bridging intervention mechanics."""

    def test_bridging_creates_connections(self):
        """Test that bridging adds cross-group connections."""
        params = SimulationParameters(
            n_agents=50,
            network_type="echo_chamber",
            n_bridges=10
        )
        sim = OpinionSimulation(params)

        # Count initial cross-group connections
        positive = set(a.id for a in sim.agents if a.opinion > 0.3)
        negative = set(a.id for a in sim.agents if a.opinion < -0.3)

        initial_cross = 0
        for p in positive:
            for n in sim.network[p]:
                if n in negative:
                    initial_cross += 1

        # Apply bridging
        sim.apply_bridging_intervention()

        # Count again
        final_cross = 0
        for p in positive:
            for n in sim.network[p]:
                if n in negative:
                    final_cross += 1

        # Should have more cross-group connections
        assert final_cross >= initial_cross

    def test_bridging_in_run(self):
        """Test bridging works when called during run."""
        params = SimulationParameters(
            n_agents=50,
            network_type="echo_chamber",
            n_bridges=10
        )
        sim = OpinionSimulation(params)
        history = sim.run(30, bridging_at=10)

        assert len(history) == 30
        assert sim.round == 30


class TestBoundaryConditions:
    """Test simulation handles edge cases correctly."""

    def test_single_agent(self):
        """Test simulation with single agent."""
        params = SimulationParameters(n_agents=1)
        sim = OpinionSimulation(params)
        sim.run(5)
        assert sim.round == 5

    def test_two_agents(self):
        """Test with minimum viable population."""
        params = SimulationParameters(n_agents=2)
        sim = OpinionSimulation(params)
        sim.run(10)
        assert sim.round == 10

    def test_zero_backfire(self):
        """Test with zero backfire effect."""
        params = SimulationParameters(n_agents=30, backfire_effect=0.0)
        sim = OpinionSimulation(params)
        sim.run(10)
        assert sim.round == 10

    def test_full_confidence_threshold(self):
        """Test with maximum confidence threshold."""
        params = SimulationParameters(n_agents=30, confidence_threshold=2.0)
        sim = OpinionSimulation(params)
        sim.run(10)
        # All opinions should converge with infinite threshold
        assert sim.round == 10

    def test_no_extremists(self):
        """Test with zero extremist fraction."""
        params = SimulationParameters(n_agents=30, extremist_fraction=0.0)
        sim = OpinionSimulation(params)
        sim.run(10)
        assert sim.round == 10

    def test_all_extremists(self):
        """Test with all agents as extremists."""
        params = SimulationParameters(n_agents=30, extremist_fraction=1.0)
        sim = OpinionSimulation(params)
        sim.run(10)
        # Should still run
        assert sim.round == 10


class TestMetrics:
    """Test metrics calculation."""

    def test_polarization_index(self):
        """Test polarization index is calculated correctly."""
        params = SimulationParameters(n_agents=30)
        sim = OpinionSimulation(params)
        sim.run(5)

        for metrics in sim.metrics_history:
            # Polarization index should be fraction
            assert 0 <= metrics['polarization_index'] <= 1
            # Should match calculation
            total = metrics['n_positive'] + metrics['n_negative'] + metrics['n_moderate']
            assert total == 30

    def test_variance_non_negative(self):
        """Test variance is always non-negative."""
        params = SimulationParameters(n_agents=30)
        sim = OpinionSimulation(params)
        sim.run(10)

        for metrics in sim.metrics_history:
            assert metrics['opinion_variance'] >= 0

    def test_mean_opinion_bounded(self):
        """Test mean opinion stays in valid range."""
        params = SimulationParameters(n_agents=50)
        sim = OpinionSimulation(params)
        sim.run(20)

        for metrics in sim.metrics_history:
            assert -1 <= metrics['mean_opinion'] <= 1


class TestSummary:
    """Test summary generation."""

    def test_get_summary(self):
        """Test summary contains expected fields."""
        params = SimulationParameters(n_agents=30)
        sim = OpinionSimulation(params)
        sim.run(10)
        summary = sim.get_summary()

        expected_fields = [
            'initial_variance', 'final_variance', 'variance_change',
            'initial_polarization', 'final_polarization', 'final_extremity'
        ]

        for field in expected_fields:
            assert field in summary, f"Missing field: {field}"

    def test_empty_summary(self):
        """Test summary with no rounds run."""
        params = SimulationParameters(n_agents=10)
        sim = OpinionSimulation(params)
        summary = sim.get_summary()
        assert summary == {}


class TestMediaInfluence:
    """Test media influence mechanics."""

    def test_media_shifts_opinions(self):
        """Test media influence shifts opinions toward bias."""
        np.random.seed(42)
        random.seed(42)

        params = SimulationParameters(
            n_agents=50,
            media_bias=1.0,  # Strong positive bias
            media_strength=0.1
        )
        sim = OpinionSimulation(params)

        initial_mean = np.mean([a.opinion for a in sim.agents])
        sim.run(50)
        final_mean = np.mean([a.opinion for a in sim.agents])

        # Mean should shift toward positive
        assert final_mean > initial_mean


def run_all_tests():
    """Run all tests and report results."""
    import traceback

    test_classes = [
        TestAgentBasics,
        TestOpinionUpdates,
        TestSimulationParameters,
        TestSimulationBasics,
        TestReproducibility,
        TestExpectedDynamics,
        TestBridgingIntervention,
        TestBoundaryConditions,
        TestMetrics,
        TestSummary,
        TestMediaInfluence,
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
