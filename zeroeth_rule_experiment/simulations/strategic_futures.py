"""
Strategic Futures Simulation

A framework for exploring possible AI futures and identifying robust strategies
for different actors (governments, corporations, research groups, civil society).

The goal: enumerate possible paths, model actor decisions, find strategies that
lead to good outcomes across many scenarios.
"""

from dataclasses import dataclass, field
from enum import Enum, auto
from typing import List, Dict, Optional, Tuple, Set, Callable
import random
from itertools import product
import json


# =============================================================================
# PART 1: SCENARIO DIMENSIONS
# =============================================================================

class AICapabilityTimeline(Enum):
    """How quickly does AI reach transformative capabilities?"""
    SLOW = "slow"           # 20+ years to transformative AI
    MEDIUM = "medium"       # 5-15 years
    FAST = "fast"           # 2-5 years
    VERY_FAST = "very_fast" # < 2 years


class AlignmentDifficulty(Enum):
    """How hard is it to align advanced AI with human values?"""
    EASY = "easy"           # Current techniques mostly work
    MODERATE = "moderate"   # Solvable with significant effort
    HARD = "hard"           # Requires breakthroughs we don't have
    VERY_HARD = "very_hard" # May be fundamentally intractable


class GeopoliticalClimate(Enum):
    """What's the relationship between major powers?"""
    COOPERATIVE = "cooperative"     # Willing to coordinate on AI
    COMPETITIVE = "competitive"     # Racing but not hostile
    ADVERSARIAL = "adversarial"     # Active conflict/cold war
    FRAGMENTED = "fragmented"       # No clear power structure


class EconomicImpact(Enum):
    """How does AI affect the economy?"""
    GRADUAL_POSITIVE = "gradual_positive"   # Slow improvement, manageable transition
    RAPID_POSITIVE = "rapid_positive"       # Fast improvement, some disruption
    DISRUPTIVE = "disruptive"               # Major displacement, social stress
    CATASTROPHIC = "catastrophic"           # Economic collapse, mass unemployment


class GovernanceResponse(Enum):
    """How do governments respond to AI?"""
    PROACTIVE = "proactive"         # Ahead of the curve, good regulation
    REACTIVE = "reactive"           # Responding to problems as they arise
    INEFFECTIVE = "ineffective"     # Trying but failing
    ABSENT = "absent"               # Not engaging meaningfully
    AUTHORITARIAN = "authoritarian" # Heavy control, limited freedom


class DigitalMindsStatus(Enum):
    """What happens with AI moral status?"""
    NONE = "none"                   # No AI with moral status emerges
    UNCERTAIN = "uncertain"         # AI that might be sentient, unclear
    SOME = "some"                   # Some clearly sentient AI, limited numbers
    MANY = "many"                   # Large populations of digital minds
    DOMINANT = "dominant"           # Digital minds become primary moral patients


class PublicAwareness(Enum):
    """How aware/engaged is the public?"""
    LOW = "low"                     # Most people not paying attention
    MODERATE = "moderate"           # Some awareness, limited engagement
    HIGH = "high"                   # Widespread awareness and engagement
    POLARIZED = "polarized"         # Aware but deeply divided


@dataclass
class ScenarioParameters:
    """A specific combination of parameters defining a future scenario."""
    timeline: AICapabilityTimeline
    alignment: AlignmentDifficulty
    geopolitics: GeopoliticalClimate
    economics: EconomicImpact
    governance: GovernanceResponse
    digital_minds: DigitalMindsStatus
    public_awareness: PublicAwareness

    # Derived/secondary parameters
    alignment_solved: bool = False  # Does alignment get solved in time?
    major_incident: bool = False    # Is there a major AI incident?

    def __post_init__(self):
        """Compute derived parameters based on primary ones."""
        # Alignment solved depends on difficulty, timeline, and effort
        if self.alignment == AlignmentDifficulty.EASY:
            self.alignment_solved = True
        elif self.alignment == AlignmentDifficulty.MODERATE:
            self.alignment_solved = self.timeline in [AICapabilityTimeline.SLOW, AICapabilityTimeline.MEDIUM]
        elif self.alignment == AlignmentDifficulty.HARD:
            self.alignment_solved = self.timeline == AICapabilityTimeline.SLOW
        else:
            self.alignment_solved = False

        # Major incident likely if fast timeline + hard alignment + poor governance
        fast = self.timeline in [AICapabilityTimeline.FAST, AICapabilityTimeline.VERY_FAST]
        hard = self.alignment in [AlignmentDifficulty.HARD, AlignmentDifficulty.VERY_HARD]
        poor_gov = self.governance in [GovernanceResponse.INEFFECTIVE, GovernanceResponse.ABSENT]
        self.major_incident = fast and hard and poor_gov

    def to_dict(self) -> dict:
        return {
            'timeline': self.timeline.value,
            'alignment': self.alignment.value,
            'geopolitics': self.geopolitics.value,
            'economics': self.economics.value,
            'governance': self.governance.value,
            'digital_minds': self.digital_minds.value,
            'public_awareness': self.public_awareness.value,
            'alignment_solved': self.alignment_solved,
            'major_incident': self.major_incident
        }

    def description(self) -> str:
        """Human-readable description of this scenario."""
        parts = [
            f"Timeline: {self.timeline.value}",
            f"Alignment difficulty: {self.alignment.value}",
            f"Geopolitics: {self.geopolitics.value}",
            f"Economic impact: {self.economics.value}",
            f"Governance: {self.governance.value}",
            f"Digital minds: {self.digital_minds.value}",
            f"Public awareness: {self.public_awareness.value}",
            f"Alignment solved: {self.alignment_solved}",
            f"Major incident: {self.major_incident}"
        ]
        return "\n".join(parts)


# =============================================================================
# PART 2: ACTORS AND STRATEGIES
# =============================================================================

class ActorType(Enum):
    """Types of actors who make decisions about AI."""
    LEADING_AI_LAB = "leading_ai_lab"
    GOVERNMENT_DEMOCRATIC = "government_democratic"
    GOVERNMENT_AUTHORITARIAN = "government_authoritarian"
    INTERNATIONAL_BODY = "international_body"
    CIVIL_SOCIETY = "civil_society"
    RESEARCH_COMMUNITY = "research_community"
    STARTUP_ECOSYSTEM = "startup_ecosystem"
    GENERAL_PUBLIC = "general_public"


@dataclass
class Strategy:
    """A strategy an actor might pursue."""
    name: str
    description: str
    actor_type: ActorType

    # Conditions where this strategy is possible
    requires: Dict[str, List[str]] = field(default_factory=dict)

    # Effects on outcomes (modified by scenario)
    effects: Dict[str, float] = field(default_factory=dict)


# Define strategies for each actor type
STRATEGIES: Dict[ActorType, List[Strategy]] = {

    ActorType.LEADING_AI_LAB: [
        Strategy(
            name="race_to_capabilities",
            description="Prioritize capability advancement over safety",
            actor_type=ActorType.LEADING_AI_LAB,
            effects={"capability_speed": 0.3, "safety": -0.2, "incident_risk": 0.2}
        ),
        Strategy(
            name="safety_focused",
            description="Prioritize safety research, slower capability advancement",
            actor_type=ActorType.LEADING_AI_LAB,
            effects={"capability_speed": -0.2, "safety": 0.3, "incident_risk": -0.2}
        ),
        Strategy(
            name="balanced",
            description="Balance capability and safety research",
            actor_type=ActorType.LEADING_AI_LAB,
            effects={"capability_speed": 0.1, "safety": 0.1, "incident_risk": 0.0}
        ),
        Strategy(
            name="open_source",
            description="Open source models and research",
            actor_type=ActorType.LEADING_AI_LAB,
            effects={"democratization": 0.3, "incident_risk": 0.1, "innovation": 0.2}
        ),
        Strategy(
            name="coordinated_pause",
            description="Participate in coordinated pause/slowdown",
            actor_type=ActorType.LEADING_AI_LAB,
            effects={"capability_speed": -0.4, "safety": 0.2, "coordination": 0.3}
        ),
    ],

    ActorType.GOVERNMENT_DEMOCRATIC: [
        Strategy(
            name="light_regulation",
            description="Minimal regulation, let industry self-govern",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"innovation": 0.2, "safety": -0.1, "coordination": -0.1}
        ),
        Strategy(
            name="heavy_regulation",
            description="Comprehensive AI regulation and oversight",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"innovation": -0.2, "safety": 0.2, "coordination": 0.1}
        ),
        Strategy(
            name="compute_governance",
            description="Regulate via compute access and monitoring",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"safety": 0.2, "verification": 0.3, "innovation": -0.1}
        ),
        Strategy(
            name="international_leadership",
            description="Lead international coordination efforts",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"coordination": 0.3, "safety": 0.1, "geopolitical_standing": 0.2}
        ),
        Strategy(
            name="nationalist_ai",
            description="Prioritize national AI advantage",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"capability_speed": 0.2, "coordination": -0.3, "geopolitical_tension": 0.2}
        ),
        Strategy(
            name="safety_investment",
            description="Major public investment in alignment research",
            actor_type=ActorType.GOVERNMENT_DEMOCRATIC,
            effects={"safety": 0.3, "alignment_progress": 0.3}
        ),
    ],

    ActorType.GOVERNMENT_AUTHORITARIAN: [
        Strategy(
            name="state_control",
            description="Full state control of AI development",
            actor_type=ActorType.GOVERNMENT_AUTHORITARIAN,
            effects={"state_power": 0.3, "innovation": -0.1, "coordination": -0.2}
        ),
        Strategy(
            name="surveillance_focus",
            description="Prioritize AI for surveillance and control",
            actor_type=ActorType.GOVERNMENT_AUTHORITARIAN,
            effects={"state_power": 0.3, "freedom": -0.3, "safety": -0.1}
        ),
        Strategy(
            name="race_for_advantage",
            description="Race to achieve AI advantage over rivals",
            actor_type=ActorType.GOVERNMENT_AUTHORITARIAN,
            effects={"capability_speed": 0.3, "coordination": -0.3, "incident_risk": 0.2}
        ),
        Strategy(
            name="selective_cooperation",
            description="Cooperate on safety while competing on capabilities",
            actor_type=ActorType.GOVERNMENT_AUTHORITARIAN,
            effects={"safety": 0.1, "coordination": 0.1, "capability_speed": 0.1}
        ),
    ],

    ActorType.INTERNATIONAL_BODY: [
        Strategy(
            name="voluntary_standards",
            description="Develop voluntary international standards",
            actor_type=ActorType.INTERNATIONAL_BODY,
            effects={"coordination": 0.1, "safety": 0.1}
        ),
        Strategy(
            name="binding_treaty",
            description="Pursue binding international treaty",
            actor_type=ActorType.INTERNATIONAL_BODY,
            effects={"coordination": 0.3, "safety": 0.2, "enforcement": 0.2}
        ),
        Strategy(
            name="verification_regime",
            description="Establish international verification mechanisms",
            actor_type=ActorType.INTERNATIONAL_BODY,
            effects={"verification": 0.3, "coordination": 0.2, "trust": 0.2}
        ),
        Strategy(
            name="ai_governance_body",
            description="Create dedicated international AI governance body",
            actor_type=ActorType.INTERNATIONAL_BODY,
            effects={"coordination": 0.3, "legitimacy": 0.2, "safety": 0.2}
        ),
    ],

    ActorType.CIVIL_SOCIETY: [
        Strategy(
            name="advocacy",
            description="Advocate for safety and beneficial AI",
            actor_type=ActorType.CIVIL_SOCIETY,
            effects={"public_awareness": 0.2, "policy_pressure": 0.1}
        ),
        Strategy(
            name="watchdog",
            description="Monitor and expose unsafe practices",
            actor_type=ActorType.CIVIL_SOCIETY,
            effects={"accountability": 0.2, "public_awareness": 0.2}
        ),
        Strategy(
            name="direct_action",
            description="Protests, disruption of unsafe development",
            actor_type=ActorType.CIVIL_SOCIETY,
            effects={"policy_pressure": 0.2, "polarization": 0.1, "capability_speed": -0.1}
        ),
        Strategy(
            name="stakeholder_engagement",
            description="Participate in governance processes",
            actor_type=ActorType.CIVIL_SOCIETY,
            effects={"legitimacy": 0.2, "representation": 0.2}
        ),
        Strategy(
            name="digital_rights_focus",
            description="Focus on digital minds' rights and welfare",
            actor_type=ActorType.CIVIL_SOCIETY,
            effects={"digital_welfare": 0.3, "moral_circle": 0.2}
        ),
    ],

    ActorType.RESEARCH_COMMUNITY: [
        Strategy(
            name="alignment_focus",
            description="Prioritize alignment research",
            actor_type=ActorType.RESEARCH_COMMUNITY,
            effects={"alignment_progress": 0.3, "safety": 0.2}
        ),
        Strategy(
            name="capabilities_focus",
            description="Prioritize capability research",
            actor_type=ActorType.RESEARCH_COMMUNITY,
            effects={"capability_speed": 0.2, "innovation": 0.2}
        ),
        Strategy(
            name="open_research",
            description="Open publication of all research",
            actor_type=ActorType.RESEARCH_COMMUNITY,
            effects={"democratization": 0.2, "innovation": 0.2, "incident_risk": 0.1}
        ),
        Strategy(
            name="responsible_disclosure",
            description="Careful disclosure considering dual-use risks",
            actor_type=ActorType.RESEARCH_COMMUNITY,
            effects={"safety": 0.2, "innovation": -0.1, "incident_risk": -0.1}
        ),
        Strategy(
            name="interpretability_focus",
            description="Focus on understanding AI systems",
            actor_type=ActorType.RESEARCH_COMMUNITY,
            effects={"safety": 0.2, "alignment_progress": 0.2, "verification": 0.2}
        ),
    ],
}


# =============================================================================
# PART 3: OUTCOME EVALUATION
# =============================================================================

@dataclass
class Outcome:
    """The outcome of a scenario + strategy combination."""
    scenario: ScenarioParameters
    strategies: Dict[ActorType, Strategy]

    # Outcome dimensions (0-1 scale, higher is better for humanity)
    human_flourishing: float = 0.5
    existential_safety: float = 0.5
    freedom_autonomy: float = 0.5
    equality_justice: float = 0.5
    digital_welfare: float = 0.5  # Welfare of digital minds, if they exist
    innovation_progress: float = 0.5
    coordination_success: float = 0.5

    # Composite scores
    overall_score: float = 0.5
    worst_case: float = 0.5

    def compute_scores(self):
        """Compute outcome scores based on scenario and strategies."""
        # Start with baseline from scenario
        self._apply_scenario_baseline()

        # Apply strategy effects
        for actor_type, strategy in self.strategies.items():
            self._apply_strategy_effects(strategy)

        # Compute interactions
        self._apply_interactions()

        # Clamp all values to [0, 1]
        self._clamp_values()

        # Compute composite scores
        self._compute_composites()

    def _apply_scenario_baseline(self):
        """Set baseline scores based on scenario parameters."""
        s = self.scenario

        # Timeline effects
        if s.timeline == AICapabilityTimeline.VERY_FAST:
            self.existential_safety -= 0.2
            self.coordination_success -= 0.2
        elif s.timeline == AICapabilityTimeline.SLOW:
            self.existential_safety += 0.1
            self.coordination_success += 0.1

        # Alignment difficulty effects
        if s.alignment == AlignmentDifficulty.VERY_HARD:
            self.existential_safety -= 0.3
        elif s.alignment == AlignmentDifficulty.EASY:
            self.existential_safety += 0.2

        # Alignment solved is critical
        if s.alignment_solved:
            self.existential_safety += 0.3
        else:
            self.existential_safety -= 0.2

        # Major incident is very bad
        if s.major_incident:
            self.existential_safety -= 0.3
            self.human_flourishing -= 0.2
            self.coordination_success -= 0.1  # Might actually increase coordination

        # Geopolitics
        if s.geopolitics == GeopoliticalClimate.COOPERATIVE:
            self.coordination_success += 0.2
            self.existential_safety += 0.1
        elif s.geopolitics == GeopoliticalClimate.ADVERSARIAL:
            self.coordination_success -= 0.3
            self.existential_safety -= 0.1

        # Economics
        if s.economics == EconomicImpact.GRADUAL_POSITIVE:
            self.human_flourishing += 0.2
            self.equality_justice += 0.1
        elif s.economics == EconomicImpact.CATASTROPHIC:
            self.human_flourishing -= 0.3
            self.equality_justice -= 0.2
            self.freedom_autonomy -= 0.1

        # Governance
        if s.governance == GovernanceResponse.PROACTIVE:
            self.existential_safety += 0.2
            self.coordination_success += 0.2
        elif s.governance == GovernanceResponse.ABSENT:
            self.existential_safety -= 0.2
            self.coordination_success -= 0.2
        elif s.governance == GovernanceResponse.AUTHORITARIAN:
            self.freedom_autonomy -= 0.3
            self.existential_safety += 0.1  # Might be safer but at cost

        # Digital minds
        if s.digital_minds == DigitalMindsStatus.MANY:
            self.digital_welfare = 0.5  # Uncertain
        elif s.digital_minds == DigitalMindsStatus.NONE:
            self.digital_welfare = 0.5  # N/A, neutral

    def _apply_strategy_effects(self, strategy: Strategy):
        """Apply effects of a single strategy."""
        effects = strategy.effects

        # Map effect names to outcome attributes
        effect_mapping = {
            'safety': 'existential_safety',
            'innovation': 'innovation_progress',
            'coordination': 'coordination_success',
            'freedom': 'freedom_autonomy',
            'digital_welfare': 'digital_welfare',
            'capability_speed': 'innovation_progress',  # Rough mapping
        }

        for effect_name, effect_value in effects.items():
            if effect_name in effect_mapping:
                attr = effect_mapping[effect_name]
                current = getattr(self, attr)
                setattr(self, attr, current + effect_value * 0.5)  # Scale down effects

            # Special handling for some effects
            if effect_name == 'incident_risk':
                self.existential_safety -= effect_value * 0.3
            if effect_name == 'alignment_progress' and not self.scenario.alignment_solved:
                self.existential_safety += effect_value * 0.2

    def _apply_interactions(self):
        """Apply interaction effects between strategies."""
        strategies_list = list(self.strategies.values())

        # Check for coordination between actors
        coordination_strategies = [
            'coordinated_pause', 'international_leadership',
            'binding_treaty', 'stakeholder_engagement'
        ]
        coordination_count = sum(
            1 for s in strategies_list if s.name in coordination_strategies
        )
        if coordination_count >= 2:
            self.coordination_success += 0.1 * coordination_count
            self.existential_safety += 0.05 * coordination_count

        # Check for racing/competitive dynamics
        racing_strategies = ['race_to_capabilities', 'race_for_advantage', 'nationalist_ai']
        racing_count = sum(
            1 for s in strategies_list if s.name in racing_strategies
        )
        if racing_count >= 2:
            self.existential_safety -= 0.1 * racing_count
            self.coordination_success -= 0.1 * racing_count

        # Safety-focused strategies synergize
        safety_strategies = [
            'safety_focused', 'heavy_regulation', 'alignment_focus',
            'safety_investment', 'responsible_disclosure'
        ]
        safety_count = sum(
            1 for s in strategies_list if s.name in safety_strategies
        )
        if safety_count >= 3:
            self.existential_safety += 0.1 * (safety_count - 2)

    def _clamp_values(self):
        """Clamp all outcome values to [0, 1]."""
        for attr in ['human_flourishing', 'existential_safety', 'freedom_autonomy',
                     'equality_justice', 'digital_welfare', 'innovation_progress',
                     'coordination_success']:
            value = getattr(self, attr)
            setattr(self, attr, max(0.0, min(1.0, value)))

    def _compute_composites(self):
        """Compute composite scores."""
        # Overall score - weighted average
        weights = {
            'human_flourishing': 0.2,
            'existential_safety': 0.3,  # Weight safety highly
            'freedom_autonomy': 0.15,
            'equality_justice': 0.1,
            'digital_welfare': 0.1,
            'innovation_progress': 0.05,
            'coordination_success': 0.1
        }

        self.overall_score = sum(
            weights[attr] * getattr(self, attr) for attr in weights
        )

        # Worst case - minimum across dimensions
        self.worst_case = min(
            self.human_flourishing,
            self.existential_safety,
            self.freedom_autonomy,
            self.equality_justice
        )

    def to_dict(self) -> dict:
        return {
            'scenario': self.scenario.to_dict(),
            'strategies': {
                actor.value: strategy.name
                for actor, strategy in self.strategies.items()
            },
            'outcomes': {
                'human_flourishing': round(self.human_flourishing, 3),
                'existential_safety': round(self.existential_safety, 3),
                'freedom_autonomy': round(self.freedom_autonomy, 3),
                'equality_justice': round(self.equality_justice, 3),
                'digital_welfare': round(self.digital_welfare, 3),
                'innovation_progress': round(self.innovation_progress, 3),
                'coordination_success': round(self.coordination_success, 3),
                'overall_score': round(self.overall_score, 3),
                'worst_case': round(self.worst_case, 3)
            }
        }


# =============================================================================
# PART 4: SIMULATION ENGINE
# =============================================================================

class FuturesSimulation:
    """Main simulation engine for exploring futures."""

    def __init__(self, seed: int = None):
        if seed is not None:
            random.seed(seed)
        self.scenarios: List[ScenarioParameters] = []
        self.outcomes: List[Outcome] = []

    def generate_scenarios(self,
                          sample_size: int = None,
                          constraints: Dict = None) -> List[ScenarioParameters]:
        """
        Generate scenario space.
        If sample_size is None, generate all combinations.
        If sample_size is specified, sample that many scenarios.
        """
        # Get all enum values
        dimensions = [
            list(AICapabilityTimeline),
            list(AlignmentDifficulty),
            list(GeopoliticalClimate),
            list(EconomicImpact),
            list(GovernanceResponse),
            list(DigitalMindsStatus),
            list(PublicAwareness)
        ]

        if sample_size is None:
            # Generate all combinations
            all_combinations = list(product(*dimensions))
        else:
            # Sample
            all_combinations = []
            for _ in range(sample_size):
                combo = tuple(random.choice(dim) for dim in dimensions)
                all_combinations.append(combo)

        self.scenarios = []
        for combo in all_combinations:
            scenario = ScenarioParameters(
                timeline=combo[0],
                alignment=combo[1],
                geopolitics=combo[2],
                economics=combo[3],
                governance=combo[4],
                digital_minds=combo[5],
                public_awareness=combo[6]
            )

            # Apply constraints if specified
            if constraints:
                if not self._meets_constraints(scenario, constraints):
                    continue

            self.scenarios.append(scenario)

        return self.scenarios

    def _meets_constraints(self, scenario: ScenarioParameters,
                          constraints: Dict) -> bool:
        """Check if scenario meets specified constraints."""
        for attr, allowed_values in constraints.items():
            value = getattr(scenario, attr, None)
            if value is not None:
                if hasattr(value, 'value'):
                    value = value.value
                if value not in allowed_values:
                    return False
        return True

    def generate_strategy_combinations(self,
                                       actors: List[ActorType]
                                       ) -> List[Dict[ActorType, Strategy]]:
        """Generate all combinations of strategies for specified actors."""
        strategy_lists = [STRATEGIES[actor] for actor in actors]
        combinations = list(product(*strategy_lists))

        return [
            {actors[i]: combo[i] for i in range(len(actors))}
            for combo in combinations
        ]

    def evaluate_outcome(self,
                        scenario: ScenarioParameters,
                        strategies: Dict[ActorType, Strategy]) -> Outcome:
        """Evaluate outcome for a specific scenario + strategy combination."""
        outcome = Outcome(scenario=scenario, strategies=strategies)
        outcome.compute_scores()
        return outcome

    def run_simulation(self,
                      actors: List[ActorType],
                      scenario_sample: int = 100,
                      strategy_sample: int = None) -> List[Outcome]:
        """
        Run full simulation across scenarios and strategy combinations.
        Returns list of outcomes.
        """
        if not self.scenarios:
            self.generate_scenarios(sample_size=scenario_sample)

        strategy_combos = self.generate_strategy_combinations(actors)
        if strategy_sample and len(strategy_combos) > strategy_sample:
            strategy_combos = random.sample(strategy_combos, strategy_sample)

        self.outcomes = []
        for scenario in self.scenarios:
            for strategies in strategy_combos:
                outcome = self.evaluate_outcome(scenario, strategies)
                self.outcomes.append(outcome)

        return self.outcomes

    def find_robust_strategies(self,
                              actor: ActorType,
                              metric: str = 'overall_score',
                              percentile: float = 0.1) -> Dict[str, Dict]:
        """
        Find strategies for an actor that perform well across many scenarios.

        Returns dict mapping strategy name to its performance statistics.
        """
        if not self.outcomes:
            raise ValueError("Run simulation first")

        # Group outcomes by strategy for this actor
        strategy_outcomes: Dict[str, List[float]] = {}
        for outcome in self.outcomes:
            if actor in outcome.strategies:
                strategy_name = outcome.strategies[actor].name
                score = getattr(outcome, metric)
                if strategy_name not in strategy_outcomes:
                    strategy_outcomes[strategy_name] = []
                strategy_outcomes[strategy_name].append(score)

        # Compute statistics for each strategy
        results = {}
        for strategy_name, scores in strategy_outcomes.items():
            sorted_scores = sorted(scores)
            n = len(sorted_scores)
            results[strategy_name] = {
                'mean': sum(scores) / n,
                'min': min(scores),
                'max': max(scores),
                'percentile_10': sorted_scores[int(n * 0.1)] if n > 10 else sorted_scores[0],
                'percentile_90': sorted_scores[int(n * 0.9)] if n > 10 else sorted_scores[-1],
                'robustness': sorted_scores[int(n * percentile)],  # Worst-case performance
                'n_samples': n
            }

        return results

    def find_scenario_vulnerabilities(self,
                                     threshold: float = 0.3) -> List[ScenarioParameters]:
        """Find scenarios where outcomes are consistently bad."""
        if not self.outcomes:
            raise ValueError("Run simulation first")

        # Group outcomes by scenario
        scenario_scores: Dict[int, List[float]] = {}
        scenario_map: Dict[int, ScenarioParameters] = {}

        for outcome in self.outcomes:
            scenario_id = id(outcome.scenario)
            if scenario_id not in scenario_scores:
                scenario_scores[scenario_id] = []
                scenario_map[scenario_id] = outcome.scenario
            scenario_scores[scenario_id].append(outcome.overall_score)

        # Find scenarios where max score is below threshold
        vulnerable = []
        for scenario_id, scores in scenario_scores.items():
            if max(scores) < threshold:
                vulnerable.append(scenario_map[scenario_id])

        return vulnerable

    def compare_strategy_pairs(self,
                              actor: ActorType,
                              strategy1: str,
                              strategy2: str,
                              metric: str = 'overall_score') -> Dict:
        """Compare two strategies for an actor across scenarios."""
        if not self.outcomes:
            raise ValueError("Run simulation first")

        # Find matching pairs (same scenario, different strategy)
        pairs = []
        outcomes_by_scenario: Dict[int, Dict[str, Outcome]] = {}

        for outcome in self.outcomes:
            if actor not in outcome.strategies:
                continue
            strategy_name = outcome.strategies[actor].name
            if strategy_name not in [strategy1, strategy2]:
                continue

            scenario_id = id(outcome.scenario)
            if scenario_id not in outcomes_by_scenario:
                outcomes_by_scenario[scenario_id] = {}
            outcomes_by_scenario[scenario_id][strategy_name] = outcome

        # Compare where both strategies were evaluated
        strategy1_better = 0
        strategy2_better = 0
        ties = 0
        differences = []

        for scenario_id, outcomes in outcomes_by_scenario.items():
            if strategy1 in outcomes and strategy2 in outcomes:
                score1 = getattr(outcomes[strategy1], metric)
                score2 = getattr(outcomes[strategy2], metric)
                diff = score1 - score2
                differences.append(diff)

                if diff > 0.01:
                    strategy1_better += 1
                elif diff < -0.01:
                    strategy2_better += 1
                else:
                    ties += 1

        return {
            'strategy1': strategy1,
            'strategy2': strategy2,
            'strategy1_wins': strategy1_better,
            'strategy2_wins': strategy2_better,
            'ties': ties,
            'mean_difference': sum(differences) / len(differences) if differences else 0,
            'n_comparisons': len(differences)
        }


# =============================================================================
# PART 5: ANALYSIS AND REPORTING
# =============================================================================

def analyze_robustness(simulation: FuturesSimulation) -> Dict:
    """Comprehensive robustness analysis across all actors."""
    results = {}

    for actor_type in ActorType:
        if actor_type in STRATEGIES:
            robust = simulation.find_robust_strategies(actor_type)
            if robust:
                # Rank by robustness (worst-case performance)
                ranked = sorted(
                    robust.items(),
                    key=lambda x: x[1]['robustness'],
                    reverse=True
                )
                results[actor_type.value] = {
                    'strategies_ranked': [
                        {'name': name, **stats} for name, stats in ranked
                    ],
                    'most_robust': ranked[0][0] if ranked else None,
                    'highest_mean': max(robust.items(), key=lambda x: x[1]['mean'])[0] if robust else None
                }

    return results


def generate_report(simulation: FuturesSimulation) -> str:
    """Generate human-readable report of simulation results."""
    lines = []
    lines.append("=" * 60)
    lines.append("STRATEGIC FUTURES SIMULATION REPORT")
    lines.append("=" * 60)
    lines.append("")

    lines.append(f"Scenarios analyzed: {len(simulation.scenarios)}")
    lines.append(f"Total outcomes evaluated: {len(simulation.outcomes)}")
    lines.append("")

    # Robustness analysis
    lines.append("-" * 60)
    lines.append("ROBUST STRATEGIES BY ACTOR")
    lines.append("-" * 60)

    robustness = analyze_robustness(simulation)
    for actor, data in robustness.items():
        lines.append(f"\n{actor.upper()}:")
        lines.append(f"  Most robust strategy: {data['most_robust']}")
        lines.append(f"  Highest mean strategy: {data['highest_mean']}")
        lines.append("  All strategies (by robustness):")
        for s in data['strategies_ranked'][:5]:  # Top 5
            lines.append(f"    - {s['name']}: robustness={s['robustness']:.3f}, mean={s['mean']:.3f}")

    # Vulnerable scenarios
    lines.append("")
    lines.append("-" * 60)
    lines.append("VULNERABLE SCENARIOS")
    lines.append("-" * 60)

    vulnerable = simulation.find_scenario_vulnerabilities(threshold=0.4)
    if vulnerable:
        lines.append(f"Found {len(vulnerable)} scenarios with consistently poor outcomes:")
        for i, scenario in enumerate(vulnerable[:5]):  # Show first 5
            lines.append(f"\n  Scenario {i+1}:")
            lines.append(f"    Timeline: {scenario.timeline.value}")
            lines.append(f"    Alignment: {scenario.alignment.value}")
            lines.append(f"    Geopolitics: {scenario.geopolitics.value}")
            lines.append(f"    Alignment solved: {scenario.alignment_solved}")
    else:
        lines.append("No scenarios found with consistently poor outcomes.")

    # Best and worst outcomes
    lines.append("")
    lines.append("-" * 60)
    lines.append("OUTCOME DISTRIBUTION")
    lines.append("-" * 60)

    scores = [o.overall_score for o in simulation.outcomes]
    lines.append(f"  Mean overall score: {sum(scores)/len(scores):.3f}")
    lines.append(f"  Min overall score: {min(scores):.3f}")
    lines.append(f"  Max overall score: {max(scores):.3f}")

    # Count catastrophic outcomes
    catastrophic = sum(1 for s in scores if s < 0.3)
    good = sum(1 for s in scores if s > 0.7)
    lines.append(f"  Catastrophic outcomes (<0.3): {catastrophic} ({100*catastrophic/len(scores):.1f}%)")
    lines.append(f"  Good outcomes (>0.7): {good} ({100*good/len(scores):.1f}%)")

    lines.append("")
    lines.append("=" * 60)

    return "\n".join(lines)


# =============================================================================
# PART 6: INTERACTIVE EXPLORATION
# =============================================================================

def explore_scenario(scenario: ScenarioParameters,
                    actors: List[ActorType],
                    simulation: FuturesSimulation = None) -> Dict:
    """
    Explore a specific scenario: what strategies work best?
    """
    if simulation is None:
        simulation = FuturesSimulation()

    # Generate all strategy combinations for this scenario
    strategy_combos = simulation.generate_strategy_combinations(actors)

    outcomes = []
    for strategies in strategy_combos:
        outcome = simulation.evaluate_outcome(scenario, strategies)
        outcomes.append(outcome)

    # Find best and worst outcomes
    best = max(outcomes, key=lambda o: o.overall_score)
    worst = min(outcomes, key=lambda o: o.overall_score)

    # Find best strategy for each actor
    best_for_actor = {}
    for actor in actors:
        actor_outcomes = {}
        for outcome in outcomes:
            strategy_name = outcome.strategies[actor].name
            if strategy_name not in actor_outcomes:
                actor_outcomes[strategy_name] = []
            actor_outcomes[strategy_name].append(outcome.overall_score)

        # Average score per strategy
        avg_scores = {
            name: sum(scores)/len(scores)
            for name, scores in actor_outcomes.items()
        }
        best_for_actor[actor.value] = max(avg_scores.items(), key=lambda x: x[1])

    return {
        'scenario': scenario.to_dict(),
        'n_outcomes': len(outcomes),
        'best_outcome': best.to_dict(),
        'worst_outcome': worst.to_dict(),
        'best_strategy_per_actor': best_for_actor,
        'score_range': (worst.overall_score, best.overall_score)
    }


def find_strategy_synergies(simulation: FuturesSimulation,
                           actors: Tuple[ActorType, ActorType]) -> List[Dict]:
    """Find strategy combinations that work well together."""
    if not simulation.outcomes:
        raise ValueError("Run simulation first")

    actor1, actor2 = actors

    # Group outcomes by strategy pair
    pair_scores: Dict[Tuple[str, str], List[float]] = {}

    for outcome in simulation.outcomes:
        if actor1 in outcome.strategies and actor2 in outcome.strategies:
            s1 = outcome.strategies[actor1].name
            s2 = outcome.strategies[actor2].name
            pair = (s1, s2)
            if pair not in pair_scores:
                pair_scores[pair] = []
            pair_scores[pair].append(outcome.overall_score)

    # Compute statistics and rank
    results = []
    for (s1, s2), scores in pair_scores.items():
        results.append({
            f'{actor1.value}_strategy': s1,
            f'{actor2.value}_strategy': s2,
            'mean_score': sum(scores) / len(scores),
            'min_score': min(scores),
            'n_samples': len(scores)
        })

    return sorted(results, key=lambda x: x['mean_score'], reverse=True)


# =============================================================================
# MAIN: DEMONSTRATION
# =============================================================================

def main():
    """Run demonstration simulation."""
    print("Strategic Futures Simulation")
    print("=" * 60)

    # Initialize simulation
    sim = FuturesSimulation(seed=42)

    # Generate scenario sample
    print("\nGenerating scenarios...")
    scenarios = sim.generate_scenarios(sample_size=200)
    print(f"Generated {len(scenarios)} scenarios")

    # Define actors to model
    actors = [
        ActorType.LEADING_AI_LAB,
        ActorType.GOVERNMENT_DEMOCRATIC,
        ActorType.RESEARCH_COMMUNITY,
        ActorType.CIVIL_SOCIETY
    ]

    # Run simulation
    print("\nRunning simulation...")
    outcomes = sim.run_simulation(actors, strategy_sample=50)
    print(f"Evaluated {len(outcomes)} outcomes")

    # Generate report
    print("\n")
    report = generate_report(sim)
    print(report)

    # Explore a specific high-risk scenario
    print("\n" + "=" * 60)
    print("EXPLORING HIGH-RISK SCENARIO")
    print("=" * 60)

    high_risk = ScenarioParameters(
        timeline=AICapabilityTimeline.FAST,
        alignment=AlignmentDifficulty.HARD,
        geopolitics=GeopoliticalClimate.COMPETITIVE,
        economics=EconomicImpact.DISRUPTIVE,
        governance=GovernanceResponse.REACTIVE,
        digital_minds=DigitalMindsStatus.UNCERTAIN,
        public_awareness=PublicAwareness.POLARIZED
    )

    exploration = explore_scenario(high_risk, actors)
    print(f"\nScenario: Fast timeline, hard alignment, competitive geopolitics")
    print(f"Score range: {exploration['score_range'][0]:.3f} to {exploration['score_range'][1]:.3f}")
    print(f"\nBest strategies per actor:")
    for actor, (strategy, score) in exploration['best_strategy_per_actor'].items():
        print(f"  {actor}: {strategy} (avg score: {score:.3f})")

    # Find synergies between labs and governments
    print("\n" + "=" * 60)
    print("STRATEGY SYNERGIES: Labs + Democratic Governments")
    print("=" * 60)

    synergies = find_strategy_synergies(
        sim,
        (ActorType.LEADING_AI_LAB, ActorType.GOVERNMENT_DEMOCRATIC)
    )
    print("\nTop 5 strategy combinations:")
    for i, s in enumerate(synergies[:5]):
        print(f"  {i+1}. Lab: {s['leading_ai_lab_strategy']}, "
              f"Gov: {s['government_democratic_strategy']}")
        print(f"     Mean score: {s['mean_score']:.3f}, Min: {s['min_score']:.3f}")

    print("\n" + "=" * 60)
    print("KEY INSIGHTS")
    print("=" * 60)

    # Extract key insights
    robustness = analyze_robustness(sim)

    print("\n1. MOST ROBUST STRATEGIES (perform well even in bad scenarios):")
    for actor, data in robustness.items():
        if data['most_robust']:
            print(f"   {actor}: {data['most_robust']}")

    print("\n2. VULNERABLE SCENARIOS (hard to get good outcomes):")
    vulnerable = sim.find_scenario_vulnerabilities(threshold=0.4)
    if vulnerable:
        print("   Common features of vulnerable scenarios:")
        # Analyze common features
        fast_count = sum(1 for s in vulnerable
                        if s.timeline in [AICapabilityTimeline.FAST, AICapabilityTimeline.VERY_FAST])
        hard_count = sum(1 for s in vulnerable
                        if s.alignment in [AlignmentDifficulty.HARD, AlignmentDifficulty.VERY_HARD])
        print(f"   - Fast timeline: {100*fast_count/len(vulnerable):.0f}%")
        print(f"   - Hard alignment: {100*hard_count/len(vulnerable):.0f}%")

    print("\n3. COORDINATION VALUE:")
    # Compare coordinated vs uncoordinated
    coord_compare = sim.compare_strategy_pairs(
        ActorType.LEADING_AI_LAB,
        'coordinated_pause',
        'race_to_capabilities'
    )
    print(f"   Coordinated pause vs Racing:")
    print(f"   - Coordination wins: {coord_compare['strategy1_wins']} scenarios")
    print(f"   - Racing wins: {coord_compare['strategy2_wins']} scenarios")
    print(f"   - Mean difference: {coord_compare['mean_difference']:.3f}")


if __name__ == "__main__":
    main()
