package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.AiService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 助手接口：配置查看/保存（API Key 只存不明取）、测试连接、评语/寄语生成。
 * 用户配置存 setting 表按 uid 隔离；生成失败由 AiService 自动降级规则模板。
 */
@RestController
@RequestMapping("/api/ai")
public class AiController extends BaseController {

    private final AiService aiService;
    private final JdbcTemplate jdbc;

    public AiController(AiService aiService, JdbcTemplate jdbc) {
        this.aiService = aiService;
        this.jdbc = jdbc;
    }

    /** 当前生效配置（绝不返回 key 明文，仅掩码与是否已配置） */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Long uid = uid();
        Map<String, Object> cfg = aiService.effectiveConfig(uid);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", cfg.get("enabled"));
        res.put("baseUrl", cfg.get("baseUrl"));
        res.put("model", cfg.get("model"));
        res.put("temperature", cfg.get("temperature"));
        res.put("hasDefaultKey", cfg.get("hasDefaultKey"));
        boolean hasUserKey = (boolean) cfg.get("hasUserKey");
        res.put("hasUserKey", hasUserKey);
        // 用户自配 key 显示后 4 位掩码；默认 key 不下发任何片段
        res.put("keyHint", hasUserKey ? mask(userSetting(uid, "ai_api_key")) : "");
        return Result.ok(res);
    }

    /**
     * 保存用户配置。baseUrl/model/temperature 留空 = 清除用户覆盖回退默认；
     * apiKey：空串=不修改，"__clear__"=清除自配 key 回退默认服务。
     */
    @PutMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        upsertOrClear(uid, "ai_enabled", str(body.get("enabled")));
        upsertOrClear(uid, "ai_base_url", str(body.get("baseUrl")));
        upsertOrClear(uid, "ai_model", str(body.get("model")));
        upsertOrClear(uid, "ai_temperature", str(body.get("temperature")));
        String apiKey = str(body.get("apiKey"));
        if ("__clear__".equals(apiKey)) {
            jdbc.update("DELETE FROM setting WHERE user_id=? AND k='ai_api_key'", uid);
        } else if (!apiKey.isEmpty()) {
            upsertSetting(uid, "ai_api_key", apiKey);
        }
        return Result.ok();
    }

    /** 测试连接：用当前生效配置发 ping，返回 {ok, message, model} */
    @PostMapping("/test")
    public Result<Map<String, Object>> test() {
        return Result.ok(aiService.testConnection(uid()));
    }

    /**
     * 统一生成：scene=comment 期末评语 / message 通知单寄语。
     * 返回 {content, source:"ai"|"template", model?, error?}，AI 失败已自动降级模板。
     */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        String scene = str(body.get("scene"));
        if (!"comment".equals(scene) && !"message".equals(scene)) {
            return Result.fail("scene 仅支持 comment / message");
        }
        Object sid = body.get("studentId");
        if (sid == null) return Result.fail("缺少 studentId");
        Long studentId = ((Number) sid).longValue();
        return Result.ok(aiService.generate(uid(), scene, studentId));
    }

    /** key 掩码：仅保留后 4 位 */
    private String mask(String key) {
        if (key == null || key.isBlank()) return "";
        String tail = key.length() <= 4 ? key : key.substring(key.length() - 4);
        return "••••" + tail;
    }

    private String userSetting(Long uid, String k) {
        try {
            String v = jdbc.queryForObject("SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** 值非空则写入，空串则删除该用户覆盖（回退默认服务） */
    private void upsertOrClear(Long uid, String k, String v) {
        if (v == null || v.isEmpty()) {
            jdbc.update("DELETE FROM setting WHERE user_id=? AND k=?", uid, k);
        } else {
            upsertSetting(uid, k, v);
        }
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

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
