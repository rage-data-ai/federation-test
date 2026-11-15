package com.cmcc.cmdevops.security.federation.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.account.UserService;
import com.cmcc.cmdevops.security.federation.account.UserService.UserPatch;
import com.cmcc.cmdevops.security.federation.account.UserView;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;
import com.cmcc.cmdevops.security.federation.security.RequireRole;

/** 管理员维护本地账号：查看、改角色、禁用 / 启用、重置密码。 */
@RestController
@RequestMapping("/api/v1/users")
@RequireRole(GatewayRole.ADMIN)
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    public record ResetPasswordBody(String password) {
    }

    @GetMapping
    public List<UserView> list() {
        return users.list();
    }

    @PutMapping("/{id}")
    public UserView update(@PathVariable("id") long id, @RequestBody UserPatch patch) {
        return users.update(id, patch, GatewayUser.current().name());
    }

    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable("id") long id, @RequestBody ResetPasswordBody body) {
        users.resetPassword(id, body.password(), GatewayUser.current().name());
        return Map.of("success", true);
    }
}
