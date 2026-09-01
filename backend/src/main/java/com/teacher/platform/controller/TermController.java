package com.teacher.platform.controller;

import com.teacher.platform.common.Result;
import com.teacher.platform.service.TermStatsService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学期素材聚合：评语助手、家长通知单、成长档案共用的数据出口（按账号隔离）
 */
@RestController
@RequestMapping("/api/term")
public class TermController extends BaseController {

    private final TermStatsService termStats;

    public TermController(TermStatsService termStats) {
        this.termStats = termStats;
    }

    /** 全班学期素材（默认班主任班；附带学期起止日期） */
    @GetMapping("/materials")
    public Result<Map<String, Object>> materials(@RequestParam(required = false) Long classId) {
        Long uid = uid();
        Long cid = classId != null ? classId : termStats.homeroomClassId(uid);
        String[] range = termStats.termRange(uid);
        List<Map<String, Object>> students = termStats.materials(uid, cid);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("termStart", range[0]);
        result.put("termEnd", range[1]);
        result.put("classId", cid);
        result.put("students", students);
        return Result.ok(result);
    }

    /** 单生学期素材（成长档案/单人评语生成用） */
    @GetMapping("/material")
    public Result<Map<String, Object>> material(@RequestParam Long studentId) {
        return Result.ok(termStats.material(uid(), studentId));
    }
}
