#!/bin/bash
# Sync results from a Vast.ai instance back to local machine
# Usage: ./sync-results.sh INSTANCE_ID [REMOTE_PATH] [LOCAL_PATH]

set -e

INSTANCE_ID="$1"
REMOTE_PATH="${2:-/root/project/outputs}"
LOCAL_PATH="${3:-./outputs}"

if [ -z "$INSTANCE_ID" ]; then
    echo "Usage: $0 INSTANCE_ID [REMOTE_PATH] [LOCAL_PATH]"
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

# Create local directory
mkdir -p "$LOCAL_PATH"

# Check if remote path exists
echo "Checking remote path $REMOTE_PATH..."
if ! ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "test -e $REMOTE_PATH" 2>/dev/null; then
    echo "WARNING: Remote path $REMOTE_PATH does not exist"
    exit 0
fi

# Sync
echo "Syncing root@$SSH_HOST:$REMOTE_PATH to $LOCAL_PATH..."
rsync -avz --progress \
    -e "ssh $SSH_OPTS -p $SSH_PORT" \
    "root@$SSH_HOST:$REMOTE_PATH/" \
    "$LOCAL_PATH/"

echo "Sync complete!"
echo "Results saved to: $LOCAL_PATH"
