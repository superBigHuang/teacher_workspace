package com.teacher.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 学期数据聚合服务（评语素材 / 家长通知单 / 成长档案 / 请假统计四处共用）。
 * 按 setting 中 term_start~term_end 划定"本学期"区间，把考勤、请假、作业、纪律、
 * 沟通、职务、值日、最近两次考试排名等散落在 7 张表里的数据聚合成每生一份素材。
 * 全部查询按账号 uid 隔离；批量查询 + 内存聚合，避免 N+1。
 */
@Service
public class TermStatsService {

    private final JdbcTemplate jdbc;

    public TermStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 学期起止日期（setting 缺失时用默认 2026 秋） */
    public String[] termRange(Long uid) {
        String start = setting(uid, "term_start", "2026-09-01");
        String end = setting(uid, "term_end", "2027-01-31");
        return new String[]{start, end};
    }

    /** 班主任班 id：优先 setting，其次 class_info 中 HOMEROOM，兜底 1 */
    public Long homeroomClassId(Long uid) {
        try {
            return Long.valueOf(Objects.requireNonNull(
                    setting(uid, "homeroom_class_id", null)));
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

    /**
     * 全班学期素材。每生字段：
     * late/absent 考勤次数、leaveDays/sickLeaveDays 请假天数、homeworkMissing/lastMissingSubject 作业、
     * discipline 违纪、commCount 沟通、roles 职务、dutyCount 值日参与、
     * exams 最近两次考试{rank,total,rankChange}、rankSegment 位次段。
     */
    public List<Map<String, Object>> materials(Long uid, Long classId) {
        String[] range = termRange(uid);
        String start = range[0], end = range[1];

        // 学生名单
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id studentId, name, stu_no stuNo, gender FROM student " +
                        "WHERE user_id=? AND class_id=? ORDER BY stu_no, id", uid, classId);
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> s : students) {
            Long sid = ((Number) s.get("studentId")).longValue();
            Map<String, Object> m = new LinkedHashMap<>(s);
            m.put("late", 0); m.put("absent", 0);
            m.put("leaveDays", 0.0); m.put("sickLeaveDays", 0.0);
            m.put("homeworkMissing", 0); m.put("lastMissingSubject", "");
            m.put("discipline", 0); m.put("commCount", 0);
            m.put("roles", ""); m.put("dutyCount", 0);
            m.put("rank", null); m.put("rankChange", null); m.put("totalScore", null);
            m.put("rankSegment", ""); m.put("exams", new ArrayList<Map<String, Object>>());
            m.put("achievementCount", 0); m.put("topAchievement", "");
            byId.put(sid, m);
        }
        if (byId.isEmpty()) return new ArrayList<>();

        fillAttendance(uid, start, end, byId);
        fillLeave(uid, start, end, byId);
        fillHomework(uid, start, end, byId);
        fillCount(uid, start, end, byId, "discipline_log", "discipline");
        fillCount(uid, start, end, byId, "comm_log", "commCount");
        fillRoles(uid, byId);
        fillDuty(uid, byId);
        fillExamRanks(uid, start, end, byId);
        fillAchievements(uid, start + "~" + end, byId);
        return new ArrayList<>(byId.values());
    }

    /** 单生素材（供成长档案/单条生成用） */
    public Map<String, Object> material(Long uid, Long studentId) {
        Long classId;
        try {
            classId = jdbc.queryForObject(
                    "SELECT class_id FROM student WHERE id=? AND user_id=?", Long.class, studentId, uid);
        } catch (Exception e) {
            return null;
        }
        return materials(uid, classId).stream()
                .filter(m -> Objects.equals(m.get("studentId"), studentId))
                .findFirst().orElse(null);
    }

    // ---------- 各维度填充 ----------

    /** 考勤：迟到/缺勤次数（请假状态由请假天数单独统计，不重复计） */
    private void fillAttendance(Long uid, String start, String end, Map<Long, Map<String, Object>> byId) {
        jdbc.queryForList(
                "SELECT student_id sid, status, COUNT(*) c FROM attendance " +
                        "WHERE user_id=? AND record_date BETWEEN ? AND ? AND status IN ('迟到','缺勤') GROUP BY student_id, status",
                uid, start, end).forEach(r -> {
            Map<String, Object> m = byId.get(((Number) r.get("sid")).longValue());
            if (m == null) return;
            int c = ((Number) r.get("c")).intValue();
            if ("迟到".equals(r.get("status"))) m.put("late", c);
            else m.put("absent", c);
        });
    }

    /** 请假：学期内按请假单汇总天数（含请假中与已销假），病假单独累计用于预警 */
    private void fillLeave(Long uid, String start, String end, Map<Long, Map<String, Object>> byId) {
        jdbc.queryForList(
                "SELECT student_id sid, leave_type lt, SUM(days) d FROM leave_record " +
                        "WHERE user_id=? AND start_date BETWEEN ? AND ? GROUP BY student_id, leave_type",
                uid, start, end).forEach(r -> {
            Map<String, Object> m = byId.get(((Number) r.get("sid")).longValue());
            if (m == null) return;
            double days = ((Number) r.get("d")).doubleValue();
            m.put("leaveDays", round1((double) m.get("leaveDays") + days));
            if ("病假".equals(r.get("lt"))) m.put("sickLeaveDays", round1(days + (double) m.get("sickLeaveDays")));
        });
    }

    /** 作业：未交次数 + 最近一次未交科目（全量取回，内存取每生最新一条） */
    private void fillHomework(Long uid, String start, String end, Map<Long, Map<String, Object>> byId) {
        Map<Long, Integer> cnt = new HashMap<>();
        jdbc.queryForList(
                "SELECT student_id sid, subject, record_date d FROM homework_check " +
                        "WHERE user_id=? AND status='未交' AND record_date BETWEEN ? AND ? " +
                        "ORDER BY record_date DESC, id DESC",
                uid, start, end).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            cnt.merge(sid, 1, Integer::sum);
            Map<String, Object> m = byId.get(sid);
            // 查询已按日期倒序，每生第一条即最近未交
            if (m != null && ((String) m.get("lastMissingSubject")).isEmpty()) {
                m.put("lastMissingSubject", String.valueOf(r.get("subject")));
            }
        });
        cnt.forEach((sid, c) -> {
            Map<String, Object> m = byId.get(sid);
            if (m != null) m.put("homeworkMissing", c);
        });
    }

    /** 通用计数填充：discipline_log→discipline、comm_log→commCount（均按学期日期过滤） */
    private void fillCount(Long uid, String start, String end, Map<Long, Map<String, Object>> byId,
                           String table, String field) {
        String dateCol = "comm_log".equals(table) ? "contact_date" : "record_date";
        jdbc.queryForList(
                "SELECT student_id sid, COUNT(*) c FROM " + table + " " +
                        "WHERE user_id=? AND " + dateCol + " BETWEEN ? AND ? GROUP BY student_id",
                uid, start, end).forEach(r -> {
            Map<String, Object> m = byId.get(((Number) r.get("sid")).longValue());
            if (m != null) m.put(field, ((Number) r.get("c")).intValue());
        });
    }

    /** 班委职务：多职务逗号拼接 */
    private void fillRoles(Long uid, Map<Long, Map<String, Object>> byId) {
        jdbc.queryForList("SELECT student_id sid, role FROM committee WHERE user_id=?", uid)
                .forEach(r -> {
                    Map<String, Object> m = byId.get(((Number) r.get("sid")).longValue());
                    if (m == null) return;
                    String roles = (String) m.get("roles");
                    String role = String.valueOf(r.get("role"));
                    m.put("roles", roles.isEmpty() ? role : roles + "、" + role);
                });
    }

    /** 值日参与次数（duty_roster 中出现的排数） */
    private void fillDuty(Long uid, Map<Long, Map<String, Object>> byId) {
        jdbc.queryForList(
                "SELECT student_id sid, COUNT(*) c FROM duty_roster WHERE user_id=? GROUP BY student_id", uid)
                .forEach(r -> {
                    Map<String, Object> m = byId.get(((Number) r.get("sid")).longValue());
                    if (m != null) m.put("dutyCount", ((Number) r.get("c")).intValue());
                });
    }

    /**
     * 本学期成就：获得枚数 + 代表成就名（最高等级一枚，白金>金>银>铜），
     * 评语素材"本学期成就 X 枚/代表成就名"用。
     */
    private void fillAchievements(Long uid, String term, Map<Long, Map<String, Object>> byId) {
        Map<Long, List<Object[]>> byStudent = new HashMap<>();
        jdbc.queryForList(
                "SELECT sa.student_id sid, d.name name, " +
                        "CASE d.tier WHEN 'PLATINUM' THEN 4 WHEN 'GOLD' THEN 3 WHEN 'SILVER' THEN 2 ELSE 1 END lv " +
                        "FROM student_achievement sa JOIN achievement_def d ON d.id=sa.def_id " +
                        "WHERE sa.user_id=? AND sa.term=?", uid, term).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            byStudent.computeIfAbsent(sid, k -> new ArrayList<>())
                    .add(new Object[]{r.get("name"), ((Number) r.get("lv")).intValue()});
        });
        byStudent.forEach((sid, list) -> {
            Map<String, Object> m = byId.get(sid);
            if (m == null) return;
            m.put("achievementCount", list.size());
            list.sort((a, b) -> Integer.compare((int) b[1], (int) a[1]));
            m.put("topAchievement", String.valueOf(list.get(0)[0]));
        });
    }

    /**
     * 最近两次考试排名：学期内考试按日期倒序取 2 次（[0]=最新）；
     * 每次考试按总分降序排名，rankChange = 上次名次 - 本次名次（正数=上升）；
     * 位次段：前 10 名 / 中上（前 1/3）/ 中等（前 2/3）/ 待努力。
     */
    private void fillExamRanks(Long uid, String start, String end, Map<Long, Map<String, Object>> byId) {
        List<Map<String, Object>> exams = jdbc.queryForList(
                "SELECT id, name, exam_date examDate FROM exam WHERE user_id=? " +
                        "AND (exam_date IS NULL OR exam_date BETWEEN ? AND ?) " +
                        "ORDER BY exam_date IS NULL, exam_date DESC, id DESC LIMIT 2",
                uid, start, end);
        // 逐考试计算 学生id -> (总分, 名次, 参考人数)
        List<Map<Long, Object[]>> rankMaps = new ArrayList<>();
        for (Map<String, Object> ex : exams) {
            Long examId = ((Number) ex.get("id")).longValue();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT student_id sid, SUM(score) t FROM score " +
                            "WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL GROUP BY student_id",
                    uid, examId);
            rows.sort((a, b) -> Double.compare(
                    ((Number) b.get("t")).doubleValue(), ((Number) a.get("t")).doubleValue()));
            int total = rows.size();
            Map<Long, Object[]> rankMap = new HashMap<>();
            for (int i = 0; i < rows.size(); i++) {
                Long sid = ((Number) rows.get(i).get("sid")).longValue();
                rankMap.put(sid, new Object[]{round1(((Number) rows.get(i).get("t")).doubleValue()), i + 1, total});
            }
            rankMaps.add(rankMap);
        }

        for (Map<String, Object> m : byId.values()) {
            Long sid = ((Number) m.get("studentId")).longValue();
            List<Map<String, Object>> examList = new ArrayList<>();
            Integer latestRank = null, prevRank = null;
            for (int i = 0; i < exams.size(); i++) {
                Map<String, Object> ex = exams.get(i);
                Object[] info = rankMaps.get(i).get(sid);
                if (info == null) continue;
                double total = (double) info[0];
                int rank = (int) info[1];
                int totalStudents = (int) info[2];
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("examId", ((Number) ex.get("id")).longValue());
                em.put("name", ex.get("name"));
                em.put("examDate", String.valueOf(ex.get("examDate")));
                em.put("total", total);
                em.put("rank", rank);
                em.put("totalStudents", totalStudents);
                examList.add(em);
                if (i == 0) { latestRank = rank; m.put("totalScore", total); }
                if (i == 1) prevRank = rank;
            }
            m.put("exams", examList);
            if (latestRank != null) {
                m.put("rank", latestRank);
                if (prevRank != null) m.put("rankChange", prevRank - latestRank);
                int totalStudents = (int) examList.get(0).get("totalStudents");
                m.put("rankSegment", segment(latestRank, totalStudents));
            }
        }
    }

    /** 位次段：前 10 名 → 前10；否则按三等分 中上/中等/待努力 */
    private String segment(int rank, int total) {
        if (rank <= 10) return "前10";
        if (rank * 3 <= total) return "中上";
        if (rank * 3 <= total * 2) return "中等";
        return "待努力";
    }

    /**
     * 成长档案-成绩走势：学期内全部考试（按日期升序）中该生的总分与班级名次。
     * 缺考的考试不出点；每次考试按全班总分降序排名（与成绩明细页口径一致）。
     */
    public List<Map<String, Object>> examTrend(Long uid, String start, String end, Long studentId) {
        List<Map<String, Object>> exams = jdbc.queryForList(
                "SELECT id, name, exam_date examDate FROM exam WHERE user_id=? " +
                        "AND (exam_date IS NULL OR exam_date BETWEEN ? AND ?) ORDER BY exam_date, id",
                uid, start, end);
        List<Map<String, Object>> trend = new ArrayList<>();
        for (Map<String, Object> ex : exams) {
            Long examId = ((Number) ex.get("id")).longValue();
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT student_id sid, SUM(score) t FROM score " +
                            "WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL GROUP BY student_id",
                    uid, examId);
            rows.sort((a, b) -> Double.compare(
                    ((Number) b.get("t")).doubleValue(), ((Number) a.get("t")).doubleValue()));
            for (int i = 0; i < rows.size(); i++) {
                if (!Objects.equals(((Number) rows.get(i).get("sid")).longValue(), studentId)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", ex.get("name"));
                m.put("examDate", String.valueOf(ex.get("examDate")));
                m.put("total", round1(((Number) rows.get(i).get("t")).doubleValue()));
                m.put("rank", i + 1);
                m.put("totalStudents", rows.size());
                trend.add(m);
                break;
            }
        }
        return trend;
    }

    /**
     * 成长档案-时间线：合并考勤异常（迟到/缺勤）、请假单、作业未交、课堂纪律、家校沟通
     * 五类记录，统一 {date, type, emoji, text} 结构，按日期倒序。
     * 考勤中"请假"状态由请假单联动生成，为避免与请假单重复，此处不取。
     */
    public List<Map<String, Object>> timeline(Long uid, String start, String end, Long studentId) {
        List<Map<String, Object>> list = new ArrayList<>();
        // 考勤异常：迟到/缺勤
        jdbc.queryForList(
                "SELECT record_date d, status FROM attendance WHERE user_id=? AND student_id=? " +
                        "AND record_date BETWEEN ? AND ? AND status IN ('迟到','缺勤')",
                uid, studentId, start, end).forEach(r -> {
            String st = String.valueOf(r.get("status"));
            list.add(timelineItem(r.get("d"), "attendance", "迟到".equals(st) ? "⏰" : "❗", st));
        });
        // 请假单（含请假中与已销假，信息比考勤联动行更完整）
        jdbc.queryForList(
                "SELECT start_date s, end_date e, leave_type lt, days, reason FROM leave_record " +
                        "WHERE user_id=? AND student_id=? AND start_date BETWEEN ? AND ?",
                uid, studentId, start, end).forEach(r -> {
            String text = "请假（" + r.get("lt") + "）" + r.get("days") + "天（" + r.get("s") + "~" + r.get("e") + "）"
                    + (r.get("reason") != null ? "：" + r.get("reason") : "");
            list.add(timelineItem(r.get("s"), "leave", "🏖️", text));
        });
        // 作业未交
        jdbc.queryForList(
                "SELECT record_date d, subject FROM homework_check WHERE user_id=? AND student_id=? " +
                        "AND status='未交' AND record_date BETWEEN ? AND ?",
                uid, studentId, start, end).forEach(r ->
                list.add(timelineItem(r.get("d"), "homework", "📚", "作业未交：" + r.get("subject"))));
        // 课堂纪律
        jdbc.queryForList(
                "SELECT record_date d, content FROM discipline_log WHERE user_id=? AND student_id=? " +
                        "AND record_date BETWEEN ? AND ?",
                uid, studentId, start, end).forEach(r ->
                list.add(timelineItem(r.get("d"), "discipline", "📝", String.valueOf(r.get("content")))));
        // 家校沟通
        jdbc.queryForList(
                "SELECT contact_date d, method, content FROM comm_log WHERE user_id=? AND student_id=? " +
                        "AND contact_date BETWEEN ? AND ?",
                uid, studentId, start, end).forEach(r ->
                list.add(timelineItem(r.get("d"), "comm", "💬",
                        "[" + r.get("method") + "] " + r.get("content"))));
        list.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        return list;
    }

    /** 时间线条目构造 */
    private Map<String, Object> timelineItem(Object date, String type, String emoji, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", String.valueOf(date));
        m.put("type", type);
        m.put("emoji", emoji);
        m.put("text", text);
        return m;
    }

    private String setting(Long uid, String k, String def) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null || v.isBlank() ? def : v;
        } catch (Exception e) {
            return def;
        }
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
