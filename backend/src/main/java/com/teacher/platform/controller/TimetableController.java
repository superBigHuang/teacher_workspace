package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 课程表：班级、科目、节次、排课网格
 */
@RestController
@RequestMapping("/api/timetable")
public class TimetableController {

    private final JdbcTemplate jdbc;

    public TimetableController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 班级 ----------
    @GetMapping("/classes")
    public Result<List<Map<String, Object>>> classes() {
        return Result.ok(jdbc.queryForList("SELECT id,name,type,term FROM class_info ORDER BY type DESC, id"));
    }

    @PostMapping("/class")
    public Result<Long> addClass(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO class_info(name,type,term) VALUES(?,?,?)",
                str(body.get("name")), str(body.getOrDefault("type", "SUBJECT")), str(body.get("term")));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/class/{id}")
    public Result<Void> renameClass(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE class_info SET name=? WHERE id=?", str(body.get("name")), id);
        return Result.ok();
    }

    // ---------- 科目 ----------
    @GetMapping("/subjects")
    public Result<List<Map<String, Object>>> subjects() {
        return Result.ok(jdbc.queryForList("SELECT id,name,teacher,color FROM subject ORDER BY id"));
    }

    @PostMapping("/subject")
    public Result<Long> addSubject(@RequestBody Map<String, Object> body) {
        jdbc.update("INSERT INTO subject(name,teacher,color) VALUES(?,?,?)",
                str(body.get("name")), str(body.get("teacher")), str(body.getOrDefault("color", "#9fc5f8")));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/subject/{id}")
    public Result<Void> updateSubject(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE subject SET name=?,teacher=?,color=? WHERE id=?",
                str(body.get("name")), str(body.get("teacher")), str(body.get("color")), id);
        return Result.ok();
    }

    @DeleteMapping("/subject/{id}")
    public Result<Void> deleteSubject(@PathVariable Long id) {
        jdbc.update("DELETE FROM subject WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 节次 ----------
    @GetMapping("/periods")
    public Result<List<Map<String, Object>>> periods() {
        return Result.ok(jdbc.queryForList("SELECT id,name,start_time startTime,end_time endTime,sort,is_break isBreak FROM period ORDER BY sort"));
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
        jdbc.update("DELETE FROM timetable WHERE period_id=?", id);
        jdbc.update("DELETE FROM period WHERE id=?", id);
        return Result.ok();
    }

    // ---------- 排课 ----------
    /** 课表网格：返回节次列表 + 已排课程（含科目信息） */
    @GetMapping("/grid")
    public Result<Map<String, Object>> grid(@RequestParam Long classId) {
        List<Map<String, Object>> periods = jdbc.queryForList(
                "SELECT id,name,start_time startTime,end_time endTime,sort,is_break isBreak FROM period ORDER BY sort");
        List<Map<String, Object>> cells = jdbc.queryForList(
                "SELECT t.day_of_week dayOfWeek, t.period_id periodId, t.subject_id subjectId, " +
                "sub.name subjectName, sub.teacher, sub.color " +
                "FROM timetable t LEFT JOIN subject sub ON sub.id=t.subject_id WHERE t.class_id=?", classId);
        return Result.ok(Map.of("periods", periods, "cells", cells));
    }

    /** 排课/换课（某班某天某节） */
    @PostMapping("/cell")
    public Result<Void> setCell(@RequestBody Map<String, Object> body) {
        Long classId = ((Number) body.get("classId")).longValue();
        int dow = ((Number) body.get("dayOfWeek")).intValue();
        Long periodId = ((Number) body.get("periodId")).longValue();
        Long subjectId = body.get("subjectId") == null ? null : ((Number) body.get("subjectId")).longValue();
        jdbc.update("DELETE FROM timetable WHERE class_id=? AND day_of_week=? AND period_id=?", classId, dow, periodId);
        if (subjectId != null) {
            jdbc.update("INSERT INTO timetable(class_id,day_of_week,period_id,subject_id) VALUES(?,?,?,?)",
                    classId, dow, periodId, subjectId);
        }
        return Result.ok();
    }

    @PostMapping("/clear")
    public Result<Void> clear(@RequestParam Long classId) {
        jdbc.update("DELETE FROM timetable WHERE class_id=?", classId);
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
