#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""熔断演示（docs/06 场景 V3b）：停 billing → 提交任务被快速失败(D0101/503) → 恢复 → 自动闭合。
前置：dev-up.sh start 全栈已运行。用法：python scripts/circuit_demo.py
"""
import hashlib
import hmac
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

BASE = "http://localhost:8080"
PASS = FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    print(f"  {'✔' if cond else '✘'} {name}" + (f"  [{detail}]" if detail else ""))
    PASS, FAIL = PASS + (1 if cond else 0), FAIL + (0 if cond else 1)


def req(method, url, body=None, headers=None, timeout=10):
    data = body.encode() if isinstance(body, str) else body
    r = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except Exception:
            return e.code, {}


def sign_headers(app_key, secret, method, path, body: bytes):
    ts = str(int(time.time() * 1000))
    nonce = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    canonical = "\n".join([app_key, ts, nonce, method.upper(), path,
                           hashlib.sha256(body).hexdigest()])
    sig = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {"X-App-Key": app_key, "X-Timestamp": ts, "X-Nonce": nonce, "X-Signature": sig}


def main():
    email = "cb_%s@t.cn" % "".join(random.choices(string.ascii_lowercase, k=8))
    st, body = req("POST", f"{BASE}/api/v1/auth/register",
                   json.dumps({"email": email, "password": "p@ssw0rd123", "nickname": "cb"}))
    auth = {"Authorization": "Bearer " + body["data"]["tokens"]["accessToken"]}
    st, body = req("POST", f"{BASE}/api/v1/apps", json.dumps({"name": "cb-app"}), auth)
    app_key, app_secret = body["data"]["appKey"], body["data"]["appSecret"]

    # 充值积分（直接复用下单+回调）
    st, body = req("POST", f"{BASE}/api/v1/orders", json.dumps({"planCode": "credits_500"}), auth)
    st, body = req("POST", f"{BASE}/api/v1/payments/mock-pay",
                   json.dumps({"orderNo": body["data"]["orderNo"]}), auth)
    req("POST", f"{BASE}/api/v1/payments/callback", json.dumps(body["data"]["callbackBody"]),
        {"X-Channel-Sign": body["data"]["signature"]})

    task_body = json.dumps({"type": "TTS", "prompt": "熔断演示"}, ensure_ascii=False)

    def submit():
        h = sign_headers(app_key, app_secret, "POST", "/api/v1/tasks", task_body.encode())
        h["Idempotency-Key"] = "cb-" + "".join(random.choices(string.digits, k=10))
        return req("POST", f"{BASE}/api/v1/tasks", task_body, h)

    print("== 前置：billing 正常，提交一次成功 ==")
    st, body = submit()
    check("正常提交 200", st == 200, f"http={st}")
    time.sleep(2)

    print("== 请先执行: ./scripts/dev-up.sh stop billing ，然后按回车继续 ==")
    input()
    t0 = time.time()
    results = [submit() for _ in range(5)]
    fast_fail = all(s in (503,) and b.get("code") == 0xD0101 for s, b in results)
    elapsed = time.time() - t0
    check("billing 宕机 → 5 次提交全部 503 D0101（快速失败）", fast_fail,
          f"5次耗时 {elapsed:.1f}s code={[b.get('code') for _, b in results][:1]}")
    check("失败耗时 < 3s（熔断生效，不悬挂等待）", elapsed < 3.0, f"{elapsed:.1f}s")

    print("== 请先执行: ./scripts/dev-up.sh start billing ，等待 healthy 后按回车 ==")
    input()
    ok = False
    detail = ""
    for i in range(15):   # 熔断 20s 后半开 → 闭合
        st, body = submit()
        detail = f"http={st}"
        if st == 200:
            ok = True
            break
        time.sleep(3)
    check("billing 恢复 → 熔断自动闭合，提交恢复 200", ok, detail)

    print(f"\nPASS: {PASS}  FAIL: {FAIL}")
    sys.exit(1 if FAIL else 0)


if __name__ == "__main__":
    main()
