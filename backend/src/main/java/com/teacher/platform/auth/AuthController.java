package com.teacher.platform.auth;

import com.teacher.platform.common.JwtUtil;
import com.teacher.platform.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录鉴权
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    /** 登录：单账号密码校验，返回 token */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String password = body.getOrDefault("password", "");
        try {
            Map<String, Object> user = jdbc.queryForMap(
                    "SELECT id, username, password_hash, display_name FROM sys_user WHERE username='admin'");
            String hash = (String) user.get("password_hash");
            if (!encoder.matches(password, hash)) {
                return Result.fail(401, "密码错误");
            }
            String token = jwtUtil.generate(((Number) user.get("id")).longValue(), (String) user.get("username"));
            return Result.ok(Map.of(
                    "token", token,
                    "displayName", user.get("display_name") == null ? "老师" : user.get("display_name")));
        } catch (Exception e) {
            // 查无此用户、连库失败、表/库不存在都会走到这里，打印真实异常便于排查
            log.error("登录查询失败：{}", e.getMessage(), e);
            return Result.fail("账号不存在或数据未初始化");
        }
    }

    /** 修改密码 */
    @PostMapping("/change-password")
    public Result<Void> changePassword(@RequestBody Map<String, String> body) {
        String oldPwd = body.getOrDefault("oldPassword", "");
        String newPwd = body.getOrDefault("newPassword", "");
        if (newPwd.length() < 6) {
            return Result.fail("新密码至少 6 位");
        }
        Map<String, Object> user = jdbc.queryForMap("SELECT password_hash FROM sys_user WHERE username='admin'");
        if (!encoder.matches(oldPwd, (String) user.get("password_hash"))) {
            return Result.fail("原密码错误");
        }
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE username='admin'", encoder.encode(newPwd));
        return Result.ok();
    }
}
