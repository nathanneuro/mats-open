# vasticlaude

A toolkit for connecting your existing repos to GPU experiments on [Vast.ai](https://vast.ai), orchestrated by Claude Code.

## The Problem

Vast.ai offers cheap GPU servers, but they can be unreliable. Managing experiments across potentially flaky infrastructure requires patience and robust error handling—exactly what an AI assistant can provide.

## The Solution

vasticlaude provides a set of tools and conventions that let Claude Code:

1. **Provision** GPU instances on Vast.ai matching your requirements
2. **Deploy** your code and dependencies to remote instances
3. **Run** experiments with automatic retry and checkpointing
4. **Monitor** progress and handle failures gracefully
5. **Retrieve** results and artifacts back to your local machine
6. **Teardown** instances when done (or on budget limits)

## Design Philosophy

- **Repo-agnostic**: Works with any existing project structure
- **Claude-native**: Designed to be used *by* Claude Code, not just *with* it
- **Failure-tolerant**: Assumes things will break; handles it gracefully
- **Cost-conscious**: Tracks spend, supports budget limits, prefers spot instances
- **Transparent**: All state is human-readable; no magic black boxes

## Components

```
vasticlaude/
├── cli/              # Command-line tools for manual use
├── lib/              # Core library for programmatic use
├── prompts/          # Claude Code prompt templates and conventions
├── scripts/          # Helper scripts for common operations
└── examples/         # Example configurations and workflows
```

## Typical Workflow

1. User tells Claude: "Run this training script on a GPU"
2. Claude uses vasticlaude to:
   - Find a suitable Vast.ai instance
   - SSH in, set up the environment
   - Sync the repo
   - Launch the experiment
   - Monitor for completion/failure
   - Retry if instance dies
   - Pull results when done
   - Destroy the instance

## Status

Early development. Contributions welcome.
