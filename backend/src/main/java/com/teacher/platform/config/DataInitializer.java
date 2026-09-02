package com.teacher.platform.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动初始化：
 * 1) 幂等迁移：为旧库补 user_id 列、调整多用户唯一约束；
 * 2) 全局模板种子（节次、速记规则，所有账号共享）；
 * 3) 默认 admin 账号及其默认数据（经 UserDataSeeder）。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final UserDataSeeder seeder;

    public DataInitializer(JdbcTemplate jdbc, UserDataSeeder seeder) {
        this.jdbc = jdbc;
        this.seeder = seeder;
    }

    /** 需要按账号隔离的业务表 */
    private static final String[] USER_TABLES = {
            "class_info", "student", "parent_contact", "subject", "timetable", "seat_config",
            "seat_assignment", "duty_task", "duty_config", "duty_roster", "committee", "exam",
            "score", "comm_log", "todo", "attendance", "discipline_log", "homework_check",
            "quick_note", "holiday", "card_config", "setting",
            "pet", "pet_rule", "pet_log", "pet_badge", "pet_shop_item", "pet_exchange"
    };

    @Override
    public void run(String... args) {
        migrate();
        seedGlobalPeriods();
        seedGlobalRules();
        seedBuiltinAchievements();
        seedBuiltinSkin();
        seedAdmin();
    }

    // ---------- 迁移 ----------
    private void migrate() {
        // sys_user 新增字段
        ensureColumn("sys_user", "email", "email VARCHAR(128)");
        ensureColumn("sys_user", "school", "school VARCHAR(128)");
        // 业务表补 user_id（现有行自动取 DEFAULT 1，即 admin）
        for (String t : USER_TABLES) {
            ensureColumn(t, "user_id", "user_id BIGINT NOT NULL DEFAULT 1");
        }
        ensureColumn("duty_roster", "task_id", "task_id BIGINT NULL COMMENT '分配的值日事项'");
        // 考勤关联请假单：请假登记自动写入的考勤带 leave_id，删单时精确回滚（手动标记不受影响）
        ensureColumn("attendance", "leave_id", "leave_id BIGINT NULL COMMENT '关联请假单 leave_record.id'");
        // 宠物 v1.1：跨页面升级待演标记
        ensureColumn("pet", "evolve_pending",
                "evolve_pending TINYINT NOT NULL DEFAULT 0 COMMENT '跨页面升级待演标记'");

        // 多用户唯一约束：去掉旧的单列唯一，改为 (user_id, 业务键)
        rebuildUniqueIndex("student", "uk_stuno", "uk_user_stuno", "user_id, stu_no");
        rebuildUniqueIndex("duty_config", "day_of_week", "uk_user_day", "user_id, day_of_week");
        rebuildUniqueIndex("card_config", "card_key", "uk_user_card", "user_id, card_key");
        rebuildSettingPrimaryKey();
    }

    /** 若指定表缺少某列则 ALTER 增加；通过 JDBC 元数据判断，保证可重复执行 */
    private void ensureColumn(String table, String column, String columnDef) {
        try {
            if (!columnExists(table, column)) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDef);
            }
        } catch (Exception e) {
            System.out.println("[migrate] 跳过 " + table + "." + column + "：" + e.getMessage());
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (Connection conn = jdbc.getDataSource().getConnection();
             ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }

    private boolean indexExists(String table, String index) throws Exception {
        try (Connection conn = jdbc.getDataSource().getConnection();
             ResultSet rs = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    /** 删除旧唯一索引（若存在）并建立 (user_id, ...) 联合唯一索引（若不存在） */
    private void rebuildUniqueIndex(String table, String oldIndex, String newIndex, String cols) {
        try {
            if (indexExists(table, oldIndex)) {
                jdbc.execute("ALTER TABLE " + table + " DROP INDEX " + oldIndex);
            }
            if (!indexExists(table, newIndex)) {
                jdbc.execute("ALTER TABLE " + table + " ADD UNIQUE KEY " + newIndex + " (" + cols + ")");
            }
        } catch (Exception e) {
            System.out.println("[migrate] 索引跳过 " + table + "：" + e.getMessage());
        }
    }

    /** setting 表主键由 k 改为 (user_id, k) */
    private void rebuildSettingPrimaryKey() {
        try {
            List<String> pk = new ArrayList<>();
            try (Connection conn = jdbc.getDataSource().getConnection();
                 ResultSet rs = conn.getMetaData().getPrimaryKeys(conn.getCatalog(), null, "setting")) {
                while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
            }
            if (!pk.contains("user_id")) {
                jdbc.execute("ALTER TABLE setting DROP PRIMARY KEY");
                jdbc.execute("ALTER TABLE setting ADD PRIMARY KEY (user_id, k)");
            }
        } catch (Exception e) {
            System.out.println("[migrate] setting 主键跳过：" + e.getMessage());
        }
    }

    // ---------- 全局模板（所有账号共享，不带 user_id） ----------
    private void seedGlobalPeriods() {
        if (count("SELECT COUNT(*) FROM period") == 0) {
            Object[][] rows = {
                    {"早自习", "07:40", "08:00", 1, 0},
                    {"第1节", "08:00", "08:45", 2, 0},
                    {"第2节", "08:55", "09:40", 3, 0},
                    {"跑操", "09:40", "10:05", 4, 1},
                    {"第3节", "10:05", "10:50", 5, 0},
                    {"第4节", "11:00", "11:45", 6, 0},
                    {"午饭+午休", "12:00", "13:45", 7, 1},
                    {"第5节", "14:00", "14:45", 8, 0},
                    {"第6节", "14:55", "15:40", 9, 0},
                    {"晚自习1", "18:30", "19:20", 10, 0},
                    {"晚自习2", "19:30", "20:20", 11, 0},
            };
            for (Object[] r : rows) {
                jdbc.update("INSERT INTO period(name,start_time,end_time,sort,is_break) VALUES(?,?,?,?,?)",
                        r[0], r[1], r[2], r[3], r[4]);
            }
        }
    }

    private void seedGlobalRules() {
        if (count("SELECT COUNT(*) FROM quick_note_rule") == 0) {
            Object[][] rows = {
                    {"ATTENDANCE", "迟到,早退,请假,缺勤,旷课,考勤,早读"},
                    {"COMM", "电话,家长,沟通,联系,微信,家访,家长群"},
                    {"HOMEWORK", "作业,未交,催,收齐,补交,交作业"},
                    {"DISCIPLINE", "违纪,说话,零食,打架,捣乱,课堂纪律,打闹"},
                    {"TODO", "准备,班会,PPT,活动,记得,需要,别忘了,提醒,采购,打印"},
            };
            for (Object[] r : rows) {
                jdbc.update("INSERT INTO quick_note_rule(module_key,keywords,enabled) VALUES(?,?,1)", r[0], r[1]);
            }
        }
    }

    /**
     * 内置成就定义（user_id=0 全局共享，所有账号 UNION 可见）。
     * 10 条自动规则 + 1 条大满贯；按 name 幂等补齐，老师可改阈值/停用但不可删。
     */
    private void seedBuiltinAchievements() {
        if (count("SELECT COUNT(*) FROM achievement_def WHERE user_id=0") > 0) return;
        // 每行：名称, 描述, 图标, 等级, 规则类型, 规则参数JSON
        Object[][] defs = {
                {"全勤之星", "本学期无迟到、无缺勤、无请假记录，全勤到校", "check", "SILVER",
                        "PERFECT_ATTENDANCE", "{\"allowLeave\":false}"},
                {"作业全勤", "本学期作业全部按时上交，没有未交记录", "books", "SILVER",
                        "PERFECT_HOMEWORK", "{}"},
                {"纪律标兵", "本学期没有课堂违纪记录，自律守纪", "shield", "SILVER",
                        "PERFECT_DISCIPLINE", "{}"},
                {"进步之星", "最近两次考试班级排名提升 5 名及以上", "rocket", "SILVER",
                        "RANK_UP", "{\"minRankUp\":5}"},
                {"全科优秀", "最近一次考试所有科目成绩均达到优秀线", "glowing-star", "GOLD",
                        "ALL_EXCELLENT", "{}"},
                {"单科状元", "任一科目取得班级最高分（并列均可获得）", "crown", "BRONZE",
                        "SUBJECT_TOP", "{}"},
                {"满分达人", "任一科目取得满分（满分线默认 100 分）", "hundred", "BRONZE",
                        "SCORE_FULL", "{\"fullScore\":100}"},
                {"劳动能手", "本学期参与值日劳动 10 次及以上", "broom", "BRONZE",
                        "DUTY_COUNT", "{\"minDuty\":10}"},
                {"班委担当", "担任班委职务，认真为班级服务", "medal-1st", "BRONZE",
                        "COMMITTEE", "{}"},
                {"连续全勤", "连续 4 周没有迟到、缺勤记录", "sun", "BRONZE",
                        "CONTINUOUS_ATTENDANCE", "{\"weeks\":4}"},
                {"大满贯", "集齐本学期全部启用的自动成就", "trophy", "PLATINUM",
                        "COLLECTOR", "{}"},
        };
        for (Object[] d : defs) {
            jdbc.update("INSERT INTO achievement_def(user_id,name,description,icon,tier,kind,rule_type,rule_params,enabled,builtin) " +
                            "VALUES(0,?,?,?,?,'AUTO',?,?,1,1)",
                    d[0], d[1], d[2], d[3], d[4], d[5]);
        }
    }

    /**
     * 内置宠物皮肤（user_id=NULL 全局共享，所有账号可见可领养）。
     * 自定义皮肤由老师在「自创宠物」弹窗上传，按账号隔离。
     * 按 skin_key 逐条幂等：已存在跳过，版本迭代追加新皮肤时老库重启即可补播。
     * 图片随前端发布：frontend/public/pets/{key}_{egg|1..5}.webp build 进 dist。
     */
    private void seedBuiltinSkin() {
        // 内置皮肤清单：skin_key / 中文名 / 进化特效主题（与前端 FX_THEMES 对应）
        record BuiltinSkin(String key, String name, String fxTheme) {}
        List<BuiltinSkin> skins = List.of(
                new BuiltinSkin("dragon", "云小龙", "gold"),
                new BuiltinSkin("firedragon", "烈焰神龙", "fire"),
                new BuiltinSkin("catdragon", "灵翼喵龙", "ice"),
                new BuiltinSkin("candy", "糖果天使龙", "candy"),
                new BuiltinSkin("forest", "青叶森龙", "forest"),
                new BuiltinSkin("moonrabbit", "月华仙兔", "thunder"));
        for (BuiltinSkin s : skins) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM pet_skin WHERE skin_key=?",
                    Integer.class, s.key());
            if (n != null && n > 0) continue;
            jdbc.update("INSERT INTO pet_skin(user_id,skin_key,name,built_in,fx_theme," +
                            "img_egg,img_1,img_2,img_3,img_4,img_5) VALUES(NULL,?,? ,1,?, ?,?,?,?,?,?)",
                    s.key(), s.name(), s.fxTheme(),
                    "/pets/" + s.key() + "_egg.webp",
                    "/pets/" + s.key() + "_1.webp", "/pets/" + s.key() + "_2.webp",
                    "/pets/" + s.key() + "_3.webp", "/pets/" + s.key() + "_4.webp",
                    "/pets/" + s.key() + "_5.webp");
        }
    }

    // ---------- 默认 admin 账号 ----------
    private void seedAdmin() {
        if (count("SELECT COUNT(*) FROM sys_user") == 0) {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES(?,?,?)",
                    "admin", encoder.encode("123456"), "王路飞");
        }
        // admin 账号（id=1）的默认数据；已有数据则仅补齐缺失设置
        Long adminId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username='admin' ORDER BY id LIMIT 1",
                Long.class);
        if (adminId != null) {
            String name = jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?", String.class, adminId);
            seeder.seedIfEmpty(adminId, name);
        }
    }

    private Integer count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
