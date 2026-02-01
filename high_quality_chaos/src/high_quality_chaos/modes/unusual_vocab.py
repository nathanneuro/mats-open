"""
Unusual Vocabulary Mode

Substitutes common, boring words with strange, unusual, or archaic alternatives
while preserving semantic meaning. The goal is high-entropy vocabulary without
sacrificing comprehensibility.
"""

import json
import random
import re
from pathlib import Path
from typing import Optional

# Unusual words database: maps common words to lists of strange alternatives
# Organized by rough semantic category
UNUSUAL_VOCAB_DB = {
    # Verbs of motion/action
    "walk": ["perambulate", "sashay", "traipse", "galumph", "shamble", "schlep", "mosey", "toddle"],
    "run": ["scamper", "careen", "hurtle", "scramble", "scuttle", "scarper", "bolt", "absquatulate"],
    "go": ["vamoose", "decamp", "hie", "betake", "wend", "repair", "locomote"],
    "move": ["peregrinate", "translocate", "bestir", "stir", "budge", "gallivant"],
    "jump": ["gambol", "caper", "cavort", "frolic", "bound", "vault", "hurdle"],
    "fall": ["tumble", "plummet", "cascade", "careen", "topple", "nosedive"],
    "throw": ["hurl", "lob", "catapult", "chuck", "fling", "heave", "yeet"],
    "hit": ["wallop", "thwack", "smite", "clobber", "bludgeon", "pummel", "belabor"],
    "eat": ["masticate", "nosh", "scarf", "hoover", "devour", "ingest", "partake"],
    "drink": ["imbibe", "quaff", "tipple", "swig", "guzzle", "slurp", "sup"],
    "sleep": ["slumber", "snooze", "kip", "drowse", "repose", "zonk"],
    "look": ["gander", "gawk", "ogle", "peer", "peep", "squint", "rubberneck"],
    "see": ["behold", "espy", "descry", "discern", "perceive", "witness", "glimpse"],
    "think": ["cogitate", "ruminate", "muse", "ponder", "cerebrate", "noodle"],
    "talk": ["jabber", "prattle", "yammer", "blather", "confabulate", "natter", "palaver"],
    "say": ["utter", "articulate", "vocalize", "opine", "declaim", "intone", "ejaculate"],
    "shout": ["bellow", "holler", "vociferate", "caterwaul", "ululate", "yawp"],
    "laugh": ["chortle", "guffaw", "cachinnate", "titter", "snicker", "snigger"],
    "cry": ["blubber", "snivel", "keen", "lament", "ululate", "wail", "caterwail"],
    "want": ["crave", "covet", "yearn", "hanker", "pine", "itch", "jones"],
    "like": ["fancy", "relish", "savor", "dig", "groove on", "cotton to"],
    "love": ["adore", "cherish", "dote on", "be besotted with", "be enamored of"],
    "hate": ["abhor", "loathe", "detest", "execrate", "abominate", "despise"],
    "get": ["procure", "obtain", "acquire", "secure", "snag", "cop", "score"],
    "give": ["bestow", "bequeath", "impart", "confer", "proffer", "tender"],
    "take": ["purloin", "filch", "swipe", "nab", "pinch", "snatch", "appropriate"],
    "make": ["fabricate", "concoct", "contrive", "cobble", "wrangle", "conjure"],
    "break": ["shatter", "fracture", "splinter", "rend", "cleave", "sunder"],
    "fix": ["mend", "rectify", "remedy", "ameliorate", "patch", "bodge"],
    "start": ["commence", "initiate", "inaugurate", "embark", "instigate"],
    "stop": ["cease", "desist", "halt", "discontinue", "kibosh", "scotch"],
    "help": ["assist", "succor", "abet", "bolster", "buttress", "undergird"],
    "hurt": ["injure", "maim", "afflict", "beset", "smite", "lacerate"],
    "use": ["employ", "utilize", "wield", "ply", "leverage", "harness"],
    "try": ["endeavor", "strive", "essay", "assay", "attempt", "have a bash at"],
    "know": ["ken", "fathom", "grok", "apprehend", "cognize", "twig"],
    "understand": ["comprehend", "grok", "fathom", "grasp", "twig", "suss"],
    "remember": ["recollect", "reminisce", "bethink", "hark back"],
    "forget": ["disremember", "blank on", "lose track of"],
    "believe": ["credit", "deem", "reckon", "ween", "trow", "opine"],
    "seem": ["appear", "come across as", "strike one as"],
    "become": ["metamorphose into", "transmogrify into", "morph into"],
    "happen": ["transpire", "occur", "befall", "eventuate", "come to pass"],
    "wait": ["tarry", "bide", "linger", "loiter", "dally", "dawdle"],
    "work": ["toil", "labor", "slog", "grind", "plug away", "beaver away"],
    "play": ["frolic", "cavort", "gambol", "romp", "skylark", "lark about"],
    "live": ["dwell", "abide", "reside", "inhabit", "sojourn"],
    "die": ["perish", "expire", "croak", "snuff it", "shuffle off this mortal coil"],
    "kill": ["slay", "dispatch", "liquidate", "terminate", "eliminate", "smite"],
    "steal": ["pilfer", "purloin", "filch", "pinch", "nick", "swipe", "lift"],
    "hide": ["secrete", "squirrel away", "stash", "cache", "conceal"],
    "show": ["display", "exhibit", "flaunt", "brandish", "parade", "trot out"],
    "find": ["unearth", "uncover", "ferret out", "stumble upon", "chance upon"],
    "lose": ["mislay", "misplace", "forfeit"],
    "win": ["triumph", "prevail", "vanquish", "conquer", "best"],
    "fail": ["flounder", "flop", "founder", "come a cropper", "bomb"],
    "change": ["alter", "modify", "transmute", "transmogrify", "metamorphose"],
    "grow": ["burgeon", "flourish", "proliferate", "wax", "mushroom"],
    "shrink": ["dwindle", "wane", "diminish", "contract", "atrophy"],
    "burn": ["combust", "conflagrate", "incinerate", "char", "immolate"],
    "cut": ["cleave", "sever", "incise", "lacerate", "slice", "carve"],
    "build": ["construct", "erect", "fabricate", "assemble", "cobble together"],
    "destroy": ["demolish", "raze", "obliterate", "annihilate", "decimate"],
    "clean": ["scour", "scrub", "swab", "ablute", "expurgate"],
    "write": ["scribe", "inscribe", "pen", "author", "scrawl", "dash off"],
    "read": ["peruse", "parse", "decipher", "construe", "scan"],

    # Adjectives - quality/state
    "good": ["splendid", "superb", "capital", "cracking", "smashing", "bully"],
    "bad": ["abysmal", "atrocious", "execrable", "egregious", "lousy", "rotten"],
    "big": ["gargantuan", "colossal", "elephantine", "humongous", "whopping"],
    "small": ["diminutive", "minuscule", "teensy", "wee", "itty-bitty", "piddling"],
    "fast": ["fleet", "nippy", "zippy", "expeditious", "brisk", "spanking"],
    "slow": ["languid", "sluggish", "torpid", "plodding", "glacial", "snail-paced"],
    "hot": ["sweltering", "scorching", "torrid", "blistering", "sizzling"],
    "cold": ["frigid", "gelid", "glacial", "frosty", "nippy", "brisk"],
    "new": ["novel", "newfangled", "fresh-minted", "spanking new", "nascent"],
    "old": ["venerable", "antiquated", "hoary", "decrepit", "grizzled", "superannuated"],
    "happy": ["elated", "jubilant", "ebullient", "euphoric", "tickled pink", "chuffed"],
    "sad": ["morose", "lugubrious", "melancholic", "doleful", "woebegone", "crestfallen"],
    "angry": ["wrathful", "irate", "incensed", "livid", "apoplectic", "choleric"],
    "scared": ["terrified", "petrified", "aghast", "affrighted", "spooked"],
    "tired": ["fatigued", "knackered", "bushed", "zonked", "spent", "enervated"],
    "strong": ["robust", "strapping", "brawny", "stalwart", "burly", "hale"],
    "weak": ["feeble", "frail", "enfeebled", "enervated", "debilitated", "puny"],
    "smart": ["astute", "sagacious", "perspicacious", "shrewd", "canny", "brainy"],
    "stupid": ["obtuse", "dim-witted", "doltish", "bovine", "vacuous", "addlepated"],
    "beautiful": ["pulchritudinous", "comely", "ravishing", "fetching", "fair"],
    "ugly": ["hideous", "unsightly", "grotesque", "ghastly", "repugnant"],
    "nice": ["pleasant", "agreeable", "affable", "congenial", "amiable", "genial"],
    "mean": ["churlish", "curmudgeonly", "cantankerous", "ornery", "crabby"],
    "important": ["paramount", "pivotal", "crucial", "consequential", "momentous"],
    "strange": ["peculiar", "outlandish", "queer", "uncanny", "eldritch", "weird"],
    "normal": ["quotidian", "humdrum", "workaday", "prosaic", "vanilla"],
    "easy": ["facile", "effortless", "straightforward", "a doddle", "a cinch"],
    "hard": ["arduous", "grueling", "onerous", "Herculean", "formidable"],
    "rich": ["affluent", "moneyed", "flush", "well-heeled", "loaded", "rolling in it"],
    "poor": ["indigent", "impecunious", "penurious", "penniless", "skint"],
    "full": ["replete", "brimming", "chock-full", "stuffed to the gills"],
    "empty": ["vacant", "bereft", "devoid", "barren", "hollow"],
    "clean": ["pristine", "immaculate", "spotless", "spick-and-span"],
    "dirty": ["filthy", "grimy", "grubby", "squalid", "mucky", "begrimed"],
    "wet": ["sodden", "drenched", "waterlogged", "saturated", "sopping"],
    "dry": ["parched", "desiccated", "arid", "bone-dry"],
    "loud": ["raucous", "cacophonous", "thunderous", "deafening", "stentorian"],
    "quiet": ["hushed", "muted", "subdued", "sotto voce", "still"],
    "busy": ["bustling", "frenetic", "hectic", "abuzz", "humming"],
    "calm": ["tranquil", "serene", "placid", "unruffled", "halcyon"],
    "funny": ["droll", "comical", "risible", "amusing", "mirthful", "jocular"],
    "serious": ["grave", "solemn", "sober", "earnest", "staid"],
    "crazy": ["unhinged", "deranged", "bonkers", "daft", "barmy", "batty"],
    "boring": ["tedious", "monotonous", "humdrum", "soporific", "yawnsome"],
    "interesting": ["engrossing", "riveting", "captivating", "compelling", "gripping"],

    # Nouns - common things
    "thing": ["doodad", "thingamajig", "whatchamacallit", "doohickey", "gizmo"],
    "person": ["individual", "personage", "soul", "specimen", "character", "bod"],
    "people": ["folk", "denizens", "populace", "citizenry", "multitude", "masses"],
    "friend": ["chum", "pal", "comrade", "compatriot", "confidant", "crony"],
    "enemy": ["adversary", "nemesis", "foe", "antagonist", "arch-rival"],
    "child": ["youngster", "sprog", "tyke", "tot", "moppet", "rugrat", "ankle-biter"],
    "man": ["fellow", "chap", "bloke", "gent", "gentleman", "dude"],
    "woman": ["dame", "lass", "gal", "lady"],
    "house": ["abode", "domicile", "dwelling", "residence", "digs", "pad", "homestead"],
    "room": ["chamber", "quarters", "compartment", "nook"],
    "car": ["automobile", "vehicle", "motor", "jalopy", "wheels", "ride"],
    "money": ["lucre", "dough", "moolah", "simoleons", "scratch", "bread", "loot"],
    "food": ["victuals", "grub", "nosh", "comestibles", "provender", "fare"],
    "water": ["aqua", "H2O", "the wet stuff"],
    "job": ["occupation", "vocation", "trade", "calling", "gig", "racket"],
    "problem": ["conundrum", "predicament", "quandary", "pickle", "jam", "snag"],
    "idea": ["notion", "conception", "brainchild", "brainwave", "wheeze"],
    "question": ["query", "inquiry", "interrogatory", "poser"],
    "answer": ["reply", "response", "rejoinder", "riposte", "retort"],
    "mistake": ["blunder", "gaffe", "faux pas", "boo-boo", "flub", "cock-up"],
    "success": ["triumph", "victory", "coup", "masterstroke", "feather in one's cap"],
    "failure": ["fiasco", "debacle", "flop", "washout", "dud", "turkey"],
    "beginning": ["commencement", "inception", "genesis", "outset", "dawn"],
    "end": ["conclusion", "terminus", "denouement", "finale", "swan song"],
    "place": ["locale", "locality", "venue", "spot", "haunt", "stomping ground"],
    "time": ["epoch", "era", "juncture", "moment", "spell", "stretch"],
    "way": ["manner", "fashion", "mode", "method", "modus operandi"],
    "part": ["portion", "segment", "component", "constituent", "morsel"],
    "group": ["cadre", "coterie", "cabal", "cohort", "posse", "gaggle"],
    "story": ["yarn", "tale", "narrative", "chronicle", "saga", "anecdote"],
    "word": ["vocable", "term", "utterance", "lexeme"],
    "sound": ["din", "racket", "clamor", "hubbub", "cacophony"],
    "smell": ["aroma", "scent", "odor", "whiff", "effluvium", "reek"],
    "taste": ["flavor", "savor", "tang", "palate"],
    "feeling": ["sensation", "sentiment", "inkling", "intuition", "vibe"],
    "thought": ["cogitation", "rumination", "notion", "musing", "reflection"],
    "face": ["visage", "countenance", "physiognomy", "mug", "phiz", "kisser"],
    "body": ["physique", "corpus", "frame", "bod", "chassis"],
    "hand": ["mitt", "paw", "appendage"],
    "head": ["noggin", "dome", "cranium", "bonce", "noodle", "bean"],
    "heart": ["ticker", "core", "cockles"],
    "brain": ["gray matter", "noodle", "cerebrum"],
    "eyes": ["peepers", "orbs", "lookers"],
    "mouth": ["maw", "gob", "kisser", "yap", "trap"],
    "clothes": ["garments", "attire", "raiment", "threads", "togs", "duds"],
    "book": ["tome", "volume", "opus", "manuscript"],
    "letter": ["missive", "epistle", "dispatch", "communique"],

    # Adverbs
    "very": ["exceedingly", "frightfully", "tremendously", "awfully", "jolly"],
    "really": ["verily", "truly", "genuinely", "indeed", "in truth"],
    "quickly": ["posthaste", "expeditiously", "apace", "pronto", "lickety-split"],
    "slowly": ["languidly", "torpidly", "sluggishly", "at a snail's pace"],
    "completely": ["utterly", "thoroughly", "wholly", "entirely", "altogether"],
    "almost": ["nigh", "well-nigh", "virtually", "all but", "practically"],
    "often": ["oft", "frequently", "habitually", "regularly", "recurrently"],
    "sometimes": ["occasionally", "periodically", "intermittently", "on occasion"],
    "always": ["perpetually", "eternally", "incessantly", "evermore", "ad infinitum"],
    "never": ["ne'er", "not ever", "at no time", "under no circumstances"],
    "now": ["presently", "forthwith", "posthaste", "at this juncture"],
    "soon": ["anon", "ere long", "in short order", "before long", "imminently"],
    "here": ["hither", "in this locale", "at this locus"],
    "there": ["thither", "yonder", "at that locale"],
    "together": ["in concert", "in tandem", "jointly", "collectively"],
    "alone": ["solo", "singly", "unaccompanied", "on one's lonesome"],
    "maybe": ["perchance", "mayhaps", "peradventure", "conceivably", "possibly"],
    "probably": ["in all likelihood", "most likely", "presumably", "doubtless"],
    "certainly": ["assuredly", "indubitably", "unquestionably", "without a doubt"],
    "suddenly": ["abruptly", "precipitously", "all of a sudden", "out of the blue"],
    "finally": ["at long last", "ultimately", "in the end", "after all is said and done"],
}


class UnusualVocabMode:
    """
    Transforms text by substituting common words with unusual/archaic alternatives.
    """

    def __init__(
        self,
        intensity: float = 0.3,
        custom_vocab: Optional[dict] = None,
        preserve_tone: bool = True,
    ):
        """
        Args:
            intensity: Probability of replacing eligible words (0.0 to 1.0)
            custom_vocab: Additional vocabulary to merge with base database
            preserve_tone: Try to maintain general register (formal/informal match)
        """
        self.intensity = max(0.0, min(1.0, intensity))
        self.preserve_tone = preserve_tone

        # Build vocabulary database
        self.vocab_db = UNUSUAL_VOCAB_DB.copy()
        if custom_vocab:
            for word, alts in custom_vocab.items():
                if word in self.vocab_db:
                    self.vocab_db[word].extend(alts)
                else:
                    self.vocab_db[word] = alts

        # Build lowercase lookup
        self.vocab_lookup = {k.lower(): v for k, v in self.vocab_db.items()}

    def _match_case(self, original: str, replacement: str) -> str:
        """Match the case pattern of the original word."""
        if original.isupper():
            return replacement.upper()
        elif original.istitle():
            return replacement.title()
        else:
            return replacement.lower()

    def _is_word_boundary(self, text: str, start: int, end: int) -> bool:
        """Check if position represents word boundaries."""
        before_ok = start == 0 or not text[start-1].isalnum()
        after_ok = end == len(text) or not text[end].isalnum()
        return before_ok and after_ok

    def transform(self, text: str) -> str:
        """
        Transform text by substituting common words with unusual alternatives.

        Args:
            text: Input text to transform

        Returns:
            Transformed text with unusual vocabulary substitutions
        """
        result = text
        offset = 0

        # Find all words and their positions
        word_pattern = re.compile(r'\b[a-zA-Z]+\b')

        for match in word_pattern.finditer(text):
            word = match.group()
            word_lower = word.lower()

            if word_lower in self.vocab_lookup:
                # Probabilistic replacement based on intensity
                if random.random() < self.intensity:
                    alternatives = self.vocab_lookup[word_lower]
                    replacement = random.choice(alternatives)
                    replacement = self._match_case(word, replacement)

                    # Calculate position with offset adjustment
                    start = match.start() + offset
                    end = match.end() + offset

                    # Replace in result
                    result = result[:start] + replacement + result[end:]

                    # Update offset for subsequent replacements
                    offset += len(replacement) - len(word)

        return result

    def get_available_substitutions(self, text: str) -> dict:
        """
        Get all possible substitutions for words in the text.

        Returns dict mapping words to their available alternatives.
        """
        word_pattern = re.compile(r'\b[a-zA-Z]+\b')
        substitutions = {}

        for match in word_pattern.finditer(text):
            word = match.group()
            word_lower = word.lower()

            if word_lower in self.vocab_lookup and word_lower not in substitutions:
                substitutions[word_lower] = self.vocab_lookup[word_lower]

        return substitutions

    @classmethod
    def expand_vocab_from_web(cls, query: str = "unusual english words") -> list:
        """
        Search the web for unusual vocabulary to expand the database.
        Returns list of words found (integration with web search).

        Note: This is a stub for web expansion capability.
        Real implementation would use WebSearch or similar.
        """
        # Placeholder for web expansion
        # In practice, would search for:
        # - archaic/obsolete words
        # - regional dialect words
        # - technical jargon from obscure fields
        # - loanwords from other languages
        # - neologisms and slang
        raise NotImplementedError(
            "Web expansion requires external search integration. "
            "Use the CLI with --expand-vocab flag for interactive expansion."
        )
