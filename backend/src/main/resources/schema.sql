DROP TABLE IF EXISTS dataset_relation;
DROP TABLE IF EXISTS dataset_physical_table;
DROP TABLE IF EXISTS logical_dataset;
DROP TABLE IF EXISTS fact_refund;
DROP TABLE IF EXISTS fact_payment;
DROP TABLE IF EXISTS fact_inventory_snapshot;
DROP TABLE IF EXISTS fact_order_202608;
DROP TABLE IF EXISTS fact_order_202607;
DROP TABLE IF EXISTS fact_order_202606;
DROP TABLE IF EXISTS fact_order;
DROP TABLE IF EXISTS dim_shop;
DROP TABLE IF EXISTS dim_product;
DROP TABLE IF EXISTS dim_user;
DROP TABLE IF EXISTS metadata_catalog;

CREATE TABLE metadata_catalog (
    table_name VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL,
    business_domain VARCHAR(64) NOT NULL,
    grain_description VARCHAR(256) NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    trust_status VARCHAR(32) NOT NULL,
    maintenance_mode VARCHAR(32) NOT NULL,
    last_synced_at TIMESTAMP NOT NULL
);

CREATE TABLE logical_dataset (
    dataset_id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    business_domain VARCHAR(64) NOT NULL,
    description VARCHAR(512) NOT NULL,
    grain_description VARCHAR(256) NOT NULL,
    owner_name VARCHAR(128) NOT NULL,
    trust_status VARCHAR(32) NOT NULL,
    partition_strategy VARCHAR(64) NOT NULL,
    routing_column VARCHAR(64)
);

CREATE TABLE dataset_physical_table (
    dataset_id VARCHAR(64) NOT NULL,
    physical_table_name VARCHAR(64) NOT NULL,
    partition_value VARCHAR(16) NOT NULL,
    routing_priority INT NOT NULL,
    PRIMARY KEY (dataset_id, physical_table_name),
    CONSTRAINT fk_mapping_dataset FOREIGN KEY (dataset_id) REFERENCES logical_dataset(dataset_id)
);

CREATE TABLE dataset_relation (
    source_dataset_id VARCHAR(64) NOT NULL,
    target_dataset_id VARCHAR(64) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    join_expression VARCHAR(256) NOT NULL,
    description VARCHAR(256) NOT NULL,
    PRIMARY KEY (source_dataset_id, target_dataset_id, relation_type),
    CONSTRAINT fk_relation_source FOREIGN KEY (source_dataset_id) REFERENCES logical_dataset(dataset_id),
    CONSTRAINT fk_relation_target FOREIGN KEY (target_dataset_id) REFERENCES logical_dataset(dataset_id)
);

CREATE TABLE dim_user (
    user_id BIGINT PRIMARY KEY,
    city VARCHAR(64) NOT NULL,
    user_level VARCHAR(32) NOT NULL
);

CREATE TABLE dim_product (
    product_id BIGINT PRIMARY KEY,
    product_name VARCHAR(128) NOT NULL,
    category VARCHAR(64) NOT NULL,
    brand VARCHAR(64) NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL
);

CREATE TABLE dim_shop (
    shop_id BIGINT PRIMARY KEY,
    shop_name VARCHAR(128) NOT NULL,
    city VARCHAR(64) NOT NULL,
    shop_type VARCHAR(32) NOT NULL
);

CREATE TABLE fact_order (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    order_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES dim_user(user_id),
    CONSTRAINT fk_order_product FOREIGN KEY (product_id) REFERENCES dim_product(product_id),
    CONSTRAINT fk_order_shop FOREIGN KEY (shop_id) REFERENCES dim_shop(shop_id)
);

CREATE TABLE fact_order_202606 (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    order_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE fact_order_202607 (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    order_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE fact_order_202608 (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    order_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE fact_payment (
    payment_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    payment_amount DECIMAL(12, 2) NOT NULL,
    payment_channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    paid_at TIMESTAMP NOT NULL
);

CREATE TABLE fact_refund (
    refund_id BIGINT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    refund_amount DECIMAL(12, 2) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE fact_inventory_snapshot (
    snapshot_date DATE NOT NULL,
    product_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    available_quantity INT NOT NULL,
    locked_quantity INT NOT NULL,
    PRIMARY KEY (snapshot_date, product_id, shop_id)
);
