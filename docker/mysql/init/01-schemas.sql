-- MySQL 初始化：database per service
CREATE DATABASE IF NOT EXISTS tg_account DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS tg_billing DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS tg_task    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'tiangong'@'%' IDENTIFIED BY 'tiangong123';
GRANT ALL PRIVILEGES ON tg_account.* TO 'tiangong'@'%';
GRANT ALL PRIVILEGES ON tg_billing.* TO 'tiangong'@'%';
GRANT ALL PRIVILEGES ON tg_task.*    TO 'tiangong'@'%';
FLUSH PRIVILEGES;
