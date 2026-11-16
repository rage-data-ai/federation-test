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

    public BusinessContext context() {
        return context;
    }

    public void setContext(BusinessContext context) {
        this.context = context;
    }

    public MetadataReport report() {
        return report;
    }

    public void setReport(MetadataReport report) {
        this.report = report;
    }

    public boolean metadataRecovered() {
        return metadataRecovered;
    }

    public void setMetadataRecovered(boolean metadataRecovered) {
        this.metadataRecovered = metadataRecovered;
    }
}
