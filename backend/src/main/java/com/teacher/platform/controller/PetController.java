package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.PetService;
import com.teacher.platform.service.TermStatsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * 班级宠物园（游戏化积分激励）：宠物领养/成长、评价加减分、流水撤回、毕业徽章、
 * 荣誉榜、评价项管理、小商店兑换、成绩一键发奖。全部按 uid 隔离。
 */
@RestController
@RequestMapping("/api/pet")
public class PetController extends BaseController {

    private final JdbcTemplate jdbc;
    private final PetService petService;
    private final TermStatsService termStats;

    public PetController(JdbcTemplate jdbc, PetService petService, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.petService = petService;
        this.termStats = termStats;
    }

    private Long classId() {
        return termStats.homeroomClassId(uid());
    }

    // ---------- 宠物 ----------

    /** 全班宠物状态：学生 + 宠物（未领养为 null）+ 等级/成长值/毕业数 + 当日负面状态标记 */
    @GetMapping("/pets")
    public Result<List<Map<String, Object>>> pets() {
        Long uid = uid();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT s.id studentId, s.name, s.gender, s.stu_no stuNo, " +
                        "p.id petId, p.pet_key petKey, p.pet_name petName, p.level, p.exp, p.graduated_count graduatedCount " +
                        "FROM student s LEFT JOIN pet p ON p.student_id=s.id AND p.user_id=s.user_id " +
                        "WHERE s.user_id=? AND s.class_id=? ORDER BY s.stu_no, s.id", uid, classId());
        Map<Long, Set<String>> flags = petService.statusFlags(uid);
        for (Map<String, Object> row : list) {
            Long sid = ((Number) row.get("studentId")).longValue();
            int level = row.get("level") == null ? 0 : ((Number) row.get("level")).intValue();
            int exp = row.get("exp") == null ? 0 : ((Number) row.get("exp")).intValue();
            int[] range = PetService.levelRange(Math.max(1, level));
            row.put("expStart", range[0]);
            row.put("expTarget", range[1]);
            Set<String> f = flags.getOrDefault(sid, Set.of());
            row.put("dizzy", f.contains("dizzy"));
            row.put("frozen", f.contains("frozen"));
            row.put("broken", f.contains("broken"));
        }
        return Result.ok(list);
    }

    /** 重置/移除宠物（删除宠物及其流水/徽章/兑换记录；误领养或转学生清理用） */
    @DeleteMapping("/pet/{studentId}")
    public Result<Void> resetPet(@PathVariable Long studentId) {
        Long uid = uid();
        for (String t : List.of("pet_log", "pet_badge", "pet_exchange", "pet")) {
            jdbc.update("DELETE FROM " + t + " WHERE user_id=? AND student_id=?", uid, studentId);
        }
        return Result.ok();
    }

    /** 领养宠物 */
    @PostMapping("/adopt")
    public Result<Void> adopt(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long sid = asLong(body.get("studentId"));
        boolean ok = petService.adopt(uid, sid, str(body.get("petKey")), str(body.get("petName")));
        return ok ? Result.ok() : Result.fail("该学生已领养过宠物");
    }

    /** 单人评价 */
    @PostMapping("/evaluate")
    public Result<Map<String, Object>> evaluate(@RequestBody Map<String, Object> body) {
        Long sid = asLong(body.get("studentId"));
        Long ruleId = asLong(body.get("ruleId"));
        int n = petService.award(uid(), sid, ruleId, null, "MANUAL", null, null);
        return Result.ok(Map.of("affected", n));
    }

    /** 批量评价（多学生同一评价项） */
    @PostMapping("/evaluate-batch")
    public Result<Map<String, Object>> evaluateBatch(@RequestBody Map<String, Object> body) {
        Long ruleId = asLong(body.get("ruleId"));
        int n = 0;
        for (Object o : (Iterable<?>) body.getOrDefault("studentIds", List.of())) {
            n += petService.award(uid(), asLong(o), ruleId, null, "MANUAL", null, null);
        }
        return Result.ok(Map.of("affected", n));
    }

    /** 撤回一条流水（回退成长值，不删业务记录） */
    @PostMapping("/revoke")
    public Result<Void> revoke(@RequestBody Map<String, Object> body) {
        petService.revokeLog(uid(), asLong(body.get("logId")));
        return Result.ok();
    }

    /** 成长值流水（可按学生/日期筛选），含规则名/学生名 */
    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(@RequestParam(required = false) Long studentId,
                                                  @RequestParam(required = false) String date) {
        Long uid = uid();
        StringBuilder sql = new StringBuilder(
                "SELECT l.id, l.student_id studentId, s.name studentName, l.rule_id ruleId, " +
                        "r.name ruleName, l.score, l.source, l.biz_ref bizRef, l.remark, l.created_at createdAt " +
                        "FROM pet_log l JOIN student s ON s.id=l.student_id " +
                        "LEFT JOIN pet_rule r ON r.id=l.rule_id WHERE l.user_id=?");
        List<Object> args = new ArrayList<>(List.of(uid));
        if (studentId != null) { sql.append(" AND l.student_id=?"); args.add(studentId); }
        if (date != null && !date.isBlank()) { sql.append(" AND DATE(l.created_at)=?"); args.add(date); }
        sql.append(" ORDER BY l.id DESC LIMIT 200");
        return Result.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /** 毕业：仅 Lv5 可毕业，徽章+1 并重置为 Lv1 重新成长 */
    @PostMapping("/graduate")
    public Result<Void> graduate(@RequestBody Map<String, Object> body) {
        boolean ok = petService.graduate(uid(), asLong(body.get("studentId")));
        return ok ? Result.ok() : Result.fail("宠物尚未满级（Lv.5），还不能毕业");
    }

    /** 荣誉榜：按累计总值（exp + 毕业次数×280）降序；后 1/3 不返回名次（只回等级） */
    @GetMapping("/ranking")
    public Result<List<Map<String, Object>>> ranking() {
        Long uid = uid();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT s.id studentId, s.name, s.gender, p.pet_name petName, p.pet_key petKey, " +
                        "p.level, p.exp, p.graduated_count badges, " +
                        "(p.exp + p.graduated_count*" + PetService.MAX_EXP + ") total " +
                        "FROM student s JOIN pet p ON p.student_id=s.id AND p.user_id=s.user_id " +
                        "WHERE s.user_id=? AND s.class_id=? ORDER BY total DESC, p.exp DESC, s.stu_no",
                uid, classId());
        int ranked = (int) Math.ceil(list.size() * 2.0 / 3.0);   // 前 2/3 有名次
        for (int i = 0; i < list.size(); i++) {
            list.get(i).put("rank", i < ranked ? i + 1 : null);
        }
        return Result.ok(list);
    }

    // ---------- 评价项 ----------

    @GetMapping("/rules")
    public Result<List<Map<String, Object>>> rules() {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM pet_rule WHERE user_id=? ORDER BY category, score DESC, id", uid()));
    }

    @PostMapping("/rule")
    public Result<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        if (str(body.get("name")).isBlank()) return Result.fail("请填写评价项名称");
        jdbc.update("INSERT INTO pet_rule(user_id,category,name,score,icon,enabled) VALUES(?,?,?,?,?,1)",
                uid, str(body.getOrDefault("category", "行为")), str(body.get("name")),
                asInt(body.get("score")), str(body.get("icon")));
        return Result.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    @PutMapping("/rule/{id}")
    public Result<Void> updateRule(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long uid = uid();
        Integer n = jdbc.update(
                "UPDATE pet_rule SET category=?, name=?, score=?, icon=?, enabled=? WHERE id=? AND user_id=?",
                str(body.getOrDefault("category", "行为")), str(body.get("name")), asInt(body.get("score")),
                str(body.get("icon")), Boolean.FALSE.equals(body.get("enabled")) ? 0 : 1, id, uid);
        return n == 0 ? Result.fail("评价项不存在") : Result.ok();
    }

    @DeleteMapping("/rule/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        jdbc.update("DELETE FROM pet_rule WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    // ---------- 小商店 ----------

    @GetMapping("/shop-items")
    public Result<List<Map<String, Object>>> shopItems() {
        return Result.ok(jdbc.queryForList(
                "SELECT * FROM pet_shop_item WHERE user_id=? ORDER BY price, id", uid()));
    }

    @PostMapping("/shop-item")
    public Result<Void> createItem(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        if (str(body.get("name")).isBlank()) return Result.fail("请填写商品名称");
        jdbc.update("INSERT INTO pet_shop_item(user_id,name,icon,price,stock) VALUES(?,?,?,?,?)",
                uid, str(body.get("name")), str(body.get("icon")),
                Math.max(1, asInt(body.get("price"))), asInt(body.get("stock"), -1));
        return Result.ok();
    }

    @PutMapping("/shop-item/{id}")
    public Result<Void> updateItem(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE pet_shop_item SET name=?, icon=?, price=?, stock=? WHERE id=? AND user_id=?",
                str(body.get("name")), str(body.get("icon")),
                Math.max(1, asInt(body.get("price"))), asInt(body.get("stock"), -1), id, uid());
        return Result.ok();
    }

    @DeleteMapping("/shop-item/{id}")
    public Result<Void> deleteItem(@PathVariable Long id) {
        jdbc.update("DELETE FROM pet_shop_item WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    /** 兑换记录（含学生名/商品名），小商店弹窗用 */
    @GetMapping("/exchanges")
    public Result<List<Map<String, Object>>> exchanges() {
        return Result.ok(jdbc.queryForList(
                "SELECT e.id, e.student_id studentId, s.name studentName, e.item_id itemId, " +
                        "i.name itemName, i.icon itemIcon, e.cost, e.status, e.created_at createdAt " +
                        "FROM pet_exchange e JOIN student s ON s.id=e.student_id " +
                        "LEFT JOIN pet_shop_item i ON i.id=e.item_id " +
                        "WHERE e.user_id=? ORDER BY e.id DESC LIMIT 200", uid()));
    }

    /** 兑换：校验宠物存在/成长值足够/库存，扣 exp（不降级）、扣库存、记待兑现 */
    @PostMapping("/exchange")
    public Result<Void> exchange(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long sid = asLong(body.get("studentId"));
        Long itemId = asLong(body.get("itemId"));
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM pet_shop_item WHERE id=? AND user_id=?", itemId, uid);
        if (items.isEmpty()) return Result.fail("商品不存在");
        Map<String, Object> item = items.get(0);
        int price = ((Number) item.get("price")).intValue();
        int stock = ((Number) item.get("stock")).intValue();
        if (stock == 0) return Result.fail("该商品库存不足");
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,exp FROM pet WHERE user_id=? AND student_id=?", uid, sid);
        if (pets.isEmpty()) return Result.fail("该学生还未领养宠物");
        int exp = ((Number) pets.get(0).get("exp")).intValue();
        if (exp < price) return Result.fail("成长值不足（需要 " + price + "，当前 " + exp + "）");
        // 扣成长值（只减 exp 不降级，下限 0）+ 扣库存 + 写兑换记录
        jdbc.update("UPDATE pet SET exp=GREATEST(0, exp-?) WHERE id=?", price, pets.get(0).get("id"));
        if (stock > 0) jdbc.update("UPDATE pet_shop_item SET stock=stock-1 WHERE id=?", itemId);
        jdbc.update("INSERT INTO pet_exchange(user_id,student_id,item_id,cost,status) VALUES(?,?,?,?,'待兑现')",
                uid, sid, itemId, price);
        return Result.ok();
    }

    /** 兑现：待兑现 → 已兑现（线下履约确认） */
    @PutMapping("/exchange/{id}/fulfill")
    public Result<Void> fulfill(@PathVariable Long id) {
        jdbc.update("UPDATE pet_exchange SET status='已兑现' WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    // ---------- 成绩一键发奖 ----------

    /**
     * 成绩发奖待发清单：该考试中——任一科满分(≥100)+3、全科达优秀线+2、
     * 较上一次考试总分排名进步≥5名+2；每名学生取最高一项不叠加；
     * 已发过的（AUTO_SCORE 幂等键存在）标记 granted=true。
     */
    @GetMapping("/score-awards")
    public Result<List<Map<String, Object>>> scoreAwards(@RequestParam Long examId) {
        Long uid = uid();
        int excellent = settingInt(uid, "excellent_line", 85);
        // 本次考试：每生 {科目: 分}、总分排名
        Map<Long, Map<String, Double>> scores = new HashMap<>();
        jdbc.queryForList("SELECT student_id sid, subject, score FROM score WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL",
                uid, examId).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            double sc = r.get("score") == null ? 0 : ((Number) r.get("score")).doubleValue();
            scores.computeIfAbsent(sid, k -> new HashMap<>()).put(str(r.get("subject")), sc);
        });
        Map<Long, Integer> curRank = examRank(uid, examId);
        Map<Long, Integer> prevRank = examRank(uid, prevExamId(uid, examId));
        // 已发标记
        Set<Long> granted = new HashSet<>();
        jdbc.queryForList("SELECT biz_ref FROM pet_log WHERE user_id=? AND source='AUTO_SCORE'", uid)
                .forEach(r -> {
                    String ref = str(r.get("biz_ref"));
                    String prefix = "exam:" + examId + ":";
                    if (ref.startsWith(prefix)) {
                        try { granted.add(Long.parseLong(ref.substring(prefix.length()))); } catch (Exception ignored) {}
                    }
                });
        // 学生名
        Map<Long, String> names = new HashMap<>();
        jdbc.queryForList("SELECT id, name FROM student WHERE user_id=?", uid)
                .forEach(r -> names.put(((Number) r.get("id")).longValue(), str(r.get("name"))));

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Double>> e : scores.entrySet()) {
            Long sid = e.getKey();
            Collection<Double> ss = e.getValue().values();
            boolean full = ss.stream().anyMatch(v -> v >= 100);
            boolean allEx = ss.stream().allMatch(v -> v >= excellent);
            Integer cr = curRank.get(sid), pr = prevRank.get(sid);
            boolean progress = cr != null && pr != null && pr - cr >= 5;
            // 取最高一项：满分+3 > 优秀+2 ≈ 进步+2
            int score = 0;
            String reason = null;
            if (full) { score = 3; reason = "平时测验满分 +3"; }
            else if (allEx) { score = 2; reason = "全科达优秀线 +2"; }
            else if (progress) { score = 2; reason = "排名进步 " + (pr - cr) + " 名 +2"; }
            if (score == 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("studentId", sid);
            row.put("name", names.get(sid));
            row.put("score", score);
            row.put("reason", reason);
            row.put("granted", granted.contains(sid));
            result.add(row);
        }
        result.sort((a, b) -> Integer.compare((int) b.get("score"), (int) a.get("score")));
        return Result.ok(result);
    }

    /** 发放成绩奖：source=AUTO_SCORE，biz_ref=exam:{examId}:{studentId} 幂等 */
    @PostMapping("/score-awards/grant")
    public Result<Map<String, Object>> grantScoreAwards(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long examId = asLong(body.get("examId"));
        int n = 0;
        for (Object o : (Iterable<?>) body.getOrDefault("items", List.of())) {
            if (o instanceof Map<?, ?> it) {
                Long sid = asLong(it.get("studentId"));
                int score = asInt(it.get("score"));
                n += petService.award(uid, sid, null, score, "AUTO_SCORE",
                        "exam:" + examId + ":" + sid, str(it.get("reason")));
            }
        }
        return Result.ok(Map.of("affected", n));
    }

    // ---------- 辅助 ----------

    /** 某场考试总分降序排名：studentId -> 名次 */
    private Map<Long, Integer> examRank(Long uid, Long examId) {
        Map<Long, Integer> rank = new HashMap<>();
        if (examId == null) return rank;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT student_id sid, SUM(score) t FROM score WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL GROUP BY student_id",
                uid, examId);
        rows.sort((a, b) -> Double.compare(((Number) b.get("t")).doubleValue(), ((Number) a.get("t")).doubleValue()));
        for (int i = 0; i < rows.size(); i++) {
            rank.put(((Number) rows.get(i).get("sid")).longValue(), i + 1);
        }
        return rank;
    }

    /** 上一场考试（id 更小的最近一场）；无则 null */
    private Long prevExamId(Long uid, Long examId) {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM exam WHERE user_id=? AND id<? ORDER BY id DESC LIMIT 1",
                    Long.class, uid, examId);
        } catch (Exception e) {
            return null;
        }
    }

    private int settingInt(Long uid, String k, int def) {
        try {
            String v = jdbc.queryForObject("SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null || v.isBlank() ? def : (int) Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private int asInt(Object o) {
        return asInt(o, 0);
    }

    private int asInt(Object o, int def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
