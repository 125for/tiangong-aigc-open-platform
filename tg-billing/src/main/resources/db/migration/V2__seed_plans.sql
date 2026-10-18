-- 种子商品：订阅（月度积分额度）与积分包
INSERT INTO plans (code, name, type, price_cents, period_days, monthly_credits, credits, description) VALUES
('starter_pro',  '创作者专业版·月订阅', 'SUBSCRIPTION', 9900,  30, 6000, 0,    '月度 6000 积分，适合个人创作者'),
('studio_max',   '工作室旗舰版·月订阅', 'SUBSCRIPTION', 29900, 30, 20000, 0,  '月度 20000 积分，适合小团队'),
('credits_500',  '积分包 500',          'CREDIT_PACK',  2900,  0,  0,     500,  '一次性 500 积分，永不过期'),
('credits_2000', '积分包 2000',         'CREDIT_PACK',  9900,  0,  0,     2000, '一次性 2000 积分，永不过期');
