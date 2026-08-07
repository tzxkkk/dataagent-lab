INSERT INTO metadata_catalog(table_name, display_name, description) VALUES
('fact_order', '订单事实表', '记录订单金额、状态、下单用户和创建时间'),
('dim_user', '用户维度表', '记录用户所在城市和用户等级');

INSERT INTO dim_user(user_id, city, user_level) VALUES
(1, '武汉', 'GOLD'),
(2, '武汉', 'SILVER'),
(3, '成都', 'GOLD'),
(4, '北京', 'PLATINUM'),
(5, '成都', 'SILVER'),
(6, '北京', 'GOLD');

INSERT INTO fact_order(order_id, user_id, order_amount, status, created_at) VALUES
(101, 1, 100.00, 'COMPLETED', '2026-07-01 10:00:00'),
(102, 2,  50.00, 'COMPLETED', '2026-07-02 11:00:00'),
(103, 3,  80.00, 'COMPLETED', '2026-07-03 12:00:00'),
(104, 4, 120.00, 'CANCELLED', '2026-07-04 13:00:00'),
(105, 5,  20.00, 'PENDING',   '2026-07-05 14:00:00'),
(106, 6, 200.00, 'COMPLETED', '2026-07-06 15:00:00');

