"""
ChaosTransformer: Main orchestrator for high-quality chaos injection.

Combines multiple chaos modes to transform text while preserving meaning.
"""

import os
import random
from typing import Optional

from .modes.unusual_vocab import UnusualVocabMode
from .modes.neologisms import NeologismMode
from .modes.char_noise import CharNoiseMode


class ChaosTransformer:
    """
    Main transformer that orchestrates chaos injection across multiple modes.

    The philosophy: Low entropy text is sloppified and boring. Pure randomness
    is static. High-quality chaos sits in the sweet spot—structured enough to
    preserve meaning, chaotic enough to be interesting.
    """

    def __init__(
        self,
        unusual_vocab: bool = True,
        neologisms: bool = False,
        char_noise: bool = False,
        intensity: float = 0.3,
        seed: Optional[int] = None,
    ):
        """
        Initialize the chaos transformer with specified modes.

        Args:
            unusual_vocab: Enable unusual vocabulary substitutions
            neologisms: Enable neologism generation
            char_noise: Enable character-level noise
            intensity: Overall chaos intensity (0.0 to 1.0)
            seed: Random seed for reproducibility
        """
        if seed is not None:
            random.seed(seed)

        self.intensity = max(0.0, min(1.0, intensity))

        # Initialize enabled modes
        self.modes = []

        if unusual_vocab:
            self.unusual_vocab_mode = UnusualVocabMode(intensity=self.intensity)
            self.modes.append(("unusual_vocab", self.unusual_vocab_mode))
        else:
            self.unusual_vocab_mode = None

        if neologisms:
            self.neologism_mode = NeologismMode(intensity=self.intensity * 0.7)
            self.modes.append(("neologisms", self.neologism_mode))
        else:
            self.neologism_mode = None

        if char_noise:
            self.char_noise_mode = CharNoiseMode(intensity=self.intensity * 0.5)
            self.modes.append(("char_noise", self.char_noise_mode))
        else:
            self.char_noise_mode = None

    def transform(
        self,
        text: str,
        modes: Optional[list] = None,
    ) -> str:
        """
        Transform text with high-quality chaos.

        Args:
            text: Input text to transform
            modes: Specific modes to apply (None = use all enabled modes)

        Returns:
            Transformed chaotic text
        """
        result = text

        # Determine which modes to apply
        if modes is None:
            active_modes = self.modes
        else:
            active_modes = [(name, mode) for name, mode in self.modes if name in modes]

        # Apply modes in order
        for name, mode in active_modes:
            result = mode.transform(result)

        return result

    def preview(self, text: str) -> dict:
        """
        Preview transformations without combining them.

        Returns dict of mode_name -> transformed_text for comparison.
        """
        previews = {"original": text}

        for name, mode in self.modes:
            previews[name] = mode.transform(text)

        return previews

    def transform_with_llm(
        self,
        text: str,
        instruction: str = "Rewrite this text using unusual vocabulary while preserving meaning",
    ) -> str:
        """
        Use an LLM to intelligently inject chaos.

        Requires ANTHROPIC_API_KEY environment variable.

        Args:
            text: Input text to transform
            instruction: Prompt instruction for the LLM

        Returns:
            LLM-transformed text
        """
        api_key = os.environ.get("ANTHROPIC_API_KEY")
        if not api_key:
            raise ValueError(
                "ANTHROPIC_API_KEY environment variable required for LLM transformation"
            )

        try:
            import anthropic
        except ImportError:
            raise ImportError("anthropic package required. Install with: pip install anthropic")

        client = anthropic.Anthropic(api_key=api_key)

        prompt = f"""You are a text transformer that introduces high-quality chaos.
Your goal is to make text more interesting and less generic while preserving its core meaning.

{instruction}

Original text:
{text}

Transformed text (maintain similar length, preserve meaning, be creative with word choice):"""

        message = client.messages.create(
            model="claude-sonnet-4-20250514",
            max_tokens=len(text) * 2 + 200,
            messages=[{"role": "user", "content": prompt}]
        )

        return message.content[0].text.strip()

    @classmethod
    def quick_chaos(cls, text: str, intensity: float = 0.3) -> str:
        """
        One-liner for quick chaos injection.

        Args:
            text: Text to transform
            intensity: Chaos intensity

        Returns:
            Transformed text
        """
        transformer = cls(
            unusual_vocab=True,
            neologisms=False,
            char_noise=False,
            intensity=intensity,
        )
        return transformer.transform(text)

    @classmethod
    def full_chaos(cls, text: str, intensity: float = 0.4) -> str:
        """
        Apply all chaos modes at once.

        Args:
            text: Text to transform
            intensity: Chaos intensity

        Returns:
            Fully chaotic text
        """
        transformer = cls(
            unusual_vocab=True,
            neologisms=True,
            char_noise=True,
            intensity=intensity,
        )
        return transformer.transform(text)

    def set_intensity(self, intensity: float) -> None:
        """Update intensity across all modes."""
        self.intensity = max(0.0, min(1.0, intensity))

        if self.unusual_vocab_mode:
            self.unusual_vocab_mode.intensity = self.intensity

        if self.neologism_mode:
            self.neologism_mode.intensity = self.intensity * 0.7

        if self.char_noise_mode:
            self.char_noise_mode.intensity = self.intensity * 0.5

    def get_stats(self, text: str) -> dict:
        """
        Get statistics about potential transformations.

        Returns info about how much the text could be modified.
        """
        stats = {
            "char_count": len(text),
            "word_count": len(text.split()),
        }

        if self.unusual_vocab_mode:
            subs = self.unusual_vocab_mode.get_available_substitutions(text)
            stats["vocab_substitutable_words"] = len(subs)
            stats["vocab_total_alternatives"] = sum(len(v) for v in subs.values())

        return stats
