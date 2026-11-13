-- T1 单源下推：网关直连 ClickHouse
SELECT key, count(*) AS cnt
FROM ck81.pangu.thbd_config
GROUP BY 1;

-- T2 湖/关系库跨源 JOIN：走 Trino
SELECT c.*，n.*
FROM mysql.tsoc.auth_user c
JOIN ck81.pangu.sample n ON c.userid = n.id

-- Kafka 默认映射到落湖表
SELECT _partition_id, _partition_offset, _message
FROM kafka81.default."my-topic"
         LIMIT 20

-- T7 向量两阶段：网关召回后注入 VALUES，再交给 Trino/直连 JOIN
SELECT a.id, a."from", a."to", a.msgtype, a.createtime
FROM es81.default.his_msg a
         JOIN mysql70.tsoc.auth_user b ON a.chatType = b.userid
    LIMIT 20
