#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Chroma 服务端自检脚本（只依赖标准库，不需要装 chromadb）。

用途：在把本应用切到 chroma profile 之前，先确认这台机器上的 Chroma
能不能扛住本项目的数据量。本项目约 586 块 × 1024 维。

为什么要自检：Windows + pip 版 Chroma 的底层 hnswlib 原生库在部分
Python 版本下不可靠，会在写入到一定条数时**段错误退出**，客户端只能
看到 Connection reset，排查时很容易误判成代码问题。

用法：
    python tools/chroma_selfcheck.py                 # 默认 600 条
    python tools/chroma_selfcheck.py --n 100         # 测 100 条
    python tools/chroma_selfcheck.py --ghost         # 追加"删除不存在的 id"测试
    python tools/chroma_selfcheck.py --url http://localhost:8000

退出码：0 = 通过；1 = 未通过（看最后的中文诊断）
"""

import argparse
import json
import sys
import urllib.error
import urllib.request
import uuid

TENANT = "SpringAiTenant"
DATABASE = "SpringAiDatabase"
DIM = 1024
BATCH = 10


def api_base(url):
    return url.rstrip("/") + "/api/v2"


def call(method, url, body=None, timeout=30):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method,
                                headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace").strip()
            if not raw:
                return resp.status, None
            try:
                return resp.status, json.loads(raw)
            except ValueError:
                return resp.status, raw  # /version 返回的是裸字符串
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(raw) if raw.strip() else None
        except ValueError:
            parsed = raw[:300]
        return e.code, parsed


def post(url, body, timeout=30):
    """POST 且容忍"已存在"类失败（补齐 tenant/database 用的幂等调用）。"""
    try:
        return call("POST", url, body, timeout=timeout)
    except Exception:
        return -1, None


def fake_vector(i):
    """确定性的伪向量，避免每次跑都不同；归一化后模拟真实 embedding。"""
    seed = (i * 2654435761) % (2 ** 32)
    vec = []
    for d in range(DIM):
        seed = (1103515245 * seed + 12345) % (2 ** 31)
        vec.append((seed / (2 ** 31) - 0.5) * 2.0)
    norm = sum(v * v for v in vec) ** 0.5 or 1.0
    return [v / norm for v in vec]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:8000")
    ap.add_argument("--n", type=int, default=600)
    ap.add_argument("--collection", default="selfcheck-" + uuid.uuid4().hex[:6])
    ap.add_argument("--ghost", action="store_true",
                    help="追加测试删除不存在的 id（这一步会让有缺陷的 Chroma 直接崩溃）")
    args = ap.parse_args()

    base = api_base(args.url)
    print("=" * 62)
    print("Chroma 自检：目标 %s，写入 %d 条 × %d 维" % (args.url, args.n, DIM))
    print("=" * 62)

    # 1) 心跳
    try:
        status, _ = call("GET", base + "/heartbeat", timeout=5)
        _, version = call("GET", base + "/version", timeout=5)
        print("[1/4] 心跳 OK（HTTP %s），服务端版本：%s" % (status, version))
    except Exception as e:
        print("[1/4] 连不上 Chroma：%s" % e)
        print("\n结论：Chroma 没在跑。先启动服务再测：")
        print("  chroma run --path ./data/chroma-server --host localhost --port 8000")
        return 1

    # 2) 建集合（tenant / database 按需补齐，全部幂等）
    post(base + "/tenants", {"name": TENANT})
    post("%s/tenants/%s/databases" % (base, TENANT), {"name": DATABASE})
    col_url = "%s/tenants/%s/databases/%s/collections" % (base, TENANT, DATABASE)
    status, body = post(col_url, {"name": args.collection,
                                  "metadata": {"hnsw:space": "cosine"},
                                  "get_or_create": True})
    col_id = body.get("id") if isinstance(body, dict) else None
    if not col_id:
        # 有些版本对已存在的集合返回 409，回读一次即可
        _, body = call("GET", "%s/%s" % (col_url, args.collection))
        col_id = body.get("id") if isinstance(body, dict) else None
    if not col_id:
        print("[2/4] 建集合失败：HTTP %s %s" % (status, str(body)[:300]))
        return 1
    print("[2/4] 集合就绪：%s（id=%s）" % (args.collection, col_id[:8]))

    # 3) 分批写入（模拟真实入库：每批 10 条，与应用一致）
    up_url = "%s/%s/upsert" % (col_url, col_id)
    written = 0
    try:
        for start in range(0, args.n, BATCH):
            stop = min(start + BATCH, args.n)
            ids = ["sc-%05d" % i for i in range(start, stop)]
            body = {
                "ids": ids,
                "embeddings": [fake_vector(i) for i in range(start, stop)],
                "documents": ["第%d条 安全自检样本文本。" % i for i in range(start, stop)],
                "metadatas": [{"docName": "selfcheck.txt", "chunkIndex": i}
                              for i in range(start, stop)],
            }
            status, resp = call("POST", up_url, body, timeout=60)
            if status not in (200, 201):
                print("[3/4] 第 %d~%d 条写入失败：HTTP %s %s"
                      % (start, stop - 1, status, str(resp)[:200]))
                return 1
            written = stop
            if written % 100 == 0 or written == args.n:
                print("       已写入 %d/%d 条" % (written, args.n))
    except Exception as e:
        print("[3/4] 写到第 %d 条时连接断开：%s" % (written, e))
        print("\n结论：Chroma 服务端在写入过程中崩溃了（不是应用代码问题）。")
        print("处理：换 WSL2 / Docker 里的 Chroma，或先用默认的 SimpleVectorStore。")
        return 1

    # 4) 回读校验
    count_url = "%s/%s/count" % (col_url, col_id)
    status, cnt = call("GET", count_url)
    print("[4/4] 写入完成，集合计数：%s" % cnt)

    # 5) 可选：删除不存在的 id（已知会让有缺陷的 hnswlib 段错误）
    if args.ghost:
        print("       追加测试：删除一个不存在的 id …")
        try:
            del_url = "%s/%s/delete" % (col_url, col_id)
            status, resp = call("POST", del_url, {"ids": ["ghost-does-not-exist"]})
            print("       HTTP %s %s" % (status, str(resp)[:120]))
        except Exception as e:
            print("       ✗ 服务端已崩溃：%s" % e)
            print("\n结论：该 Chroma 不能删不存在的 id（本项目已加过滤绕过，但仍建议换版本）。")
            return 1

    print("\n结论：通过。这台机器上的 Chroma 能承载本项目数据量。")
    print("接下来：mvn spring-boot:run -Dspring-boot.run.profiles=chroma")
    print("（自检集合 %s 可留可删，不影响应用）" % args.collection)
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    sys.exit(main())
