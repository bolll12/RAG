#!/bin/sh
set -eu
rag_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export OLLAMA_MODELS="$rag_root/data/ollama-models"
export OLLAMA_HOST=127.0.0.1:11434
exec "$rag_root/.tools/ollama/ollama" "$@"
