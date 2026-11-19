#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
if [[ -z "${SPRING_PROFILES_ACTIVE:-}" ]]; then
  if [[ -f .env.policy-mvp ]]; then
    export SPRING_PROFILES_ACTIVE=local,policy-mvp,supabase,chronos-local
  else
    export SPRING_PROFILES_ACTIVE=local,chronos-local
  fi
elif [[ ",$SPRING_PROFILES_ACTIVE," != *,chronos-local,* ]]; then
  export SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE,chronos-local"
fi
exec bash ./gradlew bootRun
