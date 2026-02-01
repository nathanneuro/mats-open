"""
Neologism Mode

Creates plausible, guessable neologisms through:
- Language mixing (borrowing morphemes from other languages)
- Word smushing (portmanteau generation)
- Creative affixation

The goal is novel words that can be understood from context.
"""

import random
import re
from typing import Optional

# Affixes borrowed from various languages
FOREIGN_PREFIXES = {
    # German
    "über": {"meaning": "super/over", "replaces": ["very", "super", "extremely", "ultra"]},
    "un": {"meaning": "non/un", "replaces": ["not", "non"]},
    "ur": {"meaning": "proto/original", "replaces": ["original", "ancient", "primal"]},
    "haupt": {"meaning": "main/chief", "replaces": ["main", "chief", "primary"]},
    # Japanese
    "mega": {"meaning": "big", "replaces": ["big", "large", "huge"]},
    "dai": {"meaning": "great", "replaces": ["great", "grand"]},
    "cho": {"meaning": "super", "replaces": ["super", "very"]},
    # Latin
    "quasi": {"meaning": "sort-of/almost", "replaces": ["almost", "sort of", "nearly"]},
    "proto": {"meaning": "first/original", "replaces": ["first", "original", "early"]},
    "neo": {"meaning": "new", "replaces": ["new", "modern"]},
    "pseudo": {"meaning": "fake/false", "replaces": ["fake", "false", "pretend"]},
    "meta": {"meaning": "about itself", "replaces": ["self-referential", "recursive"]},
    "omni": {"meaning": "all", "replaces": ["all", "every", "universal"]},
    # Greek
    "hyper": {"meaning": "excessive", "replaces": ["excessive", "extreme", "over"]},
    "para": {"meaning": "beside/beyond", "replaces": ["beside", "almost", "near"]},
    "anti": {"meaning": "against", "replaces": ["against", "opposing"]},
    "poly": {"meaning": "many", "replaces": ["many", "multiple", "various"]},
    # Spanish/Portuguese
    "super": {"meaning": "above/great", "replaces": ["great", "excellent", "super"]},
    "mal": {"meaning": "bad", "replaces": ["bad", "poor", "ill"]},
    # French
    "demi": {"meaning": "half", "replaces": ["half", "partial", "semi"]},
    "faux": {"meaning": "false", "replaces": ["false", "fake", "imitation"]},
    # Yiddish
    "schm": {"meaning": "dismissive reduplication", "replaces": []},  # special handling
}

FOREIGN_SUFFIXES = {
    # German
    "heit": {"meaning": "-ness", "replaces": ["ness", "ity"]},
    "schaft": {"meaning": "-ship/-dom", "replaces": ["ship", "dom"]},
    "lich": {"meaning": "-ly/-ish", "replaces": ["ly", "ish"]},
    # Japanese
    "ish": {"meaning": "somewhat", "replaces": []},
    # Latin/Greek
    "esque": {"meaning": "in the style of", "replaces": ["like", "ish"]},
    "oid": {"meaning": "resembling", "replaces": ["like", "ish"]},
    "ize": {"meaning": "to make/become", "replaces": []},
    "ification": {"meaning": "process of", "replaces": ["ing", "ation"]},
    "itude": {"meaning": "state of", "replaces": ["ness"]},
    "arium": {"meaning": "place of", "replaces": ["place", "room"]},
    "orium": {"meaning": "place for", "replaces": ["place", "room"]},
    # French
    "age": {"meaning": "action/result", "replaces": ["ing", "ment"]},
    "ette": {"meaning": "small/feminine", "replaces": ["small", "little"]},
    "ière": {"meaning": "one who does", "replaces": ["er", "or"]},
    # Russian-ish
    "nik": {"meaning": "person associated with", "replaces": ["person", "one who"]},
    "ska": {"meaning": "related to", "replaces": []},
    # Misc
    "core": {"meaning": "aesthetic/genre", "replaces": ["style", "aesthetic"]},
    "pilled": {"meaning": "influenced by", "replaces": ["influenced", "affected"]},
    "maxxing": {"meaning": "maximizing", "replaces": ["maximizing", "optimizing"]},
    "brained": {"meaning": "minded", "replaces": ["minded", "thinking"]},
}

# Words commonly used in portmanteaus
SMUSH_PAIRS = [
    # Emotional intensifiers
    ("anxiety", "excitement", "anxcitement"),
    ("fear", "joy", "fearjoy"),
    ("hungry", "angry", "hangry"),
    ("sad", "glad", "sglad"),

    # Tech/modern
    ("information", "entertainment", "infotainment"),
    ("education", "entertainment", "edutainment"),
    ("emotion", "icon", "emoticon"),
    ("web", "log", "blog"),
    ("fan", "magazine", "fanzine"),
    ("chill", "relax", "chillax"),
]


class NeologismMode:
    """
    Transforms text by creating plausible neologisms through
    language mixing and word smushing.
    """

    def __init__(
        self,
        intensity: float = 0.2,
        prefer_language_mixing: bool = True,
        prefer_portmanteau: bool = True,
        languages: Optional[list] = None,
    ):
        """
        Args:
            intensity: Probability of creating neologisms (0.0 to 1.0)
            prefer_language_mixing: Allow foreign prefix/suffix additions
            prefer_portmanteau: Allow word smushing
            languages: List of language sources to use (None = all)
        """
        self.intensity = max(0.0, min(1.0, intensity))
        self.prefer_language_mixing = prefer_language_mixing
        self.prefer_portmanteau = prefer_portmanteau
        self.languages = languages

    def _add_yiddish_schmee(self, word: str) -> str:
        """
        Apply Yiddish dismissive reduplication.
        "fancy schmancy", "rules schmules"
        """
        # Find first vowel
        first_vowel = None
        for i, char in enumerate(word):
            if char.lower() in 'aeiou':
                first_vowel = i
                break

        if first_vowel is None or first_vowel == 0:
            return f"{word} schm{word}"

        return f"{word} schm{word[first_vowel:]}"

    def _create_portmanteau(self, word1: str, word2: str) -> str:
        """
        Create a portmanteau from two words by finding optimal blend point.
        """
        # Simple approach: overlap on shared sounds or midpoint blend
        w1 = word1.lower()
        w2 = word2.lower()

        # Try to find overlapping sounds
        best_overlap = 0
        best_pos = len(w1) // 2

        for i in range(1, len(w1)):
            suffix = w1[i:]
            for j in range(len(w2)):
                prefix = w2[:len(suffix)]
                if suffix == prefix:
                    if len(suffix) > best_overlap:
                        best_overlap = len(suffix)
                        best_pos = i
                    break

        # Blend at best position
        result = w1[:best_pos] + w2[best_overlap:]
        return result

    def _add_foreign_prefix(self, word: str) -> tuple[str, str]:
        """Add a foreign prefix to intensify or modify meaning."""
        prefix, info = random.choice(list(FOREIGN_PREFIXES.items()))

        # Handle special cases
        if prefix == "schm":
            return self._add_yiddish_schmee(word), "yiddish dismissive"

        # Don't stack prefixes that already exist
        if word.lower().startswith(prefix):
            return word, ""

        return f"{prefix}{word}", info["meaning"]

    def _add_foreign_suffix(self, word: str) -> tuple[str, str]:
        """Add a foreign suffix to modify word class or meaning."""
        suffix, info = random.choice(list(FOREIGN_SUFFIXES.items()))

        # Clean word ending for suffix attachment
        clean_word = word.rstrip('ey')

        # Avoid awkward double consonants or vowels
        if clean_word.endswith(suffix[0]) and suffix[0] not in 'aeiou':
            clean_word = clean_word[:-1]

        return f"{clean_word}{suffix}", info["meaning"]

    def _spontaneous_portmanteau(self, text: str) -> str:
        """
        Find adjacent descriptive words and smush them together.
        """
        # Pattern: adjective + noun or adverb + adjective
        # This is a simplified heuristic
        words = text.split()

        if len(words) < 2:
            return text

        result_words = []
        i = 0

        while i < len(words):
            if i < len(words) - 1 and random.random() < self.intensity * 0.3:
                # Try to smush adjacent words (30% of intensity chance)
                word1 = re.sub(r'[^\w]', '', words[i])
                word2 = re.sub(r'[^\w]', '', words[i + 1])

                if len(word1) >= 3 and len(word2) >= 3:
                    portmanteau = self._create_portmanteau(word1, word2)

                    # Preserve punctuation from second word
                    trailing = ''.join(c for c in words[i + 1] if not c.isalnum())

                    result_words.append(portmanteau + trailing)
                    i += 2
                    continue

            result_words.append(words[i])
            i += 1

        return ' '.join(result_words)

    def transform(self, text: str) -> str:
        """
        Transform text by introducing neologisms.

        Args:
            text: Input text to transform

        Returns:
            Transformed text with neologisms
        """
        result = text

        # Apply language mixing (prefixes/suffixes)
        if self.prefer_language_mixing:
            word_pattern = re.compile(r'\b([a-zA-Z]{4,})\b')

            def maybe_affix(match):
                word = match.group(1)
                if random.random() < self.intensity * 0.5:
                    if random.random() < 0.5:
                        modified, _ = self._add_foreign_prefix(word)
                    else:
                        modified, _ = self._add_foreign_suffix(word)
                    return modified
                return word

            result = word_pattern.sub(maybe_affix, result)

        # Apply portmanteau generation
        if self.prefer_portmanteau:
            result = self._spontaneous_portmanteau(result)

        return result

    def generate_neologism(
        self,
        base_word: str,
        style: str = "random"
    ) -> tuple[str, str]:
        """
        Generate a single neologism from a base word.

        Args:
            base_word: The word to transform
            style: "prefix", "suffix", "portmanteau", or "random"

        Returns:
            Tuple of (neologism, explanation)
        """
        if style == "prefix" or (style == "random" and random.random() < 0.5):
            return self._add_foreign_prefix(base_word)
        else:
            return self._add_foreign_suffix(base_word)

    def yiddishize(self, text: str) -> str:
        """
        Apply Yiddish-style dismissive reduplication throughout.
        "The rules are the rules" -> "The rules-schmules are the rules-schmules"
        """
        word_pattern = re.compile(r'\b([a-zA-Z]{4,})\b')

        def add_schmee(match):
            word = match.group(1)
            if random.random() < self.intensity:
                return self._add_yiddish_schmee(word)
            return word

        return word_pattern.sub(add_schmee, text)
