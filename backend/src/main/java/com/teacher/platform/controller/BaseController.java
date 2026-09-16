package com.teacher.platform.controller;

import com.teacher.platform.common.AdminRequiredException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Controller 基类：提供当前登录账号 id 与角色（数据隔离、接口鉴权用）。
 * 拦截器校验 JWT 后把 uid/role 写入请求属性，这里经 RequestContextHolder 取出，
 * 业务方法无需在签名上声明 HttpServletRequest。
 */
public abstract class BaseController {

    /** 当前登录账号 id；取不到返回 null */
    protected Long uid() {
        Object o = attr("uid");
        return o == null ? null : ((Number) o).longValue();
    }

    /** 当前登录账号角色：ADMIN/TEACHER；取不到返回 null */
    protected String role() {
        Object o = attr("role");
        return o == null ? null : String.valueOf(o);
    }

    /** 仅管理员可继续；非 ADMIN 抛 AdminRequiredException（全局处理器转 code=403） */
    protected void requireAdmin() {
        if (!"ADMIN".equals(role())) throw new AdminRequiredException();
    }

    private Object attr(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getAttribute(name);
        }
        return null;
    }
}
