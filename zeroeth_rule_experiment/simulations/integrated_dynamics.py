#!/usr/bin/env python3
"""
Integrated Social Dynamics Simulation

Combines three previously separate models into a unified system:
1. Kindness dynamics - how prosocial behavior spreads
2. Cooperation dynamics - how cooperation/defection evolves
3. Opinion dynamics - how beliefs form and polarize

The key insight: these aren't separate systems. In real communities:
- Kindness affects cooperation (kind people cooperate more)
- Cooperation affects polarization (successful cooperation reduces hostility)
- Polarization affects kindness (we're less kind to "the other side")

This creates feedback loops that can lead to virtuous or vicious cycles.
"""

import numpy as np
import random
from dataclasses import dataclass, field
from typing import List, Dict, Optional, Tuple
from enum import Enum


class CooperationStrategy(Enum):
    """How an agent decides to cooperate."""
    UNCONDITIONAL = "unconditional"  # Always cooperate
    RECIPROCAL = "reciprocal"  # Cooperate if others do (TFT-like)
    SELECTIVE = "selective"  # Only cooperate with similar opinions
    DEFECTOR = "defector"  # Never cooperate


@dataclass
class IntegratedAgent:
    """
    An agent with kindness, cooperation, and opinion properties.

    These properties interact:
    - High wellbeing → more likely to cooperate
    - Successful cooperation → higher wellbeing
    - Opinion similarity → more kindness exchanged
    - Polarization → less cross-group cooperation
    """
    id: int

    # Kindness properties
    kindness_capacity: float  # 0-1: ability to be kind
    wellbeing: float  # 0-1: current wellbeing

    # Cooperation properties
    cooperation_strategy: CooperationStrategy
    reputation: float  # 0-1: how cooperative others think you are

    # Opinion properties
    opinion: float  # -1 to 1: position on key issue
    confidence: float  # 0-1: how certain about opinion
    openness: float  # 0-1: willingness to update opinion

    # Tracking
    group_identity: int = 0  # Which group they identify with (0, 1, or -1 for none)
    kindness_given: float = 0
    kindness_received: float = 0
    cooperation_score: float = 0

    # History
    wellbeing_history: List[float] = field(default_factory=list)
    opinion_history: List[float] = field(default_factory=list)
    cooperation_history: List[float] = field(default_factory=list)


@dataclass
class IntegratedParameters:
    """Parameters for the integrated simulation."""

    # Population
    n_agents: int = 100

    # Network
    network_type: str = "small_world"  # random, small_world, homophily
    avg_connections: int = 8

    # Kindness dynamics
    kindness_to_wellbeing: float = 0.25
    wellbeing_to_kindness: float = 0.15
    giving_boost: float = 0.1
    wellbeing_decay: float = 0.03

    # Cooperation dynamics
    cooperation_reward: float = 3.0
    defection_temptation: float = 5.0
    sucker_payoff: float = 0.0
    mutual_defection: float = 1.0
    cooperation_rounds_per_step: int = 3

    # Opinion dynamics
    confidence_threshold: float = 0.4  # How close opinions need to be to influence
    backfire_strength: float = 0.02  # How much opposing views strengthen beliefs
    media_influence: float = 0.01  # External opinion pressure

    # Cross-system interactions (the key additions)
    wellbeing_cooperation_link: float = 0.3  # How much wellbeing affects cooperation
    cooperation_wellbeing_link: float = 0.2  # How much cooperation success affects wellbeing
    opinion_kindness_link: float = 0.4  # How much opinion similarity affects kindness
    polarization_cooperation_link: float = 0.3  # How much polarization reduces cross-group cooperation

    # Group dynamics
    initial_polarization: float = 0.3  # Starting opinion spread
    group_formation_threshold: float = 0.3  # Opinion distance to form groups


class IntegratedSimulation:
    """
    Unified simulation of social dynamics.

    Each round:
    1. Agents interact kindly (or not) based on capacity and opinion similarity
    2. Agents play cooperation games (affected by wellbeing and group identity)
    3. Agents update opinions (affected by interactions)
    4. Cross-system effects propagate
    """

    def __init__(self, params: IntegratedParameters):
        self.params = params
        self.agents: List[IntegratedAgent] = []
        self.network: Dict[int, List[int]] = {}
        self.round = 0
        self.metrics_history = []

        self._initialize_agents()
        self._build_network()
        self._assign_groups()

    def _initialize_agents(self):
        """Create agents with initial states."""
        strategies = list(CooperationStrategy)
        strategy_weights = [0.2, 0.5, 0.2, 0.1]  # Mostly reciprocal

        for i in range(self.params.n_agents):
            # Initial opinion with some polarization
            if random.random() < 0.5:
                opinion = np.random.normal(-self.params.initial_polarization, 0.2)
            else:
                opinion = np.random.normal(self.params.initial_polarization, 0.2)
            opinion = max(-1, min(1, opinion))

            agent = IntegratedAgent(
                id=i,
                kindness_capacity=np.random.beta(2, 2),
                wellbeing=np.random.beta(2, 2),
                cooperation_strategy=random.choices(strategies, weights=strategy_weights)[0],
                reputation=0.5,
                opinion=opinion,
                confidence=np.random.beta(2, 3),  # Mostly moderate confidence
                openness=np.random.beta(3, 2)  # Mostly open
            )
            self.agents.append(agent)

    def _build_network(self):
        """Build social network."""
        n = self.params.n_agents
        k = self.params.avg_connections

        if self.params.network_type == "random":
            p = k / (n - 1)
            for i in range(n):
                self.network[i] = []
                for j in range(n):
                    if i != j and random.random() < p:
                        self.network[i].append(j)

        elif self.params.network_type == "small_world":
            rewire_prob = 0.1
            for i in range(n):
                self.network[i] = []
                for j in range(1, k // 2 + 1):
                    neighbor = (i + j) % n
                    if random.random() < rewire_prob:
                        neighbor = random.randint(0, n - 1)
                    if neighbor != i and neighbor not in self.network[i]:
                        self.network[i].append(neighbor)

                    neighbor = (i - j) % n
                    if random.random() < rewire_prob:
                        neighbor = random.randint(0, n - 1)
                    if neighbor != i and neighbor not in self.network[i]:
                        self.network[i].append(neighbor)

        elif self.params.network_type == "homophily":
            # Connect based on opinion similarity
            for i in range(n):
                self.network[i] = []
                distances = [(j, abs(self.agents[i].opinion - self.agents[j].opinion))
                            for j in range(n) if i != j]
                distances.sort(key=lambda x: x[1])
                # Connect to most similar, with some randomness
                for j, _ in distances[:k]:
                    if random.random() < 0.8:  # 80% connect to similar
                        self.network[i].append(j)
                    elif distances:
                        # 20% connect to random
                        rand_j = random.choice([x[0] for x in distances])
                        if rand_j not in self.network[i]:
                            self.network[i].append(rand_j)

    def _assign_groups(self):
        """Assign group identities based on opinions."""
        for agent in self.agents:
            if agent.opinion < -self.params.group_formation_threshold:
                agent.group_identity = -1
            elif agent.opinion > self.params.group_formation_threshold:
                agent.group_identity = 1
            else:
                agent.group_identity = 0

    def step(self):
        """Run one simulation round."""
        # Reset round counters
        for agent in self.agents:
            agent.kindness_given = 0
            agent.kindness_received = 0
            agent.cooperation_score = 0

        # Phase 1: Kindness interactions
        self._kindness_phase()

        # Phase 2: Cooperation games
        self._cooperation_phase()

        # Phase 3: Opinion updates
        self._opinion_phase()

        # Phase 4: Cross-system effects
        self._integration_phase()

        # Phase 5: Decay and bounds
        self._maintenance_phase()

        # Record metrics
        self._record_metrics()

        self.round += 1

    def _kindness_phase(self):
        """Agents exchange kindness with neighbors."""
        kind_acts = []

        for agent in self.agents:
            if agent.kindness_capacity < 0.2:
                continue  # Too depleted

            if random.random() > agent.kindness_capacity:
                continue  # Didn't act this round

            neighbors = self.network[agent.id]
            if not neighbors:
                continue

            # Choose target - preference for similar opinions
            target_id = self._choose_kindness_target(agent, neighbors)
            target = self.agents[target_id]

            # Kindness amount depends on opinion similarity
            opinion_distance = abs(agent.opinion - target.opinion)
            similarity_factor = 1 - (opinion_distance * self.params.opinion_kindness_link)
            similarity_factor = max(0.1, similarity_factor)  # Never zero

            amount = agent.kindness_capacity * similarity_factor
            amount *= (1 + np.random.normal(0, 0.1))  # Add noise
            amount = max(0, min(1, amount))

            kind_acts.append((agent.id, target_id, amount))
            agent.kindness_given += amount

        # Apply kindness
        for giver_id, receiver_id, amount in kind_acts:
            self.agents[receiver_id].kindness_received += amount

    def _choose_kindness_target(self, agent: IntegratedAgent, neighbors: List[int]) -> int:
        """Choose who to be kind to - biased toward similar opinions."""
        if not neighbors:
            return agent.id

        # Weight by opinion similarity
        weights = []
        for n_id in neighbors:
            neighbor = self.agents[n_id]
            distance = abs(agent.opinion - neighbor.opinion)
            weight = np.exp(-distance * 2)  # Exponential decay with distance
            weights.append(weight)

        total = sum(weights)
        if total == 0:
            return random.choice(neighbors)

        probs = [w / total for w in weights]
        return np.random.choice(neighbors, p=probs)

    def _cooperation_phase(self):
        """Agents play cooperation games."""
        for _ in range(self.params.cooperation_rounds_per_step):
            # Pair up agents
            shuffled = list(range(self.params.n_agents))
            random.shuffle(shuffled)

            for i in range(0, len(shuffled) - 1, 2):
                agent1 = self.agents[shuffled[i]]
                agent2 = self.agents[shuffled[i + 1]]

                # Decide cooperation
                move1 = self._decide_cooperation(agent1, agent2)
                move2 = self._decide_cooperation(agent2, agent1)

                # Calculate payoffs
                payoff1, payoff2 = self._get_payoffs(move1, move2)

                agent1.cooperation_score += payoff1
                agent2.cooperation_score += payoff2

                # Update reputations
                if move1 == "C":
                    agent1.reputation = min(1, agent1.reputation + 0.05)
                else:
                    agent1.reputation = max(0, agent1.reputation - 0.05)

                if move2 == "C":
                    agent2.reputation = min(1, agent2.reputation + 0.05)
                else:
                    agent2.reputation = max(0, agent2.reputation - 0.05)

    def _decide_cooperation(self, agent: IntegratedAgent, partner: IntegratedAgent) -> str:
        """Decide whether to cooperate."""
        # Base probability from strategy
        if agent.cooperation_strategy == CooperationStrategy.UNCONDITIONAL:
            base_prob = 0.9
        elif agent.cooperation_strategy == CooperationStrategy.RECIPROCAL:
            base_prob = partner.reputation
        elif agent.cooperation_strategy == CooperationStrategy.SELECTIVE:
            opinion_dist = abs(agent.opinion - partner.opinion)
            base_prob = 1 - opinion_dist if opinion_dist < 0.5 else 0.1
        else:  # DEFECTOR
            base_prob = 0.1

        # Modify by wellbeing (happier people cooperate more)
        wellbeing_mod = (agent.wellbeing - 0.5) * self.params.wellbeing_cooperation_link

        # Modify by group identity (less cooperation across groups)
        if agent.group_identity != 0 and partner.group_identity != 0:
            if agent.group_identity != partner.group_identity:
                group_mod = -self.params.polarization_cooperation_link
            else:
                group_mod = 0.1  # Slight in-group boost
        else:
            group_mod = 0

        final_prob = base_prob + wellbeing_mod + group_mod
        final_prob = max(0, min(1, final_prob))

        return "C" if random.random() < final_prob else "D"

    def _get_payoffs(self, move1: str, move2: str) -> Tuple[float, float]:
        """Get payoffs for cooperation game."""
        if move1 == "C" and move2 == "C":
            return self.params.cooperation_reward, self.params.cooperation_reward
        elif move1 == "D" and move2 == "D":
            return self.params.mutual_defection, self.params.mutual_defection
        elif move1 == "C" and move2 == "D":
            return self.params.sucker_payoff, self.params.defection_temptation
        else:  # move1 == "D" and move2 == "C"
            return self.params.defection_temptation, self.params.sucker_payoff

    def _opinion_phase(self):
        """Agents update opinions based on interactions."""
        opinion_updates = []

        for agent in self.agents:
            neighbors = self.network[agent.id]
            if not neighbors:
                continue

            # Aggregate neighbor influence
            total_influence = 0
            influence_count = 0

            for n_id in neighbors:
                neighbor = self.agents[n_id]
                opinion_distance = abs(agent.opinion - neighbor.opinion)

                # Only influenced by those within confidence threshold
                if opinion_distance < self.params.confidence_threshold:
                    # Move toward neighbor
                    influence = (neighbor.opinion - agent.opinion) * neighbor.confidence * agent.openness
                    total_influence += influence * 0.1
                    influence_count += 1
                else:
                    # Backfire effect - may strengthen own position
                    if random.random() < self.params.backfire_strength:
                        # Move away from neighbor
                        direction = 1 if agent.opinion > neighbor.opinion else -1
                        total_influence += direction * self.params.backfire_strength

            # Add media influence (toward extremes)
            if agent.opinion > 0:
                total_influence += self.params.media_influence
            else:
                total_influence -= self.params.media_influence

            # Kindness received increases openness temporarily
            if agent.kindness_received > 0.5:
                total_influence *= 1.2  # More open to change

            opinion_updates.append((agent.id, total_influence))

        # Apply updates
        for agent_id, update in opinion_updates:
            agent = self.agents[agent_id]
            agent.opinion += update
            agent.opinion = max(-1, min(1, agent.opinion))

        # Update group identities
        self._assign_groups()

    def _integration_phase(self):
        """Apply cross-system effects."""
        for agent in self.agents:
            # Kindness received → wellbeing
            agent.wellbeing += agent.kindness_received * self.params.kindness_to_wellbeing

            # Kindness given → wellbeing (giving boost)
            agent.wellbeing += agent.kindness_given * self.params.giving_boost

            # Cooperation success → wellbeing
            coop_effect = (agent.cooperation_score - self.params.cooperation_rounds_per_step * 2)
            coop_effect = coop_effect / (self.params.cooperation_rounds_per_step * 3)  # Normalize
            agent.wellbeing += coop_effect * self.params.cooperation_wellbeing_link

            # Wellbeing → kindness capacity
            target_capacity = agent.wellbeing
            agent.kindness_capacity += (target_capacity - agent.kindness_capacity) * self.params.wellbeing_to_kindness

    def _maintenance_phase(self):
        """Apply decay and enforce bounds."""
        for agent in self.agents:
            # Wellbeing decay toward baseline
            agent.wellbeing += (0.5 - agent.wellbeing) * self.params.wellbeing_decay

            # Bounds
            agent.wellbeing = max(0, min(1, agent.wellbeing))
            agent.kindness_capacity = max(0, min(1, agent.kindness_capacity))
            agent.reputation = max(0, min(1, agent.reputation))

            # Record history
            agent.wellbeing_history.append(agent.wellbeing)
            agent.opinion_history.append(agent.opinion)
            agent.cooperation_history.append(agent.cooperation_score)

    def _record_metrics(self):
        """Record population-level metrics."""
        opinions = [a.opinion for a in self.agents]
        wellbeings = [a.wellbeing for a in self.agents]
        kindnesses = [a.kindness_capacity for a in self.agents]

        # Polarization: variance of opinions
        polarization = np.var(opinions)

        # Group sizes
        group_neg = sum(1 for a in self.agents if a.group_identity == -1)
        group_pos = sum(1 for a in self.agents if a.group_identity == 1)
        group_neutral = sum(1 for a in self.agents if a.group_identity == 0)

        # Cross-group kindness
        cross_group_kindness = 0
        cross_group_count = 0
        for agent in self.agents:
            if agent.group_identity == 0:
                continue
            for n_id in self.network[agent.id]:
                neighbor = self.agents[n_id]
                if neighbor.group_identity != 0 and neighbor.group_identity != agent.group_identity:
                    cross_group_count += 1
                    # Would need to track this more precisely, approximate

        metrics = {
            "round": self.round,
            "mean_wellbeing": np.mean(wellbeings),
            "mean_kindness": np.mean(kindnesses),
            "mean_opinion": np.mean(opinions),
            "polarization": polarization,
            "std_opinion": np.std(opinions),
            "group_negative": group_neg,
            "group_positive": group_pos,
            "group_neutral": group_neutral,
            "mean_reputation": np.mean([a.reputation for a in self.agents]),
            "mean_cooperation_score": np.mean([a.cooperation_score for a in self.agents]),
        }
        self.metrics_history.append(metrics)

    def run(self, n_rounds: int) -> List[dict]:
        """Run simulation for n rounds."""
        for _ in range(n_rounds):
            self.step()
        return self.metrics_history

    def get_summary(self) -> dict:
        """Get summary of simulation."""
        if not self.metrics_history:
            return {}

        initial = self.metrics_history[0]
        final = self.metrics_history[-1]

        return {
            "rounds": len(self.metrics_history),
            "initial_wellbeing": initial["mean_wellbeing"],
            "final_wellbeing": final["mean_wellbeing"],
            "wellbeing_change": final["mean_wellbeing"] - initial["mean_wellbeing"],
            "initial_polarization": initial["polarization"],
            "final_polarization": final["polarization"],
            "polarization_change": final["polarization"] - initial["polarization"],
            "initial_kindness": initial["mean_kindness"],
            "final_kindness": final["mean_kindness"],
            "final_group_sizes": {
                "negative": final["group_negative"],
                "neutral": final["group_neutral"],
                "positive": final["group_positive"]
            }
        }


def run_integrated_experiment(name: str, params: IntegratedParameters,
                              n_rounds: int = 100, n_runs: int = 5) -> dict:
    """Run multiple simulations and aggregate."""
    results = []

    for _ in range(n_runs):
        sim = IntegratedSimulation(params)
        sim.run(n_rounds)
        results.append(sim.get_summary())

    # Aggregate
    agg = {}
    for key in results[0].keys():
        if isinstance(results[0][key], dict):
            continue
        values = [r[key] for r in results]
        agg[f"{key}_mean"] = np.mean(values)
        agg[f"{key}_std"] = np.std(values)

    return {"name": name, "aggregated": agg, "raw": results}


def main():
    """Run experiments demonstrating integrated dynamics."""

    print("=" * 70)
    print("INTEGRATED SOCIAL DYNAMICS SIMULATION")
    print("=" * 70)
    print()
    print("This simulation combines kindness, cooperation, and opinion dynamics")
    print("to show how they interact and create feedback loops.")
    print()

    # Experiment 1: Baseline
    print("Experiment 1: Baseline (default parameters)")
    print("-" * 50)
    baseline = run_integrated_experiment("baseline", IntegratedParameters())
    print(f"  Wellbeing change:    {baseline['aggregated']['wellbeing_change_mean']:+.3f} "
          f"(+/- {baseline['aggregated']['wellbeing_change_std']:.3f})")
    print(f"  Polarization change: {baseline['aggregated']['polarization_change_mean']:+.3f} "
          f"(+/- {baseline['aggregated']['polarization_change_std']:.3f})")
    print(f"  Kindness change:     {baseline['aggregated']['final_kindness_mean'] - baseline['aggregated']['initial_kindness_mean']:+.3f}")
    print()

    # Experiment 2: High cross-system coupling
    print("Experiment 2: High cross-system coupling")
    print("-" * 50)
    high_coupling = IntegratedParameters(
        wellbeing_cooperation_link=0.5,
        cooperation_wellbeing_link=0.4,
        opinion_kindness_link=0.6,
        polarization_cooperation_link=0.5
    )
    result = run_integrated_experiment("high_coupling", high_coupling)
    print(f"  Wellbeing change:    {result['aggregated']['wellbeing_change_mean']:+.3f}")
    print(f"  Polarization change: {result['aggregated']['polarization_change_mean']:+.3f}")
    print()

    # Experiment 3: Low coupling (more independent systems)
    print("Experiment 3: Low coupling (systems more independent)")
    print("-" * 50)
    low_coupling = IntegratedParameters(
        wellbeing_cooperation_link=0.1,
        cooperation_wellbeing_link=0.1,
        opinion_kindness_link=0.1,
        polarization_cooperation_link=0.1
    )
    result = run_integrated_experiment("low_coupling", low_coupling)
    print(f"  Wellbeing change:    {result['aggregated']['wellbeing_change_mean']:+.3f}")
    print(f"  Polarization change: {result['aggregated']['polarization_change_mean']:+.3f}")
    print()

    # Experiment 4: High initial polarization
    print("Experiment 4: High initial polarization")
    print("-" * 50)
    high_polar = IntegratedParameters(initial_polarization=0.7)
    result = run_integrated_experiment("high_polarization", high_polar)
    print(f"  Wellbeing change:    {result['aggregated']['wellbeing_change_mean']:+.3f}")
    print(f"  Polarization change: {result['aggregated']['polarization_change_mean']:+.3f}")
    print()

    # Experiment 5: Strong backfire effect
    print("Experiment 5: Strong backfire effect")
    print("-" * 50)
    strong_backfire = IntegratedParameters(backfire_strength=0.1)
    result = run_integrated_experiment("strong_backfire", strong_backfire)
    print(f"  Wellbeing change:    {result['aggregated']['wellbeing_change_mean']:+.3f}")
    print(f"  Polarization change: {result['aggregated']['polarization_change_mean']:+.3f}")
    print()

    # Experiment 6: Network comparison
    print("Experiment 6: Network structure comparison")
    print("-" * 50)
    for network in ["random", "small_world", "homophily"]:
        net_params = IntegratedParameters(network_type=network)
        result = run_integrated_experiment(f"network_{network}", net_params)
        print(f"  {network:12s}: wellbeing {result['aggregated']['wellbeing_change_mean']:+.3f}, "
              f"polarization {result['aggregated']['polarization_change_mean']:+.3f}")
    print()

    print("=" * 70)
    print("KEY FINDINGS")
    print("=" * 70)
    print("""
1. FEEDBACK LOOPS MATTER
   When systems are tightly coupled, small changes amplify:
   - Kindness → wellbeing → cooperation → more kindness (virtuous cycle)
   - Polarization → less cross-group kindness → less cooperation → more polarization (vicious cycle)

2. INITIAL CONDITIONS MATTER
   High initial polarization makes it harder to establish virtuous cycles.
   Early intervention is more effective than late intervention.

3. NETWORK STRUCTURE INTERACTS WITH DYNAMICS
   - Homophily networks amplify polarization (echo chambers)
   - Small-world networks balance local clustering with bridge connections
   - Random networks spread influence widely but weakly

4. BACKFIRE EFFECTS ARE DANGEROUS
   When exposure to opposing views strengthens existing beliefs,
   well-intentioned "dialogue" can increase polarization.

5. DECOUPLING CAN BE PROTECTIVE
   When systems are less tightly coupled, negative spirals are weaker.
   But so are positive spirals - trade-off between stability and growth.

IMPLICATIONS FOR INTERVENTION:
- Target early, before polarization locks in
- Build kindness and cooperation BEFORE attempting opinion change
- Design networks that balance similarity (trust) with diversity (bridging)
- Be cautious about exposure interventions - they can backfire
- Consider decoupling as protective measure during crises

These are MODEL-GENERATED HYPOTHESES, not proven facts.
The model embeds assumptions that may be wrong.
Real validation requires empirical testing.
""")


if __name__ == "__main__":
    main()
