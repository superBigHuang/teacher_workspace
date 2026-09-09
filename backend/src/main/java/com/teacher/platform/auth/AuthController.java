package com.teacher.platform.auth;

import com.teacher.platform.common.CurrentUser;
import com.teacher.platform.common.JwtUtil;
import com.teacher.platform.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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

    /** 是否处在可信反向代理之后：true 才采信代理头取客户端 IP，防止直连时伪造 XFF 绕过失败计数 */
    @Value("${app.trust-proxy:false}")
    private boolean trustProxy;

    public AuthController(JdbcTemplate jdbc, JwtUtil jwtUtil, CaptchaService captchaService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.captchaService = captchaService;
    }

    /** 登录验证码（字符图形码）：公开端点，失败达阈值后登录须携带 */
    @GetMapping("/captcha")
    public Result<Map<String, Object>> captcha() {
        return Result.ok(captchaService.issue());
    }

    /** 登录：账号 + 密码；连续失败 3 次后需验证码，5 次后锁定 10 分钟 */
    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        if (username.isBlank()) return Result.fail(401, "请输入账号");
        String ip = clientIp(req);

        // 锁定中：不查库、不接受验证码，直接拒绝（即使密码正确）
        long lockedMs = captchaService.lockedRemainMs(username, ip);
        if (lockedMs > 0) {
            long mins = Math.max(1, (lockedMs + 59_999) / 60_000);
            return Result.fail(401, "登录失败次数过多，请 " + mins + " 分钟后再试",
                    Map.of("locked", true, "remainSec", lockedMs / 1000, "needCaptcha", true));
        }
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
            boolean userMissing = list.isEmpty();
            boolean pwdWrong = userMissing ||
                    !encoder.matches(password, (String) list.get(0).get("password_hash"));
            if (pwdWrong) {
                captchaService.onFail(username, ip);
                long nowLocked = captchaService.lockedRemainMs(username, ip);
                String msg = nowLocked > 0
                        ? "登录失败次数过多，已锁定 10 分钟"
                        : "账号或密码错误";   // 统一文案，不区分账号不存在/密码错误，防账号枚举
                return Result.fail(401, msg, failData(username, ip, nowLocked));
            }
            captchaService.onSuccess(username, ip);
            Map<String, Object> user = list.get(0);
            String dn = user.get("display_name") == null ? username : (String) user.get("display_name");
            String token = jwtUtil.generate(((Number) user.get("id")).longValue(), username);
            return Result.ok(Map.of("token", token, "displayName", dn, "username", username));
        } catch (Exception e) {
            log.error("登录查询失败：{}", e.getMessage(), e);
            return Result.fail("登录失败，请稍后重试");
        }
    }

    /** 失败响应附带的 data：达阈值带 needCaptcha，新锁定带 locked/remainSec */
    private Map<String, Object> failData(String username, String ip, long lockedMs) {
        Map<String, Object> d = new HashMap<>();
        if (captchaService.needCaptcha(username, ip)) d.put("needCaptcha", true);
        if (lockedMs > 0) {
            d.put("locked", true);
            d.put("remainSec", lockedMs / 1000);
        }
        return d.isEmpty() ? null : d;
    }

    /**
     * 取客户端 IP：仅在 trust-proxy=true（部署在可信反代之后）时采信代理头。
     * 单跳代理取 X-Forwarded-For 末段（nginx 追加的直连地址），防止客户端伪造首段；
     * 直连部署一律用 TCP 远端地址，避免改 XFF 就让失败计数/锁定永远不命中。
     */
    private String clientIp(HttpServletRequest req) {
        if (trustProxy) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                return parts[parts.length - 1].trim();
            }
            String xri = req.getHeader("X-Real-IP");
            if (xri != null && !xri.isBlank()) return xri.trim();
        }
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
