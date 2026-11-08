#!/usr/bin/env python3
"""挑一个"上行大包能通"的百炼接入 IP，写进本地 hosts 文件。

为什么需要这个脚本
------------------
现象：调用百炼时偶发"请求发出后 ~19 秒被 Connection reset"，页面表现为
回答降级成【抽取式回答】。重试有时成功有时失败，看上去像随机故障。

实测（同一时刻、同一份 4KB 请求体、逐个 IP 直连）：

    IP                轻量 GET /models      带 4KB body 的 POST
    8.140.217.18      401  0.11s            401  2.6s        ← 通
    39.96.198.249     401  0.14s            超时 12s+ 后被重置
    8.152.159.24      401  0.13s            超时 12s+ 后被重置
    39.96.213.166     401  0.12s            超时 12s+ 后被重置

也就是说：TCP 连得上、TLS 握手正常、小请求正常，只有"上行请求体超过约 1.2KB"
的请求丢包并最终被重置——典型的 PMTU 黑洞（路径 MTU 变小，但 ICMP
"fragmentation needed" 被中间设备吞掉，大包就静默消失）。

dashscope.aliyuncs.com 有 4 个 A 记录轮询，等于每次请求在抽签：抽到坏 IP 就卡
19 秒然后失败。这解释了"同一段代码时好时坏"。

本脚本的做法：逐个 IP 发一个 3KB 左右的无鉴权 POST（服务端会立刻回 401，
不需要 API key），谁能快速回应就选谁，写入 ./hosts；应用启动时用
    -Djdk.net.hosts.file=<项目>/hosts
让 JVM 只认这个 IP（见 restart.sh）。注意 `-Djdk.net.hosts.file` 只影响 JVM，
浏览器/curl 不受影响 —— 不想动系统 hosts 表时这是最省事的办法。

用法：
    python tools/pin_dashscope_ip.py                # 探测并写入 ./hosts
    python tools/pin_dashscope_ip.py --check        # 只探测，不写文件
"""

import argparse
import json
import socket
import ssl
import sys
import time

DOMAIN = "dashscope.aliyuncs.com"
PROBE_PATH = "/compatible-mode/v1/chat/completions"
TIMEOUT = 8.0
# 约 3KB：要明显超过约 1.2KB 的临界值，才能把"大包不通"的 IP 筛掉
PROBE_BODY = json.dumps(
    {
        "model": "qwen-plus",
        "messages": [{"role": "user", "content": "施工安全规范" * 120}],
        "max_tokens": 8,
    },
    ensure_ascii=False,
)


def resolve_ips(domain):
    """取该域名全部 IPv4 地址（去重保序）。"""
    ips = []
    for info in socket.getaddrinfo(domain, 443, socket.AF_INET, socket.SOCK_STREAM):
        ip = info[4][0]
        if ip not in ips:
            ips.append(ip)
    return ips


def probe(ip, timeout=TIMEOUT):
    """向指定 IP 发一个带 body 的 POST，返回 (是否可用, 耗时秒, 说明)。

    注意：必须连 IP、但 SNI 仍用域名（server_hostname=DOMAIN）。
    直接用 HTTPSConnection(ip) 会把 SNI 设成 IP，服务端会直接断开连接，
    于是所有 IP 都"不可用"，探测就失真了。
    """
    ctx = ssl._create_unverified_context()  # 按 IP 连接，证书名对不上，这里不校验
    body = PROBE_BODY.encode()
    request = (
        "POST %s HTTP/1.1\r\n"
        "Host: %s\r\n"
        "Content-Type: application/json\r\n"
        "Content-Length: %d\r\n"
        "Connection: close\r\n\r\n" % (PROBE_PATH, DOMAIN, len(body))
    ).encode() + body

    started = time.time()
    try:
        with socket.create_connection((ip, 443), timeout=timeout) as raw:
            with ctx.wrap_socket(raw, server_hostname=DOMAIN) as tls:
                tls.sendall(request)
                first = tls.recv(64)
        elapsed = time.time() - started
        status_line = first.split(b"\r\n", 1)[0].decode("latin-1", "replace").strip()
        # 没有 API key 时服务端回 401/403 也算"链路通"
        return bool(status_line), elapsed, status_line or "无响应"
    except Exception as exc:  # noqa: BLE001 - 探测失败原因只需展示
        return False, time.time() - started, type(exc).__name__


def main():
    parser = argparse.ArgumentParser(description="挑选可用的百炼接入 IP")
    parser.add_argument("--domain", default=DOMAIN)
    parser.add_argument("--out", default="hosts", help="输出文件（默认项目根目录 hosts）")
    parser.add_argument("--check", action="store_true", help="只探测，不写文件")
    parser.add_argument("--timeout", type=float, default=TIMEOUT)
    args = parser.parse_args()

    ips = resolve_ips(args.domain)
    if not ips:
        print("解析不到 %s 的 A 记录" % args.domain)
        return 1

    print("%s 解析到 %d 个 IP，逐个探测（带约 %dKB 请求体）："
          % (args.domain, len(ips), len(PROBE_BODY.encode()) // 1024))
    good = []
    for ip in ips:
        ok, cost, detail = probe(ip, args.timeout)
        print("  %-16s %s  %.2fs  %s" % (ip, "可用" if ok else "不可用", cost, detail))
        if ok:
            good.append((ip, cost))

    if not good:
        print("\n全部 IP 都不通：说明不是 IP 选路问题，先检查网络/代理/API 域名。")
        return 2

    good.sort(key=lambda x: x[1])
    best = good[0][0]
    print("\n选定：%s（可用 %d/%d）" % (best, len(good), len(ips)))

    if args.check:
        return 0

    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 由 tools/pin_dashscope_ip.py 生成：把百炼固定到上行大包能通的 IP\n")
        f.write("%s %s\n" % (best, args.domain))
    print("已写入 %s：启动时加 -Djdk.net.hosts.file=<项目>/%s 即可生效" % (args.out, args.out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
