#!/usr/bin/env bash
# No containers or paid APIs: test generated secrets and merged runtime configuration.
set -Eeuo pipefail
set +x
umask 077
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
cd "$ROOT"
bash infra/ci/gen-env.sh --out "$work/env" >/dev/null
python3 - "$work" <<'PY'
import pathlib,sys
p=pathlib.Path(sys.argv[1])/'env'
def env(name): return dict(x.split('=',1) for x in (p/name).read_text().splitlines())
assert env('core-api.env')['NEO4J_AUTH']==env('neo4j.env')['NEO4J_AUTH']
assert env('neo4j.env')['NEO4J_AUTH'].startswith('neo4j/')
assert (p/'neo4j.env').stat().st_mode & 0o777 == 0o600
PY
pg_before="$(sha256sum "$work/env/postgres.env")"
# Upgrade a pre-graph nine-file environment without regenerating PostgreSQL passwords.
sed -i 's/^NEO4J_AUTH=.*/NEO4J_AUTH=/' "$work/env/core-api.env"
unlink "$work/env/neo4j.env"
bash infra/ci/gen-env.sh --out "$work/env" --keep-existing >/dev/null
[ "$pg_before" = "$(sha256sum "$work/env/postgres.env")" ]
export MUJOONG_VERIFY_DIR="$work" MUJOONG_LIVE_ENV_FILE="$work/live.env"
export MUJOONG_LIVE_PROXY_REGEX='172\.30\.40\.10'
: >"$work/live.env"
dc=(docker compose --env-file "$work/env/compose.env" -f infra/compose.demo.yml)
"${dc[@]}" config --format json >"$work/demo.json"
"${dc[@]}" -f infra/compose.ci.yml config --format json >"$work/ci.json"
"${dc[@]}" -f infra/compose.ci.yml -f infra/compose.live.yml config --format json >"$work/live.json"
"${dc[@]}" -f infra/compose.ci.yml --profile knowledge config --format json >"$work/graph-ci.json"
docker compose --env-file "$work/env/compose.env" -f infra/compose.dev.yml config --format json >"$work/dev.json"
python3 - "$work" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1])
def services(name): return json.loads((p/(name+'.json')).read_text())['services']
demo,ci,live,dev=map(services,['demo','ci','live','dev'])
assert 'neo4j' in demo and not demo['neo4j'].get('profiles')
assert demo['core-api']['depends_on']['neo4j']['condition']=='service_healthy'
assert demo['neo4j']['environment']['NEO4J_AUTH']==demo['core-api']['environment']['NEO4J_AUTH']
assert demo['neo4j']['read_only']
assert any('256m' in x and 'exec' in x for x in demo['neo4j']['tmpfs'])
assert any(v['target']=='/var/lib/neo4j/conf' for v in demo['neo4j']['volumes'])
assert ci['core-api']['environment']['MUJOONG_KNOWLEDGE_NEO4J_ENABLED']=='false'
assert 'neo4j' not in ci['core-api']['depends_on']
assert 'neo4j' not in ci
assert 'neo4j' in services('graph-ci')
assert live['core-api']['environment']['MUJOONG_KNOWLEDGE_NEO4J_ENABLED']=='true'
assert 'neo4j' in live and not live['neo4j'].get('profiles')
assert live['core-api']['depends_on']['neo4j']['condition']=='service_healthy'
assert live['core-api']['environment']['NEO4J_AUTH']==live['neo4j']['environment']['NEO4J_AUTH']
assert 'neo4j' in dev
assert all(v.get('host_ip')=='127.0.0.1' for v in dev['neo4j']['ports'])
assert 'NEO4J_AUTH' not in demo['web'].get('environment',{})
assert 'NEO4J_AUTH' not in demo['realtime'].get('environment',{})
PY
printf 'NEO4J_AUTH=neo4j/different-test-value\n' >"$work/env/neo4j.env"
if bash infra/ci/gen-env.sh --out "$work/env" --keep-existing >"$work/mismatch.log" 2>&1; then
  echo 'FAIL: mismatched credentials were overwritten' >&2
  exit 1
fi
[ "$pg_before" = "$(sha256sum "$work/env/postgres.env")" ]
echo 'PASS: graph default, CI opt-out, live/dev startup, secret isolation, legacy env upgrade, mismatch refusal'
