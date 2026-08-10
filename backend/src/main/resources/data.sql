INSERT INTO metadata_catalog(
    table_name, display_name, description, business_domain, grain_description,
    owner_name, trust_status, maintenance_mode, last_synced_at
) VALUES
('fact_order', '订单事实表（基线）', '用于原有 Golden Set 的订单基线表', '交易', '一行一笔订单', '交易数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('dim_user', '用户维度表', '记录用户所在城市和用户等级', '用户', '一行一个用户', '用户数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('fact_order_202606', '订单事实表 2026-06', '2026 年 6 月订单物理分表', '交易', '一行一笔订单', '交易数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('fact_order_202607', '订单事实表 2026-07', '2026 年 7 月订单物理分表', '交易', '一行一笔订单', '交易数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('fact_order_202608', '订单事实表 2026-08', '2026 年 8 月订单物理分表', '交易', '一行一笔订单', '交易数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('dim_product', '商品维度表', '记录商品、品类、品牌和标准单价', '商品', '一行一个商品', '商品数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('dim_shop', '店铺维度表', '记录店铺所在城市和经营类型', '商家', '一行一个店铺', '商家数据组', 'TRUSTED', 'MANUAL', CURRENT_TIMESTAMP),
('fact_payment', '支付事实表', '记录订单支付渠道、金额和支付状态', '交易', '一行一笔支付', '支付数据组', 'REVIEWED', 'MANUAL', CURRENT_TIMESTAMP),
('fact_refund', '退款事实表', '记录订单退款金额、原因和处理状态', '交易', '一行一笔退款', '售后数据组', 'REVIEWED', 'MANUAL', CURRENT_TIMESTAMP);

INSERT INTO logical_dataset(
    dataset_id, display_name, business_domain, description, grain_description,
    owner_name, trust_status, partition_strategy, routing_column
) VALUES
('orders', '订单主题数据集', '交易', '统一描述订单业务，底层按月映射到物理分表', '一行一笔订单', '交易数据组', 'TRUSTED', 'MONTHLY', 'created_at'),
('users', '用户主题数据集', '用户', '用户城市和等级的统一入口', '一行一个用户', '用户数据组', 'TRUSTED', 'STATIC', NULL),
('products', '商品主题数据集', '商品', '商品基础信息和标准单价', '一行一个商品', '商品数据组', 'TRUSTED', 'STATIC', NULL),
('shops', '店铺主题数据集', '商家', '店铺位置和经营类型', '一行一个店铺', '商家数据组', 'TRUSTED', 'STATIC', NULL),
('payments', '支付主题数据集', '交易', '订单支付事实的统一入口', '一行一笔支付', '支付数据组', 'REVIEWED', 'STATIC', NULL),
('refunds', '退款主题数据集', '交易', '订单退款事实的统一入口', '一行一笔退款', '售后数据组', 'REVIEWED', 'STATIC', NULL),
('inventory', '库存快照数据集', '供应链', '按天记录商品在店铺的可售和锁定库存', '一天一个商品在一个店铺的快照', '供应链数据组', 'EXPERIMENTAL', 'STATIC', NULL);

INSERT INTO dataset_physical_table(dataset_id, physical_table_name, partition_value, routing_priority) VALUES
('orders', 'fact_order_202606', '2026-06', 10),
('orders', 'fact_order_202607', '2026-07', 20),
('orders', 'fact_order_202608', '2026-08', 30),
('users', 'dim_user', 'STATIC', 10),
('products', 'dim_product', 'STATIC', 10),
('shops', 'dim_shop', 'STATIC', 10),
('payments', 'fact_payment', 'STATIC', 10),
('refunds', 'fact_refund', 'STATIC', 10),
('inventory', 'fact_inventory_snapshot', 'STATIC', 10);

INSERT INTO dataset_relation(
    source_dataset_id, target_dataset_id, relation_type, join_expression, description
) VALUES
('orders', 'users', 'MANY_TO_ONE', 'orders.user_id = users.user_id', '订单归属于下单用户'),
('orders', 'products', 'MANY_TO_ONE', 'orders.product_id = products.product_id', '订单包含一个商品'),
('orders', 'shops', 'MANY_TO_ONE', 'orders.shop_id = shops.shop_id', '订单归属于成交店铺'),
('payments', 'orders', 'MANY_TO_ONE', 'payments.order_id = orders.order_id', '支付记录关联订单'),
('refunds', 'orders', 'MANY_TO_ONE', 'refunds.order_id = orders.order_id', '退款记录关联订单');

INSERT INTO dim_user(user_id, city, user_level) VALUES
(1, '武汉', 'GOLD'),
(2, '武汉', 'SILVER'),
(3, '成都', 'GOLD'),
(4, '北京', 'PLATINUM'),
(5, '成都', 'SILVER'),
(6, '北京', 'GOLD');

INSERT INTO dim_product(product_id, product_name, category, brand, unit_price) VALUES
(11, '智能音箱', '数码', 'ByteSound', 100.00),
(12, '运动手环', '数码', 'Pulse', 80.00),
(13, '旅行背包', '箱包', 'Trail', 200.00),
(14, '保温杯', '家居', 'Daily', 50.00);

INSERT INTO dim_shop(shop_id, shop_name, city, shop_type) VALUES
(21, '武汉旗舰店', '武汉', 'SELF_OPERATED'),
(22, '成都中心店', '成都', 'MARKETPLACE'),
(23, '北京直营店', '北京', 'SELF_OPERATED');

INSERT INTO fact_order(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(101, 1, 11, 21, 100.00, 'COMPLETED', '2026-07-01 10:00:00'),
(102, 2, 14, 21,  50.00, 'COMPLETED', '2026-07-02 11:00:00'),
(103, 3, 12, 22,  80.00, 'COMPLETED', '2026-07-03 12:00:00'),
(104, 4, 11, 23, 120.00, 'CANCELLED', '2026-07-04 13:00:00'),
(105, 5, 14, 22,  20.00, 'PENDING',   '2026-07-05 14:00:00'),
(106, 6, 13, 23, 200.00, 'COMPLETED', '2026-07-06 15:00:00');

INSERT INTO fact_order_202606(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(601, 1, 11, 21, 100.00, 'COMPLETED', '2026-06-05 10:00:00'),
(602, 3, 12, 22,  80.00, 'COMPLETED', '2026-06-18 12:00:00');

INSERT INTO fact_order_202607(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(701, 2, 14, 21,  50.00, 'COMPLETED', '2026-07-02 11:00:00'),
(702, 6, 13, 23, 200.00, 'COMPLETED', '2026-07-06 15:00:00'),
(703, 5, 14, 22,  20.00, 'PENDING',   '2026-07-09 09:30:00');

INSERT INTO fact_order_202608(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(801, 4, 11, 23, 120.00, 'COMPLETED', '2026-08-01 13:00:00'),
(802, 1, 12, 21,  88.00, 'COMPLETED', '2026-08-03 16:00:00');

INSERT INTO fact_payment(payment_id, order_id, payment_amount, payment_channel, status, paid_at) VALUES
(9001, 601, 100.00, 'ALIPAY', 'PAID', '2026-06-05 10:01:00'),
(9002, 701,  50.00, 'WECHAT', 'PAID', '2026-07-02 11:01:00'),
(9003, 801, 120.00, 'CARD', 'PAID', '2026-08-01 13:02:00');

INSERT INTO fact_refund(refund_id, order_id, refund_amount, reason, status, created_at) VALUES
(9101, 702, 20.00, '部分退货', 'COMPLETED', '2026-07-10 09:00:00'),
(9102, 801, 120.00, '重复下单', 'PENDING', '2026-08-02 10:00:00');

INSERT INTO fact_inventory_snapshot(
    snapshot_date, product_id, shop_id, available_quantity, locked_quantity
) VALUES
('2026-08-01', 11, 21, 30, 2),
('2026-08-01', 12, 21, 18, 1),
('2026-08-01', 13, 23,  6, 1),
('2026-08-02', 11, 21, 27, 3);
