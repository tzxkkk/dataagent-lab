INSERT IGNORE INTO metadata_catalog(
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

INSERT IGNORE INTO logical_dataset(
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

INSERT IGNORE INTO dataset_field_catalog(
    dataset_id, field_name, display_name, description, value_semantics
) VALUES
('orders', 'order_id', '订单 ID', '订单唯一标识', NULL),
('orders', 'user_id', '下单用户 ID', '关联 users 数据集中的用户', NULL),
('orders', 'product_id', '商品 ID', '关联 products 数据集中的商品', NULL),
('orders', 'shop_id', '成交店铺 ID', '关联 shops 数据集中的成交店铺', NULL),
('orders', 'order_amount', '订单金额', '订单应付金额，单位为元', NULL),
('orders', 'status', '订单状态', '订单当前业务状态', 'COMPLETED=已完成; PENDING=待处理; CANCELLED=已取消'),
('orders', 'created_at', '下单时间', '订单创建时间，也是月份分表路由字段', NULL),
('users', 'user_id', '用户 ID', '用户唯一标识', NULL),
('users', 'city', '用户所在城市', '用户所在城市，用于按下单用户地域分析', NULL),
('users', 'user_level', '用户等级', '用户分层等级', 'PLATINUM=铂金; GOLD=黄金; SILVER=白银'),
('products', 'product_id', '商品 ID', '商品唯一标识', NULL),
('products', 'category', '商品品类', '商品所属标准品类', NULL),
('products', 'brand', '商品品牌', '商品所属品牌', NULL),
('shops', 'shop_id', '店铺 ID', '店铺唯一标识', NULL),
('shops', 'city', '成交店铺城市', '成交店铺所在城市，用于按履约或经营区域分析', NULL),
('shops', 'shop_type', '店铺类型', '店铺经营类型', NULL),
('payments', 'payment_amount', '支付金额', '实际支付金额，单位为元', NULL),
('payments', 'payment_channel', '支付渠道', '用户完成支付所使用的渠道', 'ALIPAY=支付宝; WECHAT=微信; CARD=银行卡'),
('payments', 'status', '支付状态', '支付处理状态', 'PAID=支付成功; FAILED=支付失败'),
('refunds', 'refund_amount', '退款金额', '退款金额，单位为元', NULL),
('refunds', 'reason', '退款原因', '用户或系统记录的退款原因', NULL),
('refunds', 'status', '退款状态', '退款处理状态', 'COMPLETED=已完成; PENDING=处理中'),
('inventory', 'snapshot_date', '快照日期', '库存状态对应的自然日', NULL),
('inventory', 'available_quantity', '可售库存', '当前可直接销售的库存数量', NULL),
('inventory', 'locked_quantity', '锁定库存', '已被订单占用但尚未扣减的库存数量', NULL);

INSERT IGNORE INTO dataset_physical_table(dataset_id, physical_table_name, partition_value, routing_priority) VALUES
('orders', 'fact_order_202606', '2026-06', 10),
('orders', 'fact_order_202607', '2026-07', 20),
('orders', 'fact_order_202608', '2026-08', 30),
('users', 'dim_user', 'STATIC', 10),
('products', 'dim_product', 'STATIC', 10),
('shops', 'dim_shop', 'STATIC', 10),
('payments', 'fact_payment', 'STATIC', 10),
('refunds', 'fact_refund', 'STATIC', 10),
('inventory', 'fact_inventory_snapshot', 'STATIC', 10);

INSERT IGNORE INTO dataset_relation(
    source_dataset_id, target_dataset_id, relation_type, join_expression, description
) VALUES
('orders', 'users', 'MANY_TO_ONE', 'orders.user_id = users.user_id', '订单归属于下单用户'),
('orders', 'products', 'MANY_TO_ONE', 'orders.product_id = products.product_id', '订单包含一个商品'),
('orders', 'shops', 'MANY_TO_ONE', 'orders.shop_id = shops.shop_id', '订单归属于成交店铺'),
('payments', 'orders', 'MANY_TO_ONE', 'payments.order_id = orders.order_id', '支付记录关联订单'),
('refunds', 'orders', 'MANY_TO_ONE', 'refunds.order_id = orders.order_id', '退款记录关联订单');

INSERT IGNORE INTO dim_user(user_id, city, user_level) VALUES
(1, '武汉', 'GOLD'),
(2, '武汉', 'SILVER'),
(3, '成都', 'GOLD'),
(4, '北京', 'PLATINUM'),
(5, '成都', 'SILVER'),
(6, '北京', 'GOLD');

INSERT IGNORE INTO dim_product(product_id, product_name, category, brand, unit_price) VALUES
(11, '智能音箱', '数码', 'ByteSound', 100.00),
(12, '运动手环', '数码', 'Pulse', 80.00),
(13, '旅行背包', '箱包', 'Trail', 200.00),
(14, '保温杯', '家居', 'Daily', 50.00);

INSERT IGNORE INTO dim_shop(shop_id, shop_name, city, shop_type) VALUES
(21, '武汉旗舰店', '武汉', 'SELF_OPERATED'),
(22, '成都中心店', '成都', 'MARKETPLACE'),
(23, '北京直营店', '北京', 'SELF_OPERATED');

INSERT IGNORE INTO fact_order(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(101, 1, 11, 21, 100.00, 'COMPLETED', '2026-07-01 10:00:00'),
(102, 2, 14, 21,  50.00, 'COMPLETED', '2026-07-02 11:00:00'),
(103, 3, 12, 22,  80.00, 'COMPLETED', '2026-07-03 12:00:00'),
(104, 4, 11, 23, 120.00, 'CANCELLED', '2026-07-04 13:00:00'),
(105, 5, 14, 22,  20.00, 'PENDING',   '2026-07-05 14:00:00'),
(106, 6, 13, 23, 200.00, 'COMPLETED', '2026-07-06 15:00:00');

INSERT IGNORE INTO fact_order_202606(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(601, 1, 11, 21, 100.00, 'COMPLETED', '2026-06-05 10:00:00'),
(602, 3, 12, 22,  80.00, 'COMPLETED', '2026-06-18 12:00:00');

INSERT IGNORE INTO fact_order_202607(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(701, 2, 14, 21,  50.00, 'COMPLETED', '2026-07-02 11:00:00'),
(702, 6, 13, 23, 200.00, 'COMPLETED', '2026-07-06 15:00:00'),
(703, 5, 14, 22,  20.00, 'PENDING',   '2026-07-09 09:30:00');

INSERT IGNORE INTO fact_order_202608(order_id, user_id, product_id, shop_id, order_amount, status, created_at) VALUES
(801, 4, 11, 23, 120.00, 'COMPLETED', '2026-08-01 13:00:00'),
(802, 1, 12, 21,  88.00, 'COMPLETED', '2026-08-03 16:00:00');

INSERT IGNORE INTO fact_payment(payment_id, order_id, payment_amount, payment_channel, status, paid_at) VALUES
(9001, 601, 100.00, 'ALIPAY', 'PAID', '2026-06-05 10:01:00'),
(9002, 701,  50.00, 'WECHAT', 'PAID', '2026-07-02 11:01:00'),
(9003, 801, 120.00, 'CARD', 'PAID', '2026-08-01 13:02:00');

INSERT IGNORE INTO fact_refund(refund_id, order_id, refund_amount, reason, status, created_at) VALUES
(9101, 702, 20.00, '部分退货', 'COMPLETED', '2026-07-10 09:00:00'),
(9102, 801, 120.00, '重复下单', 'PENDING', '2026-08-02 10:00:00');

INSERT IGNORE INTO fact_inventory_snapshot(
    snapshot_date, product_id, shop_id, available_quantity, locked_quantity
) VALUES
('2026-08-01', 11, 21, 30, 2),
('2026-08-01', 12, 21, 18, 1),
('2026-08-01', 13, 23,  6, 1),
('2026-08-02', 11, 21, 27, 3);
