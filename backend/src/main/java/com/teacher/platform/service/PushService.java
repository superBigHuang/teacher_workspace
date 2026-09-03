package com.teacher.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息推送服务：企业微信群机器人 / 钉钉群机器人（加签可选）/ Bark（iOS）三渠道，
 * 配置存 setting（push_* 键，per-user），发送记录写 push_log 按 (用户,渠道,业务键) 幂等去重。
 * 定时线程无 HTTP 请求上下文，故所有方法显式传 uid、不继承 BaseController。
 * 推送失败只记日志、不抛异常（一个渠道挂不影响其他渠道与业务）；文案统一含"班主任"三字，
 * 兼容钉钉机器人"关键词"安全设置。
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);
    /** 本期支持的推送渠道（配置判定与逐渠道发送用） */
    private static final List<String> CHANNELS = List.of("wecom", "dingtalk", "bark");
    /** Bark 官方服务器（用户未填自建服务器时用） */
    private static final String BARK_DEFAULT = "https://api.day.app";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public PushService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读用户设置项（不存在/异常返回空串） */
    public String setting(Long uid, String k) {
        try {
            String v = jdbc.queryForObject("SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 事件开关：解析 push_events JSON（{todo:1,achievement:1,leave:0,...}）。
     * 缺省（未配置/键缺失/JSON 损坏）：待办与成就早报默认开，其余事件默认关。
     */
    public boolean eventOn(Long uid, String event) {
        boolean def = "todo".equals(event) || "achievement".equals(event);
        String json = setting(uid, "push_events");
        if (json.isBlank()) return def;
        try {
            Map<?, ?> m = objectMapper.readValue(json, Map.class);
            Object v = m.get(event);
            if (v == null) return def;
            return asInt(v) == 1;
        } catch (Exception e) {
            return def;
        }
    }

    /** 渠道是否已配置（webhook/key 非空即视为可用） */
    private boolean configured(Long uid, String channel) {
        return switch (channel) {
            case "wecom" -> !setting(uid, "push_wecom_url").isBlank();
            case "dingtalk" -> !setting(uid, "push_dingtalk_url").isBlank();
            case "bark" -> !setting(uid, "push_bark_key").isBlank();
            default -> false;
        };
    }

    /**
     * 向某用户所有已配置渠道推送一条消息。
     * 流程（每渠道独立 try/catch）：总开关 push_enabled 非 1 直接返回 →
     * bizRef 非空且 push_log 已有 (uid,channel,bizRef) 则跳过（幂等）→
     * 发送成功后写 push_log（主 bizRef + extraRefs 明细键，成就早报去重用）。
     *
     * @param bizRef    业务幂等键（如 todo-daily:2026-09-03）；null/空=不去重（测试消息不用此入口）
     * @param extraRefs 附加明细幂等键（如成就早报每条 ach:saId:tier），随主消息发送成功一并落库
     */
    public void pushToUser(Long uid, String title, String content, String bizRef, List<String> extraRefs) {
        if (!"1".equals(setting(uid, "push_enabled"))) return;
        for (String ch : CHANNELS) {
            if (!configured(uid, ch)) continue;
            try {
                if (bizRef != null && !bizRef.isBlank() && logged(uid, ch, bizRef)) continue;
                boolean ok = switch (ch) {
                    case "wecom" -> sendWecom(setting(uid, "push_wecom_url"), content);
                    case "dingtalk" -> sendDingtalk(setting(uid, "push_dingtalk_url"),
                            setting(uid, "push_dingtalk_secret"), content);
                    case "bark" -> sendBark(setting(uid, "push_bark_key"), setting(uid, "push_bark_server"), title, content);
                    default -> false;
                };
                if (ok) {
                    insertLog(uid, ch, bizRef);
                    if (extraRefs != null) {
                        for (String ref : extraRefs) {
                            if (ref != null && !ref.isBlank() && !logged(uid, ch, ref)) insertLog(uid, ch, ref);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("推送失败 uid={} channel={} bizRef={}: {}", uid, ch, bizRef, e.toString());
            }
        }
    }

    /** 无附加明细键的推送（待办汇总等） */
    public void pushToUser(Long uid, String title, String content, String bizRef) {
        pushToUser(uid, title, content, bizRef, List.of());
    }

    /**
     * 测试推送：向指定渠道发一条连通性测试消息（不写 push_log、不查幂等）。
     * 返回 {ok:true} 或 {ok:false,message:错误摘要}（供前端弹窗/ElMessage 反馈老师）。
     */
    public Map<String, Object> testPush(Long uid, String channel) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (!CHANNELS.contains(channel)) {
            res.put("ok", false);
            res.put("message", "未知渠道：" + channel);
            return res;
        }
        if (!configured(uid, channel)) {
            res.put("ok", false);
            res.put("message", "该渠道尚未配置");
            return res;
        }
        String content = "🔔 测试成功，班主任工作台推送已连通 ✅";
        try {
            boolean ok = switch (channel) {
                case "wecom" -> sendWecom(setting(uid, "push_wecom_url"), content);
                case "dingtalk" -> sendDingtalk(setting(uid, "push_dingtalk_url"),
                        setting(uid, "push_dingtalk_secret"), content);
                case "bark" -> sendBark(setting(uid, "push_bark_key"), setting(uid, "push_bark_server"),
                        "班主任工作台", content);
                default -> false;
            };
            res.put("ok", ok);
            if (!ok) res.put("message", "渠道返回失败，请检查地址/密钥");
            return res;
        } catch (Exception e) {
            log.warn("测试推送失败 uid={} channel={}: {}", uid, channel, e.toString());
            res.put("ok", false);
            res.put("message", e.getMessage() == null ? "发送异常" : e.getMessage());
            return res;
        }
    }

    // ---------- 三个渠道底层发送（JDK11 HttpClient，超时 10 秒；失败抛异常带摘要） ----------

    /** 企业微信群机器人：POST webhook，text 消息；errcode=0 成功 */
    private boolean sendWecom(String webhook, String content) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "msgtype", "text", "text", Map.of("content", content)));
        HttpResponse<String> resp = postJson(webhook, body);
        return checkRobotErr(resp);
    }

    /**
     * 钉钉群机器人：secret 非空时按官方加签算法拼 timestamp/sign 参数
     * （HmacSHA256(timestamp+"\n"+secret) → Base64 → URLEncode）；errcode=0 成功。
     */
    private boolean sendDingtalk(String webhook, String secret, String content) throws Exception {
        String url = webhook;
        if (secret != null && !secret.isBlank()) {
            long ts = System.currentTimeMillis() / 1000;
            String stringToSign = ts + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
                    StandardCharsets.UTF_8);
            url += (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + sign;
        }
        String body = objectMapper.writeValueAsString(Map.of(
                "msgtype", "text", "text", Map.of("content", content)));
        HttpResponse<String> resp = postJson(url, body);
        return checkRobotErr(resp);
    }

    /** Bark（iOS）：GET {server}/{key}/{标题}/{内容}；HTTP 2xx 视为成功 */
    private boolean sendBark(String key, String server, String title, String body) throws Exception {
        String base = (server == null || server.isBlank()) ? BARK_DEFAULT : server.trim().replaceAll("/+$", "");
        String url = base + "/" + enc(key) + "/" + enc(title) + "/" + enc(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("Bark 返回 HTTP " + resp.statusCode());
        }
        return true;
    }

    /** POST application/json 工具方法 */
    private HttpResponse<String> postJson(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 企微/钉钉返回 JSON 校验：errcode 存在且非 0 时抛错（把渠道 errmsg 透传给老师） */
    private boolean checkRobotErr(HttpResponse<String> resp) throws Exception {
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("机器人返回 HTTP " + resp.statusCode());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = objectMapper.readValue(resp.body(), Map.class);
        Object errcode = m.get("errcode");
        if (errcode != null && asInt(errcode) != 0) {
            Object errmsg = m.get("errmsg");
            throw new RuntimeException(errmsg == null ? "发送失败" : String.valueOf(errmsg));
        }
        return true;
    }

    // ---------- push_log 幂等 ----------

    private boolean logged(Long uid, String channel, String bizRef) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM push_log WHERE user_id=? AND channel=? AND biz_ref=?",
                Integer.class, uid, channel, bizRef);
        return n != null && n > 0;
    }

    private void insertLog(Long uid, String channel, String bizRef) {
        jdbc.update("INSERT INTO push_log(user_id,channel,biz_ref,ok) VALUES(?,?,?,1)",
                uid, channel, bizRef);
    }

    private String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}
