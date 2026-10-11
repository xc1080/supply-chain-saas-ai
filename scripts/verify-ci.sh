#!/usr/bin/env bash
# Linux/Jenkins entry. Starts only a uniquely named disposable Compose project.
set -euo pipefail
verification_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$verification_root"
verification_venv=""
for verification_key in $(compgen -e); do
    case "$verification_key" in
        FUSION_*|COMMERCE_*|SPRING_*|AI_BAILIAN_*|DEEPSEEK_*|OPENAI_*|LANGSMITH_*|LANGCHAIN_*|UVICORN_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS|CLASSPATH|PYTHONPATH|PYTHONSTARTUP) unset "$verification_key" ;;
    esac
done
mkdir -p logs/engineering-ci
command -v java >/dev/null
command -v mvn >/dev/null
command -v python3 >/dev/null
command -v npm >/dev/null
command -v mysql >/dev/null
docker compose version >/dev/null
python3 -c 'import sys; assert sys.version_info >= (3, 12), "Python 3.12 or newer is required"'
node -e 'if (Number(process.versions.node.split(".")[0]) < 24) throw Error("Node 24 or newer is required")'

verification_project="engineering-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:12])')"
export VERIFY_MYSQL_PASSWORD="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
export VERIFY_MYSQL_PORT=0 VERIFY_REDIS_PORT=0
verification_compose=(docker compose -p "$verification_project" -f scripts/compose.engineering.yml)
verification_cleanup() {
    local verification_exit=$?
    trap - EXIT
    "${verification_compose[@]}" logs --no-color > logs/engineering-ci/infrastructure.log 2>&1 || true
    if ! "${verification_compose[@]}" down --volumes --remove-orphans >> logs/engineering-ci/infrastructure.log 2>&1; then
        verification_exit=1
    fi
    if [[ -n "$verification_venv" ]]; then
        if [[ -d "$verification_venv" && ! -L "$verification_venv" &&
              "$(basename -- "$verification_venv")" == engineering-venv-* &&
              "$(cd "$verification_venv" && pwd -P)" == "$verification_venv" &&
              -f "$verification_venv/.verification-owner" &&
              "$(cat "$verification_venv/.verification-owner")" == "$verification_project" ]]; then
            rm -rf -- "$verification_venv"
        else
            echo 'Refused cleanup of a directory without this run ownership marker' >&2
            verification_exit=1
        fi
    fi
    exit "$verification_exit"
}
trap verification_cleanup EXIT
"${verification_compose[@]}" up -d --wait > logs/engineering-ci/infrastructure-start.log 2>&1
verification_mysql_port="$("${verification_compose[@]}" port mysql 3306 | awk -F: '{print $NF}')"
verification_redis_port="$("${verification_compose[@]}" port redis 6379 | awk -F: '{print $NF}')"

verification_venv="$(mktemp -d -t engineering-venv-XXXXXXXXXXXX)"
verification_venv="$(cd "$verification_venv" && pwd -P)"
printf '%s' "$verification_project" > "$verification_venv/.verification-owner"
python3 -m venv "$verification_venv"
verification_python="$verification_venv/bin/python"
"$verification_python" -m pip install -r fusion-agent/requirements-lock.txt -r fusion-agent/requirements.txt > logs/engineering-ci/python-install.log 2>&1
(
    cd ks-inventory-system
    mvn -B -ntp verify -Dmaven.test.skip=false -DskipTests=false
) > logs/engineering-ci/java-tests.log 2>&1
export FUSION_REDIS_URL="redis://127.0.0.1:$verification_redis_port/8"
# Scrub provider, tracing, Spring and JVM overrides, preserving our Redis.
"$verification_python" -c 'import os,runpy; from scripts.verify_engineering import clean_environment; endpoint=os.environ["FUSION_REDIS_URL"]; safe=clean_environment(); safe["FUSION_REDIS_URL"]=endpoint; os.environ.clear(); os.environ.update(safe); runpy.run_path("fusion-agent/run_tests.py",run_name="__main__")' > logs/engineering-ci/python-tests.log 2>&1
"$verification_python" -B -m unittest discover -s scripts -p 'test_*.py' -v > logs/engineering-ci/verification-guards.log 2>&1
(
    cd ks-inventory-system/ks-vue3
    npm ci
    VITE_APP_TITLE='铠思进销存系统' VITE_APP_ENV=production VITE_APP_BASE_API=/prod-api VITE_BUILD_COMPRESS=gzip npm run build:prod
) > logs/engineering-ci/admin-build.log 2>&1
(
    cd Simlect-AI-Mall/Simlect-front/Simlect-web
    npm ci
    npm run test:sku
    node --test scripts/refund-policy.test.mjs
    npm run build:demo
) > logs/engineering-ci/customer-build.log 2>&1
"$verification_python" scripts/verify_engineering.py \
    --mysql-port "$verification_mysql_port" \
    --redis-url "redis://127.0.0.1:$verification_redis_port/0" \
    --jar ks-inventory-system/ruoyi-admin/target/ruoyi-admin.jar \
    --output "logs/engineering-ci/mysql-$verification_project"
