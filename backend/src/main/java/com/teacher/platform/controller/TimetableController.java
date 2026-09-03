package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 课程表：班级、科目（按账号隔离）、节次（全局模板）、排课网格
 */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController extends BaseController {

    private final JdbcTemplate jdbc;

    public TimetableController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 班级（per-user） ----------
    @GetMapping("/classes")
    public Result<List<Map<String, Object>>> classes() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name,type,term FROM class_info WHERE user_id=? ORDER BY type DESC, id", uid()));
    }

    @PostMapping("/class")
    public Result<Long> addClass(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO class_info(user_id,name,type,term) VALUES(?,?,?,?)",
                uid(), str(body.get("name")), str(body.getOrDefault("type", "SUBJECT")), str(body.get("term")));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/class/{id}")
    public Result<Void> renameClass(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE class_info SET name=? WHERE id=? AND user_id=?",
                str(body.get("name")), id, uid());
        return Result.ok();
    }

    @DeleteMapping("/class/{id}")
    public Result<Void> deleteClass(@PathVariable Long id) {
        Long uid = uid();
        // 删班级连带清理其课表与座次（均按 class_id 归属）
        jdbc.update("DELETE FROM timetable WHERE user_id=? AND class_id=?", uid, id);
        jdbc.update("DELETE FROM seat_assignment WHERE user_id=? AND class_id=?", uid, id);
        jdbc.update("DELETE FROM seat_config WHERE user_id=? AND class_id=?", uid, id);
        jdbc.update("DELETE FROM class_info WHERE id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    // ---------- 科目（per-user） ----------
    @GetMapping("/subjects")
    public Result<List<Map<String, Object>>> subjects() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name,teacher,color,is_mine isMine FROM subject WHERE user_id=? ORDER BY id", uid()));
    }

    @PostMapping("/subject")
    public Result<Long> addSubject(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO subject(user_id,name,teacher,color,is_mine) VALUES(?,?,?,?,?)",
                uid(), str(body.get("name")), str(body.get("teacher")),
                str(body.getOrDefault("color", "#9fc5f8")),
                boolBody(body, "isMine") ? 1 : 0);
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/subject/{id}")
    public Result<Void> updateSubject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE subject SET name=?,teacher=?,color=?,is_mine=? WHERE id=? AND user_id=?",
                str(body.get("name")), str(body.get("teacher")), str(body.get("color")),
                boolBody(body, "isMine") ? 1 : 0, id, uid());
        return Result.ok();
    }

    /** 解析请求体布尔字段：兼容 true/false、1/0、"true"/"1"（前端 el-checkbox 可能传布尔或数字） */
    private boolean boolBody(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        String s = String.valueOf(v).trim();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    @DeleteMapping("/subject/{id}")
    public Result<Void> deleteSubject(@PathVariable Long id) {
        jdbc.update("DELETE FROM subject WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    // ---------- 节次（全局共享模板，不带 user_id） ----------
    @GetMapping("/periods")
    public Result<List<Map<String, Object>>> periods() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name,start_time startTime,end_time endTime,sort,is_break isBreak FROM period ORDER BY sort"));
    }

    @PostMapping("/period")
    public Result<Long> addPeriod(@RequestBody Map<String, Object> body) {
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort),0) FROM period", Integer.class);
        jdbc.update("INSERT INTO period(name,start_time,end_time,sort,is_break) VALUES(?,?,?,?,?)",
                str(body.get("name")), str(body.get("startTime")), str(body.get("endTime")),
                maxSort + 1, body.getOrDefault("isBreak", 0));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/period/{id}")
    public Result<Void> updatePeriod(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE period SET name=?,start_time=?,end_time=?,is_break=? WHERE id=?",
                str(body.get("name")), str(body.get("startTime")), str(body.get("endTime")),
                body.getOrDefault("isBreak", 0), id);
        return Result.ok();
    }

    @DeleteMapping("/period/{id}")
    public Result<Void> deletePeriod(@PathVariable Long id) {
        // 节次为全局模板，删除时清理所有账号对该节次的排课引用
        jdbc.update("DELETE FROM timetable WHERE period_id=?", id);
        jdbc.update("DELETE FROM period WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 排课（per-user，经 class_id 归属） ----------
    /** 课表网格：返回节次列表 + 已排课程（含科目信息） */
    @GetMapping("/grid")
    public Result<Map<String, Object>> grid(@RequestParam Long classId) {
        Long uid = uid();
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id,name,start_time startTime,end_time endTime,sort,is_break isBreak FROM period ORDER BY sort");
        List<Map<String, Object>> cells = jdbc.queryForList(
                "SELECT t.day_of_week dayOfWeek, t.period_id periodId, t.subject_id subjectId, " +
                "sub.name subjectName, sub.teacher, sub.color, sub.is_mine isMine " +
                "FROM timetable t LEFT JOIN subject sub ON sub.id=t.subject_id " +
                "WHERE t.user_id=? AND t.class_id=?", uid, classId);
        return Result.ok(Map.of("periods", periods, "cells", cells));
    }

    /** 排课/换课（某班某天某节）；入参 id 兼容 Number 与数字字符串（前端 el-select value 可能传 "2"）；必填字段缺失给明确报错 */
    @PostMapping("/cell")
    public Result<Void> setCell(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long classId = reqLong(body, "classId");
        int dow = reqInt(body, "dayOfWeek");
        Long periodId = reqLong(body, "periodId");
        Long subjectId = body.get("subjectId") == null ? null : Long.valueOf(String.valueOf(body.get("subjectId")));
        jdbc.update("DELETE FROM timetable WHERE user_id=? AND class_id=? AND day_of_week=? AND period_id=?",
                uid, classId, dow, periodId);
        if (subjectId != null) {
            jdbc.update("INSERT INTO timetable(user_id,class_id,day_of_week,period_id,subject_id) VALUES(?,?,?,?,?)",
                    uid, classId, dow, periodId, subjectId);
        }
        return Result.ok();
    }

    @PostMapping("/clear")
    public Result<Void> clear(@RequestParam Long classId) {
        jdbc.update("DELETE FROM timetable WHERE user_id=? AND class_id=?", uid(), classId);
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 必填 Long 解析：兼容 Number 与数字字符串；缺失/空白时抛明确异常（避免 String.valueOf(null)="null" 再 NumberFormatException） */
    private Long reqLong(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("参数错误：" + key + " 不能为空");
        }
        return Long.valueOf(String.valueOf(v));
    }

    /** 必填 int 解析，规则同 reqLong */
    private int reqInt(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("参数错误：" + key + " 不能为空");
        }
        return Integer.parseInt(String.valueOf(v));
    }
}
