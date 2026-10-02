# Sourced by the seed and check scripts: how to reach the Postgres and Kafka the local stack
# runs against. Locally those are the ones installed on this machine (see docs/LOCAL_ACCESS.md),
# not containers, so there is nothing to `docker exec` into.
#
# Every setting can be overridden from the environment, e.g. for the containerised fallback in
# docker-compose.infra.yml:
#   PGPORT=5533 KAFKA_BOOTSTRAP=kafka:9092 KAFKA_CLIENT_NETWORK=homefix-core_default bash docker/...

_LOCAL_INFRA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Host psql. The Windows installer does not put it on PATH, so fall back to the newest install.
if [ -z "${PSQL:-}" ]; then
  PSQL="$(command -v psql 2>/dev/null || true)"
  if [ -z "${PSQL}" ]; then
    PSQL="$(ls -d /c/Program\ Files/PostgreSQL/*/bin/psql.exe 2>/dev/null | sort -V | tail -1)"
  fi
fi
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-homefix}"
export PGDATABASE="${PGDATABASE:-homefix}"
if [ -z "${PGPASSWORD:-}" ] && [ -f "${_LOCAL_INFRA_ROOT}/.env" ]; then
  PGPASSWORD="$(grep -m1 '^DB_PASSWORD=' "${_LOCAL_INFRA_ROOT}/.env" | cut -d= -f2- | tr -d '\r')"
  export PGPASSWORD
fi

# psql_q <sql>  → unaligned, tuples-only output with Windows line endings stripped.
psql_q() {
  if [ -z "${PSQL}" ]; then
    echo "psql not found; install PostgreSQL client tools or set PSQL" >&2
    return 127
  fi
  "${PSQL}" -qAt -v ON_ERROR_STOP=1 -c "$1" | tr -d '\r'
}

# Kafka CLI tools run in a throwaway container against the host broker's container-facing
# listener, so the scripts work the same whether or not the Kafka distribution's own (.bat)
# tools are on this machine. host-gateway makes host.docker.internal resolve on Linux too.
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-host.docker.internal:9094}"
KAFKA_CLIENT_IMAGE="${KAFKA_CLIENT_IMAGE:-apache/kafka:4.1.1}"
KAFKA_CLIENT_NETWORK="${KAFKA_CLIENT_NETWORK:-}"

# kafka_tool <script> [args...]  e.g. kafka_tool kafka-topics.sh --list
kafka_tool() {
  local script="$1"; shift
  MSYS_NO_PATHCONV=1 docker run --rm --add-host=host.docker.internal:host-gateway \
    ${KAFKA_CLIENT_NETWORK:+--network "${KAFKA_CLIENT_NETWORK}"} \
    "${KAFKA_CLIENT_IMAGE}" "/opt/kafka/bin/${script}" --bootstrap-server "${KAFKA_BOOTSTRAP}" "$@"
}
