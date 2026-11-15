package com.cmcc.cmdevops.security.federation.ask.prompt;

import java.util.List;
import java.util.Optional;

public interface AskPromptStore {

    AskPrompt save(AskPrompt item);

    Optional<AskPrompt> find(long id);

    List<AskPrompt> list(String scope, String owner);

    /** 当前生效的全局补充提示词（最多一条）。 */
    Optional<AskPrompt> activeGlobal();

    /** 当前生效的个人补充提示词（最多一条）。 */
    Optional<AskPrompt> activePersonal(String owner);

    void delete(long id);
}
