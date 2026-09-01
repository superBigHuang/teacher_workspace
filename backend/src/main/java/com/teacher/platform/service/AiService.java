package com.teacher.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.*;

/**
 * AI 接入层：OpenAI 兼容 Chat Completions 协议（RestClient，零新依赖）。
 * 两级配置——用户 setting 覆盖 > application.yml 默认服务；未启用/未配 key/调用失败
 * 一律降级到 {@link CommentGenerator} 规则模板，功能永不不可用。
 * 适配推理模型（讯飞星火 spark-x2.5-4b）：正文在 content，思考在 reasoning_content，
 * content 为空时从思考内容兜底提取，max_tokens 按场景给足（思考共享配额）。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;
    private final CommentGenerator generator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ai.defaults.base-url:}")
    private String defBaseUrl;
    @Value("${ai.defaults.api-key:}")
    private String defApiKey;
    @Value("${ai.defaults.model:}")
    private String defModel;
    @Value("${ai.defaults.temperature:0.7}")
    private double defTemperature;
    @Value("${ai.defaults.timeout-ms:60000}")
    private int timeoutMs;

    public AiService(JdbcTemplate jdbc, TermStatsService termStats, CommentGenerator generator) {
        this.jdbc = jdbc;
        this.termStats = termStats;
        this.generator = generator;
    }

    /** 生效配置（用户值优先，空则回退默认）；enabled 默认 true（默认服务开箱即用） */
    public Map<String, Object> effectiveConfig(Long uid) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        String enabled = setting(uid, "ai_enabled", "");
        cfg.put("enabled", !"false".equalsIgnoreCase(enabled));
        cfg.put("baseUrl", firstNonBlank(setting(uid, "ai_base_url", ""), defBaseUrl));
        cfg.put("apiKey", firstNonBlank(setting(uid, "ai_api_key", ""), defApiKey));
        cfg.put("model", firstNonBlank(setting(uid, "ai_model", ""), defModel));
        String userTemp = setting(uid, "ai_temperature", "");
        cfg.put("temperature", userTemp.isBlank() ? defTemperature : Double.parseDouble(userTemp));
        cfg.put("hasDefaultKey", !defApiKey.isBlank());
        cfg.put("hasUserKey", !setting(uid, "ai_api_key", "").isBlank());
        return cfg;
    }

    /**
     * 统一生成入口。scene=comment 期末评语（150~250字）/ message 通知单寄语（30~80字）。
     * 流程：取学期素材 → 模板先生成好保底文本 → AI 可用则调用并走兜底链 → 任何异常落模板。
     * 返回 {content, source:"ai"|"template", model?, error?}。
     */
    public Map<String, Object> generate(Long uid, String scene, Long studentId) {
        Map<String, Object> material = termStats.material(uid, studentId);
        if (material == null) {
            return result("", "template", null, "学生不存在或无素材");
        }
        boolean comment = !"message".equals(scene);
        // 保底文本先生成，AI 任何环节失败都能用
        String fallback = comment ? generator.comment(material) : generator.message(material);

        Map<String, Object> cfg = effectiveConfig(uid);
        boolean ready = (boolean) cfg.get("enabled")
                && !str(cfg.get("apiKey")).isBlank()
                && !str(cfg.get("baseUrl")).isBlank()
                && !str(cfg.get("model")).isBlank();
        if (!ready) {
            return result(fallback, "template", null, "AI 未启用或未配置 API Key");
        }

        String system = comment ? COMMENT_SYSTEM : MESSAGE_SYSTEM;
        String userPrompt;
        try {
            userPrompt = comment ? commentPrompt(material) : messagePrompt(material);
        } catch (Exception e) {
            return result(fallback, "template", null, "素材序列化失败：" + e.getMessage());
        }
        int maxTokens = comment ? 3000 : 1500;
        // 温度：用户显式配置则用用户值，否则按场景给（评语 0.8 / 寄语 0.6）
        double temperature = ((Number) cfg.get("temperature")).doubleValue();
        if (setting(uid, "ai_temperature", "").isBlank()) {
            temperature = comment ? 0.8 : 0.6;
        }
        long t0 = System.currentTimeMillis();
        try {
            String content = chat(cfg, system, userPrompt, maxTokens, temperature);
            if (content != null && !content.isBlank()) {
                log.info("[ai] scene={} student={} ok {}ms source=ai", scene, studentId, System.currentTimeMillis() - t0);
                return result(content.trim(), "ai", str(cfg.get("model")), null);
            }
            log.warn("[ai] scene={} student={} 空响应，降级模板", scene, studentId);
            return result(fallback, "template", null, "AI 返回内容为空");
        } catch (Exception e) {
            log.warn("[ai] scene={} student={} 调用失败 {}ms：{}", scene, studentId,
                    System.currentTimeMillis() - t0, e.getMessage());
            return result(fallback, "template", null, "AI 调用失败：" + brief(e.getMessage()));
        }
    }

    /** 测试连接：发一条 ping 短请求（max_tokens 50），返回 {ok, message, model} */
    public Map<String, Object> testConnection(Long uid) {
        Map<String, Object> cfg = effectiveConfig(uid);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("model", str(cfg.get("model")));
        if (!(boolean) cfg.get("enabled")) {
            res.put("ok", false);
            res.put("message", "AI 已关闭（当前走规则模板）");
            return res;
        }
        if (str(cfg.get("apiKey")).isBlank()) {
            res.put("ok", false);
            res.put("message", "未配置 API Key（默认服务 key 未注入，且未自配 key）");
            return res;
        }
        try {
            // max_tokens 给 300：推理模型思考与正文共享配额，50 会被思考占满导致正文为空
            String content = chat(cfg, "你是一个连接测试助手。", "回复\"连接正常\"四个字即可。", 300, 0.7);
            res.put("ok", content != null && !content.isBlank());
            res.put("message", content == null || content.isBlank() ? "服务已连通但返回为空" : content.trim());
        } catch (Exception e) {
            res.put("ok", false);
            res.put("message", "连接失败：" + brief(e.getMessage()));
        }
        return res;
    }

    /**
     * 调用 OpenAI 兼容 /chat/completions。
     * 响应取 choices[0].message.content；为空时从推理模型扩展字段 reasoning_content
     * 中提取"最终输出/评语：/寄语："之后的正文；再为空返回 null（上层降级模板）。
     */
    @SuppressWarnings("unchecked")
    private String chat(Map<String, Object> cfg, String system, String user, int maxTokens, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.get("model"));
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(10).toMillis());
        factory.setReadTimeout(timeoutMs);

        Map<String, Object> resp = RestClient.builder()
                .requestFactory(factory)
                .build()
                .post()
                .uri(endpoint(str(cfg.get("baseUrl"))))
                .header("Authorization", "Bearer " + str(cfg.get("apiKey")))
                .header("lora_id", "0")   // 讯飞星火示例要求，保险保留；其他服务商忽略自定义头
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (resp == null) return null;
        Object choicesObj = resp.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) return null;
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> choice)) return null;
        Object msgObj = choice.get("message");
        if (!(msgObj instanceof Map<?, ?> message)) return null;
        String content = cleanContent(str(message.get("content")));
        if (content != null && !content.isBlank()) return content;
        // 推理模型兜底：思考过程里提取最终正文
        return extractFromReasoning(str(message.get("reasoning_content")));
    }

    /**
     * 正文清洗：推理模型行为有波动，实测两种污染形态——
     * A) 正文后接"检查字数…"等思考尾巴；B) 整篇思考写进 content，正文埋在"草稿："之后。
     * 处理：先取最后一个正文起始标记之后的文本，再切掉思考性尾巴，去包裹引号；
     * 清洗后仍超长（>600 字，评语/寄语正常均不超过 300 字）视为污染未除净，返回 null 走兜底。
     */
    private String cleanContent(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s;
        // 1) 正文起始标记（取最后出现的一个，避开思考中对标记词的引用）
        String[] startMarkers = {"最终评语", "最终输出", "评语正文", "最终寄语", "寄语正文", "草稿：", "草稿:"};
        int start = -1;
        for (String mk : startMarkers) {
            int idx = t.lastIndexOf(mk);
            if (idx >= 0) start = Math.max(start, idx + mk.length());
        }
        if (start > 0) t = t.substring(start);
        // 2) 切掉思考性尾巴（取最早出现的位置）
        String[] tailMarkers = {"检查字数", "统计字数", "字数统计", "字数检查", "字数：", "字数:",
                "我们来数", "我们来统计", "我们再数", "---", "说明：", "备注："};
        int cut = t.length();
        for (String mk : tailMarkers) {
            int idx = t.indexOf(mk);
            if (idx >= 0 && idx < cut) cut = idx;
        }
        t = t.substring(0, cut).trim();
        // 3) 去首尾包裹引号、序号前缀
        t = t.replaceAll("^[“”\"'\\s\\-*0-9.、）)]+", "").replaceAll("[“”\"'\\s]+$", "").trim();
        if (t.isBlank() || t.length() > 600) return null;
        return t;
    }

    /** 拼接请求地址：baseUrl 不以 /vN 结尾时自动补 /v1（兼容 deepseek 等不带版本号的写法） */
    private String endpoint(String baseUrl) {
        String u = baseUrl.replaceAll("/+$", "");
        if (!u.matches(".*\\/v\\d+$")) u = u + "/v1";
        return u + "/chat/completions";
    }

    /** 从推理思考内容中提取最终正文：取最后一个标记之后的文本 */
    private String extractFromReasoning(String reasoning) {
        if (reasoning == null || reasoning.isBlank()) return null;
        String[] markers = {"最终输出", "最终评语", "最终寄语", "评语：", "评语:", "寄语：", "寄语:", "正文：", "正文:"};
        int cut = -1;
        for (String mk : markers) {
            int idx = reasoning.lastIndexOf(mk);
            if (idx > cut) cut = idx + mk.length();
        }
        String text = cut > 0 ? reasoning.substring(cut) : reasoning;
        // 去掉思考中常见的引号、序号前缀与换行
        text = text.trim().replaceAll("^[：:\\s\\-\\*\"'“”]+", "").replaceAll("[\"'“”]+$", "").trim();
        return text.isBlank() ? null : text;
    }

    // ---------- prompt 组装（素材用中文字段名，便于模型准确引用） ----------

    /** 评语 prompt：学业/考勤/作业/纪律/闪光点五段素材（LinkedHashMap 允许 null 值，统一转"无"） */
    private String commentPrompt(Map<String, Object> m) throws Exception {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("姓名", m.get("name"));
        Map<String, Object> study = new LinkedHashMap<>();
        study.put("班级排名", nz(m.get("rank")));
        study.put("排名变化_正数为上升负数为下降", nz(m.get("rankChange")));
        study.put("位次段", str(m.get("rankSegment")).isBlank() ? "无" : m.get("rankSegment"));
        study.put("最近考试总分", nz(m.get("totalScore")));
        data.put("学业", study);
        Map<String, Object> attend = new LinkedHashMap<>();
        attend.put("迟到次数", nz(m.get("late")));
        attend.put("缺勤次数", nz(m.get("absent")));
        attend.put("请假天数", nz(m.get("leaveDays")));
        data.put("考勤", attend);
        Map<String, Object> hw = new LinkedHashMap<>();
        hw.put("未交次数", nz(m.get("homeworkMissing")));
        hw.put("最近未交科目", str(m.get("lastMissingSubject")).isBlank() ? "无" : m.get("lastMissingSubject"));
        data.put("作业", hw);
        Map<String, Object> disc = new LinkedHashMap<>();
        disc.put("违纪条数", nz(m.get("discipline")));
        data.put("纪律", disc);
        Map<String, Object> shine = new LinkedHashMap<>();
        shine.put("班委职务", str(m.get("roles")).isBlank() ? "无" : m.get("roles"));
        shine.put("值日参与次数", nz(m.get("dutyCount")));
        shine.put("家校沟通次数", nz(m.get("commCount")));
        shine.put("本学期成就枚数", nz(m.get("achievementCount")));
        shine.put("代表成就", str(m.get("topAchievement")).isBlank() ? "无" : m.get("topAchievement"));
        data.put("闪光点", shine);
        return "请根据以下学生本学期素材写期末评语：" + objectMapper.writeValueAsString(data);
    }

    /** prompt 素材值：null 转"无"（缺考试数据时 rank/rankChange/totalScore 为 null） */
    private Object nz(Object o) {
        return o == null ? "无" : o;
    }

    /** 寄语 prompt：考试成绩 + 日常表现 */
    @SuppressWarnings("unchecked")
    private String messagePrompt(Map<String, Object> m) throws Exception {
        String examName = "本次考试";
        if (m.get("exams") instanceof List<?> exams && !exams.isEmpty()
                && exams.get(0) instanceof Map<?, ?> em) {
            examName = str(em.get("name"));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("姓名", m.get("name"));
        data.put("考试名", examName);
        data.put("总分", nz(m.get("totalScore")));
        data.put("班级排名", nz(m.get("rank")));
        data.put("排名变化_正数为上升负数为下降", nz(m.get("rankChange")));
        Map<String, Object> daily = new LinkedHashMap<>();
        daily.put("迟到次数", nz(m.get("late")));
        daily.put("作业未交次数", nz(m.get("homeworkMissing")));
        daily.put("违纪条数", nz(m.get("discipline")));
        daily.put("请假天数", nz(m.get("leaveDays")));
        data.put("日常表现", daily);
        return "请写教师寄语：" + objectMapper.writeValueAsString(data);
    }

    private static final String COMMENT_SYSTEM = """
            你是一位经验丰富的中学班主任，正在为学生写期末评语。要求：
            1) 用第二人称"你"称呼学生，语气真诚温暖、鼓励为主，符合班主任口吻；
            2) 评语正文 150~250 字；
            3) 必须自然引用素材中的具体事实（班级排名、排名变化、迟到次数、作业情况、职务、家校沟通等），数据要准确，不许编造素材中没有的事实，不许空泛套话；
            4) 结构：先肯定优点与进步，再委婉指出需要改进之处，最后表达期望与鼓励；
            5) 素材中表现不佳的数据（如迟到、未交作业）要点到但措辞温和，不贴标签；
            6) 直接输出评语正文，不要输出思考过程、字数统计、标题、序号或任何说明。
            """;

    private static final String MESSAGE_SYSTEM = """
            你是一位中学班主任，要在学生的成绩通知单"教师寄语"栏写一段话，学生和家长都会看到。要求：
            1) 30~80 字，简短温暖；
            2) 结合素材中的成绩进退步与在校表现，进步的给予肯定、退步的委婉鼓励，不批评指责；
            3) 可以对家长提一句配合建议（如"请家长多关注作业完成情况"）；
            4) 直接输出寄语正文，不要思考过程、标题或说明。
            """;

    // ---------- 工具 ----------

    private Map<String, Object> result(String content, String source, String model, String error) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", content);
        r.put("source", source);
        if (model != null) r.put("model", model);
        if (error != null) r.put("error", error);
        return r;
    }

    /** 错误信息截断，避免把服务商整页 HTML/长堆栈返回前端 */
    private String brief(String msg) {
        if (msg == null) return "未知错误";
        String s = msg.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private String setting(Long uid, String k, String def) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
