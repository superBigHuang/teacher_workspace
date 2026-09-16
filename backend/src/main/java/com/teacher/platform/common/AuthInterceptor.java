package com.teacher.platform.common;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
import java.util.Map;

/**
 * 登录拦截器：校验 Authorization: Bearer <token>。
 * JWT 验真后再按主键查一次 sys_user：账号已删除（理论不存在）或被停用 → 401，
 * 停用因此即时生效（不做 token 黑名单）；role 一并塞入请求属性供接口鉴权。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;
    private final JdbcTemplate jdbc;

    public AuthInterceptor(JwtUtil jwtUtil, JdbcTemplate jdbc) {
        this.jwtUtil = jwtUtil;
        this.jdbc = jdbc;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 放行预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            Claims claims = jwtUtil.parse(auth.substring(7));
            if (claims != null) {
                // 统一转 Long（jjwt 对小整数可能返回 Integer）
                Object uidRaw = claims.get("uid");
                Long uid = uidRaw == null ? null : ((Number) uidRaw).longValue();
                if (uid != null) {
                    List<Map<String, Object>> rows = jdbc.queryForList(
                            "SELECT role, enabled FROM sys_user WHERE id=?", uid);
                    if (!rows.isEmpty() && CurrentUser.isEnabled(rows.get(0).get("enabled"))) {
                        request.setAttribute("uid", uid);
                        request.setAttribute("username", claims.getSubject());
                        request.setAttribute("role", String.valueOf(rows.get(0).get("role")));
                        return true;
                    }
                    // token 有效但账号已停用：给出明确文案（前端按 401 清登录态）
                    return deny(response, "账号已停用，请联系管理员");
                }
            }
        }
        return deny(response, "未登录或登录已过期");
    }

    /** 写出 401 统一 JSON 错误体 */
    private boolean deny(HttpServletResponse response, String msg) throws Exception {
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"msg\":\"" + msg + "\",\"data\":null}");
        return false;
    }
}
