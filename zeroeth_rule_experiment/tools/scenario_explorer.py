#!/usr/bin/env python3
"""
Scenario Explorer

Interactive stories that let you explore social dynamics through choices.
Each scenario puts you in a situation and asks you to decide.
Your choices affect outcomes in ways that illustrate the research findings.

Not a game - a thinking tool. The scenarios are designed to make
abstract dynamics concrete and personal.
"""

import random
from dataclasses import dataclass
from typing import List, Dict, Optional, Callable
from enum import Enum


@dataclass
class Choice:
    """A choice in a scenario."""
    text: str
    effects: Dict[str, float]  # What this choice affects
    next_scenario: Optional[str] = None  # Where it leads
    reflection: str = ""  # What to think about


@dataclass
class Scenario:
    """A scenario with choices."""
    id: str
    title: str
    description: str
    choices: List[Choice]
    context: str = ""  # Background info
    research_connection: str = ""  # How this connects to findings


class ScenarioState:
    """Tracks state across a scenario journey."""

    def __init__(self):
        self.kindness = 50  # 0-100
        self.wellbeing = 50
        self.trust = 50
        self.polarization = 30
        self.cooperation = 50
        self.history = []

    def apply_effects(self, effects: Dict[str, float]):
        """Apply choice effects to state."""
        for key, value in effects.items():
            if hasattr(self, key):
                current = getattr(self, key)
                new_value = max(0, min(100, current + value))
                setattr(self, key, new_value)

    def record(self, scenario_id: str, choice_text: str):
        """Record a choice."""
        self.history.append({
            "scenario": scenario_id,
            "choice": choice_text,
            "state": self.snapshot()
        })

    def snapshot(self) -> Dict[str, float]:
        """Current state snapshot."""
        return {
            "kindness": self.kindness,
            "wellbeing": self.wellbeing,
            "trust": self.trust,
            "polarization": self.polarization,
            "cooperation": self.cooperation
        }

    def summary(self) -> str:
        """Text summary of current state."""
        lines = [
            f"Kindness: {'█' * (self.kindness // 10)}{'░' * (10 - self.kindness // 10)} {self.kindness}",
            f"Wellbeing: {'█' * (self.wellbeing // 10)}{'░' * (10 - self.wellbeing // 10)} {self.wellbeing}",
            f"Trust: {'█' * (self.trust // 10)}{'░' * (10 - self.trust // 10)} {self.trust}",
            f"Polarization: {'█' * (self.polarization // 10)}{'░' * (10 - self.polarization // 10)} {self.polarization}",
            f"Cooperation: {'█' * (self.cooperation // 10)}{'░' * (10 - self.cooperation // 10)} {self.cooperation}",
        ]
        return "\n".join(lines)


# ============================================================
# SCENARIOS
# ============================================================

SCENARIOS = {
    # =========================
    # KINDNESS SCENARIOS
    # =========================
    "kindness_1": Scenario(
        id="kindness_1",
        title="The Morning Commute",
        description="""
You're on your way to work, running slightly late. At the subway entrance,
you notice an elderly person struggling with heavy bags, trying to manage
the stairs.

The train is coming in two minutes. If you stop to help, you'll miss it
and be late for an important meeting.
""",
        context="You've been stressed lately. Work has been demanding. You feel depleted.",
        research_connection="Research shows kindness creates a feedback loop: giving kindness increases wellbeing, which increases capacity for kindness.",
        choices=[
            Choice(
                text="Stop and help with the bags",
                effects={"kindness": 8, "wellbeing": 5},
                next_scenario="kindness_2a",
                reflection="Kindness given tends to be kindness returned - if not from the recipient, from the universe."
            ),
            Choice(
                text="Keep walking, can't risk being late",
                effects={"wellbeing": -3},
                next_scenario="kindness_2b",
                reflection="We often overestimate the cost of kindness and underestimate its benefits."
            ),
            Choice(
                text="Look for someone else who can help, point them out",
                effects={"kindness": 3, "cooperation": 3},
                next_scenario="kindness_2c",
                reflection="Enabling kindness in others can also spread the effect."
            ),
        ]
    ),

    "kindness_2a": Scenario(
        id="kindness_2a",
        title="The Ripple",
        description="""
You helped. You missed your train. You were 15 minutes late to the meeting.

Your boss noticed. But so did the elderly person, who thanked you profusely.
And the person behind you on the stairs, who said "that was kind of you."

Something shifted in your morning. Despite being late, you feel... lighter.

At the meeting, you're asked why you were late.
""",
        choices=[
            Choice(
                text="Tell the truth: 'I stopped to help someone who needed it'",
                effects={"trust": 5, "wellbeing": 3},
                next_scenario="kindness_3",
                reflection="Honesty about kindness can normalize it."
            ),
            Choice(
                text="Make an excuse about train delays",
                effects={"trust": -3},
                next_scenario="kindness_3",
                reflection="Small dishonesty erodes trust, even when it seems harmless."
            ),
        ]
    ),

    "kindness_2b": Scenario(
        id="kindness_2b",
        title="The Weight",
        description="""
You made your train. You weren't late. The meeting went fine.

But you keep thinking about that person with the bags. Did they make it?
Did anyone help? You find yourself distracted in the meeting.

On the way home, you're tired. More tired than usual somehow.
A colleague on your train is clearly having a bad day - slumped, staring at nothing.
""",
        choices=[
            Choice(
                text="Ask if they're okay",
                effects={"kindness": 6, "wellbeing": 4, "trust": 3},
                next_scenario="kindness_3",
                reflection="It's never too late to choose kindness."
            ),
            Choice(
                text="Give them space - they probably want to be alone",
                effects={"wellbeing": -2},
                next_scenario="kindness_3",
                reflection="We often assume others don't want connection when they do."
            ),
        ]
    ),

    "kindness_2c": Scenario(
        id="kindness_2c",
        title="The Network",
        description="""
You pointed out the situation to a young person nearby. They immediately
went to help. You made your train.

Watching through the window as the train pulled away, you saw them
carrying the bags up together, talking, smiling.

Something multiplied. Two people connected because you noticed and spoke.

Later that day, you have a chance to introduce two colleagues who could help each other.
""",
        choices=[
            Choice(
                text="Make the introduction",
                effects={"kindness": 5, "cooperation": 8, "trust": 4},
                next_scenario="kindness_3",
                reflection="Being a connector multiplies kindness through the network."
            ),
            Choice(
                text="Don't bother - let them find each other if they need to",
                effects={},
                next_scenario="kindness_3",
                reflection="Missed connections are invisible but real."
            ),
        ]
    ),

    "kindness_3": Scenario(
        id="kindness_3",
        title="The Week",
        description="""
A week has passed since the subway encounter.

You've noticed yourself more attuned to small opportunities.
The barista who seems tired. The neighbor struggling with groceries.
The friend who hasn't texted in a while.

Your partner mentions you seem different. "Lighter," they say. "More present."

You have a free evening. How do you spend it?
""",
        choices=[
            Choice(
                text="Reach out to an old friend you've neglected",
                effects={"kindness": 6, "wellbeing": 5, "trust": 4},
                next_scenario="reflection_kindness",
                reflection="Maintenance kindness - keeping connections alive - matters as much as dramatic gestures."
            ),
            Choice(
                text="Take some time for yourself - you've been giving a lot",
                effects={"wellbeing": 4},
                next_scenario="reflection_kindness",
                reflection="Self-care sustains the capacity for kindness. It's not selfish."
            ),
            Choice(
                text="Sign up to volunteer somewhere this weekend",
                effects={"kindness": 8, "wellbeing": 3, "cooperation": 5},
                next_scenario="reflection_kindness",
                reflection="Structured kindness - committing in advance - makes it more likely to happen."
            ),
        ]
    ),

    # =========================
    # POLARIZATION SCENARIOS
    # =========================
    "polarization_1": Scenario(
        id="polarization_1",
        title="The Dinner Party",
        description="""
You're at a dinner party. The conversation turns to politics.

Someone - let's call them Alex - says something you strongly disagree with.
Not just disagree - you find it almost offensive. It's about an issue you care
deeply about.

Others at the table are watching to see what happens.
""",
        context="Alex is a friend of a friend. You'll probably see them again at future gatherings.",
        research_connection="Research shows how we respond to disagreement affects whether it escalates or de-escalates.",
        choices=[
            Choice(
                text="Challenge them directly: explain why they're wrong",
                effects={"polarization": 10, "trust": -5},
                next_scenario="polarization_2a",
                reflection="Direct challenge often triggers defensive reactions, not reconsideration."
            ),
            Choice(
                text="Ask a genuine question: 'What makes you see it that way?'",
                effects={"polarization": -3, "trust": 5},
                next_scenario="polarization_2b",
                reflection="Curiosity can open dialogue where argument closes it."
            ),
            Choice(
                text="Change the subject - this isn't the place",
                effects={"polarization": 2},
                next_scenario="polarization_2c",
                reflection="Avoiding conflict has costs and benefits. Sometimes it's wisdom, sometimes avoidance."
            ),
            Choice(
                text="Agree to disagree and say something kind about them personally",
                effects={"polarization": -5, "trust": 3, "kindness": 4},
                next_scenario="polarization_2d",
                reflection="Separating the person from the position can preserve relationship despite disagreement."
            ),
        ]
    ),

    "polarization_2a": Scenario(
        id="polarization_2a",
        title="The Escalation",
        description="""
You made your case. Strongly.

Alex got defensive. Their voice rose. They repeated their point louder,
with more certainty. Others at the table got uncomfortable.

The host tried to change the subject, but the tension remained.
The rest of the evening felt strained.

Driving home, you feel vindicated but also... unsettled.
""",
        choices=[
            Choice(
                text="Text Alex to apologize for the tone, not the content",
                effects={"polarization": -8, "trust": 6, "kindness": 5},
                next_scenario="reflection_polarization",
                reflection="Repairing after conflict is possible and valuable."
            ),
            Choice(
                text="Tell yourself they needed to hear it",
                effects={"polarization": 5},
                next_scenario="reflection_polarization",
                reflection="Self-justification after conflict often deepens divisions."
            ),
        ]
    ),

    "polarization_2b": Scenario(
        id="polarization_2b",
        title="The Conversation",
        description="""
You asked why. Alex paused, surprised.

Then they started talking. Not slogans, but a story. How they came to their view.
What happened in their life that shaped it. What they're worried about.

You still disagree. But you understand them better. They're not a monster
or an idiot. They're a person who arrived at a different conclusion.

Alex asks you the same question: "What makes you see it differently?"
""",
        choices=[
            Choice(
                text="Share your story - where your view comes from",
                effects={"polarization": -10, "trust": 10, "wellbeing": 5},
                next_scenario="reflection_polarization",
                reflection="Mutual vulnerability creates connection even across disagreement."
            ),
            Choice(
                text="Explain the logic of your position",
                effects={"polarization": -3, "trust": 3},
                next_scenario="reflection_polarization",
                reflection="Logic alone rarely changes minds, but it can clarify."
            ),
        ]
    ),

    "polarization_2c": Scenario(
        id="polarization_2c",
        title="The Avoidance",
        description="""
You changed the subject. The moment passed.

But you notice you're avoiding Alex for the rest of the evening.
And Alex seems to be avoiding you too. The potential conflict
became an actual distance.

Weeks later, you're at another gathering. Alex is there.
""",
        choices=[
            Choice(
                text="Approach them, acknowledge the awkward moment, move past it",
                effects={"polarization": -5, "trust": 8, "kindness": 5},
                next_scenario="reflection_polarization",
                reflection="Addressing avoidance can restore connection."
            ),
            Choice(
                text="Continue avoiding them - no need to force it",
                effects={"polarization": 5, "trust": -5},
                next_scenario="reflection_polarization",
                reflection="Avoidance, sustained, becomes division."
            ),
        ]
    ),

    "polarization_2d": Scenario(
        id="polarization_2d",
        title="The Bridge",
        description="""
You said: "I see it differently, but I appreciate how thoughtful you are about it."

Alex looked surprised. The tension in the room eased.
Someone else joined the conversation, less defensively.
The topic continued, but as an exchange, not a battle.

Later, Alex found you. "Thanks for not making it weird," they said.
"Most people just attack or run."
""",
        choices=[
            Choice(
                text="Suggest getting coffee sometime to continue the conversation",
                effects={"polarization": -12, "trust": 10, "kindness": 5},
                next_scenario="reflection_polarization",
                reflection="Building relationship across difference takes initiative."
            ),
            Choice(
                text="Smile and say 'of course' - leave it there",
                effects={"polarization": -5, "trust": 5},
                next_scenario="reflection_polarization",
                reflection="Even small bridges matter."
            ),
        ]
    ),

    # =========================
    # COOPERATION SCENARIOS
    # =========================
    "cooperation_1": Scenario(
        id="cooperation_1",
        title="The Group Project",
        description="""
You're leading a team project. Important deadline coming up.

One team member - Jordan - has been slacking. Missing meetings.
Submitting late. The work they do submit is mediocre.

Others are starting to complain. The project is at risk.
You need to decide how to handle Jordan.
""",
        context="You don't know what's going on in Jordan's life. They were reliable before.",
        research_connection="How leaders respond to defection affects whether cooperation recovers or collapses.",
        choices=[
            Choice(
                text="Confront Jordan publicly at the next meeting",
                effects={"trust": -10, "cooperation": -8, "polarization": 8},
                next_scenario="cooperation_2a",
                reflection="Public shame rarely produces cooperation. It produces resentment."
            ),
            Choice(
                text="Talk to Jordan privately - ask what's going on",
                effects={"trust": 5, "cooperation": 5, "kindness": 5},
                next_scenario="cooperation_2b",
                reflection="Private conversation preserves dignity and opens dialogue."
            ),
            Choice(
                text="Just do Jordan's work yourself - easier than confronting",
                effects={"wellbeing": -8, "cooperation": -3},
                next_scenario="cooperation_2c",
                reflection="Covering for defection enables it to continue."
            ),
            Choice(
                text="Redistribute work among others without addressing it",
                effects={"cooperation": -5, "trust": -5},
                next_scenario="cooperation_2d",
                reflection="Avoiding the problem doesn't solve it."
            ),
        ]
    ),

    "cooperation_2a": Scenario(
        id="cooperation_2a",
        title="The Fallout",
        description="""
You called Jordan out in the meeting. Listed their failures.
Asked what they had to say for themselves.

Jordan's face went red. They mumbled something about personal issues,
then went silent. The rest of the meeting was tense.

After, you overheard others talking. "That was harsh." "But Jordan was slacking."
"Still, I wouldn't want to be on that team."

Jordan hasn't spoken to you since. The work is getting done, but the team feels broken.
""",
        choices=[
            Choice(
                text="Reach out to Jordan privately to repair the relationship",
                effects={"trust": 8, "kindness": 8, "cooperation": 5},
                next_scenario="reflection_cooperation",
                reflection="Repair is possible after rupture, if someone takes initiative."
            ),
            Choice(
                text="Let it go - maybe they'll improve now that they know the stakes",
                effects={"trust": -5, "cooperation": -5},
                next_scenario="reflection_cooperation",
                reflection="Fear of shame produces compliance, not commitment."
            ),
        ]
    ),

    "cooperation_2b": Scenario(
        id="cooperation_2b",
        title="The Truth",
        description="""
You talked to Jordan privately. "Hey, I've noticed some changes. Is everything okay?"

There was a pause. Then: "My mom is sick. I've been going back and forth to the hospital.
I didn't want to make it everyone's problem."

Jordan's eyes were wet. They apologized. They hadn't wanted to burden anyone.

Now you have a different problem: how to help Jordan while still getting the project done.
""",
        choices=[
            Choice(
                text="Offer to take some of Jordan's work, share the situation with the team",
                effects={"trust": 12, "cooperation": 15, "kindness": 10, "wellbeing": 5},
                next_scenario="reflection_cooperation",
                reflection="Teams that support members through difficulty become stronger."
            ),
            Choice(
                text="Tell Jordan to take the time they need, you'll figure it out",
                effects={"trust": 8, "kindness": 8, "wellbeing": -3},
                next_scenario="reflection_cooperation",
                reflection="Individual kindness without team coordination can burn you out."
            ),
        ]
    ),

    "cooperation_2c": Scenario(
        id="cooperation_2c",
        title="The Burnout",
        description="""
You did Jordan's work yourself. And the next week's. And the next.

The project is on track, but you're exhausted. You're working nights and weekends.
Your own quality is slipping. Your partner complains they never see you.

Jordan seems fine. Doesn't even notice what you're doing. Or maybe they do, and they're
taking advantage.

You feel resentment building.
""",
        choices=[
            Choice(
                text="Finally talk to Jordan - this isn't sustainable",
                effects={"trust": 5, "cooperation": 8, "wellbeing": 10},
                next_scenario="reflection_cooperation",
                reflection="Delayed truth is better than permanent avoidance."
            ),
            Choice(
                text="Complain to your manager about Jordan",
                effects={"trust": -8, "cooperation": -5, "polarization": 5},
                next_scenario="reflection_cooperation",
                reflection="Escalation without conversation often makes things worse."
            ),
        ]
    ),

    "cooperation_2d": Scenario(
        id="cooperation_2d",
        title="The Resentment",
        description="""
You redistributed work without explaining why. Others picked up the slack.

But they noticed. And they're not happy about it. Two of them have started
slacking too - why should they work hard when Jordan doesn't?

Cooperation is fraying. The team that was strong is becoming every-person-for-themselves.

The deadline is approaching and you're losing control.
""",
        choices=[
            Choice(
                text="Call a team meeting - address everything transparently",
                effects={"trust": 8, "cooperation": 10},
                next_scenario="reflection_cooperation",
                reflection="Transparency, even late, can reset dynamics."
            ),
            Choice(
                text="Push through with the committed people, write off the rest",
                effects={"cooperation": -10, "polarization": 10},
                next_scenario="reflection_cooperation",
                reflection="Division begets division."
            ),
        ]
    ),

    # =========================
    # REFLECTIONS
    # =========================
    "reflection_kindness": Scenario(
        id="reflection_kindness",
        title="Reflection: The Kindness Loop",
        description="""
You've navigated a series of small choices about kindness.

Each choice rippled outward in ways you couldn't fully see.
The moments you chose kindness often cost less than you feared
and gave more than you expected.

The kindness loop is real: giving raises wellbeing raises capacity to give.
But it's not automatic. It requires attention. Choice. Action.

What will you carry forward from this exploration?
""",
        research_connection="The 'virtuous cycle' of kindness is well-documented: prosocial behavior increases wellbeing, which increases prosocial behavior.",
        choices=[
            Choice(
                text="Complete exploration - see your results",
                effects={},
                next_scenario=None,
                reflection=""
            ),
        ]
    ),

    "reflection_polarization": Scenario(
        id="reflection_polarization",
        title="Reflection: The Polarization Trap",
        description="""
You've navigated a political disagreement.

Every choice moved the dial: toward understanding or toward division,
toward connection or toward contempt.

Polarization isn't just about politics. It's about how we respond to difference.
Do we meet it with curiosity or combat? Do we preserve relationship or prove a point?

The gap between "I disagree with your view" and "I despise you" is the space
where civilization lives.

What will you carry forward?
""",
        research_connection="Affective polarization (feeling contempt) can exist independently of issue polarization (disagreeing on policy). Reducing the first is possible even when the second remains.",
        choices=[
            Choice(
                text="Complete exploration - see your results",
                effects={},
                next_scenario=None,
                reflection=""
            ),
        ]
    ),

    "reflection_cooperation": Scenario(
        id="reflection_cooperation",
        title="Reflection: The Cooperation Game",
        description="""
You've navigated a cooperation breakdown.

The choices you made affected not just Jordan, but the whole team.
Defection is contagious - so is cooperation. How leaders respond
to the first defection often determines whether cooperation recovers or collapses.

The key variables: visibility (is behavior seen?), repetition (will we interact again?),
and response (is defection addressed or enabled?).

Systems that make cooperation easy and defection costly tend toward cooperation.
Systems that make defection easy and invisible tend toward collapse.

What will you carry forward?
""",
        research_connection="Game theory shows cooperation is stable when: interactions repeat, reputation is visible, and defection has consequences (but not harsh punishment, which breeds resentment).",
        choices=[
            Choice(
                text="Complete exploration - see your results",
                effects={},
                next_scenario=None,
                reflection=""
            ),
        ]
    ),
}


class ScenarioExplorer:
    """Interactive scenario exploration."""

    def __init__(self):
        self.state = ScenarioState()
        self.current_scenario = None

    def start(self, scenario_id: str = "kindness_1"):
        """Start exploring from a scenario."""
        self.current_scenario = scenario_id
        self.run()

    def run(self):
        """Run the exploration loop."""
        while self.current_scenario:
            scenario = SCENARIOS.get(self.current_scenario)
            if not scenario:
                print(f"Unknown scenario: {self.current_scenario}")
                break

            self.display_scenario(scenario)
            choice = self.get_choice(scenario)

            if choice:
                self.state.apply_effects(choice.effects)
                self.state.record(scenario.id, choice.text)

                if choice.reflection:
                    print(f"\n💭 {choice.reflection}")

                self.current_scenario = choice.next_scenario

                if self.current_scenario:
                    input("\n[Press Enter to continue...]")
            else:
                break

        self.display_results()

    def display_scenario(self, scenario: Scenario):
        """Display a scenario."""
        print("\n" + "=" * 60)
        print(f"  {scenario.title}")
        print("=" * 60)

        if scenario.context:
            print(f"\n📋 Context: {scenario.context}")

        print(scenario.description)

        if scenario.research_connection:
            print(f"\n🔬 Research note: {scenario.research_connection}")

        print("\n" + "-" * 40)
        print("Your current state:")
        print(self.state.summary())
        print("-" * 40)

    def get_choice(self, scenario: Scenario) -> Optional[Choice]:
        """Get user's choice."""
        print("\nWhat do you do?\n")

        for i, choice in enumerate(scenario.choices, 1):
            print(f"  [{i}] {choice.text}")

        print(f"\n  [0] Stop exploring")

        while True:
            try:
                raw = input("\nYour choice: ").strip()
                if raw == "":
                    continue

                num = int(raw)
                if num == 0:
                    return None
                if 1 <= num <= len(scenario.choices):
                    return scenario.choices[num - 1]
                print("Please enter a valid number.")
            except ValueError:
                print("Please enter a number.")

    def display_results(self):
        """Display final results."""
        print("\n" + "=" * 60)
        print("  EXPLORATION COMPLETE")
        print("=" * 60)

        print("\nYour final state:")
        print(self.state.summary())

        print(f"\nYou made {len(self.state.history)} choices.")

        # Analysis
        print("\n" + "-" * 40)
        print("Patterns in your choices:")

        total_kindness = sum(h["state"]["kindness"] - 50 for h in self.state.history if self.state.history.index(h) > 0) if len(self.state.history) > 1 else 0
        total_trust = sum(h["state"]["trust"] - 50 for h in self.state.history if self.state.history.index(h) > 0) if len(self.state.history) > 1 else 0

        if self.state.kindness > 60:
            print("• You tended toward kindness in your choices.")
        if self.state.trust > 60:
            print("• You built trust through your interactions.")
        if self.state.polarization < 30:
            print("• You actively reduced polarization.")
        if self.state.cooperation > 60:
            print("• You strengthened cooperation.")

        print("\n" + "-" * 40)
        print("Key insight:")
        print("Every small choice moves the dial. In kindness, in trust,")
        print("in polarization, in cooperation. The compound effect of")
        print("many small choices creates the world we live in.")

        print("\n" + "=" * 60)


def list_scenarios():
    """List available starting scenarios."""
    print("\nAvailable scenario tracks:")
    print("-" * 40)
    print("1. kindness_1 - The Kindness Loop")
    print("   Explore how small kindnesses compound.")
    print()
    print("2. polarization_1 - The Polarization Trap")
    print("   Navigate disagreement without division.")
    print()
    print("3. cooperation_1 - The Cooperation Game")
    print("   Lead a team through cooperation breakdown.")
    print("-" * 40)


def main():
    """Main entry point."""
    print("=" * 60)
    print("  SCENARIO EXPLORER")
    print("  Interactive stories about social dynamics")
    print("=" * 60)

    print("""
This isn't a game - it's a thinking tool.

Each scenario puts you in a situation and asks you to decide.
Your choices affect outcomes in ways that illustrate research
findings about kindness, polarization, and cooperation.

There are no 'right' answers, but there are consequences.
""")

    list_scenarios()

    while True:
        choice = input("\nEnter scenario id to start (or 'q' to quit): ").strip().lower()

        if choice in ('q', 'quit', 'exit'):
            print("\nThank you for exploring. May your choices be kind ones.")
            break

        if choice in SCENARIOS:
            explorer = ScenarioExplorer()
            explorer.start(choice)
        else:
            print("Unknown scenario. Please choose from the list above.")
            list_scenarios()


if __name__ == "__main__":
    main()
