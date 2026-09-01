package com.teacher.platform.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 账号级默认数据：新用户注册后为其初始化一套空架子
 * （默认班级、科目、值日事项/人数、节假日、卡片顺序、默认设置），不含学生。
 * 同一账号已有班级数据时不再重复插入，仅补齐缺失的默认设置项。
 */
@Component
public class UserDataSeeder {

    private final JdbcTemplate jdbc;

    public UserDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 为指定账号播种默认数据；若该账号已有班级则仅补齐设置 */
    public void seedIfEmpty(Long uid, String displayName) {
        Integer classCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM class_info WHERE user_id=?", Integer.class, uid);
        if (classCnt != null && classCnt > 0) {
            seedSettings(uid, displayName, null);
            seedPetRules(uid);
            return;
        }

        // 默认班级：一个班主任班 + 一个任课班
        jdbc.update("INSERT INTO class_info(user_id,name,type,term) VALUES(?,?,?,?)",
                uid, "初一(1)班", "HOMEROOM", "2026秋季");
        Long homeId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO class_info(user_id,name,type,term) VALUES(?,?,?,?)",
                uid, "初一(2)班", "SUBJECT", "2026秋季");

        // 科目
        Object[][] subs = {
                {"语文", "孙老师", "#f78fb3"}, {"数学", "赵老师", "#6fa8f5"}, {"英语", "钱老师", "#7ed6a5"},
                {"科学", "我", "#f0c96e"}, {"社会", "李老师", "#b39ddb"}, {"美术", "王老师", "#f0939a"},
                {"体育", "吴老师", "#8fd3d8"}, {"音乐", "郑老师", "#c5e384"}, {"班会", "我", "#f5b971"},
                {"自习", "我", "#9fc5f8"}, {"信息技术", "王老师", "#82b1e0"},
        };
        for (Object[] s : subs) {
            jdbc.update("INSERT INTO subject(user_id,name,teacher,color) VALUES(?,?,?,?)",
                    uid, s[0], s[1], s[2]);
        }

        // 值日事项
        String[] tasks = {"扫地", "擦黑板", "倒垃圾", "擦窗户", "整理讲台"};
        for (String t : tasks) {
            jdbc.update("INSERT INTO duty_task(user_id,name) VALUES(?,?)", uid, t);
        }
        // 每日值日人数（默认 4 人）
        for (int d = 1; d <= 5; d++) {
            jdbc.update("INSERT INTO duty_config(user_id,day_of_week,person_count) VALUES(?,?,4)", uid, d);
        }

        // 节假日
        Object[][] hols = {
                {"中秋节", "2026-09-25"}, {"国庆节", "2026-10-01"},
                {"圣诞节", "2026-12-25"}, {"元旦", "2027-01-01"},
        };
        for (Object[] h : hols) {
            jdbc.update("INSERT INTO holiday(user_id,name,holiday_date) VALUES(?,?,?)", uid, h[0], h[1]);
        }

        // 工作台卡片顺序
        String[] cards = {"attendance", "timetable", "holiday", "todayCourse", "discipline", "homework"};
        for (int i = 0; i < cards.length; i++) {
            jdbc.update("INSERT INTO card_config(user_id,card_key,sort_order,visible) VALUES(?,?,?,1)",
                    uid, cards[i], i);
        }

        seedSettings(uid, displayName, homeId);
        seedPetRules(uid);
    }

    /**
     * 宠物园预置评价项（学习 11 项 + 行为 18 项 + 扣分项 8 个，共 37 项）。
     * 每行：分类(学习/行为)、名称、分值(±)、emoji 图标；已有评价项则不重复播种。
     */
    private void seedPetRules(Long uid) {
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM pet_rule WHERE user_id=?", Integer.class, uid);
        if (cnt != null && cnt > 0) return;
        Object[][] rules = {
                // 学习类（+）
                {"学习", "作业完成优秀", 1, "📒"},
                {"学习", "平时测验满分", 3, "💯"},
                {"学习", "测验达优秀线", 2, "🌟"},
                {"学习", "默写全对", 1, "✍️"},
                {"学习", "订正态度认真", 1, "✏️"},
                {"学习", "优秀作业值得表扬", 1, "🏅"},
                {"学习", "学习状态进步", 1, "📈"},
                {"学习", "被老师点名表扬", 1, "👍"},
                {"学习", "单元测验显著进步", 2, "🚀"},
                {"学习", "早读认真专注", 1, "📖"},
                {"学习", "课前准备充分", 1, "🎒"},
                {"学习", "作业未交", -1, "📮"},
                // 行为类（+）
                {"行为", "升旗安静整齐", 1, "🇨🇳"},
                {"行为", "守纪表现优秀", 2, "🤫"},
                {"行为", "主动帮助同学", 2, "🤝"},
                {"行为", "拾金不昧(一般)", 2, "💰"},
                {"行为", "拾金不昧(贵重)", 5, "💎"},
                {"行为", "帮助生病同学", 3, "🩹"},
                {"行为", "调解同学矛盾", 3, "☯️"},
                {"行为", "好人好事被校表扬", 3, "🏫"},
                {"行为", "志愿服务", 3, "🙋"},
                {"行为", "犯错主动认错", 1, "🙇"},
                {"行为", "认真完成值日", 1, "🧹"},
                {"行为", "主动擦黑板", 1, "📋"},
                {"行为", "整理讲台", 1, "🧴"},
                {"行为", "倒垃圾", 2, "🗑️"},
                {"行为", "座位整洁", 1, "✨"},
                {"行为", "座位周围无垃圾", 1, "🍃"},
                {"行为", "整理图书", 2, "📚"},
                {"行为", "眼保健操认真", 1, "👀"},
                // 行为类（扣分项）
                {"行为", "迟到", -1, "⏰"},
                {"行为", "早退", -1, "🏃"},
                {"行为", "缺勤", -2, "❗"},
                {"行为", "课堂违纪", -2, "📢"},
                {"行为", "吃零食/打闹", -2, "🍭"},
                {"行为", "座位脏乱", -1, "🗑️"},
                {"行为", "忘记值日", -2, "🧹"},
        };
        for (Object[] r : rules) {
            jdbc.update("INSERT INTO pet_rule(user_id,category,name,score,icon,enabled) VALUES(?,?,?,?,?,1)",
                    uid, r[0], r[1], r[2], r[3]);
        }
    }

    /** 写入默认设置；homeId 为 null 时沿用已有的班主任班配置，INSERT IGNORE 避免覆盖用户改动 */
    private void seedSettings(Long uid, String displayName, Long homeId) {
        if (homeId == null) {
            // 老账号：取其班主任班 id 作为默认（若设置里还没有）
            try {
                homeId = jdbc.queryForObject(
                        "SELECT id FROM class_info WHERE user_id=? AND type='HOMEROOM' ORDER BY id LIMIT 1",
                        Long.class, uid);
            } catch (Exception e) {
                homeId = null;
            }
        }
        String teacher = displayName == null || displayName.isBlank() ? "老师" : displayName;
        // INSERT IGNORE：已存在的 (user_id,k) 不动，仅补齐缺失项
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "teacher_name", teacher);
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "term", "2026秋季");
        // 学期起止日期：评语素材/通知单/请假统计的"本学期"区间
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "term_start", "2026-09-01");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "term_end", "2027-01-31");
        // 病假累计预警天数（请假闭环用，默认 20 天）
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "sick_leave_warn_days", "20");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "school_motto", "教练，我想打篮球");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "personal_motto", "努力是能够超越天才的！");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "pass_line", "60");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "excellent_line", "85");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "duty_pointer", "0");
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "duty_round", "0");
        // 宠物园：登记考勤/作业/纪律时自动扣宠物成长值的总开关（默认开）
        jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)", uid, "pet_auto_deduct", "1");
        if (homeId != null) {
            jdbc.update("INSERT IGNORE INTO setting(user_id,k,v) VALUES(?,?,?)",
                    uid, "homeroom_class_id", String.valueOf(homeId));
        }
    }
}
