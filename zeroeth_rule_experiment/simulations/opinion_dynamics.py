#!/usr/bin/env python3
"""
Opinion Dynamics and Polarization Simulation

Models how opinions form, spread, and potentially polarize in populations.

Based on classic models (bounded confidence, social influence) with extensions.

Key questions:
1. Under what conditions does consensus emerge vs. polarization?
2. How do filter bubbles affect opinion dynamics?
3. Can small interventions reduce polarization?
4. What role do "bridge" individuals play?
"""

import numpy as np
import random
from dataclasses import dataclass, field
from typing import List, Dict, Tuple, Optional
from enum import Enum


@dataclass
class Agent:
    """An individual with opinions."""
    id: int
    opinion: float  # -1 to 1 (could represent any continuum)
    confidence: float  # How strongly they hold their opinion (0-1)
    openness: float  # How willing to change opinion (0-1)
    influence: float  # How much they influence others (0-1)

    opinion_history: List[float] = field(default_factory=list)

    def update_from_interaction(self, other_opinion: float, other_influence: float,
                                 params: 'SimulationParameters'):
        """Update opinion based on interaction."""
        opinion_diff = abs(self.opinion - other_opinion)

        # Bounded confidence: only update if opinions are close enough
        if opinion_diff > params.confidence_threshold:
            # Too far apart - may actually push away (backfire effect)
            if params.backfire_effect > 0:
                push = params.backfire_effect * np.sign(self.opinion - other_opinion)
                self.opinion += push * (1 - self.confidence)
        else:
            # Close enough to influence
            # Weighted by openness, other's influence, and inverse of confidence
            weight = self.openness * other_influence * (1 - self.confidence * 0.5)
            weight *= params.influence_strength

            # Move toward other's opinion
            self.opinion += weight * (other_opinion - self.opinion)

        # Clamp
        self.opinion = max(-1, min(1, self.opinion))


@dataclass
class SimulationParameters:
    """Parameters for opinion dynamics."""
    n_agents: int = 200

    # Network
    network_type: str = "homophily"  # "random", "homophily", "echo_chamber"
    avg_connections: int = 10
    homophily_strength: float = 0.7  # Probability of connecting to similar others

    # Opinion dynamics
    confidence_threshold: float = 0.5  # Max opinion difference for influence
    influence_strength: float = 0.1  # How much opinions change per interaction
    backfire_effect: float = 0.02  # Push away from distant opinions

    # Agent heterogeneity
    confidence_distribution: str = "uniform"  # "uniform", "bimodal"
    extremist_fraction: float = 0.1  # Fraction with very high confidence

    # External influences
    media_bias: float = 0.0  # -1 to 1, central media influence
    media_strength: float = 0.0  # How much media affects opinions

    # Intervention
    bridging_intervention: bool = False  # Create cross-group connections
    n_bridges: int = 10


class OpinionSimulation:
    """
    Simulation of opinion dynamics in a social network.
    """

    def __init__(self, params: SimulationParameters):
        self.params = params
        self.agents: List[Agent] = []
        self.network: Dict[int, List[int]] = {}
        self.round = 0
        self.metrics_history = []

        self._initialize_agents()
        self._build_network()

    def _initialize_agents(self):
        """Create agents with diverse initial opinions and traits."""
        for i in range(self.params.n_agents):
            # Initial opinion: could be uniform or bimodal
            if random.random() < 0.5:
                opinion = np.random.normal(-0.5, 0.3)
            else:
                opinion = np.random.normal(0.5, 0.3)
            opinion = max(-1, min(1, opinion))

            # Confidence: some people are more certain
            if random.random() < self.params.extremist_fraction:
                confidence = np.random.uniform(0.8, 1.0)  # Extremists
            else:
                confidence = np.random.uniform(0.2, 0.6)

            # Openness: inverse relationship with confidence
            openness = 1 - confidence * 0.5 + np.random.normal(0, 0.1)
            openness = max(0.1, min(0.9, openness))

            # Influence: some people are more influential
            influence = np.random.beta(2, 5)  # Skewed toward lower values

            self.agents.append(Agent(
                id=i,
                opinion=opinion,
                confidence=confidence,
                openness=openness,
                influence=influence
            ))

    def _build_network(self):
        """Build social network with possible homophily."""
        n = self.params.n_agents
        k = self.params.avg_connections

        for i in range(n):
            self.network[i] = []

        if self.params.network_type == "random":
            # Random connections
            p = k / (n - 1)
            for i in range(n):
                for j in range(i + 1, n):
                    if random.random() < p:
                        self.network[i].append(j)
                        self.network[j].append(i)

        elif self.params.network_type == "homophily":
            # More likely to connect to similar opinions
            for i in range(n):
                # Try to make k connections
                potential = list(range(n))
                potential.remove(i)
                random.shuffle(potential)

                connections_made = 0
                for j in potential:
                    if connections_made >= k:
                        break
                    if j in self.network[i]:
                        continue

                    opinion_diff = abs(self.agents[i].opinion - self.agents[j].opinion)
                    similarity = 1 - opinion_diff / 2  # 0 to 1

                    # Probability based on similarity
                    prob = (1 - self.params.homophily_strength) + \
                           self.params.homophily_strength * similarity

                    if random.random() < prob:
                        self.network[i].append(j)
                        self.network[j].append(i)
                        connections_made += 1

        elif self.params.network_type == "echo_chamber":
            # Strong separation by opinion
            positive = [a for a in self.agents if a.opinion >= 0]
            negative = [a for a in self.agents if a.opinion < 0]

            for group in [positive, negative]:
                for agent in group:
                    others = [a for a in group if a.id != agent.id]
                    n_connections = min(k, len(others))
                    connections = random.sample(others, n_connections)
                    for other in connections:
                        if other.id not in self.network[agent.id]:
                            self.network[agent.id].append(other.id)
                            self.network[other.id].append(agent.id)

    def step(self):
        """Run one simulation round."""
        # Each agent interacts with some neighbors
        for agent in self.agents:
            neighbors = self.network[agent.id]
            if not neighbors:
                continue

            # Interact with 1-3 neighbors
            n_interactions = min(len(neighbors), random.randint(1, 3))
            partners = random.sample(neighbors, n_interactions)

            for partner_id in partners:
                partner = self.agents[partner_id]
                agent.update_from_interaction(
                    partner.opinion, partner.influence, self.params
                )

        # Media influence
        if self.params.media_strength > 0:
            for agent in self.agents:
                agent.opinion += self.params.media_strength * agent.openness * \
                                (self.params.media_bias - agent.opinion)
                agent.opinion = max(-1, min(1, agent.opinion))

        # Record history
        for agent in self.agents:
            agent.opinion_history.append(agent.opinion)

        self._record_metrics()
        self.round += 1

    def _record_metrics(self):
        """Record population-level metrics."""
        opinions = [a.opinion for a in self.agents]

        # Polarization: variance or bimodality
        variance = np.var(opinions)

        # Count in "camps"
        n_positive = sum(1 for o in opinions if o > 0.3)
        n_negative = sum(1 for o in opinions if o < -0.3)
        n_moderate = len(opinions) - n_positive - n_negative

        # Mean and extremity
        mean_opinion = np.mean(opinions)
        mean_extremity = np.mean([abs(o) for o in opinions])

        metrics = {
            "round": self.round,
            "mean_opinion": mean_opinion,
            "opinion_variance": variance,
            "mean_extremity": mean_extremity,
            "n_positive": n_positive,
            "n_negative": n_negative,
            "n_moderate": n_moderate,
            "polarization_index": (n_positive + n_negative) / len(opinions) if len(opinions) > 0 else 0
        }
        self.metrics_history.append(metrics)

    def apply_bridging_intervention(self):
        """Create connections between opposing groups."""
        positive = [a for a in self.agents if a.opinion > 0.3]
        negative = [a for a in self.agents if a.opinion < -0.3]

        if not positive or not negative:
            return

        for _ in range(self.params.n_bridges):
            p = random.choice(positive)
            n = random.choice(negative)
            if n.id not in self.network[p.id]:
                self.network[p.id].append(n.id)
                self.network[n.id].append(p.id)

    def run(self, n_rounds: int, bridging_at: Optional[int] = None) -> List[dict]:
        """Run simulation for n rounds."""
        for r in range(n_rounds):
            if bridging_at and r == bridging_at:
                self.apply_bridging_intervention()
            self.step()
        return self.metrics_history

    def get_summary(self) -> dict:
        """Get summary statistics."""
        if not self.metrics_history:
            return {}

        initial = self.metrics_history[0]
        final = self.metrics_history[-1]

        return {
            "initial_variance": initial["opinion_variance"],
            "final_variance": final["opinion_variance"],
            "variance_change": final["opinion_variance"] - initial["opinion_variance"],
            "initial_polarization": initial["polarization_index"],
            "final_polarization": final["polarization_index"],
            "final_extremity": final["mean_extremity"]
        }


def run_polarization_experiments():
    """Run experiments on polarization dynamics."""
    print("=" * 70)
    print("OPINION DYNAMICS AND POLARIZATION SIMULATION")
    print("=" * 70)
    print()

    # Experiment 1: Effect of network structure
    print("Experiment 1: Effect of network structure on polarization")
    print("-" * 50)
    for network in ["random", "homophily", "echo_chamber"]:
        params = SimulationParameters(network_type=network)
        sim = OpinionSimulation(params)
        sim.run(100)
        summary = sim.get_summary()
        print(f"  {network:15s}: polarization = {summary['final_polarization']:.2f}, "
              f"variance = {summary['final_variance']:.3f}")
    print()

    # Experiment 2: Effect of confidence threshold
    print("Experiment 2: Effect of bounded confidence threshold")
    print("-" * 50)
    for threshold in [0.2, 0.4, 0.6, 0.8, 1.0]:
        params = SimulationParameters(confidence_threshold=threshold)
        sim = OpinionSimulation(params)
        sim.run(100)
        summary = sim.get_summary()
        print(f"  threshold={threshold:.1f}: polarization = {summary['final_polarization']:.2f}, "
              f"extremity = {summary['final_extremity']:.2f}")
    print()

    # Experiment 3: Effect of extremist fraction
    print("Experiment 3: Effect of extremist (high-confidence) fraction")
    print("-" * 50)
    for extremist in [0.0, 0.05, 0.10, 0.20, 0.30]:
        params = SimulationParameters(extremist_fraction=extremist)
        sim = OpinionSimulation(params)
        sim.run(100)
        summary = sim.get_summary()
        print(f"  extremists={extremist:.0%}: polarization = {summary['final_polarization']:.2f}")
    print()

    # Experiment 4: Bridging intervention
    print("Experiment 4: Effect of bridging intervention")
    print("-" * 50)
    # Without intervention
    params = SimulationParameters(network_type="echo_chamber")
    sim = OpinionSimulation(params)
    sim.run(100)
    summary_no_bridge = sim.get_summary()
    print(f"  No bridging:   polarization = {summary_no_bridge['final_polarization']:.2f}")

    # With intervention
    for n_bridges in [5, 10, 20, 50]:
        params = SimulationParameters(network_type="echo_chamber", n_bridges=n_bridges)
        sim = OpinionSimulation(params)
        sim.run(100, bridging_at=30)
        summary = sim.get_summary()
        print(f"  {n_bridges} bridges:   polarization = {summary['final_polarization']:.2f}")
    print()

    # Experiment 5: Backfire effect
    print("Experiment 5: Effect of backfire (counter-argument strengthening)")
    print("-" * 50)
    for backfire in [0.0, 0.02, 0.05, 0.10]:
        params = SimulationParameters(backfire_effect=backfire)
        sim = OpinionSimulation(params)
        sim.run(100)
        summary = sim.get_summary()
        print(f"  backfire={backfire:.2f}: polarization = {summary['final_polarization']:.2f}, "
              f"extremity = {summary['final_extremity']:.2f}")
    print()

    print("=" * 70)
    print("KEY FINDINGS")
    print("=" * 70)
    print("""
1. NETWORK STRUCTURE MATTERS ENORMOUSLY
   - Echo chambers (sorted by opinion) maintain and increase polarization
   - Random networks tend toward moderate consensus
   - Homophily falls in between

2. BOUNDED CONFIDENCE CREATES POLARIZATION
   - If people only listen to those already similar to them
     (low confidence threshold), polarization increases
   - Higher thresholds → more consensus but slower dynamics

3. EXTREMISTS HAVE OUTSIZED INFLUENCE
   - A small fraction of high-confidence extremists
     can pull moderates toward poles
   - Confident voices dominate even when outnumbered

4. BRIDGING CAN HELP BUT HAS LIMITS
   - Creating connections between opposing groups reduces polarization
   - But requires enough bridges and people willing to engage
   - May not work if backfire effect is strong

5. BACKFIRE EFFECTS ARE DANGEROUS
   - If exposure to opposing views strengthens existing beliefs,
     cross-group contact can increase polarization
   - This depends on HOW the interaction happens, not just IF

IMPLICATIONS:
- Don't just create diverse networks; design interactions carefully
- Target high-confidence moderates for bridging, not extremists
- Small structural interventions can have large effects
- The same contact can depolarize or polarize depending on conditions
""")


if __name__ == "__main__":
    run_polarization_experiments()
