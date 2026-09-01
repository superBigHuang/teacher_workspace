package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.TermStatsService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 请假闭环：登记（区间考勤自动置请假且不覆盖手动标记）、销假、台账、
 * 学期汇总与病假预警（按账号隔离）。
 */
@RestController
@RequestMapping("/api/leave")
public class LeaveController extends BaseController {

    private final JdbcTemplate jdbc;
    private final TermStatsService termStats;

    public LeaveController(JdbcTemplate jdbc, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.termStats = termStats;
    }

    /** 请假中列表（含学生姓名、应返校日、超期/今日应销假/今日请假中标记），按应返校日升序 */
    @GetMapping("/active")
    public Result<List<Map<String, Object>>> active() {
        Long uid = uid();
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT l.id, l.student_id studentId, s.name, s.stu_no stuNo, l.leave_type leaveType, " +
                        "l.start_date startDate, l.end_date endDate, l.days, l.reason, l.notify_method notifyMethod, " +
                        "l.status, l.return_date returnDate " +
                        "FROM leave_record l JOIN student s ON s.id=l.student_id " +
                        "WHERE l.user_id=? AND l.status='ACTIVE' ORDER BY l.end_date, l.id", uid);
        for (Map<String, Object> r : list) {
            LocalDate end = LocalDate.parse(String.valueOf(r.get("endDate")));
            LocalDate start = LocalDate.parse(String.valueOf(r.get("startDate")));
            r.put("overdue", end.isBefore(today));          // 超期未销假 → 未归校标红
            r.put("returnToday", end.isEqual(today));       // 今日应销假
            r.put("inLeaveToday", !today.isBefore(start) && !today.isAfter(end));
        }
        return Result.ok(list);
    }

    /** 台账：全部单据（含已销假），可按学生 / 月份(yyyy-MM) 过滤 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) Long studentId,
                                                  @RequestParam(required = false) String month) {
        Long uid = uid();
        StringBuilder sql = new StringBuilder(
                "SELECT l.id, l.student_id studentId, s.name, s.stu_no stuNo, l.leave_type leaveType, " +
                        "l.start_date startDate, l.end_date endDate, l.days, l.reason, l.notify_method notifyMethod, " +
                        "l.status, l.return_date returnDate " +
                        "FROM leave_record l JOIN student s ON s.id=l.student_id WHERE l.user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(uid);
        if (studentId != null) {
            sql.append(" AND l.student_id=?");
            args.add(studentId);
        }
        if (month != null && !month.isBlank()) {
            sql.append(" AND DATE_FORMAT(l.start_date,'%Y-%m')=?");
            args.add(month);
        }
        sql.append(" ORDER BY l.start_date DESC, l.id DESC");
        return Result.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    /**
     * 登记请假：写单据 + 区间内考勤自动置"请假"。
     * 考勤用 INSERT IGNORE：老师已手动标记（迟到/缺勤等）的日期保留不覆盖，仅补空白日期；
     * 自动写入的考勤带 leave_id，删单时据此精确回滚。days 支持 0.5（半天），未传按整天算。
     */
    @PostMapping
    public Result<Long> create(@RequestBody Map<String, Object> body) {
        Long uid = uid();
        Long studentId = ((Number) body.get("studentId")).longValue();
        String leaveType = str(body.get("leaveType"));
        LocalDate start = LocalDate.parse(str(body.get("startDate")));
        LocalDate end = LocalDate.parse(str(body.get("endDate")));
        if (end.isBefore(start)) return Result.fail("结束日期不能早于开始日期");
        double days;
        Object daysRaw = body.get("days");
        if (daysRaw != null && !String.valueOf(daysRaw).isBlank()) {
            days = Double.parseDouble(String.valueOf(daysRaw));
        } else {
            days = ChronoUnit.DAYS.between(start, end) + 1;
        }
        jdbc.update("INSERT INTO leave_record(user_id,student_id,leave_type,start_date,end_date,days,reason,notify_method,status) " +
                        "VALUES(?,?,?,?,?,?,?,?, 'ACTIVE')",
                uid, studentId, leaveType, start.toString(), end.toString(), days,
                str(body.get("reason")), str(body.get("notifyMethod")));
        Long leaveId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // 联动考勤：区间内每一天补"请假"（已有手动记录的日期自动跳过）
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            jdbc.update("INSERT IGNORE INTO attendance(user_id,record_date,student_id,status,leave_id) VALUES(?,?,?,'请假',?)",
                    uid, d.toString(), studentId, leaveId);
        }
        return Result.ok(leaveId);
    }

    /**
     * 编辑请假单（续假兜底）：可改结束日期/天数/事由。
     * 延长时新区间日期补"请假"（INSERT IGNORE 不覆盖手动标记）；
     * 缩短时回滚多出日期里本单据自动写入的考勤（按 leave_id 精确匹配）。
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long uid = uid();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT student_id studentId, start_date startDate, end_date endDate " +
                        "FROM leave_record WHERE id=? AND user_id=?", id, uid);
        if (rows.isEmpty()) return Result.fail("请假单不存在");
        Long studentId = ((Number) rows.get(0).get("studentId")).longValue();
        LocalDate start = LocalDate.parse(String.valueOf(rows.get(0).get("startDate")));
        LocalDate oldEnd = LocalDate.parse(String.valueOf(rows.get(0).get("endDate")));
        String endDateStr = str(body.get("endDate"));
        LocalDate newEnd = endDateStr.isEmpty() ? oldEnd : LocalDate.parse(endDateStr);
        if (newEnd.isBefore(start)) return Result.fail("结束日期不能早于开始日期");
        String reason = str(body.get("reason"));
        Object daysRaw = body.get("days");
        if (daysRaw != null && !String.valueOf(daysRaw).isBlank()) {
            jdbc.update("UPDATE leave_record SET end_date=?, days=?, reason=? WHERE id=? AND user_id=?",
                    newEnd.toString(), Double.parseDouble(String.valueOf(daysRaw)), reason, id, uid);
        } else {
            jdbc.update("UPDATE leave_record SET end_date=?, reason=? WHERE id=? AND user_id=?",
                    newEnd.toString(), reason, id, uid);
        }
        if (newEnd.isAfter(oldEnd)) {
            // 续假延长：新增日期补请假考勤
            for (LocalDate d = oldEnd.plusDays(1); !d.isAfter(newEnd); d = d.plusDays(1)) {
                jdbc.update("INSERT IGNORE INTO attendance(user_id,record_date,student_id,status,leave_id) VALUES(?,?,?,'请假',?)",
                        uid, d.toString(), studentId, id);
            }
        } else if (newEnd.isBefore(oldEnd)) {
            // 缩短：回滚多出日期的自动考勤（record_date > newEnd 且 <= oldEnd）
            jdbc.update("DELETE FROM attendance WHERE user_id=? AND leave_id=? AND record_date>? AND record_date<=?",
                    uid, id, newEnd.toString(), oldEnd.toString());
        }
        return Result.ok();
    }

    /** 销假：置 DONE 并记录实际返校日（默认今天）；已生成的请假考勤保留留痕 */
    @PostMapping("/{id}/return")
    public Result<Void> doReturn(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        String returnDate = body == null ? "" : str(body.get("returnDate"));
        if (returnDate.isEmpty()) returnDate = LocalDate.now().toString();
        int n = jdbc.update("UPDATE leave_record SET status='DONE', return_date=? WHERE id=? AND user_id=?",
                returnDate, id, uid());
        return n > 0 ? Result.ok() : Result.fail("请假单不存在");
    }

    /** 误登记删除：同步清除本单据自动写入的请假考勤（按 leave_id 精确匹配，手动标记不动） */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long uid = uid();
        jdbc.update("DELETE FROM attendance WHERE user_id=? AND leave_id=?", uid, id);
        jdbc.update("DELETE FROM leave_record WHERE id=? AND user_id=?", id, uid);
        return Result.ok();
    }

    /**
     * 本学期汇总：每生总天数/病假/事假/公假天数与次数，病假超阈值标 warn；
     * 同时返回工作台四个计数（请假中 / 今日请假 / 今日应销假 / 超期未归）。
     */
    @GetMapping("/summary")
    public Result<Map<String, Object>> summary() {
        Long uid = uid();
        String[] range = termStats.termRange(uid);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT l.student_id studentId, s.name, s.stu_no stuNo, " +
                        "ROUND(SUM(l.days),1) totalDays, " +
                        "ROUND(SUM(CASE WHEN l.leave_type='病假' THEN l.days ELSE 0 END),1) sickDays, " +
                        "ROUND(SUM(CASE WHEN l.leave_type='事假' THEN l.days ELSE 0 END),1) personalDays, " +
                        "ROUND(SUM(CASE WHEN l.leave_type='公假' THEN l.days ELSE 0 END),1) publicDays, " +
                        "COUNT(*) cnt " +
                        "FROM leave_record l JOIN student s ON s.id=l.student_id " +
                        "WHERE l.user_id=? AND l.start_date BETWEEN ? AND ? GROUP BY l.student_id ORDER BY sickDays DESC",
                uid, range[0], range[1]);
        double warnDays;
        try {
            warnDays = Double.parseDouble(Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k='sick_leave_warn_days'", String.class, uid)));
        } catch (Exception e) {
            warnDays = 20;
        }
        for (Map<String, Object> r : rows) {
            r.put("warn", ((Number) r.get("sickDays")).doubleValue() >= warnDays);
        }
        LocalDate today = LocalDate.now();
        Map<String, Object> stat = new LinkedHashMap<>();
        stat.put("students", rows);
        stat.put("warnDays", warnDays);
        stat.put("activeCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id=? AND status='ACTIVE'", Integer.class, uid));
        stat.put("todayLeaveCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id=? AND status='ACTIVE' AND start_date<=? AND end_date>=?",
                Integer.class, uid, today.toString(), today.toString()));
        stat.put("todayReturnCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id=? AND status='ACTIVE' AND end_date=?",
                Integer.class, uid, today.toString()));
        stat.put("overdueCount", jdbc.queryForObject(
                "SELECT COUNT(*) FROM leave_record WHERE user_id=? AND status='ACTIVE' AND end_date<?",
                Integer.class, uid, today.toString()));
        return Result.ok(stat);
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
