#!/usr/bin/env bash
# Prints the most recent OTP the local stack issued.
#
# The local stack has no SMS provider, so nothing arrives on a real phone. The
# Auth Service runs a dev gateway that writes the message to
# docker/dev-sms/dev-sms.log (SMS_PROVIDER=file in docker-compose.core.yml).
# That file is a plain host file, so this works even when the container runtime's
# API is unavailable; container logs are only a fallback.
#
# Usage:
#   bash docker/otp.sh                 # latest code issued to anyone
#   bash docker/otp.sh +919876543210   # request a code for that number, then print it
set -uo pipefail

AUTH_URL="${AUTH_URL:-http://localhost:8081}"
AUTH_CONTAINER="${AUTH_CONTAINER:-homefix-core-auth-service-1}"
SMS_LOG="${SMS_LOG:-$(dirname "$0")/dev-sms/dev-sms.log}"

extract_code() { grep -o 'code is [0-9]\{4,10\}' | tail -1 | grep -o '[0-9]\{4,10\}'; }

# When a number is given, match that number's line: the file holds every code
# issued, so the newest line may belong to a different number.
filter_number() {
  if [ -n "${MOBILE:-}" ]; then grep -F "${MOBILE}"; else cat; fi
}

latest_otp() {
  if [ -s "${SMS_LOG}" ]; then
    local from_file
    from_file="$(filter_number < "${SMS_LOG}" | extract_code)"
    if [ -n "${from_file}" ]; then
      printf '%s' "${from_file}"
      return 0
    fi
  fi
  # Fallback for a stack still running the log-only gateway.
  docker logs "${AUTH_CONTAINER}" 2>&1 | extract_code
}

if [ $# -ge 1 ]; then
  MOBILE="$1"
  echo "==> Requesting an OTP for ${MOBILE}"
  status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"mobileNumber\":\"${MOBILE}\"}" "${AUTH_URL}/auth/register/otp")"
  case "${status}" in
    202|200) ;;
    429) echo "Rate limited: at most 5 OTP requests per hour per number. Try another number." >&2; exit 1 ;;
    400) echo "Rejected: the number must be E.164, e.g. +919876543210." >&2; exit 1 ;;
    *)   echo "Auth Service returned HTTP ${status}." >&2; exit 1 ;;
  esac
  sleep 1
fi

OTP="$(latest_otp)"
if [ -z "${OTP}" ]; then
  echo "No OTP found." >&2
  echo "Looked in: ${SMS_LOG}" >&2
  echo "If that file is missing, the stack predates SMS_PROVIDER=file — rebuild the" >&2
  echo "auth service: docker compose -f docker-compose.core.yml up -d --build auth-service" >&2
  exit 1
fi

echo "OTP: ${OTP}"
