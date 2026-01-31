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

The primary tool is the `vastai` CLI. Install with `pip install vastai`.

### Essential Commands

```bash
# Setup (one-time)
vastai set api-key YOUR_API_KEY

# Search for GPU instances
vastai search offers 'reliability > 0.95 num_gpus=1 gpu_ram >= 24' --order 'dph'
# Filters: reliability, num_gpus, gpu_ram, compute_cap, disk_space, dph ($/hr), etc.
# Order: dph (cheapest first), gpu_ram, num_gpus, etc. Add '-' for descending

# Create an instance (ID from search results)
vastai create instance 12345678 \
  --image pytorch/pytorch:2.1.0-cuda12.1-cudnn8-runtime \
  --disk 50 \
  --ssh \
  --direct \
  --onstart-cmd "echo 'ready'"

# List your running instances
vastai show instances
vastai show instances --raw  # JSON output for parsing

# Get SSH connection string
vastai ssh-url INSTANCE_ID
# Returns something like: ssh -p 12345 root@hostname.vast.ai

# View instance logs
vastai logs INSTANCE_ID

# Stop/start/reboot (without destroying)
vastai stop instance INSTANCE_ID
vastai start instance INSTANCE_ID
vastai reboot instance INSTANCE_ID

# Destroy instance (permanent - deletes all data)
vastai destroy instance INSTANCE_ID
```

### Typical Workflow Commands

```bash
# 1. Find a cheap 24GB GPU
vastai search offers 'reliability > 0.95 gpu_ram >= 24 dph < 0.50' --order 'dph' --limit 5

# 2. Create instance with PyTorch
vastai create instance OFFER_ID --image pytorch/pytorch --disk 50 --ssh --direct

# 3. Wait and check status
vastai show instances  # Look for status to become "running"

# 4. Get SSH command
SSH_CMD=$(vastai ssh-url INSTANCE_ID --raw | jq -r '.ssh_command')

# 5. Connect and run
$SSH_CMD "nvidia-smi"  # Verify GPU
rsync -avz --exclude '.git' ./ root@host:~/project/  # Sync code
$SSH_CMD "cd ~/project && python train.py"

# 6. Retrieve results
rsync -avz root@host:~/project/outputs/ ./outputs/

# 7. Cleanup
vastai destroy instance INSTANCE_ID
```

### Parsing JSON Output

Use `--raw` flag to get JSON output for programmatic use:

```bash
# Get instance details as JSON
vastai show instances --raw | jq '.[] | {id, status: .actual_status, cost: .dph_total}'

# Get SSH host and port
vastai ssh-url INSTANCE_ID --raw | jq -r '.ssh_host, .ssh_port'
```

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
