-- T1 单源下推：网关直连 ClickHouse
SELECT n_regionkey, count(*) AS cnt
FROM clickhouse.tpch.nation
GROUP BY 1;

-- T2 湖/关系库跨源 JOIN：走 Trino
SELECT c.name, n.n_name, r.r_name
FROM mysql.shop.customer c
JOIN clickhouse.tpch.nation n ON c.nationkey = n.n_nationkey
JOIN postgresql.shop.region r ON n.n_regionkey = r.r_regionkey;

-- Kafka 默认映射到落湖表
SELECT * FROM kafka.events.orders LIMIT 10;

-- T7 向量两阶段：网关召回后注入 VALUES，再交给 Trino/直连 JOIN
SELECT a.id, a."from", a."to", a.msgtype, a.createtime
FROM es81.default.his_msg a
         JOIN mysql70.tsoc.auth_user b ON a.chatType = b.userid
    LIMIT 20
