package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.apache.poi.ss.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 题库（老师自建，供打怪兽/答题 PK 抽题）：分类管理、题目增删改查、
 * Excel 导入（分类|题目|答案|难度）、智能抽题（最久未用优先，30 天内降权）。全部按 uid 隔离。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController extends BaseController {

    private final JdbcTemplate jdbc;

    public QuizController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 分类 ----------

    /** 分类列表（含每分类的题目数 questionCount，供左栏卡片展示） */
    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> categories() {
        Long uid = uid();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT c.id, c.name, c.icon, c.enabled, c.sort, " +
                "(SELECT COUNT(*) FROM quiz_bank b WHERE b.category_id=c.id AND b.user_id=c.user_id) questionCount " +
                "FROM quiz_category c WHERE c.user_id=? ORDER BY c.sort, c.id", uid);
        return Result.ok(list);
    }

    /** 分类新增/编辑：id 为空新增；name 必填 */
    @PostMapping("/category")
    public Result<Map<String, Object>> saveCategory(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String name = str(body.get("name"));
        if (name.isBlank()) return Result.fail("分类名称不能为空");
        String icon = str(body.getOrDefault("icon", "📚"));
        Integer enabled = body.get("enabled") == null ? 1 : ((Number) body.get("enabled")).intValue();
        Object id = body.get("id");
        if (id != null && ((Number) id).longValue() > 0) {
            jdbc.update("UPDATE quiz_category SET name=?, icon=?, enabled=? WHERE id=? AND user_id=?",
                    name, icon, enabled, id, uid);
            return Result.ok(Map.of("id", ((Number) id).longValue()));
        }
        jdbc.update("INSERT INTO quiz_category(user_id,name,icon,enabled) VALUES(?,?,?,?)", uid, name, icon, enabled);
        return Result.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    /** 删除分类：仅限空分类（有题目时提示先清空题目） */
    @PostMapping("/category/delete/{id}")
    public Result<Void> deleteCategory(@PathVariable Long id) {
        Long uid = uid();
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quiz_bank WHERE user_id=? AND category_id=?", Integer.class, uid, id);
        if (cnt != null && cnt > 0) return Result.fail("该分类下还有 " + cnt + " 道题，请先清空再删除");
        jdbc.update("DELETE FROM quiz_category WHERE id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    // ---------- 题目 ----------

    /**
     * 题目分页列表：categoryId/difficulty/kw 全部可选；返回 {rows, total}。
     */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(@RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) Integer difficulty,
                                            @RequestParam(required = false, defaultValue = "") String kw,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "50") int size) {
        Long uid = uid();
        StringBuilder where = new StringBuilder(" WHERE b.user_id=?");
        List<Object> args = new ArrayList<>(List.of(uid));
        if (categoryId != null) { where.append(" AND b.category_id=?"); args.add(categoryId); }
        if (difficulty != null) { where.append(" AND b.difficulty=?"); args.add(difficulty); }
        if (kw != null && !kw.isBlank()) { where.append(" AND (b.question LIKE ? OR b.answer LIKE ?)"); args.add("%" + kw + "%"); args.add("%" + kw + "%"); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM quiz_bank b" + where, Long.class, args.toArray());
        String limit = " LIMIT " + Math.max(0, (page - 1) * size) + "," + size;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.category_id categoryId, c.name categoryName, c.icon categoryIcon, " +
                "b.question, b.answer, b.difficulty, b.enabled, b.use_count useCount, b.last_used_at lastUsedAt " +
                "FROM quiz_bank b JOIN quiz_category c ON c.id=b.category_id" + where +
                " ORDER BY b.id DESC" + limit, args.toArray());
        return Result.ok(Map.of("rows", rows, "total", total == null ? 0 : total));
    }

    /** 题目新增/编辑：id 为空新增；题干必填 */
    @PostMapping("/save")
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        String question = str(body.get("question"));
        if (question.isBlank()) return Result.fail("题干不能为空");
        Long categoryId = body.get("categoryId") == null ? null : ((Number) body.get("categoryId")).longValue();
        if (categoryId == null) return Result.fail("请选择分类");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM quiz_category WHERE user_id=? AND id=?", Integer.class, uid, categoryId);
        if (cnt == null || cnt == 0) return Result.fail("分类不存在");
        String answer = str(body.get("answer"));
        int difficulty = body.get("difficulty") == null ? 1 : ((Number) body.get("difficulty")).intValue();
        if (difficulty < 1 || difficulty > 3) difficulty = 1;
        Object id = body.get("id");
        if (id != null && ((Number) id).longValue() > 0) {
            jdbc.update("UPDATE quiz_bank SET category_id=?, question=?, answer=?, difficulty=? WHERE id=? AND user_id=?",
                    categoryId, question, answer, difficulty, id, uid);
            return Result.ok(Map.of("id", ((Number) id).longValue()));
        }
        jdbc.update("INSERT INTO quiz_bank(user_id,category_id,question,answer,difficulty) VALUES(?,?,?,?,?)",
                uid, categoryId, question, answer, difficulty);
        return Result.ok(Map.of("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class)));
    }

    /** 删除题目 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        jdbc.update("DELETE FROM quiz_bank WHERE id=? AND user_id=?", id, uid());
        return Result.ok();
    }

    /**
     * Excel 导入：列=分类|题目|答案|难度（首行表头跳过）；分类名不存在自动建（icon 📚），
     * 难度列空或非 1~3 默认 1；返回 {added, skipped, categoriesCreated}。
     */
    @PostMapping("/import")
    public Result<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) throws Exception {
        Long uid = uid();
        Workbook wb = WorkbookFactory.create(file.getInputStream());
        Sheet sheet = wb.getSheetAt(0);
        Row header = sheet.getRow(0);
        Map<String, Integer> col = new HashMap<>();
        for (Cell c : header) col.put(getString(c).trim(), c.getColumnIndex());
        // 分类名缓存：避免每行查库
        Map<String, Long> catCache = new HashMap<>();
        int added = 0, skipped = 0, categoriesCreated = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) continue;
            String catName = cell(row, col, "分类");
            String question = cell(row, col, "题目");
            String answer = cell(row, col, "答案");
            // 整行空白（分类/题目都空）跳过不计
            if (catName.isBlank() && question.isBlank()) continue;
            // 题干缺失计入 skipped
            if (question.isBlank() || catName.isBlank()) { skipped++; continue; }
            Long catId = catCache.get(catName);
            if (catId == null) {
                List<Map<String, Object>> found = jdbc.queryForList(
                        "SELECT id FROM quiz_category WHERE user_id=? AND name=? LIMIT 1", uid, catName);
                if (found.isEmpty()) {
                    jdbc.update("INSERT INTO quiz_category(user_id,name,icon) VALUES(?,?,'📚')", uid, catName);
                    catId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    categoriesCreated++;
                } else {
                    catId = ((Number) found.get(0).get("id")).longValue();
                }
                catCache.put(catName, catId);
            }
            int difficulty = 1;
            try { difficulty = Integer.parseInt(cell(row, col, "难度")); } catch (Exception ignore) { }
            if (difficulty < 1 || difficulty > 3) difficulty = 1;
            jdbc.update("INSERT INTO quiz_bank(user_id,category_id,question,answer,difficulty) VALUES(?,?,?,?,?)",
                    uid, catId, question, answer, difficulty);
            added++;
        }
        wb.close();
        return Result.ok(Map.of("added", added, "skipped", skipped, "categoriesCreated", categoriesCreated));
    }

    // ---------- 抽题 ----------

    /**
     * 抽一题（供打怪兽/PK）：只在启用中的分类与题目里抽；
     * 最久未用优先（30 天内用过的排最后，不排除），随机打破并列；抽中即记 use_count+1 / last_used_at。
     * 无可用题目返回 data=null（不报错，调用方自行提示）。
     */
    @PostMapping("/draw")
    public Result<Map<String, Object>> draw(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long categoryId = body.get("categoryId") == null ? null : ((Number) body.get("categoryId")).longValue();
        Integer difficulty = body.get("difficulty") == null ? null : ((Number) body.get("difficulty")).intValue();
        StringBuilder where = new StringBuilder(" WHERE b.user_id=? AND b.enabled=1 AND c.enabled=1");
        List<Object> args = new ArrayList<>(List.of(uid));
        if (categoryId != null) { where.append(" AND b.category_id=?"); args.add(categoryId); }
        if (difficulty != null) { where.append(" AND b.difficulty=?"); args.add(difficulty); }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.id, b.category_id categoryId, c.name categoryName, c.icon categoryIcon, " +
                "b.question, b.answer, b.difficulty FROM quiz_bank b JOIN quiz_category c ON c.id=b.category_id" +
                where + " ORDER BY (b.last_used_at IS NULL) DESC, b.last_used_at ASC, RAND() LIMIT 1", args.toArray());
        if (rows.isEmpty()) return Result.ok(null);
        Map<String, Object> quiz = rows.get(0);
        jdbc.update("UPDATE quiz_bank SET use_count=use_count+1, last_used_at=NOW() WHERE id=?", quiz.get("id"));
        return Result.ok(quiz);
    }

    // ---------- 辅助 ----------

    private String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }

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
}
