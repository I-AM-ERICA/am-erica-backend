#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f .env.policy-mvp && -z "${AI_INTERNAL_KEY:-}" ]]; then
  echo 'Place the server-provided client credentials in .env.policy-mvp, or set AI_INTERNAL_KEY.'
  exit 1
fi
export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local,policy-mvp,supabase}"
# application-policy-mvp.yml imports model credentials; .env retains DB/login/SMTP settings.
export APP_ENV_IMPORT="${APP_ENV_IMPORT:-optional:file:.env[.properties]}"
exec bash ./gradlew bootRun
