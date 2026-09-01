package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人设置：账号资料（显示名/邮箱/学校）、教学设置（学期/校训/分数线）、学校名录共享记忆
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController extends BaseController {

    private final JdbcTemplate jdbc;

    public SettingsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 个人资料 + 教学设置（合并 sys_user 与 setting） */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        Long uid = uid();
        Map<String, Object> result = new LinkedHashMap<>(jdbc.queryForMap(
                "SELECT username, display_name displayName, email, school FROM sys_user WHERE id=?", uid));
        jdbc.queryForList("SELECT k,v FROM setting WHERE user_id=?", uid)
                .forEach(r -> result.put((String) r.get("k"), r.get("v")));
        return Result.ok(result);
    }

    /** 保存资料 + 教学设置；学校名同时写入全校共享的学校名录 */
    @PutMapping("/profile")
    public Result<Void> saveProfile(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String displayName = str(body.get("displayName"));
        String email = str(body.get("email"));
        String school = str(body.get("school"));
        jdbc.update("UPDATE sys_user SET display_name=?, email=?, school=? WHERE id=?",
                displayName, email.isEmpty() ? null : email, school.isEmpty() ? null : school, uid);

        // 学校记忆：跨账号共享，任何人输入过的学校都能被搜到；重复输入累加使用频次
        if (!school.isEmpty()) {
            jdbc.update("INSERT INTO school_dict(name) VALUES(?) " +
                    "ON DUPLICATE KEY UPDATE use_count=use_count+1", school);
        }

        upsertSetting(uid, "teacher_name", displayName);
        upsertSetting(uid, "term", str(body.get("term")));
        upsertSetting(uid, "school_motto", str(body.get("schoolMotto")));
        upsertSetting(uid, "personal_motto", str(body.get("personalMotto")));
        upsertSetting(uid, "pass_line", str(body.get("passLine")));
        upsertSetting(uid, "excellent_line", str(body.get("excellentLine")));
        // 学期起止日期（本学期统计区间）与病假预警天数
        upsertSetting(uid, "term_start", str(body.get("termStart")));
        upsertSetting(uid, "term_end", str(body.get("termEnd")));
        upsertSetting(uid, "sick_leave_warn_days", str(body.get("sickLeaveWarnDays")));
        // 通知单统一寄语（家长成绩通知单兜底用）
        upsertSetting(uid, "report_message", str(body.get("reportMessage")));
        // 班级宠物园：登记考勤/作业/纪律时自动扣宠物成长值总开关（'1'开 '0'关，请假不扣）
        upsertSetting(uid, "pet_auto_deduct", str(body.get("petAutoDeduct")));
        // 侧边栏头像（emoji 字符）
        upsertSetting(uid, "avatar", str(body.get("avatar")));
        return Result.ok();
    }

    /** 学校名模糊搜索（全校共享，按使用频次排序） */
    @GetMapping("/schools")
    public Result<List<String>> schools(@RequestParam(required = false) String q) {
        String kw = q == null ? "" : q.trim();
        List<Map<String, Object>> rows;
        if (kw.isEmpty()) {
            rows = jdbc.queryForList("SELECT name FROM school_dict ORDER BY use_count DESC, id DESC LIMIT 20");
        } else {
            rows = jdbc.queryForList(
                    "SELECT name FROM school_dict WHERE name LIKE ? ORDER BY use_count DESC, id DESC LIMIT 20",
                    "%" + kw + "%");
        }
        return Result.ok(rows.stream().map(r -> (String) r.get("name")).toList());
    }

    /** 写入账号级设置（不存在则插，存在则改） */
    private void upsertSetting(Long uid, String k, String v) {
        if (v == null || v.isEmpty()) return;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM setting WHERE user_id=? AND k=?", Integer.class, uid, k);
        if (n != null && n > 0) {
            jdbc.update("UPDATE setting SET v=? WHERE user_id=? AND k=?", v, uid, k);
        } else {
            jdbc.update("INSERT INTO setting(user_id,k,v) VALUES(?,?,?)", uid, k, v);
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
