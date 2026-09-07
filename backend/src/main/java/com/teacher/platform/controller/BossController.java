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
 * 打怪兽（课堂答题闯关）· 多模式场次化：
 * 一天可多场，每场一只 Boss；四种模式——
 * - RANDOM 随机：答对答错都随机切下一个学生，答对加分；
 * - CLASS 班级：按座位列顺序一列一列挨个答题，一轮全班全对触发「团结就是力量」（可累计成就）；
 * - GROUP 小组：一组一怪（座位的一列=一组），怪血量=该组人数，每人答对掉 1 滴，整组全对击杀 →
 *   该组组员额外加分（无最后一击个人奖、无全班同庆）；未击杀不加分，换下一组新怪；
 * - MANUAL 点名：老师手动选学生，不自动切人。
 *
 * 设计要点：
 * - 题库场（category_id 非空）开局与每次判定后自动抽题；抽背场（category_id 为空）无题，老师口头布置。
 * - 血量：RANDOM/CLASS = target_rounds × hit_per_pass；GROUP 一组一怪，怪血量=当前组人数（每人 1 滴）。
 *   答错不扣血，故打不满一轮/一组不会被秒杀。
 * - 当前题/当前答题人/轮次存内存 Map（按 bossId 隔离，单教师场景；重启丢失可由 /state 重进）。
 * - 奖励走 petService.award（biz_ref 幂等防重复发奖）；source=BOSS 触发升级自动置 evolve_pending。
 * - 答案防泄漏：大屏 state/SSE 均不含 answer；手机 /draw 才返回答案。
 * - 进行中场不提供删除/取消接口（防误删）；/reset 仅供 E2E 回滚。
 */
@RestController
@RequestMapping("/api/boss")
public class BossController extends BaseController {

    /** Boss 形象图片编号池（对应 frontend/public/boss/monsterN.png，透明底抠图，每场随机一只）。
     *  boss_emoji 列仅 VARCHAR(8) 存不下路径，故只存编号 "1"~"4"，前端拼 /boss/monster{n}.png。 */
    private static final String[] IMG_POOL = {"1", "2", "3", "4"};
    /** Boss 随机名字池 */
    private static final String[] NAME_POOL = {
            "小怪兽阿呜", "钢铁大王", "捣蛋鬼嘟嘟", "暗夜魔王", "泡泡糖怪", "雷霆战将", "贪吃小恐龙", "迷雾法师"};
    /** 模式中文名（默认标题用） */
    private static final Map<String, String> MODE_CN = Map.of(
            "RANDOM", "随机挑战", "CLASS", "班级挑战", "GROUP", "小组挑战", "MANUAL", "点名挑战");

    /**
     * 单场内存态：当前题（抽背为 null）+ 当前答题人（点名模式等待时为 null）+ 轮次 + 小组列号。
     * key=bossId，多场互不干扰；重启丢失，重进 /state 后由前端引导继续（题/人可重抽）。
     */
    private record BossSession(Long quizId, String question, String categoryName, String categoryIcon,
                               Long pickedStudentId, String pickedStudentName,
                               int roundNo, Integer groupCol) {}
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

    /** 班主任班 id（抽人/发奖范围） */
    private Long classId() {
        return termStats.homeroomClassId(uid());
    }

    // ---------- 查询：场次列表 / 单场状态 / 报告 ----------

    /**
     * 今日场次（大屏进入页兜底）：返回今天最新一场（ACTIVE 优先），无则 null。
     * 大屏新流程以 /sessions 列表 + /state?bossId= 为准；本接口保留给手机 /m 与旧前端。
     */
    @GetMapping("/today")
    public Result<Map<String, Object>> today() {
        Long uid = uid();
        Map<String, Object> boss = latestBossToday(uid);
        if (boss == null) return Result.ok(null);
        ensureTurn(uid, boss);
        return Result.ok(stateOf(uid, boss));
    }

    /** 近期场次列表（今天+昨天，新在上）：供大屏「新建 / 继续 / 看报告」选择；跨天 ACTIVE 懒判 ESCAPED。 */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> sessions() {
        Long uid = uid();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.title, b.boss_name bossName, b.boss_emoji bossEmoji, b.mode, b.status, " +
                        "b.boss_date bossDate, b.hp_total hpTotal, b.hp_left hpLeft, b.hit_per_pass hitPerPass, " +
                        "b.round_no roundNo, b.target_rounds targetRounds, b.kill_student_id killStudentId, " +
                        "b.created_at createdAt, b.ended_at endedAt, " +
                        "(SELECT COUNT(*) FROM boss_hit h WHERE h.boss_id=b.id AND h.result='PASS') passCnt, " +
                        "(SELECT COUNT(*) FROM boss_hit h WHERE h.boss_id=b.id AND h.result='FAIL') failCnt, " +
                        "(SELECT s.name FROM student s WHERE s.id=b.kill_student_id) killStudentName " +
                        "FROM daily_boss b WHERE b.user_id=? AND b.boss_date >= CURDATE() - INTERVAL 1 DAY " +
                        "ORDER BY b.id DESC", uid);
        for (Map<String, Object> r : rows) lazyEscaped(r);
        return Result.ok(rows);
    }

    /** 指定场次状态（大屏继续战斗 / 刷新）；bossId 缺省回退今日最新场。 */
    @GetMapping("/state")
    public Result<Map<String, Object>> state(@RequestParam(required = false) Long bossId) {
        Long uid = uid();
        Map<String, Object> boss = bossId == null ? latestBossToday(uid) : bossById(uid, bossId);
        if (boss == null) return Result.ok(null);
        ensureTurn(uid, boss);
        return Result.ok(stateOf(uid, boss));
    }

    /** 进行中场若内存轮次丢失（后端重启），按已判定记录重建当前题/答题人，避免大屏空等。 */
    private void ensureTurn(Long uid, Map<String, Object> boss) {
        if (!"ACTIVE".equals(boss.get("status"))) return;
        Long bossId = ((Number) boss.get("id")).longValue();
        if (!sessions.containsKey(bossId)) advance(uid, bossId);
    }

    /** 结束场战报：模式/标题/击杀者/对错数/小组排名/逐条明细。 */
    @GetMapping("/report")
    public Result<Map<String, Object>> report(@RequestParam Long bossId) {
        Long uid = uid();
        Map<String, Object> boss = bossById(uid, bossId);
        if (boss == null) return Result.fail("场次不存在");
        lazyEscaped(boss);
        List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT h.id, h.student_id studentId, s.name studentName, h.quiz_id quizId, h.round_no roundNo, " +
                        "h.group_col groupCol, q.question, h.result, h.damage, h.created_at createdAt " +
                        "FROM boss_hit h JOIN student s ON s.id=h.student_id AND s.user_id=h.user_id " +
                        "LEFT JOIN quiz_bank q ON q.id=h.quiz_id " +
                        "WHERE h.user_id=? AND h.boss_id=? ORDER BY h.id", uid, bossId);
        Map<String, Object> data = new HashMap<>(boss);
        data.put("modeCn", MODE_CN.getOrDefault(str(boss.get("mode")), "挑战"));
        data.put("hits", hits);
        data.put("stats", statsOf(uid, boss));
        data.put("groups", groupRanking(uid, bossId));
        return Result.ok(data);
    }

    /** 当日挑战记录（旧接口，保留兼容）：取当天最新一场 + 明细。 */
    @GetMapping("/log")
    public Result<Map<String, Object>> log(@RequestParam(required = false) String date) {
        Long uid = uid();
        LocalDate d = date == null || date.isBlank() ? LocalDate.now() : LocalDate.parse(date);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM daily_boss WHERE user_id=? AND boss_date=? ORDER BY id DESC LIMIT 1", uid, d);
        if (rows.isEmpty()) return Result.ok(Map.of("date", d.toString(), "boss", "", "hits", List.of()));
        Map<String, Object> boss = rows.get(0);
        List<Map<String, Object>> hits = jdbc.queryForList(
                "SELECT h.id, h.student_id studentId, s.name studentName, h.quiz_id quizId, " +
                        "q.question, h.result, h.damage, h.created_at createdAt " +
                        "FROM boss_hit h JOIN student s ON s.id=h.student_id AND s.user_id=h.user_id " +
                        "LEFT JOIN quiz_bank q ON q.id=h.quiz_id " +
                        "WHERE h.user_id=? AND h.boss_id=? ORDER BY h.id", uid, boss.get("id"));
        return Result.ok(Map.of("date", d.toString(), "boss", boss, "hits", hits));
    }

    // ---------- 开局 / 判定（主流程） ----------

    /**
     * 开始一场挑战。
     * 新流程（大屏，带 mode）：每次都新建一场。入参 {mode,title,categoryId,difficulty}。
     * 旧流程（手机 /m，无 mode）：幂等——今日已有 ACTIVE 场直接返回，否则建 RANDOM 场。
     * 血量：CLASS/RANDOM/MANUAL = target_rounds × hit_per_pass（CLASS target=班级人数，RANDOM/MANUAL=min(人数, boss.random_target 默认20)）；
     * GROUP 一组一怪，hit_per_pass=1、target_rounds=小组数，开局血量=第一组人数（换组时重置为下一组人数）。
     * 开局自动：有题库抽第一题；按模式选第一人（MANUAL 不选人等点名）。
     */
    @PostMapping("/start")
    public Result<Map<String, Object>> start(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String mode = str(body.get("mode")).toUpperCase();
        boolean legacy = mode.isBlank();
        if (legacy) {
            Map<String, Object> active = activeBoss(uid);
            if (active != null) {
                remoteHub.dispatch(uid, "boss.state", stateOf(uid, active));
                return Result.ok(stateOf(uid, active));
            }
            mode = "RANDOM";
        }
        if (!MODE_CN.containsKey(mode)) return Result.fail("未知模式：" + mode);

        Long classId = classId();
        List<Map<String, Object>> roster = roster(uid);
        int n = roster.size();
        if (n == 0) return Result.fail("班里还没有学生，先去花名册添加");
        List<Map<String, Object>> seat = seatSeq(uid);
        if ("GROUP".equals(mode) && seat.isEmpty()) {
            return Result.fail("小组模式需要先在「座次表」排好座位（小组=座位的列）");
        }
        if ("GROUP".equals(mode) && seat.size() < n) {
            return Result.fail("还有学生没排座位，小组模式需要先在座次表给每个学生排好座（小组=座位的列）");
        }

        Long categoryId = asLong(body.get("categoryId"));
        Integer difficulty = body.get("difficulty") == null ? null : asInt(body.get("difficulty"));
        int settingHit = settingInt(uid, "boss.hit_per_pass", 10);
        int hitPerPass;
        int targetRounds;
        int hpTotal;
        if ("GROUP".equals(mode)) {
            // 一组一怪：怪血量=当前组人数，每人答对掉 1 滴（hit_per_pass=1），整组全对正好击杀
            List<Integer> cols = groupCols(uid);
            hitPerPass = 1;
            targetRounds = Math.max(cols.size(), 1);
            hpTotal = groupMembers(uid, cols.get(0)).size();
        } else if ("CLASS".equals(mode)) {
            hitPerPass = settingHit;
            targetRounds = n;
            hpTotal = targetRounds * hitPerPass;
        } else {
            hitPerPass = settingHit;
            targetRounds = Math.min(n, settingInt(uid, "boss.random_target", 20));
            hpTotal = targetRounds * hitPerPass;
        }

        String title = str(body.get("title"));
        if (title.isBlank()) {
            LocalDate today = LocalDate.now();
            title = today.getMonthValue() + "月" + today.getDayOfMonth() + "日 · " + MODE_CN.get(mode);
        }
        Random rnd = new Random();
        String emoji = IMG_POOL[rnd.nextInt(IMG_POOL.length)];
        String name = NAME_POOL[rnd.nextInt(NAME_POOL.length)];

        jdbc.update("INSERT INTO daily_boss(user_id,boss_date,boss_name,boss_emoji,mode,title,category_id,difficulty," +
                        "hp_total,hp_left,hit_per_pass,round_no,target_rounds,status) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?,'ACTIVE')",
                uid, LocalDate.now(), name, emoji, mode, title, categoryId, difficulty,
                hpTotal, hpTotal, hitPerPass, targetRounds);
        Map<String, Object> boss = activeBoss(uid);
        Long bossId = ((Number) boss.get("id")).longValue();

        advance(uid, bossId);   // 开局自动抽题 + 选第一人
        boss = bossById(uid, bossId);
        remoteHub.dispatch(uid, "boss.state", stateOf(uid, boss));
        return Result.ok(stateOf(uid, boss));
    }

    /**
     * 判定（大屏主操作）：{bossId(可空，缺省今日 ACTIVE), studentId, quizId(抽背空), result: PASS|FAIL}。
     * 写一行 boss_hit（带轮次/小组列）；PASS 加分扣血，FAIL 仅记录。
     * HP 归零 → 非 GROUP：KILLED，击杀者 +kill_score、全班 +kill_all_score；CLASS 整轮全对另触发团结奖。
     * GROUP：一组一怪，当前组整组答对即击杀该组怪 → 仅该组组员 +group_win_score（无最后一击、无全班奖），
     *        随后换下一组新怪；未击杀（有人答错）不加分直接换组；全部组答完收尾（有组击杀→KILLED，否则→ESCAPED）。
     * 未结束则自动推进（抽下题/切下一人，MANUAL 等点名）。
     */
    @PostMapping("/judge")
    public Result<Map<String, Object>> judge(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long bossId = asLong(body.get("bossId"));
        Map<String, Object> boss = bossId == null ? activeBoss(uid) : bossById(uid, bossId);
        if (boss == null) return Result.fail("没有进行中的怪兽，先新建一场");
        if (!"ACTIVE".equals(boss.get("status"))) return Result.fail("这场怪兽已经结束啦");
        bossId = ((Number) boss.get("id")).longValue();
        String mode = str(boss.get("mode")).toUpperCase();

        String result = str(body.get("result")).toUpperCase();
        if (!"PASS".equals(result) && !"FAIL".equals(result)) return Result.fail("result 必须为 PASS 或 FAIL");

        BossSession session = sessions.get(bossId);
        Long categoryId = asLong(boss.get("category_id"));
        Long quizId;
        if (categoryId != null) {
            // 题库场：当前题必须存在且与提交一致（防重复判定/乱序）
            Long curQuiz = session == null ? null : session.quizId();
            quizId = asLong(body.get("quizId"));
            if (curQuiz == null || quizId == null || !quizId.equals(curQuiz)) {
                return Result.fail("题目已自动切换，请按当前题目判定");
            }
        } else {
            quizId = null;   // 抽背场：无题
        }

        Long studentId;
        if ("MANUAL".equals(mode)) {
            studentId = asLong(body.get("studentId"));
            if (studentId == null) return Result.fail("点名模式请先选择学生");
        } else {
            studentId = session == null ? null : session.pickedStudentId();
            if (studentId == null) return Result.fail("还没轮到学生答题，请稍候");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE user_id=? AND id=?",
                Integer.class, uid, studentId);
        if (exists == null || exists == 0) return Result.fail("学生不存在");

        int roundNo = session != null ? session.roundNo() : asInt(boss.get("round_no"));
        Integer groupCol = session != null ? session.groupCol() : null;
        boolean pass = "PASS".equals(result);
        int hitPerPass = ((Number) boss.get("hit_per_pass")).intValue();
        int damage = pass ? hitPerPass : 0;

        jdbc.update("INSERT INTO boss_hit(user_id,boss_id,student_id,quiz_id,round_no,group_col,result,damage) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                uid, bossId, studentId, quizId, roundNo, groupCol, result, damage);
        Long hitId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Map<String, Object> data = new HashMap<>();
        if (pass) {
            int passScore = settingInt(uid, "boss.pass_score", 5);
            petService.award(uid, studentId, null, passScore, "BOSS",
                    "boss:" + bossId + ":hit:" + hitId, "打怪兽通关");
            jdbc.update("UPDATE daily_boss SET hp_left = hp_left - ? WHERE id=? AND status='ACTIVE'", damage, bossId);
            // 班级模式：本轮全班每人各答一次且全对 → 团结奖（与击杀同判，幂等）
            maybeAwardUnity(uid, boss, bossId, roundNo);
            boss = bossById(uid, bossId);
            int hpLeft = ((Number) boss.get("hp_left")).intValue();
            if (hpLeft <= 0) {
                if ("GROUP".equals(mode)) {
                    // 一组一怪：当前组怪被整组击杀 → 该组组员加分（不发最后一击个人奖、不发全班同庆）
                    int gc = groupCol != null ? groupCol : -1;
                    awardGroupKill(uid, bossId, gc);
                    int curRound = asInt(boss.get("round_no"));
                    if (curRound >= groupCols(uid).size()) {
                        // 最后一组击杀 → 整场结束
                        jdbc.update("UPDATE daily_boss SET status='KILLED', hp_left=0, ended_at=NOW() " +
                                "WHERE id=? AND status='ACTIVE'", bossId);
                        sessions.remove(bossId);
                        data.put("killed", true);
                        data.put("allDone", true);
                        boss = bossById(uid, bossId);
                        remoteHub.dispatch(uid, "boss.killed",
                                Map.of("bossId", bossId, "allDone", true, "stats", statsOf(uid, boss)));
                    }
                    // 非最后一组：保持 ACTIVE，方法末尾 advance 会换下一组的新怪
                } else {
                    jdbc.update("UPDATE daily_boss SET status='KILLED', hp_left=0, kill_student_id=?, " +
                                    "killed_at=NOW(), ended_at=NOW() WHERE id=? AND status='ACTIVE'",
                            studentId, bossId);
                    // 击杀者 +kill_score
                    int killScore = settingInt(uid, "boss.kill_score", 10);
                    petService.award(uid, studentId, null, killScore, "BOSS",
                            "boss:" + bossId + ":kill:" + studentId, "打怪兽击杀");
                    // 全班 +kill_all_score（award 内部自动跳过未领养宠物的学生）
                    int allScore = settingInt(uid, "boss.kill_all_score", 2);
                    for (Map<String, Object> c : roster(uid)) {
                        Long sid = ((Number) c.get("studentId")).longValue();
                        petService.award(uid, sid, null, allScore, "BOSS",
                                "boss:" + bossId + ":ALL:" + sid, "全班击杀同庆");
                    }
                    boss = bossById(uid, bossId);
                    sessions.remove(bossId);
                    String killerName = String.valueOf(jdbc.queryForObject(
                            "SELECT name FROM student WHERE user_id=? AND id=?", String.class, uid, studentId));
                    data.put("killed", true);
                    data.put("killStudentName", killerName);
                    remoteHub.dispatch(uid, "boss.killed",
                            Map.of("killStudentName", killerName, "bossId", bossId, "stats", statsOf(uid, boss)));
                }
            }
        }

        if ("ACTIVE".equals(bossById(uid, bossId).get("status"))) {
            advance(uid, bossId);   // 未击杀：自动切题切人（GROUP 末组答完也可能在此收尾）
        }
        boss = bossById(uid, bossId);
        if (!"ACTIVE".equals(boss.get("status"))) {
            data.put("killed", "KILLED".equals(boss.get("status")));
            data.put("allDone", "GROUP".equals(mode));
        }
        Map<String, Object> state = stateOf(uid, boss);
        data.putAll(state);
        remoteHub.dispatch(uid, "boss.state", state);
        return Result.ok(data);
    }

    // ---------- 手机 /m 旧流程保留（出题 / 抽人，作用于今日 ACTIVE 场） ----------

    /** 出题（手机端）：返回题 + 答案（仅手机展示），写入内存态当前题；大屏 state 不含答案。 */
    @PostMapping("/draw")
    public Result<Map<String, Object>> draw(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = activeBoss(uid);
        if (boss == null) return Result.fail("还没有进行中的怪兽，先点「开始」");
        Long bossId = ((Number) boss.get("id")).longValue();
        Long categoryId = body.get("categoryId") != null ? asLong(body.get("categoryId")) : asLong(boss.get("category_id"));
        Integer difficulty = body.get("difficulty") != null ? asInt(body.get("difficulty"))
                : (boss.get("difficulty") == null ? null : asInt(boss.get("difficulty")));
        Map<String, Object> quiz = drawQuizRow(uid, categoryId, difficulty);
        if (quiz == null) return Result.fail("该条件下没有可用题目，先去题库录题或换条件");
        BossSession old = sessions.get(bossId);
        sessions.put(bossId, new BossSession(
                ((Number) quiz.get("id")).longValue(), String.valueOf(quiz.get("question")),
                String.valueOf(quiz.get("categoryName")), String.valueOf(quiz.get("categoryIcon")),
                old == null ? null : old.pickedStudentId(), old == null ? null : old.pickedStudentName(),
                old == null ? asInt(boss.get("round_no")) : old.roundNo(),
                old == null ? null : old.groupCol()));
        remoteHub.dispatch(uid, "boss.state", stateOf(uid, bossById(uid, bossId)));
        return Result.ok(quiz);
    }

    /** 抽人（手机端）：RANDOM 班级随机 / MANUAL 指定学生；写入内存态，SSE boss.pick 推大屏。 */
    @PostMapping("/pick-student")
    public Result<Map<String, Object>> pickStudent(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Map<String, Object> boss = activeBoss(uid);
        if (boss == null) return Result.fail("还没有进行中的怪兽，先点「开始」");
        Long bossId = ((Number) boss.get("id")).longValue();
        String pickMode = str(body.getOrDefault("mode", "RANDOM")).toUpperCase();
        Long studentId = asLong(body.get("studentId"));
        Map<String, Object> student;
        if ("MANUAL".equals(pickMode) && studentId != null) {
            student = jdbc.queryForList("SELECT id studentId, name FROM student WHERE user_id=? AND id=?",
                    uid, studentId).stream().findFirst().orElse(null);
            if (student == null) return Result.fail("学生不存在");
        } else {
            List<Map<String, Object>> roster = roster(uid);
            if (roster.isEmpty()) return Result.fail("班里还没有学生");
            student = roster.get(new Random().nextInt(roster.size()));
        }
        Long sid = ((Number) student.get("studentId")).longValue();
        String sname = String.valueOf(student.get("name"));
        BossSession old = sessions.get(bossId);
        sessions.put(bossId, new BossSession(
                old == null ? null : old.quizId(), old == null ? null : old.question(),
                old == null ? null : old.categoryName(), old == null ? null : old.categoryIcon(),
                sid, sname,
                old == null ? asInt(boss.get("round_no")) : old.roundNo(),
                old == null ? null : old.groupCol()));
        remoteHub.dispatch(uid, "boss.pick", Map.of("studentId", sid, "name", sname, "bossId", bossId));
        return Result.ok(student);
    }

    // ---------- 内部：推进 / 发奖 ----------

    /**
     * 推进到下一答题轮：题库场先抽下一题；再按模式选下一人。
     * CLASS：按座位列序挨个叫（未排座的补在队尾，保证人人被叫到），一轮答完 round_no+1 从头再来。
     * GROUP：一组一怪——组内按排号挨个叫，整组答完切下一组（换怪、重置血量=下一组人数）；
     *        全部组答完则收尾（有任意一组击杀→KILLED，否则→ESCAPED）。
     * RANDOM：随机且避开上一人；MANUAL：不选人等老师点名。
     */
    private void advance(Long uid, Long bossId) {
        Map<String, Object> boss = bossById(uid, bossId);
        String mode = str(boss.get("mode")).toUpperCase();
        int roundNo = asInt(boss.get("round_no"));

        // 抽题（题库场）
        Map<String, Object> quiz = null;
        Long categoryId = asLong(boss.get("category_id"));
        if (categoryId != null) quiz = drawQuizRow(uid, categoryId, asIntOrNull(boss.get("difficulty")));
        Long quizId = quiz == null ? null : ((Number) quiz.get("id")).longValue();
        String question = quiz == null ? null : String.valueOf(quiz.get("question"));
        String catName = quiz == null ? null : String.valueOf(quiz.get("categoryName"));
        String catIcon = quiz == null ? null : String.valueOf(quiz.get("categoryIcon"));

        Long pickId = null;
        String pickName = null;
        Integer groupCol = null;

        if ("GROUP".equals(mode)) {
            // 一组一怪：round_no=当前是第几组（1 起），group_col=物理列号；怪血量=该组人数，每人答对掉 1 滴
            List<Integer> cols = groupCols(uid);
            int gIdx = roundNo - 1;
            boolean ended = gIdx >= cols.size();
            if (!ended) {
                int col = cols.get(gIdx);
                List<Map<String, Object>> members = groupMembers(uid, col);
                int answered = countGroupHits(uid, bossId, col);
                if (answered >= members.size()) {
                    // 本组每人都答过 → 换下一组新怪（无论击杀与否：未击杀不加分，直接换）
                    gIdx++;
                    if (gIdx >= cols.size()) {
                        ended = true;
                    } else {
                        int nextCol = cols.get(gIdx);
                        List<Map<String, Object>> nextMembers = groupMembers(uid, nextCol);
                        jdbc.update("UPDATE daily_boss SET round_no=?, hp_total=?, hp_left=? WHERE id=?",
                                gIdx + 1, nextMembers.size(), nextMembers.size(), bossId);
                        roundNo = gIdx + 1;
                        Map<String, Object> first = nextMembers.get(0);
                        pickId = ((Number) first.get("sid")).longValue();
                        pickName = String.valueOf(first.get("name"));
                        groupCol = nextCol;
                    }
                } else {
                    // 组内下一个人（按排号）
                    Map<String, Object> cur = members.get(answered);
                    pickId = ((Number) cur.get("sid")).longValue();
                    pickName = String.valueOf(cur.get("name"));
                    groupCol = col;
                }
            }
            if (ended) {
                // 所有组答完收尾：有任意一组整组答对（怪被击杀）→ KILLED，否则 → ESCAPED
                boolean killedAny = groupRanking(uid, bossId).stream()
                        .anyMatch(r -> Boolean.TRUE.equals(r.get("killed")));
                jdbc.update("UPDATE daily_boss SET status=?, ended_at=NOW() WHERE id=? AND status='ACTIVE'",
                        killedAny ? "KILLED" : "ESCAPED", bossId);
                sessions.remove(bossId);
                if (killedAny) {
                    remoteHub.dispatch(uid, "boss.killed",
                            Map.of("bossId", bossId, "allDone", true, "stats", statsOf(uid, bossById(uid, bossId))));
                }
                return;
            }
        } else if ("CLASS".equals(mode)) {
            // 按座位列序挨个叫，未排座学生补队尾，保证每个学生都被叫到
            List<Map<String, Object>> seq = classSeq(uid);
            int done = countRoundHits(uid, bossId, roundNo);
            int idx = done;
            if (idx >= seq.size()) {
                // 本轮结束 → 轮次 +1，从队首开始
                jdbc.update("UPDATE daily_boss SET round_no = round_no + 1 WHERE id=?", bossId);
                roundNo++;
                idx = 0;
            }
            if (idx < seq.size()) {
                Map<String, Object> cur = seq.get(idx);
                pickId = ((Number) cur.get("studentId")).longValue();
                pickName = String.valueOf(cur.get("name"));
            }
        } else if ("RANDOM".equals(mode)) {
            List<Map<String, Object>> roster = roster(uid);
            Long avoid = sessions.containsKey(bossId) ? sessions.get(bossId).pickedStudentId() : null;
            List<Map<String, Object>> pool = new ArrayList<>(roster);
            if (avoid != null && pool.size() > 1) {
                pool.removeIf(r -> avoid.equals(((Number) r.get("studentId")).longValue()));
            }
            if (!pool.isEmpty()) {
                Map<String, Object> cur = pool.get(new Random().nextInt(pool.size()));
                pickId = ((Number) cur.get("studentId")).longValue();
                pickName = String.valueOf(cur.get("name"));
            }
        }
        // MANUAL：pickId 留空，等老师点名

        sessions.put(bossId, new BossSession(quizId, question, catName, catIcon, pickId, pickName, roundNo, groupCol));
    }

    /** 班级模式团结奖：本轮全班每人各答一次且全 PASS → 全班各 +unity_score（biz 幂等）。成就由扫描器统计。 */
    private void maybeAwardUnity(Long uid, Map<String, Object> boss, Long bossId, int roundNo) {
        if (!"CLASS".equals(str(boss.get("mode")).toUpperCase())) return;
        int n = roster(uid).size();
        List<Map<String, Object>> cnt = jdbc.queryForList(
                "SELECT result, COUNT(*) c FROM boss_hit WHERE user_id=? AND boss_id=? AND round_no=? GROUP BY result",
                uid, bossId, roundNo);
        int pass = 0, fail = 0;
        for (Map<String, Object> r : cnt) {
            if ("PASS".equals(r.get("result"))) pass = ((Number) r.get("c")).intValue();
            else fail = ((Number) r.get("c")).intValue();
        }
        if (pass < n || fail > 0) return;   // 未满一轮或有错不发
        int unityScore = settingInt(uid, "boss.unity_score", 3);
        for (Map<String, Object> c : roster(uid)) {
            Long sid = ((Number) c.get("studentId")).longValue();
            petService.award(uid, sid, null, unityScore, "BOSS",
                    "boss:" + bossId + ":unity:" + roundNo + ":" + sid, "团结就是力量");
        }
    }

    /** 小组模式击杀奖：某组怪被整组击杀 → 仅该组组员各 +group_win_score（biz 幂等，无最后一击个人奖）。 */
    private void awardGroupKill(Long uid, Long bossId, int col) {
        if (col <= 0) return;
        List<Map<String, Object>> members = groupMembers(uid, col);
        int winScore = settingInt(uid, "boss.group_win_score", 5);
        for (Map<String, Object> m : members) {
            Long sid = ((Number) m.get("sid")).longValue();
            petService.award(uid, sid, null, winScore, "BOSS",
                    "boss:" + bossId + ":group:" + col + ":" + sid, "打怪兽优胜小组");
        }
    }

    /** 小组排名：按座位列统计组员数与本场 PASS/FAIL 判定次数（命中按 group_col 子查询计数，避免座位×命中连乘）。 */
    private List<Map<String, Object>> groupRanking(Long uid, Long bossId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sa.col_no groupCol, COUNT(DISTINCT sa.student_id) members, " +
                        "(SELECT COUNT(*) FROM boss_hit h WHERE h.user_id=sa.user_id AND h.boss_id=? " +
                        "  AND h.group_col=sa.col_no AND h.result='PASS') pass, " +
                        "(SELECT COUNT(*) FROM boss_hit h WHERE h.user_id=sa.user_id AND h.boss_id=? " +
                        "  AND h.group_col=sa.col_no AND h.result='FAIL') fail " +
                        "FROM seat_assignment sa " +
                        "WHERE sa.user_id=? AND sa.class_id=? GROUP BY sa.col_no ORDER BY pass DESC, sa.col_no",
                bossId, bossId, uid, classId());
        int rank = 1;
        for (Map<String, Object> r : rows) {
            int pass = ((Number) r.get("pass")).intValue();
            int members = ((Number) r.get("members")).intValue();
            r.put("rank", rank++);
            r.put("name", "第" + r.get("groupCol") + "组");
            // 整组每人都答对（pass==组员数）即该组怪被击杀
            r.put("killed", members > 0 && pass >= members);
        }
        return rows;
    }

    // ---------- 内部：数据访问 ----------

    /** 班主任班在籍学生（按 id 顺序，即班级轮答/发奖名单） */
    private List<Map<String, Object>> roster(Long uid) {
        return jdbc.queryForList(
                "SELECT id studentId, name FROM student WHERE user_id=? AND class_id=? ORDER BY id",
                uid, classId());
    }

    /** 座位序列（小组=座位列）：按列、行排序，每行带 groupCol；未排座返回空。 */
    private List<Map<String, Object>> seatSeq(Long uid) {
        return jdbc.queryForList(
                "SELECT sa.student_id studentId, s.name, sa.col_no groupCol, sa.row_no rowNo " +
                        "FROM seat_assignment sa JOIN student s ON s.id=sa.student_id AND s.user_id=sa.user_id " +
                        "WHERE sa.user_id=? AND sa.class_id=? ORDER BY sa.col_no, sa.row_no",
                uid, classId());
    }

    /** 班级轮答序列：先按座位列序（一列一列叫），未排座学生补在队尾，保证人人被叫到。 */
    private List<Map<String, Object>> classSeq(Long uid) {
        List<Map<String, Object>> seq = new ArrayList<>(seatSeq(uid));
        Set<Long> seated = new HashSet<>();
        for (Map<String, Object> m : seq) seated.add(((Number) m.get("studentId")).longValue());
        for (Map<String, Object> r : roster(uid)) {
            Long sid = ((Number) r.get("studentId")).longValue();
            if (!seated.contains(sid)) seq.add(r);
        }
        return seq;
    }

    /** 小组列号（物理 col_no）升序；GROUP 模式一组一怪，列即组。 */
    private List<Integer> groupCols(Long uid) {
        return jdbc.queryForList(
                "SELECT DISTINCT col_no FROM seat_assignment WHERE user_id=? AND class_id=? ORDER BY col_no",
                Integer.class, uid, classId());
    }

    /** 某组（列）组员，按排号升序；sid=学生 id。 */
    private List<Map<String, Object>> groupMembers(Long uid, int col) {
        return jdbc.queryForList(
                "SELECT sa.student_id sid, s.name FROM seat_assignment sa " +
                        "JOIN student s ON s.id=sa.student_id AND s.user_id=sa.user_id " +
                        "WHERE sa.user_id=? AND sa.class_id=? AND sa.col_no=? ORDER BY sa.row_no",
                uid, classId(), col);
    }

    /** 本场本轮已判定人次 */
    private int countRoundHits(Long uid, Long bossId, int roundNo) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boss_hit WHERE user_id=? AND boss_id=? AND round_no=?",
                Integer.class, uid, bossId, roundNo);
        return c == null ? 0 : c;
    }

    /** 本场某组（列）已判定人次（GROUP 组内进度） */
    private int countGroupHits(Long uid, Long bossId, int col) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM boss_hit WHERE user_id=? AND boss_id=? AND group_col=?",
                Integer.class, uid, bossId, col);
        return c == null ? 0 : c;
    }

    /** 抽一道题（最久未用优先），返回含 answer；无可用题返回 null。 */
    private Map<String, Object> drawQuizRow(Long uid, Long categoryId, Integer difficulty) {
        StringBuilder where = new StringBuilder(" WHERE b.user_id=? AND b.enabled=1 AND c.enabled=1");
        List<Object> args = new ArrayList<>(List.of(uid));
        if (categoryId != null) { where.append(" AND b.category_id=?"); args.add(categoryId); }
        if (difficulty != null) { where.append(" AND b.difficulty=?"); args.add(difficulty); }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.category_id categoryId, c.name categoryName, c.icon categoryIcon, " +
                        "b.question, b.answer, b.difficulty FROM quiz_bank b JOIN quiz_category c ON c.id=b.category_id" +
                        where + " ORDER BY (b.last_used_at IS NULL) DESC, b.last_used_at ASC, RAND() LIMIT 1",
                args.toArray());
        if (rows.isEmpty()) return null;
        Map<String, Object> quiz = rows.get(0);
        jdbc.update("UPDATE quiz_bank SET use_count=use_count+1, last_used_at=NOW() WHERE id=?", quiz.get("id"));
        return quiz;
    }

    /** 今日 ACTIVE 场（最新一场进行中）；无则 null */
    private Map<String, Object> activeBoss(Long uid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM daily_boss WHERE user_id=? AND boss_date=CURDATE() AND status='ACTIVE' ORDER BY id DESC LIMIT 1",
                uid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 今日最新一场（任意状态）；无则 null */
    private Map<String, Object> latestBossToday(Long uid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM daily_boss WHERE user_id=? AND boss_date=CURDATE() ORDER BY id DESC LIMIT 1", uid);
        if (rows.isEmpty()) return null;
        Map<String, Object> boss = rows.get(0);
        lazyEscaped(boss);
        return boss;
    }

    private Map<String, Object> bossById(Long uid, Long bossId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM daily_boss WHERE user_id=? AND id=?", uid, bossId);
        if (rows.isEmpty()) return null;
        Map<String, Object> boss = rows.get(0);
        lazyEscaped(boss);
        return boss;
    }

    /** 跨天仍 ACTIVE 视为逃跑（仅展示态，不落库） */
    private void lazyEscaped(Map<String, Object> boss) {
        Object d = boss.get("boss_date");
        if ("ACTIVE".equals(boss.get("status")) && d != null && LocalDate.parse(String.valueOf(d)).isBefore(LocalDate.now())) {
            boss.put("status", "ESCAPED");
        }
    }

    /**
     * 大屏/手机共用的整体状态：boss 全量字段 + 当前题（无答案）+ 当前答题人 + 轮次进度 + 战绩。
     * progress：CLASS/GROUP 为本轮进度（done/total=本轮人数），RANDOM/MANUAL 为总进度（done/total=目标人次）。
     */
    private Map<String, Object> stateOf(Long uid, Map<String, Object> boss) {
        Long bossId = ((Number) boss.get("id")).longValue();
        BossSession s = sessions.get(bossId);
        Map<String, Object> state = new HashMap<>(boss);
        state.put("question", s == null ? null : s.question());
        state.put("categoryName", s == null ? null : s.categoryName());
        state.put("categoryIcon", s == null ? null : s.categoryIcon());
        state.put("currentQuizId", s == null ? null : s.quizId());
        state.put("pickedStudentId", s == null ? null : s.pickedStudentId());
        state.put("pickedStudentName", s == null ? null : s.pickedStudentName());
        state.put("groupCol", s == null ? null : s.groupCol());
        state.put("modeCn", MODE_CN.getOrDefault(str(boss.get("mode")), "挑战"));

        Map<String, Object> stats = statsOf(uid, boss);
        state.put("stats", stats);
        int totalDone = ((Number) stats.getOrDefault("pass", 0)).intValue()
                + ((Number) stats.getOrDefault("fail", 0)).intValue();
        String mode = str(boss.get("mode")).toUpperCase();
        int roundNo = s != null ? s.roundNo() : asInt(boss.get("round_no"));
        boolean active = "ACTIVE".equals(boss.get("status"));
        Map<String, Object> progress;
        if ("GROUP".equals(mode)) {
            // 一组一怪：round=第几组，done/total=当前组内已答/组员数；另带小组前三榜
            List<Integer> cols = groupCols(uid);
            int gIdx = cols.isEmpty() ? 0 : Math.max(0, Math.min(roundNo - 1, cols.size() - 1));
            int col = cols.isEmpty() ? 0 : cols.get(gIdx);
            int members = col == 0 ? 0 : groupMembers(uid, col).size();
            int gDone = active && col != 0 ? countGroupHits(uid, bossId, col) : 0;
            progress = new HashMap<>();
            progress.put("done", gDone);
            progress.put("total", Math.max(members, 1));
            progress.put("round", gIdx + 1);
            progress.put("groupCount", cols.size());
            progress.put("groupCol", col);
            List<Map<String, Object>> ranking = groupRanking(uid, bossId);
            state.put("groupTop3", ranking.size() > 3 ? new ArrayList<>(ranking.subList(0, 3)) : ranking);
        } else if ("CLASS".equals(mode)) {
            int done = active ? countRoundHits(uid, bossId, roundNo) : 0;
            progress = Map.of("done", done, "total", Math.max(classSeq(uid).size(), 1), "round", roundNo);
        } else {
            progress = Map.of("done", totalDone, "total", Math.max(asInt(boss.get("target_rounds")), 1), "round", roundNo);
        }
        state.put("progress", progress);
        return state;
    }

    /** 本场战绩：pass/fail 次数 + 击杀王 + 今日 BOSS 加分前三 */
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

    /**
     * 重置今日挑战（E2E 专用）：撤回当日全部 BOSS 流水（回滚成长值/等级，含击杀/全班/小组/团结奖），
     * 删今日全部场次与命中明细、清内存会话；另清全班 evolve_pending（BOSS 异步加分跨级会置待演标记）。
     */
    @PostMapping("/reset")
    public Result<Void> reset() {
        Long uid = uid();
        List<Long> logIds = jdbc.queryForList(
                "SELECT id FROM pet_log WHERE user_id=? AND source='BOSS' AND DATE(created_at)=CURDATE()",
                Long.class, uid);
        for (Long logId : logIds) petService.revokeLog(uid, logId);
        jdbc.update("UPDATE pet SET evolve_pending=0 WHERE user_id=?", uid);
        List<Long> bossIds = jdbc.queryForList(
                "SELECT id FROM daily_boss WHERE user_id=? AND boss_date=CURDATE()", Long.class, uid);
        for (Long id : bossIds) {
            jdbc.update("DELETE FROM boss_hit WHERE user_id=? AND boss_id=?", uid, id);
            jdbc.update("DELETE FROM daily_boss WHERE user_id=? AND id=?", uid, id);
            sessions.remove(id);
        }
        return Result.ok(null);
    }

    // ---------- 辅助 ----------

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

    private Integer asIntOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
