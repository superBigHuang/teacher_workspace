package com.teacher.platform.auth;

import com.wf.captcha.SpecCaptcha;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录验证码服务（内存态，不依赖 Redis）
 * <p>
 * 两道闸门：
 * 1. 失败计数：同一 账号+IP 在 FAIL_WINDOW_MS 窗口内连续失败达 FAIL_THRESHOLD 次后，
 *    登录必须携带有效验证码；登录成功即清零。
 * 2. 验证码存储：字符图形验证码（easy-captcha），一次性使用（验完即删），TTL 过期作废。
 * <p>
 * 说明：阈值验证码是机器人/暴力尝试的拦截手段，非强安全防线；内网单机部署足够。
 */
@Service
public class CaptchaService {

    /** 连续失败多少次后要求验证码 */
    private static final int FAIL_THRESHOLD = 3;
    /** 失败计数窗口：距上次失败超过该时长则计数作废 */
    private static final long FAIL_WINDOW_MS = 30 * 60 * 1000L;
    /** 验证码有效期 */
    private static final long CAPTCHA_TTL_MS = 5 * 60 * 1000L;

    /** key = username(小写)|IP → 失败计数 */
    private final Map<String, FailCnt> failCounts = new ConcurrentHashMap<>();
    /** captchaId → 答案 + 过期时间 */
    private final Map<String, Cap> captchaStore = new ConcurrentHashMap<>();

    private static final class FailCnt {
        int count;
        long lastAt;
    }

    private record Cap(String answer, long expireAt) {}

    private String key(String username, String ip) {
        return (username == null ? "" : username.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }

    /** 当前是否需要验证码：窗口内失败次数达阈值 */
    public boolean needCaptcha(String username, String ip) {
        FailCnt f = failCounts.get(key(username, ip));
        if (f == null) return false;
        synchronized (f) {
            if (System.currentTimeMillis() - f.lastAt > FAIL_WINDOW_MS) return false;
            return f.count >= FAIL_THRESHOLD;
        }
    }

    /** 登录失败：计数 +1（窗口外重新计） */
    public void onFail(String username, String ip) {
        FailCnt f = failCounts.computeIfAbsent(key(username, ip), k -> new FailCnt());
        synchronized (f) {
            if (System.currentTimeMillis() - f.lastAt > FAIL_WINDOW_MS) f.count = 0;
            f.count++;
            f.lastAt = System.currentTimeMillis();
        }
    }

    /** 登录成功：清零该 账号+IP 的失败计数 */
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
        if (!img.startsWith("data:")) img = "data:image/gif;base64," + img;

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
