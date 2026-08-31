package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 值日表·班委：值日事项、每日人数、自动轮值、班委名单
 */
@RestController
@RequestMapping("/api/duty")
public class DutyController {

    private final JdbcTemplate jdbc;

    public DutyController(JdbcTemplate jdbc) {
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

    // ---------- 值日事项 ----------
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> tasks() {
        return Result.ok(jdbc.queryForList("SELECT id,name FROM duty_task ORDER BY id"));
    }

    @PostMapping("/task")
    public Result<Void> addTask(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO duty_task(name) VALUES(?)", str(body.get("name")));
        return Result.ok();
    }

    @DeleteMapping("/task/{id}")
    public Result<Void> deleteTask(@PathVariable Long id) {
        // 先解绑已分配到该事项的值日学生（置空任务，避免引用悬空）
        jdbc.update("UPDATE duty_roster SET task_id=NULL WHERE task_id=?", id);
        jdbc.update("DELETE FROM duty_task WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 每日人数 ----------
    @GetMapping("/config")
    public Result<List<Map<String, Object>>> config() {
        return Result.ok(jdbc.queryForList(
                "SELECT day_of_week dayOfWeek, person_count personCount FROM duty_config ORDER BY day_of_week"));
    }

    @PostMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("configs");
        for (Map<String, Object> c : list) {
            int dow = ((Number) c.get("dayOfWeek")).intValue();
            int cnt = ((Number) c.get("personCount")).intValue();
            jdbc.update("UPDATE duty_config SET person_count=? WHERE day_of_week=?", cnt, dow);
        }
        return Result.ok();
    }

    // ---------- 值日名单 ----------
    @GetMapping("/roster")
    public Result<Map<String, Object>> roster() {
        Map<String, Object> result = new HashMap<>();
        for (int d = 1; d <= 5; d++) {
            List<Map<String, Object>> students = jdbc.queryForList(
                    "SELECT r.id rosterId, r.student_id studentId, s.name, s.stu_no stuNo, s.gender, " +
                    "r.task_id taskId, t.name taskName " +
                    "FROM duty_roster r LEFT JOIN student s ON s.id=r.student_id " +
                    "LEFT JOIN duty_task t ON t.id=r.task_id " +
                    "WHERE r.day_of_week=? ORDER BY r.id", d);
            result.put(String.valueOf(d), students);
        }
        return Result.ok(result);
    }

    /** 自动轮值：按花名册顺序 + 每日人数顺延分配 */
    @PostMapping("/auto")
    public Result<Void> autoRotate() {
        Long cid = homeroomClassId();
        List<Long> students = jdbc.queryForList(
                "SELECT id FROM student WHERE class_id=? ORDER BY stu_no", cid)
                .stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        if (students.isEmpty()) return Result.fail("花名册为空，请先添加学生");
        int n = students.size();

        int pointer = getSettingInt("duty_pointer", 0);
        int round = getSettingInt("duty_round", 0) + 1;
        Map<Integer, Integer> countMap = new HashMap<>();
        jdbc.queryForList("SELECT day_of_week d, person_count c FROM duty_config")
                .forEach(m -> countMap.put(((Number) m.get("d")).intValue(), ((Number) m.get("c")).intValue()));

        jdbc.update("DELETE FROM duty_roster");
        int idx = pointer;
        for (int d = 1; d <= 5; d++) {
            int cnt = countMap.getOrDefault(d, 4);
            for (int k = 0; k < cnt; k++) {
                Long sid = students.get(idx % n);
                jdbc.update("INSERT INTO duty_roster(day_of_week,student_id,round) VALUES(?,?,?)", d, sid, round);
                idx++;
            }
        }
        saveSetting("duty_pointer", String.valueOf(idx % n));
        saveSetting("duty_round", String.valueOf(round));
        return Result.ok();
    }

    @PostMapping("/roster")
    public Result<Void> addRoster(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO duty_roster(day_of_week,student_id) VALUES(?,?)",
                ((Number) body.get("dayOfWeek")).intValue(), ((Number) body.get("studentId")).longValue());
        return Result.ok();
    }

    @DeleteMapping("/roster/{id}")
    public Result<Void> removeRoster(@PathVariable Long id) {
        jdbc.update("DELETE FROM duty_roster WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 班委 ----------
    @GetMapping("/committee")
    public Result<List<Map<String, Object>>> committee() {
        return Result.ok(jdbc.queryForList(
                "SELECT c.id, c.student_id studentId, c.role, s.name, s.stu_no stuNo " +
                "FROM committee c LEFT JOIN student s ON s.id=c.student_id ORDER BY c.id"));
    }

    @PostMapping("/committee")
    public Result<Void> addCommittee(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO committee(student_id,role) VALUES(?,?)",
                ((Number) body.get("studentId")).longValue(), str(body.get("role")));
        return Result.ok();
    }

    @DeleteMapping("/committee/{id}")
    public Result<Void> removeCommittee(@PathVariable Long id) {
        jdbc.update("DELETE FROM committee WHERE id=?", id);
        return Result.ok();
    }

    private int getSettingInt(String k, int def) {
        try {
            return Integer.parseInt(jdbc.queryForObject("SELECT v FROM setting WHERE k=?", String.class, k));
        } catch (Exception e) {
            return def;
        }
    }

    private void saveSetting(String k, String v) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM setting WHERE k=?", Integer.class, k);
        if (n == 0) jdbc.update("INSERT INTO setting(k,v) VALUES(?,?)", k, v);
        else jdbc.update("UPDATE setting SET v=? WHERE k=?", v, k);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
