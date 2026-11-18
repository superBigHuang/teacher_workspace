package com.teacher.platform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 测试库定时重置调度器：
 * 1. 启动时检查：若 demo 库未初始化，自动灌装初始数据；
 * 2. 每天凌晨 04:00 定时巡检：若上次重置已超过 7 天，自动执行全量高保真数据重置。
 */
@Service
@Order(100) // 确保在 DataInitializer 之后运行
public class DemoResetScheduler implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoResetScheduler.class);

    private final DemoDataSeeder demoDataSeeder;

    public DemoResetScheduler(DemoDataSeeder demoDataSeeder) {
        this.demoDataSeeder = demoDataSeeder;
    }

    @Override
    public void run(String... args) {
        try {
            demoDataSeeder.seedIfEmpty();
        } catch (Exception e) {
            log.warn("【测试库】启动初始化检查失败（若无需测试库可忽略）：{}", e.getMessage());
        }
    }

    /** 每天凌晨 04:00 执行定时检查 */
    @Scheduled(cron = "0 0 4 * * ?")
    public void dailyCheckReset() {
        long last = demoDataSeeder.getLastResetTime();
        long now = System.currentTimeMillis();
        if (last > 0 && (now - last) >= DemoDataSeeder.RESET_INTERVAL_MS) {
            log.info("【测试库】距离上次重置已满 7 天，触发凌晨自动全量重置...");
            try {
                demoDataSeeder.reset(true);
            } catch (Exception e) {
                log.error("【测试库】定时重置失败：{}", e.getMessage(), e);
            }
        } else {
            long remainHours = Math.max(0, (DemoDataSeeder.RESET_INTERVAL_MS - (now - last)) / (3600 * 1000L));
            log.info("【测试库】当前重置周期未满 7 天，距下次重置约 {} 小时", remainHours);
        }
    }
}
