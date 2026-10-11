CREATE SCHEMA IF NOT EXISTS shop;

CREATE TABLE shop.region (
    r_regionkey INT PRIMARY KEY,
    r_name VARCHAR(64) NOT NULL
);

INSERT INTO shop.region (r_regionkey, r_name) VALUES
    (1, 'AMERICA'),
    (2, 'ASIA'),
    (3, 'EUROPE'),
    (4, 'AFRICA');
