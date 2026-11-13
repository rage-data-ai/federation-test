package com.cmcc.cmdevops.security.federation.ontology;

import java.util.List;

import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Attribute;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Code;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Concept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Condition;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.DefinedConcept;
import com.cmcc.cmdevops.security.federation.ontology.OntologyModel.Relation;

public final class OntologyFixtures {

    /** 告警表 level 列的码值集合，名字由表和列决定。 */
    public static final String ALERT_LEVEL = OntologyRepository.codeListName("ck81.pangu.alert", "level");

    private OntologyFixtures() {
    }

    public static void alertAsset(OntologyRepository repository) {
        repository.saveConcept(new Concept("Asset", "资产", "Asset", List.of("主机", "设备"), "被纳管的 IT 资产",
                null, "mysql70.tsoc.asset", null, false));
        repository.saveConcept(new Concept("Alert", "告警", "Alert", List.of("安全告警", "告警事件"), "归并后的安全告警",
                null, "ck81.pangu.alert", "occurTime", true));

        repository.saveAttribute(new Attribute("assetName", "资产名称", "asset name", List.of(), null,
                "Asset", "string", "name", null, false, null));
        repository.saveAttribute(new Attribute("assetPk", "资产编号", "asset id", List.of(), null,
                "Asset", "long", "id", null, true, null));
        repository.saveAttribute(new Attribute("alertId", "告警编号", "alert id", List.of(), null,
                "Alert", "string", "id", null, true, null));
        repository.saveAttribute(new Attribute("alertLevel", "告警级别", "alert level", List.of("危险级别", "严重程度"), null,
                "Alert", "integer", "level", null, false, "AlertLevel"));
        repository.saveAttribute(new Attribute("occurTime", "发生时间", "occur time", List.of(), null,
                "Alert", "dateTime", "occurTime", null, false, null));

        repository.saveCode(new Code("AlertLevel_3", ALERT_LEVEL, "3", "高危", "high", List.of("严重"), null));
        repository.saveCode(new Code("AlertLevel_2", ALERT_LEVEL, "2", "中危", "medium", List.of(), null));

        repository.saveRelation(new Relation("affectsAsset", "影响资产", "affects asset", List.of("受影响资产"), null,
                "Alert", "Asset", "assetId", "id", "hasAlert", false, true));

        repository.saveDefinedConcept(new DefinedConcept("HighRiskAlert", "高危告警", "high risk alert",
                List.of("高风险告警"), "级别不低于 3 的告警", "Alert",
                List.of(new Condition("alertLevel", ">=", "3")), null));
        repository.saveDefinedConcept(new DefinedConcept("CompromisedHost", "失陷主机", "compromised host",
                List.of(), null, "Asset", List.of(), "近 24 小时高危告警数不少于 3 条的资产"));
    }
}
