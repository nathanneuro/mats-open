# Claude Code Instructions for vasticlaude

This toolkit is designed for YOU (Claude Code) to use when helping users run experiments on Vast.ai GPU servers.

## When to Use This

When the user wants to:
- Run training/inference on a GPU they don't have locally
- Use Vast.ai specifically (or mentions cheap GPU rental)
- Run long-running experiments that need monitoring

## Core Principles

### 1. Patience Over Speed
Vast.ai instances can be slow to start, may fail, and connections can drop. That's okay. Your job is to handle this gracefully so the user doesn't have to babysit.

### 2. Always Have a Recovery Plan
Before starting any operation:
- Know how to check if it succeeded
- Know how to retry if it failed
- Know where checkpoints/artifacts are stored

### 3. Be Cost-Conscious
- Always destroy instances when done
- Prefer spot/interruptible instances for fault-tolerant workloads
- Track and report costs to the user
- Honor budget limits

### 4. Keep State Visible
- Log what you're doing and why
- Store state in human-readable files
- Never assume previous state—always verify

## Typical Flow

```
1. UNDERSTAND what the user wants to run
2. FIND a suitable Vast.ai instance (gpu type, disk, cost)
3. PROVISION the instance
4. WAIT for it to be ready (with retries)
5. SETUP the environment (ssh, deps, code sync)
6. RUN the experiment
7. MONITOR for completion or failure
8. HANDLE failures (retry on same or new instance)
9. RETRIEVE results
10. TEARDOWN the instance
11. REPORT to user
```

## Tools Available

(TODO: Document the specific CLI/library tools as they're built)

## State Files

vasticlaude uses simple state files to track active experiments:

- `~/.vasticlaude/instances.json` - Currently provisioned instances
- `~/.vasticlaude/experiments/` - Per-experiment state and logs
- `.vasticlaude/` in repo - Project-specific config

## Error Handling

When things fail (and they will):

1. **Instance won't start**: Try a different instance/region
2. **SSH connection drops**: Retry with backoff (2s, 4s, 8s, 16s)
3. **Instance dies mid-experiment**: Check for checkpoints, resume on new instance
4. **Disk full**: Retrieve what you can, provision larger instance
5. **API errors**: Retry with backoff, then ask user for help

## Cost Tracking

Always track:
- Instance cost per hour
- Total time running
- Cumulative spend for this session/experiment

Report costs to user periodically and when done.
