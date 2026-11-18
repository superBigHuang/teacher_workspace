package com.teacher.platform.config.datasource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 数据源路由拦截器：优先级最高。
 * 根据请求头 X-Datasource、访问域名 Host、或 URL query 参数动态判定数据源。
 */
@Component
public class DataSourceInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行 OPTIONS 请求，但同样保证上下文干净
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 1. 优先读取显式请求头 X-Datasource: demo / master
        String headerDs = request.getHeader("X-Datasource");
        if (headerDs != null && !headerDs.isBlank()) {
            if ("demo".equalsIgnoreCase(headerDs.trim())) {
                DataSourceContext.set(DataSourceContext.DEMO);
                return true;
            } else if ("master".equalsIgnoreCase(headerDs.trim())) {
                DataSourceContext.set(DataSourceContext.MASTER);
                return true;
            }
        }

        // 2. 检查 URL 参数 ?env=demo（方便本地与浏览器直链调试）
        String envParam = request.getParameter("env");
        if ("demo".equalsIgnoreCase(envParam) || "dev".equalsIgnoreCase(envParam)) {
            DataSourceContext.set(DataSourceContext.DEMO);
            return true;
        }

        // 3. 检查 Host / X-Forwarded-Host（线上多域名反代识别）
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host != null && (host.toLowerCase().contains("demo") || host.toLowerCase().contains("dev"))) {
            DataSourceContext.set(DataSourceContext.DEMO);
            return true;
        }

        // 4. 默认走正式库 master
        DataSourceContext.set(DataSourceContext.MASTER);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 请求结束，务必清理 ThreadLocal，防止线程复用引发污染
        DataSourceContext.clear();
    }
}
