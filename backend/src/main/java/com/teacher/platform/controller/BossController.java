package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.PetService;
import com.teacher.platform.service.RemoteHub;
import com.teacher.platform.service.TermStatsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每日打怪兽（课堂答题闯关）：每天一只 Boss（uk user_id+boss_date），
 * 手机 /m 出题抽人、大屏 /boss 实时显示，PASS 扣 HP 加成长值，HP 归零击杀三档发奖。
 *
 * 设计要点：
 * - ESCAPED 懒处理：查询时发现 boss_date &lt; 今天且 ACTIVE，返回时置 ESCAPED，不落库（KISS）。
 * - 当前题/当前答题人存内存 Map（单教师单班场景；重启丢失可重抽，不落库）。
 * - 奖励走 petService.award ruleId=null 分值直传（复刻 AUTO_SCORE 模式，分值读 setting 有默认值），
 *   biz_ref 幂等防重复发奖；source=BOSS 触发升级自动置 evolve_pending 由 PC 端补播。
 * - 答案防泄漏：答案只在手机 /m 的出题响应里返回；大屏接口与 SSE payload 均剔除 answer。
 */
@RestController
@RequestMapping("/api/boss")
public class BossController extends BaseController {

    /** Boss 形象 emoji 池（每天随机一只） */
    private static final String[] EMOJI_POOL = {"👾", "🐲", "👹", "🦖", "🦑", "👽", "🤖"};
    /** Boss 随机名字池 */
    private static final String[] NAME_POOL = {
            "小怪兽阿呜", "钢铁大王", "捣蛋鬼嘟嘟", "暗夜魔王", "泡泡糖怪", "雷霆战将", "贪吃小恐龙", "迷雾法师"};

    /** 当前会话（内存态）：当前题 quizId/题目快照 + 当前答题人；重启丢失可重抽 */
    private record BossSession(Long quizId, String question, String categoryName, String categoryIcon,
                               Long pickedStudentId, String pickedStudentName) {}
    private final Map<Long, BossSession> sessions = new ConcurrentHashMap<>();

    private final JdbcTemplate jdbc;
    private final PetService petService;
    private final TermStatsService termStats;
    private final RemoteHub remoteHub;

    public BossController(JdbcTemplate jdbc, PetService petService, TermStatsService termStats, RemoteHub remoteHub) {
        this.jdbc = jdbc;
        this.petService = petService;
        this.termStats = termStats;
        this.remoteHub = remoteHub;
    }

    /** 班主任班 id（抽人范围） */
    private Long classId() {
        return termStats.homeroomClassId(uid());
    }

    // ---------- 查询 ----------

    /**
     * 今日 Boss（大屏进入页面拉取）：无则 data=null（等老师 /m 点开始）；
     * 懒判 ESCAPED（昨天的 ACTIVE 今天查到即视为逃跑，不落库）；
     * 附今日战绩 stats（✅/❌ 次数、击杀者姓名）与内存态当前题/当前答题人（不含答案）。
     */
    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        Long uid = uid();
        Map<String, Object> boss = todayBoss(uid);
        if (boss == null) return Result.ok(null);
        Map<String, Object> data = stateOf(uid, boss);
        return Result.ok(data);
    }

    /** 当日挑战记录：boss + 命中明细（date 缺省今天） */
    @GetMapping("/log")
    public Result<Map<String, Object>> log(@RequestParam(required = false) String date) {
        Long uid = uid();
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        Map<String, Object> boss = bossOf(uid, d);
        if (boss == null) return Result.ok(Map.of("date", d.toString(), "boss", "", "hits", List.of()));
        List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT h.id, h.student_id studentId, s.name studentName, h.quiz_id quizId, " +
                        "q.question, h.result, h.damage, h.created_at createdAt " +
                        "FROM boss_hit h JOIN student s ON s.id=h.student_id AND s.user_id=h.user_id " +
                        "LEFT JOIN quiz_bank q ON q.id=h.quiz_id " +
                        "WHERE h.user_id=? AND h.boss_id=? ORDER BY h.id", uid, boss.get("id"));
        return Result.ok(Map.of("date", d.toString(), "boss", boss, "hits", hits));
    }

    // ---------- 开局 / 出题 / 抽人 / 判定 ----------

    /**
     * 开始今日挑战：幂等——今日已存在（含已击杀）直接返回现有记录，不复活不重建；
     * 新建时随机 emoji + 名字，HP/单次伤害读 setting（缺省 100/10）。
     */
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = todayBoss(uid);
        if (boss == null) {
            int hpTotal = settingInt(uid, "boss.hp_total", 100);
            int hitPerPass = settingInt(uid, "boss.hit_per_pass", 10);
            Random rnd = new Random();
            String emoji = EMOJI_POOL[rnd.nextInt(EMOJI_POOL.length)];
            String name = NAME_POOL[rnd.nextInt(NAME_POOL.length)];
            jdbc.update("INSERT INTO daily_boss(user_id,boss_date,boss_name,boss_emoji,hp_total,hp_left,hit_per_pass) " +
                            "VALUES(?,?,?,?,?,?,?)",
                    uid, LocalDate.now(), name, emoji, hpTotal, hpTotal, hitPerPass);
            boss = todayBoss(uid);
        }
        remoteHub.dispatch(uid, "boss.state", stateOf(uid, boss));
        return Result.ok(stateOf(uid, boss));
    }

    /**
     * 出题（手机端调用）：复用题库抽题逻辑（最久未用优先），题与答案返回给老师手机，
     * 同时记入内存态当前题，SSE boss.state 只推题目不含答案（大屏防泄漏）。
     * 无可用题返回 fail 提示先去题库录题。
     */
    @PostMapping("/draw")
    public Result<Map<String, Object>> draw(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = todayBoss(uid);
        if (boss == null) return Result.fail("今日还没有怪兽，先点「开始」");
        if (!"ACTIVE".equals(boss.get("status"))) return Result.fail("今天的怪兽已经倒下啦，明天再来！");

        Long categoryId = asLong(body.get("categoryId"));
        Integer difficulty = body.get("difficulty") == null ? null : asInt(body.get("difficulty"));
        StringBuilder where = new StringBuilder(" WHERE b.user_id=? AND b.enabled=1 AND c.enabled=1");
        List<Object> args = new ArrayList<>(List.of(uid));
        if (categoryId != null) { where.append(" AND b.category_id=?"); args.add(categoryId); }
        if (difficulty != null) { where.append(" AND b.difficulty=?"); args.add(difficulty); }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.category_id categoryId, c.name categoryName, c.icon categoryIcon, " +
                        "b.question, b.answer, b.difficulty FROM quiz_bank b JOIN quiz_category c ON c.id=b.category_id" +
                        where + " ORDER BY (b.last_used_at IS NULL) DESC, b.last_used_at ASC, RAND() LIMIT 1",
                args.toArray());
        if (rows.isEmpty()) return Result.fail("该条件下没有可用题目，先去 /pet 题库录题或换条件");
        Map<String, Object> quiz = rows.get(0);
        jdbc.update("UPDATE quiz_bank SET use_count=use_count+1, last_used_at=NOW() WHERE id=?", quiz.get("id"));

        // 记内存态当前题（判定时校验 quizId 归属，防重放乱序）
        sessions.put(uid, new BossSession(
                ((Number) quiz.get("id")).longValue(), String.valueOf(quiz.get("question")),
                String.valueOf(quiz.get("categoryName")), String.valueOf(quiz.get("categoryIcon")),
                null, null));
        remoteHub.dispatch(uid, "boss.state", stateOf(uid, boss));
        return Result.ok(quiz);   // 手机端：题目 + 答案
    }

    /**
     * 抽人（手机端调用）：RANDOM=班主任班在籍学生随机，MANUAL=指定 studentId；
     * 记入内存态当前答题人，SSE boss.pick 推大屏高亮。
     */
    @PostMapping("/pick-student")
    public Result<Map<String, Object>> pickStudent(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = todayBoss(uid);
        if (boss == null) return Result.fail("今日还没有怪兽，先点「开始」");

        String mode = str(body.getOrDefault("mode", "RANDOM")).toUpperCase();
        Long studentId = asLong(body.get("studentId"));
        Map<String, Object> student;
        if ("MANUAL".equals(mode) && studentId != null) {
            student = jdbc.queryForList("SELECT id studentId, name FROM student WHERE user_id=? AND id=?",
                    uid, studentId).stream().findFirst().orElse(null);
            if (student == null) return Result.fail("学生不存在");
        } else {
            List<Map<String, Object>> students = jdbc.queryForList(
                    "SELECT id studentId, name FROM student WHERE user_id=? AND class_id=? ORDER BY RAND() LIMIT 1",
                    uid, classId());
            if (students.isEmpty()) return Result.fail("班里还没有学生");
            student = students.get(0);
        }
        Long sid = ((Number) student.get("studentId")).longValue();
        String sname = String.valueOf(student.get("name"));
        // 保留当前题（若有），仅更新答题人
        BossSession old = sessions.get(uid);
        sessions.put(uid, new BossSession(
                old == null ? null : old.quizId(), old == null ? null : old.question(),
                old == null ? null : old.categoryName(), old == null ? null : old.categoryIcon(),
                sid, sname));
        remoteHub.dispatch(uid, "boss.pick", Map.of("studentId", sid, "name", sname));
        return Result.ok(student);
    }

    /**
     * 判定（手机端调用）：{studentId, quizId, result: PASS|FAIL}。
     * quizId 必须属于内存态当前题（防重放乱序）；每次判定写一行 boss_hit（PASS 记 damage）。
     * PASS：award +5（biz_ref=boss:{date}:hit:{hitId} 幂等）→ HP 扣减（UPDATE 守卫 status='ACTIVE' 防击杀后继续打）
     *       → SSE boss.state；若 HP&lt;=0：置 KILLED + 击杀者 award +10 + 全班 award +2（未领养自动跳过）
     *       → SSE boss.killed（大屏爆炸 + 金雨）。
     * FAIL：只写 hit，不扣 HP 不加分（保护积极性）。
     */
    @PostMapping("/judge")
    public Result<Map<String, Object>> judge(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = todayBoss(uid);
        if (boss == null) return Result.fail("今日还没有怪兽，先点「开始」");
        if (!"ACTIVE".equals(boss.get("status"))) return Result.fail("今天的怪兽已经倒下啦");

        Long bossId = ((Number) boss.get("id")).longValue();
        Long studentId = asLong(body.get("studentId"));
        Long quizId = asLong(body.get("quizId"));
        String result = str(body.get("result")).toUpperCase();
        if (studentId == null) return Result.fail("缺少学生");
        if (!"PASS".equals(result) && !"FAIL".equals(result)) return Result.fail("result 必须为 PASS 或 FAIL");
        BossSession session = sessions.get(uid);
        if (session == null || session.quizId() == null || quizId == null || !quizId.equals(session.quizId())) {
            return Result.fail("请先出题再判定");
        }
        // 学生须在籍
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE user_id=? AND id=?",
                Integer.class, uid, studentId);
        if (exists == null || exists == 0) return Result.fail("学生不存在");

        String date = String.valueOf(boss.get("boss_date"));
        boolean pass = "PASS".equals(result);
        int hitPerPass = ((Number) boss.get("hit_per_pass")).intValue();
        int damage = pass ? hitPerPass : 0;

        // 写命中明细（biz_ref 由调用方不需要感知，hit 行本身允许重复判定——幂等由 pet_log 的 biz_ref 保证）
        jdbc.update("INSERT INTO boss_hit(user_id,boss_id,student_id,quiz_id,result,damage) VALUES(?,?,?,?,?,?)",
                uid, bossId, studentId, quizId, result, damage);
        Long hitId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Map<String, Object> data = new HashMap<>();
        if (pass) {
            // 单次通关加分（幂等：source=BOSS + biz_ref）
            int passScore = settingInt(uid, "boss.pass_score", 5);
            petService.award(uid, studentId, null, passScore, "BOSS",
                    "boss:" + date + ":hit:" + hitId, "打怪兽通关");
            // HP 扣减（守卫 ACTIVE：击杀后同秒并发的判定不再扣血）
            jdbc.update("UPDATE daily_boss SET hp_left = hp_left - ? WHERE id=? AND status='ACTIVE'", damage, bossId);
            boss = todayBoss(uid);
            int hpLeft = ((Number) boss.get("hp_left")).intValue();
            if (hpLeft <= 0) {
                // 击杀：置 KILLED + 击杀者 +10 + 全班 +2（只给有宠物的发，award 内部自动跳过未领养）
                jdbc.update("UPDATE daily_boss SET status='KILLED', hp_left=0, kill_student_id=?, killed_at=NOW() WHERE id=?",
                        studentId, bossId);
                int killScore = settingInt(uid, "boss.kill_score", 10);
                petService.award(uid, studentId, null, killScore, "BOSS",
                        "boss:" + date + ":kill:" + studentId, "打怪兽击杀");
                int allScore = settingInt(uid, "boss.kill_all_score", 2);
                List<Map<String, Object>> classmates = jdbc.queryForList(
                        "SELECT id FROM student WHERE user_id=? AND class_id=?", uid, classId());
                for (Map<String, Object> c : classmates) {
                    Long sid = ((Number) c.get("id")).longValue();
                    petService.award(uid, sid, null, allScore, "BOSS",
                            "boss:" + date + ":ALL:" + sid, "全班击杀同庆");
                }
                boss = todayBoss(uid);
                String killerName = String.valueOf(jdbc.queryForObject(
                        "SELECT name FROM student WHERE user_id=? AND id=?", String.class, uid, studentId));
                data.put("killed", true);
                data.put("killStudentName", killerName);
                remoteHub.dispatch(uid, "boss.killed",
                        Map.of("killStudentName", killerName, "stats", statsOf(uid, boss)));
            }
        }
        // 当前题消费掉（一题一判），SSE 推大屏刷新
        sessions.remove(uid);
        Map<String, Object> state = stateOf(uid, boss);
        data.putAll(state);
        remoteHub.dispatch(uid, "boss.state", state);
        return Result.ok(data);
    }

    // ---------- 内部 ----------

    /**
     * 重置今日挑战（E2E 测试专用配套）：逐条撤回当日 BOSS 流水（回滚成长值/等级，
     * 含击杀者 +10 与全班同庆 +2——直接 DELETE 流水会留下虚高 exp），再删命中明细与 Boss、清内存会话。
     * 另清全班 evolve_pending：BOSS 为异步 source，加分跨级会置待演标记，revoke 只回 exp/level 不清标记，
     * 否则残留 pending 会让座次表页误补播进化演出。生产界面不暴露此入口。
     */
    @PostMapping("/reset")
    public Result<Void> reset() {
        Long uid = uid();
        List<Long> logIds = jdbc.queryForList(
                "SELECT id FROM pet_log WHERE user_id=? AND source='BOSS' AND DATE(created_at)=CURDATE()",
                Long.class, uid);
        for (Long logId : logIds) petService.revokeLog(uid, logId);
        // 清待演标记（与 boss/流水是否存在无关，幂等兜底；E2E 专用，回滚 BOSS 异步跨级副作用）
        jdbc.update("UPDATE pet SET evolve_pending=0 WHERE user_id=?", uid);
        Map<String, Object> boss = todayBoss(uid);
        if (boss != null) {
            Object bossId = boss.get("id");
            jdbc.update("DELETE FROM boss_hit WHERE user_id=? AND boss_id=?", uid, bossId);
            jdbc.update("DELETE FROM daily_boss WHERE user_id=? AND id=?", uid, bossId);
        }
        sessions.remove(uid);
        return Result.ok(null);
    }

    /** 今日 Boss 行（无则 null）；懒判 ESCAPED：boss_date<今天 且 ACTIVE → 返回时置 ESCAPED 不落库 */
    private Map<String, Object> todayBoss(Long uid) {
        return bossOf(uid, LocalDate.now());
    }

    private Map<String, Object> bossOf(Long uid, LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM daily_boss WHERE user_id=? AND boss_date=?", uid, date);
        if (rows.isEmpty()) return null;
        Map<String, Object> boss = new HashMap<>(rows.get(0));
        if ("ACTIVE".equals(boss.get("status")) && date.isBefore(LocalDate.now())) {
            boss.put("status", "ESCAPED");
        }
        return boss;
    }

    /**
     * 大屏/手机共用的整体状态：boss 全量字段 + 当前题（无答案）+ 当前答题人 + 今日战绩。
     * 全班各学生今日得分并入 stats.stars（{studentId,name,score} 前三）。
     */
    private Map<String, Object> stateOf(Long uid, Map<String, Object> boss) {
        BossSession s = sessions.get(uid);
        Map<String, Object> state = new HashMap<>(boss);
        state.put("question", s == null ? null : s.question());
        state.put("categoryName", s == null ? null : s.categoryName());
        state.put("categoryIcon", s == null ? null : s.categoryIcon());
        state.put("pickedStudentId", s == null ? null : s.pickedStudentId());
        state.put("pickedStudentName", s == null ? null : s.pickedStudentName());
        state.put("stats", statsOf(uid, boss));
        return state;
    }

    /** 今日战绩：pass/fail 次数 + 击杀王姓名 + 今日加分前三（含击杀/同庆） */
    private Map<String, Object> statsOf(Long uid, Map<String, Object> boss) {
        Map<String, Object> stats = new HashMap<>();
        Object bossId = boss.get("id");
        List<Map<String, Object>> cnt = jdbc.queryForList(
                "SELECT result, COUNT(*) c FROM boss_hit WHERE user_id=? AND boss_id=? GROUP BY result", uid, bossId);
        int passCnt = 0, failCnt = 0;
        for (Map<String, Object> r : cnt) {
            if ("PASS".equals(r.get("result"))) passCnt = ((Number) r.get("c")).intValue();
            else failCnt = ((Number) r.get("c")).intValue();
        }
        stats.put("pass", passCnt);
        stats.put("fail", failCnt);
        Object killer = boss.get("kill_student_id");
        if (killer != null) {
            try {
                stats.put("killStudentName", jdbc.queryForObject(
                        "SELECT name FROM student WHERE user_id=? AND id=?", String.class, uid, killer));
            } catch (Exception ignore) { }
        }
        // 今日 BOSS 加分前三（打分口径与宠物周榜一致：只 SUM 正分）
        List<Map<String, Object>> top = jdbc.queryForList(
                "SELECT l.student_id studentId, s.name, SUM(l.score) score FROM pet_log l " +
                        "JOIN student s ON s.id=l.student_id AND s.user_id=l.user_id " +
                        "WHERE l.user_id=? AND l.source='BOSS' AND l.score>0 AND DATE(l.created_at)=CURDATE() " +
                        "GROUP BY l.student_id, s.name ORDER BY score DESC LIMIT 3", uid);
        stats.put("stars", top);
        return stats;
    }

    // ---------- 辅助（与 PetController 同款） ----------

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
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
