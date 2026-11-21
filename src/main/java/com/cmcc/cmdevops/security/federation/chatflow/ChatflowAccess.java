package com.cmcc.cmdevops.security.federation.chatflow;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

/** ChatFlow 应用 / 流程资源所有权：ADMIN 或所有者可改；他人 403。 */
final class ChatflowAccess {

    private ChatflowAccess() {
    }

    static void assertCanManage(String owner) {
        GatewayUser user = GatewayUser.current();
        if (user.hasRole(GatewayRole.ADMIN)) {
            return;
        }
        if (owner != null && owner.equals(user.name())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该资源");
    }

    static boolean canManage(String owner) {
        GatewayUser user = GatewayUser.current();
        return user.hasRole(GatewayRole.ADMIN) || (owner != null && owner.equals(user.name()));
    }
}
