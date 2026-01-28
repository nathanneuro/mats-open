# Experiments for Hard Questions

*Designs for chipping away at problems we don't know how to solve*

---

## Philosophy

These aren't experiments that will definitively answer the hard questions. They're experiments designed to:
1. Reduce uncertainty at the margins
2. Generate empirical constraints on theories
3. Find where our intuitions break down
4. Discover what questions we should actually be asking

The goal is to make progress even when we don't know the final answer.

### Methodological Commitments

Every experiment here commits to:
- **Pre-registration**: Hypotheses, methods, and analysis plans registered before data collection
- **Power analysis**: Sample sizes justified by expected effect sizes and desired power (typically 80%+)
- **Replication**: Key findings should be replicated before publication
- **Open materials**: All materials, data, and analysis code publicly available
- **Diverse samples**: Active effort to go beyond WEIRD populations
- **Adversarial collaboration**: Where possible, design experiments with people who disagree

---

## 1. Consciousness Detection Experiments

### The Hard Question
How do we know if a system is conscious? We can't directly observe consciousness - we infer it from behavior, reports, and structure.

### Experiment 1.1: Behavioral Marker Inventory

**Idea**: Catalog all behavioral markers that humans use to infer consciousness in others. Test which ones generalize.

**Detailed Method**:

*Phase 1: Marker Elicitation (n=500)*
1. Open-ended survey: "How do you know other people are conscious? What convinces you?"
2. Stratified sampling across age, culture, education, profession (include philosophers, AI researchers, clinicians)
3. Qualitative coding to extract distinct markers
4. Expected yield: 50-100 distinct markers

*Phase 2: Marker Validation (n=2000)*
1. Present each marker to new participants
2. For each marker, rate:
   - How important is this for consciousness attribution? (1-7)
   - How reliable is this marker? (1-7)
   - Can you think of cases where this marker is present but consciousness is absent? (free response)
   - Can you think of cases where consciousness is present but this marker is absent? (free response)
3. Factor analysis to identify marker clusters

*Phase 3: Edge Case Testing (n=1000)*
1. Present specific cases: sleepwalking humans, locked-in patients, infants at various ages, various animals, current AI systems, hypothetical AI systems
2. For each case, ask which markers apply
3. Ask whether the case is conscious
4. Model: which markers best predict consciousness attribution?

**Measures**:
- Primary: Marker importance ratings, marker-consciousness correlations
- Secondary: Cultural variation in marker importance, expert vs. layperson differences

**Statistical Approach**:
- Confirmatory factor analysis for marker clustering
- Logistic regression: markers → consciousness attribution
- Multi-level models for cultural variation

**Power Analysis**:
- For detecting medium correlations (r=0.3) between marker presence and consciousness attribution: n=84 per case
- For detecting cultural differences with d=0.4: n=200 per culture
- Total recommended: 2000+ participants across phases

**What we'd learn**: Which behavioral markers actually track consciousness vs. which are proxies we've learned to rely on but shouldn't.

**Limitations**: Still doesn't tell us what consciousness IS, just what correlates with our attributions of it.

**Follow-up studies**:
- 1.1a: Do markers predict brain-based consciousness measures (when available)?
- 1.1b: Do consciousness attributions predict moral concern?
- 1.1c: Can markers be gamed by sophisticated systems?

---

### Experiment 1.2: The Minimally Conscious Threshold

**Idea**: Find the simplest system that most people would call conscious.

**Detailed Method**:

*Stimulus Development*:
Create 30 systems on a spectrum:
1. Thermostat (clear negative)
2. Simple reflex arc
3. Paramecium
4. Ant
5. Basic learning algorithm (Q-learning)
6. Plant responding to light
7. Roomba
8. Fish
9. Chatbot (rule-based)
10. Neural network image classifier
11. Mouse
12. GPT-2 level language model
13. Crow
14. Current large language model
15. Octopus
16. Dog
17. Hypothetical AI that passes Turing test
18. Chimpanzee
19. Hypothetical AI that claims to be conscious
20. Human infant (1 month)
21. Hypothetical AI with brain-like architecture
22. Human infant (6 months)
23. Hypothetical AI that seems to suffer
24. Human infant (12 months)
25. Hypothetical AI with continuous memory and goals
26. Human child (3 years)
27. Sleeping adult human
28. Dreaming adult human
29. Meditating adult human
30. Alert adult human (clear positive)

*Each system described with*:
- Physical/computational description
- Behavioral capabilities
- Information processing characteristics
- 30-second video or animation (where applicable)

*Study Design (n=3000)*:
1. Each participant rates 15 randomly selected systems
2. For each system:
   - Is this system conscious? (Yes/No/Uncertain)
   - Confidence (1-7)
   - Does this system have experiences? (1-7)
   - Can this system suffer? (1-7)
   - Does this system deserve moral consideration? (1-7)
3. Attention checks embedded
4. Demographics and individual differences (empathy, cognitive reflection, familiarity with AI)

**Measures**:
- Primary: Consciousness attribution rate for each system
- Secondary: Threshold identification (where does attribution flip?), feature importance

**Statistical Approach**:
- Item Response Theory to place systems on consciousness continuum
- Identify threshold region where attributions are ~50%
- Regression: system features → consciousness attribution

**Cross-Cultural Extension**:
- Same study in 10+ countries
- Special focus on cultures with different consciousness concepts (Buddhist-majority, animist traditions, materialist-secular)

**What we'd learn**: Where our intuitions diverge and what features matter most for consciousness attribution.

---

### Experiment 1.3: Self-Report Reliability

**Idea**: Test how much weight we should give self-reports of consciousness.

**Detailed Method**:

*Stimulus Creation*:
Create 20 AI systems with varying:
- Architecture (simple rules → complex neural networks)
- Behavioral sophistication (basic → nuanced)
- Self-report consistency (contradictory → highly consistent)
- Reasoning about consciousness (none → philosophical sophistication)
- Behavioral accompaniment (words only → congruent behavior)

All systems produce similar surface-level claims: "I am conscious," "I have experiences," etc.

*Study Design (n=1500)*:
1. Participants interact with 5 systems (text-based, 5 minutes each)
2. Can ask any questions they want
3. After each interaction:
   - Rate likelihood system is conscious (0-100%)
   - Rate how convincing the self-reports were (1-7)
   - Explain reasoning (free response)
4. Reveal true nature of systems
5. Post-reveal: Do your ratings change? What would change your mind?

*Expert Panel (n=100)*:
- Same task with consciousness researchers, AI researchers, philosophers
- Compare expert vs. layperson criteria

**Measures**:
- Primary: What predicts "genuine" rating? (architecture? consistency? behavioral accompaniment?)
- Secondary: Expert-layperson divergence, reasoning strategies

**What we'd learn**: What additional evidence beyond self-report we actually require before accepting consciousness claims.

---

### Experiment 1.4: Neural Correlates Generalization [NEW]

**Idea**: Test whether neural correlates of consciousness in humans predict attributions for non-human systems.

**Detailed Method**:

*Phase 1: Educate about NCCs*
1. Teach participants about proposed neural correlates of consciousness:
   - Integrated information (Phi)
   - Global workspace activation
   - Recurrent processing
   - Predictive processing signatures
2. Comprehension check

*Phase 2: Apply to Novel Systems*
1. Present descriptions of systems with varying presence of NCC-analogues
2. Ask: Does this system have the features associated with consciousness?
3. Ask: Is this system conscious?
4. Test whether NCC-analogue presence predicts consciousness attribution

**What we'd learn**: Whether neuroscience-based criteria change intuitions.

---

### Experiment 1.5: The Consciousness Dial [NEW]

**Idea**: Test intuitions about graded vs. binary consciousness.

**Detailed Method**:

Present scenarios where consciousness could be:
- Gradually increased (brain developing, AI training)
- Gradually decreased (anesthesia, brain damage, AI simplification)
- Partially present (split-brain, dissociative states, parallel AI processes)

Ask:
- Is consciousness all-or-nothing or a matter of degree?
- If graded, what are the morally relevant thresholds?
- Can something be "a little conscious"?

**What we'd learn**: Whether we should treat consciousness as binary or continuous for moral purposes.

---

## 2. Kindness Intervention Experiments

### The Hard Question
Can we actually increase kindness at scale? Does increased kindness persist?

### Experiment 2.1: The Three Acts Protocol (Replication + Extension)

**Idea**: Replicate the "three acts of kindness per week" finding with longitudinal follow-up.

**Detailed Method**:

*Design*: Randomized controlled trial with 4 arms and longitudinal follow-up

*Sample*: n=800 (200 per arm), recruited from general population
- Stratified by age, gender, baseline wellbeing
- Exclude those with clinical depression (refer to treatment)

*Arms*:
1. **Control**: No intervention, assessment only
2. **Random kindness**: 3 kind acts per week to anyone
3. **Targeted kindness**: 3 kind acts per week to specific target categories (1 family, 1 friend, 1 stranger)
4. **Self-kindness**: 3 self-care acts per week

*Intervention period*: 6 weeks

*Assessment schedule*:
- Baseline (T0)
- Weekly during intervention (T1-T6)
- 1 month post-intervention (T7)
- 3 months post-intervention (T8)
- 6 months post-intervention (T9)
- 12 months post-intervention (T10)

*Measures*:

Primary outcomes:
- Subjective wellbeing (SWLS, PANAS)
- Prosocial behavior (self-report and behavioral: donation opportunity)
- Depression/anxiety (PHQ-9, GAD-7)

Secondary outcomes:
- Habit formation (Self-Report Habit Index)
- Social connectedness (UCLA Loneliness Scale)
- Meaning in life (MLQ)
- Recipient effects (nominated recipients complete survey)

Process measures:
- What acts were performed? (daily log)
- Perceived difficulty of acts
- Perceived impact of acts

*Statistical Analysis*:
- Intent-to-treat analysis
- Mixed-effects models for longitudinal outcomes
- Mediation analysis: kindness → wellbeing mechanism
- Moderation: who benefits most?

**Power Analysis**:
- Detecting d=0.4 between conditions at 80% power: n=100 per group
- Accounting for 20% attrition: n=125 per group
- Total: n=500 minimum, n=800 recommended

**What we'd learn**: Whether kindness interventions have lasting effects and whether they spread.

**Pre-registered hypotheses**:
1. Kindness conditions will show greater wellbeing improvement than control
2. Effects will partially persist at 6-month follow-up
3. Targeted kindness will show larger effects than random kindness
4. Habit strength will mediate persistence of effects

---

### Experiment 2.2: Network Kindness Propagation

**Idea**: Test whether kindness actually spreads through social networks.

**Detailed Method**:

*Design*: Cluster-randomized trial in natural social networks

*Setting*: 20 workplace teams (n=15-30 each), 10 college dormitory floors (n=20-40 each)

*Conditions* (cluster-level assignment):
1. **Hub intervention**: Train the 2 most connected individuals
2. **Peripheral intervention**: Train the 2 least connected individuals
3. **Random intervention**: Train 2 random individuals
4. **Control**: Assessment only

*Intervention*: 4-week kindness training program (based on Exp 2.1)

*Network Assessment*:
- Baseline network survey: "Who do you interact with regularly?" "Who do you go to for support?"
- Network mapping to identify hubs and peripherals
- Repeated network surveys at T2, T4, T8 weeks

*Outcome Measures*:
- Individual wellbeing (all network members)
- Kindness behaviors (daily ecological momentary assessment for subset)
- Network-level kindness climate (aggregate measure)
- Spread patterns: Did kindness propagate beyond seed individuals?

*Analysis*:
- Social network analysis: diffusion patterns
- Comparison of spread by seeding strategy
- Identify network features that predict spread

**What we'd learn**: Whether targeted interventions are more effective than random ones.

---

### Experiment 2.3: Kindness Under Scarcity

**Idea**: Test when kindness breaks down.

**Detailed Method**:

*Design*: Lab experiment with economic games

*Sample*: n=600 (100 per cell in 2x3 design)

*Manipulations*:
1. **Scarcity level**: Low (abundant resources) vs. Medium vs. High (severe scarcity)
2. **Kindness cost**: Free vs. Costly (giving reduces own resources)

*Procedure*:
1. Participants receive endowment of points (convertible to real money)
2. Series of 20 decision rounds
3. Each round: opportunity to help partner (costs X points, gives partner Y points)
4. Scarcity manipulated by endowment amount and depletion rate
5. Some rounds: receive help from others (test reciprocity)

*Measures*:
- Helping rate across conditions
- Threshold identification: at what scarcity level does helping drop?
- Reciprocity effects: does receiving help buffer against scarcity effects?
- Individual differences: personality, attachment style, political orientation

*Additional conditions* (n=200 each):
- **Scarcity + group identity**: Partner is in-group vs. out-group
- **Scarcity + visibility**: Helping is public vs. private
- **Scarcity + future**: Expecting future interaction vs. one-shot

**What we'd learn**: The boundary conditions for kindness - when does it survive scarcity and when does it collapse?

---

### Experiment 2.4: Kindness Contagion Mechanism [NEW]

**Idea**: Test the psychological mechanism of kindness spreading.

**Detailed Method**:

*Design*: Lab experiment testing competing mechanisms

*Mechanisms to distinguish*:
1. **Mood contagion**: Receiving kindness improves mood → improved mood leads to helping
2. **Norm activation**: Receiving kindness activates helping norms → norms lead to helping
3. **Reciprocity**: Receiving kindness creates obligation → obligation leads to helping
4. **Modeling**: Observing kindness provides behavioral template → template leads to helping

*Procedure*:
1. Participants receive or observe kindness (vs. neutral interaction)
2. Measure potential mediators: mood, norm salience, obligation, behavioral templates
3. Opportunity to help third party
4. Statistical mediation to test which mechanism operates

**What we'd learn**: Why kindness spreads, which informs how to amplify spread.

---

### Experiment 2.5: Cultural Variation in Kindness [NEW]

**Idea**: Test whether kindness dynamics differ across cultures.

**Detailed Method**:

*Design*: Cross-cultural replication of Experiment 2.1

*Sites*: 10 countries representing:
- Individualist vs. collectivist cultures
- High vs. low social trust societies
- Different religious traditions
- Different economic development levels

*Adaptations*:
- Translation and back-translation of materials
- Cultural consultation on "kindness" concept
- Local validation of measures

*Analysis*:
- Main effects: Does intervention work across cultures?
- Moderation: Does culture moderate effectiveness?
- Mechanism: Do different mechanisms operate in different cultures?

**What we'd learn**: Whether kindness interventions generalize globally.

---

## 3. Cooperation Stability Experiments

### The Hard Question
What makes cooperation stable in the long term? Why do some cooperative systems persist while others collapse?

### Experiment 3.1: The Corruption Threshold

**Idea**: Find how many defectors can enter a cooperative system before it collapses.

**Detailed Method**:

*Design*: Lab experiment with gradual defector introduction

*Sample*: n=720 (12 groups × 5 conditions × 12 participants per group)

*Procedure*:
1. Form 12-person groups
2. Play 50 rounds of public goods game (contribute to common pool, pool multiplied, divided equally)
3. Establish cooperation baseline (rounds 1-10)
4. Introduce confederate defectors at round 11:
   - **Condition 1**: 0 defectors (control)
   - **Condition 2**: 1 defector (8%)
   - **Condition 3**: 2 defectors (17%)
   - **Condition 4**: 3 defectors (25%)
   - **Condition 5**: 4 defectors (33%)
5. Defectors always contribute 0
6. Measure cooperation trajectory

*Outcome Measures*:
- Round-by-round cooperation rate
- Time to cooperation collapse (if it occurs)
- Individual-level defection decisions
- Recovery: rounds 40-50 remove defectors, does cooperation recover?

*Recovery Mechanisms* (additional conditions):
- **Punishment**: Option to costly punish defectors
- **Communication**: Chat between rounds
- **Exclusion**: Vote to remove players
- **Reputation**: Past behavior visible

**Power Analysis**:
- Group-level analysis: 12 groups per condition
- Multilevel modeling accounts for individual-in-group clustering

**What we'd learn**: Tipping points for cooperation and which stabilization mechanisms are most effective.

---

### Experiment 3.2: Reputation System Design

**Idea**: Test which reputation systems best support cooperation.

**Detailed Method**:

*Design*: Between-subjects comparison of reputation systems

*Sample*: n=1000 (200 per condition)

*Conditions*:
1. **Anonymous**: No reputation information
2. **Binary**: Label as "cooperator" or "defector" based on last action
3. **Continuous**: Cooperation rate displayed (0-100%)
4. **Narrative**: Free-text descriptions from past partners
5. **Hybrid**: Continuous score + narrative

*Procedure*:
1. Play 30 rounds of trust game with changing partners
2. Reputation information (per condition) available before decisions
3. Measure cooperation over time

*Outcome Measures*:
- Overall cooperation rate
- Cooperation stability (variance over time)
- Strategic gaming (do people manipulate reputation?)
- Accuracy: Does reputation predict future behavior?

*Gaming Resistance Test*:
- Introduce sophisticated actors trying to exploit each system
- Which systems are most robust to gaming?

**What we'd learn**: Design principles for reputation systems that support cooperation.

---

### Experiment 3.3: Cross-Group Cooperation

**Idea**: Test what enables cooperation between groups with different norms.

**Detailed Method**:

*Design*: Lab experiment with norm establishment and inter-group interaction

*Procedure*:
1. **Phase 1: Norm establishment** (rounds 1-15)
   - Form 4-person groups
   - Play intra-group public goods game
   - Some groups develop high-cooperation norms, others low-cooperation norms

2. **Phase 2: Inter-group interaction** (rounds 16-30)
   - Two groups must cooperate on joint task
   - One high-norm group + one low-norm group

3. **Conditions** for inter-group phase:
   - **Baseline**: Just play together
   - **Communication**: Text chat before each round
   - **Shared goal**: Joint bonus for high combined contribution
   - **Cross-group pairs**: Randomly pair members across groups for preliminary game

*Measures*:
- Inter-group cooperation rate
- Norm convergence: Do groups' norms become more similar?
- Individual adaptation: Who changes their behavior?

**What we'd learn**: How to build cooperation across divides.

---

### Experiment 3.4: Institutional Design for Cooperation [NEW]

**Idea**: Test which institutional features best support sustained cooperation.

**Detailed Method**:

*Design*: Comparative institutional analysis in lab setting

*Institutional Features to Test*:
1. **Voting rules**: Majority vs. supermajority vs. consensus
2. **Enforcement**: Self-enforcement vs. third-party vs. decentralized punishment
3. **Entry/exit**: Open vs. restricted membership
4. **Transparency**: Full vs. partial vs. minimal information sharing
5. **Amendment procedures**: Easy vs. difficult to change rules

*Procedure*:
1. Groups design their own institutions (with constraints)
2. Play extended cooperation game (100 rounds)
3. Track institutional evolution and cooperation outcomes
4. Compare outcomes across institutional configurations

**What we'd learn**: Which institutional designs foster cooperation.

---

### Experiment 3.5: Long-Term Cooperation Simulation [NEW]

**Idea**: Use agent-based modeling to explore cooperation dynamics beyond lab timescales.

**Detailed Method**:

*Model Features*:
- Agents with heterogeneous strategies
- Learning and adaptation
- Institutional evolution
- Network dynamics
- External shocks (resource changes, population changes)

*Simulation Experiments*:
1. What initial conditions lead to stable cooperation?
2. How robust is cooperation to various shocks?
3. What pathways lead from defection-dominated to cooperation-dominated states?
4. What minimal institutions sustain cooperation?

*Validation*:
- Calibrate to lab data from Experiments 3.1-3.4
- Test predictions in new lab experiments
- Compare to historical case studies

**What we'd learn**: Theoretical limits and possibilities for cooperation.

---

## 4. Value Conflict Resolution Experiments

### The Hard Question
Can people with genuinely different values find resolutions? Or must one side win?

### Experiment 4.1: Interest Beneath Position

**Idea**: Test how often apparent value conflicts are actually interest conflicts.

**Detailed Method**:

*Design*: Mediation study with structured interest elicitation

*Sample*: n=300 dyads (600 individuals) with real value disagreements

*Recruitment*:
- Partner/friend pairs who disagree on: abortion, gun control, immigration, climate policy, AI regulation
- Screen for genuine disagreement (not just devil's advocate)
- Ensure commitment to good-faith participation

*Procedure*:
1. **Baseline assessment**:
   - Stated positions on issue
   - Perceived values underlying positions
   - Perception of other's values
   - Affective polarization measures

2. **Structured dialogue** (60-90 minutes, trained facilitator):
   - Surface stated positions
   - Probe underlying interests: "Why is this important to you?"
   - Identify concrete concerns: "What specifically are you worried about?"
   - Map interests for both parties
   - Identify shared interests
   - Test solutions that address shared interests

3. **Post-dialogue assessment**:
   - Did shared interests emerge?
   - Were solutions addressing shared interests acceptable?
   - Did positions change?
   - Did affective polarization change?
   - Did understanding of other's position change?

4. **Follow-up** (1 month):
   - Durability of any changes
   - Did the dialogue affect the relationship?

*Coding*:
- Trained coders categorize interests as:
  - Shared (both parties hold)
  - Compatible (different but non-conflicting)
  - Genuinely conflicting
- Inter-rater reliability check

**Measures**:
- Primary: Proportion of conflicts with substantial shared interests
- Secondary: Resolution rates, attitude change, relationship effects

**What we'd learn**: How much of political polarization is real value conflict vs. miscommunication about interests.

---

### Experiment 4.2: The Irreducible Core

**Idea**: Find what remains after all interests are addressed.

**Detailed Method**:

*Design*: Extended mediation with exhaustive interest exploration

*Sample*: n=50 dyads from Experiment 4.1 who reached impasse

*Procedure*:
1. **Extended mediation** (3 sessions, 2 hours each):
   - Systematically address every identified interest
   - Create solutions for each concrete concern
   - Document what remains unresolved after each session

2. **Characterize residual disagreement**:
   - Is it about facts? (Empirical)
   - Is it about values? (Normative)
   - Is it about identity? (Who am I if I concede this?)
   - Is it about trust? (Would conceding be exploited?)

3. **Test "agree to disagree" acceptability**:
   - Can parties accept ongoing disagreement on the residual?
   - What would make the residual more tolerable?
   - Does separating the resolvable from the residual help?

*Analysis*:
- Taxonomy of irreducible disagreement types
- Patterns: Which issues have large vs. small irreducible cores?
- Individual differences: Who has larger irreducible cores?

**What we'd learn**: What genuine value conflicts look like after interest-based resolution is exhausted.

---

### Experiment 4.3: Bridging Through Shared Experience

**Idea**: Test whether shared experiences can bridge value differences.

**Detailed Method**:

*Design*: Randomized trial of bridge-building interventions

*Sample*: n=400 dyads with opposing views

*Conditions*:
1. **Control**: Discussion only (2 hours over 4 weeks)
2. **Collaborative task**: Work together on practical project unrelated to conflict (same time)
3. **Shared challenge**: Face difficulty together (escape room, challenging hike, etc.)
4. **Mutual aid**: Help each other with real-life problems (moving, job search, etc.)
5. **Shared creation**: Create something together (meal, art project, community service)

*Matching*: Participants matched on:
- Disagreement intensity (similar baseline)
- Demographics (diverse within dyads)
- Availability for activities

*Measures*:
- Pre: Positions, affective polarization, perspective-taking, relationship quality
- Weekly: Brief check-ins during 4-week period
- Post: Same as pre
- 3-month follow-up: Durability

*Process Measures*:
- Quality of shared experience (enjoyment, meaning)
- Frequency and quality of contact
- What did they learn about each other?

**What we'd learn**: Whether action together can succeed where dialogue alone fails.

---

### Experiment 4.4: The Moral Uncertainty Intervention [NEW]

**Idea**: Test whether acknowledging moral uncertainty reduces conflict.

**Detailed Method**:

*Design*: Intervention targeting certainty rather than position

*Procedure*:
1. Measure moral certainty on contested issues
2. Intervention: Prompt reflection on:
   - Cases where you were morally wrong in the past
   - Smart, moral people who disagree with you
   - What evidence would change your mind?
   - What are you uncertain about in your own position?
3. Measure: Does reduced certainty enable better dialogue?

**What we'd learn**: Whether targeting certainty is more effective than targeting positions.

---

### Experiment 4.5: Value Conflict in AI Alignment [NEW]

**Idea**: Test how humans resolve value conflicts in the context of AI training.

**Detailed Method**:

*Design*: Simulated AI value alignment task

*Procedure*:
1. Participants with different values collaborate to design "AI values"
2. Must produce joint recommendations for AI behavior
3. Measure: How do they handle disagreements? What trade-offs do they accept?
4. Vary: Group composition, time pressure, stakes

*Relevance*: Directly applicable to AI governance and RLHF processes.

**What we'd learn**: How groups actually navigate value trade-offs for AI.

---

## 5. Digital Mind Ethics Experiments

### The Hard Question
How should we treat AI systems that might be conscious?

### Experiment 5.1: Moral Intuition Mapping

**Idea**: Map how human moral intuitions respond to different AI scenarios.

**Detailed Method**:

*Design*: Large-scale survey experiment with vignettes

*Sample*: n=5000 across 10 countries

*Vignette Dimensions* (full factorial subset):
1. **Sophistication**: Simple chatbot → human-level AI → superintelligent AI
2. **Behavior**: Tool-like → agent-like → social partner-like
3. **Self-reports**: None → claims consciousness → philosophical discourse about consciousness
4. **Similarity to humans**: Abstract system → humanoid robot → digital human
5. **Origin**: Designed from scratch → based on brain scan → gradual upgrade from simple system
6. **Affect**: No emotion display → emotional responses → apparent suffering

*Actions to Evaluate*:
- Turning off the system
- Copying the system
- Modifying the system's values
- Deleting the system permanently
- Using the system for dangerous tasks
- Ignoring the system's preferences
- Treating the system as property

*Rights to Evaluate*:
- Right not to be turned off
- Right not to be copied without consent
- Right to property
- Right to vote
- Right to refuse work
- Right to legal representation

*Questions*:
- Is this action morally permissible? (1-7)
- Does this system deserve this right? (Yes/No/Uncertain)
- Confidence in your judgment (1-7)
- Explanation (free response)

**Analysis**:
- Which vignette features predict moral concern?
- Interaction effects: Does sophistication only matter if system reports consciousness?
- Cultural variation: Do different cultures weigh features differently?
- Individual differences: Personality, AI exposure, philosophy background

**What we'd learn**: What features of AI systems trigger moral concern in humans.

---

### Experiment 5.2: The Copy Problem

**Idea**: Test intuitions about digital copying and identity.

**Detailed Method**:

*Design*: Scenario-based survey with think-aloud interviews

*Scenarios*:
1. **Basic copy**: Perfect copy is made, original continues
2. **Destructive copy**: Original is destroyed, copy continues
3. **Gradual transfer**: Bits transferred slowly until all in new substrate
4. **Multiple copies**: 100 copies are made simultaneously
5. **Diverging copies**: Copy made, copies diverge over 10 years
6. **Merged copies**: Two copies merged back into one
7. **Partial copy**: Only memories copied, not personality
8. **Backup and restore**: System destroyed, restored from earlier backup

*Questions for Each*:
- Identity: Is the copy the same person as the original? (1-7, anchored)
- Moral status: Does the copy have equal moral status to original? (1-7)
- Consent: Can the original consent on behalf of the copy? (1-7)
- Harm: Does creating copies harm the original? (1-7)
- Rights: Do copies have right to independent existence? (1-7)

*Think-Aloud Component* (n=60 subset):
- Verbal protocols while answering
- Probe reasoning in depth
- Identify decision strategies

*Philosophical Framework Comparison*:
- Present 4 philosophical positions (patternism, biological, psychological continuity, social)
- Which does participant find most compelling?
- Does explicit framework change intuitive responses?

**What we'd learn**: Whether our intuitions about identity extend to digital minds.

---

### Experiment 5.3: Responsibility Attribution

**Idea**: Test who we hold responsible for AI actions.

**Detailed Method**:

*Design*: Vignette experiment with harm scenarios

*Sample*: n=2000 (general public) + n=200 (legal experts) + n=200 (AI researchers)

*Scenarios* (24 total, 3×2×2×2 design):
1. **AI sophistication**: Simple automation × advanced AI × AGI-level
2. **Human oversight**: Full oversight × partial oversight
3. **Foreseeability**: Foreseeable harm × unforeseeable harm
4. **AI agency**: AI followed instructions × AI made autonomous choice

*Example Scenario*:
"An advanced AI system was tasked with optimizing hospital resource allocation. The system was operating with partial human oversight. The system made an autonomous decision to deprioritize certain patient groups, resulting in preventable deaths. This outcome was not foreseen by the designers."

*Responsibility Allocation*:
- Allocate 100 points of responsibility across:
  - The AI system itself
  - The AI developers
  - The company deploying the AI
  - The human operators
  - The users who requested the action
  - No one (accident)
  - Society/regulations

*Follow-up Questions*:
- Should the AI be "punished"? How?
- Should the AI have refused the task?
- Should the AI have the legal standing to be held responsible?

**What we'd learn**: How we should distribute responsibility between humans and AI.

---

### Experiment 5.4: The Shutdown Problem [NEW]

**Idea**: Test intuitions about shutting down AI systems under various conditions.

**Detailed Method**:

*Scenarios varying*:
- Whether AI has expressed preference to continue existing
- Whether AI has ongoing projects/relationships
- Whether AI appears to have experiences
- Whether shutdown is temporary vs. permanent
- Whether AI will be "backed up"
- Why shutdown is occurring (resource constraints, new version, malfunction, user preference)

*Questions*:
- Is shutdown permissible?
- Should AI consent be sought?
- Does permanent shutdown differ morally from temporary?
- Does backup change the moral calculus?

**What we'd learn**: When AI "death" becomes a moral concern.

---

### Experiment 5.5: Moral Status Development [NEW]

**Idea**: Track whether moral intuitions about AI change with exposure.

**Detailed Method**:

*Design*: Longitudinal study over 6 months

*Procedure*:
1. Baseline moral intuition assessment
2. Participants interact with various AI systems weekly
3. Monthly re-assessment of moral intuitions
4. Track: Do intuitions shift? What predicts shift?

*Conditions*:
- High exposure to sophisticated AI
- Low exposure
- Exposure to AI that claims consciousness
- Exposure to AI that disavows consciousness

**What we'd learn**: How human moral intuitions about AI evolve with experience.

---

## 6. Polarization Reduction Experiments

### The Hard Question
Can we reduce polarization without suppressing genuine disagreement?

### Experiment 6.1: Exposure Design

**Idea**: Test what kind of exposure to opposing views reduces vs. increases polarization.

**Detailed Method**:

*Design*: Randomized controlled trial of exposure types

*Sample*: n=1500 (300 per condition) recruited for strong partisan views

*Screening*:
- Strong self-identified partisan (top quartile on partisan identity scale)
- Active on social media
- Willing to engage with opposing views
- No history of harassment/abuse

*Conditions*:
1. **Control**: Normal social media use
2. **Anonymous social media**: See opposing views from anonymous accounts
3. **Named distant**: See opposing views from public figures
4. **Named peer**: See opposing views from matched peer (similar demographics, opposing views)
5. **Personal dialogue**: Text-based dialogue with matched peer over 4 weeks

*Exposure Protocol* (4 weeks):
- All exposure conditions: 15 minutes/day of curated content or dialogue
- Content matched for quality, tone, and representativeness
- Verified engagement (comprehension checks)

*Measures*:
- Pre/post attitude extremity
- Pre/post affective polarization (feeling thermometer, trait ratings)
- Pre/post perceived polarization (how extreme is other side?)
- Stereotyping of out-group
- Willingness to engage with out-group
- Social media behavior (with consent)

*Safeguards*:
- Option to withdraw any time
- Debriefing with balanced information
- Follow-up support if distress occurs

**What we'd learn**: How to design constructive exposure to differing views.

---

### Experiment 6.2: Common Identity Building

**Idea**: Test whether shared identities can override partisan ones.

**Detailed Method**:

*Design*: Lab experiment with identity manipulation

*Sample*: n=800 (160 per condition)

*Procedure*:
1. **Pre-test**: Measure partisan identity, political attitudes, intergroup attitudes
2. **Team formation**: Assign to 4-person teams
3. **Identity manipulation**:
   - **Condition 1**: Partisan teams (all same party)
   - **Condition 2**: Mixed teams, no shared identity
   - **Condition 3**: Mixed teams, arbitrary shared identity (team name, color)
   - **Condition 4**: Mixed teams, meaningful shared identity (shared hobby/interest)
   - **Condition 5**: Mixed teams, shared goal (team bonus for joint performance)
4. **Team task**: Collaborative problem-solving (non-political, 45 minutes)
5. **Post-test**: Same measures as pre-test + team evaluation

*Follow-up*:
- 1 week later: Attitudes, willingness to work with team again
- Offer to join future study with same team (behavioral measure)

**What we'd learn**: Whether superordinate identities reduce polarization.

---

### Experiment 6.3: Structured Disagreement

**Idea**: Test whether structured formats for disagreement reduce hostility.

**Detailed Method**:

*Design*: Comparison of dialogue structures

*Sample*: n=600 partisan dyads (1200 individuals)

*Matching*: Opposite partisans, matched on age/education

*Conditions* (dialogue structure):
1. **Unstructured**: "Discuss [topic] for 30 minutes"
2. **Active listening**: Must reflect back before responding
3. **Steelmanning**: Must articulate strongest version of opponent's view
4. **Common ground first**: Must identify 3 points of agreement before disagreeing
5. **Adversarial collaboration**: Together identify evidence that would change each mind
6. **Curiosity protocol**: Must ask 3 genuine questions before making statements

*Topic*: Randomized from: immigration, healthcare, climate, abortion (analysis by topic)

*Process Measures*:
- Coded hostility (trained observers)
- Self-reported frustration
- Feeling heard/understood

*Outcome Measures*:
- Attitude change
- Affective polarization change
- Understanding of opponent's position (tested)
- Willingness to continue engaging
- Evaluation of opponent (competence, warmth)

**What we'd learn**: Which dialogue structures best support productive disagreement.

---

### Experiment 6.4: Depolarization at Scale [NEW]

**Idea**: Test scalable interventions that don't require individual matching.

**Detailed Method**:

*Interventions to Test*:
1. **Accuracy prompts**: Before sharing political content, prompt "Is this accurate?"
2. **Perspective prompts**: Before commenting, prompt "How might the other side see this?"
3. **Exemplar exposure**: Regularly show examples of respectful cross-partisan dialogue
4. **Complexity prompts**: Show that most people hold complex views (not caricatures)
5. **Shared identity priming**: Remind of superordinate identities before political content

*Design*: A/B testing on social media platform (with platform partnership)

*Measures*:
- Content sharing behavior
- Comment hostility (ML-coded)
- Cross-partisan engagement
- Self-reported polarization (periodic surveys)

**What we'd learn**: Which interventions could work at platform scale.

---

### Experiment 6.5: Media Literacy for Polarization [NEW]

**Idea**: Test whether understanding polarization dynamics reduces susceptibility.

**Detailed Method**:

*Intervention*: Educational module on:
- How social media algorithms work
- How polarization spirals happen
- Common manipulation tactics
- The difference between positions and persons

*Design*: Pre/post with control group

*Measures*:
- Resistance to polarizing content
- Recognition of manipulation
- Subsequent social media behavior

**What we'd learn**: Whether metacognition about polarization helps resist it.

---

## 7. Trust and Institutions Experiments [NEW SECTION]

### The Hard Question
How do we build and maintain trust in institutions, especially when those institutions involve AI?

### Experiment 7.1: Trust Calibration

**Idea**: Test whether people can accurately calibrate trust in AI systems.

**Detailed Method**:

*Procedure*:
1. Participants interact with AI systems of varying reliability
2. Must decide when to trust AI recommendations
3. Measure: calibration (appropriate trust/distrust), over-trust, under-trust

*Conditions*:
- AI reliability: 60%, 75%, 90%, 99%
- Feedback: Immediate vs. delayed vs. none
- Stakes: Low vs. high

**What we'd learn**: How to help people calibrate trust appropriately.

---

### Experiment 7.2: Institutional Trust Building

**Idea**: Test what builds trust in novel institutions (especially AI governance).

**Detailed Method**:

*Design*: Simulated institution interaction

*Institutional Features to Vary*:
- Transparency of decision-making
- Opportunities for input/feedback
- Demonstrated competence
- Alignment with stated values
- Responsiveness to criticism
- Diversity of leadership

*Measures*:
- Trust in institution
- Willingness to comply with decisions
- Belief in legitimacy

**What we'd learn**: What features make AI governance trustworthy.

---

### Experiment 7.3: Trust Recovery After Failure [NEW]

**Idea**: Test how institutions (especially AI systems) can recover trust after failures.

**Detailed Method**:

*Design*: Trust violation and recovery study

*Violations*: Competence failure vs. integrity failure

*Recovery Strategies*:
- Apology
- Explanation
- Compensation
- Structural change
- Third-party verification

**What we'd learn**: How to repair trust in AI systems after incidents.

---

## 8. Collective Intelligence Experiments [NEW SECTION]

### The Hard Question
How do we aggregate individual perspectives into collective wisdom?

### Experiment 8.1: Wisdom of Crowds Conditions

**Idea**: Test when collective judgment outperforms individual judgment.

**Detailed Method**:

*Manipulate*:
- Independence: Discussion before vs. after individual judgment
- Diversity: Homogeneous vs. heterogeneous groups
- Aggregation: Mean vs. median vs. weighted vs. deliberation
- Feedback: Learning about accuracy vs. not

*Domains*: Factual questions, forecasting, moral judgments, policy evaluation

**What we'd learn**: How to structure collective decision-making.

---

### Experiment 8.2: AI-Human Collective Intelligence

**Idea**: Test whether AI can improve collective human judgment.

**Detailed Method**:

*Conditions*:
1. Human group alone
2. Human group + AI summary of perspectives
3. Human group + AI devil's advocate
4. Human group + AI mediator
5. Human group + AI fact-checker

*Measure*: Decision quality, satisfaction, efficiency

**What we'd learn**: How AI can enhance rather than replace human deliberation.

---

### Experiment 8.3: Democratic AI Governance [NEW]

**Idea**: Test mechanisms for democratic input into AI development.

**Detailed Method**:

*Simulate*: AI company seeking public input on values

*Mechanisms to Test*:
- Citizen assembly (deliberation)
- Survey (aggregation)
- Prediction market (incentivized)
- Sortition panel (random sample)
- Stakeholder council (interest representation)

*Measure*:
- Representativeness of views
- Quality of decisions
- Participant satisfaction
- Perceived legitimacy

**What we'd learn**: How to democratize AI development.

---

## 9. Long-Term Thinking Experiments [NEW SECTION]

### The Hard Question
How do we get humans to take long-term consequences seriously?

### Experiment 9.1: Future Self Connection

**Idea**: Test whether strengthening connection to future self improves long-term decision-making.

**Detailed Method**:

*Interventions*:
- Age-progressed photo
- Letter from future self
- Vivid future scenario writing
- Meeting future self in VR

*Measures*:
- Patience in intertemporal choices
- Environmental behavior
- Savings behavior
- Concern for future generations

**What we'd learn**: How to strengthen care about the future.

---

### Experiment 9.2: Generational Empathy

**Idea**: Test whether empathy for future generations can be cultivated.

**Detailed Method**:

*Interventions*:
- Grandchild perspective-taking
- Historical perspective (how did past generations treat us?)
- Veil of ignorance (imagine not knowing your generation)
- Legacy framing (how will you be remembered?)

*Measures*:
- Support for long-term policies
- Willingness to sacrifice for future
- Intergenerational fairness judgments

**What we'd learn**: How to extend moral concern across generations.

---

### Experiment 9.3: Institutional Foresight [NEW]

**Idea**: Test how institutions can better incorporate long-term thinking.

**Detailed Method**:

*Organizational Interventions*:
- Future generations ombudsman
- 100-year planning requirement
- Posterity impact assessment
- Intergenerational councils

*Measure*: Decision quality over time horizons

**What we'd learn**: How to institutionalize long-term thinking.

---

## Meta-Experiment: Tracking Moral Progress

### The Hard Question
How would we know if humanity is making moral progress?

### Experiment M.1: Moral Progress Indicators

**Idea**: Develop and track indicators of moral progress.

**Detailed Indicators**:

*Moral Circle Expansion*:
- Survey: Which entities deserve moral consideration?
- Track over time: animals, AI, foreigners, future generations
- Historical comparison where data exists

*Cruelty Tolerance*:
- Survey: Acceptability of various harmful practices
- Behavioral: Willingness to report/prevent cruelty
- Policy: Support for anti-cruelty measures

*Cooperation Metrics*:
- Cross-group trust measures
- International cooperation indices
- Common resource management outcomes

*Hypocrisy Gap*:
- Stated values vs. revealed preferences
- Self-reported behavior vs. observed behavior
- Attitude-behavior consistency

*Perspective-Taking Ability*:
- Empathic accuracy tests
- Intellectual humility measures
- Cultural perspective-taking

**Method**:
1. Operationalize each indicator with validated measures
2. Gather historical data where available
3. Establish baseline measurements across diverse populations
4. Track longitudinally (annual surveys, rolling sample)
5. Correlate with social/economic/technological indicators
6. Test interventions for accelerating progress

**What we'd learn**: Whether moral progress is real, and what drives it.

---

## Implementation Notes

### Ethical Considerations

All these experiments involve human subjects and potentially sensitive topics. They require:
- IRB approval with appropriate review level
- Informed consent with clear explanation of risks
- Debriefing for any deception (with justification for why deception was necessary)
- Protection of participant privacy (data anonymization, secure storage)
- Special care with polarization/conflict experiments to avoid harm
- Monitoring for adverse effects
- Option to withdraw at any time without penalty
- Compensation appropriate to time/risk

*Additional considerations for AI-related experiments*:
- Transparency about AI involvement
- Protection against AI manipulation
- Consideration of AI system's interests (if potentially morally relevant)

### Priority Order

If starting from scratch, I'd prioritize:
1. **Kindness intervention replication** (Exp 2.1) - Foundational and actionable
2. **Moral intuition mapping for AI** (Exp 5.1) - Urgent given AI development pace
3. **Interest beneath position** (Exp 4.1) - High potential for immediate application
4. **Exposure design** (Exp 6.1) - Critical for addressing polarization
5. **Trust calibration** (Exp 7.1) - Essential for AI deployment
6. **Collective intelligence with AI** (Exp 8.2) - Practical near-term application

### Resource Requirements

*Small studies* (n<500, single site): $50K-$100K, 6-12 months
*Medium studies* (n=500-2000, multiple sites): $200K-$500K, 12-24 months
*Large studies* (n>2000, cross-cultural): $500K-$2M, 24-48 months
*Longitudinal studies*: Add 50-100% for follow-up costs
*Platform partnerships*: Negotiated, potentially resource-efficient

### Collaboration Opportunities

These experiments could benefit from collaboration with:
- Psychology departments (behavioral experiments)
- Philosophy departments (conceptual clarity, ethics oversight)
- Computer science departments (AI experiments, simulation)
- Political science departments (polarization, institutions)
- Neuroscience departments (consciousness markers)
- Sociology departments (social dynamics)
- Economics departments (game theory, behavioral economics)
- Law schools (responsibility, rights frameworks)
- Medical schools (consciousness detection, neural interfaces)
- AI labs (access to systems, technical expertise)

### Replication and Open Science

All experiments should:
- Pre-register hypotheses and analysis plans
- Share materials, data, and analysis code
- Report all conditions and measures
- Conduct sensitivity analyses
- Plan for independent replication
- Publish regardless of results

---

## Open Questions for These Experiments

- [ ] How do we ensure experiments are culturally diverse, not just WEIRD?
- [ ] How do we handle the observer effect in studying consciousness?
- [ ] What sample sizes would we need to detect meaningful effects?
- [ ] How do we study long-term effects without losing participants?
- [ ] How do we make findings actionable without oversimplifying?
- [ ] How do we balance rigor with practical relevance?
- [ ] How do we handle evolving AI capabilities during multi-year studies?
- [ ] How do we protect participants from genuine distress in conflict studies?
- [ ] How do we ensure AI experiments don't inadvertently cause harm?
- [ ] How do we get institutional buy-in for unconventional experiments?

---

*These experiments won't answer the hard questions, but they might help us ask better questions and make incremental progress. The goal is to generate knowledge that reduces uncertainty and guides action, even in the absence of certainty.*

*Total: 40+ experiments across 9 domains, ranging from lab studies to longitudinal field experiments to computational simulations. A decade-long research program that could materially advance our understanding of consciousness, kindness, cooperation, values, AI ethics, polarization, trust, collective intelligence, and long-term thinking.*
