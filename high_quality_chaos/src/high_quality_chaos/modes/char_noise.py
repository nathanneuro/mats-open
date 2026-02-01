"""
Character-Level Noise Mode

Injects visual chaos through UTF-8 character manipulation:
- Leetspeak (l33t) substitutions
- Unicode homoglyphs (visually similar characters from other scripts)
- Subtle diacritics and accent marks
- Mathematical and fancy unicode variants
- Aesthetic spacing modifications

The goal is text that's still readable but visually strange.
"""

import random
import re
from typing import Optional

# Classic leetspeak substitutions
LEETSPEAK = {
    'a': ['4', '@', '/-\\', '^'],
    'b': ['8', '|3', '13', '!3'],
    'c': ['(', '<', '{', '['],
    'd': ['|)', '|]', '0', 'cl'],
    'e': ['3', '€', '&', '[-'],
    'f': ['|=', 'ph', '|#'],
    'g': ['6', '9', '&', '(_+'],
    'h': ['#', '|-|', '}{', ']-['],
    'i': ['1', '!', '|', ']['],
    'j': ['_|', '_/', '_]'],
    'k': ['|<', '|{', ']{'],
    'l': ['1', '|_', '|', '£'],
    'm': ['|\\/|', '/\\/\\', '^^', '|v|'],
    'n': ['|\\|', '/\\/', '|V', '/V'],
    'o': ['0', '()', '[]', '<>'],
    'p': ['|*', '|>', '|D', '|o'],
    'q': ['(_,)', '()_', '0_', '&'],
    'r': ['|2', '|?', '/2', '®'],
    's': ['5', '$', '§', 'z'],
    't': ['7', '+', '-|-', '†'],
    'u': ['|_|', '\\_/', '\\_\\', 'µ'],
    'v': ['\\/', '|/', '\\|'],
    'w': ['\\/\\/', 'vv', '\\N', '\\^/'],
    'x': ['><', '}{', '×', '%'],
    'y': ["'/", '`/', '¥', 'j'],
    'z': ['2', '7_', '~/_', '%'],
}

# Subtle leetspeak (more readable)
SUBTLE_LEET = {
    'a': ['4', '@'],
    'e': ['3'],
    'i': ['1', '!'],
    'l': ['1'],
    'o': ['0'],
    's': ['5', '$'],
    't': ['7'],
}

# Unicode homoglyphs (visually similar from other scripts)
HOMOGLYPHS = {
    'a': ['а', 'ą', 'ă', 'ā', 'ä'],  # Cyrillic а, Polish, Romanian, etc.
    'b': ['ƅ', 'Ь', 'ḃ'],
    'c': ['с', 'ç', 'ć', 'ċ'],  # Cyrillic с
    'd': ['ԁ', 'ḋ', 'ď'],
    'e': ['е', 'ė', 'ę', 'ё', 'ë'],  # Cyrillic е
    'f': ['ƒ', 'ḟ'],
    'g': ['ġ', 'ğ', 'ģ'],
    'h': ['һ', 'ḣ', 'ħ'],  # Cyrillic һ
    'i': ['і', 'ï', 'ı', 'ì', 'í'],  # Ukrainian і
    'j': ['ј', 'ĵ'],  # Cyrillic ј
    'k': ['κ', 'ķ', 'ḳ'],
    'l': ['ӏ', 'ł', 'ľ', 'ļ'],  # Cyrillic palochka
    'm': ['м', 'ṁ'],  # Cyrillic м
    'n': ['ո', 'ñ', 'ń', 'ň', 'ņ'],  # Armenian
    'o': ['о', 'ö', 'ø', 'ō', 'ő'],  # Cyrillic о
    'p': ['р', 'ṗ'],  # Cyrillic р
    'q': ['ԛ', 'ɋ'],
    'r': ['ṙ', 'ř', 'ŗ'],
    's': ['ѕ', 'ś', 'ş', 'š', 'ṡ'],  # Cyrillic ѕ
    't': ['т', 'ť', 'ț', 'ṫ'],
    'u': ['ü', 'ū', 'ű', 'ų', 'ù', 'ú'],
    'v': ['ν', 'ѵ'],  # Greek nu, Cyrillic izhitsa
    'w': ['ẃ', 'ẅ', 'ŵ'],
    'x': ['х', 'ẋ'],  # Cyrillic х
    'y': ['у', 'ý', 'ÿ', 'ŷ'],  # Cyrillic у
    'z': ['ź', 'ż', 'ž', 'ẓ'],

    'A': ['А', 'Á', 'À', 'Ä', 'Â', 'Ã'],  # Cyrillic А
    'B': ['В', 'Ḃ', 'Ɓ'],  # Cyrillic В
    'C': ['С', 'Ç', 'Ć', 'Č'],  # Cyrillic С
    'D': ['Ḋ', 'Ď'],
    'E': ['Е', 'Ë', 'É', 'È', 'Ê', 'Ė'],  # Cyrillic Е
    'F': ['Ḟ'],
    'G': ['Ġ', 'Ğ'],
    'H': ['Н', 'Ḣ', 'Ħ'],  # Cyrillic Н
    'I': ['І', 'Ï', 'Í', 'Ì', 'Î'],  # Ukrainian І
    'J': ['Ј', 'Ĵ'],  # Cyrillic Ј
    'K': ['К', 'Ḳ', 'Ķ'],  # Cyrillic К
    'L': ['Ł', 'Ľ', 'Ļ'],
    'M': ['М', 'Ṁ'],  # Cyrillic М
    'N': ['Ñ', 'Ń', 'Ň'],
    'O': ['О', 'Ö', 'Ø', 'Ó', 'Ò', 'Ô'],  # Cyrillic О
    'P': ['Р', 'Ṗ'],  # Cyrillic Р
    'Q': ['Ԛ'],
    'R': ['Ṙ', 'Ř'],
    'S': ['Ѕ', 'Ś', 'Ş', 'Š', 'Ṡ'],  # Cyrillic Ѕ
    'T': ['Т', 'Ť', 'Ț', 'Ṫ'],  # Cyrillic Т
    'U': ['Ü', 'Ú', 'Ù', 'Û', 'Ū'],
    'V': ['Ѵ', 'Ṿ'],  # Cyrillic Izhitsa
    'W': ['Ẃ', 'Ẅ', 'Ŵ'],
    'X': ['Х', 'Ẋ'],  # Cyrillic Х
    'Y': ['У', 'Ý', 'Ŷ'],  # Cyrillic У
    'Z': ['Ź', 'Ż', 'Ž'],
}

# Combining diacritical marks (add above/below characters)
COMBINING_MARKS = [
    '\u0300',  # Combining grave accent
    '\u0301',  # Combining acute accent
    '\u0302',  # Combining circumflex
    '\u0303',  # Combining tilde
    '\u0304',  # Combining macron
    '\u0306',  # Combining breve
    '\u0307',  # Combining dot above
    '\u0308',  # Combining diaeresis
    '\u030a',  # Combining ring above
    '\u030c',  # Combining caron
]

# Zalgo-style marks (more extreme)
ZALGO_ABOVE = [
    '\u030d', '\u030e', '\u0310', '\u0312', '\u0313', '\u0314',
    '\u033d', '\u033e', '\u033f', '\u0346', '\u034a', '\u034b', '\u034c',
]
ZALGO_BELOW = [
    '\u0316', '\u0317', '\u0318', '\u0319', '\u031c', '\u031d', '\u031e',
    '\u031f', '\u0320', '\u0324', '\u0325', '\u0326', '\u0329', '\u032a',
]

# Mathematical/fancy unicode alphabets
MATH_BOLD = {chr(i): chr(0x1D400 + i - ord('A')) for i in range(ord('A'), ord('Z') + 1)}
MATH_BOLD.update({chr(i): chr(0x1D41A + i - ord('a')) for i in range(ord('a'), ord('z') + 1)})

MATH_ITALIC = {chr(i): chr(0x1D434 + i - ord('A')) for i in range(ord('A'), ord('Z') + 1)}
MATH_ITALIC.update({chr(i): chr(0x1D44E + i - ord('a')) for i in range(ord('a'), ord('z') + 1)})
# Fix 'h' which has a special character
MATH_ITALIC['h'] = 'ℎ'

MATH_SCRIPT = {chr(i): chr(0x1D49C + i - ord('A')) for i in range(ord('A'), ord('Z') + 1)}
MATH_SCRIPT.update({chr(i): chr(0x1D4B6 + i - ord('a')) for i in range(ord('a'), ord('z') + 1)})
# Fix special script letters
for old, new in [('B', 'ℬ'), ('E', 'ℰ'), ('F', 'ℱ'), ('H', 'ℋ'), ('I', 'ℐ'),
                  ('L', 'ℒ'), ('M', 'ℳ'), ('R', 'ℛ'), ('e', 'ℯ'), ('g', 'ℊ'), ('o', 'ℴ')]:
    MATH_SCRIPT[old] = new

FULLWIDTH = {chr(i): chr(0xFF01 + i - ord('!')) for i in range(ord('!'), ord('~') + 1)}

# Small caps
SMALL_CAPS = {
    'a': 'ᴀ', 'b': 'ʙ', 'c': 'ᴄ', 'd': 'ᴅ', 'e': 'ᴇ', 'f': 'ꜰ', 'g': 'ɢ',
    'h': 'ʜ', 'i': 'ɪ', 'j': 'ᴊ', 'k': 'ᴋ', 'l': 'ʟ', 'm': 'ᴍ', 'n': 'ɴ',
    'o': 'ᴏ', 'p': 'ᴘ', 'q': 'ǫ', 'r': 'ʀ', 's': 'ꜱ', 't': 'ᴛ', 'u': 'ᴜ',
    'v': 'ᴠ', 'w': 'ᴡ', 'x': 'x', 'y': 'ʏ', 'z': 'ᴢ',
}


class CharNoiseMode:
    """
    Injects character-level visual noise while maintaining readability.
    """

    def __init__(
        self,
        intensity: float = 0.2,
        style: str = "mixed",
        preserve_words: Optional[list] = None,
    ):
        """
        Args:
            intensity: Probability of character modification (0.0 to 1.0)
            style: One of "leet", "subtle_leet", "homoglyph", "diacritics",
                   "zalgo", "math", "fullwidth", "smallcaps", "mixed"
            preserve_words: List of words to never modify (e.g., proper nouns)
        """
        self.intensity = max(0.0, min(1.0, intensity))
        self.style = style
        self.preserve_words = set(w.lower() for w in (preserve_words or []))

    def _apply_leet(self, text: str, subtle: bool = False) -> str:
        """Apply leetspeak substitutions."""
        table = SUBTLE_LEET if subtle else LEETSPEAK
        result = []

        for char in text:
            lower = char.lower()
            if lower in table and random.random() < self.intensity:
                replacement = random.choice(table[lower])
                # Preserve case for simple substitutions
                if len(replacement) == 1 and char.isupper():
                    replacement = replacement.upper()
                result.append(replacement)
            else:
                result.append(char)

        return ''.join(result)

    def _apply_homoglyphs(self, text: str) -> str:
        """Replace characters with visually similar Unicode alternatives."""
        result = []

        for char in text:
            if char in HOMOGLYPHS and random.random() < self.intensity:
                result.append(random.choice(HOMOGLYPHS[char]))
            else:
                result.append(char)

        return ''.join(result)

    def _apply_diacritics(self, text: str, intensity: float = 1.0) -> str:
        """Add subtle combining diacritical marks."""
        result = []

        for char in text:
            result.append(char)
            if char.isalpha() and random.random() < self.intensity * intensity:
                # Add 1-2 marks
                num_marks = random.randint(1, 2)
                for _ in range(num_marks):
                    result.append(random.choice(COMBINING_MARKS))

        return ''.join(result)

    def _apply_zalgo(self, text: str, intensity: float = 0.5) -> str:
        """Apply zalgo-style combining characters (controlled chaos)."""
        result = []

        for char in text:
            result.append(char)
            if char.isalpha() and random.random() < self.intensity * intensity:
                # Add marks above and below
                num_above = random.randint(0, 2)
                num_below = random.randint(0, 2)

                for _ in range(num_above):
                    result.append(random.choice(ZALGO_ABOVE))
                for _ in range(num_below):
                    result.append(random.choice(ZALGO_BELOW))

        return ''.join(result)

    def _apply_math_style(self, text: str, style: str = "bold") -> str:
        """Convert to mathematical unicode alphabet."""
        styles = {
            "bold": MATH_BOLD,
            "italic": MATH_ITALIC,
            "script": MATH_SCRIPT,
        }
        table = styles.get(style, MATH_BOLD)

        result = []
        for char in text:
            if char in table and random.random() < self.intensity:
                result.append(table[char])
            else:
                result.append(char)

        return ''.join(result)

    def _apply_fullwidth(self, text: str) -> str:
        """Convert to fullwidth unicode characters."""
        result = []

        for char in text:
            if char in FULLWIDTH and random.random() < self.intensity:
                result.append(FULLWIDTH[char])
            else:
                result.append(char)

        return ''.join(result)

    def _apply_smallcaps(self, text: str) -> str:
        """Convert lowercase to small caps."""
        result = []

        for char in text:
            lower = char.lower()
            if char.islower() and lower in SMALL_CAPS and random.random() < self.intensity:
                result.append(SMALL_CAPS[lower])
            else:
                result.append(char)

        return ''.join(result)

    def _should_preserve_word(self, word: str) -> bool:
        """Check if a word should be preserved (not modified)."""
        clean = re.sub(r'[^\w]', '', word).lower()
        return clean in self.preserve_words

    def transform(self, text: str) -> str:
        """
        Transform text with character-level noise.

        Args:
            text: Input text to transform

        Returns:
            Transformed text with character noise
        """
        # If preserving certain words, process word by word
        if self.preserve_words:
            words = re.split(r'(\s+)', text)
            result_parts = []

            for word in words:
                if word.isspace() or self._should_preserve_word(word):
                    result_parts.append(word)
                else:
                    result_parts.append(self._transform_segment(word))

            return ''.join(result_parts)

        return self._transform_segment(text)

    def _transform_segment(self, text: str) -> str:
        """Apply the configured transformation style to a text segment."""
        style_map = {
            "leet": lambda t: self._apply_leet(t, subtle=False),
            "subtle_leet": lambda t: self._apply_leet(t, subtle=True),
            "homoglyph": self._apply_homoglyphs,
            "diacritics": self._apply_diacritics,
            "zalgo": self._apply_zalgo,
            "math_bold": lambda t: self._apply_math_style(t, "bold"),
            "math_italic": lambda t: self._apply_math_style(t, "italic"),
            "math_script": lambda t: self._apply_math_style(t, "script"),
            "fullwidth": self._apply_fullwidth,
            "smallcaps": self._apply_smallcaps,
        }

        if self.style == "mixed":
            # Randomly choose a style for each transformation
            style = random.choice(list(style_map.keys()))
            return style_map[style](text)
        elif self.style in style_map:
            return style_map[self.style](text)
        else:
            return text

    def demonstrate_styles(self, sample: str = "Hello World") -> dict:
        """
        Show how different styles transform the same text.

        Returns dict of style_name -> transformed_text
        """
        saved_intensity = self.intensity
        self.intensity = 0.8  # High intensity for demo

        demos = {}
        for style in ["leet", "subtle_leet", "homoglyph", "diacritics",
                      "zalgo", "math_bold", "math_italic", "math_script",
                      "fullwidth", "smallcaps"]:
            self.style = style
            demos[style] = self._transform_segment(sample)

        self.intensity = saved_intensity
        return demos
