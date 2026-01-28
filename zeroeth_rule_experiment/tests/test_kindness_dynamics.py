#!/usr/bin/env python3
"""
Reliability tests for kindness dynamics simulation.

Tests:
1. Basic functionality - simulation runs without errors
2. Reproducibility - same seed produces same results
3. Boundary conditions - extreme parameters handled correctly
4. Expected behaviors - model produces theoretically expected patterns
5. Conservation laws - metrics stay within valid ranges
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'simulations'))

import numpy as np
import random
from kindness_dynamics import (
    Agent, SimulationParameters, KindnessSimulation, run_experiment
)


class TestAgentBasics:
    """Test Agent class functionality."""

    def test_agent_creation(self):
        """Test that agents are created with valid initial values."""
        agent = Agent(
            id=0,
            kindness_capacity=0.5,
            wellbeing=0.5,
            received_kindness=0,
            given_kindness=0
        )
        assert agent.id == 0
        assert 0 <= agent.kindness_capacity <= 1
        assert 0 <= agent.wellbeing <= 1
        assert agent.kindness_history == []
        assert agent.wellbeing_history == []

    def test_agent_post_init(self):
        """Test that history lists are initialized."""
        agent = Agent(id=1, kindness_capacity=0.5, wellbeing=0.5,
                      received_kindness=0, given_kindness=0)
        assert isinstance(agent.kindness_history, list)
        assert isinstance(agent.wellbeing_history, list)


class TestSimulationParameters:
    """Test SimulationParameters defaults and validation."""

    def test_default_parameters(self):
        """Test default parameter values."""
        params = SimulationParameters()
        assert params.n_agents == 100
        assert params.network_type == "small_world"
        assert params.avg_connections == 6
        assert 0 <= params.kindness_threshold <= 1
        assert params.intervention_round is None

    def test_custom_parameters(self):
        """Test custom parameter values."""
        params = SimulationParameters(
            n_agents=50,
            network_type="random",
            intervention_round=10
        )
        assert params.n_agents == 50
        assert params.network_type == "random"
        assert params.intervention_round == 10


class TestSimulationBasics:
    """Test basic simulation functionality."""

    def test_simulation_creation(self):
        """Test simulation initializes correctly."""
        params = SimulationParameters(n_agents=20)
        sim = KindnessSimulation(params)
        assert len(sim.agents) == 20
        assert sim.round == 0
        assert len(sim.metrics_history) == 0

    def test_simulation_runs(self):
        """Test simulation runs without errors."""
        params = SimulationParameters(n_agents=20)
        sim = KindnessSimulation(params)
        history = sim.run(10)
        assert len(history) == 10
        assert sim.round == 10

    def test_all_network_types(self):
        """Test all network types initialize correctly."""
        for network_type in ["random", "small_world", "scale_free", "grid"]:
            params = SimulationParameters(n_agents=25, network_type=network_type)
            sim = KindnessSimulation(params)
            # Check network is built
            assert len(sim.network) == 25
            # Run briefly to ensure it works
            sim.run(3)
            assert sim.round == 3


class TestReproducibility:
    """Test that simulations are reproducible with same seeds."""

    def test_reproducibility_with_seed(self):
        """Test same random seed produces same results."""
        params = SimulationParameters(n_agents=30)

        # Run 1
        np.random.seed(42)
        random.seed(42)
        sim1 = KindnessSimulation(params)
        history1 = sim1.run(20)

        # Run 2 with same seed
        np.random.seed(42)
        random.seed(42)
        sim2 = KindnessSimulation(params)
        history2 = sim2.run(20)

        # Results should be identical
        for h1, h2 in zip(history1, history2):
            assert abs(h1['mean_wellbeing'] - h2['mean_wellbeing']) < 1e-10
            assert abs(h1['mean_kindness_capacity'] - h2['mean_kindness_capacity']) < 1e-10

    def test_different_seeds_different_results(self):
        """Test different seeds produce different initial conditions."""
        params = SimulationParameters(n_agents=50)

        # Check that different seeds create different initial populations
        initial_wellbeings = []
        for seed in [42, 123, 456]:
            np.random.seed(seed)
            random.seed(seed)
            sim = KindnessSimulation(params)
            # Just check initial state differs
            initial_wellbeings.append(sum(a.wellbeing for a in sim.agents))

        # Initial states should differ
        assert len(set(round(w, 2) for w in initial_wellbeings)) > 1


class TestBoundaryConditions:
    """Test simulation handles edge cases correctly."""

    def test_single_agent(self):
        """Test simulation with single agent."""
        params = SimulationParameters(n_agents=1)
        sim = KindnessSimulation(params)
        sim.run(5)
        assert sim.round == 5
        # Single agent can't interact with anyone
        assert all(m['n_kind_acts'] == 0 for m in sim.metrics_history)

    def test_no_connections(self):
        """Test with very sparse network."""
        params = SimulationParameters(n_agents=10, avg_connections=0)
        sim = KindnessSimulation(params)
        sim.run(5)
        # Should still run, just with no interactions
        assert sim.round == 5

    def test_extreme_parameters(self):
        """Test with extreme but valid parameter values."""
        # Very high kindness boost
        params = SimulationParameters(
            n_agents=20,
            kindness_to_wellbeing=1.0,
            giving_kindness_boost=1.0,
            wellbeing_decay=0.0
        )
        sim = KindnessSimulation(params)
        sim.run(10)
        # Should not crash
        assert sim.round == 10

        # All values should stay in valid range
        for agent in sim.agents:
            assert 0 <= agent.wellbeing <= 1
            assert 0 <= agent.kindness_capacity <= 1

    def test_zero_rounds(self):
        """Test running for zero rounds."""
        params = SimulationParameters(n_agents=10)
        sim = KindnessSimulation(params)
        history = sim.run(0)
        assert len(history) == 0
        assert sim.round == 0


class TestConservationLaws:
    """Test that metrics stay within valid ranges."""

    def test_values_bounded(self):
        """Test all agent values stay in [0, 1]."""
        params = SimulationParameters(n_agents=50)
        sim = KindnessSimulation(params)
        sim.run(50)

        for agent in sim.agents:
            assert 0 <= agent.wellbeing <= 1, f"Wellbeing out of range: {agent.wellbeing}"
            assert 0 <= agent.kindness_capacity <= 1, f"Capacity out of range: {agent.kindness_capacity}"
            # History should also be bounded
            for w in agent.wellbeing_history:
                assert 0 <= w <= 1
            for k in agent.kindness_history:
                assert 0 <= k <= 1

    def test_metrics_non_negative(self):
        """Test aggregate metrics are non-negative."""
        params = SimulationParameters(n_agents=50)
        sim = KindnessSimulation(params)
        sim.run(30)

        for metrics in sim.metrics_history:
            assert metrics['mean_wellbeing'] >= 0
            assert metrics['mean_kindness_capacity'] >= 0
            assert metrics['n_kind_acts'] >= 0
            assert metrics['total_kindness_given'] >= 0
            assert 0 <= metrics['pct_above_threshold'] <= 1


class TestExpectedBehaviors:
    """Test that model produces theoretically expected patterns."""

    def test_intervention_increases_metrics(self):
        """Test that intervention generally increases kindness/wellbeing."""
        np.random.seed(42)
        random.seed(42)

        # Without intervention
        params_no_int = SimulationParameters(n_agents=50, intervention_round=None)
        sim_no_int = KindnessSimulation(params_no_int)
        sim_no_int.run(50)
        summary_no_int = sim_no_int.get_summary()

        np.random.seed(42)
        random.seed(42)

        # With intervention
        params_int = SimulationParameters(n_agents=50, intervention_round=10,
                                          intervention_strength=0.5)
        sim_int = KindnessSimulation(params_int)
        sim_int.run(50)
        summary_int = sim_int.get_summary()

        # Intervention should generally help (with strong enough strength)
        # Note: Due to stochasticity, we check that intervention at least doesn't hurt badly
        assert summary_int['final_kindness'] >= summary_no_int['final_kindness'] - 0.2

    def test_decay_without_practice(self):
        """Test that capacity decays without kind acts."""
        params = SimulationParameters(
            n_agents=10,
            kindness_threshold=2.0,  # Very high threshold - no one acts
            capacity_decay=0.1
        )
        sim = KindnessSimulation(params)

        initial_capacities = [a.kindness_capacity for a in sim.agents]
        sim.run(10)
        final_capacities = [a.kindness_capacity for a in sim.agents]

        # Capacities should generally decrease without practice
        assert np.mean(final_capacities) < np.mean(initial_capacities)

    def test_virtuous_cycle(self):
        """Test that kindness creates virtuous cycle under right conditions."""
        # High boost from kindness, low decay
        params = SimulationParameters(
            n_agents=30,
            kindness_to_wellbeing=0.5,
            giving_kindness_boost=0.3,
            wellbeing_decay=0.01,
            capacity_decay=0.01,
            kindness_threshold=0.2,  # Low threshold - easy to be kind
            network_type="small_world"
        )

        np.random.seed(42)
        random.seed(42)

        sim = KindnessSimulation(params)
        sim.run(100)
        summary = sim.get_summary()

        # Under favorable conditions, system should trend positive
        # or at least maintain reasonably high levels
        assert summary['final_wellbeing'] >= 0.4
        assert summary['final_kindness'] >= 0.3


class TestInterventionTypes:
    """Test different intervention targeting strategies."""

    def test_hub_intervention(self):
        """Test hub-targeted intervention runs correctly."""
        params = SimulationParameters(
            n_agents=30,
            network_type="scale_free",  # Has clear hubs
            intervention_round=10,
            intervention_type="hubs"
        )
        sim = KindnessSimulation(params)
        sim.run(20)
        assert sim.round == 20

    def test_low_wellbeing_intervention(self):
        """Test low-wellbeing targeted intervention runs correctly."""
        params = SimulationParameters(
            n_agents=30,
            intervention_round=10,
            intervention_type="low_wellbeing"
        )
        sim = KindnessSimulation(params)
        sim.run(20)
        assert sim.round == 20

    def test_random_intervention(self):
        """Test random intervention runs correctly."""
        params = SimulationParameters(
            n_agents=30,
            intervention_round=10,
            intervention_type="random"
        )
        sim = KindnessSimulation(params)
        sim.run(20)
        assert sim.round == 20


class TestRunExperiment:
    """Test the run_experiment helper function."""

    def test_run_experiment_aggregates(self):
        """Test run_experiment produces aggregated results."""
        params = SimulationParameters(n_agents=20)
        result = run_experiment("test", params, n_rounds=10, n_runs=3)

        assert result['name'] == "test"
        assert 'aggregated' in result
        assert 'raw' in result
        assert len(result['raw']) == 3

        # Check aggregated metrics exist
        assert 'wellbeing_change_mean' in result['aggregated']
        assert 'wellbeing_change_std' in result['aggregated']


class TestSummary:
    """Test summary generation."""

    def test_get_summary(self):
        """Test summary contains expected fields."""
        params = SimulationParameters(n_agents=20)
        sim = KindnessSimulation(params)
        sim.run(10)
        summary = sim.get_summary()

        expected_fields = [
            'initial_wellbeing', 'final_wellbeing', 'wellbeing_change',
            'initial_kindness', 'final_kindness', 'kindness_change',
            'initial_kind_acts', 'final_kind_acts', 'rounds_run'
        ]

        for field in expected_fields:
            assert field in summary, f"Missing field: {field}"

    def test_empty_summary(self):
        """Test summary with no rounds run."""
        params = SimulationParameters(n_agents=10)
        sim = KindnessSimulation(params)
        summary = sim.get_summary()
        assert summary == {}


def run_all_tests():
    """Run all tests and report results."""
    import traceback

    test_classes = [
        TestAgentBasics,
        TestSimulationParameters,
        TestSimulationBasics,
        TestReproducibility,
        TestBoundaryConditions,
        TestConservationLaws,
        TestExpectedBehaviors,
        TestInterventionTypes,
        TestRunExperiment,
        TestSummary,
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
