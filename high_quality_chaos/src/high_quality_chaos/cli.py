"""
Command-line interface for High Quality Chaos.

Usage:
    chaos transform "your text here"
    chaos transform --file input.txt --output output.txt
    echo "text" | chaos transform --stdin
    chaos demo "sample text"
"""

import sys
from pathlib import Path

import click
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from .transformer import ChaosTransformer
from .modes.char_noise import CharNoiseMode

console = Console()


@click.group()
@click.version_option(version="0.1.0")
def main():
    """
    High Quality Chaos: Anti-mode-collapse text transformer.

    Inject controlled entropy into text while preserving semantic content.
    Because low-entropy text is sloppified, and pure randomness is boring static.
    """
    pass


@main.command()
@click.argument("text", required=False)
@click.option("--file", "-f", type=click.Path(exists=True), help="Input file to transform")
@click.option("--output", "-o", type=click.Path(), help="Output file (default: stdout)")
@click.option("--stdin", is_flag=True, help="Read from stdin")
@click.option("--intensity", "-i", default=0.3, type=float, help="Chaos intensity 0.0-1.0")
@click.option("--unusual-vocab/--no-unusual-vocab", default=True, help="Use unusual vocabulary")
@click.option("--neologisms/--no-neologisms", default=False, help="Generate neologisms")
@click.option("--char-noise/--no-char-noise", default=False, help="Add character-level noise")
@click.option("--char-style", type=str, default="mixed",
              help="Character noise style: leet, subtle_leet, homoglyph, diacritics, etc.")
@click.option("--seed", type=int, help="Random seed for reproducibility")
@click.option("--llm", is_flag=True, help="Use LLM for intelligent chaos (requires ANTHROPIC_API_KEY)")
def transform(text, file, output, stdin, intensity, unusual_vocab, neologisms,
              char_noise, char_style, seed, llm):
    """
    Transform text with high-quality chaos.

    Examples:
        chaos transform "The quick brown fox jumps over the lazy dog"
        chaos transform -f essay.txt -o chaotic_essay.txt
        chaos transform --neologisms --char-noise "Hello world"
    """
    # Get input text
    if stdin:
        input_text = sys.stdin.read()
    elif file:
        input_text = Path(file).read_text()
    elif text:
        input_text = text
    else:
        console.print("[red]Error:[/red] Provide text, --file, or --stdin")
        sys.exit(1)

    # Create transformer
    transformer = ChaosTransformer(
        unusual_vocab=unusual_vocab,
        neologisms=neologisms,
        char_noise=char_noise,
        intensity=intensity,
        seed=seed,
    )

    # Set char noise style if specified
    if char_noise and transformer.char_noise_mode:
        transformer.char_noise_mode.style = char_style

    # Transform
    if llm:
        try:
            result = transformer.transform_with_llm(input_text)
        except (ValueError, ImportError) as e:
            console.print(f"[red]Error:[/red] {e}")
            sys.exit(1)
    else:
        result = transformer.transform(input_text)

    # Output
    if output:
        Path(output).write_text(result)
        console.print(f"[green]Written to {output}[/green]")
    else:
        console.print(result)


@main.command()
@click.argument("text", default="The quick brown fox jumps over the lazy dog.")
@click.option("--intensity", "-i", default=0.5, type=float, help="Demo intensity")
def demo(text, intensity):
    """
    Demonstrate all chaos modes on sample text.

    Shows how each mode transforms the text independently.
    """
    console.print(Panel(f"[bold]Original:[/bold] {text}", title="High Quality Chaos Demo"))

    # Vocab mode
    transformer = ChaosTransformer(
        unusual_vocab=True,
        neologisms=False,
        char_noise=False,
        intensity=intensity,
    )
    vocab_result = transformer.transform(text)
    console.print(f"\n[cyan]Unusual Vocab:[/cyan] {vocab_result}")

    # Neologism mode
    transformer = ChaosTransformer(
        unusual_vocab=False,
        neologisms=True,
        char_noise=False,
        intensity=intensity,
    )
    neo_result = transformer.transform(text)
    console.print(f"[magenta]Neologisms:[/magenta] {neo_result}")

    # Character noise demos
    console.print("\n[yellow]Character Noise Styles:[/yellow]")
    char_mode = CharNoiseMode(intensity=0.6)
    demos = char_mode.demonstrate_styles(text[:30])

    table = Table()
    table.add_column("Style", style="green")
    table.add_column("Result")

    for style, result in demos.items():
        table.add_row(style, result)

    console.print(table)

    # Full chaos
    console.print("\n[red]Full Chaos (all modes):[/red]")
    full = ChaosTransformer.full_chaos(text, intensity=intensity)
    console.print(Panel(full))


@main.command()
@click.argument("word")
@click.option("--count", "-n", default=5, help="Number of suggestions")
def suggest(word, count):
    """
    Get unusual vocabulary suggestions for a word.

    Examples:
        chaos suggest "walk"
        chaos suggest "happy" -n 10
    """
    from .modes.unusual_vocab import UNUSUAL_VOCAB_DB

    word_lower = word.lower()

    if word_lower in UNUSUAL_VOCAB_DB:
        alternatives = UNUSUAL_VOCAB_DB[word_lower]
        console.print(f"[green]Alternatives for '{word}':[/green]")
        for alt in alternatives[:count]:
            console.print(f"  • {alt}")
        if len(alternatives) > count:
            console.print(f"  [dim]... and {len(alternatives) - count} more[/dim]")
    else:
        console.print(f"[yellow]No alternatives found for '{word}'[/yellow]")
        console.print("Consider adding it to the vocabulary database!")


@main.command()
@click.argument("text")
def stats(text):
    """
    Show transformation statistics for text.

    Shows how many words can be transformed and available alternatives.
    """
    transformer = ChaosTransformer(
        unusual_vocab=True,
        neologisms=True,
        char_noise=True,
        intensity=0.3,
    )

    info = transformer.get_stats(text)

    table = Table(title="Transformation Stats")
    table.add_column("Metric", style="cyan")
    table.add_column("Value", style="green")

    for key, value in info.items():
        table.add_row(key.replace("_", " ").title(), str(value))

    console.print(table)


@main.command()
def vocab():
    """
    List all words in the unusual vocabulary database.
    """
    from .modes.unusual_vocab import UNUSUAL_VOCAB_DB

    console.print(f"[bold]Unusual Vocabulary Database[/bold] ({len(UNUSUAL_VOCAB_DB)} words)\n")

    # Group by first letter
    by_letter = {}
    for word in sorted(UNUSUAL_VOCAB_DB.keys()):
        first = word[0].upper()
        if first not in by_letter:
            by_letter[first] = []
        by_letter[first].append(word)

    for letter in sorted(by_letter.keys()):
        words = by_letter[letter]
        console.print(f"[cyan]{letter}:[/cyan] {', '.join(words)}")


if __name__ == "__main__":
    main()
