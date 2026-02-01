#!/bin/bash
# SSH to a Vast.ai instance with retry logic
# Usage: ./ssh-with-retry.sh INSTANCE_ID [COMMAND]

set -e

INSTANCE_ID="$1"
shift
COMMAND="$*"

if [ -z "$INSTANCE_ID" ]; then
    echo "Usage: $0 INSTANCE_ID [COMMAND]"
    exit 1
fi

MAX_RETRIES=5
RETRY_DELAYS=(2 4 8 16 32)

# Get SSH connection info
get_ssh_info() {
    SSH_INFO=$(vastai ssh-url "$INSTANCE_ID" --raw 2>/dev/null)
    SSH_HOST=$(echo "$SSH_INFO" | jq -r '.ssh_host')
    SSH_PORT=$(echo "$SSH_INFO" | jq -r '.ssh_port')

    if [ -z "$SSH_HOST" ] || [ "$SSH_HOST" = "null" ]; then
        return 1
    fi
    return 0
}

# SSH options for reliability
SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=10 -o ServerAliveInterval=30 -o ServerAliveCountMax=3"

echo "Getting SSH info for instance $INSTANCE_ID..."

for ((i=0; i<MAX_RETRIES; i++)); do
    if get_ssh_info; then
        break
    fi

    if [ $i -lt $((MAX_RETRIES-1)) ]; then
        DELAY=${RETRY_DELAYS[$i]}
        echo "Failed to get SSH info, retrying in ${DELAY}s..."
        sleep $DELAY
    else
        echo "ERROR: Could not get SSH info after $MAX_RETRIES attempts"
        exit 1
    fi
done

echo "Connecting to root@$SSH_HOST:$SSH_PORT..."

for ((i=0; i<MAX_RETRIES; i++)); do
    if [ -n "$COMMAND" ]; then
        # Run command
        if ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "$COMMAND"; then
            exit 0
        fi
    else
        # Interactive session
        if ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST"; then
            exit 0
        fi
    fi

    EXIT_CODE=$?

    if [ $i -lt $((MAX_RETRIES-1)) ]; then
        DELAY=${RETRY_DELAYS[$i]}
        echo "SSH failed (exit $EXIT_CODE), retrying in ${DELAY}s..."
        sleep $DELAY
    else
        echo "ERROR: SSH failed after $MAX_RETRIES attempts"
        exit $EXIT_CODE
    fi
done
