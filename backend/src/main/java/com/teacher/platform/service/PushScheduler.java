package com.teacher.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 消息推送定时任务：每天早 8:00 对所有开启推送的账号各发两条汇总——
 * ①待办今日到期/已逾期提醒（bizRef=todo-daily:日期）；
 * ②学生成就早报（先幂等 scan 算出隔夜新成就，再汇总过去一天未推过的获奖/升级，
 *   bizRef=ach-daily:日期 整报去重，明细 ach:saId:tier 逐人次去重，重跑/升级不重复推）。
 * 定时线程无请求上下文：uid 从 setting 中 push_enabled='1' 的账号取，逐用户显式传参；
 * 单用户/单事件异常只记日志，不影响其他用户与事件。
 */
@Service
public class PushScheduler {

    private static final Logger log = LoggerFactory.getLogger(PushScheduler.class);
    private static final Map<String, String> TIER_EMOJI = Map.of("BRONZE", "🥉", "SILVER", "🥈", "GOLD", "🥇");

    private final JdbcTemplate jdbc;
    private final PushService push;
    private final AchievementScanner scanner;
    private final TermStatsService termStats;

    public PushScheduler(JdbcTemplate jdbc, PushService push,
                         AchievementScanner scanner, TermStatsService termStats) {
        this.jdbc = jdbc;
        this.push = push;
        this.scanner = scanner;
        this.termStats = termStats;
    }

    /** 每天早 8:00 汇总推送（cron：秒 分 时） */
    @Scheduled(cron = "0 0 8 * * ?")
    public void dailyPush() {
        List<Long> uids;
        try {
            uids = jdbc.queryForList(
                    "SELECT DISTINCT user_id FROM setting WHERE k='push_enabled' AND v='1'", Long.class);
        } catch (Exception e) {
            log.warn("推送定时：查询启用账号失败：{}", e.toString());
            return;
        }
        for (Long uid : uids) {
            try {
                pushTodoDaily(uid);
            } catch (Exception e) {
                log.warn("待办推送失败 uid={}: {}", uid, e.toString());
            }
            try {
                pushAchievementDaily(uid);
            } catch (Exception e) {
                log.warn("成就推送失败 uid={}: {}", uid, e.toString());
            }
        }
    }

    /**
     * 待办汇总：未完成且截止日期 <= 今天的待办，分「今日到期」「已逾期」两组拼一条消息；
     * 两组都空则不发。bizRef=todo-daily:今天，每天每渠道只发一次。
     */
    private void pushTodoDaily(Long uid) {
        if (!push.eventOn(uid, "todo")) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, due_date FROM todo WHERE user_id=? AND done=0 " +
                        "AND due_date IS NOT NULL AND due_date <= CURDATE() ORDER BY due_date", uid);
        List<String> today = new ArrayList<>();
        List<String> overdue = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String title = String.valueOf(r.get("title"));
            boolean isToday = LocalDate.now().toString().equals(String.valueOf(r.get("due_date")));
            (isToday ? today : overdue).add(title);
        }
        if (today.isEmpty() && overdue.isEmpty()) return;

        StringBuilder sb = new StringBuilder("📋 班主任待办提醒\n");
        if (!today.isEmpty()) {
            sb.append("【今日到期】").append(numbered(today)).append("\n");
        }
        if (!overdue.isEmpty()) {
            sb.append("【已逾期】").append(numbered(overdue)).append("（共 ").append(overdue.size()).append(" 条）");
        }
        push.pushToUser(uid, "班主任待办提醒", sb.toString().trim(), "todo-daily:" + LocalDate.now());
    }

    /**
     * 成就早报：先 scanner.scan（幂等，把隔夜新达标/升级算出来），再查本学期
     * awarded_at 在昨天/今天的获奖记录，过滤掉 push_log 中已推过的 ach:saId:tier 明细，
     * 汇总一条推送；无新成就不发。整报 bizRef=ach-daily:日期 防同日重发，
     * 明细键 ach:saId:tier 随发送成功落库（升级 tier 变=新键，会再推一次）。
     */
    private void pushAchievementDaily(Long uid) {
        if (!push.eventOn(uid, "achievement")) return;
        scanner.scan(uid);   // 幂等扫描：人不在电脑前时由定时任务补算隔夜新成就

        String[] range = termStats.termRange(uid);
        String term = range[0] + "~" + range[1];
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT sa.id sid, sa.tier, s.name sname, d.name dname, d.icon " +
                        "FROM student_achievement sa " +
                        "JOIN student s ON s.id=sa.student_id " +
                        "JOIN achievement_def d ON d.id=sa.def_id " +
                        "WHERE sa.user_id=? AND sa.term=? " +
                        "AND sa.awarded_at >= DATE_SUB(CURDATE(), INTERVAL 1 DAY)", uid, term);

        // 已推明细（渠道无关：任一渠道推过即视为推过，避免重复打扰）
        Set<String> pushed = new HashSet<>();
        jdbc.queryForList("SELECT DISTINCT biz_ref FROM push_log WHERE user_id=? AND biz_ref LIKE 'ach:%'", uid)
                .forEach(r -> pushed.add(String.valueOf(r.get("biz_ref"))));

        List<String> lines = new ArrayList<>();
        List<String> extraRefs = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String tier = r.get("tier") == null ? "" : String.valueOf(r.get("tier"));
            String ref = "ach:" + r.get("sid") + ":" + tier;
            if (pushed.contains(ref)) continue;
            String emoji = tier.isBlank() ? String.valueOf(r.get("icon")) : TIER_EMOJI.getOrDefault(tier, "🏅");
            lines.add(r.get("sname") + " " + r.get("dname") + (emoji == null || "null".equals(emoji) ? "🏅" : emoji));
            extraRefs.add(ref);
        }
        if (lines.isEmpty()) return;

        String content = "🏅 班主任成就早报\n过去一天新成就：\n" + String.join("\n", lines)
                + "\n（共 " + lines.size() + " 人次）";
        push.pushToUser(uid, "班主任成就早报", content, "ach-daily:" + LocalDate.now(), extraRefs);
    }

    /** 编号列表：①②…（超 20 条回退 1. 2. 数字编号） */
    private String numbered(List<String> items) {
        String[] circled = {"①","②","③","④","⑤","⑥","⑦","⑧","⑨","⑩",
                "⑪","⑫","⑬","⑭","⑮","⑯","⑰","⑱","⑲","⑳"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(i < circled.length ? circled[i] : (i + 1) + ".").append(items.get(i));
        }
        return sb.toString();
    }
}
