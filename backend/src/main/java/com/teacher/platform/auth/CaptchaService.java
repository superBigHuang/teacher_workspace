package com.teacher.platform.auth;

import com.wf.captcha.SpecCaptcha;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录防护服务（内存态，不依赖 Redis）
 * <p>
 * 三道闸门（均以 账号+IP 为维度，窗口 30 分钟）：
 * 1. 失败计数：连续失败达 {@link #FAIL_THRESHOLD} 次后登录必须携带有效验证码；
 * 2. 失败锁定：连续失败达 {@link #LOCK_THRESHOLD} 次后锁定 {@link #LOCK_MS}，
 *    锁定期内即使密码正确也拒绝（成功登录即清零）；
 * 3. 验证码存储：字符图形验证码（easy-captcha），一次性使用（验完即删），TTL 过期作废。
 * <p>
 * 说明：阈值验证码 + 短时锁定是机器人/暴力尝试的拦截减速手段，非强安全防线；内网单机部署足够。
 */
@Service
public class CaptchaService {

    /** 连续失败多少次后要求验证码 */
    private static final int FAIL_THRESHOLD = 3;
    /** 连续失败多少次后锁定登录 */
    private static final int LOCK_THRESHOLD = 5;
    /** 失败计数窗口：距上次失败超过该时长则计数作废 */
    private static final long FAIL_WINDOW_MS = 30 * 60 * 1000L;
    /** 锁定时长 */
    private static final long LOCK_MS = 10 * 60 * 1000L;
    /** 验证码有效期 */
    private static final long CAPTCHA_TTL_MS = 5 * 60 * 1000L;

    /** key = username(小写)|IP → 失败计数与锁定态 */
    private final Map<String, FailCnt> failCounts = new ConcurrentHashMap<>();
    /** captchaId → 答案 + 过期时间 */
    private final Map<String, Cap> captchaStore = new ConcurrentHashMap<>();

    private static final class FailCnt {
        int count;
        long lastAt;
        long lockedUntil;
    }

    private record Cap(String answer, long expireAt) {}

    private String key(String username, String ip) {
        return (username == null ? "" : username.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    /** 当前是否需要验证码：窗口内失败次数达阈值且未锁定（锁定由上层直接拒） */
    public boolean needCaptcha(String username, String ip) {
        FailCnt f = failCounts.get(key(username, ip));
        if (f == null) return false;
        synchronized (f) {
            if (System.currentTimeMillis() - f.lastAt > FAIL_WINDOW_MS) return false;
            return f.count >= FAIL_THRESHOLD;
        }
    }

    /** 锁定剩余毫秒；0 表示未锁定 */
    public long lockedRemainMs(String username, String ip) {
        FailCnt f = failCounts.get(key(username, ip));
        if (f == null) return 0;
        synchronized (f) {
            return Math.max(0, f.lockedUntil - System.currentTimeMillis());
        }
    }

    /** 登录失败：计数 +1（窗口外重新计），达锁定阈值则锁定固定时长 */
    public void onFail(String username, String ip) {
        FailCnt f = failCounts.computeIfAbsent(key(username, ip), k -> new FailCnt());
        synchronized (f) {
            long now = System.currentTimeMillis();
            if (now - f.lastAt > FAIL_WINDOW_MS) {
                f.count = 0;
                f.lockedUntil = 0;
            }
            f.count++;
            f.lastAt = now;
            if (f.count >= LOCK_THRESHOLD) f.lockedUntil = now + LOCK_MS;
        }
    }

    /** 登录成功：清零该 账号+IP 的失败计数与锁定 */
    public void onSuccess(String username, String ip) {
        failCounts.remove(key(username, ip));
    }

    /**
     * 生成一张字符图形验证码（4 位，已剔除易混淆字符）
     * <p>
     * 不用算术码：easy-captcha 的 ArithmeticCaptcha 依赖 Nashorn 脚本引擎求值，
     * 该引擎自 JDK15 起移除，JDK17 下 getEngineByName 返回 null 会 NPE。
     * @return {captchaId, img}，img 为可直接放入 img src 的 data URI
     */
    public Map<String, Object> issue() {
        SpecCaptcha captcha = new SpecCaptcha(130, 48, 4);
        String answer = captcha.text();   // 必须先取答案再渲染
        String id = UUID.randomUUID().toString().replace("-", "");
        captchaStore.put(id, new Cap(answer, System.currentTimeMillis() + CAPTCHA_TTL_MS));
        // 懒清理过期验证码（量很小，随发码顺带扫）
        long now = System.currentTimeMillis();
        captchaStore.entrySet().removeIf(e -> now > e.getValue().expireAt());

        String img = captcha.toBase64();
        if (!img.startsWith("data:")) img = "data:image/png;base64," + img;

        Map<String, Object> m = new HashMap<>();
        m.put("captchaId", id);
        m.put("img", img);
        return m;
    }

    /**
     * 校验验证码（一次性：无论对错验完即删，防复用）
     * @return true=通过；false=缺失/过期/错误
     */
    public boolean verify(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) return false;
        Cap cap = captchaStore.remove(captchaId);
        if (cap == null || System.currentTimeMillis() > cap.expireAt()) return false;
        return cap.answer().equalsIgnoreCase(input.trim());
    }
}
