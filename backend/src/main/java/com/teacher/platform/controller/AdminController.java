package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理接口：清空当前账号的学生业务数据（用于从演示数据切换到真实班级）。
 * 仅清当前登录账号的数据，保留账号/班级/科目/节次/座次与值日配置/速记规则/节假日/卡片/系统设置。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController extends BaseController {

    private final JdbcTemplate jdbc;

    public AdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 清空当前账号的学生相关业务数据。
     * 按“先子表后父表”的顺序删除，避免外键/引用残留。
     */
    @PostMapping("/reset-business")
    public Result<Map<String, Object>> resetBusiness() {
        Long uid = uid();
        // 引用 student / exam 的业务表在前，主表 student、exam 在最后
        List<String> tables = List.of(
                "score", "attendance", "discipline_log", "homework_check",
                "comm_log", "todo", "seat_assignment", "duty_roster",
                "committee", "parent_contact", "quick_note", "exam", "student"
        );
        int total = 0;
        Map<String, Object> detail = new LinkedHashMap<>();
        for (String t : tables) {
            int n = jdbc.update("DELETE FROM " + t + " WHERE user_id=?", uid);
            detail.put(t, n);
            total += n;
        }
        return Result.ok(Map.of("deletedRows", total, "detail", detail));
    }
}
