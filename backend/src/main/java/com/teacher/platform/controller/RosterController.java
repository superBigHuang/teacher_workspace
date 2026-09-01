package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 花名册：学生与家长信息 CRUD、统计、Excel 导入导出、学生档案聚合。
 * 数据按登录账号（user_id）隔离。
 */
@RestController
@RequestMapping("/api/roster")
public class RosterController extends BaseController {

    private final JdbcTemplate jdbc;

    public RosterController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当前账号的班主任班 id（取设置，兜底取该账号第一个 HOMEROOM 班） */
    private Long homeroomClassId() {
        Long uid = uid();
        try {
            return Long.valueOf(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k='homeroom_class_id'", String.class, uid));
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

    /** 统计：总人数/男/女/比例 */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats() {
        Long cid = homeroomClassId();
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE user_id=? AND class_id=?", Integer.class, uid(), cid);
        Integer boys = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE user_id=? AND class_id=? AND gender='男'", Integer.class, uid(), cid);
        Integer girls = total - boys;
        return Result.ok(Map.of("total", total, "boys", boys, "girls", girls, "ratio", boys + ":" + girls));
    }

    /** 学生列表（含家长），可按性别/关键字过滤 */
    @GetMapping("/students")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String gender,
                                                  @RequestParam(required = false) String keyword) {
        Long uid = uid();
        Long cid = homeroomClassId();
        StringBuilder sql = new StringBuilder(
                "SELECT id,stu_no,name,gender,address FROM student WHERE user_id=? AND class_id=?");
        List<Object> args = new ArrayList<>(List.of(uid, cid));
        if (gender != null && !gender.isBlank() && !gender.equals("全部")) {
            sql.append(" AND gender=?");
            args.add(gender);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE ? OR stu_no LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY stu_no");
        List<Map<String, Object>> students = jdbc.queryForList(sql.toString(), args.toArray());
        if (!students.isEmpty()) {
            List<Long> ids = students.stream().map(s -> ((Number) s.get("id")).longValue()).toList();
            String in = String.join(",", ids.stream().map(x -> "?").toList());
            List<Map<String, Object>> parents = jdbc.queryForList(
                    "SELECT id,student_id,relation,name,phone FROM parent_contact WHERE user_id=? AND student_id IN (" + in + ")",
                    concatArgs(uid, ids));
            Map<Long, List<Map<String, Object>>> pmap = new HashMap<>();
            for (Map<String, Object> p : parents) {
                pmap.computeIfAbsent(((Number) p.get("student_id")).longValue(), k -> new ArrayList<>()).add(p);
            }
            students.forEach(s -> s.put("parents", pmap.getOrDefault(((Number) s.get("id")).longValue(), new ArrayList<>())));
        }
        return Result.ok(students);
    }

    /** 新增学生（含家长） */
    @PostMapping("/student")
    public Result<Long> add(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long cid = homeroomClassId();
        String stuNo = str(body.get("stuNo"));
        String name = str(body.get("name"));
        if (name.isBlank()) return Result.fail("姓名不能为空");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student WHERE user_id=? AND stu_no=?", Integer.class, uid, stuNo);
        if (cnt != null && cnt > 0) return Result.fail("学号已存在：" + stuNo);
        jdbc.update("INSERT INTO student(user_id,stu_no,name,gender,class_id,address) VALUES(?,?,?,?,?,?)",
                uid, stuNo, name, str(body.getOrDefault("gender", "男")), cid, str(body.get("address")));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        saveParents(uid, id, body.get("parents"));
        return Result.ok(id);
    }

    /** 修改学生（含家长，整体覆盖） */
    @PutMapping("/student/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long uid = uid();
        jdbc.update("UPDATE student SET stu_no=?,name=?,gender=?,address=? WHERE id=? AND user_id=?",
                str(body.get("stuNo")), str(body.get("name")),
                str(body.getOrDefault("gender", "男")), str(body.get("address")), id, uid);
        jdbc.update("DELETE FROM parent_contact WHERE user_id=? AND student_id=?", uid, id);
        saveParents(uid, id, body.get("parents"));
        return Result.ok();
    }

    @DeleteMapping("/student/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        deleteStudentCascade(uid(), id);
        return Result.ok();
    }

    /** 清空全班学生（含全部关联记录） */
    @PostMapping("/clear")
    public Result<Void> clear() {
        Long uid = uid();
        List<Map<String, Object>> ids = jdbc.queryForList(
                "SELECT id FROM student WHERE user_id=? AND class_id=?", uid, homeroomClassId());
        for (Map<String, Object> r : ids) {
            deleteStudentCascade(uid, ((Number) r.get("id")).longValue());
        }
        return Result.ok();
    }

    @SuppressWarnings("unchecked")
    private void saveParents(Long uid, Long studentId, Object parentsObj) {
        if (!(parentsObj instanceof List<?> list)) return;
        for (Object o : list) {
            Map<String, Object> p = (Map<String, Object>) o;
            String pname = str(p.get("name"));
            String phone = str(p.get("phone"));
            if (pname.isBlank() && phone.isBlank()) continue;
            jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(?,?,?,?,?)",
                    uid, studentId, str(p.getOrDefault("relation", "父亲")), pname, phone);
        }
    }

    /** Excel 导入：表头支持 学号/姓名/性别/住址/父亲/父亲电话/母亲/母亲电话 */
    @PostMapping("/import")
    public Result<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
        Long uid = uid();
        Long cid = homeroomClassId();
        // WorkbookFactory 按文件头自动识别：.xls(HSSF/OLE2) 与 .xlsx(XSSF/OOXML) 均可导入
        Workbook wb = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = wb.getSheetAt(0);
        Row header = sheet.getRow(0);
        Map<String, Integer> col = new HashMap<>();
        for (Cell c : header) {
            col.put(getString(c).trim(), c.getColumnIndex());
        }
        int added = 0, skipped = 0, incomplete = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String name = cell(row, col, "姓名");
            String stuNo = cell(row, col, "学号");
            String genderRaw = cell(row, col, "性别");
            // 整行空白（学号/姓名/性别都空）直接跳过，不计入任何统计
            if (name.isBlank() && stuNo.isBlank() && genderRaw.isBlank()) continue;
            // 信息完整性校验：学号、姓名、性别三项缺一不可，性别须为男/女；不完整则跳过，不自动补全
            String gender = normalizeGender(genderRaw);
            if (stuNo.isBlank() || name.isBlank() || gender == null) { incomplete++; continue; }
            // 同一账号内学号重复则跳过
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM student WHERE user_id=? AND stu_no=?", Integer.class, uid, stuNo);
            if (cnt != null && cnt > 0) { skipped++; continue; }
            String address = cell(row, col, "住址");
            jdbc.update("INSERT INTO student(user_id,stu_no,name,gender,class_id,address) VALUES(?,?,?,?,?,?)",
                    uid, stuNo, name, gender, cid, address);
            Long sid = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            String dad = cell(row, col, "父亲"), dadPhone = cell(row, col, "父亲电话");
            String mom = cell(row, col, "母亲"), momPhone = cell(row, col, "母亲电话");
            if (!dad.isBlank() || !dadPhone.isBlank())
                jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(?,?,?,?,?)",
                        uid, sid, "父亲", dad, dadPhone);
            if (!mom.isBlank() || !momPhone.isBlank())
                jdbc.update("INSERT INTO parent_contact(user_id,student_id,relation,name,phone) VALUES(?,?,?,?,?)",
                        uid, sid, "母亲", mom, momPhone);
            added++;
        }
        wb.close();
        return Result.ok(Map.of("added", added, "skipped", skipped, "incomplete", incomplete));
    }

    /** 导出花名册 Excel */
    @GetMapping("/export")
    public void export(HttpServletResponse response) throws Exception {
        Long uid = uid();
        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id,stu_no,name,gender,address FROM student WHERE user_id=? AND class_id=? ORDER BY stu_no",
                uid, homeroomClassId());
        Workbook wb = new XSSFWorkbook();
        Sheet sheet = wb.createSheet("花名册");
        String[] heads = {"学号", "姓名", "性别", "住址", "父亲", "父亲电话", "母亲", "母亲电话"};
        Row hr = sheet.createRow(0);
        for (int i = 0; i < heads.length; i++) hr.createCell(i).setCellValue(heads[i]);
        int r = 1;
        for (Map<String, Object> s : students) {
            Long sid = ((Number) s.get("id")).longValue();
            List<Map<String, Object>> ps = jdbc.queryForList(
                    "SELECT relation,name,phone FROM parent_contact WHERE user_id=? AND student_id=?", uid, sid);
            String dad = "", dadP = "", mom = "", momP = "";
            for (Map<String, Object> p : ps) {
                if ("父亲".equals(p.get("relation"))) { dad = str(p.get("name")); dadP = str(p.get("phone")); }
                if ("母亲".equals(p.get("relation"))) { mom = str(p.get("name")); momP = str(p.get("phone")); }
            }
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(str(s.get("stu_no")));
            row.createCell(1).setCellValue(str(s.get("name")));
            row.createCell(2).setCellValue(str(s.get("gender")));
            row.createCell(3).setCellValue(str(s.get("address")));
            row.createCell(4).setCellValue(dad);
            row.createCell(5).setCellValue(dadP);
            row.createCell(6).setCellValue(mom);
            row.createCell(7).setCellValue(momP);
        }
        for (int i = 0; i < heads.length; i++) sheet.autoSizeColumn(i);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment;filename=" +
                URLEncoder.encode("花名册.xlsx", StandardCharsets.UTF_8));
        wb.write(response.getOutputStream());
        wb.close();
    }

    /** 学生档案聚合：基本信息 + 家长 + 沟通 + 考勤/纪律/作业 */
    @GetMapping("/student/{id}/profile")
    public Result<Map<String, Object>> profile(@PathVariable Long id) {
        Long uid = uid();
        Map<String, Object> info = jdbc.queryForMap(
                "SELECT id,stu_no,name,gender,address FROM student WHERE id=? AND user_id=?", id, uid);
        List<Map<String, Object>> parents = jdbc.queryForList(
                "SELECT relation,name,phone FROM parent_contact WHERE user_id=? AND student_id=?", uid, id);
        List<Map<String, Object>> comms = jdbc.queryForList(
                "SELECT contact_date,method,content FROM comm_log WHERE user_id=? AND student_id=? ORDER BY contact_date DESC LIMIT 20",
                uid, id);
        List<Map<String, Object>> atts = jdbc.queryForList(
                "SELECT record_date,status FROM attendance WHERE user_id=? AND student_id=? AND status<>'出勤' ORDER BY record_date DESC LIMIT 20",
                uid, id);
        List<Map<String, Object>> discs = jdbc.queryForList(
                "SELECT record_date,content FROM discipline_log WHERE user_id=? AND student_id=? ORDER BY record_date DESC LIMIT 20",
                uid, id);
        List<Map<String, Object>> hws = jdbc.queryForList(
                "SELECT record_date,subject,status FROM homework_check WHERE user_id=? AND student_id=? AND status='未交' ORDER BY record_date DESC LIMIT 20",
                uid, id);
        return Result.ok(Map.of("info", info, "parents", parents, "comms", comms,
                "attendances", atts, "disciplines", discs, "homeworks", hws));
    }

    /** 拼接 IN 查询参数：首位放 uid，后接 id 列表 */
    private Object[] concatArgs(Object uid, List<Long> ids) {
        List<Object> args = new ArrayList<>();
        args.add(uid);
        args.addAll(ids);
        return args.toArray();
    }

    private String cell(Row row, Map<String, Integer> col, String key) {
        Integer idx = col.get(key);
        if (idx == null) return "";
        Cell c = row.getCell(idx);
        return c == null ? "" : getString(c);
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

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    /** 性别归一化：含“男/女”关键字转为标准值，无法识别返回 null（视为信息不全） */
    private String normalizeGender(String s) {
        if (s == null) return null;
        String g = s.trim();
        if (g.contains("男")) return "男";
        if (g.contains("女")) return "女";
        return null;
    }

    /** 删除学生的全部关联记录（家长/沟通/考勤/纪律/作业/座次/值日/班委/成绩/成就/宠物），避免孤儿数据 */
    private void deleteStudentCascade(Long uid, Long sid) {
        for (String t : List.of("parent_contact", "comm_log", "attendance", "discipline_log",
                "homework_check", "seat_assignment", "duty_roster", "committee", "score",
                "student_achievement", "pet", "pet_log", "pet_badge", "pet_exchange")) {
            jdbc.update("DELETE FROM " + t + " WHERE user_id=? AND student_id=?", uid, sid);
        }
        jdbc.update("DELETE FROM student WHERE id=? AND user_id=?", sid, uid);
    }
}
