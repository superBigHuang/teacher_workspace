package com.teacher.platform.common;

/**
 * 非管理员访问仅 ADMIN 接口时抛出；由 GlobalExceptionHandler 统一转成
 * 统一包装体 {code:403,msg}（HTTP 状态仍 200，与本项目 Result 风格一致）。
 */
public class AdminRequiredException extends RuntimeException {
    public AdminRequiredException() {
        super("仅管理员可操作");
    }
}
