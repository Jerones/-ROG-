#!/usr/bin/env bash
# 一键：停旧服务 → 打包 → 启动 → 等就绪
#
# 为什么需要它：Windows 下 jar 被运行中的进程占用时，maven 的 repackage 会失败，
# 并且会把 fat jar 覆盖成一个只有 100 多 KB 的薄 jar，正在运行的服务随即崩溃。
# 所以改完代码必须先停服再打包，这个脚本把顺序固定下来。
#
# 用法：
#   bash restart.sh              # 默认端口 18080
#   PORT=18081 bash restart.sh   # 换端口
#   SKIP_BUILD=1 bash restart.sh # 只重启不打包
#   NO_TEST=0 bash restart.sh    # 打包时跑全量测试（默认跳过）
#   PIN_IP=1 bash restart.sh     # 先探测并固定百炼接入 IP 再启动
#   CHROMA_HOST=http://192.168.1.20 bash restart.sh   # 换 Chroma 地址（默认 localhost:8000）
#   JAVA_OPTS="-Xmx1g" bash restart.sh
set -uo pipefail

PORT="${PORT:-18080}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR" || exit 1

JAR="target/safety-rag-qa-1.0.0.jar"
LOG=".run.log"
NO_TEST="${NO_TEST:-1}"
HOSTS_FILE="$PROJECT_DIR/hosts"

# Chroma 地址：环境变量优先，其次本地文件 .chroma-host（不提交，写本机常用地址），
# 最后回落到 application.yml 的默认值 http://localhost:8000。
# 向量库是必要依赖，地址不对就是启动失败，这里统一在脚本里决定，免得到处加参数。
if [ -z "${CHROMA_HOST:-}" ] && [ -f "$PROJECT_DIR/.chroma-host" ]; then
    CHROMA_HOST="$(head -1 "$PROJECT_DIR/.chroma-host" | tr -d '\r[:space:]')"
fi
CHROMA_HOST="${CHROMA_HOST:-http://localhost}"
export CHROMA_HOST

say() { printf '\033[36m[restart]\033[0m %s\n' "$*"; }
die() { printf '\033[31m[restart] %s\033[0m\n' "$*" >&2; exit 1; }

# 监听该端口的进程号（Windows 的 PID，跨平台统一从端口反查）
# 不用 $!：Git Bash 给的是 MSYS 虚拟 pid，taskkill 和 kill -0 都认不了。
port_pid() {
    if command -v lsof >/dev/null 2>&1; then
        lsof -ti tcp:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1
        return
    fi
    netstat -ano 2>/dev/null \
        | grep -E "[:.]${PORT}[[:space:]]" | grep -i LISTENING \
        | awk '{print $NF}' | head -1
}

kill_pid() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) taskkill //PID "$1" //F >/dev/null 2>&1 ;;
        *) kill -9 "$1" >/dev/null 2>&1 ;;
    esac
}

# ---------- 1. 停旧服务 ----------
stop_server() {
    local pid
    pid="$(port_pid)"
    if [ -z "$pid" ]; then
        say "端口 $PORT 空闲，无需停止"
        return 0
    fi
    say "停止旧服务（pid=$pid）"
    kill_pid "$pid"
    for _ in $(seq 1 20); do
        [ -z "$(port_pid)" ] && { say "端口已释放"; return 0; }
        sleep 0.5
    done
    die "端口 $PORT 一直被占用，停止失败，请手动检查"
}

# ---------- 2. 打包 ----------
build() {
    local args=(-DskipTests package)
    [ "$NO_TEST" = "0" ] && args=(package)
    say "开始打包（mvn ${args[*]}）"
    # 先离线试：本地仓库齐全时十来秒；新增依赖时自动回落到在线
    if ! mvn -o -q "${args[@]}"; then
        say "离线构建失败，改走在线仓库重试"
        mvn -q "${args[@]}" || die "打包失败，未启动"
    fi
    [ -f "$JAR" ] || die "没有产出 $JAR"
    local size
    size=$(wc -c < "$JAR" | tr -d ' ')
    # 薄 jar 只有一百多 KB，说明 repackage 没成功（多半是旧进程还占着 jar）
    if [ "$size" -lt 1000000 ]; then
        die "jar 只有 $((size / 1024)) KB，repackage 未成功（旧进程可能还占用 jar），重跑一次"
    fi
    say "打包完成：$JAR（$((size / 1024 / 1024)) MB）"
}

# ---------- 3. 启动并等就绪 ----------
start() {
    local extra=()
    if [ -n "${JAVA_OPTS:-}" ]; then
        # shellcheck disable=SC2206
        extra+=(${JAVA_OPTS})
    fi
    # 百炼有 4 个 A 记录，本机网络对其中的 IP 存在 PMTU 黑洞（上行大包被丢，
    # 19 秒后连接被重置 → 回答降级成抽取式）。hosts 由 tools/pin_dashscope_ip.py
    # 探测生成，这里让 JVM 只认那个能通大包的 IP；只影响本进程，不动系统 hosts。
    if [ -f "$HOSTS_FILE" ]; then
        extra+=("-Djdk.net.hosts.file=$HOSTS_FILE")
        say "固定百炼接入 IP：$(grep -v '^#' "$HOSTS_FILE" | head -1)"
    else
        say "未找到 hosts（可选）：如需固定百炼 IP，先跑 python tools/pin_dashscope_ip.py"
    fi

    say "启动服务：http://localhost:$PORT/（向量库 Chroma：$CHROMA_HOST）"
    nohup java "${extra[@]}" -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &

    local waited=0
    for _ in $(seq 1 60); do
        if curl -sf --noproxy '*' -o /dev/null "http://localhost:$PORT/api/kb/stats" 2>/dev/null; then
            say "就绪（pid=$(port_pid)，耗时约 ${waited} 秒）：http://localhost:$PORT/"
            curl -s --noproxy '*' "http://localhost:$PORT/api/kb/stats"
            echo
            return 0
        fi
        # 起来一会儿进程又没了 → 别干等，直接报错
        if [ "$waited" -ge 10 ] && [ -z "$(port_pid)" ]; then
            echo "--- $LOG 末尾 ---"
            tail -30 "$LOG"
            die "进程已退出，启动失败（完整日志见 $LOG）"
        fi
        sleep 2
        waited=$((waited + 2))
    done
    echo "--- $LOG 末尾 ---"
    tail -30 "$LOG"
    die "等了 ${waited} 秒仍未就绪，看 $LOG"
}

stop_server
[ "${SKIP_BUILD:-0}" = "1" ] || build
# PIN_IP=1：重新探测一次百炼接入 IP（换网络环境、或又出现"19 秒后重置"时用）
if [ "${PIN_IP:-0}" = "1" ]; then
    say "重新探测百炼接入 IP"
    python tools/pin_dashscope_ip.py || say "探测失败，沿用现有 hosts"
fi
start
