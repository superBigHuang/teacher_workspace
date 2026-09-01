package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 家长联系方式：通讯录 + 家校沟通记录（按账号隔离）
 */
@RestController
@RequestMapping("/api/parent")
public class CommController extends BaseController {

    private final JdbcTemplate jdbc;

    public CommController(JdbcTemplate jdbc) {
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

    /** 通讯录：学生 + 家长信息，可按姓名/家长/电话搜索 */
    @GetMapping("/contacts")
    public Result<List<Map<String, Object>>> contacts(@RequestParam(required = false) String keyword) {
        Long uid = uid();
        Long cid = homeroomClassId();
        StringBuilder sql = new StringBuilder(
                "SELECT s.id student_id, s.stu_no, s.name student_name, s.gender, s.address " +
                "FROM student s WHERE s.user_id=? AND s.class_id=?");
        List<Object> args = new ArrayList<>(List.of(uid, cid));
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (s.name LIKE ? OR s.stu_no LIKE ? OR EXISTS(" +
                    "SELECT 1 FROM parent_contact p WHERE p.user_id=? AND p.student_id=s.id AND (p.name LIKE ? OR p.phone LIKE ?)))");
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
            args.add(uid);
            args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY s.stu_no");
        List<Map<String, Object>> list = jdbc.queryForList(sql.toString(), args.toArray());
        for (Map<String, Object> r : list) {
            Long sid = ((Number) r.get("student_id")).longValue();
            List<Map<String, Object>> parents = jdbc.queryForList(
                    "SELECT id,relation,name,phone FROM parent_contact WHERE user_id=? AND student_id=? ORDER BY id",
                    uid, sid);
            r.put("parents", parents);
        }
        return Result.ok(list);
    }

    /** 沟通记录列表（可按学生过滤） */
    @GetMapping("/logs")
    public Result<List<Map<String, Object>>> logs(@RequestParam(required = false) Long studentId) {
        Long uid = uid();
        String sql = "SELECT l.id, l.student_id, s.name student_name, l.contact_date, l.method, l.content, " +
                "l.parent_id FROM comm_log l LEFT JOIN student s ON s.id=l.student_id WHERE l.user_id=? ";
        if (studentId != null) {
            return Result.ok(jdbc.queryForList(
                    sql + "AND l.student_id=? ORDER BY l.contact_date DESC, l.id DESC", uid, studentId));
        }
        return Result.ok(jdbc.queryForList(
                sql + "ORDER BY l.contact_date DESC, l.id DESC", uid));
    }

    /** 新增沟通记录 */
    @PostMapping("/log")
    public Result<Void> addLog(@RequestBody Map<String, Object> body) {
        Object sid = body.get("studentId");
        if (sid == null) return Result.fail("请选择学生");
        jdbc.update("INSERT INTO comm_log(user_id,student_id,parent_id,contact_date,method,content) VALUES(?,?,?,?,?,?)",
                uid(), ((Number) sid).longValue(),
                body.get("parentId") == null ? null : ((Number) body.get("parentId")).longValue(),
                str(body.getOrDefault("contactDate", "")),
                str(body.getOrDefault("method", "电话")),
                str(body.getOrDefault("content", "")));
        return Result.ok();
    }

    @DeleteMapping("/log/{id}")
    public Result<Void> deleteLog(@PathVariable Long id) {
        jdbc.update("DELETE FROM comm_log WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    /** 清空当前账号全部沟通记录 */
    @PostMapping("/logs/clear")
    public Result<Void> clearLogs() {
        jdbc.update("DELETE FROM comm_log WHERE user_id=?", uid());
        return Result.ok();
    }

    /** 补录家长（沟通弹窗内用） */
    @PostMapping("/contact")
    public Result<Long> addContact(@RequestBody Map<String, Object> body) {
        Long sid = ((Number) body.get("studentId")).longValue();
        jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(?,?,?,?,?)",
                uid(), sid, str(body.getOrDefault("relation", "其他")),
                str(body.get("name")), str(body.get("phone")));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
