package com.teacher.platform.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宠物园纯逻辑单测（不启动 Spring、不连数据库）：
 * 破壳跨天判断 {@link PetService#isHatchDue} 与破壳等级结算 {@link PetService#levelOf}。
 */
class PetServiceTest {

    @Test
    void isHatchDue_今天领养_未到期() {
        LocalDate today = LocalDate.of(2026, 9, 2);
        // 今天任意时刻领养（凌晨 0 点到深夜 23:59）都不算到期，需跨自然日
        assertFalse(PetService.isHatchDue(today.atStartOfDay(), today));
        assertFalse(PetService.isHatchDue(today.atTime(23, 59, 59), today));
    }

    @Test
    void isHatchDue_昨天及更早领养_已到期() {
        LocalDate today = LocalDate.of(2026, 9, 2);
        // 昨天深夜领养，今天首次打开即到期（只看日期，不看具体时分）
        assertTrue(PetService.isHatchDue(today.minusDays(1).atTime(23, 59), today));
        assertTrue(PetService.isHatchDue(today.minusDays(3).atTime(8, 0), today));
    }

    @Test
    void isHatchDue_未来时间或空值_不到期() {
        LocalDate today = LocalDate.of(2026, 9, 2);
        assertFalse(PetService.isHatchDue(today.plusDays(1).atStartOfDay(), today));
        assertFalse(PetService.isHatchDue(null, today));
    }

    @Test
    void levelOf_破壳按累计exp结算等级() {
        // 蛋期间攒的成长值，破壳时一次性结算：[0,40)=Lv1 [40,100)=Lv2 ... [280+]=Lv5
        assertEquals(1, PetService.levelOf(0));
        assertEquals(1, PetService.levelOf(39));
        assertEquals(2, PetService.levelOf(40));
        assertEquals(3, PetService.levelOf(100));
        assertEquals(4, PetService.levelOf(180));
        assertEquals(5, PetService.levelOf(280));
    }
}
