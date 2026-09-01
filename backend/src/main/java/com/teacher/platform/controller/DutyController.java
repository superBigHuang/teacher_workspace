package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 值日表·班委：值日事项、每日人数、自动轮值、班委名单（按账号隔离）
 */
@RestController
@RequestMapping("/api/duty")
public class DutyController extends BaseController {

    private final JdbcTemplate jdbc;

    public DutyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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

    // ---------- 值日事项 ----------
    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> tasks() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name FROM duty_task WHERE user_id=? ORDER BY id", uid()));
    }

    @PostMapping("/task")
    public Result<Void> addTask(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO duty_task(user_id,name) VALUES(?,?)", uid(), str(body.get("name")));
        return Result.ok();
    }

    @DeleteMapping("/task/{id}")
    public Result<Void> deleteTask(@PathVariable Long id) {
        Long uid = uid();
        // 先解绑已分配到该事项的值日学生（置空任务，避免引用悬空）
        jdbc.update("UPDATE duty_roster SET task_id=NULL WHERE user_id=? AND task_id=?", uid, id);
        jdbc.update("DELETE FROM duty_task WHERE id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    // ---------- 每日人数 ----------
    @GetMapping("/config")
    public Result<List<Map<String, Object>>> config() {
        return Result.ok(jdbc.queryForList(
                "SELECT day_of_week dayOfWeek, person_count personCount FROM duty_config WHERE user_id=? ORDER BY day_of_week",
                uid()));
    }

    @PostMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) body.get("configs");
        for (Map<String, Object> c : list) {
            int dow = ((Number) c.get("dayOfWeek")).intValue();
            int cnt = ((Number) c.get("personCount")).intValue();
            jdbc.update("UPDATE duty_config SET person_count=? WHERE user_id=? AND day_of_week=?", cnt, uid, dow);
        }
        return Result.ok();
    }

    // ---------- 值日名单 ----------
    @GetMapping("/roster")
    public Result<Map<String, Object>> roster() {
        Long uid = uid();
        Map<String, Object> result = new HashMap<>();
        for (int d = 1; d <= 5; d++) {
            List<Map<String, Object>> students = jdbc.queryForList(
                    "SELECT r.id rosterId, r.student_id studentId, s.name, s.stu_no stuNo, s.gender, " +
                    "r.task_id taskId, t.name taskName " +
                    "FROM duty_roster r LEFT JOIN student s ON s.id=r.student_id " +
                    "LEFT JOIN duty_task t ON t.id=r.task_id " +
                    "WHERE r.user_id=? AND r.day_of_week=? ORDER BY r.id", uid, d);
            result.put(String.valueOf(d), students);
        }
        return Result.ok(result);
    }

    /** 自动轮值：按花名册顺序 + 每日人数顺延分配，任务轮流分到每人（人人有明确分工） */
    @PostMapping("/auto")
    public Result<Void> autoRotate() {
        Long uid = uid();
        Long cid = homeroomClassId();
        List<Long> students = jdbc.queryForList(
                "SELECT id FROM student WHERE user_id=? AND class_id=? ORDER BY stu_no", uid, cid)
                .stream().map(m -> ((Number) m.get("id")).longValue()).toList();
        if (students.isEmpty()) return Result.fail("花名册为空，请先添加学生");
        int n = students.size();

        int pointer = getSettingInt(uid, "duty_pointer", 0);
        int round = getSettingInt(uid, "duty_round", 0) + 1;
        Map<Integer, Integer> countMap = new HashMap<>();
        jdbc.queryForList("SELECT day_of_week d, person_count c FROM duty_config WHERE user_id=?", uid)
                .forEach(m -> countMap.put(((Number) m.get("d")).intValue(), ((Number) m.get("c")).intValue()));

        // 值日事项列表：轮值时把任务轮流分配到每个学生，做到“人人有明确分工”
        List<Long> taskIds = jdbc.queryForList(
                "SELECT id FROM duty_task WHERE user_id=? ORDER BY id", Long.class, uid);
        jdbc.update("DELETE FROM duty_roster WHERE user_id=?", uid);
        int idx = pointer;
        int taskIdx = 0;
        for (int d = 1; d <= 5; d++) {
            int cnt = countMap.getOrDefault(d, 4);
            for (int k = 0; k < cnt; k++) {
                Long sid = students.get(idx % n);
                Long taskId = taskIds.isEmpty() ? null : taskIds.get(taskIdx % taskIds.size());
                if (taskId == null) {
                    jdbc.update("INSERT INTO duty_roster(user_id,day_of_week,student_id,round) VALUES(?,?,?,?)",
                            uid, d, sid, round);
                } else {
                    jdbc.update("INSERT INTO duty_roster(user_id,day_of_week,student_id,task_id,round) VALUES(?,?,?,?,?)",
                            uid, d, sid, taskId, round);
                }
                idx++;
                taskIdx++;
            }
        }
        saveSetting(uid, "duty_pointer", String.valueOf(idx % n));
        saveSetting(uid, "duty_round", String.valueOf(round));
        return Result.ok();
    }

    @PostMapping("/roster")
    public Result<Void> addRoster(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long taskId = body.get("taskId") == null ? null : ((Number) body.get("taskId")).longValue();
        jdbc.update("INSERT INTO duty_roster(user_id,day_of_week,student_id,task_id) VALUES(?,?,?,?)",
                uid, ((Number) body.get("dayOfWeek")).intValue(),
                ((Number) body.get("studentId")).longValue(), taskId);
        return Result.ok();
    }

    @DeleteMapping("/roster/{id}")
    public Result<Void> removeRoster(@PathVariable Long id) {
        jdbc.update("DELETE FROM duty_roster WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    // ---------- 班委 ----------
    @GetMapping("/committee")
    public Result<List<Map<String, Object>>> committee() {
        return Result.ok(jdbc.queryForList(
                "SELECT c.id, c.student_id studentId, c.role, s.name, s.stu_no stuNo " +
                "FROM committee c LEFT JOIN student s ON s.id=c.student_id " +
                "WHERE c.user_id=? ORDER BY c.id", uid()));
    }

    @PostMapping("/committee")
    public Result<Void> addCommittee(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO committee(user_id,student_id,role) VALUES(?,?,?)",
                uid(), ((Number) body.get("studentId")).longValue(), str(body.get("role")));
        return Result.ok();
    }

    @DeleteMapping("/committee/{id}")
    public Result<Void> removeCommittee(@PathVariable Long id) {
        jdbc.update("DELETE FROM committee WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    private int getSettingInt(Long uid, String k, int def) {
        try {
            return Integer.parseInt(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k));
        } catch (Exception e) {
            return def;
        }
    }

    private void saveSetting(Long uid, String k, String v) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM setting WHERE user_id=? AND k=?", Integer.class, uid, k);
        if (n == 0) jdbc.update("INSERT INTO setting(user_id,k,v) VALUES(?,?,?)", uid, k, v);
        else jdbc.update("UPDATE setting SET v=? WHERE user_id=? AND k=?", v, uid, k);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
