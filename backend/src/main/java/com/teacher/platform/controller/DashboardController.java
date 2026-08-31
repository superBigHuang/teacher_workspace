package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * 工作台：全局设置、聚合统计、节假日、卡片顺序、一句话速记规则引擎
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final JdbcTemplate jdbc;

    public DashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private Long homeroomClassId() {
        try {
            return Long.valueOf(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE k='homeroom_class_id'", String.class));
        } catch (Exception e) {
            return 1L;
        }
    }

    // ---------- 设置 ----------
    @GetMapping("/settings")
    public Result<Map<String, String>> settings() {
        Map<String, String> map = new LinkedHashMap<>();
        jdbc.queryForList("SELECT k,v FROM setting").forEach(r ->
                map.put((String) r.get("k"), r.get("v") == null ? "" : String.valueOf(r.get("v"))));
        return Result.ok(map);
    }

    @PostMapping("/settings")
    public Result<Void> saveSettings(@RequestBody Map<String, String> body) {
        body.forEach((k, v) -> {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM setting WHERE k=?", Integer.class, k);
            if (n == 0) jdbc.update("INSERT INTO setting(k,v) VALUES(?,?)", k, v);
            else jdbc.update("UPDATE setting SET v=? WHERE k=?", v, k);
        });
        return Result.ok();
    }

    // ---------- 聚合统计 ----------
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Long cid = homeroomClassId();
        LocalDate today = LocalDate.now();
        int dow = today.getDayOfWeek().getValue();

        int studentTotal = jdbc.queryForObject("SELECT COUNT(*) FROM student WHERE class_id=?", Integer.class, cid);
        int todoOpen = jdbc.queryForObject("SELECT COUNT(*) FROM todo WHERE done=0", Integer.class);
        int commToday = jdbc.queryForObject("SELECT COUNT(*) FROM comm_log WHERE contact_date=?", Integer.class, today.toString());

        // 今日课程
        List<Map<String, Object>> todayCells = jdbc.queryForList(
                "SELECT t.period_id, p.start_time, p.end_time, p.is_break FROM timetable t " +
                "JOIN period p ON p.id=t.period_id WHERE t.class_id=? AND t.day_of_week=?", cid, dow);
        int todayCourses = todayCells.size();
        LocalTime now = LocalTime.now();
        int todayRemaining = (int) todayCells.stream().filter(c -> {
            Object et = c.get("end_time");
            if (et == null) return false;
            return LocalTime.parse(String.valueOf(et).substring(0, 5)).isAfter(now);
        }).count();
        Integer weekRemaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM timetable WHERE class_id=? AND day_of_week>=?", Integer.class, cid, dow);

        // 考勤
        int abnormal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance a JOIN student s ON s.id=a.student_id " +
                "WHERE a.record_date=? AND s.class_id=? AND a.status IN ('迟到','请假','缺勤')",
                Integer.class, today.toString(), cid);
        int attTotal = studentTotal;
        int attPresent = attTotal - abnormal;

        int disciplineToday = jdbc.queryForObject(
                "SELECT COUNT(*) FROM discipline_log WHERE record_date=?", Integer.class, today.toString());
        int homeworkMissing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM homework_check WHERE record_date=? AND status='未交'",
                Integer.class, today.toString());

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
        stats.put("disciplineToday", disciplineToday);
        stats.put("homeworkMissing", homeworkMissing);
        stats.put("today", today.toString());
        return Result.ok(stats);
    }

    // ---------- 节假日 ----------
    @GetMapping("/holidays")
    public Result<List<Map<String, Object>>> holidays() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> list = jdbc.queryForList("SELECT id,name,holiday_date date FROM holiday ORDER BY holiday_date");
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
        jdbc.update("INSERT INTO holiday(name,holiday_date) VALUES(?,?)",
                str(body.get("name")), str(body.get("date")));
        return Result.ok();
    }

    @DeleteMapping("/holiday/{id}")
    public Result<Void> deleteHoliday(@PathVariable Long id) {
        jdbc.update("DELETE FROM holiday WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 卡片顺序 ----------
    @GetMapping("/cards")
    public Result<List<String>> cards() {
        return Result.ok(jdbc.queryForList("SELECT card_key k FROM card_config ORDER BY sort_order", String.class));
    }

    @PostMapping("/cards")
    @SuppressWarnings("unchecked")
    public Result<Void> saveCards(@RequestBody Map<String, Object> body) {
        List<String> order = (List<String>) body.get("order");
        for (int i = 0; i < order.size(); i++) {
            jdbc.update("UPDATE card_config SET sort_order=? WHERE card_key=?", i, order.get(i));
        }
        return Result.ok();
    }

    // ---------- 一句话速记 ----------
    /** 规则解析：分句 → 匹配模块关键词 + 人名/学号 */
    @PostMapping("/quicknote/parse")
    public Result<Map<String, Object>> parse(@RequestBody Map<String, Object> body) {
        String content = str(body.get("content"));
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT module_key, keywords FROM quick_note_rule WHERE enabled=1");
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id,name,stu_no FROM student WHERE class_id=?", homeroomClassId());

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

    /** 确认保存：按模块批量落库 */
    @PostMapping("/quicknote/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveQuickNote(@RequestBody Map<String, Object> body) {
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
                        jdbc.update("INSERT INTO attendance(record_date,student_id,status) VALUES(?,?,?) " +
                                "ON DUPLICATE KEY UPDATE status=VALUES(status)", date, studentId, status);
                    }
                }
                case "COMM" -> {
                    if (studentId != null) {
                        jdbc.update("INSERT INTO comm_log(student_id,contact_date,method,content) VALUES(?,?,?,?)",
                                studentId, date, str(it.getOrDefault("method", "电话")), text);
                    }
                }
                case "HOMEWORK" -> {
                    if (studentId != null) {
                        String subject = str(it.getOrDefault("subject", "作业"));
                        jdbc.update("INSERT INTO homework_check(record_date,subject,student_id,status) VALUES(?,?,?,'未交') " +
                                "ON DUPLICATE KEY UPDATE status='未交'", date, subject, studentId);
                    }
                }
                case "DISCIPLINE" -> {
                    if (studentId != null) {
                        jdbc.update("INSERT INTO discipline_log(record_date,student_id,content) VALUES(?,?,?)",
                                date, studentId, text);
                    }
                }
                case "TODO" -> jdbc.update("INSERT INTO todo(title,category,due_date,done,source) VALUES(?,?,?,0,'QUICK')",
                        text, str(it.getOrDefault("category", "其他")), date);
                default -> {}
            }
        }
        jdbc.update("INSERT INTO quick_note(content,parsed_json) VALUES(?,?)", content, String.valueOf(items.size()));
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
