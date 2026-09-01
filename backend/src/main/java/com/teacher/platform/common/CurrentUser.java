package com.teacher.platform.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从当前请求获取登录账号 id（拦截器校验 JWT 后写入 uid 属性）。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** 当前登录账号 id；未登录（理论上被拦截器拦住）返回 null */
    public static Long uid(HttpServletRequest req) {
        Object o = req.getAttribute("uid");
        if (o == null) return null;
        return ((Number) o).longValue();
    }
}
