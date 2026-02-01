#!/bin/bash
# Sync local code to a Vast.ai instance, respecting .gitignore
# Usage: ./sync-code.sh INSTANCE_ID [LOCAL_PATH] [REMOTE_PATH]

set -e

INSTANCE_ID="$1"
LOCAL_PATH="${2:-.}"
REMOTE_PATH="${3:-/root/project}"

if [ -z "$INSTANCE_ID" ]; then
    echo "Usage: $0 INSTANCE_ID [LOCAL_PATH] [REMOTE_PATH]"
    exit 1
fi

# Get SSH connection info
SSH_INFO=$(vastai ssh-url "$INSTANCE_ID" --raw 2>/dev/null)
SSH_HOST=$(echo "$SSH_INFO" | jq -r '.ssh_host')
SSH_PORT=$(echo "$SSH_INFO" | jq -r '.ssh_port')

if [ -z "$SSH_HOST" ] || [ "$SSH_HOST" = "null" ]; then
    echo "ERROR: Could not get SSH info for instance $INSTANCE_ID"
    exit 1
fi

# SSH options
SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30"

# Build rsync exclude patterns
EXCLUDES=""

# Always exclude these
EXCLUDES="$EXCLUDES --exclude=.git/"
EXCLUDES="$EXCLUDES --exclude=__pycache__/"
EXCLUDES="$EXCLUDES --exclude=*.pyc"
EXCLUDES="$EXCLUDES --exclude=.env"
EXCLUDES="$EXCLUDES --exclude=.venv/"
EXCLUDES="$EXCLUDES --exclude=venv/"
EXCLUDES="$EXCLUDES --exclude=node_modules/"
EXCLUDES="$EXCLUDES --exclude=*.egg-info/"
EXCLUDES="$EXCLUDES --exclude=.pytest_cache/"
EXCLUDES="$EXCLUDES --exclude=.mypy_cache/"

# Use .gitignore if present
if [ -f "$LOCAL_PATH/.gitignore" ]; then
    EXCLUDES="$EXCLUDES --filter=':- .gitignore'"
fi

# Create remote directory
echo "Creating remote directory $REMOTE_PATH..."
ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "mkdir -p $REMOTE_PATH" 2>/dev/null || true

# Sync
echo "Syncing $LOCAL_PATH to root@$SSH_HOST:$REMOTE_PATH..."
rsync -avz --progress \
    -e "ssh $SSH_OPTS -p $SSH_PORT" \
    $EXCLUDES \
    "$LOCAL_PATH/" \
    "root@$SSH_HOST:$REMOTE_PATH/"

echo "Sync complete!"
echo "Remote path: $REMOTE_PATH"
