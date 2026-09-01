package com.teacher.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 成就规则引擎：扫描全班学期数据，判定 10 条自动规则 + 大满贯，
 * 与已颁发记录 diff 后幂等颁发，返回新颁发清单（前端跳杯庆祝用）。
 * 数据复用 TermStatsService 学期素材（考勤/作业/纪律/排名/值日/职务）；
 * 成绩类规则（全科优秀/单科状元/满分达人）查最近一次考试明细。
 */
@Service
public class AchievementScanner {

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AchievementScanner(JdbcTemplate jdbc, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.termStats = termStats;
    }

    /** 单条规则评估结果 */
    public static class Eval {
        public boolean met;          // 是否达成
        public String progress;      // 未达成时的当前进度文本（达成时为 null）
        Eval(boolean met, String progress) { this.met = met; this.progress = progress; }
    }

    /**
     * 扫描当前学期：判定所有启用的自动规则，颁发新成就（幂等），大满贯最后评定。
     * 返回新颁发清单 [{studentId, name, defId, defName, icon, tier}]。
     */
    public List<Map<String, Object>> scan(Long uid) {
        Long classId = termStats.homeroomClassId(uid);
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        List<Map<String, Object>> materials = termStats.materials(uid, classId);
        if (materials.isEmpty()) return new ArrayList<>();

        // 启用的自动规则（内置 user_id=0 + 本账号自定义，本期自定义均为 MANUAL，此处实际只有内置）
        List<Map<String, Object>> defs = jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE kind='AUTO' AND enabled=1 AND (user_id=0 OR user_id=?) ORDER BY id", uid);
        Map<String, Object> examData = loadLatestExam(uid, range[0], range[1]);
        int excellentLine = settingInt(uid, "excellent_line", 85);

        // 已颁发记录索引：studentId -> Set<defId>
        Map<Long, Set<Long>> awarded = new HashMap<>();
        jdbc.queryForList(
                "SELECT student_id sid, def_id did FROM student_achievement WHERE user_id=? AND term=?", uid, term)
                .forEach(r -> awarded.computeIfAbsent(((Number) r.get("sid")).longValue(), k -> new HashSet<>())
                        .add(((Number) r.get("did")).longValue()));

        List<Map<String, Object>> newAwards = new ArrayList<>();
        List<Map<String, Object>> autoDefs = new ArrayList<>();   // 非大满贯的启用自动规则（大满贯判定用）
        for (Map<String, Object> def : defs) {
            String ruleType = str(def.get("rule_type"));
            Long defId = ((Number) def.get("id")).longValue();
            if ("COLLECTOR".equals(ruleType)) continue;           // 大满贯最后单独评定
            autoDefs.add(def);
            for (Map<String, Object> m : materials) {
                Long sid = ((Number) m.get("studentId")).longValue();
                Eval ev = evaluate(ruleType, params(def), m, examData, excellentLine);
                if (ev.met && !awarded.getOrDefault(sid, Set.of()).contains(defId)) {
                    insertAward(uid, sid, defId, term, "AUTO", null);
                    awarded.computeIfAbsent(sid, k -> new HashSet<>()).add(defId);
                    newAwards.add(awardItem(sid, str(m.get("name")), def));
                }
            }
        }

        // 大满贯：集齐全部"启用中且非大满贯"的自动成就
        Long collectorId = defs.stream()
                .filter(d -> "COLLECTOR".equals(str(d.get("rule_type"))))
                .map(d -> ((Number) d.get("id")).longValue()).findFirst().orElse(null);
        if (collectorId != null) {
            Set<Long> autoDefIds = new HashSet<>();
            autoDefs.forEach(d -> autoDefIds.add(((Number) d.get("id")).longValue()));
            Map<String, Object> collector = defs.stream()
                    .filter(d -> collectorId.equals(((Number) d.get("id")).longValue())).findFirst().orElse(null);
            for (Map<String, Object> m : materials) {
                Long sid = ((Number) m.get("studentId")).longValue();
                Set<Long> got = awarded.getOrDefault(sid, Set.of());
                boolean all = got.containsAll(autoDefIds) && !autoDefIds.isEmpty();
                if (all && !got.contains(collectorId)) {
                    insertAward(uid, sid, collectorId, term, "AUTO", null);
                    newAwards.add(awardItem(sid, str(m.get("name")), collector));
                }
            }
        }
        return newAwards;
    }

    /**
     * 评估某生全部启用自动规则：返回 [{defId, ruleType, name, icon, tier, met, progress}]。
     * 学生徽章墙灰色徽章悬停进度用。
     */
    public List<Map<String, Object>> evaluateStudent(Long uid, Long studentId) {
        Map<String, Object> material = termStats.material(uid, studentId);
        if (material == null) return new ArrayList<>();
        String[] range = termStats.termRange(uid);
        Map<String, Object> examData = loadLatestExam(uid, range[0], range[1]);
        int excellentLine = settingInt(uid, "excellent_line", 85);
        List<Map<String, Object>> result = new ArrayList<>();
        jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE kind='AUTO' AND enabled=1 AND (user_id=0 OR user_id=?) ORDER BY id", uid)
                .forEach(def -> {
                    Eval ev = evaluate(str(def.get("rule_type")), params(def), material, examData, excellentLine);
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("defId", ((Number) def.get("id")).longValue());
                    r.put("name", def.get("name"));
                    r.put("icon", def.get("icon"));
                    r.put("tier", def.get("tier"));
                    r.put("met", ev.met);
                    r.put("progress", ev.progress);
                    result.add(r);
                });
        return result;
    }

    /**
     * 单条规则判定。返回 Eval（met + 未达成时的进度文本）。
     * 成绩类规则从 examData 取该生最近一次考试明细；无考试数据时相关规则不达成。
     */
    @SuppressWarnings("unchecked")
    private Eval evaluate(String ruleType, Map<String, Object> p, Map<String, Object> m,
                          Map<String, Object> examData, int excellentLine) {
        int late = asInt(m.get("late"));
        int absent = asInt(m.get("absent"));
        double leaveDays = asDouble(m.get("leaveDays"));
        int hw = asInt(m.get("homeworkMissing"));
        int disc = asInt(m.get("discipline"));
        int duty = asInt(m.get("dutyCount"));
        String roles = str(m.get("roles"));
        Object rcObj = m.get("rankChange");

        Map<String, Double> myScores = (Map<String, Double>) examData.get("scores_" + m.get("studentId"));
        Map<String, Double> subjMax = (Map<String, Double>) examData.get("subjMax");

        switch (ruleType) {
            case "PERFECT_ATTENDANCE": {
                boolean allowLeave = Boolean.TRUE.equals(p.get("allowLeave"));
                boolean met = late == 0 && absent == 0 && (allowLeave || leaveDays == 0);
                return new Eval(met, met ? null : "迟到 " + late + " 次、缺勤 " + absent + " 次、请假 " + fmt(leaveDays) + " 天（目标全 0）");
            }
            case "PERFECT_HOMEWORK":
                return new Eval(hw == 0, hw == 0 ? null : "作业未交 " + hw + " 次（目标 0 次）");
            case "PERFECT_DISCIPLINE":
                return new Eval(disc == 0, disc == 0 ? null : "课堂违纪 " + disc + " 条（目标 0 条）");
            case "RANK_UP": {
                int need = asInt(p.getOrDefault("minRankUp", 5));
                int change = rcObj == null ? 0 : ((Number) rcObj).intValue();
                boolean met = rcObj != null && change >= need;
                String prog = rcObj == null ? "暂无两次考试排名记录" : "排名已提升 " + change + "/" + need + " 名";
                return new Eval(met, met ? null : prog);
            }
            case "ALL_EXCELLENT": {
                if (myScores == null || myScores.isEmpty()) return new Eval(false, "本学期暂无考试成绩");
                long okCount = myScores.values().stream().filter(s -> s >= excellentLine).count();
                boolean met = okCount == myScores.size();
                return new Eval(met, met ? null : "达优秀线 " + okCount + "/" + myScores.size() + " 科（优秀线 " + excellentLine + " 分）");
            }
            case "SUBJECT_TOP": {
                if (myScores == null || subjMax == null) return new Eval(false, "本学期暂无考试成绩");
                boolean met = myScores.entrySet().stream()
                        .anyMatch(e -> Math.abs(e.getValue() - subjMax.getOrDefault(e.getKey(), 0.0)) < 0.01);
                return new Eval(met, met ? null : "暂无科目取得班级最高分");
            }
            case "SCORE_FULL": {
                double full = asDouble(p.getOrDefault("fullScore", 100));
                if (myScores == null || myScores.isEmpty()) return new Eval(false, "本学期暂无考试成绩");
                double best = myScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
                boolean met = best >= full;
                return new Eval(met, met ? null : "当前最高 " + fmt(best) + "/" + fmt(full) + " 分");
            }
            case "DUTY_COUNT": {
                int need = asInt(p.getOrDefault("minDuty", 10));
                boolean met = duty >= need;
                return new Eval(met, met ? null : "值日参与 " + duty + "/" + need + " 次");
            }
            case "COMMITTEE":
                return new Eval(!roles.isBlank(), roles.isBlank() ? "尚未担任班委职务" : null);
            case "CONTINUOUS_ATTENDANCE": {
                // KISS：学期内无迟到、无缺勤即视为持续全勤（不看请假）
                boolean met = late == 0 && absent == 0;
                return new Eval(met, met ? null : "迟到 " + late + " 次、缺勤 " + absent + " 次（目标连续 4 周全 0）");
            }
            case "COLLECTOR":
                return new Eval(false, "集齐本学期全部启用的自动成就即可获得");
            default:
                return new Eval(false, null);
        }
    }

    // ---------- 辅助 ----------

    /**
     * 加载学期内最近一次考试的成绩明细：
     * scores_<studentId> = Map<科目, 分数>；subjMax = Map<科目, 班级最高分>。
     */
    private Map<String, Object> loadLatestExam(Long uid, String start, String end) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Double> subjMax = new HashMap<>();
        Long examId;
        try {
            examId = jdbc.queryForObject(
                    "SELECT id FROM exam WHERE user_id=? AND (exam_date IS NULL OR exam_date BETWEEN ? AND ?) " +
                            "ORDER BY exam_date IS NULL, exam_date DESC, id DESC LIMIT 1", Long.class, uid, start, end);
        } catch (Exception e) {
            examId = null;
        }
        if (examId != null) {
            jdbc.queryForList(
                    "SELECT student_id sid, subject, score FROM score WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL",
                    uid, examId).forEach(r -> {
                Long sid = ((Number) r.get("sid")).longValue();
                String subj = str(r.get("subject"));
                double sc = r.get("score") == null ? 0 : ((Number) r.get("score")).doubleValue();
                @SuppressWarnings("unchecked")
                Map<String, Double> ms = (Map<String, Double>) data.computeIfAbsent("scores_" + sid, k -> new HashMap<String, Double>());
                ms.put(subj, sc);
                subjMax.merge(subj, sc, Math::max);
            });
        }
        data.put("subjMax", subjMax);
        return data;
    }

    private void insertAward(Long uid, Long sid, Long defId, String term, String source, String remark) {
        jdbc.update("INSERT IGNORE INTO student_achievement(user_id,student_id,def_id,term,source,remark,awarded_at) " +
                "VALUES(?,?,?,?,?,?,?)", uid, sid, defId, term, source, remark, LocalDate.now());
    }

    private Map<String, Object> awardItem(Long sid, String name, Map<String, Object> def) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("studentId", sid);
        it.put("name", name);
        it.put("defId", ((Number) def.get("id")).longValue());
        it.put("defName", def.get("name"));
        it.put("icon", def.get("icon"));
        it.put("tier", def.get("tier"));
        return it;
    }

    /** 解析 rule_params JSON（空/损坏返回空 Map） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> params(Map<String, Object> def) {
        try {
            String json = str(def.get("rule_params"));
            if (json.isBlank()) return new HashMap<>();
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
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

    private int asInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private double asDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** 数字格式化：整数不带小数点 */
    private String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
