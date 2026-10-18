#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
天工 TianGong · 端到端冒烟验收脚本（对应 docs/06 §3 场景 V1/V2/V3）

用法:
  python scripts/smoke_test.py                    # 全流程主链路 + 幂等重放 + 防篡改/防重放
  python scripts/smoke_test.py --expect-failed    # 推理全失败模式(需 TG_ENGINE_FAIL_RATE=1.0 重启服务)
  python scripts/smoke_test.py --expect-timeout   # 超时模式(需小 TTL + 大延迟重启服务)
  python scripts/smoke_test.py --skip-task        # 仅账号+计费链路
  python scripts/smoke_test.py --base http://localhost:8080
"""
import argparse
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
TASK_DIRECT = "http://localhost:8083"   # webhook 回调直连任务服务（模拟外部回调地址）

PASS, FAIL = 0, 0
FAILURES = []


def check(name, cond, detail=""):
    global PASS, FAIL
    mark = "✔" if cond else "✘"
    print(f"  {mark} {name}" + (f"  [{detail}]" if detail else ""))
    if cond:
        PASS += 1
    else:
        FAIL += 1
        FAILURES.append(name)


def req(method, url, body=None, headers=None, timeout=15):
    data = body.encode("utf-8") if isinstance(body, str) else body
    r = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}"), dict(resp.headers)
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode("utf-8") or "{}")
        except Exception:
            payload = {}
        return e.code, payload, dict(e.headers)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def hmac_sha256_hex(secret: str, data: str) -> str:
    return hmac.new(secret.encode("utf-8"), data.encode("utf-8"), hashlib.sha256).hexdigest()


def sign_headers(app_key, secret, method, path, body: bytes):
    ts = str(int(time.time() * 1000))
    nonce = "".join(random.choices(string.ascii_letters + string.digits, k=16))
    canonical = app_key + "\n" + ts + "\n" + nonce + "\n" + method.upper() + "\n" + path + "\n" + sha256_hex(body)
    return {
        "X-App-Key": app_key,
        "X-Timestamp": ts,
        "X-Nonce": nonce,
        "X-Signature": hmac_sha256_hex(secret, canonical),
    }


def main():
    global BASE, TASK_DIRECT
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=BASE)
    ap.add_argument("--task-direct", default=TASK_DIRECT)
    ap.add_argument("--skip-task", action="store_true")
    ap.add_argument("--expect-failed", action="store_true")
    ap.add_argument("--expect-timeout", action="store_true")
    args = ap.parse_args()
    BASE, TASK_DIRECT = args.base, args.task_direct
    expect_status = "FAILED" if args.expect_failed else ("TIMEOUT" if args.expect_timeout else "SUCCEEDED")

    email = "smoke_%s@t.cn" % "".join(random.choices(string.ascii_lowercase + string.digits, k=10))
    password = "p@ssw0rd123"

    print("== 1. 注册与登录 ==")
    st, body, _ = req("POST", f"{BASE}/api/v1/auth/register",
                      json.dumps({"email": email, "password": password, "nickname": "smoke"}))
    check("注册即登录 200", st == 200, f"code={body.get('code')}")
    tokens = body["data"]["tokens"]
    access = tokens["accessToken"]
    auth = {"Authorization": "Bearer " + access}

    st, me, _ = req("GET", f"{BASE}/api/v1/users/me", None, auth)
    check("GET /users/me", st == 200 and me["data"]["email"] == email)
    uid = me["data"]["userId"]

    print("== 2. 创建开发者应用 ==")
    st, body, _ = req("POST", f"{BASE}/api/v1/apps", json.dumps({"name": "smoke-app"}), auth)
    check("创建应用 200", st == 200 and body["data"]["appSecret"])
    app_key = body["data"]["appKey"]
    app_secret = body["data"]["appSecret"]

    sink = f"{TASK_DIRECT}/api/v1/webhooks/sink"
    st, body, _ = req("PATCH", f"{BASE}/api/v1/apps/{app_key}",
                      json.dumps({"callbackUrl": sink}), auth)
    check("配置回调地址(测试 Sink)", st == 200 and body["data"]["callbackUrl"] == sink)

    print("== 3. 下单与支付回调（含重放幂等） ==")
    st, body, _ = req("GET", f"{BASE}/api/v1/plans")
    plans = {p["code"]: p for p in body["data"]}
    check("套餐列表含 starter_pro/credits_500", "starter_pro" in plans and "credits_500" in plans)

    st, body, _ = req("POST", f"{BASE}/api/v1/orders", json.dumps({"planCode": "starter_pro"}), auth)
    check("创建订单 200", st == 200 and body["data"]["orderNo"])
    order_no = body["data"]["orderNo"]
    grant_credits = plans["starter_pro"]["monthlyCredits"]

    st, body, _ = req("POST", f"{BASE}/api/v1/payments/mock-pay",
                      json.dumps({"orderNo": order_no}), auth)
    check("渠道模拟器铸造签名通知", st == 200 and body["data"]["signature"])
    cb_body = json.dumps(body["data"]["callbackBody"])
    cb_sign = body["data"]["signature"]

    st, body, _ = req("POST", f"{BASE}/api/v1/payments/callback", cb_body,
                      {"X-Channel-Sign": cb_sign})
    check("支付回调首次 → delivered=true", st == 200 and body["data"]["delivered"] is True)

    st, body, _ = req("GET", f"{BASE}/api/v1/credits/me", None, auth)
    check(f"积分到账 {grant_credits}", body["data"]["balance"] == grant_credits,
          f"balance={body['data']['balance']}")

    delivered_ok = True
    for _ in range(3):
        st, body, _ = req("POST", f"{BASE}/api/v1/payments/callback", cb_body,
                          {"X-Channel-Sign": cb_sign})
        delivered_ok = delivered_ok and st == 200 and body["data"]["delivered"] is False
    check("回调重放 3 次 → delivered=false（幂等）", delivered_ok)

    st, body, _ = req("GET", f"{BASE}/api/v1/credits/me", None, auth)
    check("重放后积分不变", body["data"]["balance"] == grant_credits)

    st, body, _ = req("POST", f"{BASE}/api/v1/payments/callback", cb_body, {"X-Channel-Sign": "bad"})
    check("错误签名回调 → 400 B0202", st == 400 and body.get("code") == 0xB0202, f"http={st}")

    if args.skip_task:
        summary()
        return

    print("== 4. 签名提交 AIGC 任务 ==")
    task_body = json.dumps({"type": "TEXT_TO_IMAGE",
                            "prompt": "一只在火星骑自行车的柴犬，电影感光效",
                            "params": {"size": "1024x1024"}}, ensure_ascii=False)
    idem = "smoke-" + "".join(random.choices(string.ascii_lowercase + string.digits, k=12))
    headers = sign_headers(app_key, app_secret, "POST", "/api/v1/tasks", task_body.encode("utf-8"))
    headers["Idempotency-Key"] = idem
    st, body, _ = req("POST", f"{BASE}/api/v1/tasks", task_body, headers)
    check("签名提交任务 200", st == 200 and body["data"]["taskNo"], f"http={st} code={body.get('code')}")
    task_no = body["data"]["taskNo"]
    cost = body["data"]["costCredits"]

    headers2 = sign_headers(app_key, app_secret, "POST", "/api/v1/tasks", task_body.encode("utf-8"))
    headers2["Idempotency-Key"] = idem
    st, body2, _ = req("POST", f"{BASE}/api/v1/tasks", task_body, headers2)
    check("同幂等键重放 → 同一 taskNo", st == 200 and body2["data"]["taskNo"] == task_no)

    bad = dict(sign_headers(app_key, "wrong-secret", "POST", "/api/v1/tasks", task_body.encode("utf-8")))
    st, body3, _ = req("POST", f"{BASE}/api/v1/tasks", task_body, bad)
    check("错误 AppSecret 签名 → 401", st == 401, f"http={st}")

    print(f"== 5. 轮询任务终态（期望 {expect_status}） ==")
    final = None
    for _ in range(120):
        st, body4, _ = req("GET", f"{BASE}/api/v1/tasks/{task_no}", None, auth)
        if st == 200 and body4["data"]["status"] in ("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELED"):
            final = body4["data"]
            break
        time.sleep(1)
    check(f"任务到达 {expect_status}", final is not None and final["status"] == expect_status,
          f"final={final['status'] if final else 'N/A'}")

    print("== 6. 账务与通知核销 ==")
    st, body5, _ = req("GET", f"{BASE}/api/v1/credits/me", None, auth)
    bal, held = body5["data"]["balance"], body5["data"]["held"]
    if expect_status == "SUCCEEDED":
        check(f"余额扣减 {cost} 且冻结归零", bal == grant_credits - cost and held == 0,
              f"balance={bal} held={held}")
        check("resultUrl 已生成", bool(final and final.get("resultUrl")))
    else:
        check(f"退款后余额复原（held=0）", bal == grant_credits and held == 0,
              f"balance={bal} held={held}")

    st, body6, _ = req("GET", f"{BASE}/api/v1/ledgers/me", None, auth)
    types = [l["changeType"] for l in body6["data"]["items"]]
    check("流水含 GRANT/HOLD",
          "GRANT" in types and "HOLD" in types, f"types={types}")
    if expect_status == "SUCCEEDED":
        check("流水含 SETTLE", "SETTLE" in types)
    else:
        check("流水含 REFUND", "REFUND" in types)

    hit = False
    for _ in range(15):   # Webhook 中继每 3s 投递一轮，最多等 15s
        st, body7, _ = req("GET", f"{BASE}/api/v1/webhooks/sink/records", None, auth)
        hit = st == 200 and any(task_no in (r.get("payload") or "") for r in body7["data"])
        if hit:
            break
        time.sleep(1)
    check("Webhook 已送达测试 Sink", hit)

    print("== 7. 越权与鉴权负例 ==")
    other_email = "evil_%s@t.cn" % "".join(random.choices(string.ascii_lowercase, k=8))
    req("POST", f"{BASE}/api/v1/auth/register",
        json.dumps({"email": other_email, "password": password, "nickname": "evil"}))
    st, body8, _ = req("POST", f"{BASE}/api/v1/auth/login",
                       json.dumps({"email": other_email, "password": password}))
    evil = {"Authorization": "Bearer " + body8["data"]["tokens"]["accessToken"]}
    st, _, _ = req("GET", f"{BASE}/api/v1/tasks/{task_no}", None, evil)
    check("他人读取任务 → 403", st == 403, f"http={st}")
    st, _, _ = req("GET", f"{BASE}/api/v1/users/me")
    check("无令牌访问受保护接口 → 401", st == 401, f"http={st}")

    summary()


def summary():
    print("\n================ 冒烟结果 ================")
    print(f"  PASS: {PASS}   FAIL: {FAIL}")
    if FAILURES:
        print("  失败项: " + ", ".join(FAILURES))
        print("  结论: FAIL")
        sys.exit(1)
    print("  结论: PASS ✅")
    sys.exit(0)


if __name__ == "__main__":
    main()
