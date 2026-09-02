package com.teacher.platform.config;

import com.teacher.platform.common.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web 配置：跨域 + 登录拦截器 + 上传目录静态映射
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 本地上传目录（自定义皮肤图片），/uploads/** 直接访问 */
    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    public WebConfig(AuthInterceptor authInterceptor) {
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
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                // /remote/events 走一次性 ticket 鉴权（EventSource 无法带 Authorization 头），不在 JWT 拦截范围
                .excludePathPatterns("/api/auth/login", "/api/auth/register", "/api/remote/events");
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
