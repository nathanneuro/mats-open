# vasticlaude Development Roadmap

## Phase 1: Core Infrastructure

- [ ] Vast.ai API wrapper
  - [ ] Authentication (API key management)
  - [ ] Instance search/filtering
  - [ ] Instance create/destroy
  - [ ] Instance status polling
- [ ] SSH utilities
  - [ ] Connection with retry logic
  - [ ] Command execution
  - [ ] File transfer (rsync wrapper)
- [ ] State management
  - [ ] Instance tracking
  - [ ] Experiment state
  - [ ] Cost tracking

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
