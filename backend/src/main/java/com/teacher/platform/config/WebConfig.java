package com.teacher.platform.config;

import com.teacher.platform.common.AuthInterceptor;
import com.teacher.platform.config.datasource.DataSourceInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web 配置：跨域 + 数据源路由拦截器 + 登录拦截器 + 上传目录静态映射
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final DataSourceInterceptor dataSourceInterceptor;
    private final AuthInterceptor authInterceptor;

    /** 本地上传目录（自定义皮肤图片），/uploads/** 直接访问 */
    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    public WebConfig(DataSourceInterceptor dataSourceInterceptor, AuthInterceptor authInterceptor) {
        this.dataSourceInterceptor = dataSourceInterceptor;
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 相对路径基于 JVM 工作目录，转绝对路径避免解析歧义；file: 位置必须以 / 结尾
        String abs = new File(uploadDir).getAbsolutePath().replace('\\', '/');
        registry.addResourceHandler("/uploads/**").addResourceLocations("file:" + abs + "/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 数据源路由拦截器最先执行（拦截所有请求），确定当前线程连接哪个数据库
        registry.addInterceptor(dataSourceInterceptor)
                .addPathPatterns("/**")
                .order(Ordered.HIGHEST_PRECEDENCE);

        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // /remote/events 走一次性 ticket 鉴权（EventSource 无法带 Authorization 头），不在 JWT 拦截范围
                // /auth/captcha 为公开的登录验证码图；/auth/demo/** 为测试区状态/重置公开端点
                .excludePathPatterns("/api/auth/login", "/api/auth/captcha", "/api/remote/events", "/api/auth/demo/**")
                .order(Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
