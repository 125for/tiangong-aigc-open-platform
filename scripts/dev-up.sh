#!/usr/bin/env bash
# 天工 TianGong · 本地一键拉起（local profile：H2 文件库 + 进程内 MQ/限流，零外部依赖）
# 用法：scripts/dev-up.sh [start|stop|restart|status] [gateway|account|billing|task|all]
set -u
cd "$(dirname "$0")/.."

SERVICES="account billing task gateway"
PORTS_gateway=8080 PORTS_account=8081 PORTS_billing=8082 PORTS_task=8083
mkdir -p logs data run

action="${1:-start}"
target="${2:-all}"

pid_of() { cat "run/$1.pid" 2>/dev/null; }

is_up() {
  local pid; pid="$(pid_of "$1")"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start_one() {
  local svc="$1"
  if is_up "$svc"; then echo "[$svc] already running (pid $(pid_of "$svc"))"; return; fi
  local port="PORTS_$svc"; port="${!port}"
  echo "[$svc] starting on :$port ..."
  nohup java -jar "tg-$svc/target/tg-$svc-1.0.0.jar" \
    --server.port="$port" > "logs/$svc.log" 2>&1 &
  echo $! > "run/$svc.pid"
}

stop_one() {
  local svc="$1" pid
  pid="$(pid_of "$svc")"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "[$svc] stopping (pid $pid, graceful)"
    kill "$pid"
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
  else
    echo "[$svc] not running"
  fi
  rm -f "run/$svc.pid"
}

status_one() {
  if is_up "$1"; then
    local port="PORTS_$1"; port="${!port}"
    local health; health=$(curl -s -o /dev/null -w '%{http_code}' "localhost:$port/actuator/health" 2>/dev/null)
    echo "[$1] RUNNING pid=$(pid_of "$1") health=$health"
  else
    echo "[$1] STOPPED"
  fi
}

wait_healthy() {
  local svc="$1" port="PORTS_$1"; port="${!port}"
  for _ in $(seq 1 60); do
    local code; code=$(curl -s -o /dev/null -w '%{http_code}' "localhost:$port/actuator/health" 2>/dev/null)
    [ "$code" = "200" ] && { echo "[$svc] healthy"; return 0; }
    sleep 1
  done
  echo "[$svc] HEALTH TIMEOUT —— 查看日志 logs/$svc.log"
  return 1
}

list() {
  if [ "$target" = "all" ]; then echo "$SERVICES"; else echo "$target"; fi
}

case "$action" in
  start)
    for s in $(list); do start_one "$s"; done
    echo "--- waiting health checks ---"
    rc=0
    for s in $(list); do wait_healthy "$s" || rc=1; done
    [ $rc -eq 0 ] && echo "ALL SERVICES UP. gateway → http://localhost:8080"
    exit $rc
    ;;
  stop)
    for s in $(list); do stop_one "$s"; done
    ;;
  restart)
    for s in $(list); do stop_one "$s"; done
    for s in $(list); do start_one "$s"; done
    for s in $(list); do wait_healthy "$s" >/dev/null || true; done
    echo "RESTARTED"
    ;;
  status)
    for s in $(list); do status_one "$s"; done
    ;;
  *)
    echo "usage: $0 [start|stop|restart|status] [gateway|account|billing|task|all]"
    exit 1
    ;;
esac
