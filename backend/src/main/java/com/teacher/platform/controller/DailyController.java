package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 班级日常：早读考勤、课堂纪律、作业收缴
 */
@RestController
@RequestMapping("/api/daily")
public class DailyController {

    private final JdbcTemplate jdbc;

    public DailyController(JdbcTemplate jdbc) {
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

    private List<Map<String, Object>> students() {
        return jdbc.queryForList(
                "SELECT id,stu_no stuNo,name,gender FROM student WHERE class_id=? ORDER BY stu_no",
                homeroomClassId());
    }

    // ---------- 早读考勤 ----------
    /** 某日考勤：返回学生名单 + 当日状态（未登记默认出勤） */
    @GetMapping("/attendance")
    public Result<Map<String, Object>> attendance(@RequestParam String date) {
        List<Map<String, Object>> list = students();
        Map<String, String> statusMap = new HashMap<>();
        jdbc.queryForList("SELECT student_id sid, status FROM attendance WHERE record_date=?", date)
                .forEach(r -> statusMap.put(String.valueOf(r.get("sid")), (String) r.get("status")));
        list.forEach(s -> s.put("status", statusMap.getOrDefault(String.valueOf(s.get("id")), "出勤")));
        return Result.ok(Map.of("students", list));
    }

    /** 保存考勤（批量 upsert） */
    @PostMapping("/attendance/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveAttendance(@RequestBody Map<String, Object> body) {
        String date = str(body.get("date"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) body.get("records");
        for (Map<String, Object> r : records) {
            Long sid = ((Number) r.get("studentId")).longValue();
            String status = str(r.getOrDefault("status", "出勤"));
            jdbc.update("INSERT INTO attendance(record_date,student_id,status) VALUES(?,?,?) " +
                    "ON DUPLICATE KEY UPDATE status=VALUES(status)", date, sid, status);
        }
        return Result.ok();
    }

    // ---------- 课堂纪律 ----------
    @GetMapping("/discipline")
    public Result<List<Map<String, Object>>> discipline(@RequestParam(required = false) String date) {
        if (date != null && !date.isBlank()) {
            return Result.ok(jdbc.queryForList(
                    "SELECT d.id, d.record_date date, d.student_id studentId, s.name, d.content " +
                    "FROM discipline_log d LEFT JOIN student s ON s.id=d.student_id " +
                    "WHERE d.record_date=? ORDER BY d.id DESC", date));
        }
        return Result.ok(jdbc.queryForList(
                "SELECT d.id, d.record_date date, d.student_id studentId, s.name, d.content " +
                "FROM discipline_log d LEFT JOIN student s ON s.id=d.student_id ORDER BY d.record_date DESC, d.id DESC LIMIT 100"));
    }

    @PostMapping("/discipline")
    @SuppressWarnings("unchecked")
    public Result<Void> addDiscipline(@RequestBody Map<String, Object> body) {
        String date = str(body.get("date"));
        String content = str(body.get("content"));
        List<Object> ids = (List<Object>) body.getOrDefault("studentIds", new ArrayList<>());
        for (Object o : ids) {
            jdbc.update("INSERT INTO discipline_log(record_date,student_id,content) VALUES(?,?,?)",
                    date, ((Number) o).longValue(), content);
        }
        return Result.ok();
    }

    @DeleteMapping("/discipline/{id}")
    public Result<Void> deleteDiscipline(@PathVariable Long id) {
        jdbc.update("DELETE FROM discipline_log WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 作业收缴 ----------
    /** 某日某科目：返回学生名单 + 是否未交（默认交） */
    @GetMapping("/homework")
    public Result<Map<String, Object>> homework(@RequestParam String date, @RequestParam String subject) {
        List<Map<String, Object>> list = students();
        Set<Long> missing = new HashSet<>();
        jdbc.queryForList("SELECT student_id sid FROM homework_check WHERE record_date=? AND subject=? AND status='未交'",
                        date, subject)
                .forEach(r -> missing.add(((Number) r.get("sid")).longValue()));
        list.forEach(s -> s.put("missing", missing.contains(((Number) s.get("id")).longValue())));
        return Result.ok(Map.of("students", list));
    }

    /** 保存作业收缴：只存未交名单 */
    @PostMapping("/homework/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveHomework(@RequestBody Map<String, Object> body) {
        String date = str(body.get("date"));
        String subject = str(body.get("subject"));
        List<Object> missingIds = (List<Object>) body.getOrDefault("missingIds", new ArrayList<>());
        jdbc.update("DELETE FROM homework_check WHERE record_date=? AND subject=?", date, subject);
        for (Object o : missingIds) {
            Long sid = ((Number) o).longValue();
            jdbc.update("INSERT INTO homework_check(record_date,subject,student_id,status) VALUES(?,?,?,'未交')",
                    date, subject, sid);
        }
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
