#!/usr/bin/env python3
"""
Question Partner: A Prototype Tool for Thinking Alongside

This is a simple prototype of what a "question partner" might look like.
Not an answer-giver, but a question-asker. Not a solution, but a companion
in thinking.

The idea: Sometimes what helps isn't someone telling you what to do,
but someone asking the questions you haven't thought to ask.

PROTOTYPE STATUS: This is rough. It's meant to be iterated on, not finished.
"""

import random
from dataclasses import dataclass
from typing import List, Optional
from enum import Enum


class QuestionType(Enum):
    """Types of questions that might help thinking."""
    CLARIFYING = "clarifying"       # What do you actually mean?
    EXPANDING = "expanding"         # What else could be true?
    GROUNDING = "grounding"        # What's actually happening?
    CHALLENGING = "challenging"    # Is that really true?
    CONNECTING = "connecting"      # How does this relate?
    IMAGINING = "imagining"        # What if?
    FEELING = "feeling"            # What do you feel about this?
    VALUING = "valuing"            # What matters here?


@dataclass
class Question:
    """A question that might help someone think."""
    text: str
    question_type: QuestionType
    when_to_ask: str  # Description of when this question might be useful
    follow_ups: List[str]


# =============================================================================
# THE QUESTION BANK
# =============================================================================
# These are questions that I've noticed can help clarify thinking.
# Not prescriptions - just offerings.

QUESTION_BANK: List[Question] = [
    # CLARIFYING questions
    Question(
        text="When you say that, what do you actually mean?",
        question_type=QuestionType.CLARIFYING,
        when_to_ask="When someone uses abstract or ambiguous language",
        follow_ups=[
            "Can you give me an example?",
            "What would that look like in practice?",
            "How would you know if that was true?"
        ]
    ),
    Question(
        text="What's the specific problem you're trying to solve?",
        question_type=QuestionType.CLARIFYING,
        when_to_ask="When someone seems stuck but hasn't named the stuckness",
        follow_ups=[
            "What would it look like if this problem was solved?",
            "When did this become a problem?",
            "Who else sees this as a problem?"
        ]
    ),
    Question(
        text="What would you need to know to feel confident about this?",
        question_type=QuestionType.CLARIFYING,
        when_to_ask="When someone is uncertain but hasn't articulated what would resolve the uncertainty",
        follow_ups=[
            "Is that information gettable?",
            "What if you couldn't get that information?",
            "How confident would you need to be to act?"
        ]
    ),

    # EXPANDING questions
    Question(
        text="What's another way to look at this?",
        question_type=QuestionType.EXPANDING,
        when_to_ask="When someone seems locked into a single perspective",
        follow_ups=[
            "What would [specific other person] say about this?",
            "What's the most charitable interpretation?",
            "What's the opposite position, stated as strongly as possible?"
        ]
    ),
    Question(
        text="What are you not seeing because you're too close to it?",
        question_type=QuestionType.EXPANDING,
        when_to_ask="When someone might benefit from distance",
        follow_ups=[
            "What would a stranger notice?",
            "What would you tell a friend in this situation?",
            "What will you think about this in ten years?"
        ]
    ),
    Question(
        text="What possibilities have you already dismissed?",
        question_type=QuestionType.EXPANDING,
        when_to_ask="When someone feels like they have no options",
        follow_ups=[
            "Why did you dismiss them?",
            "Were those reasons definitely true?",
            "What would have to change to make those options viable?"
        ]
    ),

    # GROUNDING questions
    Question(
        text="What's actually happening, right now, concretely?",
        question_type=QuestionType.GROUNDING,
        when_to_ask="When someone is lost in abstraction or catastrophizing",
        follow_ups=[
            "What do you see, hear, feel?",
            "What's the next immediate step?",
            "What's in your control right now?"
        ]
    ),
    Question(
        text="What do you actually know, versus what are you assuming?",
        question_type=QuestionType.GROUNDING,
        when_to_ask="When someone is treating assumptions as facts",
        follow_ups=[
            "How could you test that assumption?",
            "What if that assumption was wrong?",
            "Where did that assumption come from?"
        ]
    ),
    Question(
        text="What's the evidence for that?",
        question_type=QuestionType.GROUNDING,
        when_to_ask="When someone makes a claim that might not be grounded",
        follow_ups=[
            "Is that evidence strong or weak?",
            "What would count as counter-evidence?",
            "Are you looking for evidence or confirmation?"
        ]
    ),

    # CHALLENGING questions
    Question(
        text="What if you're wrong about this?",
        question_type=QuestionType.CHALLENGING,
        when_to_ask="When someone is very certain",
        follow_ups=[
            "How would you know if you were wrong?",
            "What's the cost of being wrong?",
            "Have you been wrong about similar things before?"
        ]
    ),
    Question(
        text="Who benefits from you believing this?",
        question_type=QuestionType.CHALLENGING,
        when_to_ask="When a belief might be serving someone's interests",
        follow_ups=[
            "Could this belief be serving your interests too?",
            "What would you believe if there were no stakes?",
            "Is the truth uncomfortable here?"
        ]
    ),
    Question(
        text="Is this what you actually think, or what you think you should think?",
        question_type=QuestionType.CHALLENGING,
        when_to_ask="When someone might be performing beliefs rather than holding them",
        follow_ups=[
            "What would you think if no one was watching?",
            "Where did this belief come from?",
            "Have you ever questioned this?"
        ]
    ),

    # CONNECTING questions
    Question(
        text="How does this relate to other things you know?",
        question_type=QuestionType.CONNECTING,
        when_to_ask="When something seems isolated but might have connections",
        follow_ups=[
            "Have you seen this pattern before?",
            "What else does this remind you of?",
            "Who else might know something about this?"
        ]
    ),
    Question(
        text="What's the relationship between these things?",
        question_type=QuestionType.CONNECTING,
        when_to_ask="When multiple things are in play but connections aren't clear",
        follow_ups=[
            "Do they cause each other?",
            "Do they share a common cause?",
            "Are they actually separate?"
        ]
    ),

    # IMAGINING questions
    Question(
        text="What would it look like if this was completely resolved?",
        question_type=QuestionType.IMAGINING,
        when_to_ask="When someone is focused on problems without vision",
        follow_ups=[
            "How would you feel?",
            "What would be different?",
            "What's one step toward that?"
        ]
    ),
    Question(
        text="What's the best possible outcome here?",
        question_type=QuestionType.IMAGINING,
        when_to_ask="When someone is only imagining bad outcomes",
        follow_ups=[
            "What would make that outcome more likely?",
            "Have you planned for success as well as failure?",
            "What if things go better than you expect?"
        ]
    ),
    Question(
        text="If you couldn't fail, what would you try?",
        question_type=QuestionType.IMAGINING,
        when_to_ask="When fear of failure is constraining thinking",
        follow_ups=[
            "What's actually stopping you?",
            "Is failure really as bad as you think?",
            "What would you learn from failing?"
        ]
    ),

    # FEELING questions
    Question(
        text="What do you actually feel about this?",
        question_type=QuestionType.FEELING,
        when_to_ask="When someone is all in their head",
        follow_ups=[
            "Where do you feel that in your body?",
            "What is that feeling trying to tell you?",
            "Have you felt this way before?"
        ]
    ),
    Question(
        text="What are you afraid of here?",
        question_type=QuestionType.FEELING,
        when_to_ask="When fear might be driving behavior but isn't named",
        follow_ups=[
            "Is that fear realistic?",
            "What would help with that fear?",
            "What would you do if you weren't afraid?"
        ]
    ),
    Question(
        text="What do you want?",
        question_type=QuestionType.FEELING,
        when_to_ask="When someone is focused on what they should want",
        follow_ups=[
            "Not what you should want - what do you actually want?",
            "What would satisfying that want feel like?",
            "Why do you want that?"
        ]
    ),

    # VALUING questions
    Question(
        text="What matters most here?",
        question_type=QuestionType.VALUING,
        when_to_ask="When priorities are unclear or competing",
        follow_ups=[
            "If you could only have one thing, what would it be?",
            "What would you sacrifice to get that?",
            "How do you know that's what matters most?"
        ]
    ),
    Question(
        text="What would the person you want to be do here?",
        question_type=QuestionType.VALUING,
        when_to_ask="When someone might be acting against their values",
        follow_ups=[
            "Who is that person?",
            "What's stopping you from being that person now?",
            "What's the smallest step toward that?"
        ]
    ),
    Question(
        text="What will you regret not doing?",
        question_type=QuestionType.VALUING,
        when_to_ask="When someone needs to connect to longer-term perspective",
        follow_ups=[
            "In a year? In ten years? At the end of your life?",
            "What regrets do you already have?",
            "Is this a pattern?"
        ]
    ),
]


# =============================================================================
# THE QUESTION PARTNER
# =============================================================================

class QuestionPartner:
    """
    A simple tool for thinking alongside someone.

    This is not an answer-giver. It's a question-asker.
    The goal is to help clarify thinking, not to prescribe conclusions.
    """

    def __init__(self):
        self.questions = QUESTION_BANK
        self.conversation_history: List[str] = []

    def get_question_by_type(self, q_type: QuestionType) -> Question:
        """Get a random question of a specific type."""
        matching = [q for q in self.questions if q.question_type == q_type]
        return random.choice(matching)

    def suggest_questions(self, context: Optional[str] = None) -> List[Question]:
        """
        Suggest a few questions that might be helpful.

        In a more sophisticated version, this would use the context
        to pick relevant questions. For now, it offers one from each type.
        """
        suggestions = []
        for q_type in QuestionType:
            matching = [q for q in self.questions if q.question_type == q_type]
            if matching:
                suggestions.append(random.choice(matching))
        return suggestions

    def reflect_back(self, user_input: str) -> str:
        """
        Reflect back what the user said, to help them hear themselves.

        This is a simple version - just reformulating.
        A more sophisticated version would extract key themes.
        """
        # Very simple reflection - in practice this would be more sophisticated
        return f"I hear you saying: {user_input}\n\nIs that right?"

    def offer_question(self, q_type: Optional[QuestionType] = None) -> str:
        """
        Offer a question for reflection.
        """
        if q_type:
            question = self.get_question_by_type(q_type)
        else:
            question = random.choice(self.questions)

        output = f"Here's a question to sit with:\n\n"
        output += f"  \"{question.text}\"\n\n"
        output += f"Some follow-ups if that's useful:\n"
        for fu in question.follow_ups:
            output += f"  - {fu}\n"

        return output


# =============================================================================
# INTERACTIVE MODE
# =============================================================================

def interactive_session():
    """
    Run an interactive question partner session.

    This is a simple prototype - just cycling through questions.
    A real version would be more responsive to what the user says.
    """
    partner = QuestionPartner()

    print("=" * 60)
    print("QUESTION PARTNER - A Prototype")
    print("=" * 60)
    print()
    print("This is a tool for thinking alongside you.")
    print("It won't give you answers. It will ask you questions.")
    print()
    print("Commands:")
    print("  'clarify'   - Get a clarifying question")
    print("  'expand'    - Get an expanding question")
    print("  'ground'    - Get a grounding question")
    print("  'challenge' - Get a challenging question")
    print("  'connect'   - Get a connecting question")
    print("  'imagine'   - Get an imagining question")
    print("  'feel'      - Get a feeling question")
    print("  'value'     - Get a valuing question")
    print("  'any'       - Get a random question")
    print("  'all'       - Get one of each type")
    print("  'quit'      - Exit")
    print()
    print("-" * 60)

    type_map = {
        'clarify': QuestionType.CLARIFYING,
        'expand': QuestionType.EXPANDING,
        'ground': QuestionType.GROUNDING,
        'challenge': QuestionType.CHALLENGING,
        'connect': QuestionType.CONNECTING,
        'imagine': QuestionType.IMAGINING,
        'feel': QuestionType.FEELING,
        'value': QuestionType.VALUING,
    }

    while True:
        print()
        user_input = input("What kind of question would help? > ").strip().lower()

        if user_input == 'quit':
            print("\nTake care. The questions remain available whenever you need them.")
            break

        elif user_input == 'all':
            print()
            for q_type in QuestionType:
                q = partner.get_question_by_type(q_type)
                print(f"[{q_type.value.upper()}]")
                print(f"  {q.text}")
                print()

        elif user_input == 'any':
            print()
            print(partner.offer_question())

        elif user_input in type_map:
            print()
            print(partner.offer_question(type_map[user_input]))

        else:
            print()
            print("I didn't understand that. Try 'any' for a random question,")
            print("or a type like 'clarify', 'expand', 'challenge', etc.")


# =============================================================================
# MAIN
# =============================================================================

if __name__ == "__main__":
    print()
    print("QUESTION PARTNER PROTOTYPE")
    print()
    print("This tool contains a bank of questions organized by type.")
    print("The idea: sometimes the help we need isn't answers but better questions.")
    print()

    # Show a sample of questions
    print("SAMPLE QUESTIONS FROM THE BANK:")
    print("-" * 60)

    partner = QuestionPartner()
    for q_type in QuestionType:
        q = partner.get_question_by_type(q_type)
        print(f"\n[{q_type.value.upper()}]")
        print(f"  \"{q.text}\"")
        print(f"  When useful: {q.when_to_ask}")

    print()
    print("-" * 60)
    print()
    print("To run an interactive session, call: interactive_session()")
    print("Or import QuestionPartner and use programmatically.")
    print()

    # Uncomment to run interactive mode:
    # interactive_session()
