"""
High Quality Chaos: Anti-mode-collapse text transformer

Inject controlled entropy into text while preserving semantic content.
Because low-entropy text is sloppified, and pure randomness is boring static.
"""

from .transformer import ChaosTransformer
from .modes.unusual_vocab import UnusualVocabMode
from .modes.neologisms import NeologismMode
from .modes.char_noise import CharNoiseMode

__version__ = "0.1.0"
__all__ = ["ChaosTransformer", "UnusualVocabMode", "NeologismMode", "CharNoiseMode"]
