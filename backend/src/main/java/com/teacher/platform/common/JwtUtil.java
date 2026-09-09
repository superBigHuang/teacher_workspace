package com.teacher.platform.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验
 */
@Component
public class JwtUtil {

    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    /** 与 application.yml 默认值一致；检测到仍在用该值时启动告警 */
    private static final String DEV_SECRET_PREFIX = "dev-insecure-secret";

    @Value("${app.jwt.secret}")
    private String secret;

    @Value("${app.jwt.expire-hours:72}")
    private long expireHours;

    @PostConstruct
    void warnIfDevSecret() {
        if (secret == null || secret.isBlank() || secret.startsWith(DEV_SECRET_PREFIX)) {
            log.warn("======== 安全告警：JWT 正在使用开发默认密钥！生产部署请设置环境变量 APP_JWT_SECRET（随机长串），否则 token 可被伪造 ========");
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成 token */
    public String generate(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireHours * 3600_000))
                .signWith(key())
                .compact();
    }

    /**
     * 解析 token，非法/过期返回 null
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
