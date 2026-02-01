#!/bin/bash
# Download a Hugging Face model/dataset to a Vast.ai instance
# Usage: ./hf-download-remote.sh INSTANCE_ID REPO_ID [OPTIONS]
#
# Examples:
#   ./hf-download-remote.sh 12345 meta-llama/Llama-2-7b-hf
#   ./hf-download-remote.sh 12345 bigcode/the-stack --repo-type dataset
#   ./hf-download-remote.sh 12345 stabilityai/sdxl --local-dir /root/models/sdxl

set -e

INSTANCE_ID="$1"
REPO_ID="$2"
shift 2
EXTRA_ARGS="$*"

if [ -z "$INSTANCE_ID" ] || [ -z "$REPO_ID" ]; then
    echo "Usage: $0 INSTANCE_ID REPO_ID [hf download options...]"
    echo ""
    echo "Examples:"
    echo "  $0 12345 meta-llama/Llama-2-7b-hf"
    echo "  $0 12345 bigcode/the-stack --repo-type dataset"
    echo "  $0 12345 stabilityai/sdxl --local-dir /root/models/sdxl"
    exit 1
fi

# Get HF token from environment or local file
if [ -n "$HF_TOKEN" ]; then
    TOKEN="$HF_TOKEN"
elif [ -f ~/.cache/huggingface/token ]; then
    TOKEN=$(cat ~/.cache/huggingface/token)
elif [ -f "$HF_HOME/token" ]; then
    TOKEN=$(cat "$HF_HOME/token")
else
    echo "ERROR: No Hugging Face token found."
    echo ""
    echo "Please either:"
    echo "  1. Set HF_TOKEN environment variable"
    echo "  2. Run 'hf auth login' locally"
    echo ""
    echo "Get your token at: https://huggingface.co/settings/tokens"
    exit 1
fi

# Verify token looks valid
if [[ ! "$TOKEN" =~ ^hf_ ]]; then
    echo "WARNING: Token doesn't start with 'hf_' - may be invalid"
fi

# Get SSH connection info
echo "Getting SSH info for instance $INSTANCE_ID..."
SSH_INFO=$(vastai ssh-url "$INSTANCE_ID" --raw 2>/dev/null)
SSH_HOST=$(echo "$SSH_INFO" | jq -r '.ssh_host')
SSH_PORT=$(echo "$SSH_INFO" | jq -r '.ssh_port')

if [ -z "$SSH_HOST" ] || [ "$SSH_HOST" = "null" ]; then
    echo "ERROR: Could not get SSH info for instance $INSTANCE_ID"
    echo "Is the instance running? Check: vastai show instances"
    exit 1
fi

# SSH options
SSH_OPTS="-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=30"

# Check if huggingface_hub is installed on remote
echo "Checking remote environment..."
if ! ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "which hf || pip show huggingface_hub" >/dev/null 2>&1; then
    echo "Installing huggingface_hub on remote instance..."
    ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "pip install -q huggingface_hub"
fi

# Check disk space
echo "Checking disk space on remote..."
ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "df -h / | tail -1"

# Run the download
echo ""
echo "Downloading $REPO_ID to instance $INSTANCE_ID..."
echo "Command: HF_TOKEN=*** hf download $REPO_ID $EXTRA_ARGS"
echo ""

ssh $SSH_OPTS -p "$SSH_PORT" "root@$SSH_HOST" "HF_TOKEN=$TOKEN hf download $REPO_ID $EXTRA_ARGS"

echo ""
echo "Download complete!"
