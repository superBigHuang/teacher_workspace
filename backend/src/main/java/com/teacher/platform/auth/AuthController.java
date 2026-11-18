package com.teacher.platform.auth;

import com.teacher.platform.common.CurrentUser;
import com.teacher.platform.common.JwtUtil;
import com.teacher.platform.common.Result;
import com.teacher.platform.config.datasource.DataSourceContext;
import com.teacher.platform.service.DemoDataSeeder;
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
 * 登录 / 当前账号 / 修改密码 / 测试区重置
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final CaptchaService captchaService;
    private final DemoDataSeeder demoDataSeeder;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 是否处在可信反向代理之后：true 才采信代理头取客户端 IP，防止直连时伪造 XFF 绕过失败计数 */
    @Value("${app.trust-proxy:false}")
    private boolean trustProxy;

    public AuthController(JdbcTemplate jdbc, JwtUtil jwtUtil, CaptchaService captchaService, DemoDataSeeder demoDataSeeder) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.captchaService = captchaService;
        this.demoDataSeeder = demoDataSeeder;
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
                    "SELECT id,username,password_hash,display_name,role,enabled,source " +
                            "FROM sys_user WHERE username=?", username);
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
            Map<String, Object> user = list.get(0);
            // 密码正确但账号已停用：明确提示（不计失败次数）
            if (!CurrentUser.isEnabled(user.get("enabled"))) {
                return Result.fail(401, "账号已停用，请联系管理员");
            }
            captchaService.onSuccess(username, ip);
            // 记录最近登录时间
            jdbc.update("UPDATE sys_user SET last_login_at=NOW() WHERE id=?", user.get("id"));
            String dn = user.get("display_name") == null ? username : (String) user.get("display_name");
            String token = jwtUtil.generate(((Number) user.get("id")).longValue(), username);
            // 角色/状态/渠道随登录响应返回，前端据此控制用户管理入口
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("displayName", dn);
            data.put("username", username);
            data.put("role", String.valueOf(user.get("role")));
            data.put("enabled", true);
            data.put("source", user.get("source"));
            return Result.ok(data);
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

    /** 当前登录账号信息（含角色/状态/渠道，前端据此控制用户管理入口） */
    @GetMapping("/me")
    public Result<Map<String, Object>> me(HttpServletRequest req) {
        Long uid = CurrentUser.uid(req);
        Map<String, Object> u = jdbc.queryForMap(
                "SELECT username, display_name displayName, email, school, role, enabled, source " +
                        "FROM sys_user WHERE id=?", uid);
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

    /**
     * 获取测试体验区状态（是否为测试区、上次重置时间、距下次重置还剩天数）
     */
    @GetMapping("/demo/status")
    public Result<Map<String, Object>> demoStatus() {
        boolean isDemo = DataSourceContext.isDemo();
        Map<String, Object> data = new HashMap<>();
        data.put("isDemo", isDemo);
        if (isDemo) {
            long last = demoDataSeeder.getLastResetTime();
            long now = System.currentTimeMillis();
            long elapsed = now - last;
            long remainMs = Math.max(0, DemoDataSeeder.RESET_INTERVAL_MS - elapsed);
            long remainDays = (remainMs + 86399999L) / (24 * 3600 * 1000L);
            data.put("lastResetTime", last);
            data.put("remainDays", Math.max(1, remainDays));
            data.put("canReset", elapsed >= DemoDataSeeder.COOLDOWN_MS);
            data.put("cooldownSec", Math.max(0, (DemoDataSeeder.COOLDOWN_MS - elapsed) / 1000));
        }
        return Result.ok(data);
    }

    /**
     * 一键恢复测试库初始数据（仅限在测试区执行，正式环境绝对拒绝）
     */
    @PostMapping("/demo/reset")
    public Result<String> resetDemoData() {
        if (!DataSourceContext.isDemo()) {
            return Result.fail(403, "正式生产环境禁止执行重置操作！");
        }
        try {
            boolean ok = demoDataSeeder.reset(false);
            if (ok) {
                return Result.ok("测试库已成功恢复为初始高保真演示数据！");
            } else {
                return Result.fail("当前已有重置任务在执行中，请稍候");
            }
        } catch (IllegalStateException e) {
            return Result.fail(400, e.getMessage());
        } catch (Exception e) {
            log.error("测试库重置失败：{}", e.getMessage(), e);
            return Result.fail("重置失败：" + e.getMessage());
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
