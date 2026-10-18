-- tg-task 初始结构
CREATE TABLE tasks (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_no      VARCHAR(40)   NOT NULL,
  user_id      BIGINT        NOT NULL,
  app_key      VARCHAR(32)   NOT NULL,
  type         VARCHAR(24)   NOT NULL,
  prompt       VARCHAR(1024) NOT NULL,
  params_json  VARCHAR(2048),
  status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
  cost_credits INT           NOT NULL,
  attempts     INT           NOT NULL DEFAULT 0,
  max_attempts INT           NOT NULL DEFAULT 3,
  timeout_at   TIMESTAMP(6)  NOT NULL,
  callback_url VARCHAR(512),
  result_url   VARCHAR(512),
  error_code   VARCHAR(64),
  error_msg    VARCHAR(255),
  version      BIGINT        NOT NULL DEFAULT 0,
  created_at   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at   TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (task_no)
);
CREATE INDEX idx_tasks_user ON tasks (user_id, created_at);
CREATE INDEX idx_tasks_status_timeout ON tasks (status, timeout_at);

CREATE TABLE task_events (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     BIGINT       NOT NULL,
  from_status VARCHAR(16),
  to_status   VARCHAR(16)  NOT NULL,
  detail      VARCHAR(512),
  created_at  TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_te_task ON task_events (task_id);

CREATE TABLE outbox_messages (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  aggregate_type  VARCHAR(16)  NOT NULL,
  aggregate_id    BIGINT       NOT NULL,
  msg_type        VARCHAR(32)  NOT NULL,
  payload_json    VARCHAR(2048) NOT NULL,
  status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
  attempts        INT          NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_outbox_pick ON outbox_messages (status, next_attempt_at);

CREATE TABLE webhook_deliveries (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_no         VARCHAR(40)   NOT NULL,
  url             VARCHAR(512)  NOT NULL,
  payload_json    VARCHAR(2048) NOT NULL,
  status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
  attempts        INT           NOT NULL DEFAULT 0,
  max_attempts    INT           NOT NULL DEFAULT 5,
  next_attempt_at TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error      VARCHAR(512),
  response_code   INT,
  created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX idx_wd_pick ON webhook_deliveries (status, next_attempt_at);

-- 测试用回调接收器（验收 Webhook 可达性）
CREATE TABLE webhook_sink_records (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  payload_json VARCHAR(2048) NOT NULL,
  received_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE idempotency_keys (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  idem_key      VARCHAR(128) NOT NULL,
  endpoint      VARCHAR(128) NOT NULL,
  request_hash  VARCHAR(64)  NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'PROCESSING',
  response_json VARCHAR(2048),
  created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at    TIMESTAMP(6) NOT NULL,
  UNIQUE (idem_key, endpoint)
);
