package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.PetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * 工作台：设置、聚合统计、节假日、卡片顺序、一句话速记规则引擎（按账号隔离；速记关键词规则全局共享）
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController extends BaseController {

    private final JdbcTemplate jdbc;
    private final PetService petService;

    public DashboardController(JdbcTemplate jdbc, PetService petService) {
        this.jdbc = jdbc;
        this.petService = petService;
    }

    private Long homeroomClassId() {
        Long uid = uid();
        try {
            return Long.valueOf(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k='homeroom_class_id'", String.class, uid));
        } catch (Exception e) {
            try {
                return jdbc.queryForObject(
                        "SELECT id FROM class_info WHERE user_id=? AND type='HOMEROOM' ORDER BY id LIMIT 1",
                        Long.class, uid);
            } catch (Exception e2) {
                return 1L;
            }
        }
    }

    // ---------- 设置 ----------
    @GetMapping("/settings")
    public Result<Map<String, String>> settings() {
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.queryForList("SELECT k,v FROM setting WHERE user_id=?", uid()).forEach(r ->
                map.put((String) r.get("k"), r.get("v") == null ? "" : String.valueOf(r.get("v"))));
        return Result.ok(map);
    }

    @PostMapping("/settings")
    public Result<Void> saveSettings(@RequestBody Map<String, String> body) {
        Long uid = uid();
        body.forEach((k, v) -> {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM setting WHERE user_id=? AND k=?", Integer.class, uid, k);
            if (n == 0) jdbc.update("INSERT INTO setting(user_id,k,v) VALUES(?,?,?)", uid, k, v);
            else jdbc.update("UPDATE setting SET v=? WHERE user_id=? AND k=?", v, uid, k);
        });
        return Result.ok();
    }

    // ---------- 聚合统计 ----------
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Long uid = uid();
        Long cid = homeroomClassId();
        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();
        String todayStr = today.toString();

        int studentTotal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE user_id=? AND class_id=?", Integer.class, uid, cid);
        int todoOpen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM todo WHERE user_id=? AND done=0", Integer.class, uid);
        int commToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM comm_log WHERE user_id=? AND contact_date=?", Integer.class, uid, todayStr);

        // 当前老师姓名：科目 teacher 字段填"我"或老师姓名都算"我的课"（setting teacher_name 兜底 sys_user.display_name）
        String teacherName = "";
        try {
            teacherName = jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k='teacher_name'", String.class, uid);
        } catch (Exception ignore) { }
        if (teacherName == null || teacherName.isBlank()) {
            try {
                teacherName = jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?", String.class, uid);
            } catch (Exception ignore) { }
        }
        if (teacherName == null || teacherName.isBlank()) teacherName = "___NOMATCH___";

        // 今日课程：跨【所有班级】（班主任班+任课班）只统计我教的节次（subject.teacher='我' 或 =老师名）
        List<Map<String, Object>> todayCells = jdbc.queryForList(
                "SELECT t.period_id, p.start_time, p.end_time, p.is_break FROM timetable t " +
                "JOIN period p ON p.id=t.period_id JOIN subject s ON s.id=t.subject_id " +
                "WHERE t.user_id=? AND t.day_of_week=? AND (s.teacher='我' OR s.teacher=?)",
                uid, dow, teacherName);
        int todayCourses = todayCells.size();
        LocalTime now = LocalTime.now();
        int todayRemaining = (int) todayCells.stream().filter(c -> {
            Object et = c.get("end_time");
            if (et == null) return false;
            return LocalTime.parse(String.valueOf(et).substring(0, 5)).isAfter(now);
        }).count();
        Integer weekRemaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable t JOIN subject s ON s.id=t.subject_id " +
                "WHERE t.user_id=? AND t.day_of_week>=? AND (s.teacher='我' OR s.teacher=?)",
                Integer.class, uid, dow, teacherName);

        // 考勤（异常数 + 异常学生明细，限班主任班）
        int abnormal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance a JOIN student s ON s.id=a.student_id " +
                "WHERE a.user_id=? AND a.record_date=? AND s.class_id=? AND a.status IN ('迟到','请假','缺勤')",
                Integer.class, uid, todayStr, cid);
        int attTotal = studentTotal;
        int attPresent = attTotal - abnormal;
        List<Map<String, Object>> attAbnormalList = jdbc.queryForList(
                "SELECT s.name, a.status FROM attendance a JOIN student s ON s.id=a.student_id " +
                "WHERE a.user_id=? AND a.record_date=? AND s.class_id=? AND a.status IN ('迟到','请假','缺勤') ORDER BY a.status",
                uid, todayStr, cid);

        // 课堂纪律：今日条数 + 明细（姓名/违纪内容），前端按学生分组去重、悬停看多条
        int disciplineToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discipline_log WHERE user_id=? AND record_date=?",
                Integer.class, uid, todayStr);
        List<Map<String, Object>> disciplineList = jdbc.queryForList(
                "SELECT s.name, d.content FROM discipline_log d JOIN student s ON s.id=d.student_id " +
                "WHERE d.user_id=? AND d.record_date=? ORDER BY d.id", uid, todayStr);

        // 作业收缴：今日未交【去重学生数】+ 明细（姓名/科目），前端按学生分组、悬停看未交科目
        int homeworkMissing = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT h.student_id) FROM homework_check h " +
                "WHERE h.user_id=? AND h.record_date=? AND h.status='未交'",
                Integer.class, uid, todayStr);
        List<Map<String, Object>> homeworkList = jdbc.queryForList(
                "SELECT s.name, h.subject FROM homework_check h JOIN student s ON s.id=h.student_id " +
                "WHERE h.user_id=? AND h.record_date=? AND h.status='未交' ORDER BY h.id", uid, todayStr);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("studentTotal", studentTotal);
        stats.put("extraStudents", 0);
        stats.put("todayCourses", todayCourses);
        stats.put("todayRemaining", todayRemaining);
        stats.put("weekRemaining", weekRemaining);
        stats.put("todoOpen", todoOpen);
        stats.put("commPending", commToday);
        stats.put("attPresent", attPresent);
        stats.put("attTotal", attTotal);
        stats.put("attAbnormalList", attAbnormalList);
        stats.put("disciplineToday", disciplineToday);
        stats.put("disciplineList", disciplineList);
        stats.put("homeworkMissing", homeworkMissing);
        stats.put("homeworkList", homeworkList);
        stats.put("today", todayStr);
        return Result.ok(stats);
    }

    // ---------- 节假日 ----------
    @GetMapping("/holidays")
    public Result<List<Map<String, Object>>> holidays() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT id,name,holiday_date date FROM holiday WHERE user_id=? ORDER BY holiday_date", uid());
        List<Map<String, Object>> upcoming = new ArrayList<>();
        for (Map<String, Object> h : list) {
            LocalDate d = LocalDate.parse(String.valueOf(h.get("date")));
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, d);
            if (days >= 0) {
                h.put("days", days);
                upcoming.add(h);
            }
        }
        upcoming.sort(Comparator.comparingLong(h -> (Long) h.get("days")));
        return Result.ok(upcoming);
    }

    @PostMapping("/holiday")
    public Result<Void> addHoliday(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO holiday(user_id,name,holiday_date) VALUES(?,?,?)",
                uid(), str(body.get("name")), str(body.get("date")));
        return Result.ok();
    }

    @DeleteMapping("/holiday/{id}")
    public Result<Void> deleteHoliday(@PathVariable Long id) {
        jdbc.update("DELETE FROM holiday WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    // ---------- 卡片顺序 ----------
    @GetMapping("/cards")
    public Result<List<String>> cards() {
        return Result.ok(jdbc.queryForList(
                "SELECT card_key k FROM card_config WHERE user_id=? ORDER BY sort_order", String.class, uid()));
    }

    @PostMapping("/cards")
    @SuppressWarnings("unchecked")
    public Result<Void> saveCards(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        List<String> order = (List<String>) body.get("order");
        for (int i = 0; i < order.size(); i++) {
            jdbc.update("UPDATE card_config SET sort_order=? WHERE user_id=? AND card_key=?", i, uid, order.get(i));
        }
        return Result.ok();
    }

    // ---------- 一句话速记 ----------
    /** 规则解析：分句 → 匹配模块关键词（全局规则）+ 人名/学号（本账号学生） */
    @PostMapping("/quicknote/parse")
    public Result<Map<String, Object>> parse(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String content = str(body.get("content"));
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT module_key, keywords FROM quick_note_rule WHERE enabled=1");
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id,name,stu_no FROM student WHERE user_id=? AND class_id=?", uid, homeroomClassId());

        String[] parts = content.split("[/／;；\n]");
        List<Map<String, Object>> segments = new ArrayList<>();
        for (String raw : parts) {
            String text = raw.trim();
            if (text.isEmpty()) continue;
            List<String> modules = new ArrayList<>();
            for (Map<String, Object> rule : rules) {
                String module = (String) rule.get("module_key");
                for (String kw : str(rule.get("keywords")).split(",")) {
                    if (!kw.isBlank() && text.contains(kw.trim())) {
                        if (!modules.contains(module)) modules.add(module);
                        break;
                    }
                }
            }
            List<Map<String, Object>> hit = new ArrayList<>();
            for (Map<String, Object> s : students) {
                String name = str(s.get("name"));
                String stuNo = str(s.get("stu_no"));
                if ((!name.isEmpty() && text.contains(name)) || (!stuNo.isEmpty() && text.contains(stuNo))) {
                    hit.add(Map.of("id", s.get("id"), "name", name));
                }
            }
            if (modules.isEmpty()) modules.add("TODO"); // 无法识别默认待办
            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("text", text);
            seg.put("modules", modules);
            seg.put("students", hit);
            segments.add(seg);
        }
        return Result.ok(Map.of("segments", segments));
    }

    /** 确认保存：按模块批量落库（全部归属当前账号） */
    @PostMapping("/quicknote/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveQuickNote(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String content = str(body.get("content"));
        String date = LocalDate.now().toString();
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.getOrDefault("items", new ArrayList<>());
        for (Map<String, Object> it : items) {
            String module = str(it.get("module"));
            Long studentId = it.get("studentId") == null ? null : ((Number) it.get("studentId")).longValue();
            String text = str(it.getOrDefault("content", it.get("title")));
            switch (module) {
                case "ATTENDANCE" -> {
                    if (studentId != null) {
                        String status = str(it.getOrDefault("status", "迟到"));
                        jdbc.update("INSERT INTO attendance(user_id,record_date,student_id,status) VALUES(?,?,?,?) " +
                                "ON DUPLICATE KEY UPDATE status=VALUES(status)", uid, date, studentId, status);
                        // 宠物自动联动：迟到 -1 / 缺勤 -2
                        if (petService.autoDeductEnabled(uid)) {
                            String ref = "att:" + date + ":" + studentId;
                            if ("缺勤".equals(status)) petService.award(uid, studentId, null, -2, "AUTO_ATTENDANCE", ref, "缺勤");
                            else petService.award(uid, studentId, null, -1, "AUTO_ATTENDANCE", ref, "迟到");
                        }
                    }
                }
                case "COMM" -> {
                    if (studentId != null) {
                        jdbc.update("INSERT INTO comm_log(user_id,student_id,contact_date,method,content) VALUES(?,?,?,?,?)",
                                uid, studentId, date, str(it.getOrDefault("method", "电话")), text);
                    }
                }
                case "HOMEWORK" -> {
                    if (studentId != null) {
                        String subject = str(it.getOrDefault("subject", "作业"));
                        jdbc.update("INSERT INTO homework_check(user_id,record_date,subject,student_id,status) VALUES(?,?,?,?,'未交') " +
                                "ON DUPLICATE KEY UPDATE status='未交'", uid, date, subject, studentId);
                        // 宠物自动联动：作业未交 -1（biz_ref 幂等）
                        if (petService.autoDeductEnabled(uid)) {
                            petService.award(uid, studentId, null, -1, "AUTO_HOMEWORK",
                                    "hw:" + date + ":" + subject + ":" + studentId, "作业未交(" + subject + ")");
                        }
                    }
                }
                case "DISCIPLINE" -> {
                    if (studentId != null) {
                        jdbc.update("INSERT INTO discipline_log(user_id,record_date,student_id,content) VALUES(?,?,?,?)",
                                uid, date, studentId, text);
                        Long logId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                        // 宠物自动联动：课堂违纪 -2
                        if (petService.autoDeductEnabled(uid)) {
                            petService.award(uid, studentId, null, -2, "AUTO_DISCIPLINE", "dis:" + logId, "课堂违纪");
                        }
                    }
                }
                case "TODO" -> jdbc.update(
                        "INSERT INTO todo(user_id,title,category,due_date,done,source) VALUES(?,?,?,?,0,'QUICK')",
                        uid, text, str(it.getOrDefault("category", "其他")), date);
                default -> {}
            }
        }
        jdbc.update("INSERT INTO quick_note(user_id,content,parsed_json) VALUES(?,?,?)",
                uid, content, String.valueOf(items.size()));
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
