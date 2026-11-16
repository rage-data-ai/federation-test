package com.cmcc.cmdevops.security.federation.ask;

import com.cmcc.cmdevops.security.federation.ask.MetadataVerifier.MetadataReport;
import com.cmcc.cmdevops.security.federation.ask.OntologyTools.BusinessContext;

/**
 * 一次问数流程在节点间传递的状态（ChatFlow 白盒拆跑）。
 */
public final class AskFlowState {

    private BusinessContext context;
    private MetadataReport report;
    private boolean metadataRecovered;
    /** 白盒图已执行过本体节点（即使结果未写入 context，也不应再自动补跑）。 */
    private boolean ontologyPrepared;
    /** 白盒图已执行过元数据节点。 */
    private boolean metadataPrepared;

    public BusinessContext context() {
        return context;
    }

    public void setContext(BusinessContext context) {
        this.context = context;
        this.ontologyPrepared = true;
    }

    public MetadataReport report() {
        return report;
    }

    public void setReport(MetadataReport report) {
        this.report = report;
        this.metadataPrepared = true;
    }

    public boolean metadataRecovered() {
        return metadataRecovered;
    }

    public void setMetadataRecovered(boolean metadataRecovered) {
        this.metadataRecovered = metadataRecovered;
    }

    public boolean ontologyPrepared() {
        return ontologyPrepared;
    }

    public void markOntologyPrepared() {
        this.ontologyPrepared = true;
    }

    public boolean metadataPrepared() {
        return metadataPrepared;
    }

    public void markMetadataPrepared() {
        this.metadataPrepared = true;
    }
}
