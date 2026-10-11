CREATE TABLE IF NOT EXISTS customer (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    nationkey INT NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
    id BIGINT PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    category VARCHAR(32) NOT NULL
);

INSERT INTO customer (id, name, nationkey) VALUES
    (1, 'Alice', 1),
    (2, 'Bob', 2),
    (3, 'Carol', 3),
    (4, 'Dave', 1),
    (5, 'Eve', 4);

INSERT INTO products (id, name, category) VALUES
    (1, 'Pixel Phone', 'phone'),
    (2, 'Galaxy Phone', 'phone'),
    (3, 'ThinkPad', 'laptop'),
    (4, 'iPhone', 'phone'),
    (5, 'iPad', 'tablet');
