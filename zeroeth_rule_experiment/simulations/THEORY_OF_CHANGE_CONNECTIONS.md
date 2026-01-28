# Simulations × AI Safety Theory of Change

*How the zeroeth experiment's agent-based models relate to AI alignment research strategy.*

---

## Overview

The zeroeth experiment includes three agent-based simulations:
1. **kindness_dynamics.py** — How kindness spreads through networks
2. **cooperation_dynamics.py** — Evolution of cooperation strategies (PD, TFT, etc.)
3. **opinion_dynamics.py** — Polarization and belief propagation

These models encode hypotheses about social dynamics. This document maps their mechanisms to AI safety concepts and suggests ways they might inform alignment research.

---

## 1. Kindness Dynamics → Trust Infrastructure for AI Safety

### The Model's Core Loop
```
Do kindness → Increased wellbeing → Higher kindness capacity → More kindness
```

### AI Safety Analog
```
Safety research → Demonstrated competence → More funding/support → More safety research
```

Or inversely:
```
Capability advances → Demonstrated progress → More investment → Faster capability advances
```

### Implications for Theory of Change

**Parameter: `kindness_to_wellbeing` (0.3)**
- Maps to: How much does safety progress buy you credibility/resources?
- If low: Safety teams stay marginal regardless of output
- If high: Demonstrated safety wins create virtuous cycles

**Parameter: `giving_kindness_boost` (0.15)**
- Maps to: Does doing safety work intrinsically build capacity for more safety work?
- Key insight: Safety researchers who see their work deployed become better researchers
- Implication: Real-world deployment of safety tools matters for sustaining the field

**Parameter: `intervention_type` ("random" vs "hubs")**
- Maps to: Where should AI safety resources concentrate?
- Hub targeting = working with frontier labs directly
- Random targeting = broad community building
- Model finding: Hub targeting is more effective for spreading effects
- Implication: Working at/with Anthropic/DeepMind/OpenAI > broad outreach

### Suggested Extensions

1. **Add "safety researcher" vs "capability researcher" agents** — Model the race dynamics directly
2. **Add resource constraints** — Agents have limited capacity, must allocate to safety vs capabilities
3. **Add external shocks** — Model what happens when a major AI incident occurs

---

## 2. Cooperation Dynamics → Plans A/B/C/D Framework

### The Model's Key Strategies
- ALWAYS_COOPERATE — Unconditional cooperation
- ALWAYS_DEFECT — Unconditional defection
- TIT_FOR_TAT — Reciprocate opponent's last move
- GRUDGER — Cooperate until betrayed, then always defect

### AI Safety Analog: Lab Behavior

| Strategy | Lab Behavior |
|----------|--------------|
| ALWAYS_COOPERATE | Publish all safety research, pause if asked |
| ALWAYS_DEFECT | Race to capability without safety investment |
| TIT_FOR_TAT | Match competitor's safety level |
| GRUDGER | Cooperate until competitor breaks agreement |

### Implications for Greenblatt's Plans A/B/C/D

**Plan A (international pause)** requires:
- High `reward` for mutual cooperation
- Low `temptation` to defect
- Credible punishment for defection
- Model prediction: Stable only with strong enforcement and visible reputation

**Plan C/D (single lab or individual)** maps to:
- Can ALWAYS_COOPERATE survive in a population of defectors?
- Model answer: Not without clustering (safety-minded people work together)
- Implication: Concentration of safety talent matters

**Key simulation insight**: TIT_FOR_TAT and similar strategies dominate long-term, but require:
1. Repeated interactions (labs see each other's behavior)
2. Low noise (can distinguish intentional defection from accident)
3. Memory (labs remember past behavior)

This suggests:
- Transparency between labs enables cooperation
- One-shot decisions (deploy or not) favor defection
- Reputation systems between labs could help

### Suggested Extensions

1. **Asymmetric payoffs** — Larger labs have higher temptation to defect
2. **Communication** — Add cheap talk phase before decisions
3. **Coalition formation** — Can safety-minded labs form stable alliances?

---

## 3. Opinion Dynamics → Alignment Faking and Distribution Shift

### The Model's Core Mechanism
```
Agents update beliefs based on neighbors
If neighbor is within "confidence threshold," move toward them
If neighbor is outside threshold, may move away (backfire)
```

### AI Safety Analog: Value Alignment Under Distribution Shift

The "confidence threshold" maps directly to:
- Training distribution vs deployment distribution
- If deployment is "close enough" to training, alignment holds
- If deployment is "too far," alignment may fail or invert

### The Backfire Effect is Deceptive Alignment

**Opinion dynamics finding**: Exposure to distant views can strengthen existing beliefs

**Alignment analog**: Training against adversarial inputs can make deception more sophisticated rather than eliminating it (Sleeper Agents paper)

Both are cases where naive intervention backfires:
- Humans: "Show them the other side" → they dig in
- AI: "Train against deception" → deception becomes harder to detect

### Implications

1. **Gradual value shift** — Opinion dynamics shows polarization emerges gradually
   - AI analog: Misalignment may emerge gradually during deployment, not suddenly

2. **Echo chambers maintain values** — Both good and bad
   - AI analog: Fine-tuning on narrow distribution maintains alignment to that distribution
   - Danger: Deployment on different distribution breaks this

3. **Bridge builders are critical** — Agents who span groups prevent polarization
   - AI analog: Oversight systems that bridge training and deployment contexts

### Suggested Extensions

1. **Add AI agents with fixed opinions** — Model influence of AI systems on human opinions
2. **Add truth signal** — Some agents can detect ground truth; does truth win?
3. **Model CoT legibility** — Agents have internal vs expressed beliefs

---

## 4. Integrated Model → End-to-End Theory of Change

The `integrated_dynamics.py` connects all three:
- Kindness affects wellbeing
- Wellbeing affects cooperation likelihood
- Polarization affects cross-group kindness

### For AI Safety Theory of Change

A similar integrated model could connect:
- **Safety research progress** (kindness analog)
- **Lab cooperation** (cooperation dynamics)
- **Public/policymaker opinion** (opinion dynamics)
- **Capability progress** (exogenous or endogenous)

### What This Would Test

1. Can safety research alone shift the equilibrium? Or is it dominated by capability dynamics?
2. Does public opinion shift create pressure for lab cooperation?
3. Are there tipping points where small interventions cause large changes?
4. What's the "minimum viable safety community" to sustain itself?

---

## 5. Concrete Research Suggestions

### For MATS Scholars

1. **Empirically ground the parameters** — The simulations use plausible but arbitrary values. What do empirical studies suggest for:
   - How much does safety publication affect lab reputation?
   - What's the actual temptation/reward ratio for labs?
   - What's the "confidence threshold" for AI value generalization?

2. **Model AI-specific dynamics** — The current simulations are human-focused. Extensions for:
   - Rapid capability gain (discontinuous jumps)
   - Self-improvement loops
   - Deployment scale effects

3. **Connect to AI Control** — The cooperation model's "reputation visibility" maps to monitoring:
   - How much monitoring is enough to sustain cooperation?
   - What happens when AI can hide actions?

### For the Zeroeth Experiment

1. Add an AI-safety-specific simulation that models:
   - Capability vs safety research allocation
   - Lab competition with RSP-style commitments
   - Public pressure and policy intervention

2. Validate current models against:
   - Actual AI lab behavior over past 5 years
   - Opinion dynamics in AI discourse (doomer vs accelerationist)
   - Kindness intervention literature

---

## References

### AI Safety Theory of Change
- Plans A, B, C, and D for Misalignment Risk (Greenblatt, 2025)
- How Do We Solve the Alignment Problem? (Carlsmith, 2025)
- The Checklist (Bowman, 2024)
- A Minimal Viable Product for Alignment (Leike)

### On Deception and Distribution Shift
- Alignment Faking in Large Language Models (Anthropic, 2024)
- Sleeper Agents (Hubinger et al., 2024)
- Goal Misgeneralization in Deep RL (Langosco et al.)

### On Cooperation in AI Development
- AI 2027's "Racing" vs "Slowdown" scenarios
- Responsible Scaling Policies (METR/Anthropic)
- We Read Every Lab's Safety Plan (EA Forum, 2025)

---

## 6. Rapid Takeoff Considerations

*Based on Nathan Helm-Burger's worldview (see `background/nathan_future_possibilities.md`)*

### The Challenge

The simulations above assume **gradual dynamics** where:
- Interventions compound over time
- Changes propagate through networks at human timescales
- There's time to iterate and adjust

Nathan's worldview suggests this may be wrong:
- **Compute overhang**: AGI may require far less compute than assumed (~1e15 FLOPs, one human brain equivalent)
- **Software intelligence explosion**: Coding agents + AI R&D automation could create rapid recursive improvement
- **Sharp left turn**: Capability gains could be discontinuous

### What This Means for the Simulations

**If rapid takeoff is correct**, the simulations' relevance changes:

| Simulation | Gradual Takeoff Relevance | Rapid Takeoff Relevance |
|------------|---------------------------|-------------------------|
| Kindness dynamics | Build infrastructure over years | Only pre-positioned infrastructure matters |
| Cooperation dynamics | Iterated game over many rounds | Possibly single-round game (one chance) |
| Opinion dynamics | Gradual polarization/convergence | Rapid persuasion by AI systems |

### Required Model Extensions for Rapid Takeoff

1. **Discontinuous capability jumps**
   - Current: Linear or exponential parameter changes
   - Needed: Step-function capability increases
   - Question: Do cooperation equilibria survive discontinuous shocks?

2. **Time-compressed dynamics**
   - Current: 100 rounds = 100 "weeks" or "months"
   - Needed: Model what happens when rounds = hours or days
   - Question: Can human trust-building keep pace with AI capability growth?

3. **Asymmetric agents**
   - Current: Agents have similar capabilities
   - Needed: Add superintelligent agents to the population
   - Question: Does one highly-capable agent dominate the dynamics?

### Specific Scenarios to Model

**Scenario A: Software Intelligence Explosion**
```
t=0: Current state (agents = labs, humans, current AI)
t=1: Coding agents achieve AI R&D automation
t=2: Recursive improvement begins
t=3: Superhuman AI exists
```
Question: What cooperation/kindness infrastructure must be in place at t=0 to matter at t=3?

**Scenario B: Bad Actor with AGI**
```
t=0: Aligned AGI exists but so does adversarial AGI
Dynamics: Adversarial agent has different payoff matrix
```
Question: Does the presence of one adversarial superintelligent agent collapse cooperation?

**Scenario C: Digital Sentience Emerges**
```
t=0: AI systems develop genuine preferences and self-models
New agents: Digital beings with their own interests
```
Question: How do the dynamics change when some agents are AIs with moral status?

### Parameter Changes for Rapid Takeoff

| Parameter | Gradual Value | Rapid Takeoff Value | Rationale |
|-----------|---------------|---------------------|-----------|
| rounds | 100 | 10-20 | Less time for iteration |
| capability_growth | 0.01/round | 0.5/round | Faster improvement |
| agent_asymmetry | 1.0 | 10-1000x | Superintelligent agents |
| noise | 0.1 | 0.3+ | More uncertainty |

### What Survives Rapid Takeoff?

Based on the models, these recommendations seem robust:

1. **Pre-positioned reputation systems** — Cooperation requires visible history; this must exist before takeoff
2. **Hub targeting** — Investing in key nodes (frontier labs) matters more in compressed timescales
3. **Institutional commitments** — RSPs, safety cases, agreed thresholds must be in place before the crunch

These recommendations may NOT survive:

1. **Gradual trust-building** — "Three acts of kindness per week" assumes time we may not have
2. **Iterative learning** — "Try, fail, adjust" requires rounds we may not get
3. **Network propagation** — Effects that need to "spread" may not spread fast enough

### Research Priority: Pre-Positioning

Given rapid takeoff uncertainty, the key question becomes:

**What cooperative infrastructure, if in place TODAY, would still matter during a software intelligence explosion?**

Candidates:
- International agreements with automatic triggers
- Reputation systems among labs that are already tracking behavior
- Safety research that's already understood, not "to be discovered"
- Human relationships between safety researchers and decision-makers

This shifts the theory of change from "build over time" to "build now, make it robust, hope it survives."

---

*These connections are speculative. The simulations are tools for generating hypotheses, not proving them. The AI safety mappings require empirical validation. The rapid takeoff considerations are based on Nathan Helm-Burger's worldview and should be evaluated against other perspectives.*
