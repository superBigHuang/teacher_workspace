package com.teacher.platform.controller;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Controller 基类：提供当前登录账号 id（数据隔离用）。
 * 拦截器校验 JWT 后把 uid 写入请求属性，这里经 RequestContextHolder 取出，
 * 业务方法无需在签名上声明 HttpServletRequest。
 */
public abstract class BaseController {

    /** 当前登录账号 id；取不到返回 null */
    protected Long uid() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object o = sra.getRequest().getAttribute("uid");
            return o == null ? null : ((Number) o).longValue();
        }
        return null;
    }
}
