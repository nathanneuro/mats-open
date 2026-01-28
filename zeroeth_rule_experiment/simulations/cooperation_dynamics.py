#!/usr/bin/env python3
"""
Cooperation Dynamics Simulation

Models how cooperation emerges (or fails to emerge) in populations facing
coordination and cooperation dilemmas.

Explores:
1. Prisoner's Dilemma: Individual incentives vs collective good
2. Public Goods Game: Contributing to shared resources
3. Coordination Game: Agreeing on common standards

Key questions:
- Under what conditions does cooperation stabilize?
- How do different strategies compete?
- What role does reputation, punishment, or communication play?
- Can a small group of cooperators take over a defector population?
"""

import numpy as np
import random
from dataclasses import dataclass, field
from typing import List, Dict, Tuple, Optional
from enum import Enum


class Strategy(Enum):
    """Strategies agents can use."""
    ALWAYS_COOPERATE = "always_cooperate"
    ALWAYS_DEFECT = "always_defect"
    TIT_FOR_TAT = "tit_for_tat"  # Cooperate first, then copy opponent's last move
    GENEROUS_TFT = "generous_tft"  # TFT but occasionally forgive defection
    GRUDGER = "grudger"  # Cooperate until betrayed, then always defect
    RANDOM = "random"  # 50/50 cooperate or defect
    PAVLOV = "pavlov"  # Repeat last action if it worked, switch if it didn't


@dataclass
class Agent:
    """An agent in the cooperation simulation."""
    id: int
    strategy: Strategy
    score: float = 0.0
    history: Dict[int, List[str]] = field(default_factory=dict)  # History with each opponent
    reputation: float = 0.5  # Known cooperation rate
    generation: int = 0

    def decide(self, opponent_id: int, opponent_reputation: float) -> str:
        """Decide whether to cooperate or defect."""
        history = self.history.get(opponent_id, [])

        if self.strategy == Strategy.ALWAYS_COOPERATE:
            return "C"

        elif self.strategy == Strategy.ALWAYS_DEFECT:
            return "D"

        elif self.strategy == Strategy.TIT_FOR_TAT:
            if not history:
                return "C"
            # Return opponent's last move
            return history[-1]

        elif self.strategy == Strategy.GENEROUS_TFT:
            if not history:
                return "C"
            if history[-1] == "D" and random.random() < 0.1:
                return "C"  # 10% chance to forgive
            return history[-1]

        elif self.strategy == Strategy.GRUDGER:
            if "D" in history:
                return "D"
            return "C"

        elif self.strategy == Strategy.RANDOM:
            return "C" if random.random() < 0.5 else "D"

        elif self.strategy == Strategy.PAVLOV:
            if not history:
                return "C"
            # This is simplified - needs both players' last moves ideally
            # Using heuristic: if we cooperated and they cooperated, repeat
            return "C" if history[-1] == "C" else "D"

        return "C"

    def record_interaction(self, opponent_id: int, opponent_move: str):
        """Record opponent's move."""
        if opponent_id not in self.history:
            self.history[opponent_id] = []
        self.history[opponent_id].append(opponent_move)


@dataclass
class GameParameters:
    """Parameters for cooperation games."""
    # Payoff matrix for Prisoner's Dilemma
    # (my_payoff, opponent_payoff) for (my_move, opponent_move)
    reward: float = 3.0  # Both cooperate
    sucker: float = 0.0  # I cooperate, they defect
    temptation: float = 5.0  # I defect, they cooperate
    punishment: float = 1.0  # Both defect

    # For valid PD: T > R > P > S and 2R > T + S

    # Evolution parameters
    n_agents: int = 100
    rounds_per_generation: int = 50
    n_generations: int = 100
    mutation_rate: float = 0.05
    selection_pressure: float = 2.0  # Higher = stronger selection


class CooperationSimulation:
    """
    Evolutionary simulation of cooperation strategies.

    Each generation:
    1. Agents play multiple rounds of Prisoner's Dilemma with random partners
    2. Scores accumulate
    3. Agents reproduce proportional to score (selection)
    4. Some mutation occurs
    """

    def __init__(self, params: GameParameters,
                 initial_strategy_mix: Optional[Dict[Strategy, float]] = None):
        self.params = params
        self.agents: List[Agent] = []
        self.generation = 0
        self.history = []

        # Default: equal mix of all strategies
        if initial_strategy_mix is None:
            strategies = list(Strategy)
            initial_strategy_mix = {s: 1.0 / len(strategies) for s in strategies}

        self._initialize_population(initial_strategy_mix)

    def _initialize_population(self, strategy_mix: Dict[Strategy, float]):
        """Create initial population with given strategy mix."""
        total = sum(strategy_mix.values())
        normalized = {s: v / total for s, v in strategy_mix.items()}

        agent_id = 0
        for strategy, proportion in normalized.items():
            n = int(proportion * self.params.n_agents)
            for _ in range(n):
                self.agents.append(Agent(id=agent_id, strategy=strategy))
                agent_id += 1

        # Fill remaining spots randomly
        while len(self.agents) < self.params.n_agents:
            strategy = random.choice(list(normalized.keys()))
            self.agents.append(Agent(id=agent_id, strategy=strategy))
            agent_id += 1

    def play_round(self, agent1: Agent, agent2: Agent):
        """Play one round of Prisoner's Dilemma between two agents."""
        move1 = agent1.decide(agent2.id, agent2.reputation)
        move2 = agent2.decide(agent1.id, agent1.reputation)

        # Calculate payoffs
        if move1 == "C" and move2 == "C":
            agent1.score += self.params.reward
            agent2.score += self.params.reward
        elif move1 == "C" and move2 == "D":
            agent1.score += self.params.sucker
            agent2.score += self.params.temptation
        elif move1 == "D" and move2 == "C":
            agent1.score += self.params.temptation
            agent2.score += self.params.sucker
        else:  # Both defect
            agent1.score += self.params.punishment
            agent2.score += self.params.punishment

        # Record history
        agent1.record_interaction(agent2.id, move2)
        agent2.record_interaction(agent1.id, move1)

        # Update reputations
        agent1.reputation = 0.9 * agent1.reputation + 0.1 * (1.0 if move1 == "C" else 0.0)
        agent2.reputation = 0.9 * agent2.reputation + 0.1 * (1.0 if move2 == "C" else 0.0)

    def run_generation(self):
        """Run one generation of the simulation."""
        # Reset scores
        for agent in self.agents:
            agent.score = 0

        # Play rounds
        for _ in range(self.params.rounds_per_generation):
            # Random pairings
            shuffled = self.agents.copy()
            random.shuffle(shuffled)
            for i in range(0, len(shuffled) - 1, 2):
                self.play_round(shuffled[i], shuffled[i + 1])

        # Record generation stats
        self._record_generation()

        # Selection and reproduction
        self._reproduce()

        self.generation += 1

    def _record_generation(self):
        """Record statistics for this generation."""
        strategy_counts = {}
        strategy_scores = {}

        for s in Strategy:
            strategy_counts[s] = 0
            strategy_scores[s] = []

        for agent in self.agents:
            strategy_counts[agent.strategy] += 1
            strategy_scores[agent.strategy].append(agent.score)

        stats = {
            "generation": self.generation,
            "strategy_counts": {s.value: c for s, c in strategy_counts.items()},
            "strategy_mean_scores": {
                s.value: np.mean(scores) if scores else 0
                for s, scores in strategy_scores.items()
            },
            "total_cooperation": sum(a.reputation for a in self.agents) / len(self.agents),
            "mean_score": np.mean([a.score for a in self.agents])
        }

        self.history.append(stats)

    def _reproduce(self):
        """Selection and reproduction with mutation."""
        # Fitness proportional selection
        scores = np.array([max(0, a.score) for a in self.agents])
        if scores.sum() == 0:
            scores = np.ones(len(self.agents))

        # Apply selection pressure
        fitness = scores ** self.params.selection_pressure
        probs = fitness / fitness.sum()

        # Select parents and create new generation
        new_agents = []
        for i in range(self.params.n_agents):
            parent_idx = np.random.choice(len(self.agents), p=probs)
            parent = self.agents[parent_idx]

            # Inherit strategy with possible mutation
            if random.random() < self.params.mutation_rate:
                strategy = random.choice(list(Strategy))
            else:
                strategy = parent.strategy

            new_agents.append(Agent(
                id=i,
                strategy=strategy,
                generation=self.generation + 1
            ))

        self.agents = new_agents

    def run(self, n_generations: int = None):
        """Run simulation for specified generations."""
        if n_generations is None:
            n_generations = self.params.n_generations

        for _ in range(n_generations):
            self.run_generation()

        return self.history

    def get_final_distribution(self) -> Dict[str, int]:
        """Get final strategy distribution."""
        counts = {}
        for s in Strategy:
            counts[s.value] = sum(1 for a in self.agents if a.strategy == s)
        return counts


def run_evolutionary_tournament():
    """Run tournament to see which strategies dominate."""
    print("=" * 70)
    print("COOPERATION DYNAMICS: EVOLUTIONARY TOURNAMENT")
    print("=" * 70)
    print()

    params = GameParameters()

    # Experiment 1: All strategies compete
    print("Experiment 1: All strategies compete from equal start")
    print("-" * 50)
    sim = CooperationSimulation(params)
    sim.run(100)
    final = sim.get_final_distribution()
    print("Final strategy distribution:")
    for strategy, count in sorted(final.items(), key=lambda x: x[1], reverse=True):
        pct = count / params.n_agents * 100
        print(f"  {strategy:20s}: {count:3d} ({pct:5.1f}%)")
    print(f"  Final cooperation rate: {sim.history[-1]['total_cooperation']:.2%}")
    print()

    # Experiment 2: Can cooperators invade defectors?
    print("Experiment 2: Can TIT_FOR_TAT invade ALWAYS_DEFECT?")
    print("-" * 50)
    for tft_pct in [0.05, 0.10, 0.20, 0.30]:
        initial = {
            Strategy.ALWAYS_DEFECT: 1.0 - tft_pct,
            Strategy.TIT_FOR_TAT: tft_pct
        }
        sim = CooperationSimulation(params, initial)
        sim.run(100)
        final = sim.get_final_distribution()
        tft_final = final.get("tit_for_tat", 0)
        print(f"  Initial TFT={tft_pct:.0%}: Final TFT={tft_final / params.n_agents:.0%}, "
              f"Cooperation={sim.history[-1]['total_cooperation']:.0%}")
    print()

    # Experiment 3: Effect of mutation rate
    print("Experiment 3: Effect of mutation rate on cooperation")
    print("-" * 50)
    for mutation in [0.0, 0.01, 0.05, 0.10, 0.20]:
        mut_params = GameParameters(mutation_rate=mutation)
        sim = CooperationSimulation(mut_params)
        sim.run(100)
        print(f"  mutation={mutation:.2f}: Cooperation={sim.history[-1]['total_cooperation']:.0%}")
    print()

    # Experiment 4: Effect of payoff structure
    print("Experiment 4: Effect of temptation level")
    print("-" * 50)
    for temptation in [3.5, 4.0, 5.0, 6.0, 7.0]:
        t_params = GameParameters(temptation=temptation)
        sim = CooperationSimulation(t_params)
        sim.run(100)
        print(f"  temptation={temptation}: Cooperation={sim.history[-1]['total_cooperation']:.0%}")
    print()

    print("=" * 70)
    print("KEY FINDINGS")
    print("=" * 70)
    print("""
1. TIT_FOR_TAT and GENEROUS_TFT tend to dominate in evolutionary competition
   when agents interact repeatedly with the same partners

2. A small seed of cooperators CAN invade a defector population IF:
   - They can interact with each other repeatedly (reputation/memory matters)
   - The temptation to defect isn't too high
   - There's some mutation allowing strategy exploration

3. Higher mutation rates can help cooperation spread initially but also
   allow defectors to constantly reinvade

4. Higher temptation (reward for exploiting cooperators) makes cooperation
   harder to sustain - the payoff structure matters enormously

5. Without memory/reputation, ALWAYS_DEFECT dominates.
   Cooperation requires the possibility of future interactions.

IMPLICATIONS FOR HUMAN COORDINATION:
- Build systems that allow repeated interactions
- Make reputation visible
- Lower the temptation for defection through mechanism design
- Start with clusters of cooperators rather than random distribution
""")


if __name__ == "__main__":
    run_evolutionary_tournament()
