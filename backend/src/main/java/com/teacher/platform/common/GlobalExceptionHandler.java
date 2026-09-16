package com.teacher.platform.common;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常兜底，返回统一 Result
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 非管理员访问管理接口：业务码 403 */
    @ExceptionHandler(AdminRequiredException.class)
    public Result<Void> handleAdminRequired(AdminRequiredException e) {
        return Result.fail(403, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handle(Exception e) {
        e.printStackTrace();
        return Result.fail(e.getMessage() == null ? "服务器异常" : e.getMessage());
    }
}
