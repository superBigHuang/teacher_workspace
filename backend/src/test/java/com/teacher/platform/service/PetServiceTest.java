package com.teacher.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宠物园纯逻辑单测（不启动 Spring、不连数据库）：
 * 孵化经验阈值 {@link PetService#HATCH_EXP} 与破壳/升级等级结算 {@link PetService#levelOf}。
 */
class PetServiceTest {

    @Test
    void hatchExp_攒够20才破壳且破壳即Lv1() {
        // 孵化阈值 20：低于 Lv2 的 40，保证破壳时按累计经验结算为 Lv1（先破壳再继续成长升级）
        assertEquals(20, PetService.HATCH_EXP);
        assertTrue(PetService.HATCH_EXP < 40);
        // 破壳那一刻按累计 exp 结算等级：19、20 分都还是 Lv1
        assertEquals(1, PetService.levelOf(PetService.HATCH_EXP - 1));
        assertEquals(1, PetService.levelOf(PetService.HATCH_EXP));
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
