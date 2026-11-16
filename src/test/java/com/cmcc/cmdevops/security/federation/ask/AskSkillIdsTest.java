package com.cmcc.cmdevops.security.federation.ask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AskSkillIdsTest {

    @Test
    void forLlmChatDropsKnowledgeAndSql() {
        Set<String> skills = AskSkillIds.forLlmChat(
                List.of(AskSkillIds.ONTOLOGY, AskSkillIds.KNOWLEDGE, AskSkillIds.SQL, AskSkillIds.PREFERENCE));
        assertEquals(Set.of(AskSkillIds.ONTOLOGY, AskSkillIds.PREFERENCE), skills);
        assertFalse(skills.contains(AskSkillIds.KNOWLEDGE));
        assertFalse(skills.contains(AskSkillIds.SQL));
    }

    @Test
    void forLlmChatEmptyWhenOnlyDedicatedSkills() {
        assertTrue(AskSkillIds.forLlmChat(List.of(AskSkillIds.KNOWLEDGE, AskSkillIds.SQL)).isEmpty());
    }
}
