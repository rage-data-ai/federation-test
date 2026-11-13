package com.cmcc.cmdevops.security.federation.ask.ontologytools;

/**
 * 术语嵌入器：将文本映射到向量，供 {@link EmbeddingTermRetriever} 余弦召回。
 */
public interface TermEmbedder {

    /** 实现名：hashing / http 等，便于日志与 steps 溯源。 */
    String name();

    float[] embed(String text);
}
