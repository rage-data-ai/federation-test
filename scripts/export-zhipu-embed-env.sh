#!/usr/bin/env bash
# 从 fed_llm 导出智谱云端嵌入环境变量（stdout 可 source）。
# 用法：eval "$(sudo -E ./scripts/export-zhipu-embed-env.sh)"
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

read -r BASE KEY < <(mysql -N federal -e "SELECT base_url, api_key FROM fed_llm LIMIT 1")
if [[ -z "${KEY:-}" ]]; then
  echo "fed_llm 无可用 api_key" >&2
  exit 1
fi

# HttpTermEmbedder：endpoint 需已含 /embeddings，否则会误拼成 …/v4/v1/embeddings
if [[ "${BASE}" == *"/coding/paas/v4"* ]]; then
  ENDPOINT="https://open.bigmodel.cn/api/coding/paas/v4/embeddings"
else
  ENDPOINT="https://open.bigmodel.cn/api/paas/v4/embeddings"
fi

MODEL="${FEDERATION_ASK_EMBED_MODEL:-embedding-3}"

cat <<EOF
export MILVUS_URI="\${MILVUS_URI:-http://127.0.0.1:19530}"
export FEDERATION_ASK_EMBED_ENDPOINT="${ENDPOINT}"
export FEDERATION_ASK_EMBED_MODEL="${MODEL}"
export FEDERATION_ASK_EMBED_API_KEY="${KEY}"
EOF
