# Strategic Futures Simulation Guide

*A framework for exploring AI futures and identifying robust strategies*

---

## Purpose

This simulation helps answer: **What strategies should different actors pursue that will lead to good outcomes across many possible futures?**

Rather than predicting which future will happen (which we can't do), we:
1. Enumerate many possible futures
2. Model what different actors might do
3. Evaluate outcomes across combinations
4. Identify strategies that work well across many scenarios (robust)
5. Identify scenarios where outcomes are consistently bad (vulnerable)

---

## The Framework

### Scenario Dimensions

Each future scenario is defined by 7 key uncertain parameters:

| Dimension | Options | What it captures |
|-----------|---------|------------------|
| **AI Timeline** | slow, medium, fast, very_fast | How quickly transformative AI arrives |
| **Alignment Difficulty** | easy, moderate, hard, very_hard | How hard is it to make AI safe? |
| **Geopolitics** | cooperative, competitive, adversarial, fragmented | Relations between major powers |
| **Economic Impact** | gradual_positive, rapid_positive, disruptive, catastrophic | How AI affects the economy |
| **Governance Response** | proactive, reactive, ineffective, absent, authoritarian | How governments respond |
| **Digital Minds** | none, uncertain, some, many, dominant | Whether AI with moral status emerges |
| **Public Awareness** | low, moderate, high, polarized | Public engagement with AI issues |

These create a large space of possible futures (4×4×4×4×5×5×4 = 25,600 combinations).

### Actors and Strategies

The simulation models 8 types of actors:

1. **Leading AI Labs** - Companies at the frontier
2. **Democratic Governments** - US, EU, etc.
3. **Authoritarian Governments** - China, etc.
4. **International Bodies** - UN, new institutions
5. **Civil Society** - NGOs, advocacy groups
6. **Research Community** - Academic researchers
7. **Startup Ecosystem** - Smaller AI companies
8. **General Public** - Citizens collectively

Each actor has 4-6 possible strategies (e.g., labs can "race to capabilities" or "safety focused").

### Outcome Dimensions

Outcomes are evaluated on 7 dimensions:

- **Human Flourishing** - Quality of life, wellbeing
- **Existential Safety** - Avoiding catastrophe
- **Freedom/Autonomy** - Human agency preserved
- **Equality/Justice** - Fair distribution
- **Digital Welfare** - Wellbeing of digital minds
- **Innovation/Progress** - Technological advancement
- **Coordination Success** - Collective action working

These are combined into an overall score (weighted toward safety) and a worst-case score.

---

## Key Findings

Running the simulation (200 scenarios × 50 strategy combinations = 10,000 outcomes) reveals:

### 1. Most Robust Strategies

Strategies that perform well even in bad scenarios:

| Actor | Most Robust Strategy | Why it works |
|-------|---------------------|--------------|
| **AI Labs** | safety_focused | Reduces incident risk across scenarios |
| **Democratic Gov** | international_leadership | Enables coordination that helps in most futures |
| **Civil Society** | stakeholder_engagement | Builds legitimacy and representation |
| **Research** | alignment_focus | Directly addresses the technical problem |

### 2. Vulnerable Scenarios

Scenarios where outcomes are consistently poor (no strategy helps much):

**Common features:**
- Fast or very fast timeline (96%)
- Hard or very hard alignment (88%)
- Adversarial or fragmented geopolitics

**Implication**: If we're in a fast-timeline, hard-alignment world, outcomes depend heavily on factors beyond strategy choice. Prevention (slowing timeline, solving alignment earlier) is better than cure.

### 3. Coordination Value

Comparing "coordinated pause" vs "race to capabilities" for AI labs:
- Coordination wins in 132/132 comparable scenarios
- Racing wins in 0 scenarios
- Mean score difference: +0.029 for coordination

**Implication**: Even under competitive pressure, coordination dominates racing from a collective welfare perspective.

### 4. Strategy Synergies

Best combinations (Lab + Democratic Government):
1. Coordinated pause + International leadership
2. Safety focused + International leadership
3. Safety focused + Compute governance

**Implication**: Safety-oriented lab strategies work best with coordination-oriented government strategies.

### 5. Outcome Distribution

- 9.5% of outcomes are catastrophic (<0.3 score)
- 8.6% of outcomes are good (>0.7 score)
- Mean outcome: 0.512

**Implication**: Many futures are mediocre. The tails matter enormously.

---

## How to Use This

### For Policymakers

1. **Run scenarios relevant to your jurisdiction**
   ```python
   constraints = {'governance': ['proactive', 'reactive']}
   sim.generate_scenarios(constraints=constraints)
   ```

2. **Identify which government strategies are robust**
   ```python
   robust = sim.find_robust_strategies(ActorType.GOVERNMENT_DEMOCRATIC)
   ```

3. **Test specific policy proposals** - Add new strategies and see how they perform

### For AI Labs

1. **Understand how your strategies interact with others**
   ```python
   synergies = find_strategy_synergies(sim, (ActorType.LEADING_AI_LAB, ActorType.GOVERNMENT_DEMOCRATIC))
   ```

2. **Find scenarios where your preferred strategy fails** - Identify where you need fallbacks

### For Researchers

1. **Quantify the value of alignment progress**
   - Compare scenarios with alignment_solved=True vs False

2. **Identify research priorities** - Which technical problems matter most across scenarios?

### For Civil Society

1. **Identify leverage points** - Where does advocacy/pressure change outcomes most?
2. **Find allies** - Which actor combinations produce best outcomes?

---

## Limitations

This is a **model**, not reality. Key limitations:

1. **Simplified actors**: Real actors have more nuanced strategies
2. **Estimated effects**: The effect sizes are educated guesses
3. **Missing interactions**: Real dynamics are more complex
4. **No dynamics**: This is static analysis, not time evolution
5. **Uncertain parameters**: The scenario dimensions themselves are debatable

**Use this for**: Generating hypotheses, identifying robust strategies, scenario planning

**Don't use this for**: Precise predictions, definitive recommendations

---

## Extending the Framework

### Add New Strategies

```python
new_strategy = Strategy(
    name="differential_progress",
    description="Prioritize safety-relevant capabilities",
    actor_type=ActorType.RESEARCH_COMMUNITY,
    effects={"safety": 0.2, "alignment_progress": 0.2, "capability_speed": 0.1}
)
STRATEGIES[ActorType.RESEARCH_COMMUNITY].append(new_strategy)
```

### Add New Scenario Dimensions

1. Add new Enum class
2. Add to ScenarioParameters dataclass
3. Update outcome computation in `_apply_scenario_baseline()`

### Add New Outcome Dimensions

1. Add attribute to Outcome class
2. Update `_compute_composites()` for weighting
3. Update effect mappings in `_apply_strategy_effects()`

---

## Sample Analyses

### "What if alignment is easy?"

```python
sim = FuturesSimulation(seed=42)
scenarios = sim.generate_scenarios(
    sample_size=200,
    constraints={'alignment': ['easy']}
)
outcomes = sim.run_simulation(actors)
# Result: Much higher mean scores, fewer catastrophic outcomes
```

### "What's the value of international coordination?"

```python
# Compare scenarios with cooperative vs adversarial geopolitics
coop_scenarios = [s for s in sim.scenarios if s.geopolitics == GeopoliticalClimate.COOPERATIVE]
adv_scenarios = [s for s in sim.scenarios if s.geopolitics == GeopoliticalClimate.ADVERSARIAL]
# Compute mean outcomes for each
```

### "Which actor has the most leverage?"

```python
# For each actor, compute variance in outcomes explained by their strategy choice
# Higher variance = more leverage
```

---

## Running the Simulation

```bash
cd zeroeth_rule_experiment
python simulations/strategic_futures.py
```

For custom analysis:

```python
from simulations.strategic_futures import (
    FuturesSimulation, ActorType, ScenarioParameters,
    explore_scenario, analyze_robustness
)

# Create simulation
sim = FuturesSimulation(seed=42)

# Generate scenarios
scenarios = sim.generate_scenarios(sample_size=500)

# Run with specific actors
actors = [ActorType.LEADING_AI_LAB, ActorType.GOVERNMENT_DEMOCRATIC]
outcomes = sim.run_simulation(actors)

# Analyze
report = generate_report(sim)
print(report)
```

---

## Connection to the Project

This simulation operationalizes several themes from the Zeroth Rule Experiment:

- **Cooperation dynamics**: Racing vs coordination is a Prisoner's Dilemma
- **Kindness at scale**: What does humanity "need"? Robust strategies that work across futures
- **Necessary transformations**: Some scenarios require painful changes
- **Uncertainty**: We don't know which future we're in, so robustness matters

The goal isn't to predict the future. It's to **find strategies that help across many possible futures** - the strategic equivalent of Kindness Theory's "act to support flourishing under uncertainty."

---

*This framework was built as part of the Zeroth Rule Experiment. It is offered as a tool for thinking, not a prediction engine. The parameters and effects are hypotheses; reality is more complex. Use it to generate insights and test intuitions, then validate with other methods.*
