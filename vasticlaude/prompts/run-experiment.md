# Prompt Template: Running an Experiment on Vast.ai

Use this template when a user wants to run an experiment on Vast.ai.

## Information to Gather

Before starting, ensure you know:

1. **What to run**: The command or script to execute
2. **GPU requirements**: What kind of GPU is needed (VRAM, type)
3. **Duration estimate**: How long the experiment typically takes
4. **Budget**: How much the user is willing to spend
5. **Data dependencies**: Any data that needs to be on the instance
6. **Output**: What files/artifacts to retrieve when done

## Suggested Opening

```
I'll help you run this on Vast.ai. Let me confirm a few things:

1. The command to run: `[command]`
2. GPU needs: [X]GB VRAM minimum
3. Expected duration: ~[X] hours
4. Budget limit: $[X] max

Does this look right? I'll also need your Vast.ai API key if it's not already configured.
```

## During Execution

Keep the user informed with periodic updates:

```
[HH:MM] Searching for suitable instance... found 3 options
[HH:MM] Provisioning instance #12345 (RTX 4090, $0.40/hr)
[HH:MM] Waiting for instance to be ready...
[HH:MM] Instance ready. Setting up environment...
[HH:MM] Syncing code (42 files, 1.2MB)...
[HH:MM] Starting experiment: `python train.py`
[HH:MM] Experiment running. GPU utilization: 95%. ETA: ~2 hours
[HH:MM] Checkpoint saved. Progress: 50%
```

## On Failure

If something fails, be specific and offer options:

```
The instance became unavailable at 45% progress.

Good news: I found a checkpoint from 5 minutes ago.

Options:
1. Resume on a new instance (recommended)
2. Start over on a new instance
3. Stop and retrieve what we have

What would you like to do?
```

## On Completion

```
Experiment completed successfully!

Summary:
- Duration: 2h 15m
- Cost: $0.90
- GPU: RTX 4090 (instance #12345)

Results retrieved to: ./outputs/

Instance has been destroyed.

Would you like me to summarize the results or run any analysis?
```
