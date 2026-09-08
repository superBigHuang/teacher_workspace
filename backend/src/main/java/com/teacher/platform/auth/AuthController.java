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
    private final CaptchaService captchaService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(JdbcTemplate jdbc, JwtUtil jwtUtil, CaptchaService captchaService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.captchaService = captchaService;
    }

    /** 登录验证码（算术图形码）：公开端点，失败达阈值后登录须携带 */
    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha() {
        return Result.ok(captchaService.issue());
    }

    /** 登录：账号 + 密码；连续失败 3 次后需带验证码 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        if (username.isBlank()) return Result.fail(401, "请输入账号");
        String ip = clientIp(req);

        // 失败达阈值：先验验证码，不通过直接拒（不查库、不计密码失败）
        if (captchaService.needCaptcha(username, ip)) {
            String captchaId = str(body.get("captchaId"));
            String captchaCode = str(body.get("captchaCode"));
            if (captchaId.isBlank() || captchaCode.isBlank()) {
                return Result.fail(401, "请完成验证码", Map.of("needCaptcha", true));
            }
            if (!captchaService.verify(captchaId, captchaCode)) {
                return Result.fail(401, "验证码错误，请重试", Map.of("needCaptcha", true));
            }
        }
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                    "SELECT id,username,password_hash,display_name FROM sys_user WHERE username=?", username);
            if (list.isEmpty()) {
                captchaService.onFail(username, ip);
                return Result.fail(401, "账号不存在", failData(username, ip));
            }
            Map<String, Object> user = list.get(0);
            if (!encoder.matches(password, (String) user.get("password_hash"))) {
                captchaService.onFail(username, ip);
                return Result.fail(401, "密码错误", failData(username, ip));
            }
            captchaService.onSuccess(username, ip);
            String dn = user.get("display_name") == null ? username : (String) user.get("display_name");
            String token = jwtUtil.generate(((Number) user.get("id")).longValue(), username);
            return Result.ok(Map.of("token", token, "displayName", dn, "username", username));
        } catch (Exception e) {
            log.error("登录查询失败：{}", e.getMessage(), e);
            return Result.fail("登录失败，请稍后重试");
        }
    }

    /** 失败响应附带的 data：达阈值时带 needCaptcha=true，前端据此拉起验证码 */
    private Map<String, Object> failData(String username, String ip) {
        return captchaService.needCaptcha(username, ip) ? Map.of("needCaptcha", true) : null;
    }

    /** 取客户端 IP：优先代理头（X-Forwarded-For 首段 / X-Real-IP），回退直连地址 */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        String xri = req.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri.trim();
        return req.getRemoteAddr();
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
