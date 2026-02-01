# High Quality Chaos

**Anti-mode-collapse text transformer**

Low-entropy text is sloppified and boring. Pure randomness is static. High-quality chaos sits in the sweet spot—structured enough to preserve meaning, chaotic enough to be interesting.

This tool injects controlled entropy into text while preserving semantic content. It's not for code—it's for making prose weirder and more alive.

## Installation

```bash
pip install -e .
```

## Quick Start

```python
from high_quality_chaos import ChaosTransformer

# Quick one-liner
text = "The man walked to the store and bought some food."
weird = ChaosTransformer.quick_chaos(text)
# "The fellow perambulated to the store and procured some victuals."

# Full chaos with all modes
very_weird = ChaosTransformer.full_chaos(text, intensity=0.5)
```

## CLI Usage

```bash
# Basic transformation
chaos transform "The quick brown fox jumps over the lazy dog"

# From file
chaos transform -f essay.txt -o chaotic_essay.txt

# With options
chaos transform --intensity 0.5 --neologisms --char-noise "Hello world"

# Demo all modes
chaos demo "Sample text to transform"

# Get vocabulary suggestions
chaos suggest "walk"

# Use LLM for intelligent chaos (requires ANTHROPIC_API_KEY)
chaos transform --llm "Your text here"
```

## Modes

### 1. Unusual Vocabulary (default)

The core mode. Substitutes common words with strange, archaic, or unusual alternatives while preserving meaning.

```python
from high_quality_chaos import UnusualVocabMode

mode = UnusualVocabMode(intensity=0.4)
result = mode.transform("I want to eat some food and then sleep.")
# "I crave to nosh some victuals and then slumber."
```

The vocabulary database includes:
- Archaic/obsolete English words
- Regional dialect terms
- Literary vocabulary
- Informal/slang alternatives
- Technical jargon repurposed

### 2. Neologisms

Creates plausible, guessable neologisms through language mixing and word smushing.

```python
from high_quality_chaos import NeologismMode

mode = NeologismMode(intensity=0.3)

# Foreign prefix/suffix addition
mode.generate_neologism("problem")  # -> "überproblem"

# Yiddish dismissive reduplication
mode.yiddishize("rules are rules")  # -> "rules-schmules are rules-schmules"
```

Features:
- Borrows prefixes from German (über-, ur-, haupt-)
- Latin/Greek affixes (proto-, neo-, -oid, -esque)
- Japanese intensifiers (mega-, dai-, cho-)
- Portmanteau generation (word smushing)

### 3. Character Noise

Visual chaos through UTF-8 character manipulation. Still readable, but strange.

```python
from high_quality_chaos import CharNoiseMode

mode = CharNoiseMode(intensity=0.3, style="homoglyph")
result = mode.transform("Hello World")
# "Неllo Wоrld" (with Cyrillic lookalikes)
```

Styles:
- `leet` - Classic l33tsp34k
- `subtle_leet` - Minimal leet (just 4, 3, 1, 0, etc.)
- `homoglyph` - Cyrillic/Greek lookalikes
- `diacritics` - Subtle accent marks
- `zalgo` - Combining characters (controlled)
- `math_bold/italic/script` - Mathematical Unicode alphabets
- `fullwidth` - Ｆｕｌｌｗｉｄｔｈ characters
- `smallcaps` - sᴍᴀʟʟ ᴄᴀᴘs
- `mixed` - Random style per transformation

## LLM-Powered Transformation

For intelligent chaos that understands context:

```python
import os
os.environ["ANTHROPIC_API_KEY"] = "your-key"

transformer = ChaosTransformer(unusual_vocab=True)
result = transformer.transform_with_llm(
    "The meeting was productive and we made good progress.",
    instruction="Rewrite with unusual vocabulary and unexpected metaphors"
)
```

## Philosophy

Mode collapse in language models produces text that is:
- Predictable and generic
- Overusing common words
- Following the same rhythms
- Boring

High-quality chaos fights this by:
- Expanding vocabulary beyond the common
- Introducing controlled unpredictability
- Maintaining semantic coherence
- Creating text that surprises while remaining comprehensible

The goal is not randomness—it's **structured novelty**.

## Extending the Vocabulary

The vocab database is a Python dict you can extend:

```python
from high_quality_chaos.modes.unusual_vocab import UNUSUAL_VOCAB_DB

# Add your own
UNUSUAL_VOCAB_DB["computer"] = ["computation engine", "thinking machine", "electronic brain"]

# Or pass custom vocab to the mode
mode = UnusualVocabMode(custom_vocab={
    "meeting": ["confab", "powwow", "conclave", "summit"]
})
```

## API Reference

### ChaosTransformer

Main orchestrator class.

```python
transformer = ChaosTransformer(
    unusual_vocab=True,    # Enable vocab substitution
    neologisms=False,      # Enable neologism generation
    char_noise=False,      # Enable character noise
    intensity=0.3,         # Overall chaos level (0.0-1.0)
    seed=42,               # For reproducibility
)

result = transformer.transform(text)
preview = transformer.preview(text)  # Dict of mode -> result
stats = transformer.get_stats(text)  # Transformation statistics
```

### Individual Modes

Each mode can be used standalone:

```python
UnusualVocabMode(intensity=0.3, custom_vocab=None, preserve_tone=True)
NeologismMode(intensity=0.2, prefer_language_mixing=True, prefer_portmanteau=True)
CharNoiseMode(intensity=0.2, style="mixed", preserve_words=["Python"])
```
