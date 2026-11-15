package com.cmcc.cmdevops.security.federation.account;

import java.util.List;
import java.util.Optional;

public interface UserStore {

    /**
     * 新增账号并返回带 id 的记录。
     *
     * @throws DuplicateUsernameException 用户名已存在
     */
    UserAccount insert(UserAccount account);

    void update(UserAccount account);

    Optional<UserAccount> find(long id);

    Optional<UserAccount> findByUsername(String username);

    List<UserAccount> list();

    void saveSession(UserSession session);

    Optional<UserSession> findSession(String tokenHash);

    void extendSession(String tokenHash, long expiresAt);

    void deleteSession(String tokenHash);

    /** 删除该用户的全部会话；{@code keepTokenHash} 非空时保留这一条。 */
    void deleteSessionsOf(long userId, String keepTokenHash);

    void deleteExpiredSessions(long now);

    final class DuplicateUsernameException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateUsernameException(String username) {
            super("用户名已被占用：" + username);
        }
    }
}
