package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 座次表：配置、随机排座（男女尽量交错）、手动交换、清空（按账号隔离）
 */
@RestController
@RequestMapping("/api/seat")
public class SeatController extends BaseController {

    private final JdbcTemplate jdbc;
    private final Random random = new Random();

    public SeatController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    /** 读取座次配置（无则返回默认 7 行 8 列，过道 2/4/6 列后） */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Long uid = uid();
        Long cid = homeroomClassId();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT `rows`,cols,aisles FROM seat_config WHERE user_id=? AND class_id=?", uid, cid);
        if (list.isEmpty()) {
            return Result.ok(Map.of("rows", 7, "cols", 8, "aisles", List.of(2, 4, 6)));
        }
        Map<String, Object> r = list.get(0);
        r.put("aisles", parseAisles((String) r.get("aisles")));
        return Result.ok(r);
    }

    @PostMapping("/config")
    public Result<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long cid = homeroomClassId();
        int rows = ((Number) body.get("rows")).intValue();
        int cols = ((Number) body.get("cols")).intValue();
        String aisles = body.get("aisles") == null ? "" : String.valueOf(body.get("aisles"))
                .replace("[", "").replace("]", "").replace(" ", "");
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_config WHERE user_id=? AND class_id=?", Integer.class, uid, cid);
        if (cnt == 0) {
            jdbc.update("INSERT INTO seat_config(user_id,class_id,`rows`,cols,aisles) VALUES(?,?,?,?,?)",
                    uid, cid, rows, cols, aisles);
        } else {
            jdbc.update("UPDATE seat_config SET `rows`=?,cols=?,aisles=? WHERE user_id=? AND class_id=?",
                    rows, cols, aisles, uid, cid);
        }
        return Result.ok();
    }

    /** 座位网格：返回每个座位的学生（含空座位） */
    @GetMapping("/grid")
    public Result<Map<String, Object>> grid() {
        Long uid = uid();
        Long cid = homeroomClassId();
        Map<String, Object> cfg = config().getData();
        int rows = ((Number) cfg.get("rows")).intValue();
        int cols = ((Number) cfg.get("cols")).intValue();
        @SuppressWarnings("unchecked")
        List<Integer> aisles = (List<Integer>) cfg.get("aisles");

        List<Map<String, Object>> seats = new ArrayList<>();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                Map<String, Object> seat = new HashMap<>();
                seat.put("row", r);
                seat.put("col", c);
                seat.put("aisle", false);
                List<Map<String, Object>> assigned = jdbc.queryForList(
                        "SELECT a.student_id, s.name, s.stu_no, s.gender FROM seat_assignment a " +
                        "LEFT JOIN student s ON s.id=a.student_id " +
                        "WHERE a.user_id=? AND a.class_id=? AND a.row_no=? AND a.col_no=?",
                        uid, cid, r, c);
                if (!assigned.isEmpty() && assigned.get(0).get("student_id") != null) {
                    Map<String, Object> a = assigned.get(0);
                    seat.put("studentId", a.get("student_id"));
                    seat.put("name", a.get("name"));
                    seat.put("stuNo", a.get("stu_no"));
                    seat.put("gender", a.get("gender"));
                }
                seats.add(seat);
            }
        }
        return Result.ok(Map.of("rows", rows, "cols", cols, "aisles", aisles, "seats", seats));
    }

    /** 随机排座：男女尽量交错，空位留在后排 */
    @PostMapping("/auto")
    public Result<Void> auto() {
        Long uid = uid();
        Long cid = homeroomClassId();
        saveConfigCurrent(uid, cid);
        Map<String, Object> cfg = config().getData();
        int rows = ((Number) cfg.get("rows")).intValue();
        int cols = ((Number) cfg.get("cols")).intValue();
        // 注意：aisles（过道）只是前端在“第 N 列后”渲染的视觉间隔，不占用也不减少逻辑座位，
        // 因此排座位置必须用满 rows×cols，不能按过道列跳过（否则每排少坐 aisles.size() 人）。

        List<Map<String, Object>> students = jdbc.queryForList(
                "SELECT id,gender FROM student WHERE user_id=? AND class_id=? ORDER BY stu_no", uid, cid);
        List<Long> boys = new ArrayList<>(), girls = new ArrayList<>();
        for (Map<String, Object> s : students) {
            if ("女".equals(s.get("gender"))) girls.add(((Number) s.get("id")).longValue());
            else boys.add(((Number) s.get("id")).longValue());
        }
        Collections.shuffle(boys);
        Collections.shuffle(girls);

        // 性别交错合并
        List<Long> ordered = new ArrayList<>();
        String last = null;
        while (!boys.isEmpty() || !girls.isEmpty()) {
            boolean takeBoy;
            if (last == null) {
                takeBoy = boys.size() >= girls.size();
            } else if ("男".equals(last) && !girls.isEmpty()) {
                takeBoy = false;
            } else if ("女".equals(last) && !boys.isEmpty()) {
                takeBoy = true;
            } else {
                takeBoy = !boys.isEmpty();
            }
            if (takeBoy && !boys.isEmpty()) { ordered.add(boys.remove(0)); last = "男"; }
            else if (!girls.isEmpty()) { ordered.add(girls.remove(0)); last = "女"; }
            else if (!boys.isEmpty()) { ordered.add(boys.remove(0)); last = "男"; }
        }

        // 生成座位位置：rows×cols 个逻辑座位全部可用，按行优先（学生数少于座位数时空位自然留在后排）
        List<int[]> positions = new ArrayList<>();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                positions.add(new int[]{r, c});
            }
        }

        jdbc.update("DELETE FROM seat_assignment WHERE user_id=? AND class_id=?", uid, cid);
        for (int i = 0; i < ordered.size() && i < positions.size(); i++) {
            int[] pos = positions.get(i);
            jdbc.update("INSERT INTO seat_assignment(user_id,class_id,row_no,col_no,student_id) VALUES(?,?,?,?,?)",
                    uid, cid, pos[0], pos[1], ordered.get(i));
        }
        return Result.ok();
    }

    @PostMapping("/clear")
    public Result<Void> clear() {
        jdbc.update("DELETE FROM seat_assignment WHERE user_id=? AND class_id=?", uid(), homeroomClassId());
        return Result.ok();
    }

    /** 交换/移动两个座位（目标可为空位） */
    @PostMapping("/move")
    public Result<Void> move(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long cid = homeroomClassId();
        int fr = ((Number) body.get("fromRow")).intValue();
        int fc = ((Number) body.get("fromCol")).intValue();
        int tr = ((Number) body.get("toRow")).intValue();
        int tc = ((Number) body.get("toCol")).intValue();
        Long fromSid = queryStudent(uid, cid, fr, fc);
        Long toSid = queryStudent(uid, cid, tr, tc);
        jdbc.update("DELETE FROM seat_assignment WHERE user_id=? AND class_id=? AND row_no=? AND col_no=?", uid, cid, tr, tc);
        jdbc.update("DELETE FROM seat_assignment WHERE user_id=? AND class_id=? AND row_no=? AND col_no=?", uid, cid, fr, fc);
        if (fromSid != null) jdbc.update("INSERT INTO seat_assignment(user_id,class_id,row_no,col_no,student_id) VALUES(?,?,?,?,?)", uid, cid, tr, tc, fromSid);
        if (toSid != null) jdbc.update("INSERT INTO seat_assignment(user_id,class_id,row_no,col_no,student_id) VALUES(?,?,?,?,?)", uid, cid, fr, fc, toSid);
        return Result.ok();
    }

    /**
     * 设/取消列组长（零表变更：组长 = committee 表一条 role='组长' 记录，自动进班委名单/评语职务/班委担当成就）。
     * 组 = 座位的一"列"，组长属哪列按其当前座位 col_no 实时推导（换座/随机排座后 👑 自动跟人）。
     * 一列至多一个组长：该生已是组长则取消；否则删掉同列旧组长后再任命。
     */
    @PostMapping("/leader")
    public Result<Map<String, Object>> setLeader(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long cid = homeroomClassId();
        Long sid = body.get("studentId") == null ? null : Long.valueOf(String.valueOf(body.get("studentId")));
        if (sid == null) return Result.fail("缺少学生");
        // 该生当前座位所在列（未排座则无列可属）
        List<Map<String, Object>> seat = jdbc.queryForList(
                "SELECT col_no FROM seat_assignment WHERE user_id=? AND class_id=? AND student_id=?",
                uid, cid, sid);
        if (seat.isEmpty()) return Result.fail("请先给该生安排座位");
        int col = ((Number) seat.get(0).get("col_no")).intValue();
        // 已是组长 → 取消
        List<Map<String, Object>> mine = jdbc.queryForList(
                "SELECT id FROM committee WHERE user_id=? AND student_id=? AND role='组长'", uid, sid);
        if (!mine.isEmpty()) {
            jdbc.update("DELETE FROM committee WHERE id=? AND user_id=?",
                    ((Number) mine.get(0).get("id")).longValue(), uid);
            return Result.ok(Map.of("canceled", true));
        }
        // 任命为该列组长：先删同列所有学生的旧组长记录（保证一列一个）
        List<Map<String, Object>> colMates = jdbc.queryForList(
                "SELECT student_id FROM seat_assignment WHERE user_id=? AND class_id=? AND col_no=?",
                uid, cid, col);
        if (!colMates.isEmpty()) {
            List<Long> mateIds = colMates.stream()
                    .map(m -> ((Number) m.get("student_id")).longValue()).toList();
            String in = String.join(",", mateIds.stream().map(x -> "?").toList());
            List<Object> args = new ArrayList<>();
            args.add(uid);
            args.addAll(mateIds);
            jdbc.update("DELETE FROM committee WHERE user_id=? AND role='组长' AND student_id IN (" + in + ")",
                    args.toArray());
        }
        jdbc.update("INSERT INTO committee(user_id,student_id,role) VALUES(?,?, '组长')", uid, sid);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("canceled", false);
        res.put("col", col);
        return Result.ok(res);
    }

    private Long queryStudent(Long uid, Long cid, int r, int c) {
        List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT student_id FROM seat_assignment WHERE user_id=? AND class_id=? AND row_no=? AND col_no=?",
                uid, cid, r, c);
        if (l.isEmpty() || l.get(0).get("student_id") == null) return null;
        return ((Number) l.get(0).get("student_id")).longValue();
    }

    /** 排座前确保配置已持久化（使用默认值） */
    private void saveConfigCurrent(Long uid, Long cid) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_config WHERE user_id=? AND class_id=?", Integer.class, uid, cid);
        if (cnt == 0) {
            jdbc.update("INSERT INTO seat_config(user_id,class_id,`rows`,cols,aisles) VALUES(?,?,?,?,?)",
                    uid, cid, 7, 8, "2,4,6");
        }
    }

    private List<Integer> parseAisles(String s) {
        List<Integer> list = new ArrayList<>();
        if (s == null || s.isBlank()) return list;
        for (String p : s.split(",")) {
            if (!p.isBlank()) list.add(Integer.valueOf(p.trim()));
        }
        return list;
    }
}
