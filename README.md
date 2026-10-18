# 天工 TianGong · AIGC 创作者开放平台后端

「AIGC 创作者开放平台」的业务形态：**面向开发者开放 AIGC API，按调用积分计费**。生产级工程实践：全链路幂等、积分预扣—结算—退款、本地消息表、熔断限流防重放、完整文档与可执行验收。

## 架构

```
开发者/控制台 ──JWT──┐
业务应用 ──AppKey签名──┴─▶ tg-gateway:8080 ──▶ tg-account:8081 (账号/JWT/应用密钥)
                                        ├──▶ tg-billing:8082 (套餐/订单/支付回调/积分账务)
                                        └──▶ tg-task:8083   (AIGC任务状态机/Outbox/Webhook)
        gateway ── 熔断(Resilience4j) ──▶ account；task ── 熔断 ──▶ billing(预扣/结算/退款)
        docker profile: MySQL(库分离) + Redis(分布式限流/nonce) + RabbitMQ(任务派发) + Prometheus
```

核心容错机制（详见 `docs/05-容错与高可用设计.md`）：支付回调幂等发货、任务提交幂等回放、积分预扣跨服务最终一致、Outbox 可靠投递、失败/超时自动退款、HMAC 签名防篡改防重放、令牌桶限流、熔断快速失败。

## 快速开始

### local 模式（零外部依赖，验收推荐）
```bash
mvn -DskipTests package
scripts/dev-up.sh start                # 拉起 gateway/account/billing/task（H2 文件库 + 进程内 MQ）
python scripts/smoke_test.py           # 端到端冒烟：23 项断言（全流程/幂等/防重放/越权）
```

### 故障注入验收（docs/06 场景 V3）
```bash
TG_ENGINE_FAIL_RATE=1.0 scripts/dev-up.sh restart task   # 推理全失败
python scripts/smoke_test.py --expect-failed             # → FAILED + 自动全额退款

TG_TASK_TTL_SECONDS=2 TG_ENGINE_LATENCY_MS=8000 scripts/dev-up.sh restart task
python scripts/smoke_test.py --expect-timeout            # → TIMEOUT + 退款

scripts/dev-up.sh stop billing && python scripts/circuit_demo.py   # 熔断演示（交互式）
```

### docker 模式（类生产）
```bash
mvn -DskipTests package
cd docker && docker compose up -d --build
# Prometheus: http://localhost:9090  RabbitMQ 控制台: http://localhost:15672
python ../scripts/smoke_test.py       # 同一套验收
```

## 文档（开发事实源）
| 文档 | 内容 |
| --- | --- |
| [01-产品需求文档](docs/01-产品需求文档-PRD.md) | 背景对标/用户故事/FR/NFR/验收标准 |
| [02-系统架构设计](docs/02-系统架构设计.md) | 架构图/选型理由/关键时序/ADR |
| [03-数据库设计](docs/03-数据库设计.md) | ER/DDL/索引/幂等锚点 |
| [04-API接口设计](docs/04-API接口设计.md) | 契约/错误码表/签名算法 |
| [05-容错与高可用设计](docs/05-容错与高可用设计.md) | 14 个故障场景×机制 |
| [06-测试方案与验收手册](docs/06-测试方案与验收手册.md) | 用例矩阵/手工验收/门禁 |
| [07-部署运维手册](docs/07-部署运维手册.md) | local/docker/监控/Runbook |
| [08-路线图与迭代计划](docs/08-路线图与迭代计划.md) | M1~M4 里程碑/面试叙事 |
| [99-缺陷与迭代记录](docs/99-缺陷与迭代记录.md) | 实现差异与踩坑复盘（面试素材） |

## 技术栈
Java 17 · Spring Boot 3.5 · Spring Cloud Gateway/OpenFeign · Resilience4j · Spring Data JPA + Flyway · H2/MySQL · Redis · RabbitMQ · Micrometer/Prometheus · JUnit5/MockMvc · jjwt · Lombok

## 测试与质量
`mvn clean verify` 一键全绿（23 个自动化用例：令牌轮换与重放撤销、支付回调幂等、20 线程并发账务一致性、任务幂等提交、失败退款、超时退款、签名防重放、限流桶语义等）。
