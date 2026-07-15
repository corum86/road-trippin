#!/usr/bin/env bash
# Keeps Claude Code auth/session state alive across devcontainer rebuilds.
# ~/.claude is backed by a named volume (see devcontainer.json "mounts"), but a
# fresh volume mount can land owned by root, and ~/.claude.json is a single file
# outside that directory, so both need fixing up on every container start.
set -euo pipefail

CLAUDE_DIR="$HOME/.claude"
CLAUDE_JSON="$HOME/.claude.json"
PERSISTED_JSON="$CLAUDE_DIR/.claude.json"

mkdir -p "$CLAUDE_DIR"
sudo chown -R "$(id -u):$(id -g)" "$CLAUDE_DIR"

if [ -f "$CLAUDE_JSON" ] && [ ! -L "$CLAUDE_JSON" ]; then
    if [ -f "$PERSISTED_JSON" ]; then
        rm -f "$CLAUDE_JSON"
    else
        mv "$CLAUDE_JSON" "$PERSISTED_JSON"
    fi
fi

touch "$PERSISTED_JSON"
ln -sf "$PERSISTED_JSON" "$CLAUDE_JSON"
