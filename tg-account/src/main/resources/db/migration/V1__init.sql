-- tg-account 初始结构（MySQL 方言；H2 以 MODE=MySQL 兼容运行）
CREATE TABLE users (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  email         VARCHAR(128) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  nickname      VARCHAR(64)  NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  version       BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (email)
);

CREATE TABLE refresh_tokens (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id    BIGINT      NOT NULL,
  token_hash VARCHAR(64)    NOT NULL,
  family_id  BIGINT      NULL,
  status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  expires_at TIMESTAMP(6) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (token_hash)
);
CREATE INDEX idx_rt_user ON refresh_tokens (user_id);

CREATE TABLE apps (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id           BIGINT       NOT NULL,
  app_key           VARCHAR(32)  NOT NULL,
  secret_cipher     VARCHAR(256) NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  callback_url      VARCHAR(512),
  status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  rate_limit_per_min INT         NOT NULL DEFAULT 120,
  created_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE (app_key)
);
CREATE INDEX idx_apps_user ON apps (user_id);
