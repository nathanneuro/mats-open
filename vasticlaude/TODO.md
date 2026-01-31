# vasticlaude Development Roadmap

## Phase 1: Core Infrastructure

Uses the official `vastai` CLI (`pip install vastai`) rather than building a custom wrapper.

- [ ] Helper scripts wrapping vastai CLI
  - [ ] `scripts/wait-for-instance.sh` - Poll until instance is running
  - [ ] `scripts/ssh-with-retry.sh` - SSH with exponential backoff
  - [ ] `scripts/sync-code.sh` - rsync with .gitignore awareness
  - [ ] `scripts/sync-results.sh` - Pull artifacts back
- [ ] State management
  - [ ] Instance tracking (JSON files)
  - [ ] Experiment state (per-experiment dirs)
  - [ ] Cost tracking (parse from vastai output)

## Phase 2: Experiment Lifecycle

- [ ] Environment setup
  - [ ] Docker support
  - [ ] Conda/pip environment creation
  - [ ] Dependency installation
- [ ] Code sync
  - [ ] Gitignore-aware rsync
  - [ ] Incremental sync
- [ ] Experiment runner
  - [ ] Process management
  - [ ] Output capture
  - [ ] Exit code handling
- [ ] Checkpoint management
  - [ ] Periodic sync back
  - [ ] Resume from checkpoint

## Phase 3: Reliability

- [ ] Failure detection
  - [ ] Instance health monitoring
  - [ ] Process crash detection
  - [ ] Disk/memory alerts
- [ ] Automatic recovery
  - [ ] Instance respawn
  - [ ] Checkpoint resume
  - [ ] Alternative instance selection
- [ ] Graceful degradation
  - [ ] Partial result retrieval
  - [ ] State preservation

## Phase 4: Developer Experience

- [ ] CLI tools
  - [ ] `vasticlaude status` - Show running experiments
  - [ ] `vasticlaude logs` - Tail experiment logs
  - [ ] `vasticlaude stop` - Graceful shutdown
  - [ ] `vasticlaude destroy` - Force cleanup
- [ ] Project templates
- [ ] Documentation
- [ ] Examples with popular frameworks (PyTorch, JAX, etc.)

## Future Ideas

- [ ] Multi-instance experiments (distributed training)
- [ ] Data staging (pre-upload datasets)
- [ ] Result caching (don't re-run identical experiments)
- [ ] Cost optimization (instance type recommendations)
- [ ] Integration with experiment trackers (W&B, MLflow)
