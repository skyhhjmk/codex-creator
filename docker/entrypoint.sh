#!/bin/sh

set -eu

CODEX_HOME="${CODEX_HOME:-${HOME:-/data/codex-creator}/.codex}"
export CODEX_HOME
export HOME="${HOME:-/data/codex-creator}"

mkdir -p "$CODEX_HOME"

if [ "${CODEX_APP_SERVER_ENABLED:-false}" = "true" ]; then
  if [ -n "${OPENAI_API_KEY:-}" ] && [ -n "${CODEX_ACCESS_TOKEN:-}" ]; then
    echo "OPENAI_API_KEY and CODEX_ACCESS_TOKEN cannot both be set." >&2
    exit 1
  fi

  if [ -n "${OPENAI_API_KEY:-}" ]; then
    if ! printf '%s\n' "$OPENAI_API_KEY" | codex login --with-api-key >/dev/null 2>&1; then
      echo "Codex API-key authentication failed." >&2
      exit 1
    fi
    unset OPENAI_API_KEY
  elif [ -n "${CODEX_ACCESS_TOKEN:-}" ]; then
    if ! printf '%s\n' "$CODEX_ACCESS_TOKEN" | codex login --with-access-token >/dev/null 2>&1; then
      echo "Codex access-token authentication failed." >&2
      exit 1
    fi
    unset CODEX_ACCESS_TOKEN
  fi

  if ! codex login status >/dev/null 2>&1; then
    echo "Codex app-server is enabled but no valid authentication is available." >&2
    exit 1
  fi
fi

exec "$@"
