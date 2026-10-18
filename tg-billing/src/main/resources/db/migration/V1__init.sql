-- tg-billing 初始结构
CREATE TABLE plans (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  code            VARCHAR(32)  NOT NULL,
  name            VARCHAR(64)  NOT NULL,
  type            VARCHAR(16)  NOT NULL,
  price_cents     INT          NOT NULL,
  currency        VARCHAR(8)   NOT NULL DEFAULT 'CNY',
  period_days     INT          NOT NULL DEFAULT 30,
  monthly_credits INT          NOT NULL DEFAULT 0,
  credits         INT          NOT NULL DEFAULT 0,
  description     VARCHAR(255),
  status          VARCHAR(16)  NOT NULL DEFAULT 'ON_SALE',
  UNIQUE (code)
);

CREATE TABLE subscriptions (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT      NOT NULL,
  plan_id        BIGINT      NOT NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  period_start   TIMESTAMP(6) NOT NULL,
  period_end     TIMESTAMP(6) NOT NULL,
  canceled_at    TIMESTAMP(6) NULL,
  created_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (user_id, plan_id)
);

CREATE TABLE orders (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no     VARCHAR(40)  NOT NULL,
  user_id      BIGINT      NOT NULL,
  plan_id      BIGINT      NOT NULL,
  amount_cents INT         NOT NULL,
  currency     VARCHAR(8)  NOT NULL DEFAULT 'CNY',
  status       VARCHAR(16) NOT NULL DEFAULT 'CREATED',
  expires_at   TIMESTAMP(6) NOT NULL,
  paid_at      TIMESTAMP(6) NULL,
  created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (order_no)
);
CREATE INDEX idx_orders_user ON orders (user_id, created_at);
CREATE INDEX idx_orders_expire ON orders (status, expires_at);

CREATE TABLE payments (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  payment_no   VARCHAR(64) NOT NULL,
  order_no     VARCHAR(40) NOT NULL,
  channel      VARCHAR(16) NOT NULL DEFAULT 'MOCK',
  amount_cents INT         NOT NULL,
  status       VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
  created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (payment_no)
);

CREATE TABLE credit_accounts (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT NOT NULL,
  balance    INT    NOT NULL DEFAULT 0,
  held       INT    NOT NULL DEFAULT 0,
  version    BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (user_id)
);

CREATE TABLE credit_ledgers (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT      NOT NULL,
  change_type   VARCHAR(16) NOT NULL,
  amount        INT         NOT NULL,
  balance_after INT         NOT NULL,
  biz_type      VARCHAR(16) NOT NULL,
  biz_no        VARCHAR(64) NOT NULL,
  remark        VARCHAR(255),
  created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (biz_type, biz_no, change_type)
);
CREATE INDEX idx_ledger_user ON credit_ledgers (user_id, created_at);

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
