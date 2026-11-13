package com.cmcc.cmdevops.security.federation.ask.ontologytools;

/**
 * 本体术语向量索引持久化（对齐 Auto Ontology「持久术语索引」意图）。
 * 实现可为落盘、联邦库 JDBC，或二者组合。
 */
public interface OntologyEmbeddingStore {

    /**
     * @return 命中指纹与嵌入器名称的索引；未命中返回 {@code null}
     */
    OntologyEmbeddingIndex load(String fingerprint, String embedderName);

    void save(OntologyEmbeddingIndex index);
}
