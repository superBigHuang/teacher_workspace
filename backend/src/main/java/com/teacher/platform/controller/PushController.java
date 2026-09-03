package com.teacher.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.platform.common.Result;
import com.teacher.platform.service.PushService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 消息推送配置接口：渠道（企业微信/钉钉/Bark）webhook 与密钥只存不明取
 * （GET 仅回"是否已配置 + 后 4 位掩码"，照抄 AiController 的 __clear__ 模式），
 * 事件开关存 push_events JSON；测试推送见 PushService.testPush。
 */
@RestController
@RequestMapping("/api/push")
public class PushController extends BaseController {

    /** 事件键与缺省值：待办/成就早报本期做（默认开），其余留开关位（默认关，即将支持） */
    private static final String[] EVENT_KEYS = {"todo", "achievement", "leave", "score", "pet", "daily"};

    private final PushService pushService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PushController(PushService pushService, JdbcTemplate jdbc) {
        this.pushService = pushService;
        this.jdbc = jdbc;
    }

    /** 推送配置状态（绝不返回完整 webhook/key，仅掩码） */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Long uid = uid();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", "1".equals(pushService.setting(uid, "push_enabled")));
        res.put("events", events(uid));

        String wecom = pushService.setting(uid, "push_wecom_url");
        String ding = pushService.setting(uid, "push_dingtalk_url");
        String dingSecret = pushService.setting(uid, "push_dingtalk_secret");
        String barkKey = pushService.setting(uid, "push_bark_key");
        res.put("wecom", Map.of("configured", !wecom.isBlank(), "hint", mask(wecom)));
        res.put("dingtalk", Map.of("configured", !ding.isBlank(), "hasSecret", !dingSecret.isBlank(),
                "hint", mask(ding)));
        Map<String, Object> bark = new LinkedHashMap<>();
        bark.put("configured", !barkKey.isBlank());
        bark.put("hint", mask(barkKey));
        bark.put("server", pushService.setting(uid, "push_bark_server"));
        res.put("bark", bark);
        return Result.ok(res);
    }

    /**
     * 保存配置。enabled/events/barkServer 正常 upsert；
     * 密钥类（wecomUrl/dingtalkUrl/dingtalkSecret/barkKey）：空串=不修改，"__clear__"=删除，非空=覆盖。
     */
    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        if (body.get("enabled") != null) {
            upsertSetting(uid, "push_enabled", Boolean.TRUE.equals(body.get("enabled")) ? "1" : "0");
        }
        Object events = body.get("events");
        if (events instanceof Map<?, ?> em) {
            try {
                upsertSetting(uid, "push_events", objectMapper.writeValueAsString(em));
            } catch (Exception ignore) { /* 事件 JSON 损坏不影响密钥保存 */ }
        }
        secretField(uid, "push_wecom_url", str(body.get("wecomUrl")));
        secretField(uid, "push_dingtalk_url", str(body.get("dingtalkUrl")));
        secretField(uid, "push_dingtalk_secret", str(body.get("dingtalkSecret")));
        secretField(uid, "push_bark_key", str(body.get("barkKey")));
        // Bark 自建服务器非密钥：空串=清除回退官方服务器
        String barkServer = str(body.get("barkServer"));
        if (!barkServer.isEmpty()) upsertSetting(uid, "push_bark_server", barkServer);
        else if (body.containsKey("barkServer")) jdbc.update("DELETE FROM setting WHERE user_id=? AND k='push_bark_server'", uid);
        return Result.ok();
    }

    /** 测试推送：body {channel: wecom/dingtalk/bark}，返回 {ok, message} */
    @PostMapping("/test")
    public Result<Map<String, Object>> test(@RequestBody Map<String, Object> body) {
        return Result.ok(pushService.testPush(uid(), str(body.get("channel"))));
    }

    /** 解析 push_events JSON 为完整事件开关 Map（缺省：todo/achievement=1，其余=0） */
    private Map<String, Object> events(Long uid) {
        Map<String, Object> res = new LinkedHashMap<>();
        Map<?, ?> saved = Map.of();
        String json = pushService.setting(uid, "push_events");
        if (!json.isBlank()) {
            try { saved = objectMapper.readValue(json, Map.class); } catch (Exception ignore) { /* 损坏按缺省 */ }
        }
        for (String k : EVENT_KEYS) {
            boolean def = "todo".equals(k) || "achievement".equals(k);
            Object v = saved.get(k);
            res.put(k, v == null ? (def ? 1 : 0) : (asInt(v) == 1 ? 1 : 0));
        }
        return res;
    }

    /** 密钥字段统一处理：空串=不动，__clear__=删除，非空=upsert */
    private void secretField(Long uid, String key, String v) {
        if ("__clear__".equals(v)) {
            jdbc.update("DELETE FROM setting WHERE user_id=? AND k=?", uid, key);
        } else if (!v.isEmpty()) {
            upsertSetting(uid, key, v);
        }
    }

    /** 掩码：仅保留后 4 位（空值返回空串） */
    private String mask(String v) {
        if (v == null || v.isBlank()) return "";
        String tail = v.length() <= 4 ? v : v.substring(v.length() - 4);
        return "••••" + tail;
    }

    private void upsertSetting(Long uid, String k, String v) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM setting WHERE user_id=? AND k=?",
                Integer.class, uid, k);
        if (n != null && n > 0) {
            jdbc.update("UPDATE setting SET v=? WHERE user_id=? AND k=?", v, uid, k);
        } else {
            jdbc.update("INSERT INTO setting(user_id,k,v) VALUES(?,?,?)", uid, k, v);
        }
    }

    private int asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
