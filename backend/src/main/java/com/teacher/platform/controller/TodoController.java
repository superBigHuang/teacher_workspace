package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 待办事项（按账号隔离）
 */
@RestController
@RequestMapping("/api/todo")
public class TodoController extends BaseController {

    private final JdbcTemplate jdbc;

    public TodoController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) Integer done) {
        StringBuilder sql = new StringBuilder(
                "SELECT id,title,category,due_date dueDate,done,source,created_at createdAt FROM todo WHERE user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(uid());
        if (category != null && !category.isBlank()) {
            sql.append(" AND category=?");
            args.add(category);
        }
        if (done != null) {
            sql.append(" AND done=?");
            args.add(done);
        }
        sql.append(" ORDER BY done ASC, (due_date IS NULL), due_date ASC, id DESC");
        return Result.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    @PostMapping
    public Result<Long> add(@RequestBody Map<String, Object> body) {
        String title = str(body.get("title"));
        if (title.isBlank()) return Result.fail("标题不能为空");
        jdbc.update("INSERT INTO todo(user_id,title,category,due_date,done,source) VALUES(?,?,?,?,0,?)",
                uid(), title, str(body.getOrDefault("category", "其他")),
                dateArg(body.get("dueDate")), str(body.getOrDefault("source", "MANUAL")));
        return Result.ok(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        jdbc.update("UPDATE todo SET title=?,category=?,due_date=? WHERE id=? AND user_id=?",
                str(body.get("title")), str(body.get("category")), dateArg(body.get("dueDate")), id, uid());
        return Result.ok();
    }

    @PostMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        int done = Boolean.TRUE.equals(body.get("done")) || Integer.valueOf(1).equals(body.get("done")) ? 1 : 0;
        jdbc.update("UPDATE todo SET done=? WHERE id=? AND user_id=?", done, id, uid());
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        jdbc.update("DELETE FROM todo WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 日期参数：null 或空白串统一转为 SQL NULL（待办允许无截止日期），避免空串写入 date 列报错 */
    private String dateArg(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
