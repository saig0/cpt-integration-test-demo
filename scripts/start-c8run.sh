#!/usr/bin/env bash
# Starts a local Camunda 8 Run with c8ctl, prepared for CPT remote runtime tests:
#   * the cluster clock is controllable (needed by processTestContext.increaseTime(...))
#   * the management API (9600) is reachable (CPT uses it to delete the data between the tests)
#
# Usage: scripts/start-c8run.sh [version]   (default: camunda.version of pom.xml, to match the CPT version)
set -euo pipefail

cd "$(dirname "$0")/.."

VERSION="${1:-$(sed -n 's#.*<camunda.version>\(.*\)</camunda.version>.*#\1#p' pom.xml | head -1)}"

# Clock control is process-scoped: it must be set on every start.
# C8 Run 8.9, 8.10 and 8.11 accept both spellings (verified). The documented one for 8.9/8.10 is
# zeebe.clock.controlled, for newer versions camunda.system.clock-controlled. Set both to be safe.
export ZEEBE_CLOCK_CONTROLLED=true
export CAMUNDA_SYSTEM_CLOCKCONTROLLED=true

echo "Starting Camunda 8 Run ${VERSION} (clock controllable)"
c8ctl cluster start "${VERSION}"

# Verify the clock control with a mutating request: GET /actuator/clock returns 200 in any case,
# DELETE returns 200 if the clock is controllable, and 403 ("clock is immutable") if not.
status=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE http://127.0.0.1:9600/actuator/clock || true)
if [ "${status}" = "200" ]; then
  echo "OK: clock is controllable"
else
  echo "WARNING: clock is not controllable (HTTP ${status}). Tests using increaseTime(...) will fail." >&2
  exit 1
fi
