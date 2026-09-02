package com.teacher.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teacher.platform.common.Result;
import com.teacher.platform.service.AchievementScanner;
import com.teacher.platform.service.TermStatsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 学生成就系统（PSN 奖杯式徽章墙）：
 * 成就定义（内置 user_id=0 全局共享 UNION 可见 + 老师自定义 per-user）、
 * 自动扫描颁发/进阶（幂等、只升不降，唯一键 uk_sa）、手动授予/撤销、学生徽章墙、成就榜、座位奖牌。
 * 奖牌档位：可进阶成就学生当前档在 student_achievement.tier（NULL=老数据回退 achievement_def.tier）；
 * 大满贯(COLLECTOR)铜银金点数 ×5（50/150/300），PLATINUM 老白金保留 100。
 * 全部接口按 uid 隔离。
 */
@RestController
@RequestMapping("/api/achievement")
public class AchievementController extends BaseController {

    private final JdbcTemplate jdbc;
    private final AchievementScanner scanner;
    private final TermStatsService termStats;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 各等级基础点数：铜10/银30/金60/白金100 */
    private static final Map<String, Integer> TIER_POINTS = Map.of(
            "BRONZE", 10, "SILVER", 30, "GOLD", 60, "PLATINUM", 100);

    public AchievementController(JdbcTemplate jdbc, AchievementScanner scanner, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.scanner = scanner;
        this.termStats = termStats;
    }

    /** 当前学期标识（term_start~term_end，与 comment 表一致） */
    private String currentTerm(Long uid) {
        String[] r = termStats.termRange(uid);
        return r[0] + "~" + r[1];
    }

    /** 本班学生数（稀有度分母） */
    private int classSize(Long uid) {
        Long classId = termStats.homeroomClassId(uid);
        if (classId == null) return 0;
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE user_id=? AND class_id=?",
                Integer.class, uid, classId);
        return c == null ? 0 : c;
    }

    /**
     * 定义列表（内置 + 本账号自定义）：含 points、本学期本班获得人数、
     * 稀有度（按本班获得率：<5% 传说 / 5~20% 史诗 / 20~50% 稀有 / >50% 常见）、获得名单。
     */
    @GetMapping("/defs")
    public Result<List<Map<String, Object>>> defs() {
        Long uid = uid();
        String term = currentTerm(uid);
        int size = classSize(uid);
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT d.*, (SELECT COUNT(*) FROM student_achievement sa " +
                        "WHERE sa.def_id=d.id AND sa.user_id=? AND sa.term=?) earnedCount " +
                        "FROM achievement_def d WHERE d.user_id=0 OR d.user_id=? ORDER BY d.kind DESC, d.id",
                uid, term, uid);
        attachEarners(uid, term, list);
        return Result.ok(list);
    }

    /** 为定义列表填充 points/稀有度/获得名单（/defs 与 /board 共用） */
    private void attachEarners(Long uid, String term, List<Map<String, Object>> list) {
        int size = classSize(uid);
        Map<Long, List<Map<String, Object>>> earners = new HashMap<>();
        jdbc.queryForList(
                "SELECT sa.id recordId, sa.def_id defId, sa.student_id studentId, s.name, " +
                        "sa.awarded_at awardedAt, sa.source, sa.remark, COALESCE(sa.tier, d.tier) tier " +
                        "FROM student_achievement sa JOIN student s ON s.id=sa.student_id " +
                        "JOIN achievement_def d ON d.id=sa.def_id " +
                        "WHERE sa.user_id=? AND sa.term=? ORDER BY sa.awarded_at DESC, sa.id DESC", uid, term)
                .forEach(r -> earners.computeIfAbsent(((Number) r.get("defId")).longValue(), k -> new ArrayList<>()).add(r));
        for (Map<String, Object> d : list) {
            Long defId = ((Number) d.get("id")).longValue();
            int earned = ((Number) d.get("earnedCount")).intValue();
            d.put("points", defPoints(d));
            d.put("rarity", rarity(earned, size));
            d.put("earners", earners.getOrDefault(defId, new ArrayList<>()));
        }
    }

    /** 定义行展示点数：大满贯展示铜档×5=50，可进阶/固定档取基础点数（前端三档门槛另展示） */
    private int defPoints(Map<String, Object> d) {
        if ("COLLECTOR".equals(str(d.get("rule_type")))) return 50;
        return TIER_POINTS.getOrDefault(str(d.get("tier")), 0);
    }

    /** 创建自定义成就（kind=MANUAL）：名称/图标/等级/描述 */
    @PostMapping("/def")
    public Result<Map<String, Object>> createDef(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String name = str(body.get("name"));
        if (name.isBlank()) return Result.fail("成就名称不能为空");
        String tier = str(body.get("tier"));
        if (!TIER_POINTS.containsKey(tier)) tier = "BRONZE";
        jdbc.update("INSERT INTO achievement_def(user_id,name,description,icon,tier,kind,enabled,builtin) " +
                        "VALUES(?,?,?,?,?,'MANUAL',1,0)",
                uid, name, str(body.get("description")), str(body.get("icon")), tier);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Result.ok(Map.of("id", id));
    }

    /**
     * 编辑定义：内置（builtin=1）仅可改 enabled（停用/启用）与 rule_params（调阈值，可进阶传三档 tiers）；
     * 自定义可改 name/description/icon/tier/enabled。
     */
    @PutMapping("/def/{id}")
    public Result<Void> updateDef(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long uid = uid();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE id=? AND (user_id=0 OR user_id=?)", id, uid);
        if (rows.isEmpty()) return Result.fail("成就不存在");
        Map<String, Object> def = rows.get(0);
        boolean builtin = ((Number) def.get("builtin")).intValue() == 1;
        if (builtin) {
            if (body.containsKey("enabled")) {
                jdbc.update("UPDATE achievement_def SET enabled=? WHERE id=?",
                        Boolean.TRUE.equals(body.get("enabled")) || body.get("enabled") == null ? 1 : 0, id);
            }
            if (body.get("ruleParams") instanceof Map<?, ?> rp) {
                try {
                    jdbc.update("UPDATE achievement_def SET rule_params=? WHERE id=?",
                            objectMapper.writeValueAsString(rp), id);
                } catch (Exception e) {
                    return Result.fail("阈值参数格式有误");
                }
            }
        } else {
            String tier = str(body.get("tier"));
            if (!TIER_POINTS.containsKey(tier)) tier = str(def.get("tier"));
            jdbc.update("UPDATE achievement_def SET name=?, description=?, icon=?, tier=?, enabled=? WHERE id=? AND user_id=?",
                    body.containsKey("name") ? str(body.get("name")) : def.get("name"),
                    body.containsKey("description") ? str(body.get("description")) : def.get("description"),
                    body.containsKey("icon") ? str(body.get("icon")) : def.get("icon"),
                    tier,
                    body.containsKey("enabled") ? (Boolean.TRUE.equals(body.get("enabled")) ? 1 : 0) : def.get("enabled"),
                    id, uid);
        }
        return Result.ok();
    }

    /** 删除自定义成就（内置不可删）；其获得记录一并删除 */
    @DeleteMapping("/def/{id}")
    public Result<Void> deleteDef(@PathVariable Long id) {
        Long uid = uid();
        int n = jdbc.update("DELETE FROM achievement_def WHERE id=? AND user_id=? AND builtin=0", id, uid);
        if (n == 0) return Result.fail("内置成就不可删除，只能停用");
        jdbc.update("DELETE FROM student_achievement WHERE def_id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    /** 扫描全班自动成就（当前学期），幂等颁发/升级，返回新颁发清单（前端跳杯庆祝） */
    @PostMapping("/scan")
    public Result<Map<String, Object>> scan() {
        List<Map<String, Object>> newAwards = scanner.scan(uid());
        return Result.ok(Map.of("newAwards", newAwards, "count", newAwards.size()));
    }

    /**
     * 座位奖牌：座次表宠物上方挂本学期 top3。
     * 先静默扫描（保证新发/升级及时上屏，不抛错），再一条 SQL 查全班本学期奖牌，
     * 内存按有效点数（大满贯 ×5）降序、同点数按 awarded_at 倒序（SQL 序稳定保持）每生取前 3。
     * 返回 { "<studentId>": [{icon, name, tier}, ...] }。
     */
    @GetMapping("/seat-medals")
    public Result<Map<String, List<Map<String, Object>>>> seatMedals() {
        Long uid = uid();
        try {
            scanner.scan(uid);
        } catch (Exception e) {
            System.out.println("[seat-medals] 扫描跳过：" + e.getMessage());
        }
        String term = currentTerm(uid);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sa.student_id sid, d.icon icon, d.name name, d.rule_type rt, " +
                        "COALESCE(sa.tier, d.tier) tier, sa.awarded_at at " +
                        "FROM student_achievement sa " +
                        "JOIN achievement_def d ON d.id=sa.def_id " +
                        "JOIN student s ON s.id=sa.student_id AND s.user_id=sa.user_id " +
                        "WHERE sa.user_id=? AND sa.term=? ORDER BY sa.awarded_at DESC, sa.id DESC",
                uid, term);
        Map<Long, List<Map<String, Object>>> grouped = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String tier = str(r.get("tier"));
            Map<String, Object> medal = new LinkedHashMap<>();
            medal.put("icon", r.get("icon"));
            medal.put("name", r.get("name"));
            medal.put("tier", tier);
            medal.put("pts", medalPoints(str(r.get("rt")), tier));   // 排序用，返回前移除
            grouped.computeIfAbsent(((Number) r.get("sid")).longValue(), k -> new ArrayList<>()).add(medal);
        }
        Map<String, List<Map<String, Object>>> result = new HashMap<>();
        grouped.forEach((sid, list) -> {
            list.sort((a, b) -> Integer.compare((int) b.get("pts"), (int) a.get("pts")));
            list.forEach(m -> m.remove("pts"));
            result.put(String.valueOf(sid), list.size() > 3 ? new ArrayList<>(list.subList(0, 3)) : list);
        });
        return Result.ok(result);
    }

    /**
     * 学生徽章墙：累计统计（总枚数/点数/四色计数）+ 按学期分组的徽章。
     * 当前学期返回全部启用定义（获得=彩色带日期/备注/当前档，未获得 AUTO 带进度文本）；
     * 历史学期仅返回已获得徽章。
     */
    @GetMapping("/student/{studentId}")
    public Result<Map<String, Object>> studentWall(@PathVariable Long studentId) {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];

        // 累计统计（全部学期，点数按学生当前档：大满贯铜银金 ×5）
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", 0); stats.put("points", 0);
        for (String t : List.of("BRONZE", "SILVER", "GOLD", "PLATINUM")) stats.put(t.toLowerCase(), 0);
        List<Map<String, Object>> all = jdbc.queryForList(
                "SELECT d.rule_type rt, COALESCE(sa.tier, d.tier) tier FROM student_achievement sa " +
                        "JOIN achievement_def d ON d.id=sa.def_id " +
                        "WHERE sa.user_id=? AND sa.student_id=?", uid, studentId);
        int total = 0, points = 0;
        Map<String, Integer> tierCount = new HashMap<>();
        for (Map<String, Object> r : all) {
            String tier = str(r.get("tier"));
            total++;
            points += medalPoints(str(r.get("rt")), tier);
            tierCount.merge(tier, 1, Integer::sum);
        }
        stats.put("total", total);
        stats.put("points", points);
        tierCount.forEach((t, c) -> stats.put(t.toLowerCase(), c));

        // 当前学期：全部启用定义 + 获得状态
        List<Map<String, Object>> defs = jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE enabled=1 AND (user_id=0 OR user_id=?) ORDER BY kind DESC, id", uid);
        Map<Long, Map<String, Object>> earnedMap = new HashMap<>();
        jdbc.queryForList(
                "SELECT * FROM student_achievement WHERE user_id=? AND student_id=? AND term=?",
                uid, studentId, term).forEach(r -> earnedMap.put(((Number) r.get("def_id")).longValue(), r));
        // 自动规则评估（进度文本/三档门槛/当前达成档）
        Map<Long, Map<String, Object>> evalMap = new HashMap<>();
        scanner.evaluateStudent(uid, studentId).forEach(e ->
                evalMap.put(((Number) e.get("defId")).longValue(), e));

        List<Map<String, Object>> currentBadges = new ArrayList<>();
        for (Map<String, Object> d : defs) {
            Long defId = ((Number) d.get("id")).longValue();
            Map<String, Object> sa = earnedMap.get(defId);
            Map<String, Object> b = badgeFromDef(d);
            b.put("earned", sa != null);
            String tier = effTier(sa, d);
            b.put("tier", tier);
            b.put("points", medalPoints(str(d.get("rule_type")), tier));
            if (sa != null) {
                b.put("awardedAt", sa.get("awarded_at"));
                b.put("source", sa.get("source"));
                b.put("remark", sa.get("remark"));
            }
            Map<String, Object> ev = evalMap.get(defId);
            if ("AUTO".equals(str(d.get("kind"))) && ev != null) {
                // 未获得：显示入门进度；已持有：仅当评估档不低于持有档（含满档/可升档）才显示，避免回落误导
                boolean showProgress = sa == null
                        || ev.get("tier") != null && tierRank(str(ev.get("tier"))) >= tierRank(tier);
                if (showProgress && ev.get("progress") != null) b.put("progress", str(ev.get("progress")));
            }
            currentBadges.add(b);
        }

        // 历史学期：仅已获得，按学期倒序
        List<Map<String, Object>> historyTerms = new ArrayList<>();
        List<Map<String, Object>> terms = jdbc.queryForList(
                "SELECT DISTINCT term FROM student_achievement WHERE user_id=? AND student_id=? ORDER BY term DESC",
                uid, studentId);
        for (Map<String, Object> t : terms) {
            String tname = str(t.get("term"));
            if (tname.equals(term)) continue;
            List<Map<String, Object>> badges = new ArrayList<>();
            jdbc.queryForList(
                    "SELECT d.*, sa.tier saTier, sa.awarded_at awardedAt, sa.source source, sa.remark remark " +
                            "FROM student_achievement sa JOIN achievement_def d ON d.id=sa.def_id " +
                            "WHERE sa.user_id=? AND sa.student_id=? AND sa.term=? ORDER BY d.id", uid, studentId, tname)
                    .forEach(r -> {
                        Map<String, Object> b = badgeFromDef(r);
                        String tier = str(r.get("saTier"));
                        if (tier.isBlank()) tier = str(r.get("tier"));
                        b.put("tier", tier);
                        b.put("points", medalPoints(str(r.get("rule_type")), tier));
                        b.put("earned", true);
                        b.put("awardedAt", r.get("awardedAt"));
                        b.put("source", r.get("source"));
                        b.put("remark", r.get("remark"));
                        badges.add(b);
                    });
            Map<String, Object> tg = new LinkedHashMap<>();
            tg.put("term", tname);
            tg.put("badges", badges);
            historyTerms.add(tg);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termStart", range[0]);
        result.put("termEnd", range[1]);
        result.put("term", term);
        result.put("stats", stats);
        result.put("currentBadges", currentBadges);
        result.put("historyTerms", historyTerms);
        return Result.ok(result);
    }

    /** 手动颁发：{defId, studentIds:[], remark}，幂等（已获得的跳过） */
    @PostMapping("/award")
    public Result<Map<String, Object>> award(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long defId = asLong(body.get("defId"));
        String remark = str(body.get("remark"));
        if (defId == null) return Result.fail("缺少成就定义");
        Integer visible = jdbc.queryForObject(
                "SELECT COUNT(*) FROM achievement_def WHERE id=? AND enabled=1 AND (user_id=0 OR user_id=?)",
                Integer.class, defId, uid);
        if (visible == null || visible == 0) return Result.fail("成就不存在或已停用");
        String term = currentTerm(uid);
        int n = 0;
        for (Object sidObj : (Iterable<?>) body.getOrDefault("studentIds", List.of())) {
            Long sid = asLong(sidObj);
            if (sid == null) continue;
            n += jdbc.update("INSERT IGNORE INTO student_achievement(user_id,student_id,def_id,term,source,remark,awarded_at) " +
                    "VALUES(?,?,?,?, 'MANUAL', ?,?)", uid, sid, defId, term, remark.isBlank() ? null : remark, LocalDate.now());
        }
        return Result.ok(Map.of("awarded", n));
    }

    /** 撤销一条获得记录（误发回收，前端二次确认） */
    @DeleteMapping("/revoke/{recordId}")
    public Result<Void> revoke(@PathVariable Long recordId) {
        jdbc.update("DELETE FROM student_achievement WHERE id=? AND user_id=?", recordId, uid());
        return Result.ok();
    }

    /**
     * 成就榜：本班每生本学期获得枚数/点数/四色计数（按点数降序）+ 定义列表（含获得人数/稀有度/名单）。
     * 点数/四色按学生当前档（COALESCE sa.tier,d.tier）Java 聚合，大满贯铜银金 ×5。
     */
    @GetMapping("/board")
    public Result<Map<String, Object>> board() {
        Long uid = uid();
        String term = currentTerm(uid);
        Long classId = termStats.homeroomClassId(uid);

        // 排行榜：本班学生 LEFT JOIN 本学期成就，逐行内存聚合（点数规则在 Java，SQL 无法表达 ×5）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.id studentId, s.name, s.gender, s.stu_no stuNo, " +
                        "d.rule_type rt, COALESCE(sa.tier, d.tier) tier " +
                        "FROM student s " +
                        "LEFT JOIN student_achievement sa ON sa.student_id=s.id AND sa.user_id=s.user_id AND sa.term=? " +
                        "LEFT JOIN achievement_def d ON d.id=sa.def_id " +
                        "WHERE s.user_id=? AND s.class_id=?",
                term, uid, classId);
        Map<Long, Map<String, Object>> agg = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long sid = ((Number) r.get("studentId")).longValue();
            Map<String, Object> a = agg.computeIfAbsent(sid, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("studentId", sid);
                m.put("name", r.get("name"));
                m.put("gender", r.get("gender"));
                m.put("stuNo", r.get("stuNo"));
                m.put("cnt", 0); m.put("points", 0);
                m.put("bronze", 0); m.put("silver", 0); m.put("gold", 0); m.put("platinum", 0);
                return m;
            });
            String tier = str(r.get("tier"));
            if (tier.isBlank()) continue;
            a.put("cnt", ((int) a.get("cnt")) + 1);
            a.put("points", ((int) a.get("points")) + medalPoints(str(r.get("rt")), tier));
            String key = tier.toLowerCase(Locale.ROOT);
            if (a.containsKey(key)) a.put(key, ((int) a.get(key)) + 1);
        }
        List<Map<String, Object>> rankings = new ArrayList<>(agg.values());
        rankings.sort((x, y) -> {
            int c = Integer.compare((int) y.get("points"), (int) x.get("points"));
            if (c != 0) return c;
            c = Integer.compare((int) y.get("cnt"), (int) x.get("cnt"));
            if (c != 0) return c;
            return str(x.get("stuNo")).compareTo(str(y.get("stuNo")));
        });

        // 定义列表（含获得人数/稀有度/名单）
        List<Map<String, Object>> defList = jdbc.queryForList(
                "SELECT d.*, (SELECT COUNT(*) FROM student_achievement sa " +
                        "WHERE sa.def_id=d.id AND sa.user_id=? AND sa.term=?) earnedCount " +
                        "FROM achievement_def d WHERE d.user_id=0 OR d.user_id=? ORDER BY d.kind DESC, d.id",
                uid, term, uid);
        attachEarners(uid, term, defList);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("term", term);
        result.put("classSize", classSize(uid));
        result.put("rankings", rankings);
        result.put("defs", defList);
        return Result.ok(result);
    }

    // ---------- 辅助 ----------

    /** 奖牌有效点数：大满贯(COLLECTOR)铜银金 ×5（50/150/300）；PLATINUM 老白金保留 100 不翻倍 */
    private int medalPoints(String ruleType, String tier) {
        if (tier == null || tier.isBlank()) return 0;
        int p = TIER_POINTS.getOrDefault(tier, 0);
        return "COLLECTOR".equals(ruleType) && !"PLATINUM".equals(tier) ? p * 5 : p;
    }

    /** 档位序数（高低比较用）；未知档按 -1 */
    private int tierRank(String tier) {
        return List.of("BRONZE", "SILVER", "GOLD", "PLATINUM").indexOf(tier);
    }

    /** 学生当前持有档：sa.tier 优先，空（老数据）回退定义档 */
    private String effTier(Map<String, Object> sa, Map<String, Object> d) {
        if (sa != null) {
            String t = str(sa.get("tier"));
            if (!t.isBlank()) return t;
        }
        return str(d.get("tier"));
    }

    /** 由定义行组装徽章视图对象（含 tiered 标志与三档门槛 tiers） */
    private Map<String, Object> badgeFromDef(Map<String, Object> d) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("defId", d.get("id"));
        b.put("name", d.get("name"));
        b.put("description", d.get("description"));
        b.put("icon", d.get("icon"));
        b.put("tier", d.get("tier"));
        b.put("kind", d.get("kind"));
        b.put("ruleType", d.get("rule_type"));
        b.put("tiered", d.get("tiered") != null && ((Number) d.get("tiered")).intValue() == 1);
        b.put("tiers", parseTiers(d.get("rule_params")));
        b.put("points", TIER_POINTS.getOrDefault(str(d.get("tier")), 0));
        return b;
    }

    /** 解析 rule_params JSON 中的 tiers 三档门槛数组（缺失/损坏返回 null） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTiers(Object ruleParams) {
        try {
            String json = str(ruleParams);
            if (json.isBlank()) return null;
            Map<String, Object> p = objectMapper.readValue(json, Map.class);
            Object t = p.get("tiers");
            if (t instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
                return (List<Map<String, Object>>) t;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 稀有度：0 人获得返回 NONE（前端显示"暂无"），否则按本班获得率分档 */
    private String rarity(int earned, int total) {
        if (earned == 0 || total == 0) return "NONE";
        double rate = earned * 100.0 / total;
        if (rate < 5) return "LEGENDARY";
        if (rate < 20) return "EPIC";
        if (rate <= 50) return "RARE";
        return "COMMON";
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
