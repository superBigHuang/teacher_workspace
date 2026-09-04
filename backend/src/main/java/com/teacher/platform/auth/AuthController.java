package com.teacher.platform.auth;

import com.teacher.platform.common.CurrentUser;
import com.teacher.platform.common.JwtUtil;
import com.teacher.platform.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 登录 / 当前账号 / 修改密码
 * （自助注册已下线：账号由管理员后台建号，/register 端点已移除）
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

    /** 登录：账号 + 密码 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        if (username.isBlank()) return Result.fail(401, "请输入账号");
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                    "SELECT id,username,password_hash,display_name FROM sys_user WHERE username=?", username);
            if (list.isEmpty()) return Result.fail(401, "账号不存在");
            Map<String, Object> user = list.get(0);
            if (!encoder.matches(password, (String) user.get("password_hash"))) {
                return Result.fail(401, "密码错误");
            }
            String dn = user.get("display_name") == null ? username : (String) user.get("display_name");
            String token = jwtUtil.generate(((Number) user.get("id")).longValue(), username);
            return Result.ok(Map.of("token", token, "displayName", dn, "username", username));
        } catch (Exception e) {
            log.error("登录查询失败：{}", e.getMessage(), e);
            return Result.fail("登录失败，请稍后重试");
        }
    }

    /** 当前登录账号信息 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest req) {
        Long uid = CurrentUser.uid(req);
        Map<String, Object> u = jdbc.queryForMap(
                "SELECT username, display_name displayName, email, school FROM sys_user WHERE id=?", uid);
        return Result.ok(u);
    }

    /** 修改密码（校验原密码） */
    @PostMapping("/change-password")
    public Result<Void> changePassword(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        Long uid = CurrentUser.uid(req);
        String oldPwd = str(body.get("oldPassword"));
        String newPwd = str(body.get("newPassword"));
        if (newPwd.length() < 6) return Result.fail("新密码至少 6 位");
        Map<String, Object> u = jdbc.queryForMap("SELECT password_hash FROM sys_user WHERE id=?", uid);
        if (!encoder.matches(oldPwd, (String) u.get("password_hash"))) {
            return Result.fail("原密码错误");
        }
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", encoder.encode(newPwd), uid);
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
