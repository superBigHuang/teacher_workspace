package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.AiService;
import com.teacher.platform.service.TermStatsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 期末评语 + 成长档案（按账号 uid 隔离）。
 * 评语：素材复用 TermStatsService，生成走 AiService（AI 优先、规则模板降级），
 * 落库 comment 表（每生每学期一条唯一）；成长档案聚合数字卡/成绩走势/五类记录时间线。
 */
@RestController
@RequestMapping("/api/comment")
public class CommentController extends BaseController {

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;
    private final AiService aiService;

    public CommentController(JdbcTemplate jdbc, TermStatsService termStats, AiService aiService) {
        this.jdbc = jdbc;
        this.termStats = termStats;
        this.aiService = aiService;
    }

    /** 全班学期素材 + 本学期已保存评语（评语页左侧名单状态点用） */
    @GetMapping("/materials")
    public Result<Map<String, Object>> materials() {
        Long uid = uid();
        Long cid = termStats.homeroomClassId(uid);
        String[] range = termStats.termRange(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termStart", range[0]);
        result.put("termEnd", range[1]);
        result.put("term", range[0] + "~" + range[1]);
        result.put("classId", cid);
        result.put("students", termStats.materials(uid, cid));
        result.put("saved", savedMap(uid, range[0] + "~" + range[1]));
        return Result.ok(result);
    }

    /** 单生素材 + 该生已保存评语（右侧面板/档案用） */
    @GetMapping("/material")
    public Result<Map<String, Object>> material(@RequestParam Long studentId) {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("material", termStats.material(uid, studentId));
        result.put("saved", savedMap(uid, range[0] + "~" + range[1]).get(studentId));
        return Result.ok(result);
    }

    /**
     * 生成评语草稿（不落库）：AI 优先，失败/未配置自动降级规则模板。
     * body: {studentId}；返回 {content, source:"ai"|"template", model?, error?}
     */
    @PostMapping("/generate")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        Long studentId = asLong(body.get("studentId"));
        if (studentId == null) return Result.fail("缺少 studentId");
        return Result.ok(aiService.generate(uid(), "comment", studentId));
    }

    /**
     * 保存/更新评语（每生每学期唯一，重复保存走 UPDATE）。
     * body: {studentId, content, source?}；学期标识由后端按 term_start~term_end 生成。
     */
    @PutMapping("/save")
    public Result<?> save(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long studentId = asLong(body.get("studentId"));
        String content = str(body.get("content")).trim();
        if (studentId == null || content.isEmpty()) return Result.fail("学生或评语内容为空");
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        String source = str(body.get("source"));
        if (source.isEmpty()) source = "manual";
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `comment` WHERE user_id=? AND student_id=? AND term=?",
                Integer.class, uid, studentId, term);
        if (cnt != null && cnt > 0) {
            jdbc.update("UPDATE `comment` SET content=?, source=?, updated_at=NOW() " +
                    "WHERE user_id=? AND student_id=? AND term=?", content, source, uid, studentId, term);
        } else {
            jdbc.update("INSERT INTO `comment` (user_id, student_id, term, content, source) VALUES (?,?,?,?,?)",
                    uid, studentId, term, content, source);
        }
        return Result.ok();
    }

    /** 删除本学期某生评语（重新生成/清理用） */
    @DeleteMapping("/{studentId}")
    public Result<?> delete(@PathVariable Long studentId) {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        jdbc.update("DELETE FROM `comment` WHERE user_id=? AND student_id=? AND term=?",
                uid, studentId, range[0] + "~" + range[1]);
        return Result.ok();
    }

    /** 本学期已保存评语列表（评语册打印用），按学号排序 */
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT c.student_id studentId, s.stu_no stuNo, s.name, c.content, c.source, c.updated_at updatedAt " +
                        "FROM `comment` c JOIN student s ON s.id = c.student_id " +
                        "WHERE c.user_id=? AND c.term=? ORDER BY s.stu_no, s.id", uid, term);
        // 抬头信息：班级名、教师名、学校
        Long cid = termStats.homeroomClassId(uid);
        String className = "";
        try {
            className = jdbc.queryForObject("SELECT name FROM class_info WHERE id=?", String.class, cid);
        } catch (Exception ignored) { }
        String teacherName = setting(uid, "teacher_name");
        if (teacherName.isEmpty()) {
            try {
                teacherName = jdbc.queryForObject("SELECT display_name FROM sys_user WHERE id=?", String.class, uid);
            } catch (Exception ignored) { }
        }
        String school = setting(uid, "school");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("term", term);
        result.put("termStart", range[0]);
        result.put("termEnd", range[1]);
        result.put("className", className);
        result.put("teacherName", teacherName);
        result.put("school", school);
        result.put("list", list);
        return Result.ok(result);
    }

    /**
     * 成长档案：数字卡素材 + 学期成绩走势 + 五类记录时间线（花名册抽屉用）。
     */
    @GetMapping("/archive")
    public Result<Map<String, Object>> archive(@RequestParam Long studentId) {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termStart", range[0]);
        result.put("termEnd", range[1]);
        result.put("material", termStats.material(uid, studentId));
        result.put("trend", termStats.examTrend(uid, range[0], range[1], studentId));
        result.put("timeline", termStats.timeline(uid, range[0], range[1], studentId));
        return Result.ok(result);
    }

    // ---------- 辅助 ----------

    /** 本学期已保存评语映射：studentId -> {content, source, updatedAt} */
    private Map<Long, Map<String, Object>> savedMap(Long uid, String term) {
        Map<Long, Map<String, Object>> map = new HashMap<>();
        jdbc.queryForList(
                "SELECT student_id sid, content, source, updated_at updatedAt FROM `comment` WHERE user_id=? AND term=?",
                uid, term).forEach(r ->
                map.put(((Number) r.get("sid")).longValue(), r));
        return map;
    }

    private String setting(Long uid, String k) {
        try {
            String v = jdbc.queryForObject("SELECT v FROM setting WHERE user_id=? AND k=?", String.class, uid, k);
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private Long asLong(Object o) {
        if (o == null) return null;
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
