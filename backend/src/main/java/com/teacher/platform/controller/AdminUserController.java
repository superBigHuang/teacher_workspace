package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.config.UserDataSeeder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户管理（仅 ADMIN）：账号列表、新增账号（选角色/注册渠道）、重置密码、停用启用、切换角色。
 * 账号只停用不删除；初始 admin 与当前登录人有硬保护，防止管理员把自己锁死。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController extends BaseController {

    /** 合法角色 */
    private static final Set<String> ROLES = Set.of("ADMIN", "TEACHER");
    /** 合法注册渠道：friend 朋友介绍 / xhs 小红书 / school 同校老师 */
    private static final Set<String> SOURCES = Set.of("friend", "xhs", "school");
    /** 账号名规则：3-64 位字母/数字/下划线 */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,64}$");
    /** 新建账号与重置密码的统一初始密码 */
    private static final String INIT_PASSWORD = "123456";

    private final JdbcTemplate jdbc;
    private final UserDataSeeder seeder;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AdminUserController(JdbcTemplate jdbc, UserDataSeeder seeder) {
        this.jdbc = jdbc;
        this.seeder = seeder;
    }

    /** 账号列表（量小不分页，按 id 升序） */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        requireAdmin();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, display_name displayName, role, enabled, source, " +
                        "created_at createdAt, last_login_at lastLoginAt " +
                        "FROM sys_user ORDER BY id ASC");
        return Result.ok(rows);
    }

    /**
     * 新增账号：username 必填且唯一、displayName 必填、role 默认 TEACHER、source 三选一必选；
     * 密码固定 BCrypt(123456)；入库后播种该账号的默认班级/科目等空架子数据。
     */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String username = str(body.get("username"));
        String displayName = str(body.get("displayName"));
        String role = str(body.get("role"));
        String source = str(body.get("source"));
        if (!USERNAME.matcher(username).matches()) {
            return Result.fail(400, "账号为 3-64 位字母、数字或下划线");
        }
        if (displayName.isBlank()) return Result.fail(400, "请填写显示名");
        if (role.isBlank()) role = "TEACHER";
        if (!ROLES.contains(role)) return Result.fail(400, "角色非法");
        if (!SOURCES.contains(source)) return Result.fail(400, "请选择注册渠道");

        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",
                Integer.class, username);
        if (cnt != null && cnt > 0) return Result.fail(400, "账号已存在：" + username);

        Long newId;
        try {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,enabled,source) " +
                            "VALUES(?,?,?,?,1,?)",
                    username, encoder.encode(INIT_PASSWORD), displayName, role, source);
            newId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (DuplicateKeyException e) {
            return Result.fail(400, "账号已存在：" + username);
        }
        // 新账号默认数据（空班级架子，不含学生）
        seeder.seedIfEmpty(newId, displayName);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, username, display_name displayName, role, enabled, source, " +
                        "created_at createdAt, last_login_at lastLoginAt FROM sys_user WHERE id=?", newId);
        return Result.ok(row);
    }

    /** 重置指定账号密码为 123456 */
    @PostMapping("/{id}/reset-password")
    public Result<Void> resetPassword(@PathVariable Long id) {
        requireAdmin();
        if (userMissing(id)) return Result.fail(400, "账号不存在");
        jdbc.update("UPDATE sys_user SET password_hash=? WHERE id=?", encoder.encode(INIT_PASSWORD), id);
        return Result.ok();
    }

    /** 启用/停用账号（body.enabled 布尔）；自己和初始 admin 不可停用 */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        Map<String, Object> target = findUser(id);
        if (target == null) return Result.fail(400, "账号不存在");
        if (!enabled) {
            if (id.equals(uid())) return Result.fail(400, "不能停用自己的账号");
            if ("admin".equals(target.get("username"))) return Result.fail(400, "初始 admin 账号不可停用");
        }
        jdbc.update("UPDATE sys_user SET enabled=? WHERE id=?", enabled ? 1 : 0, id);
        return Result.ok();
    }

    /** 切换角色 ADMIN/TEACHER；不能改自己角色，初始 admin 不可降级 */
    @PutMapping("/{id}/role")
    public Result<Void> updateRole(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        requireAdmin();
        String role = str(body.get("role"));
        if (!ROLES.contains(role)) return Result.fail(400, "角色非法");
        Map<String, Object> target = findUser(id);
        if (target == null) return Result.fail(400, "账号不存在");
        if (id.equals(uid())) return Result.fail(400, "不能修改自己的角色");
        if ("admin".equals(target.get("username")) && !"ADMIN".equals(role)) {
            return Result.fail(400, "初始 admin 账号不可降级");
        }
        jdbc.update("UPDATE sys_user SET role=? WHERE id=?", role, id);
        return Result.ok();
    }

    /** 按主键取账号（含 username 用于保护判断）；不存在返回 null */
    private Map<String, Object> findUser(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, username, role FROM sys_user WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean userMissing(Long id) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?", Integer.class, id);
        return cnt == null || cnt == 0;
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
