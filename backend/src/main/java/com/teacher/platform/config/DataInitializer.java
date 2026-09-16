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
        // 用户管理：角色 ADMIN/TEACHER、启用状态、注册渠道、最近登录时间（存量账号默认 TEACHER/启用）
        ensureColumn("sys_user", "role",
                "role VARCHAR(16) NOT NULL DEFAULT 'TEACHER' COMMENT '角色：ADMIN/TEACHER'");
        ensureColumn("sys_user", "enabled",
                "enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用'");
        ensureColumn("sys_user", "source",
                "source VARCHAR(16) NULL COMMENT '注册渠道 friend/xhs/school'");
        ensureColumn("sys_user", "last_login_at", "last_login_at DATETIME NULL COMMENT '最近登录时间'");
        // 存量库：把初始 admin 固化为管理员（幂等，每次启动校正，防止误降权）
        jdbc.update("UPDATE sys_user SET role='ADMIN' WHERE username='admin'");
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
        // 宠物 v1.2：孵蛋破壳（0=孵化中蛋，1=已破壳；DEFAULT 1 保证存量宠物全部视为已破壳，不回退成蛋）
        ensureColumn("pet", "hatched",
                "hatched TINYINT NOT NULL DEFAULT 1 COMMENT '是否已破壳：0=孵化中蛋，1=已出生'");
        // 成就进阶：定义表 tiered=1 表示铜银金三档可进阶；获得记录 tier 为学生当前持有档（NULL=老数据回退 def.tier）
        ensureColumn("achievement_def", "tiered",
                "tiered TINYINT NOT NULL DEFAULT 0 COMMENT '1=可进阶（铜银金三档），0=固定档'");
        ensureColumn("student_achievement", "tier",
                "tier VARCHAR(16) NULL COMMENT '当前持有档 BRONZE/SILVER/GOLD/PLATINUM；NULL=老数据回退 def.tier'");
        // 科目「我教的」显式标记：工作台"我的课"/作业科目按 is_mine 判定，不再靠 teacher 文本猜。
        // 仅在首次加列当次把存量 teacher='我' 的科目回填为勾选；之后老师手动勾选/取消不再被覆盖。
        try {
            if (!columnExists("subject", "is_mine")) {
                jdbc.execute("ALTER TABLE subject ADD COLUMN is_mine TINYINT NOT NULL DEFAULT 0 " +
                        "COMMENT '1=我任教的科目（工作台我的课/作业科目按此判定）'");
                int n = jdbc.update("UPDATE subject SET is_mine=1 WHERE teacher='我'");
                System.out.println("[migrate] subject.is_mine 已添加，回填 teacher='我' 科目 " + n + " 条");
            }
        } catch (Exception e) {
            System.out.println("[migrate] subject.is_mine 跳过：" + e.getMessage());
        }

        // 打怪兽多模式·场次化：daily_boss 由"每天一只"升级为"一天可多场"，补模式/标题/题库/轮次/目标轮次/结束时间；
        // boss_hit 补轮次与小组列（小组=座位表的列）。老库重启幂等加列，存量行 mode 默认 RANDOM。
        ensureColumn("daily_boss", "mode",
                "mode VARCHAR(16) DEFAULT 'RANDOM' COMMENT 'RANDOM/CLASS/GROUP/MANUAL'");
        ensureColumn("daily_boss", "title", "title VARCHAR(64)");
        ensureColumn("daily_boss", "category_id", "category_id BIGINT NULL COMMENT '题库分类；NULL=抽背'");
        ensureColumn("daily_boss", "difficulty", "difficulty INT NULL");
        ensureColumn("daily_boss", "round_no", "round_no INT DEFAULT 1");
        ensureColumn("daily_boss", "target_rounds", "target_rounds INT DEFAULT 20");
        ensureColumn("daily_boss", "ended_at", "ended_at DATETIME NULL");
        ensureColumn("boss_hit", "round_no", "round_no INT DEFAULT 1");
        ensureColumn("boss_hit", "group_col", "group_col INT NULL COMMENT '小组模式座位列号'");
        rebuildBossIndex();

        // 多用户唯一约束：去掉旧的单列唯一，改为 (user_id, 业务键)
        rebuildUniqueIndex("student", "uk_stuno", "uk_user_stuno", "user_id, stu_no");
        rebuildUniqueIndex("duty_config", "day_of_week", "uk_user_day", "user_id, day_of_week");
        rebuildUniqueIndex("card_config", "card_key", "uk_user_card", "user_id, card_key");
        rebuildSettingPrimaryKey();
    }

    /** daily_boss 去掉 (user_id,boss_date) 唯一键（一天可多场），改普通索引 idx_boss_user_date */
    private void rebuildBossIndex() {
        try {
            if (indexExists("daily_boss", "uk_boss")) {
                jdbc.execute("ALTER TABLE daily_boss DROP INDEX uk_boss");
            }
            if (!indexExists("daily_boss", "idx_boss_user_date")) {
                jdbc.execute("ALTER TABLE daily_boss ADD KEY idx_boss_user_date (user_id, boss_date)");
            }
        } catch (Exception e) {
            System.out.println("[migrate] daily_boss 索引跳过：" + e.getMessage());
        }
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
     * 固定档 AUTO 7 条 + 可进阶 AUTO 4 条 + 大满贯 1 条；按 name 幂等补齐，老师可改阈值/停用但不可删。
     * 进阶版（tiered=1）：一条定义带铜银金三档阈值（rule_params.tiers），学生只持有当前最高档。
     */
    private void seedBuiltinAchievements() {
        if (count("SELECT COUNT(*) FROM achievement_def WHERE user_id=0") == 0) {
            // 每行：名称, 描述, 图标, 入门档, 规则类型, 规则参数JSON, 是否可进阶
            Object[][] defs = {
                    {"全勤之星", "本学期无迟到、无缺勤、无请假记录，全勤到校", "check", "SILVER",
                            "PERFECT_ATTENDANCE", "{\"allowLeave\":false}", 0},
                    {"作业全勤", "本学期作业全部按时上交，没有未交记录", "books", "SILVER",
                            "PERFECT_HOMEWORK", "{}", 0},
                    {"纪律标兵", "本学期没有课堂违纪记录，自律守纪", "shield", "SILVER",
                            "PERFECT_DISCIPLINE", "{}", 0},
                    {"进步之星", "考试班级排名提升：🥉3 名 / 🥈5 名 / 🥇10 名", "rocket", "BRONZE",
                            "RANK_UP", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":3},{\"tier\":\"SILVER\",\"min\":5},{\"tier\":\"GOLD\",\"min\":10}]}", 1},
                    {"全科优秀", "最近一次考试所有科目成绩均达到优秀线", "glowing-star", "GOLD",
                            "ALL_EXCELLENT", "{}", 0},
                    {"单科状元", "任一科目取得班级最高分（并列均可获得）", "crown", "BRONZE",
                            "SUBJECT_TOP", "{}", 0},
                    {"满分达人", "最近一次考试满分科目：🥉1 科 / 🥈2 科 / 🥇3 科（满分线默认 100 分）", "hundred", "BRONZE",
                            "SCORE_FULL", "{\"fullScore\":100,\"tiers\":[{\"tier\":\"BRONZE\",\"min\":1},{\"tier\":\"SILVER\",\"min\":2},{\"tier\":\"GOLD\",\"min\":3}]}", 1},
                    {"劳动能手", "参与值日劳动：🥉10 次 / 🥈20 次 / 🥇30 次", "broom", "BRONZE",
                            "DUTY_COUNT", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":10},{\"tier\":\"SILVER\",\"min\":20},{\"tier\":\"GOLD\",\"min\":30}]}", 1},
                    {"班委担当", "担任班委职务，认真为班级服务", "medal-1st", "BRONZE",
                            "COMMITTEE", "{}", 0},
                    {"连续全勤", "连续全勤：🥉4 周 / 🥈8 周 / 🥇整学期（本学期无迟到无缺勤即得🥇）", "sun", "BRONZE",
                            "CONTINUOUS_ATTENDANCE", "{\"weeks\":4,\"tiers\":[{\"tier\":\"BRONZE\",\"min\":4},{\"tier\":\"SILVER\",\"min\":8},{\"tier\":\"GOLD\",\"min\":20}]}", 1},
                    {"大满贯", "集齐本学期全部启用的自动成就；可进阶奖牌全部升档后大满贯同步升档（🥉50/🥈150/🥇300 点）", "trophy", "PLATINUM",
                            "COLLECTOR", "{}", 0},
            };
            for (Object[] d : defs) {
                jdbc.update("INSERT INTO achievement_def(user_id,name,description,icon,tier,kind,rule_type,rule_params,enabled,builtin,tiered) " +
                                "VALUES(0,?,?,?,?,'AUTO',?,?,1,1,?)",
                        d[0], d[1], d[2], d[3], d[4], d[5], d[6]);
            }
        }
        migrateBuiltinAchievements();
    }

    /**
     * 内置成就进阶版迁移与新播种（幂等，老库重启即生效）：
     * (a) 4 条老内置按 rule_type 升级为可进阶（tiered=1、入门档 BRONZE、三档阈值与描述）；
     * (b) 新增 5 条宠物/排名联动 AUTO；(c) 新增 8 条手动模板（kind=MANUAL，不扫描不进大满贯）。
     */
    private void migrateBuiltinAchievements() {
        // (a) 4 条可进阶：按 rule_type 定位 UPDATE，内容与新库播种一致，重复执行结果不变
        Object[][] tiered = {
                {"RANK_UP", "考试班级排名提升：🥉3 名 / 🥈5 名 / 🥇10 名",
                        "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":3},{\"tier\":\"SILVER\",\"min\":5},{\"tier\":\"GOLD\",\"min\":10}]}"},
                {"SCORE_FULL", "最近一次考试满分科目：🥉1 科 / 🥈2 科 / 🥇3 科（满分线默认 100 分）",
                        "{\"fullScore\":100,\"tiers\":[{\"tier\":\"BRONZE\",\"min\":1},{\"tier\":\"SILVER\",\"min\":2},{\"tier\":\"GOLD\",\"min\":3}]}"},
                {"DUTY_COUNT", "参与值日劳动：🥉10 次 / 🥈20 次 / 🥇30 次",
                        "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":10},{\"tier\":\"SILVER\",\"min\":20},{\"tier\":\"GOLD\",\"min\":30}]}"},
                {"CONTINUOUS_ATTENDANCE", "连续全勤：🥉4 周 / 🥈8 周 / 🥇整学期（本学期无迟到无缺勤即得🥇）",
                        "{\"weeks\":4,\"tiers\":[{\"tier\":\"BRONZE\",\"min\":4},{\"tier\":\"SILVER\",\"min\":8},{\"tier\":\"GOLD\",\"min\":20}]}"},
        };
        for (Object[] t : tiered) {
            jdbc.update("UPDATE achievement_def SET tiered=1, tier='BRONZE', description=?, rule_params=? " +
                    "WHERE user_id=0 AND rule_type=?", t[1], t[2], t[0]);
        }
        // (b)+(c) 新增内置：按 name 幂等 INSERT。每行：名称, 描述, 图标, 等级, kind, rule_type, rule_paramsJSON, tiered
        Object[][] extras = {
                // 宠物联动 AUTO
                {"萌宠相伴", "已领养班级宠物，有萌宠陪伴成长", "sparkles", "BRONZE", "AUTO",
                        "PET_ADOPTED", "{}", 0},
                {"萌宠成长", "宠物成长到：🥉Lv.3 / 🥈Lv.4 / 🥇Lv.5（毕业后等级重置不降级，保留最高档）", "star", "BRONZE",
                        "AUTO", "PET_LEVEL", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":3},{\"tier\":\"SILVER\",\"min\":4},{\"tier\":\"GOLD\",\"min\":5}]}", 1},
                {"毕业典礼", "本学期宠物毕业次数：🥉1 次 / 🥈2 次 / 🥇3 次", "grad-cap", "BRONZE",
                        "AUTO", "PET_GRADUATE", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":1},{\"tier\":\"SILVER\",\"min\":2},{\"tier\":\"GOLD\",\"min\":3}]}", 1},
                {"小店达人", "本学期小店兑换次数：🥉1 次 / 🥈3 次 / 🥇5 次", "gem", "BRONZE",
                        "AUTO", "PET_SHOP", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":1},{\"tier\":\"SILVER\",\"min\":3},{\"tier\":\"GOLD\",\"min\":5}]}", 1},
                {"成绩前列", "最近一次考试总分排名：🥉前 10 / 🥈前 5 / 🥇前 3（并列同名次）", "medal-1st", "BRONZE",
                        "AUTO", "RANK_TOP", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":10},{\"tier\":\"SILVER\",\"min\":5},{\"tier\":\"GOLD\",\"min\":3}]}", 1},
                // 打怪兽班级模式联动 AUTO
                {"团结就是力量", "打怪兽·班级模式一轮全对：🥉1 次 / 🥈3 次 / 🥇5 次（全班齐心协力一轮击败怪兽）", "sparkles", "BRONZE",
                        "AUTO", "CLASS_UNITY", "{\"tiers\":[{\"tier\":\"BRONZE\",\"min\":1},{\"tier\":\"SILVER\",\"min\":3},{\"tier\":\"GOLD\",\"min\":5}]}", 1},
                // 手动模板 MANUAL（开箱即授，不扫描、不进大满贯）
                {"体育之星", "体育表现突出：校运会/体育课/体育竞赛获奖", "sports-medal", "SILVER",
                        "MANUAL", null, null, 0},
                {"艺术之星", "艺术方面有特长：绘画/书法/舞蹈等表演或获奖", "party", "SILVER",
                        "MANUAL", null, null, 0},
                {"助人为乐", "主动帮助同学、热心服务集体", "glowing-star", "SILVER",
                        "MANUAL", null, null, 0},
                {"科技小达人", "科技/编程/科学实验等方面表现优异", "rocket", "BRONZE",
                        "MANUAL", null, null, 0},
                {"音乐之星", "音乐方面有特长：歌唱/乐器/文艺汇演表现", "bell", "BRONZE",
                        "MANUAL", null, null, 0},
                {"文明礼仪", "言行文明、礼貌待人，文明礼仪标兵", "sparkles", "BRONZE",
                        "MANUAL", null, null, 0},
                {"阅读之星", "坚持课外阅读，阅读量领先", "memo", "BRONZE",
                        "MANUAL", null, null, 0},
                {"演讲达人", "演讲/朗诵/主持表现自信出色", "pencil", "BRONZE",
                        "MANUAL", null, null, 0},
        };
        for (Object[] e : extras) {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM achievement_def WHERE user_id=0 AND name=?", Integer.class, e[0]);
            if (n != null && n > 0) continue;
            jdbc.update("INSERT INTO achievement_def(user_id,name,description,icon,tier,kind,rule_type,rule_params,enabled,builtin,tiered) " +
                            "VALUES(0,?,?,?,?,?,?,?,1,1,?)",
                    e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7]);
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
            // 新库：初始 admin 直接以 ADMIN/启用 播种，密码 123456
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name,role,enabled) " +
                    "VALUES(?,?,?,'ADMIN',1)", "admin", encoder.encode("123456"), "王路飞");
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
