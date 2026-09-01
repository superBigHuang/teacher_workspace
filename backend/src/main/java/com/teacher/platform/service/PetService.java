package com.teacher.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 班级宠物园核心逻辑：领养、成长值加减（升级/扣分保护）、毕业、撤回、自动联动幂等、负面状态。
 * 升级曲线（累计 exp）：Lv2=40、Lv3=100、Lv4=180、Lv5=280；
 * 扣分 exp 下限 0 且不降级；biz_ref 幂等防止自动联动重复扣分；未领养学生不产生积分。
 */
@Service
public class PetService {

    private final JdbcTemplate jdbc;

    public PetService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 满级累计成长值 */
    public static final int MAX_EXP = 280;

    /** 等级由累计 exp 推导：[0,40)=Lv1 [40,100)=Lv2 [100,180)=Lv3 [180,280)=Lv4 [280+]=Lv5 */
    public static int levelOf(int exp) {
        if (exp >= 280) return 5;
        if (exp >= 180) return 4;
        if (exp >= 100) return 3;
        if (exp >= 40) return 2;
        return 1;
    }

    /** 本级成长区间 [起点, 阈值)，前端进度条用；Lv5 已满级返回 [280,280] */
    public static int[] levelRange(int level) {
        return switch (level) {
            case 2 -> new int[]{40, 100};
            case 3 -> new int[]{100, 180};
            case 4 -> new int[]{180, 280};
            case 5 -> new int[]{280, 280};
            default -> new int[]{0, 40};
        };
    }

    /** 领养宠物（一个学生一只，重复领养返回 false） */
    public boolean adopt(Long uid, Long studentId, String petKey, String petName) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pet WHERE user_id=? AND student_id=?", Integer.class, uid, studentId);
        if (exists != null && exists > 0) return false;
        jdbc.update("INSERT INTO pet(user_id,student_id,pet_key,pet_name,level,exp,adopted_at) VALUES(?,?,?, ?,1,0,?)",
                uid, studentId, petKey == null || petKey.isBlank() ? "dragon" : petKey,
                petName == null || petName.isBlank() ? "云小龙" : petName, LocalDateTime.now());
        return true;
    }

    /**
     * 成长值变动（评价/自动联动/发奖统一入口）。
     *
     * @param ruleId 手动评价项 pet_rule.id（自动联动传 null）
     * @param score  分值（带正负；ruleId 非空时取评价项分值，此参传 null）
     * @param source MANUAL / AUTO_ATTENDANCE / AUTO_HOMEWORK / AUTO_DISCIPLINE / AUTO_SCORE
     * @param bizRef 业务幂等键（如 "attendance:123"、"exam:5:学生id"）；非空时同 source+bizRef 已存在则跳过
     * @param remark 流水备注/事项名
     * @return 实际变动的宠物数（0=未领养或幂等跳过）
     */
    public int award(Long uid, Long studentId, Long ruleId, Integer score, String source, String bizRef, String remark) {
        if (bizRef != null) {
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pet_log WHERE user_id=? AND source=? AND biz_ref=?",
                    Integer.class, uid, source, bizRef);
            if (dup != null && dup > 0) return 0;
        }
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,exp FROM pet WHERE user_id=? AND student_id=?", uid, studentId);
        if (pets.isEmpty()) return 0;   // 未领养：不写流水、不补历史分

        int delta;
        String logRemark = remark;
        if (ruleId != null) {
            List<Map<String, Object>> rules = jdbc.queryForList(
                    "SELECT score,name,enabled FROM pet_rule WHERE id=? AND user_id=?", ruleId, uid);
            if (rules.isEmpty()) return 0;
            Map<String, Object> rule = rules.get(0);
            if (((Number) rule.get("enabled")).intValue() == 0) return 0;
            delta = ((Number) rule.get("score")).intValue();
            if (logRemark == null || logRemark.isBlank()) logRemark = String.valueOf(rule.get("name"));
        } else {
            delta = score == null ? 0 : score;
        }
        if (delta == 0) return 0;

        jdbc.update("INSERT INTO pet_log(user_id,student_id,rule_id,score,source,biz_ref,remark) VALUES(?,?,?,?,?,?,?)",
                uid, studentId, ruleId, delta, source == null ? "MANUAL" : source, bizRef, logRemark);
        Long petId = ((Number) pets.get(0).get("id")).longValue();
        int oldExp = ((Number) pets.get(0).get("exp")).intValue();
        // 扣分下限 0、加分满级封顶 280；等级按新 exp 重算（扣分不降级由 clamp 下限保证）
        int newExp = Math.max(0, Math.min(MAX_EXP, oldExp + delta));
        jdbc.update("UPDATE pet SET exp=?, level=? WHERE id=?", newExp, levelOf(newExp), petId);
        return 1;
    }

    /** 撤回一条流水：删除该 log 并回退对应 exp（同样遵守下限 0/封顶 280） */
    public void revokeLog(Long uid, Long logId) {
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT id,student_id,score FROM pet_log WHERE id=? AND user_id=?", logId, uid);
        if (logs.isEmpty()) return;
        Map<String, Object> log = logs.get(0);
        Long sid = ((Number) log.get("student_id")).longValue();
        int delta = ((Number) log.get("score")).intValue();
        jdbc.update("DELETE FROM pet_log WHERE id=? AND user_id=?", logId, uid);
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,exp FROM pet WHERE user_id=? AND student_id=?", uid, sid);
        if (pets.isEmpty()) return;
        Long petId = ((Number) pets.get(0).get("id")).longValue();
        int exp = ((Number) pets.get(0).get("exp")).intValue();
        int newExp = Math.max(0, Math.min(MAX_EXP, exp - delta));
        jdbc.update("UPDATE pet SET exp=?, level=? WHERE id=?", newExp, levelOf(newExp), petId);
    }

    /**
     * 作业收缴保存后的宠物分同步：某日某科"未交"名单全量覆盖提交。
     * biz_ref 用稳定键 hw:日期:科目:学生Id（不依赖 homework_check 自增 id，因保存先删后插）；
     * 新进入未交名单的扣 -1，已补交（不在新名单）的回退扣分。
     */
    public void syncHomework(Long uid, String date, String subject, Collection<Long> missingIds) {
        String prefix = "hw:" + date + ":" + subject + ":";
        Map<Long, Long> existing = new HashMap<>();   // studentId -> pet_log.id
        jdbc.queryForList(
                "SELECT id, biz_ref FROM pet_log WHERE user_id=? AND source='AUTO_HOMEWORK' AND biz_ref LIKE ?",
                uid, prefix + "%").forEach(r -> {
            String ref = String.valueOf(r.get("biz_ref"));
            try {
                existing.put(Long.parseLong(ref.substring(prefix.length())),
                        ((Number) r.get("id")).longValue());
            } catch (Exception ignored) {}
        });
        Set<Long> newSet = new HashSet<>(missingIds);
        // 不再未交 → 回退
        existing.forEach((sid, logId) -> {
            if (!newSet.contains(sid)) revokeLog(uid, logId);
        });
        // 新未交 → 扣分（幂等）
        for (Long sid : newSet) {
            if (!existing.containsKey(sid)) {
                award(uid, sid, null, -1, "AUTO_HOMEWORK", prefix + sid, "作业未交(" + subject + ")");
            }
        }
    }

    /**
     * 业务记录删除时联动回退：删除指定 source+bizRef 的自动流水并逐条回退 exp。
     * （考勤/作业/纪律记录被删时保持宠物分与业务数据一致）
     */
    public void rollbackBiz(Long uid, String source, String bizRef) {
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT id FROM pet_log WHERE user_id=? AND source=? AND biz_ref=?", uid, source, bizRef);
        for (Map<String, Object> l : logs) {
            revokeLog(uid, ((Number) l.get("id")).longValue());
        }
    }

    /**
     * 毕业：仅 Lv5（exp 满 280）可毕业。
     * 毕业徽章 +1（pet_badge 记录宠物名），宠物重置为 Lv1/exp0 重新成长（宠物名保留）。
     * @return true=毕业成功
     */
    public boolean graduate(Long uid, Long studentId) {
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,pet_key,pet_name,level FROM pet WHERE user_id=? AND student_id=?", uid, studentId);
        if (pets.isEmpty()) return false;
        Map<String, Object> pet = pets.get(0);
        if (((Number) pet.get("level")).intValue() < 5) return false;
        jdbc.update("INSERT INTO pet_badge(user_id,student_id,pet_key,pet_name) VALUES(?,?,?,?)",
                uid, studentId, pet.get("pet_key"), pet.get("pet_name"));
        jdbc.update("UPDATE pet SET level=1, exp=0, graduated_count=graduated_count+1 WHERE id=?",
                ((Number) pet.get("id")).longValue());
        return true;
    }

    /**
     * 全班负面状态标记（纯视觉，次日自动翻篇）：
     * dizzy=当日有扣分流水；frozen=当日有扣分且 exp 已被扣到 0；
     * broken=近 7 天扣分绝对值累计 ≥100。
     * 返回 studentId -> 状态集合。
     */
    public Map<Long, Set<String>> statusFlags(Long uid) {
        Map<Long, Set<String>> flags = new HashMap<>();
        LocalDate today = LocalDate.now();
        // 当日扣分
        jdbc.queryForList(
                "SELECT student_id sid FROM pet_log WHERE user_id=? AND score<0 AND DATE(created_at)=?",
                uid, today).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            flags.computeIfAbsent(sid, k -> new HashSet<>()).add("dizzy");
        });
        // 近 7 天扣分累计（绝对值）
        Map<Long, Integer> weekDeduct = new HashMap<>();
        jdbc.queryForList(
                "SELECT student_id sid, SUM(score) d FROM pet_log " +
                        "WHERE user_id=? AND score<0 AND created_at >= ? GROUP BY student_id",
                uid, today.atStartOfDay().minusDays(7)).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            weekDeduct.put(sid, (int) Math.abs(((Number) r.get("d")).doubleValue()));
        });
        // frozen / broken 需要结合宠物当前 exp
        jdbc.queryForList("SELECT student_id sid, exp FROM pet WHERE user_id=?", uid).forEach(r -> {
            Long sid = ((Number) r.get("sid")).longValue();
            Set<String> f = flags.computeIfAbsent(sid, k -> new HashSet<>());
            int exp = ((Number) r.get("exp")).intValue();
            if (f.contains("dizzy") && exp == 0) f.add("frozen");
            if (weekDeduct.getOrDefault(sid, 0) >= 100) f.add("broken");
        });
        return flags;
    }

    /** 自动扣分总开关：pet_auto_deduct（默认 1） */
    public boolean autoDeductEnabled(Long uid) {
        try {
            String v = jdbc.queryForObject(
                    "SELECT v FROM setting WHERE user_id=? AND k='pet_auto_deduct'", String.class, uid);
            return !"0".equals(v);
        } catch (Exception e) {
            return true;
        }
    }
}
