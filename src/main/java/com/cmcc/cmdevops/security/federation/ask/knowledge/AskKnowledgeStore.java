package com.cmcc.cmdevops.security.federation.ask.knowledge;

import java.util.List;
import java.util.Optional;

public interface AskKnowledgeStore {

    AskKnowledge save(AskKnowledge item);

    Optional<AskKnowledge> find(long id);

    List<AskKnowledge> list(String status);

    List<AskKnowledge> findByIds(List<Long> ids);

    void delete(long id);
}
