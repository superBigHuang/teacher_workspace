package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 成绩分析：考试管理、Excel 导入、各科统计、分数段、折线、明细、双考试对比
 */
@RestController
@RequestMapping("/api/score")
public class ScoreController {

    private final JdbcTemplate jdbc;

    public ScoreController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private double passLine() { return getSetting("pass_line", 60); }
    private double excellentLine() { return getSetting("excellent_line", 85); }

    private double getSetting(String k, double def) {
        try {
            return Double.parseDouble(jdbc.queryForObject("SELECT v FROM setting WHERE k=?", String.class, k));
        } catch (Exception e) {
            return def;
        }
    }

    // ---------- 考试 ----------
    @GetMapping("/exams")
    public Result<List<Map<String, Object>>> exams() {
        return Result.ok(jdbc.queryForList(
                "SELECT id,name,exam_date examDate,source_file sourceFile FROM exam ORDER BY exam_date DESC, id DESC"));
    }

    @DeleteMapping("/exam/{id}")
    public Result<Void> deleteExam(@PathVariable Long id) {
        jdbc.update("DELETE FROM score WHERE exam_id=?", id);
        jdbc.update("DELETE FROM exam WHERE id=?", id);
        return Result.ok();
    }

    /** 导入成绩 xlsx：表头 学号 | 姓名 | 科目1 | 科目2 ... */
    @PostMapping("/import")
    public Result<Map<String, Object>> importScore(@RequestParam("file") MultipartFile file,
                                                   @RequestParam String examName,
                                                   @RequestParam(required = false) String examDate) throws Exception {
        jdbc.update("INSERT INTO exam(name,exam_date,source_file) VALUES(?,?,?)",
                examName, examDate == null || examDate.isBlank() ? null : examDate, file.getOriginalFilename());
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
            Long studentId = findStudentId(stuNo, name);
            for (String sub : subjects) {
                int ci = heads.indexOf(sub);
                String val = getString(row.getCell(ci));
                if (val.isBlank()) continue;
                double score;
                try { score = Double.parseDouble(val); } catch (Exception e) { continue; }
                jdbc.update("INSERT INTO score(exam_id,student_id,subject,score,stu_no,stu_name) VALUES(?,?,?,?,?,?)",
                        examId, studentId, sub, score, stuNo, name);
            }
            rows++;
        }
        wb.close();
        return Result.ok(Map.of("examId", examId, "students", rows, "subjects", subjects.size()));
    }

    private Long findStudentId(String stuNo, String name) {
        try {
            if (!stuNo.isBlank()) {
                List<Map<String, Object>> l = jdbc.queryForList("SELECT id FROM student WHERE stu_no=?", stuNo);
                if (!l.isEmpty()) return ((Number) l.get(0).get("id")).longValue();
            }
            if (!name.isBlank()) {
                List<Map<String, Object>> l = jdbc.queryForList("SELECT id FROM student WHERE name=?", name);
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
                "SELECT subject, score FROM score WHERE exam_id=?", examId);
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
        List<Double> scores = jdbc.queryForList("SELECT score FROM score WHERE exam_id=?", examId)
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
                "SELECT stu_name name, score FROM score WHERE exam_id=? AND subject=? ORDER BY score DESC",
                examId, subject);
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
                "SELECT student_id, stu_no, stu_name, subject, score FROM score WHERE exam_id=?", examId);
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
