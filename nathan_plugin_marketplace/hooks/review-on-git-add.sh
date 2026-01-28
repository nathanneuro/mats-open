#!/bin/bash
# PreToolUse hook: review code files before git add
# Reads tool input JSON from stdin, checks if it's a git add command,
# and runs lightweight style checks on the staged code files.

set -euo pipefail

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // ""')

# Quick exit if not a git add command
if ! echo "$COMMAND" | grep -qE '^\s*git\s+add\b'; then
    exit 0
fi

# Skip if it's git add with only non-file flags (e.g., git add -u with no args means all tracked)
# but we still want to review those files

PROJECT_DIR=$(echo "$INPUT" | jq -r '.cwd // ""')
if [ -z "$PROJECT_DIR" ] || [ ! -d "$PROJECT_DIR" ]; then
    exit 0
fi

# Determine which files are being added
if echo "$COMMAND" | grep -qE 'git\s+add\s+(-A|--all|\.\s*$|\.$)'; then
    # Bulk add: get all modified + untracked files
    FILES=$(cd "$PROJECT_DIR" && {
        git diff --name-only 2>/dev/null
        git ls-files --others --exclude-standard 2>/dev/null
    } | sort -u)
else
    # Extract file paths after "git add" (strip flags like -f, -v, etc.)
    FILES=$(echo "$COMMAND" | sed -E 's/.*git\s+add\s+//' | tr ' ' '\n' | grep -v '^-' | grep -v '^$')
fi

# Filter to code files
CODE_FILES=""
while IFS= read -r f; do
    [ -z "$f" ] && continue
    case "$f" in
        *.py|*.js|*.ts|*.tsx|*.jsx|*.sh|*.bash|*.go|*.rs|*.java|*.c|*.cpp|*.h|*.hpp|*.rb|*.jl)
            CODE_FILES="${CODE_FILES}${f}"$'\n'
            ;;
    esac
done <<< "$FILES"

CODE_FILES=$(echo "$CODE_FILES" | sed '/^$/d')

if [ -z "$CODE_FILES" ]; then
    exit 0
fi

# Run checks on each code file
ISSUES=""

while IFS= read -r filepath; do
    [ -z "$filepath" ] && continue

    # Resolve path
    if [[ "$filepath" != /* ]]; then
        fullpath="$PROJECT_DIR/$filepath"
    else
        fullpath="$filepath"
    fi

    [ ! -f "$fullpath" ] && continue

    FILE_ISSUES=""

    # --- HIGH PRIORITY CHECKS ---

    # 1. Single-letter variable assignments (not i/j/k/n/_ in loops or comprehensions)
    SINGLE_LETTER=$(grep -nE '^\s*[a-hlo-wyz]\s*=' "$fullpath" 2>/dev/null | head -5 || true)
    if [ -n "$SINGLE_LETTER" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] Single-letter variable names:
$(echo "$SINGLE_LETTER" | sed 's/^/    /')"
    fi

    # 2. Common abbreviation variable names
    ABBREVS=$(grep -nE '\b(cfg|mgr|proc|btn|ctx|evt|cb|fn|obj|tmp|ret)\s*=' "$fullpath" 2>/dev/null | head -5 || true)
    if [ -n "$ABBREVS" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] Abbreviated variable names - use full descriptive names:
$(echo "$ABBREVS" | sed 's/^/    /')"
    fi

    # 3. Missing tqdm in files with multiple loops
    FOR_COUNT=$(grep -cE '^\s*for\s' "$fullpath" 2>/dev/null || true)
    HAS_TQDM=$(grep -cE 'tqdm' "$fullpath" 2>/dev/null || true)
    if [ "$FOR_COUNT" -gt 2 ] && [ "$HAS_TQDM" -eq 0 ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] ${FOR_COUNT} for-loops but no tqdm progress bars"
    fi

    # 4. Direct PDF reading
    PDF_READ=$(grep -nE 'PyPDF2|pdfplumber|import fitz|pymupdf' "$fullpath" 2>/dev/null | head -3 || true)
    if [ -n "$PDF_READ" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] Direct PDF reading detected - convert to markdown first:
$(echo "$PDF_READ" | sed 's/^/    /')"
    fi

    # 5. Training loops without checkpointing
    HAS_TRAINING_LOOP=$(grep -cE 'for.*(epoch|step|batch|iteration)' "$fullpath" 2>/dev/null || true)
    HAS_SAVE=$(grep -cE '\.(save|save_pretrained)|checkpoint|torch\.save|json\.dump|\.to_json|\.to_csv|joblib\.dump' "$fullpath" 2>/dev/null || true)
    if [ "$HAS_TRAINING_LOOP" -gt 0 ] && [ "$HAS_SAVE" -eq 0 ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] Training/processing loop found but no checkpoint or save calls"
    fi

    # --- MEDIUM PRIORITY CHECKS ---

    # 6. Pickle/shelve usage
    PICKLE=$(grep -nE 'pickle\.(dump|load)|import pickle|import shelve' "$fullpath" 2>/dev/null | head -3 || true)
    if [ -n "$PICKLE" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [SUGGESTION] Pickle/shelve usage - prefer json/jsonl for human-readable storage:
$(echo "$PICKLE" | sed 's/^/    /')"
    fi

    # 7. Classes that might be unnecessary (simple heuristic: flag for review)
    CLASS_DEFS=$(grep -nE '^\s*class\s+\w+' "$fullpath" 2>/dev/null | head -5 || true)
    CLASS_COUNT=$(echo "$CLASS_DEFS" | grep -c '.' 2>/dev/null || true)
    # Only flag if there are classes AND no obvious justification (nn.Module, BaseModel, etc.)
    HAS_FRAMEWORK_CLASS=$(grep -cE 'nn\.Module|BaseModel|ABC|Protocol|Enum|dataclass|Exception' "$fullpath" 2>/dev/null || true)
    if [ "$CLASS_COUNT" -gt 0 ] && [ "$HAS_FRAMEWORK_CLASS" -eq 0 ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [SUGGESTION] ${CLASS_COUNT} class(es) found - verify OOP is justified (prefer dicts/functions):
$(echo "$CLASS_DEFS" | sed 's/^/    /')"
    fi

    # 8. Sequential API calls that could be parallelized
    SEQ_API=$(grep -nE 'requests\.(get|post|put)|urllib|httpx\.(get|post)' "$fullpath" 2>/dev/null | head -3 || true)
    SEQ_API_COUNT=$(echo "$SEQ_API" | grep -c '.' 2>/dev/null || true)
    HAS_ASYNC=$(grep -cE 'async def|asyncio|concurrent\.futures|ThreadPool|multiprocessing' "$fullpath" 2>/dev/null || true)
    if [ "$SEQ_API_COUNT" -gt 2 ] && [ "$HAS_ASYNC" -eq 0 ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [SUGGESTION] Multiple HTTP calls without parallelization - consider async/concurrent.futures"
    fi

    # 9. Complex/nested list comprehensions (Python only)
    case "$filepath" in
        *.py)
            # Match comprehensions with 2+ for clauses, or nested comprehensions
            NESTED_COMP=$(grep -nE '\[.*for\s+\w+\s+in\s+.*for\s+\w+\s+in\s+' "$fullpath" 2>/dev/null | head -5 || true)
            INNER_COMP=$(grep -nE '\[\s*\[.*for\s+' "$fullpath" 2>/dev/null | head -5 || true)
            COMPLEX_COMP="${NESTED_COMP}${INNER_COMP}"
            if [ -n "$COMPLEX_COMP" ]; then
                FILE_ISSUES="${FILE_ISSUES}
  [SUGGESTION] Complex/nested list comprehension - use an explicit for loop instead:
$(echo "$COMPLEX_COMP" | sed 's/^/    /')"
            fi
            ;;
    esac

    # 10. Swallowed errors - bare except with pass/continue/return
    BARE_EXCEPT=$(grep -nE '^\s*except(\s*:|\s+Exception\s*:)' "$fullpath" 2>/dev/null | head -5 || true)
    if [ -n "$BARE_EXCEPT" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] Bare except / except Exception - fail fast, don't swallow errors:
$(echo "$BARE_EXCEPT" | sed 's/^/    /')"
    fi

    # 11. Defensive isinstance type-checking for branching
    ISINSTANCE_BRANCH=$(grep -nE 'isinstance\s*\(' "$fullpath" 2>/dev/null | head -5 || true)
    if [ -n "$ISINSTANCE_BRANCH" ]; then
        FILE_ISSUES="${FILE_ISSUES}
  [STRONG] isinstance() checks found - don't branch on type, just let wrong types crash:
$(echo "$ISINSTANCE_BRANCH" | sed 's/^/    /')"
    fi

    if [ -n "$FILE_ISSUES" ]; then
        ISSUES="${ISSUES}

### ${filepath}${FILE_ISSUES}"
    fi
done <<< "$CODE_FILES"

# If issues found, provide context to Claude
if [ -n "$ISSUES" ]; then
    REVIEW_TEXT="## Pre-commit Code Review

Found potential style issues in files being staged:
${ISSUES}

Consider fixing these before committing, or run the code-reviewer agent for a full review."

    # Encode review text as JSON string
    ENCODED=$(printf '%s' "$REVIEW_TEXT" | jq -Rs .)

    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow","additionalContext":%s}}' "$ENCODED"
    exit 0
fi

# No issues - allow silently
exit 0
