# 天工 TianGong · API 接口设计

文档版本：v1.0（2026-10-18）。Base URL（经网关）：`http://localhost:8080`。服务亦可直连（验收排障用）：account:8081 / billing:8082 / task:8083。运行时以 Swagger UI 为准（`/swagger-ui.html`，springdoc 生成）。

## 1. 通用约定

### 1.1 统一响应体
```json
{ "code": 0, "message": "OK", "data": { }, "traceId": "8f3a..." }
```
- `code=0` 成功；非 0 见 1.3 错误码表。HTTP 状态码与业务码同时语义化（HTTP 供基础设施判断，业务码供客户端细分处理）。

### 1.2 认证方式
| 场景 | 方式 |
| --- | --- |
| 控制台用户 | `Authorization: Bearer <accessToken>`（JWT，30min；刷新用 refreshToken） |
| 开放 API | `X-App-Key` + `X-Timestamp`(ms) + `X-Nonce`(uuid) + `X-Signature`，其中 `Signature = HMAC_SHA256(appSecret, appKey + "\n" + timestamp + "\n" + nonce + "\n" + METHOD + "\n" + path + "\n" + sha256hex(body))`，时间窗 ±300s，nonce 5 分钟内不得重复 |
| 服务间 | `X-Internal-Token`（配置的共享密钥；生产替换为 mTLS/服务账号） |

### 1.3 错误码表（节选，完整见 tg-common `ErrorCode`）
| code | HTTP | 含义 |
| --- | --- | --- |
| 0 | 200 | 成功 |
| A0101 | 400 | 参数校验失败（含字段明细） |
| A0111 | 401 | 未认证 / AccessToken 无效或过期 |
| A0112 | 401 | RefreshToken 无效/已撤销 |
| A0121 | 403 | 无权访问该资源 |
| A0201 | 409 | 资源冲突（邮箱已注册等） |
| A0301 | 429 | 触发限流（含 Retry-After 头） |
| B0101 | 404 | 商品不存在 |
| B0201 | 409 | 订单状态不允许该操作（已支付/已关闭） |
| B0202 | 400 | 支付回调验签失败 |
| B0203 | 400 | 回调金额与订单不符 |
| B0301 | 409 | 积分不足（预扣失败） |
| B0302 | 409 | 账务乐观锁冲突（可重试） |
| C0101 | 404 | 任务不存在 |
| C0201 | 409 | 任务状态不允许该操作 |
| C0202 | 422 | 任务参数非法（prompt 超长/类型不支持） |
| D0101 | 503 | 下游服务不可用/熔断打开 |
| D9999 | 500 | 服务器内部错误 |

## 2. tg-account 接口

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | 无 | 注册 `{email, password, nickname}` |
| POST | `/api/v1/auth/login` | 无 | 登录，返回 accessToken/refreshToken/user |
| POST | `/api/v1/auth/refresh` | 无 | `{refreshToken}` 轮换新令牌对 |
| POST | `/api/v1/auth/logout` | JWT | `{refreshToken}` 撤销 |
| GET | `/api/v1/users/me` | JWT | 当前用户信息 |
| GET | `/api/v1/apps` | JWT | 应用列表（Secret 脱敏） |
| POST | `/api/v1/apps` | JWT | 创建应用，**响应含明文 secret（仅此一次）** |
| POST | `/api/v1/apps/{appKey}/reset-secret` | JWT | 重置密钥 |
| PATCH | `/api/v1/apps/{appKey}` | JWT | 修改名称/回调地址/限流额 |
| GET | `/internal/v1/apps/{appKey}` | Internal | 网关取应用信息（含解密 secret、限流额） |
| GET | `/internal/v1/users/{id}` | Internal | billing 校验用户存在且 ACTIVE |

## 3. tg-billing 接口

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/plans` | 无 | 在售商品列表 |
| GET | `/api/v1/credits/me` | JWT | 我的积分账户（balance/held）+ 最近流水 |
| GET | `/api/v1/ledgers/me` | JWT | 账务流水分页 |
| POST | `/api/v1/orders` | JWT | 创建订单 `{planCode}`，返回 orderNo/金额/支付时限 |
| GET | `/api/v1/orders/{orderNo}` | JWT | 订单状态 |
| POST | `/api/v1/payments/callback` | HMAC(渠道) | **支付回调**（可重放），发货幂等 |
| POST | `/internal/v1/credits/hold` | Internal | 预扣 `{userId, bizNo, credits}` |
| POST | `/internal/v1/credits/settle` | Internal | 结算 `{bizNo}`（HOLD→实扣） |
| POST | `/internal/v1/credits/refund` | Internal | 退还 `{bizNo}`（HOLD→释放） |
| GET | `/internal/v1/credits/holds/{bizNo}` | Internal | 预扣单状态（对账/巡检用） |

**支付回调请求/响应示例**
```json
// POST /api/v1/payments/callback
// Headers: X-Channel-Sign: hex(hmac_sha256(channelKey, paymentNo|orderNo|amountCents))
{ "paymentNo": "PAY20261018000001", "orderNo": "ORD20261018AB12", "amountCents": 9900, "channel": "MOCK", "paidAt": "2026-10-18T10:00:00Z" }
// → {"code":0,"message":"OK","data":{"orderStatus":"PAID","delivered":true}}
// 重放同一回调 → {"delivered": false}，订单/积分不变
```

## 4. tg-task 接口

| 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/tasks` | **AppKey 签名** | 提交任务（支持 `Idempotency-Key` 头） |
| GET | `/api/v1/tasks/{taskNo}` | AppKey 签名 或 JWT | 任务详情（属主校验） |
| GET | `/api/v1/tasks` | JWT | 我的任务分页 `?status=&page=&size=` |
| POST | `/api/v1/tasks/{taskNo}/cancel` | AppKey 签名 或 JWT | PENDING 可取消（自动退款） |
| GET | `/api/v1/tasks/{taskNo}/events` | JWT | 状态迁移审计 |
| POST | `/api/v1/webhooks/sink` | 无 | 测试用回调接收器（验收 Webhook 用，入库可查） |
| GET | `/api/v1/webhooks/sink/records` | JWT | 查看已收到的回调 |

**提交任务请求/响应示例**
```json
// POST /api/v1/tasks   (X-App-Key/X-Timestamp/X-Nonce/X-Signature, Idempotency-Key: my-key-1)
{ "type": "TEXT_TO_IMAGE", "prompt": "一只在火星上骑自行机的柴犬，电影感光效", "params": { "size": "1024x1024", "style": "cinematic" } }
// 202
{ "code": 0, "message": "OK",
  "data": { "taskNo": "TSK20261018XY76", "status": "PENDING", "costCredits": 10, "timeoutAt": "2026-10-18T10:10:00Z" } }
```
任务计价：`TEXT_TO_IMAGE=10`、`TEXT_TO_VIDEO=50`、`TTS=5` 积分（配置可调）。

## 5. tg-gateway 行为接口（非 REST，属网关契约）

| 能力 | 行为 |
| --- | --- |
| 白名单 | `/api/v1/auth/*`、`/api/v1/plans`、`/api/v1/payments/callback`、`/api/v1/webhooks/sink`、actuator health |
| 限流 | IP：默认 120 req/min；AppKey：应用配额（默认 120 req/min）。超限 `429 + Retry-After` |
| 熔断 | 下游连接失败/超时 → `D0101` 503，Body 带 `circuit=open` 提示 |
| Trace | 入口生成/透传 `X-Trace-Id`，响应回写该头 |

## 6. 分页与排序约定

`page` 从 0 开始，`size` 默认 10（max 100），返回体 `data.page/size/total/items`。
