#!/usr/bin/env bash
# 本地对齐 CI 的全量门禁（ubuntu-24.04 / JDK 21 / Node 22）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> mvn verify"
mvn -B verify

echo "==> web lint / typecheck / test / build / e2e"
cd "$ROOT/web"
npm ci
npm run lint
npm run typecheck
npm test
npm run build
npx playwright install --with-deps chromium
npm run test:e2e

echo "OK: all gates passed"
