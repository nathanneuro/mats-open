#!/bin/bash
# Wait for a Vast.ai instance to become ready
# Usage: ./wait-for-instance.sh INSTANCE_ID [TIMEOUT_SECONDS]

set -e

INSTANCE_ID="$1"
TIMEOUT="${2:-300}"  # Default 5 minutes

if [ -z "$INSTANCE_ID" ]; then
    echo "Usage: $0 INSTANCE_ID [TIMEOUT_SECONDS]"
    exit 1
fi

echo "Waiting for instance $INSTANCE_ID to be ready (timeout: ${TIMEOUT}s)..."

START_TIME=$(date +%s)
POLL_INTERVAL=5

while true; do
    ELAPSED=$(($(date +%s) - START_TIME))

    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "ERROR: Timeout after ${TIMEOUT}s waiting for instance $INSTANCE_ID"
        exit 1
    fi

    # Get instance status
    STATUS=$(vastai show instances --raw 2>/dev/null | jq -r ".[] | select(.id == $INSTANCE_ID) | .actual_status" 2>/dev/null || echo "unknown")

    case "$STATUS" in
        "running")
            echo "Instance $INSTANCE_ID is running!"

            # Get SSH info
            SSH_INFO=$(vastai ssh-url "$INSTANCE_ID" --raw 2>/dev/null || echo "{}")
            SSH_HOST=$(echo "$SSH_INFO" | jq -r '.ssh_host // empty')
            SSH_PORT=$(echo "$SSH_INFO" | jq -r '.ssh_port // empty')

            if [ -n "$SSH_HOST" ] && [ -n "$SSH_PORT" ]; then
                echo "SSH: ssh -p $SSH_PORT root@$SSH_HOST"
            fi
            exit 0
            ;;
        "loading"|"starting"|"creating")
            echo "[${ELAPSED}s] Status: $STATUS - waiting..."
            ;;
        "exited"|"error"|"offline")
            echo "ERROR: Instance $INSTANCE_ID is in state: $STATUS"
            exit 1
            ;;
        "unknown"|"")
            echo "[${ELAPSED}s] Instance not found or status unknown - waiting..."
            ;;
        *)
            echo "[${ELAPSED}s] Status: $STATUS - waiting..."
            ;;
    esac

    sleep $POLL_INTERVAL
done
