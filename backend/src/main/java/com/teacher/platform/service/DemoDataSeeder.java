package com.teacher.platform.service;

import com.teacher.platform.config.datasource.DataSourceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 测试/演示库高保真数据播种与全量重置服务：
 * 物理隔离在 teacher_workspace_demo 库上运行。
 * 包含：50 名学生、排满课表、男女交错座位表、值日轮值、2 次考试对比成绩、待办、沟通记录与宠物。
 */
@Service
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 本地重置互斥锁，防止并发重置冲突 */
    private final ReentrantLock resetLock = new ReentrantLock();

    /** 记录上次重置时间戳（内存缓存 + setting 表双持久化） */
    private volatile long lastResetTime = 0L;

    /** 重置防刷冷却时间：5 分钟 (ms) */
    public static final long COOLDOWN_MS = 5 * 60 * 1000L;

    /** 7 天重置周期 (ms) */
    public static final long RESET_INTERVAL_MS = 7L * 24 * 3600 * 1000L;

    public DemoDataSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 启动时或首次检测：若 demo 库没有学生数据，则自动执行首次灌装 */
    public void seedIfEmpty() {
        DataSourceContext.set(DataSourceContext.DEMO);
        try {
            Integer stuCount = count("SELECT COUNT(*) FROM student");
            if (stuCount == null || stuCount == 0) {
                log.info("【测试库】检测到 teacher_workspace_demo 为空，执行首次全量高保真数据灌装...");
                reset(true);
            } else {
                // 读取已有的上次重置时间
                try {
                    String val = jdbc.queryForObject(
                            "SELECT v FROM setting WHERE user_id=1 AND k='demo_last_reset_at'", String.class);
                    if (val != null && !val.isBlank()) {
                        lastResetTime = LocalDateTime.parse(val, FMT)
                                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("【测试库】seedIfEmpty 检查失败：{}", e.getMessage(), e);
        } finally {
            DataSourceContext.clear();
        }
    }

    /** 获取上次重置时间 */
    public long getLastResetTime() {
        if (lastResetTime == 0L) {
            DataSourceContext.set(DataSourceContext.DEMO);
            try {
                String val = jdbc.queryForObject(
                        "SELECT v FROM setting WHERE user_id=1 AND k='demo_last_reset_at'", String.class);
                if (val != null && !val.isBlank()) {
                    lastResetTime = LocalDateTime.parse(val, FMT)
                            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
            } catch (Exception ignored) {
            } finally {
                DataSourceContext.clear();
            }
        }
        return lastResetTime;
    }

    /**
     * 执行全量重置并灌装演示数据
     * @param force 是否忽略 5 分钟冷却时间
     */
    public boolean reset(boolean force) {
        if (!resetLock.tryLock()) {
            log.warn("【测试库】当前已有重置任务在执行中，跳过本次请求");
            return false;
        }
        try {
            long now = System.currentTimeMillis();
            if (!force && lastResetTime > 0 && (now - lastResetTime) < COOLDOWN_MS) {
                long waitSec = (COOLDOWN_MS - (now - lastResetTime)) / 1000;
                throw new IllegalStateException("测试库刚重置过，请等待 " + waitSec + " 秒后再试");
            }

            DataSourceContext.set(DataSourceContext.DEMO);
            log.info("【测试库】开始清空并重置 teacher_workspace_demo 数据...");

            // 1. 禁用外键检查，安全清空业务表
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            String[] tablesToTruncate = {
                    "boss_hit", "daily_boss", "push_log", "pet_exchange", "pet_log", "pet_badge",
                    "pet", "student_achievement", "comment", "leave_record", "score", "exam",
                    "attendance", "discipline_log", "homework_check", "comm_log", "todo",
                    "duty_roster", "committee", "parent_contact", "quick_note", "seat_assignment",
                    "seat_config", "timetable", "student", "subject", "duty_task", "duty_config",
                    "holiday", "card_config", "quiz_bank", "quiz_category", "class_info", "setting",
                    "sys_user"
            };
            for (String t : tablesToTruncate) {
                try {
                    jdbc.execute("TRUNCATE TABLE " + t);
                } catch (Exception e) {
                    try {
                        jdbc.execute("DELETE FROM " + t);
                    } catch (Exception ignored) {}
                }
            }
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");

            // 2. 播种管理员账号 admin / 123456
            jdbc.update("INSERT INTO sys_user(id,username,password_hash,display_name,role,enabled,source) " +
                            "VALUES(1,?,?,?,'ADMIN',1,'demo')",
                    "admin", encoder.encode("123456"), "王路飞");

            // 3. 基础班级与科目
            jdbc.update("INSERT INTO class_info(id,user_id,name,type,term) VALUES(1,1,?,?,?)",
                    "初一(1)班", "HOMEROOM", "2026秋季");
            jdbc.update("INSERT INTO class_info(id,user_id,name,type,term) VALUES(2,1,?,?,?)",
                    "初一(2)班", "SUBJECT", "2026秋季");

            Object[][] subs = {
                    {"语文", "孙老师", "#f78fb3", 0}, {"数学", "赵老师", "#6fa8f5", 0},
                    {"英语", "钱老师", "#7ed6a5", 0}, {"科学", "我", "#f0c96e", 1},
                    {"社会", "李老师", "#b39ddb", 0}, {"美术", "王老师", "#f0939a", 0},
                    {"体育", "吴老师", "#8fd3d8", 0}, {"音乐", "郑老师", "#c5e384", 0},
                    {"班会", "我", "#f5b971", 1}, {"自习", "我", "#9fc5f8", 1},
                    {"信息技术", "王老师", "#82b1e0", 0}
            };
            for (Object[] s : subs) {
                jdbc.update("INSERT INTO subject(user_id,name,teacher,color,is_mine) VALUES(1,?,?,?,?)",
                        s[0], s[1], s[2], s[3]);
            }

            // 4. 值日与卡片
            String[] tasks = {"扫地", "擦黑板", "倒垃圾", "擦窗户", "整理讲台"};
            for (String t : tasks) {
                jdbc.update("INSERT INTO duty_task(user_id,name) VALUES(1,?)", t);
            }
            for (int d = 1; d <= 5; d++) {
                jdbc.update("INSERT INTO duty_config(user_id,day_of_week,person_count) VALUES(1,?,4)", d);
            }
            Object[][] hols = {
                    {"中秋节", "2026-09-25"}, {"国庆节", "2026-10-01"},
                    {"圣诞节", "2026-12-25"}, {"元旦", "2027-01-01"}
            };
            for (Object[] h : hols) {
                jdbc.update("INSERT INTO holiday(user_id,name,holiday_date) VALUES(1,?,?)", h[0], h[1]);
            }
            String[] cards = {"attendance", "timetable", "holiday", "todayCourse", "discipline", "homework"};
            for (int i = 0; i < cards.length; i++) {
                jdbc.update("INSERT INTO card_config(user_id,card_key,sort_order,visible) VALUES(1,?,?,1)", cards[i], i);
            }

            // 5. 设置项
            String nowStr = LocalDateTime.now().format(FMT);
            Map<String, String> settings = Map.ofEntries(
                    Map.entry("teacher_name", "王老师"),
                    Map.entry("term", "2026秋季"),
                    Map.entry("term_start", "2026-09-01"),
                    Map.entry("term_end", "2027-01-31"),
                    Map.entry("sick_leave_warn_days", "20"),
                    Map.entry("school_motto", "教练，我想打篮球"),
                    Map.entry("personal_motto", "努力是能够超越天才的！"),
                    Map.entry("pass_line", "60"),
                    Map.entry("excellent_line", "85"),
                    Map.entry("duty_pointer", "0"),
                    Map.entry("duty_round", "1"),
                    Map.entry("pet_auto_deduct", "1"),
                    Map.entry("homeroom_class_id", "1"),
                    Map.entry("demo_last_reset_at", nowStr)
            );
            for (var entry : settings.entrySet()) {
                jdbc.update("INSERT INTO setting(user_id,k,v) VALUES(1,?,?)", entry.getKey(), entry.getValue());
            }

            // 6. 灌入 50 名学生（海贼王人物）
            String[] boys = {"路飞","索隆","山治","乌索普","乔巴","弗兰奇","布鲁克","甚平","艾斯","萨博","龙","卡普","战国","黑胡子","红发","巴基","鹰眼","明哥","罗","基德","霍金斯","阿普","贝基","克力架","斯慕吉"};
            String[] girls = {"娜美","罗宾","女帝","佩罗娜","薇薇","白星","蕾贝卡","紫罗兰","baby5","莫奈","达斯琪","缇娜","加尔默罗","雪莉","布琳","戚风","罗拉","玛琪诺","可雅","古伊娜","诺琪高","贝尔梅尔","柯妮丝","谢丽","加洛特"};
            String[] places = {"海上餐厅巴拉蒂", "最终之岛", "和之国", "水之都Water7", "女儿岛亚马逊百合", "空岛Skypiea"};

            List<Long> studentIds = new ArrayList<>();
            int idx = 0;
            for (String b : boys) {
                studentIds.add(insertStudent(++idx, b, "男", places[idx % places.length]));
            }
            for (String g : girls) {
                studentIds.add(insertStudent(++idx, g, "女", places[idx % places.length]));
            }

            // 7. 周课表（初一(1)班，周一~周五填满节次，循环科目）
            List<Map<String, Object>> periodList = jdbc.queryForList(
                    "SELECT id, name, is_break FROM period ORDER BY sort ASC");
            List<Map<String, Object>> subjList = jdbc.queryForList(
                    "SELECT id, name FROM subject WHERE user_id=1");
            if (!periodList.isEmpty() && !subjList.isEmpty()) {
                List<Map<String, Object>> teachPeriods = periodList.stream()
                        .filter(p -> ((Number) p.get("is_break")).intValue() == 0).toList();
                List<Map<String, Object>> teached = subjList.stream()
                        .filter(s -> !"自习".equals(s.get("name"))).toList();
                Map<String, Object> studySubj = subjList.stream()
                        .filter(s -> "自习".equals(s.get("name"))).findFirst().orElse(subjList.get(0));

                int si = 0;
                for (int day = 1; day <= 5; day++) {
                    for (Map<String, Object> p : teachPeriods) {
                        Long pId = ((Number) p.get("id")).longValue();
                        String pName = String.valueOf(p.get("name"));
                        Long subId;
                        if (pName.contains("自习")) {
                            subId = ((Number) studySubj.get("id")).longValue();
                        } else {
                            subId = ((Number) teached.get(si++ % teached.size()).get("id")).longValue();
                        }
                        jdbc.update("INSERT INTO timetable(user_id,class_id,day_of_week,period_id,subject_id) " +
                                "VALUES(1,1,?,?,?)", day, pId, subId);
                    }
                }
            }

            // 8. 自动排座（7行8列，50 人填入）
            jdbc.update("INSERT INTO seat_config(user_id,class_id,`rows`,cols,aisles) VALUES(1,1,7,8,'[2,4,6]')");
            int sIdx = 0;
            for (int r = 1; r <= 7 && sIdx < studentIds.size(); r++) {
                for (int c = 1; c <= 8 && sIdx < studentIds.size(); c++) {
                    jdbc.update("INSERT INTO seat_assignment(user_id,class_id,row_no,col_no,student_id) " +
                            "VALUES(1,1,?,?,?)", r, c, studentIds.get(sIdx++));
                }
            }

            // 9. 自动轮值（周一至周五，每天 4 人，轮换分配值日事项）
            List<Map<String, Object>> taskList = jdbc.queryForList("SELECT id FROM duty_task WHERE user_id=1 ORDER BY id ASC");
            int dutyStuIdx = 0;
            for (int day = 1; day <= 5; day++) {
                for (int p = 0; p < 4; p++) {
                    Long sId = studentIds.get(dutyStuIdx % studentIds.size());
                    dutyStuIdx++;
                    Long tId = taskList.isEmpty() ? null : ((Number) taskList.get(p % taskList.size()).get("id")).longValue();
                    jdbc.update("INSERT INTO duty_roster(user_id,day_of_week,student_id,task_id,round) VALUES(1,?,?,?,1)",
                            day, sId, tId);
                }
            }

            // 10. 示例待办与沟通记录
            jdbc.update("INSERT INTO todo(user_id,title,category,due_date,done,source) " +
                    "VALUES(1,'周三班会准备诚信主题PPT','班级','2026-09-02',0,'MANUAL')");
            jdbc.update("INSERT INTO todo(user_id,title,category,due_date,done,source) " +
                    "VALUES(1,'催交数学作业未交名单','教学','2026-09-01',1,'MANUAL')");

            jdbc.update("INSERT INTO comm_log(user_id,student_id,contact_date,method,content) " +
                    "VALUES(1,?, '2026-08-26', '电话', '上课吃零食，已与家长沟通督促')", studentIds.get(0));
            jdbc.update("INSERT INTO comm_log(user_id,student_id,contact_date,method,content) " +
                    "VALUES(1,?, '2026-08-26', '微信', '近期早读表现优异，向家长报喜')", studentIds.get(1));

            // 11. 两次考试与成绩（开学摸底、第一次月考，50 人 9 门科目分数，带进退步对比）
            seedExamsAndScores(studentIds);

            // 12. 为前 15 名学生领养宠物（Lv.1~Lv.3），使宠物园/座次表立即可见
            seedDemoPets(studentIds);

            lastResetTime = now;
            log.info("【测试库】teacher_workspace_demo 全量高保真数据重置完成！耗时: {} ms", System.currentTimeMillis() - now);
            return true;
        } finally {
            DataSourceContext.clear();
            resetLock.unlock();
        }
    }

    private Long insertStudent(int i, String name, String gender, String address) {
        String stuNo = "2024" + String.format("%03d", i);
        jdbc.update("INSERT INTO student(user_id,stu_no,name,gender,class_id,address) VALUES(1,?,?,?,1,?)",
                stuNo, name, gender, address);
        Long sid = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 家长信息
        jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(1,?,? ,?,?)",
                sid, "父亲", name + "之父", "138" + String.format("%08d", i));
        jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(1,?,? ,?,?)",
                sid, "母亲", name + "之母", "139" + String.format("%08d", i));
        return sid;
    }

    private void seedExamsAndScores(List<Long> studentIds) {
        String[] subjects = {"语文", "数学", "英语", "物理", "化学", "生物", "历史", "地理", "政治"};

        // 考试 1：开学摸底
        jdbc.update("INSERT INTO exam(user_id,name,exam_date) VALUES(1, '开学摸底测试', '2026-08-20')");
        Long exam1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // 考试 2：第一次月考
        jdbc.update("INSERT INTO exam(user_id,name,exam_date) VALUES(1, '第一次月考', '2026-09-28')");
        Long exam2 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Random rand = new Random(42); // 固定随机种子保证体验数据稳定性
        for (int i = 0; i < studentIds.size(); i++) {
            Long sid = studentIds.get(i);
            String stuNo = "2024" + String.format("%03d", i + 1);
            String stuName = jdbc.queryForObject("SELECT name FROM student WHERE id=?", String.class, sid);

            for (String sub : subjects) {
                // 考试 1 基础分 58~92
                int baseScore = 58 + rand.nextInt(35);
                jdbc.update("INSERT INTO score(user_id,exam_id,student_id,subject,score,stu_no,stu_name) " +
                        "VALUES(1,?,?,?,?,?,?)", exam1, sid, sub, baseScore, stuNo, stuName);

                // 考试 2 普遍提升 4~10 分，个别学科有浮动
                int lift = 3 + rand.nextInt(8) + (rand.nextBoolean() ? 2 : -2);
                int score2 = Math.min(100, Math.max(45, baseScore + lift));
                jdbc.update("INSERT INTO score(user_id,exam_id,student_id,subject,score,stu_no,stu_name) " +
                        "VALUES(1,?,?,?,?,?,?)", exam2, sid, sub, score2, stuNo, stuName);
            }
        }
    }

    private void seedDemoPets(List<Long> studentIds) {
        String[] petNames = {"小火龙", "泡泡龙", "森之鹿", "踏雪豹", "金鳞兽", "月影兔", "烈风鹰", "青羽雀", "雷电汪", "玄武兽"};
        int count = Math.min(15, studentIds.size());
        for (int i = 0; i < count; i++) {
            Long sid = studentIds.get(i);
            int lv = 1 + (i % 3);
            int exp = lv * 40 + i * 5;
            String pname = petNames[i % petNames.length];
            jdbc.update("INSERT INTO pet(user_id,student_id,pet_key,pet_name,level,exp,adopted_at,graduated_count,hatched) " +
                    "VALUES(1,?,'dragon',?,?,?,NOW(),0,1)", sid, pname, lv, exp);
        }
    }

    private Integer count(String sql) {
        try {
            return jdbc.queryForObject(sql, Integer.class);
        } catch (Exception e) {
            return 0;
        }
    }
}
