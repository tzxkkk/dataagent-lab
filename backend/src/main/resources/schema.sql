DROP TABLE IF EXISTS fact_order;
DROP TABLE IF EXISTS dim_user;
DROP TABLE IF EXISTS metadata_catalog;

CREATE TABLE metadata_catalog (
    table_name VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    description VARCHAR(512) NOT NULL
);

CREATE TABLE dim_user (
    user_id BIGINT PRIMARY KEY,
    city VARCHAR(64) NOT NULL,
    user_level VARCHAR(32) NOT NULL
);

CREATE TABLE fact_order (
    order_id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    order_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES dim_user(user_id)
);

