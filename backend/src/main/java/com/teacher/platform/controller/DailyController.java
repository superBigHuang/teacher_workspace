package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.PetService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 班级日常：早读考勤、课堂纪律、作业收缴（按账号隔离）
 */
@RestController
@RequestMapping("/api/daily")
public class DailyController extends BaseController {

    private final JdbcTemplate jdbc;
    private final PetService petService;

    public DailyController(JdbcTemplate jdbc, PetService petService) {
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

    private List<Map<String, Object>> students() {
        return jdbc.queryForList(
                "SELECT id,stu_no stuNo,name,gender FROM student WHERE user_id=? AND class_id=? ORDER BY stu_no",
                uid(), homeroomClassId());
    }

    // ---------- 早读考勤 ----------
    /** 某日考勤：返回学生名单 + 当日状态（未登记默认出勤） */
    @GetMapping("/attendance")
    public Result<Map<String, Object>> attendance(@RequestParam String date) {
        Long uid = uid();
        List<Map<String, Object>> list = students();
        Map<String, String> statusMap = new HashMap<>();
        jdbc.queryForList("SELECT student_id sid, status FROM attendance WHERE user_id=? AND record_date=?", uid, date)
                .forEach(r -> statusMap.put(String.valueOf(r.get("sid")), (String) r.get("status")));
        list.forEach(s -> s.put("status", statusMap.getOrDefault(String.valueOf(s.get("id")), "出勤")));
        return Result.ok(Map.of("students", list));
    }

    /** 保存考勤（批量 upsert） */
    @PostMapping("/attendance/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveAttendance(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String date = str(body.get("date"));
        List<Map<String, Object>> records = (List<Map<String, Object>>) body.get("records");
        for (Map<String, Object> r : records) {
            Long sid = ((Number) r.get("studentId")).longValue();
            String status = str(r.getOrDefault("status", "出勤"));
            jdbc.update("INSERT INTO attendance(user_id,record_date,student_id,status) VALUES(?,?,?,?) " +
                    "ON DUPLICATE KEY UPDATE status=VALUES(status)", uid, date, sid, status);
            // 宠物自动联动：迟到 -1 / 缺勤 -2（biz_ref 幂等）；改回出勤/请假时回退扣分
            if (petService.autoDeductEnabled(uid)) {
                String ref = "att:" + date + ":" + sid;
                if ("迟到".equals(status)) petService.award(uid, sid, null, -1, "AUTO_ATTENDANCE", ref, "迟到");
                else if ("缺勤".equals(status)) petService.award(uid, sid, null, -2, "AUTO_ATTENDANCE", ref, "缺勤");
                else petService.rollbackBiz(uid, "AUTO_ATTENDANCE", ref);
            }
        }
        return Result.ok();
    }

    // ---------- 课堂纪律 ----------
    @GetMapping("/discipline")
    public Result<List<Map<String, Object>>> discipline(@RequestParam(required = false) String date) {
        Long uid = uid();
        if (date != null && !date.isBlank()) {
            return Result.ok(jdbc.queryForList(
                    "SELECT d.id, d.record_date date, d.student_id studentId, s.name, d.content " +
                    "FROM discipline_log d LEFT JOIN student s ON s.id=d.student_id " +
                    "WHERE d.user_id=? AND d.record_date=? ORDER BY d.id DESC", uid, date));
        }
        return Result.ok(jdbc.queryForList(
                "SELECT d.id, d.record_date date, d.student_id studentId, s.name, d.content " +
                "FROM discipline_log d LEFT JOIN student s ON s.id=d.student_id " +
                "WHERE d.user_id=? ORDER BY d.record_date DESC, d.id DESC LIMIT 100", uid));
    }

    @PostMapping("/discipline")
    @SuppressWarnings("unchecked")
    public Result<Void> addDiscipline(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String date = str(body.get("date"));
        String content = str(body.get("content"));
        List<Object> ids = (List<Object>) body.getOrDefault("studentIds", new ArrayList<>());
        for (Object o : ids) {
            Long sid = ((Number) o).longValue();
            jdbc.update("INSERT INTO discipline_log(user_id,record_date,student_id,content) VALUES(?,?,?,?)",
                    uid, date, sid, content);
            Long logId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            // 宠物自动联动：违纪 -2（biz_ref 关联纪律记录 id，删记录时回退）
            if (petService.autoDeductEnabled(uid)) {
                petService.award(uid, sid, null, -2, "AUTO_DISCIPLINE", "dis:" + logId, "课堂违纪");
            }
        }
        return Result.ok();
    }

    @DeleteMapping("/discipline/{id}")
    public Result<Void> deleteDiscipline(@PathVariable Long id) {
        jdbc.update("DELETE FROM discipline_log WHERE id=? AND user_id=?", id, uid());
        // 联动回退该条违纪产生的宠物扣分
        petService.rollbackBiz(uid(), "AUTO_DISCIPLINE", "dis:" + id);
        return Result.ok();
    }

    // ---------- 作业收缴 ----------
    /** 某日某科目：返回学生名单 + 是否未交（默认交） */
    @GetMapping("/homework")
    public Result<Map<String, Object>> homework(@RequestParam String date, @RequestParam String subject) {
        Long uid = uid();
        List<Map<String, Object>> list = students();
        Set<Long> missing = new HashSet<>();
        jdbc.queryForList(
                "SELECT student_id sid FROM homework_check WHERE user_id=? AND record_date=? AND subject=? AND status='未交'",
                uid, date, subject)
                .forEach(r -> missing.add(((Number) r.get("sid")).longValue()));
        list.forEach(s -> s.put("missing", missing.contains(((Number) s.get("id")).longValue())));
        return Result.ok(Map.of("students", list));
    }

    /** 保存作业收缴：只存未交名单 */
    @PostMapping("/homework/save")
    @SuppressWarnings("unchecked")
    public Result<Void> saveHomework(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String date = str(body.get("date"));
        String subject = str(body.get("subject"));
        List<Object> missingIds = (List<Object>) body.getOrDefault("missingIds", new ArrayList<>());
        jdbc.update("DELETE FROM homework_check WHERE user_id=? AND record_date=? AND subject=?", uid, date, subject);
        List<Long> sidList = new ArrayList<>();
        for (Object o : missingIds) {
            Long sid = ((Number) o).longValue();
            sidList.add(sid);
            jdbc.update("INSERT INTO homework_check(user_id,record_date,subject,student_id,status) VALUES(?,?,?,?,'未交')",
                    uid, date, subject, sid);
        }
        // 宠物自动联动：未交 -1；补交（移出未交名单）回退扣分
        if (petService.autoDeductEnabled(uid)) {
            petService.syncHomework(uid, date, subject, sidList);
        }
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
