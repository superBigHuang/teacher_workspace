package com.teacher.platform.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 规则模板引擎（AI 不可用时的保底生成）。
 * 按"总评 → 学业（优异/进步/波动/稳定四档）→ 考勤纪律 → 闪光点 → 期望"组装评语，
 * 每档从句式库随机取句，同班两人不雷同；所有句子只引用素材中真实数据，不编造。
 */
@Service
public class CommentGenerator {

    private final Random random = new Random();

    /** 从句式库随机取一句 */
    private String pick(List<String> list) {
        return list.get(random.nextInt(list.size()));
    }

    private int asInt(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    private double asDouble(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private String asStr(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 生成期末评语（目标 150~250 字，第二人称、鼓励为主）。
     * 分段说明：
     * 1) 总评句开场；2) 学业句按排名/排名变化分四档；3) 考勤纪律句（全勤表扬或温和提醒）；
     * 4) 作业句；5) 闪光点句（职务/值日/家校配合，有才写）；6) 期望句收尾。
     */
    public String comment(Map<String, Object> m) {
        String name = asStr(m, "name");
        StringBuilder sb = new StringBuilder();

        // 1) 总评开场
        sb.append(pick(List.of(
                name + "，本学期你在班级里的点点滴滴老师都一一看在眼里。",
                name + "，一个学期转眼过去，老师想借这份评语和你说几句心里话。",
                name + "，这个学期你的成长与变化，老师都记在心里。")));

        // 2) 学业句（按位次与进退步分档）
        sb.append(studySentence(m));

        // 3) 考勤与纪律
        int late = asInt(m, "late"), absent = asInt(m, "absent"), discipline = asInt(m, "discipline");
        if (late == 0 && absent == 0 && discipline == 0) {
            sb.append(pick(List.of(
                    "考勤上你全勤无迟到，课堂上也能严格要求自己，这份自律特别可贵。",
                    "你坚持按时到校、从不迟到，课堂纪律也让人放心，自律的习惯会让你受益很久。")));
        } else {
            if (late > 0) {
                sb.append(pick(List.of(
                        "美中不足的是本学期你有 " + late + " 次迟到记录，如果能在守时上再严格要求自己，状态一定会更好。",
                        "这学期你迟到了 " + late + " 次，老师替你着急——早睡十分钟，就能换来一整天的从容。")));
            }
            if (discipline > 0) {
                sb.append(pick(List.of(
                        "课堂上你有 " + discipline + " 次纪律小记录，老师知道你性格活泼，若能把这份热情多用在课堂参与上就更好了。",
                        "本学期有 " + discipline + " 次课堂违纪的小插曲，把小聪明用在听讲和思考上，你会更出色。")));
            }
            if (absent > 0) {
                sb.append("本学期缺勤 " + absent + " 次，落下的功课要记得及时补上。");
            }
        }

        // 4) 作业
        int hw = asInt(m, "homeworkMissing");
        if (hw > 0) {
            String subj = asStr(m, "lastMissingSubject");
            sb.append("作业有 ").append(hw).append(" 次未按时上交")
              .append(subj.isEmpty() ? "" : "（最近一次是" + subj + "）")
              .append("，作业是巩固知识的关键，下学期请一定按时完成。");
        } else {
            sb.append(pick(List.of(
                    "作业你总能按时上交，这份踏实值得表扬。",
                    "每次作业你都认真对待、按时提交，坚持下去进步自然水到渠成。")));
        }

        // 5) 闪光点（职务/值日/家校配合，有才写）
        List<String> lights = new ArrayList<>();
        String roles = asStr(m, "roles");
        if (!roles.isEmpty()) {
            lights.add("作为" + roles + "，你认真负责，是老师得力的小助手");
        }
        int duty = asInt(m, "dutyCount");
        if (duty > 0) {
            lights.add(duty + " 次值日任务你都完成得妥妥当当，从不推诿");
        }
        int comm = asInt(m, "commCount");
        if (comm > 0) {
            lights.add("家长一直很关心你的学习，家校配合也是你成长的底气");
        }
        int ach = asInt(m, "achievementCount");
        if (ach > 0) {
            String top = asStr(m, "topAchievement");
            lights.add(top.isEmpty()
                    ? "本学期你收获了 " + ach + " 枚成就徽章，每一枚都是努力的见证"
                    : "本学期你收获了 " + ach + " 枚成就徽章，「" + top + "」更是格外闪亮");
        }
        if (!lights.isEmpty()) {
            sb.append("集体生活中，").append(pick(lights)).append("。");
        }

        // 6) 期望收尾
        sb.append(pick(List.of(
                "希望下学期你继续保持优点，在课堂发言上更主动一些，老师期待看到更闪亮的你！",
                "新学期愿你带着这份认真继续向前，把小不足一个个改掉，老师相信你会带来更多惊喜！",
                "愿你再接再厉，稳住状态、大胆向前，成为更棒的自己！")));
        return sb.toString();
    }

    /** 学业句：前10 优异 / rankChange>0 进步 / <0 波动 / 其余稳定 */
    private String studySentence(Map<String, Object> m) {
        Integer rank = m.get("rank") instanceof Number ? ((Number) m.get("rank")).intValue() : null;
        Integer change = m.get("rankChange") instanceof Number ? ((Number) m.get("rankChange")).intValue() : null;
        String segment = asStr(m, "rankSegment");
        if (rank == null) {
            return "学习上你态度端正，本学期暂时没有完整的考试排名记录，老师相信只要坚持付出就会有收获。";
        }
        if ("前10".equals(segment)) {
            return pick(List.of(
                    "学习上你始终保持优异，最近一次考试总分班级第 " + rank + " 名，是同学们学习的榜样。",
                    "你的学业表现十分出色，总分稳居班级第 " + rank + " 名，扎实功底背后是日复一日的坚持。"));
        }
        if (change != null && change > 0) {
            return pick(List.of(
                    "更让老师惊喜的是你的进步：班级排名比上次提升了 " + change + " 名（现第 " + rank + " 名），这份努力值得肯定。",
                    "学习上你稳扎稳打，排名较上次前进 " + change + " 名，向上的势头老师看在眼里。"));
        }
        if (change != null && change < 0) {
            return "最近一次考试你排在班级第 " + rank + " 名，较上次有所波动，老师相信这只是暂时的，调整状态你一定能迎头赶上。";
        }
        return pick(List.of(
                "学习上你态度端正，成绩处于班级" + segment + "水平（第 " + rank + " 名），保持着自己的节奏。",
                "你的成绩稳定在班级第 " + rank + " 名左右，踏实认真，若能再多点冲劲会更加出色。"));
    }

    /**
     * 生成通知单教师寄语（30~80 字，学生家长都会看到）。
     * 按进退步分档：进步/优异给肯定，波动/中等给鼓励并附一句家长配合建议。
     */
    public String message(Map<String, Object> m) {
        String name = asStr(m, "name");
        Integer rank = m.get("rank") instanceof Number ? ((Number) m.get("rank")).intValue() : null;
        Integer change = m.get("rankChange") instanceof Number ? ((Number) m.get("rankChange")).intValue() : null;
        int hw = asInt(m, "homeworkMissing"), late = asInt(m, "late");
        String segment = asStr(m, "rankSegment");

        if (change != null && change > 0) {
            return name + "本次考试班级第" + rank + "名，较上次进步" + change + "名，势头喜人！请家长多鼓励，孩子定会再接再厉。";
        }
        if ("前10".equals(segment)) {
            return name + "成绩稳居班级前列（第" + rank + "名），是同学的榜样。请家长多给予肯定，期待孩子再攀新高。";
        }
        if (change != null && change < 0) {
            return name + "本次考试班级第" + rank + "名，成绩有波动请别灰心。请家长多关注孩子作业与作息，家校一起帮孩子稳下来。";
        }
        if (hw > 0 || late > 0) {
            return name + "在校表现总体稳定，作业与守时上还有提升空间。请家长在家多督促作业按时完成，感谢配合！";
        }
        return name + "本学期在校表现稳定，感谢家长一学期的配合。假期请督促孩子查漏补缺，期待下学期的进步。";
    }
}
