package com.teacher.platform.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动种子数据：默认账号、班级、科目、节次、规则、节假日、卡片顺序、默认设置
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public DataInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        migrate();
        seedUser();
        seedClasses();
        seedSettings();
        seedSubjects();
        seedPeriods();
        seedRules();
        seedHolidays();
        seedCards();
        seedDutyDefaults();
    }

    /** 数据库结构迁移：为已存在的旧库补充新增列（幂等，新库建表已含则跳过） */
    private void migrate() {
        ensureColumn("duty_roster", "task_id", "task_id BIGINT NULL COMMENT '分配的值日事项'");
    }

    /** 若指定表缺少某列则 ALTER 增加；通过 JDBC 元数据判断，保证可重复执行 */
    private void ensureColumn(String table, String column, String columnDef) {
        try {
            boolean exists;
            try (java.sql.Connection conn = jdbc.getDataSource().getConnection();
                 java.sql.ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
                exists = rs.next();
            }
            if (!exists) {
                jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDef);
            }
        } catch (Exception e) {
            // 迁移异常不阻断启动（如新库建表已含该列、或无权限等）
            System.out.println("[migrate] 跳过 " + table + "." + column + "：" + e.getMessage());
        }
    }

    private Integer count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private void seedUser() {
        if (count("SELECT COUNT(*) FROM sys_user") == 0) {
            jdbc.update("INSERT INTO sys_user(username,password_hash,display_name) VALUES(?,?,?)",
                    "admin", encoder.encode("123456"), "王路飞");
        }
    }

    private void seedClasses() {
        if (count("SELECT COUNT(*) FROM class_info") == 0) {
            jdbc.update("INSERT INTO class_info(name,type,term) VALUES(?,?,?)", "初一(1)班", "HOMEROOM", "2026秋季");
            jdbc.update("INSERT INTO class_info(name,type,term) VALUES(?,?,?)", "初一(2)班", "SUBJECT", "2026秋季");
        }
    }

    private void seedSettings() {
        String[][] kv = {
                {"teacher_name", "王路飞"},
                {"term", "2026秋季"},
                {"school_motto", "教练，我想打篮球"},
                {"personal_motto", "我一直都想证明：努力是能够超越天才的！"},
                {"pass_line", "60"},
                {"excellent_line", "85"},
                {"homeroom_class_id", "1"},
        };
        for (String[] p : kv) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM setting WHERE k=?", Integer.class, p[0]);
            if (n == 0) {
                jdbc.update("INSERT INTO setting(k,v) VALUES(?,?)", p[0], p[1]);
            }
        }
    }

    private void seedSubjects() {
        if (count("SELECT COUNT(*) FROM subject") == 0) {
            Object[][] rows = {
                    {"语文", "孙老师", "#f78fb3"},
                    {"数学", "赵老师", "#6fa8f5"},
                    {"英语", "钱老师", "#7ed6a5"},
                    {"科学", "我", "#f0c96e"},
                    {"社会", "李老师", "#b39ddb"},
                    {"美术", "王老师", "#f0939a"},
                    {"体育", "吴老师", "#8fd3d8"},
                    {"音乐", "郑老师", "#c5e384"},
                    {"班会", "我", "#f5b971"},
                    {"自习", "我", "#9fc5f8"},
                    {"信息技术", "王老师", "#82b1e0"},
            };
            for (Object[] r : rows) {
                jdbc.update("INSERT INTO subject(name,teacher,color) VALUES(?,?,?)", r[0], r[1], r[2]);
            }
        }
    }

    private void seedPeriods() {
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

    private void seedRules() {
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

    private void seedHolidays() {
        if (count("SELECT COUNT(*) FROM holiday") == 0) {
            Object[][] rows = {
                    {"中秋节", "2026-09-25"},
                    {"国庆节", "2026-10-01"},
                    {"圣诞节", "2026-12-25"},
                    {"元旦", "2027-01-01"},
            };
            for (Object[] r : rows) {
                jdbc.update("INSERT INTO holiday(name,holiday_date) VALUES(?,?)", r[0], r[1]);
            }
        }
    }

    private void seedCards() {
        if (count("SELECT COUNT(*) FROM card_config") == 0) {
            String[] keys = {"attendance", "timetable", "holiday", "todayCourse", "discipline", "homework"};
            for (int i = 0; i < keys.length; i++) {
                jdbc.update("INSERT INTO card_config(card_key,sort_order,visible) VALUES(?,?,1)", keys[i], i);
            }
        }
    }

    private void seedDutyDefaults() {
        if (count("SELECT COUNT(*) FROM duty_config") == 0) {
            for (int d = 1; d <= 5; d++) {
                jdbc.update("INSERT INTO duty_config(day_of_week,person_count) VALUES(?,4)", d);
            }
        }
        if (count("SELECT COUNT(*) FROM duty_task") == 0) {
            String[] tasks = {"扫地", "擦黑板", "倒垃圾", "擦窗户", "整理讲台"};
            for (String t : tasks) {
                jdbc.update("INSERT INTO duty_task(name) VALUES(?)", t);
            }
        }
    }
}
