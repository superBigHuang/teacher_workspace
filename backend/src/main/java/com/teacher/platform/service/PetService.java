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

    /** 一次成长值变动的结果：affected=实际变动的宠物数；levelUp=本次触发的升级事件（无则 null） */
    public record AwardResult(int affected, LevelUp levelUp) {}

    /** 升级事件（前端全屏进化演出用；skinKey/fxTheme 决定演出主题与形态图） */
    public record LevelUp(Long studentId, String name, String petName, int fromLevel, int toLevel,
                          String skinKey, String fxTheme) {}

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

    /** 领养宠物（一个学生一只，重复领养返回 false）；petKey 为皮肤 skin_key，自定义皮肤须归属本账号 */
    public boolean adopt(Long uid, Long studentId, String petKey, String petName) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pet WHERE user_id=? AND student_id=?", Integer.class, uid, studentId);
        if (exists != null && exists > 0) return false;
        String key = petKey == null || petKey.isBlank() ? "dragon" : petKey;
        if (!"dragon".equals(key)) {
            // 自定义皮肤：内置行 user_id 为 NULL，自定义行须属于当前账号；校验不过回退内置云龙
            Integer ok = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pet_skin WHERE skin_key=? AND (user_id IS NULL OR user_id=?)",
                    Integer.class, key, uid);
            if (ok == null || ok == 0) key = "dragon";
        }
        // v1.2：领养后先是一颗孵化中的蛋（hatched=0），次日破壳；level 初始 1，破壳时按累计 exp 结算
        jdbc.update("INSERT INTO pet(user_id,student_id,pet_key,pet_name,level,exp,hatched,adopted_at) VALUES(?,?,?, ?,1,0,0,?)",
                uid, studentId, key,
                petName == null || petName.isBlank() ? "云小龙" : petName, LocalDateTime.now());
        return true;
    }

    /** 是否到破壳时间：领养日期早于今天（跨自然日）。今天领养=false，昨天及更早=true */
    public static boolean isHatchDue(LocalDateTime adoptedAt, LocalDate today) {
        return adoptedAt != null && adoptedAt.toLocalDate().isBefore(today);
    }

    /**
     * 破壳：孵化中的蛋到期（跨自然日）则置 hatched=1，并按蛋期间累计 exp 一次性结算等级。
     * 跨天校验交给 SQL WHERE（DATE(adopted_at)&lt;CURDATE()），与待播查询同一条件，避免时区/类型歧义。
     *
     * @param forceDev true=跳过跨天校验（仅开发/E2E 开关 app.pet.dev-hatch，生产恒为 false）
     * @return true=本次发生破壳
     */
    public boolean hatch(Long uid, Long studentId, boolean forceDev) {
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,exp,hatched FROM pet WHERE user_id=? AND student_id=?", uid, studentId);
        if (pets.isEmpty()) return false;
        Map<String, Object> p = pets.get(0);
        if (((Number) p.get("hatched")).intValue() != 0) return false;   // 已破壳，幂等
        int level = levelOf(((Number) p.get("exp")).intValue());
        Long petId = ((Number) p.get("id")).longValue();
        String sql = forceDev
                ? "UPDATE pet SET hatched=1, level=? WHERE id=? AND hatched=0"
                : "UPDATE pet SET hatched=1, level=? WHERE id=? AND hatched=0 AND DATE(adopted_at)<CURDATE()";
        return jdbc.update(sql, level, petId) > 0;
    }

    /**
     * 成长值变动（评价/自动联动/发奖统一入口）。
     *
     * @param ruleId 手动评价项 pet_rule.id（自动联动传 null）
     * @param score  分值（带正负；ruleId 非空时取评价项分值，此参传 null）
     * @param source MANUAL / AUTO_ATTENDANCE / AUTO_HOMEWORK / AUTO_DISCIPLINE / AUTO_SCORE
     * @param bizRef 业务幂等键（如 "attendance:123"、"exam:5:学生id"）；非空时同 source+bizRef 已存在则跳过
     * @param remark 流水备注/事项名
     * @return 变动结果（affected=实际变动宠物数，0=未领养或幂等跳过；levelUp=本次升级事件，无则 null）。
     *         同步路径（MANUAL/AUTO_SCORE，老师在线操作）升级信息随响应返回立即演出；
     *         其余自动联动路径（考勤/作业/纪律，发生在别的页面）置 evolve_pending=1，下次入座次表页补播。
     */
    public AwardResult award(Long uid, Long studentId, Long ruleId, Integer score, String source, String bizRef, String remark) {
        if (bizRef != null) {
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pet_log WHERE user_id=? AND source=? AND biz_ref=?",
                    Integer.class, uid, source, bizRef);
            if (dup != null && dup > 0) return new AwardResult(0, null);
        }
        List<Map<String, Object>> pets = jdbc.queryForList(
                "SELECT id,exp,hatched FROM pet WHERE user_id=? AND student_id=?", uid, studentId);
        if (pets.isEmpty()) return new AwardResult(0, null);   // 未领养：不写流水、不补历史分

        int delta;
        String logRemark = remark;
        if (ruleId != null) {
            List<Map<String, Object>> rules = jdbc.queryForList(
                    "SELECT score,name,enabled FROM pet_rule WHERE id=? AND user_id=?", ruleId, uid);
            if (rules.isEmpty()) return new AwardResult(0, null);
            Map<String, Object> rule = rules.get(0);
            if (((Number) rule.get("enabled")).intValue() == 0) return new AwardResult(0, null);
            delta = ((Number) rule.get("score")).intValue();
            if (logRemark == null || logRemark.isBlank()) logRemark = String.valueOf(rule.get("name"));
        } else {
            delta = score == null ? 0 : score;
        }
        if (delta == 0) return new AwardResult(0, null);

        String src = source == null ? "MANUAL" : source;
        jdbc.update("INSERT INTO pet_log(user_id,student_id,rule_id,score,source,biz_ref,remark) VALUES(?,?,?,?,?,?,?)",
                uid, studentId, ruleId, delta, src, bizRef, logRemark);
        Long petId = ((Number) pets.get(0).get("id")).longValue();
        int oldExp = ((Number) pets.get(0).get("exp")).intValue();
        // 扣分下限 0、加分满级封顶 280
        int newExp = Math.max(0, Math.min(MAX_EXP, oldExp + delta));
        if (((Number) pets.get(0).get("hatched")).intValue() == 0) {
            // 孵化中（蛋）：成长值照常累计（蛋在吸收能量，飘字/流水正常），但等级保持 1、不进化、
            // 不产生 LevelUp、不置 evolve_pending（蛋里看不见进化）；破壳时按累计 exp 一次性结算
            jdbc.update("UPDATE pet SET exp=? WHERE id=?", newExp, petId);
            return new AwardResult(1, null);
        }
        // 已破壳：等级按新 exp 重算（扣分不降级由 clamp 下限保证）
        int oldLevel = levelOf(oldExp);
        int newLevel = levelOf(newExp);
        jdbc.update("UPDATE pet SET exp=?, level=? WHERE id=?", newExp, newLevel, petId);
        if (newLevel > oldLevel) {
            LevelUp lu = loadLevelUp(uid, studentId, oldLevel, newLevel);
            boolean sync = "MANUAL".equals(src) || "AUTO_SCORE".equals(src);
            if (!sync) {
                // 跨页面升级：置待演标记，座次表页加载时补播（演完调 evolve-seen 清除）
                jdbc.update("UPDATE pet SET evolve_pending=1 WHERE id=?", petId);
            }
            return new AwardResult(1, lu);
        }
        return new AwardResult(1, null);
    }

    /** 组装升级演出信息（学生名/宠物名/皮肤/特效主题） */
    private LevelUp loadLevelUp(Long uid, Long studentId, int fromLevel, int toLevel) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.name sname, p.pet_name pname, p.pet_key skinKey, COALESCE(sk.fx_theme,'gold') fxTheme " +
                        "FROM pet p JOIN student s ON s.id=p.student_id " +
                        "LEFT JOIN pet_skin sk ON sk.skin_key=p.pet_key " +
                        "WHERE p.user_id=? AND p.student_id=?", uid, studentId);
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        String petName = r.get("pname") == null ? "云小龙" : String.valueOf(r.get("pname"));
        return new LevelUp(studentId, String.valueOf(r.get("sname")), petName,
                fromLevel, toLevel, String.valueOf(r.get("skinKey")), String.valueOf(r.get("fxTheme")));
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
                "SELECT id,exp,hatched FROM pet WHERE user_id=? AND student_id=?", uid, sid);
        if (pets.isEmpty()) return;
        Long petId = ((Number) pets.get(0).get("id")).longValue();
        int exp = ((Number) pets.get(0).get("exp")).intValue();
        int newExp = Math.max(0, Math.min(MAX_EXP, exp - delta));
        if (((Number) pets.get(0).get("hatched")).intValue() == 0) {
            // 蛋中撤回：只回退成长值，等级保持 1（破壳时才按累计 exp 结算）
            jdbc.update("UPDATE pet SET exp=? WHERE id=?", newExp, petId);
            return;
        }
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
        // 毕业有独立仪式弹窗，不走进化演出：清待演标记；轮回从一颗新蛋开始（hatched=0，再孵一天）
        jdbc.update("UPDATE pet SET level=1, exp=0, hatched=0, graduated_count=graduated_count+1, evolve_pending=0 WHERE id=?",
                ((Number) pet.get("id")).longValue());
        return true;
    }

    /**
     * 全班当日状态标记（纯视觉，次日自动翻篇）：
     * dizzy=当日有扣分流水（前端表现为小乌云下雨）；frozen=当日有扣分且 exp 已被扣到 0；
     * broken=近 7 天扣分绝对值累计 ≥100；starToday=当日正分流水合计 ≥5（今日之星，正向状态）。
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
        // 今日之星：当日加分合计 ≥5
        jdbc.queryForList(
                "SELECT student_id sid, SUM(score) t FROM pet_log " +
                        "WHERE user_id=? AND score>0 AND DATE(created_at)=? GROUP BY student_id",
                uid, today).forEach(r -> {
            if (((Number) r.get("t")).intValue() >= 5) {
                Long sid = ((Number) r.get("sid")).longValue();
                flags.computeIfAbsent(sid, k -> new HashSet<>()).add("starToday");
            }
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
