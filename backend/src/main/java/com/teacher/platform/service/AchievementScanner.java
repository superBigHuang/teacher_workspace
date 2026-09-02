package com.teacher.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * 成就规则引擎：扫描全班学期数据，判定自动规则（固定档 + 铜银金三档可进阶）+ 大满贯，
 * 与已颁发记录 diff 后幂等颁发/升级（只升不降、不回收），返回新颁发/升级清单（前端庆祝用）。
 * 数据复用 TermStatsService 学期素材（考勤/作业/纪律/排名/值日/职务）；
 * 成绩类规则查最近一次考试明细；宠物类规则查 pet/pet_badge/pet_exchange（一次查全班，内存聚合）。
 */
@Service
public class AchievementScanner {

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 档序（低→高）；PLATINUM 为老大满贯白金，仅用于老数据兼容比较 */
    private static final List<String> TIER_ORDER = List.of("BRONZE", "SILVER", "GOLD");
    private static final Map<String, String> TIER_EMOJI = Map.of("BRONZE", "🥉", "SILVER", "🥈", "GOLD", "🥇");
    /** 各等级基础点数：铜10/银30/金60/白金100；大满贯铜银金在此基础上 ×5 */
    private static final Map<String, Integer> TIER_POINTS = Map.of(
            "BRONZE", 10, "SILVER", 30, "GOLD", 60, "PLATINUM", 100);

    public AchievementScanner(JdbcTemplate jdbc, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.termStats = termStats;
    }

    /** 单条规则评估结果 */
    public static class Eval {
        public boolean met;          // 是否达成（可进阶=至少达成铜档）
        public String tier;          // 达成档 BRONZE/SILVER/GOLD；未达成 null
        public String progress;      // 进度文本（未达成指引入门档；已达成指向下一档；满档"已达最高🥇"）
        Eval(boolean met, String tier, String progress) {
            this.met = met; this.tier = tier; this.progress = progress;
        }
    }

    /**
     * 扫描当前学期：判定所有启用的自动规则，颁发新成就/升级更高档（幂等、只升不降），大满贯最后评定。
     * 返回清单元素 [{studentId, name, defId, defName, icon, tier, kind('award'新获得/'upgrade'升级), oldTier?}]。
     */
    public List<Map<String, Object>> scan(Long uid) {
        Long classId = termStats.homeroomClassId(uid);
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        List<Map<String, Object>> materials = termStats.materials(uid, classId);
        if (materials.isEmpty()) return new ArrayList<>();

        // 启用的自动规则（内置 user_id=0 + 本账号自定义）
        List<Map<String, Object>> defs = jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE kind='AUTO' AND enabled=1 AND (user_id=0 OR user_id=?) ORDER BY id", uid);
        Map<String, Object> examData = loadLatestExam(uid, range[0], range[1]);
        Map<String, Object> petData = loadPetData(uid, range[0], range[1]);
        int excellentLine = settingInt(uid, "excellent_line", 85);

        // 持有记录索引：studentId -> (defId -> sa.tier)；tier 为空串表示老数据（回退 def.tier）
        Map<Long, Map<Long, String>> held = new HashMap<>();
        jdbc.queryForList(
                "SELECT student_id sid, def_id did, tier FROM student_achievement WHERE user_id=? AND term=?", uid, term)
                .forEach(r -> held.computeIfAbsent(((Number) r.get("sid")).longValue(), k -> new HashMap<>())
                        .put(((Number) r.get("did")).longValue(), str(r.get("tier"))));

        List<Map<String, Object>> newAwards = new ArrayList<>();
        List<Map<String, Object>> autoDefs = new ArrayList<>();   // 非大满贯的启用自动规则（大满贯判定用）
        for (Map<String, Object> def : defs) {
            String ruleType = str(def.get("rule_type"));
            Long defId = ((Number) def.get("id")).longValue();
            if ("COLLECTOR".equals(ruleType)) continue;           // 大满贯最后单独评定
            autoDefs.add(def);
            for (Map<String, Object> m : materials) {
                Long sid = ((Number) m.get("studentId")).longValue();
                Eval ev = evaluate(def, params(def), m, examData, petData, excellentLine);
                if (ev.tier == null) continue;                    // 一档都未达成：不发不动
                String heldTier = held.getOrDefault(sid, Map.of()).get(defId);
                if (heldTier == null) {
                    // 无记录：颁发当前达成档
                    insertAward(uid, sid, defId, term, "AUTO", null, ev.tier);
                    held.computeIfAbsent(sid, k -> new HashMap<>()).put(defId, ev.tier);
                    newAwards.add(awardItem(sid, str(m.get("name")), def, ev.tier, "award", null));
                } else {
                    // 有记录：老数据(tier='')回退 def.tier；仅当达成档点数更高才升级（只升不降）
                    String effHeld = heldTier.isBlank() ? str(def.get("tier")) : heldTier;
                    if (medalPoints(ruleType, ev.tier) > medalPoints(ruleType, effHeld)) {
                        jdbc.update("UPDATE student_achievement SET tier=?, awarded_at=? " +
                                "WHERE user_id=? AND student_id=? AND def_id=? AND term=?",
                                ev.tier, LocalDate.now(), uid, sid, defId, term);
                        held.get(sid).put(defId, ev.tier);
                        newAwards.add(awardItem(sid, str(m.get("name")), def, ev.tier, "upgrade", effHeld));
                    }
                }
            }
        }

        // 大满贯：先集齐（全部非大满贯 AUTO 都持有，任意档），档位 = 可进阶成就持有档的最低档（木桶）
        Map<String, Object> collector = defs.stream()
                .filter(d -> "COLLECTOR".equals(str(d.get("rule_type")))).findFirst().orElse(null);
        if (collector != null) {
            Long collectorId = ((Number) collector.get("id")).longValue();
            for (Map<String, Object> m : materials) {
                Long sid = ((Number) m.get("studentId")).longValue();
                Map<Long, String> got = held.getOrDefault(sid, Map.of());
                boolean all = autoDefs.stream().allMatch(d ->
                        got.containsKey(((Number) d.get("id")).longValue()));
                if (!all) continue;                               // 未集齐：不发（已发的也不回收）
                String bucket = null;
                for (Map<String, Object> d : autoDefs) {
                    if (((Number) d.get("tiered")).intValue() != 1) continue;   // 固定档只要求持有，不参与取 min
                    Long did = ((Number) d.get("id")).longValue();
                    String t = got.get(did);
                    if (t == null || t.isBlank()) t = str(d.get("tier"));       // 老记录回退入门档
                    bucket = lowerTier(bucket, t);
                }
                if (bucket == null) bucket = "BRONZE";            // 无可进阶定义时集齐即铜
                String heldTier = got.get(collectorId);
                if (heldTier == null) {
                    insertAward(uid, sid, collectorId, term, "AUTO", null, bucket);
                    newAwards.add(awardItem(sid, str(m.get("name")), collector, bucket, "award", null));
                } else {
                    // 老白金记录(回退 PLATINUM=100)：银150/金300 才升级，铜50 不顶掉白金
                    String effHeld = heldTier.isBlank() ? str(collector.get("tier")) : heldTier;
                    if (medalPoints("COLLECTOR", bucket) > medalPoints("COLLECTOR", effHeld)) {
                        jdbc.update("UPDATE student_achievement SET tier=?, awarded_at=? " +
                                "WHERE user_id=? AND student_id=? AND def_id=? AND term=?",
                                bucket, LocalDate.now(), uid, sid, collectorId, term);
                        newAwards.add(awardItem(sid, str(m.get("name")), collector, bucket, "upgrade", effHeld));
                    }
                }
            }
        }
        return newAwards;
    }

    /**
     * 评估某生全部启用自动规则：返回 [{defId, name, icon, tier, tiered, tiers, met, progress}]。
     * tier=当前达成档（未达成回退定义入门档）；progress 为进度文本；tiers 为三档门槛（前端悬停卡用）。
     * 学生徽章墙灰色徽章悬停进度、已持有徽章升档提示用。
     */
    public List<Map<String, Object>> evaluateStudent(Long uid, Long studentId) {
        Map<String, Object> material = termStats.material(uid, studentId);
        if (material == null) return new ArrayList<>();
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        Map<String, Object> examData = loadLatestExam(uid, range[0], range[1]);
        Map<String, Object> petData = loadPetData(uid, range[0], range[1]);
        int excellentLine = settingInt(uid, "excellent_line", 85);

        // 该生本学期持有记录：defId -> sa.tier（空串=老数据回退 def.tier）
        Map<Long, String> held = new HashMap<>();
        jdbc.queryForList(
                "SELECT def_id did, tier FROM student_achievement WHERE user_id=? AND student_id=? AND term=?",
                uid, studentId, term)
                .forEach(r -> held.put(((Number) r.get("did")).longValue(), str(r.get("tier"))));

        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> defs = jdbc.queryForList(
                "SELECT * FROM achievement_def WHERE kind='AUTO' AND enabled=1 AND (user_id=0 OR user_id=?) ORDER BY id", uid);
        List<Map<String, Object>> autoDefs = new ArrayList<>();
        for (Map<String, Object> def : defs) {
            Long defId = ((Number) def.get("id")).longValue();
            boolean tiered = ((Number) def.get("tiered")).intValue() == 1;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("defId", defId);
            r.put("name", def.get("name"));
            r.put("icon", def.get("icon"));
            r.put("tiered", tiered);
            r.put("tiers", tiersOf(params(def)));
            if ("COLLECTOR".equals(str(def.get("rule_type")))) {
                r.put("tier", def.get("tier"));
                r.put("met", held.containsKey(defId));
                r.put("progress", collectorProgress(autoDefs, held));
            } else {
                autoDefs.add(def);
                Eval ev = evaluate(def, params(def), material, examData, petData, excellentLine);
                r.put("tier", ev.tier != null ? ev.tier : def.get("tier"));
                r.put("met", ev.tier != null);
                r.put("progress", ev.progress);
            }
            result.add(r);
        }
        return result;
    }

    /**
     * 单条规则判定。固定档：met 时 tier=def.tier；可进阶：按 rule_params.tiers 取实际值满足的最高档。
     * 成绩类规则从 examData 取该生最近一次考试明细/排名；宠物类从 petData 取；无数据时相关规则不达成。
     */
    @SuppressWarnings("unchecked")
    private Eval evaluate(Map<String, Object> def, Map<String, Object> p, Map<String, Object> m,
                          Map<String, Object> examData, Map<String, Object> petData, int excellentLine) {
        String ruleType = str(def.get("rule_type"));
        String defTier = str(def.get("tier"));          // 固定档=该档；可进阶=入门档(BRONZE)
        Long sid = ((Number) m.get("studentId")).longValue();
        List<Map<String, Object>> tiers = tiersOf(p);

        int late = asInt(m.get("late"));
        int absent = asInt(m.get("absent"));
        double leaveDays = asDouble(m.get("leaveDays"));
        int hw = asInt(m.get("homeworkMissing"));
        int disc = asInt(m.get("discipline"));
        int duty = asInt(m.get("dutyCount"));
        String roles = str(m.get("roles"));
        Object rcObj = m.get("rankChange");

        Map<String, Double> myScores = (Map<String, Double>) examData.get("scores_" + sid);
        Map<Long, Integer> examRanks = (Map<Long, Integer>) examData.get("ranks");
        Map<Long, Integer> petLevel = (Map<Long, Integer>) petData.get("petLevel");
        Set<Long> petAdopted = (Set<Long>) petData.get("petAdopted");
        Map<Long, Integer> gradCount = (Map<Long, Integer>) petData.get("gradCount");
        Map<Long, Integer> shopCount = (Map<Long, Integer>) petData.get("shopCount");

        switch (ruleType) {
            case "PERFECT_ATTENDANCE": {
                boolean allowLeave = Boolean.TRUE.equals(p.get("allowLeave"));
                boolean met = late == 0 && absent == 0 && (allowLeave || leaveDays == 0);
                return new Eval(met, met ? defTier : null,
                        met ? null : "迟到 " + late + " 次、缺勤 " + absent + " 次、请假 " + fmt(leaveDays) + " 天（目标全 0）");
            }
            case "PERFECT_HOMEWORK":
                return new Eval(hw == 0, hw == 0 ? defTier : null, hw == 0 ? null : "作业未交 " + hw + " 次（目标 0 次）");
            case "PERFECT_DISCIPLINE":
                return new Eval(disc == 0, disc == 0 ? defTier : null, disc == 0 ? null : "课堂违纪 " + disc + " 条（目标 0 条）");
            case "COMMITTEE":
                return new Eval(!roles.isBlank(), !roles.isBlank() ? defTier : null, roles.isBlank() ? "尚未担任班委职务" : null);
            case "ALL_EXCELLENT": {
                if (myScores == null || myScores.isEmpty()) return new Eval(false, null, "本学期暂无考试成绩");
                long okCount = myScores.values().stream().filter(s -> s >= excellentLine).count();
                boolean met = okCount == myScores.size();
                return new Eval(met, met ? defTier : null,
                        met ? null : "达优秀线 " + okCount + "/" + myScores.size() + " 科（优秀线 " + excellentLine + " 分）");
            }
            case "SUBJECT_TOP": {
                if (myScores == null) return new Eval(false, null, "本学期暂无考试成绩");
                Map<String, Double> subjMax = (Map<String, Double>) examData.get("subjMax");
                boolean met = myScores.entrySet().stream()
                        .anyMatch(e -> Math.abs(e.getValue() - subjMax.getOrDefault(e.getKey(), 0.0)) < 0.01);
                return new Eval(met, met ? defTier : null, met ? null : "暂无科目取得班级最高分");
            }
            // ---------- 可进阶 ----------
            case "RANK_UP": {
                if (rcObj == null) return new Eval(false, null, "暂无两次考试排名记录");
                int change = ((Number) rcObj).intValue();
                String t = tierOf(tiers, change, false);
                return new Eval(t != null, t, countProgress(tiers, change, "排名已提升 " + change + " 名", "名"));
            }
            case "SCORE_FULL": {
                double full = asDouble(p.getOrDefault("fullScore", 100));
                if (myScores == null || myScores.isEmpty()) return new Eval(false, null, "本学期暂无考试成绩");
                long fullCount = myScores.values().stream().filter(s -> s >= full).count();
                String t = tierOf(tiers, fullCount, false);
                return new Eval(t != null, t, countProgress(tiers, fullCount, "满分科目 " + fullCount + " 科", "科"));
            }
            case "DUTY_COUNT": {
                String t = tierOf(tiers, duty, false);
                return new Eval(t != null, t, countProgress(tiers, duty, "值日参与 " + duty + " 次", "次"));
            }
            case "CONTINUOUS_ATTENDANCE": {
                // KISS：本期无周粒度考勤，学期内无迟到无缺勤即按最高档（金）颁发
                boolean met = late == 0 && absent == 0;
                return new Eval(met, met ? "GOLD" : null,
                        met ? "已达最高🥇" : "迟到 " + late + " 次、缺勤 " + absent + " 次，本学期全 0 可得🥇");
            }
            case "RANK_TOP": {
                Integer rk = examRanks == null ? null : examRanks.get(sid);
                if (rk == null) return new Eval(false, null, "本学期暂无考试成绩");
                String t = tierOf(tiers, rk, true);
                return new Eval(t != null, t, rankProgress(tiers, rk, "总分排名第 " + rk + " 名"));
            }
            case "PET_ADOPTED": {
                boolean met = petAdopted != null && petAdopted.contains(sid);
                return new Eval(met, met ? defTier : null, met ? null : "尚未领养班级宠物");
            }
            case "PET_LEVEL": {
                Integer lv = petLevel == null ? null : petLevel.get(sid);
                if (lv == null) return new Eval(false, null, "尚未领养班级宠物");
                String t = tierOf(tiers, lv, false);
                return new Eval(t != null, t, countProgress(tiers, lv, "宠物 Lv." + lv, "级"));
            }
            case "PET_GRADUATE": {
                int g = gradCount == null ? 0 : gradCount.getOrDefault(sid, 0);
                String t = tierOf(tiers, g, false);
                return new Eval(t != null, t, countProgress(tiers, g, "本学期宠物毕业 " + g + " 次", "次"));
            }
            case "PET_SHOP": {
                int c = shopCount == null ? 0 : shopCount.getOrDefault(sid, 0);
                String t = tierOf(tiers, c, false);
                return new Eval(t != null, t, countProgress(tiers, c, "本学期小店兑换 " + c + " 次", "次"));
            }
            case "COLLECTOR":
                return new Eval(false, null, "集齐本学期全部启用的自动成就即可获得");
            default:
                return new Eval(false, null, null);
        }
    }

    // ---------- 三档评估与进度文本 ----------

    /**
     * 三档阈值评估：tiers 按铜→金顺序，取"v 满足 min"的最高档；都不满足返回 null。
     * smallerBetter=true 为排名类（v≤min 达成，min 随档位升高而减小：10/5/3）。
     */
    private String tierOf(List<Map<String, Object>> tiers, double v, boolean smallerBetter) {
        if (tiers == null) return null;
        String best = null;
        for (Map<String, Object> t : tiers) {
            double min = asDouble(t.get("min"));
            boolean ok = smallerBetter ? v <= min : v >= min;
            if (ok) best = str(t.get("tier"));    // 持续覆盖，最后一个满足的即最高档
        }
        return best;
    }

    /** 解析 rule_params.tiers 三档阈值数组（缺失/损坏返回 null） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tiersOf(Map<String, Object> p) {
        Object o = p.get("tiers");
        if (o instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) return (List<Map<String, Object>>) o;
        return null;
    }

    /**
     * 值类可进阶进度（越大越好）：prefix 含当前值（如"值日参与 15 次"）。
     * 未达铜→"再 X unit 可得🥉"；达铜/银→"当前🥉，再 X unit 升🥈"；金→"已达最高🥇"。
     */
    private String countProgress(List<Map<String, Object>> tiers, double v, String prefix, String unit) {
        if (tiers == null) return null;
        String got = tierOf(tiers, v, false);
        if ("GOLD".equals(got)) return prefix + "，已达最高🥇";
        int idx = got == null ? -1 : TIER_ORDER.indexOf(got);
        Map<String, Object> next = tiers.get(idx + 1);
        long gap = (long) Math.ceil(asDouble(next.get("min")) - v);
        String nextEmoji = TIER_EMOJI.getOrDefault(str(next.get("tier")), "🥉");
        if (got == null) return prefix + "，再 " + gap + " " + unit + "可得" + nextEmoji;
        return prefix + "，当前" + TIER_EMOJI.get(got) + "，再 " + gap + " " + unit + "升" + nextEmoji;
    }

    /** 排名类可进阶进度（v=名次，越小越好）：缺口=当前名次-目标名次，如"再进 2 名进前 10 可得🥉" */
    private String rankProgress(List<Map<String, Object>> tiers, int rank, String prefix) {
        if (tiers == null) return null;
        String got = tierOf(tiers, rank, true);
        if ("GOLD".equals(got)) return prefix + "，已达最高🥇";
        int idx = got == null ? -1 : TIER_ORDER.indexOf(got);
        Map<String, Object> next = tiers.get(idx + 1);
        long topN = (long) asDouble(next.get("min"));
        long gap = rank - topN;
        String nextEmoji = TIER_EMOJI.getOrDefault(str(next.get("tier")), "🥉");
        if (got == null) return prefix + "，再进 " + gap + " 名进前 " + topN + " 可得" + nextEmoji;
        return prefix + "，当前" + TIER_EMOJI.get(got) + "，再进 " + gap + " 名进前 " + topN + " 升" + nextEmoji;
    }

    /** 大满贯进度文本：未集齐"还差 N 枚"；集齐按木桶档提示 */
    private String collectorProgress(List<Map<String, Object>> autoDefs, Map<Long, String> held) {
        long missing = autoDefs.stream()
                .filter(d -> !held.containsKey(((Number) d.get("id")).longValue())).count();
        if (missing > 0) return "还差 " + missing + " 枚集齐大满贯";
        String bucket = null;
        for (Map<String, Object> d : autoDefs) {
            if (((Number) d.get("tiered")).intValue() != 1) continue;
            Long did = ((Number) d.get("id")).longValue();
            String t = held.get(did);
            if (t == null || t.isBlank()) t = str(d.get("tier"));
            bucket = lowerTier(bucket, t);
        }
        if (bucket == null || "BRONZE".equals(bucket)) return "已集齐全部成就，获得大满贯🥉";
        if ("SILVER".equals(bucket)) return "可进阶奖牌全部到🥈，大满贯可升🥈（150 点）";
        return "可进阶奖牌全部到🥇，大满贯可升🥇（300 点）";
    }

    /** 两档取低（木桶）；a 为 null 时直接取 b */
    private String lowerTier(String a, String b) {
        if (a == null) return b;
        int ia = TIER_ORDER.indexOf(a), ib = TIER_ORDER.indexOf(b);
        return ia <= ib ? a : b;
    }

    /** 奖牌有效点数：大满贯(COLLECTOR)铜银金 ×5（50/150/300）；PLATINUM 老白金保留 100 不翻倍 */
    private int medalPoints(String ruleType, String tier) {
        if (tier == null || tier.isBlank()) return 0;
        int p = TIER_POINTS.getOrDefault(tier, 0);
        return "COLLECTOR".equals(ruleType) && !"PLATINUM".equals(tier) ? p * 5 : p;
    }

    // ---------- 数据源 ----------

    /**
     * 加载学期内最近一次考试的成绩明细与总分排名：
     * scores_<studentId> = Map<科目, 分数>；subjMax = Map<科目, 班级最高分>；
     * ranks = Map<studentId, 名次>（总分降序，并列同名次：1,1,3）。
     */
    private Map<String, Object> loadLatestExam(Long uid, String start, String end) {
        Map<String, Object> data = new HashMap<>();
        Map<String, Double> subjMax = new HashMap<>();
        Map<Long, Double> totals = new HashMap<>();
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
                totals.merge(sid, sc, Double::sum);
            });
        }
        data.put("subjMax", subjMax);
        // 总分竞赛排名（并列同名次）
        List<Map.Entry<Long, Double>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<Long, Integer> ranks = new HashMap<>();
        int rank = 0;
        Double prev = null;
        for (int i = 0; i < ranked.size(); i++) {
            double t = ranked.get(i).getValue();
            if (prev == null || Math.abs(t - prev) > 0.01) { rank = i + 1; prev = t; }
            ranks.put(ranked.get(i).getKey(), rank);
        }
        data.put("ranks", ranks);
        return data;
    }

    /**
     * 宠物成就数据源（一次查全班，内存聚合，无 N+1）：
     * petLevel=sid->宠物等级；petAdopted=已领养(adopted_at 非空)学生集合；
     * gradCount=sid->本学期毕业次数(pet_badge)；shopCount=sid->本学期兑换次数(pet_exchange)。
     */
    private Map<String, Object> loadPetData(Long uid, String start, String end) {
        Map<String, Object> data = new HashMap<>();
        Map<Long, Integer> petLevel = new HashMap<>();
        Set<Long> petAdopted = new HashSet<>();
        jdbc.queryForList("SELECT student_id sid, level, adopted_at FROM pet WHERE user_id=?", uid)
                .forEach(r -> {
                    Long sid = ((Number) r.get("sid")).longValue();
                    petLevel.put(sid, r.get("level") == null ? 1 : ((Number) r.get("level")).intValue());
                    if (r.get("adopted_at") != null) petAdopted.add(sid);
                });
        data.put("petLevel", petLevel);
        data.put("petAdopted", petAdopted);
        data.put("gradCount", countByStudent(
                "SELECT student_id sid, COUNT(*) c FROM pet_badge WHERE user_id=? " +
                        "AND created_at BETWEEN ? AND CONCAT(?,' 23:59:59') GROUP BY student_id", uid, start, end));
        data.put("shopCount", countByStudent(
                "SELECT student_id sid, COUNT(*) c FROM pet_exchange WHERE user_id=? " +
                        "AND created_at BETWEEN ? AND CONCAT(?,' 23:59:59') GROUP BY student_id", uid, start, end));
        return data;
    }

    /** 按学生计数的通用查询（SQL 需输出 sid、c 两列） */
    private Map<Long, Integer> countByStudent(String sql, Object... args) {
        Map<Long, Integer> map = new HashMap<>();
        jdbc.queryForList(sql, args).forEach(r ->
                map.put(((Number) r.get("sid")).longValue(), ((Number) r.get("c")).intValue()));
        return map;
    }

    // ---------- 颁发 ----------

    private void insertAward(Long uid, Long sid, Long defId, String term, String source, String remark, String tier) {
        jdbc.update("INSERT IGNORE INTO student_achievement(user_id,student_id,def_id,term,source,remark,tier,awarded_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                uid, sid, defId, term, source, remark, tier, LocalDate.now());
    }

    /** 组装庆祝清单项：kind=award 新获得 / upgrade 升级（带 oldTier） */
    private Map<String, Object> awardItem(Long sid, String name, Map<String, Object> def,
                                          String tier, String kind, String oldTier) {
        Map<String, Object> it = new LinkedHashMap<>();
        it.put("studentId", sid);
        it.put("name", name);
        it.put("defId", ((Number) def.get("id")).longValue());
        it.put("defName", def.get("name"));
        it.put("icon", def.get("icon"));
        it.put("tier", tier);
        it.put("kind", kind);
        if (oldTier != null) it.put("oldTier", oldTier);
        return it;
    }

    // ---------- 辅助 ----------

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
