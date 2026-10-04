#!/usr/bin/env bash
#
# Creates every ArchForge topic declared in topics.conf.
#
# Deliberately dependency-free (no jq / python / yq) so it runs inside the
# apache/kafka image unmodified. The topic catalogue is line-based rather than
# JSON precisely so that parsing here cannot silently produce zero topics.
#
# Idempotent: existing topics are left untouched.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVER:-kafka:9092}"
SPEC="${TOPIC_SPEC:-/app/topics.conf}"
KAFKA_HOME="${KAFKA_HOME:-/opt/kafka}"
BIN="${KAFKA_HOME}/bin"

log() { echo "[kafka-init] $*"; }
die() { echo "[kafka-init] FATAL: $*" >&2; exit 1; }

[ -r "${SPEC}" ] || die "topic spec not readable: ${SPEC}"

log "waiting for broker at ${BOOTSTRAP}"
broker_up=0
for attempt in $(seq 1 60); do
  if "${BIN}/kafka-broker-api-versions.sh" --bootstrap-server "${BOOTSTRAP}" >/dev/null 2>&1; then
    log "broker reachable after ${attempt} attempt(s)"
    broker_up=1
    break
  fi
  sleep 2
done
[ "${broker_up}" -eq 1 ] || die "broker never became reachable at ${BOOTSTRAP}"

log "applying topic catalogue ${SPEC}"

existing="$("${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --list 2>/dev/null </dev/null || true)"

# Slurp the catalogue up front. The kafka CLI wrappers inherit stdin, so
# redirecting the loop from the spec file would let them swallow it.
spec_lines=()
while IFS= read -r l || [ -n "${l}" ]; do spec_lines+=("${l}"); done < "${SPEC}"

created=0
reused=0
failed=0
declared=0

for rawline in "${spec_lines[@]}"; do
  line="${rawline%%#*}"                 # strip comments
  line="$(echo "${line}" | tr -d '\r' | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [ -z "${line}" ] && continue

  declared=$((declared + 1))

  IFS='|' read -r name partitions replication configs <<< "${line}"
  name="$(echo "${name}"         | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  partitions="$(echo "${partitions}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  replication="$(echo "${replication}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  configs="$(echo "${configs:-}"  | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"

  [ -n "${name}" ] || { log "skipping malformed line: ${rawline}"; failed=$((failed + 1)); continue; }
  partitions="${partitions:-1}"
  replication="${replication:-1}"

  if echo "${existing}" | grep -qx "${name}"; then
    log "exists  ${name} (partitions=${partitions})"
    reused=$((reused + 1))
    continue
  fi

  args=(--bootstrap-server "${BOOTSTRAP}" --create
        --topic "${name}"
        --partitions "${partitions}"
        --replication-factor "${replication}")

  if [ -n "${configs}" ]; then
    IFS=',' read -ra pairs <<< "${configs}"
    for pair in "${pairs[@]}"; do
      pair="$(echo "${pair}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
      [ -n "${pair}" ] && args+=(--config "${pair}")
    done
  fi

  if "${BIN}/kafka-topics.sh" "${args[@]}" >/dev/null 2>&1 </dev/null; then
    log "created ${name} (partitions=${partitions}, rf=${replication}${configs:+, ${configs}})"
    created=$((created + 1))
    existing="${existing}
${name}"
  else
    log "FAILED to create ${name}"
    failed=$((failed + 1))
  fi
done

log "summary: declared=${declared} created=${created} reused=${reused} failed=${failed}"

[ "${failed}" -eq 0 ] || die "${failed} topic(s) could not be created"
[ "${declared}" -gt 0 ] || die "topic catalogue was empty — refusing to report success"

# Final verification straight from the broker.
actual_list="$("${BIN}/kafka-topics.sh" --bootstrap-server "${BOOTSTRAP}" --list 2>/dev/null </dev/null || true)"
missing=0
for rawline in "${spec_lines[@]}"; do
  name="$(echo "${rawline%%#*}" | tr -d '\r' | cut -d'|' -f1 | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
  [ -z "${name}" ] && continue
  echo "${actual_list}" | grep -qx "${name}" || { log "VERIFY FAILED: ${name} absent from broker"; missing=$((missing + 1)); }
done

[ "${missing}" -eq 0 ] || die "${missing} declared topic(s) missing after create"
log "all declared topics present on the broker"
