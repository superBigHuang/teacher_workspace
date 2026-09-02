package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.AchievementScanner;
import com.teacher.platform.service.TermStatsService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 成绩分析：考试管理、Excel 导入、各科统计、分数段、折线、明细、双考试对比、
 * 家长通知单数据包（按账号隔离）
 */
@RestController
@RequestMapping("/api/score")
public class ScoreController extends BaseController {

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;
    private final AchievementScanner scanner;

    public ScoreController(JdbcTemplate jdbc, TermStatsService termStats, AchievementScanner scanner) {
        this.jdbc = jdbc;
        this.termStats = termStats;
        this.scanner = scanner;
    }

    private double passLine() { return getSetting("pass_line", 60); }
    private double excellentLine() { return getSetting("excellent_line", 85); }

    private double getSetting(String k, double def) {
        try {
            return Double.parseDouble(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid(), k));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- 考试 ----------
    @GetMapping("/exams")
    public Result<List<Map<String, Object>>> exams() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name,exam_date examDate,source_file sourceFile FROM exam WHERE user_id=? ORDER BY exam_date DESC, id DESC",
                uid()));
    }

    @DeleteMapping("/exam/{id}")
    public Result<Void> deleteExam(@PathVariable Long id) {
        Long uid = uid();
        jdbc.update("DELETE FROM score WHERE user_id=? AND exam_id=?", uid, id);
        jdbc.update("DELETE FROM exam WHERE id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    /** 导入成绩 xlsx：表头 学号 | 姓名 | 科目1 | 科目2 ...；学生匹配先姓名后学号（见 findStudentId） */
    @PostMapping("/import")
    public Result<Map<String, Object>> importScore(@RequestParam("file") MultipartFile file,
                                                   @RequestParam String examName,
                                                   @RequestParam(required = false) String examDate) throws Exception {
        Long uid = uid();
        jdbc.update("INSERT INTO exam(user_id,name,exam_date,source_file) VALUES(?,?,?,?)",
                uid, examName, examDate == null || examDate.isBlank() ? null : examDate, file.getOriginalFilename());
        Long examId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        // WorkbookFactory 自动识别 .xls/.xlsx
        Workbook wb = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = wb.getSheetAt(0);
        Row header = sheet.getRow(0);
        List<String> heads = new ArrayList<>();
        for (Cell c : header) heads.add(getString(c).trim());
        int stuNoCol = heads.indexOf("学号");
        int nameCol = heads.indexOf("姓名");
        List<String> subjects = new ArrayList<>();
        for (int i = 0; i < heads.size(); i++) {
            if (i != stuNoCol && i != nameCol && !heads.get(i).isBlank()) subjects.add(heads.get(i));
        }
        int rows = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String stuNo = stuNoCol >= 0 ? getString(row.getCell(stuNoCol)) : "";
            String name = nameCol >= 0 ? getString(row.getCell(nameCol)) : "";
            if (stuNo.isBlank() && name.isBlank()) continue;
            Long studentId = findStudentId(uid, stuNo, name);
            for (String sub : subjects) {
                int ci = heads.indexOf(sub);
                String val = getString(row.getCell(ci));
                if (val.isBlank()) continue;
                double score;
                try { score = Double.parseDouble(val); } catch (Exception e) { continue; }
                jdbc.update("INSERT INTO score(user_id,exam_id,student_id,subject,score,stu_no,stu_name) VALUES(?,?,?,?,?,?,?)",
                        uid, examId, studentId, sub, score, stuNo, name);
            }
            rows++;
        }
        wb.close();
        // 成绩导入后触发一次成就自动扫描（全勤/进步/全科优秀等），失败不阻断导入
        List<Map<String, Object>> newAwards = new ArrayList<>();
        try {
            newAwards = scanner.scan(uid);
        } catch (Exception ignored) {
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("examId", examId);
        result.put("students", rows);
        result.put("subjects", subjects.size());
        result.put("newAwards", newAwards);
        return Result.ok(result);
    }

    /**
     * 成绩行匹配在籍学生：先按姓名（成绩表学号常为考号/格式不一，姓名通常准确）——
     * 姓名唯一命中即用；重名时用学号在同名候选中区分；姓名匹配不上再按学号兜底。
     * 都匹配不上返回 null（成绩仍落库，按姓名归组显示，只是不进在籍学生统计）。
     */
    private Long findStudentId(Long uid, String stuNo, String name) {
        try {
            if (!name.isBlank()) {
                List<Map<String, Object>> byName = jdbc.queryForList(
                        "SELECT id FROM student WHERE user_id=? AND name=?", uid, name);
                if (byName.size() == 1) return ((Number) byName.get(0).get("id")).longValue();
                if (byName.size() > 1 && !stuNo.isBlank()) {
                    List<Map<String, Object>> both = jdbc.queryForList(
                            "SELECT id FROM student WHERE user_id=? AND name=? AND stu_no=?", uid, name, stuNo);
                    if (!both.isEmpty()) return ((Number) both.get(0).get("id")).longValue();
                }
            }
            if (!stuNo.isBlank()) {
                List<Map<String, Object>> l = jdbc.queryForList(
                        "SELECT id FROM student WHERE user_id=? AND stu_no=?", uid, stuNo);
                if (!l.isEmpty()) return ((Number) l.get(0).get("id")).longValue();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ---------- 统计 ----------
    /** 各科统计卡 */
    @GetMapping("/subject-stats")
    public Result<List<Map<String, Object>>> subjectStats(@RequestParam Long examId) {
        List<Map<String, Object>> scores = jdbc.queryForList(
                "SELECT subject, score FROM score WHERE user_id=? AND exam_id=?", uid(), examId);
        Map<String, List<Double>> bySub = new LinkedHashMap<>();
        for (Map<String, Object> s : scores) {
            bySub.computeIfAbsent((String) s.get("subject"), k -> new ArrayList<>())
                    .add(((Number) s.get("score")).doubleValue());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, List<Double>> e : bySub.entrySet()) {
            List<Double> vals = e.getValue();
            DoubleSummaryStatistics st = vals.stream().mapToDouble(Double::doubleValue).summaryStatistics();
            long pass = vals.stream().filter(v -> v >= passLine()).count();
            long exc = vals.stream().filter(v -> v >= excellentLine()).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("subject", e.getKey());
            m.put("avg", round(st.getAverage()));
            m.put("max", round(st.getMax()));
            m.put("min", round(st.getMin()));
            m.put("passRate", Math.round(pass * 1000.0 / vals.size()) / 10.0);
            m.put("excellentRate", Math.round(exc * 1000.0 / vals.size()) / 10.0);
            m.put("count", vals.size());
            result.add(m);
        }
        return Result.ok(result);
    }

    /** 分数段分布（全部科目合并） */
    @GetMapping("/distribution")
    public Result<Map<String, Object>> distribution(@RequestParam Long examId) {
        List<Double> scores = jdbc.queryForList(
                "SELECT score FROM score WHERE user_id=? AND exam_id=?", uid(), examId)
                .stream().map(m -> ((Number) m.get("score")).doubleValue()).toList();
        int[] buckets = new int[5]; // <60, 60-69, 70-79, 80-89, >=90
        for (double v : scores) {
            if (v < 60) buckets[0]++;
            else if (v < 70) buckets[1]++;
            else if (v < 80) buckets[2]++;
            else if (v < 90) buckets[3]++;
            else buckets[4]++;
        }
        return Result.ok(Map.of("labels", List.of("0-59", "60-69", "70-79", "80-89", "90-100"),
                "counts", Arrays.stream(buckets).boxed().toList()));
    }

    /** 单科折线：学生按分数降序 + 平均线 */
    @GetMapping("/line")
    public Result<Map<String, Object>> line(@RequestParam Long examId, @RequestParam String subject) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT stu_name name, score FROM score WHERE user_id=? AND exam_id=? AND subject=? ORDER BY score DESC",
                uid(), examId, subject);
        List<String> names = rows.stream().map(r -> (String) r.get("name")).toList();
        List<Double> vals = rows.stream().map(r -> round(((Number) r.get("score")).doubleValue())).toList();
        DoubleSummaryStatistics st = vals.stream().mapToDouble(Double::doubleValue).summaryStatistics();
        double avg = round(st.getAverage());
        long above = vals.stream().filter(v -> v >= avg).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("avg", avg);
        stats.put("max", round(st.getMax()));
        stats.put("min", round(st.getMin()));
        stats.put("above", above);
        stats.put("below", vals.size() - above);
        return Result.ok(Map.of("names", names, "scores", vals, "avg", avg, "stats", stats));
    }

    /** 成绩明细：排名/学号/姓名/各科/总分/平均分 */
    @GetMapping("/detail")
    public Result<Map<String, Object>> detail(@RequestParam Long examId) {
        List<Map<String, Object>> scores = jdbc.queryForList(
                "SELECT student_id, stu_no, stu_name, subject, score FROM score WHERE user_id=? AND exam_id=?",
                uid(), examId);
        // 科目顺序按首次出现
        List<String> subjects = new ArrayList<>();
        Map<String, Map<String, Double>> byStudent = new LinkedHashMap<>();
        Map<String, String> stuNoMap = new HashMap<>();
        for (Map<String, Object> s : scores) {
            String key = s.get("student_id") != null ? String.valueOf(s.get("student_id")) : ("N" + s.get("stu_name"));
            String sub = (String) s.get("subject");
            if (!subjects.contains(sub)) subjects.add(sub);
            byStudent.computeIfAbsent(key, k -> new LinkedHashMap<>())
                    .put(sub, ((Number) s.get("score")).doubleValue());
            stuNoMap.put(key, s.get("stu_no") == null ? "" : String.valueOf(s.get("stu_no")));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : byStudent.entrySet()) {
            Map<String, Double> sm = e.getValue();
            double total = sm.values().stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", e.getKey());
            row.put("stuNo", stuNoMap.get(e.getKey()));
            row.put("name", scores.stream().filter(s ->
                    (s.get("student_id") != null ? String.valueOf(s.get("student_id")) : "N" + s.get("stu_name"))
                            .equals(e.getKey())).findFirst().map(m -> m.get("stu_name")).orElse(""));
            row.put("scores", sm);
            row.put("total", round(total));
            row.put("avg", round(total / sm.size()));
            rows.add(row);
        }
        rows.sort((a, b) -> Double.compare((Double) b.get("total"), (Double) a.get("total")));
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);
        return Result.ok(Map.of("subjects", subjects, "rows", rows));
    }

    /** 双考试对比：examA(本次) - examB(对比)，逐生各科分差、总分变化、排名变化 */
    @GetMapping("/compare")
    public Result<Map<String, Object>> compare(@RequestParam Long examA, @RequestParam Long examB) {
        Map<String, Object> detailA = detail(examA).getData();
        Map<String, Object> detailB = detail(examB).getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsA = (List<Map<String, Object>>) detailA.get("rows");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rowsB = (List<Map<String, Object>>) detailB.get("rows");
        @SuppressWarnings("unchecked")
        List<String> subjects = (List<String>) detailA.get("subjects");

        Map<String, Map<String, Object>> mapB = new HashMap<>();
        for (Map<String, Object> r : rowsB) mapB.put(studentKey(r), r);

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> a : rowsA) {
            String key = studentKey(a);
            Map<String, Object> b = mapB.get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stuNo", a.get("stuNo"));
            row.put("name", a.get("name"));
            @SuppressWarnings("unchecked")
            Map<String, Double> sa = (Map<String, Double>) a.get("scores");
            Map<String, Double> diffs = new LinkedHashMap<>();
            double totalDiff = 0;
            if (b != null) {
                @SuppressWarnings("unchecked")
                Map<String, Double> sb = (Map<String, Double>) b.get("scores");
                for (String sub : subjects) {
                    Double va = sa.get(sub);
                    Double vb = sb.get(sub);
                    if (va != null && vb != null) {
                        double d = round(va - vb);
                        diffs.put(sub, d);
                        totalDiff += d;
                    }
                }
            }
            row.put("diffs", diffs);
            row.put("totalA", a.get("total"));
            row.put("totalDiff", round(totalDiff));
            int rankA = (int) a.get("rank");
            row.put("rankA", rankA);
            if (b != null) {
                int rankB = (int) b.get("rank");
                row.put("rankB", rankB);
                row.put("rankChange", rankB - rankA); // 正数=名次上升
            } else {
                row.put("rankB", null);
                row.put("rankChange", null);
            }
            result.add(row);
        }
        result.sort((x, y) -> {
            Integer rx = (Integer) x.get("rankChange");
            Integer ry = (Integer) y.get("rankChange");
            if (rx == null) rx = -999;
            if (ry == null) ry = -999;
            return Integer.compare(ry, rx);
        });
        return Result.ok(Map.of("subjects", subjects, "rows", result));
    }

    /**
     * 家长成绩通知单数据包（GET /score/report?examId=&compareExamId=）。
     * 以班主任班在籍学生为主表（缺考学生也出页，成绩列显示"—"）；
     * 每生含各科 {score,classAvg,classMax,grade(A≥优秀线/B≥及格线/C)}、总分/排名/对比排名变化、
     * 本学期日常表现（迟到/请假天数/作业未交/违纪，复用 TermStatsService）、统一寄语兜底。
     * AI 逐人寄语由前端并发调 /ai/generate 后覆盖 comment 字段。
     */
    @GetMapping("/report")
    public Result<Map<String, Object>> report(@RequestParam Long examId,
                                              @RequestParam(required = false) Long compareExamId) {
        Long uid = uid();
        Map<String, Object> data = new LinkedHashMap<>();

        // 抬头：学校/教师/学期/班级/考试信息/等级线/统一寄语
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT display_name displayName, school FROM sys_user WHERE id=?", uid);
        Long classId = termStats.homeroomClassId(uid);
        String className = "";
        try {
            className = jdbc.queryForObject("SELECT name FROM class_info WHERE id=?", String.class, classId);
        } catch (Exception ignored) {}
        Map<String, Object> exam;
        try {
            exam = jdbc.queryForMap("SELECT name, exam_date examDate FROM exam WHERE id=? AND user_id=?", examId, uid);
        } catch (Exception e) {
            return Result.fail("考试不存在");
        }
        data.put("school", str(user.get("school")));
        data.put("teacherName", firstNonBlank(settingStr(uid, "teacher_name"), str(user.get("displayName"))));
        data.put("term", settingStr(uid, "term"));
        data.put("className", className == null ? "" : className);
        data.put("examName", exam.get("name"));
        data.put("examDate", String.valueOf(exam.get("examDate")));
        data.put("passLine", passLine());
        data.put("excellentLine", excellentLine());
        data.put("reportMessage", settingStr(uid, "report_message"));

        // 本次与对比考试聚合
        Map<String, Object> agg = aggregateExam(uid, examId);
        @SuppressWarnings("unchecked")
        List<String> subjects = (List<String>) agg.get("subjects");
        @SuppressWarnings("unchecked")
        Map<Long, Map<String, Double>> stuScores = (Map<Long, Map<String, Double>>) agg.get("stuScores");
        @SuppressWarnings("unchecked")
        Map<String, double[]> subStat = (Map<String, double[]>) agg.get("subStat"); // [avg, max]
        @SuppressWarnings("unchecked")
        Map<Long, Integer> rankMap = (Map<Long, Integer>) agg.get("rankMap");
        @SuppressWarnings("unchecked")
        Map<Long, Double> totalMap = (Map<Long, Double>) agg.get("totalMap");
        int totalStudents = (int) agg.get("totalStudents");
        Map<Long, Integer> cmpRank = null;
        if (compareExamId != null) {
            @SuppressWarnings("unchecked")
            Map<Long, Integer> m = (Map<Long, Integer>) aggregateExam(uid, compareExamId).get("rankMap");
            cmpRank = m;
        }
        data.put("compareExamId", compareExamId);

        // 学生列表：以全班在籍学生为主表，成绩按 student_id 关联
        List<Map<String, Object>> students = new ArrayList<>();
        for (Map<String, Object> m : termStats.materials(uid, classId)) {
            Long sid = ((Number) m.get("studentId")).longValue();
            Map<String, Object> stu = new LinkedHashMap<>();
            stu.put("studentId", sid);
            stu.put("stuNo", m.get("stuNo"));
            stu.put("name", m.get("name"));
            List<Map<String, Object>> subjList = new ArrayList<>();
            Map<String, Double> ss = stuScores.getOrDefault(sid, Collections.emptyMap());
            for (String sub : subjects) {
                Double sc = ss.get(sub);
                double[] st = subStat.getOrDefault(sub, new double[]{0, 0});
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("subject", sub);
                sm.put("score", sc == null ? null : round(sc));
                sm.put("classAvg", round(st[0]));
                sm.put("classMax", round(st[1]));
                sm.put("grade", gradeOf(sc));
                subjList.add(sm);
            }
            stu.put("subjects", subjList);
            stu.put("total", totalMap.get(sid) == null ? null : round(totalMap.get(sid)));
            stu.put("rank", rankMap.get(sid));
            stu.put("totalStudents", totalStudents);
            // 排名变化：对比考试名次 - 本次名次（正数=上升）；任一缺考为 null
            Integer cur = rankMap.get(sid);
            Integer prev = cmpRank == null ? null : cmpRank.get(sid);
            stu.put("rankChange", (cur != null && prev != null) ? prev - cur : null);
            // 日常表现（本学期，与评语素材同口径）
            Map<String, Object> att = new LinkedHashMap<>();
            att.put("late", m.get("late"));
            att.put("leaveDays", m.get("leaveDays"));
            stu.put("attendance", att);
            stu.put("homeworkMissing", m.get("homeworkMissing"));
            stu.put("disciplineCount", m.get("discipline"));
            stu.put("comment", settingStr(uid, "report_message")); // 统一寄语兜底，前端可用 AI 寄语覆盖
            students.add(stu);
        }
        data.put("students", students);
        return Result.ok(data);
    }

    /**
     * 单次考试聚合：科目顺序、每生各科分数、各科[均分,最高]、总分排名（SUM(score) 降序，与明细页同口径）。
     */
    private Map<String, Object> aggregateExam(Long uid, Long examId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT student_id sid, subject, score FROM score WHERE user_id=? AND exam_id=? AND student_id IS NOT NULL",
                uid, examId);
        List<String> subjects = new ArrayList<>();
        Map<Long, Map<String, Double>> stuScores = new HashMap<>();
        Map<String, List<Double>> subVals = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Long sid = ((Number) r.get("sid")).longValue();
            String sub = String.valueOf(r.get("subject"));
            double sc = ((Number) r.get("score")).doubleValue();
            if (!subjects.contains(sub)) subjects.add(sub);
            stuScores.computeIfAbsent(sid, k -> new LinkedHashMap<>()).put(sub, sc);
            subVals.computeIfAbsent(sub, k -> new ArrayList<>()).add(sc);
        }
        Map<String, double[]> subStat = new LinkedHashMap<>();
        subVals.forEach((sub, vals) -> {
            double avg = vals.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            subStat.put(sub, new double[]{avg, max});
        });
        // 总分降序排名
        List<Map.Entry<Long, Double>> totals = new ArrayList<>();
        stuScores.forEach((sid, sm) ->
                totals.add(Map.entry(sid, sm.values().stream().mapToDouble(Double::doubleValue).sum())));
        totals.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Map<Long, Integer> rankMap = new HashMap<>();
        Map<Long, Double> totalMap = new HashMap<>();
        for (int i = 0; i < totals.size(); i++) {
            rankMap.put(totals.get(i).getKey(), i + 1);
            totalMap.put(totals.get(i).getKey(), totals.get(i).getValue());
        }
        Map<String, Object> res = new HashMap<>();
        res.put("subjects", subjects);
        res.put("stuScores", stuScores);
        res.put("subStat", subStat);
        res.put("rankMap", rankMap);
        res.put("totalMap", totalMap);
        res.put("totalStudents", totals.size());
        return res;
    }

    /** 等级：A≥优秀线 / B≥及格线 / C<及格线 / 缺考"—" */
    private String gradeOf(Double sc) {
        if (sc == null) return "—";
        if (sc >= excellentLine()) return "A";
        if (sc >= passLine()) return "B";
        return "C";
    }

    private String settingStr(Long uid, String k) {
        try {
            String v = jdbc.queryForObject("SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : (b == null ? "" : b);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    @SuppressWarnings("unchecked")
    private String studentKey(Map<String, Object> row) {
        String stuNo = (String) row.get("stuNo");
        if (stuNo != null && !stuNo.isBlank()) return "S" + stuNo;
        return "N" + row.get("name");
    }

    private String getString(Cell c) {
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue().trim();
            case NUMERIC -> {
                double d = c.getNumericCellValue();
                if (d == Math.floor(d)) yield String.valueOf((long) d);
                yield String.valueOf(d);
            }
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
