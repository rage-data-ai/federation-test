CREATE DATABASE IF NOT EXISTS tpch;

CREATE TABLE IF NOT EXISTS tpch.nation
(
    n_nationkey Int32,
    n_name String,
    n_regionkey Int32
)
ENGINE = MergeTree
ORDER BY n_nationkey;

INSERT INTO tpch.nation VALUES
    (1, 'UNITED STATES', 1),
    (2, 'CHINA', 2),
    (3, 'GERMANY', 3),
    (4, 'EGYPT', 4),
    (5, 'JAPAN', 2);
