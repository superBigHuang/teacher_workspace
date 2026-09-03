package com.teacher.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 中学班主任工作平台 后端启动类
 */
@SpringBootApplication
@EnableScheduling   // 开启定时任务（消息推送早 8 点汇总，见 PushScheduler）
public class PlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApplication.class, args);
    }
}
