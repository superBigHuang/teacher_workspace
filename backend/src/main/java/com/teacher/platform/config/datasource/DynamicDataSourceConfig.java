package com.teacher.platform.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * 动态双数据源配置：
 * 1. masterDataSource: 指向正式库 teacher_workspace
 * 2. demoDataSource: 指向测试库 teacher_workspace_demo
 * 3. dynamicDataSource: 动态路由器，注册为 @Primary DataSource 提供给 JdbcTemplate
 */
@Configuration
public class DynamicDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceConfig.class);

    @Bean(name = "masterDataSource")
    public HikariDataSource masterDataSource(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("HikariPool-Master");
        ds.setJdbcUrl(env.getProperty("spring.datasource.master.jdbc-url",
                env.getProperty("spring.datasource.url")));
        ds.setUsername(env.getProperty("spring.datasource.master.username",
                env.getProperty("spring.datasource.username", "root")));
        ds.setPassword(env.getProperty("spring.datasource.master.password",
                env.getProperty("spring.datasource.password", "root")));
        ds.setDriverClassName(env.getProperty("spring.datasource.master.driver-class-name",
                env.getProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")));
        return ds;
    }

    @Bean(name = "demoDataSource")
    public HikariDataSource demoDataSource(Environment env) {
        HikariDataSource ds = new HikariDataSource();
        ds.setPoolName("HikariPool-Demo");
        ds.setJdbcUrl(env.getProperty("spring.datasource.demo.jdbc-url",
                "jdbc:mysql://localhost:3306/teacher_workspace_demo?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false"));
        ds.setUsername(env.getProperty("spring.datasource.demo.username", "root"));
        ds.setPassword(env.getProperty("spring.datasource.demo.password", "root"));
        ds.setDriverClassName(env.getProperty("spring.datasource.demo.driver-class-name", "com.mysql.cj.jdbc.Driver"));
        return ds;
    }

    @Primary
    @Bean(name = "dataSource")
    public DataSource dynamicDataSource(@Qualifier("masterDataSource") DataSource masterDataSource,
                                        @Qualifier("demoDataSource") DataSource demoDataSource) {
        DynamicRoutingDataSource routingDataSource = new DynamicRoutingDataSource();
        Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceContext.MASTER, masterDataSource);
        targetDataSources.put(DataSourceContext.DEMO, demoDataSource);

        routingDataSource.setTargetDataSources(targetDataSources);
        routingDataSource.setDefaultTargetDataSource(masterDataSource);
        routingDataSource.afterPropertiesSet();

        // 启动时确保 demo 测试库自动执行 schema.sql 建表
        initDemoSchema(demoDataSource);

        return routingDataSource;
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** 幂等初始化 demo 测试库的表结构 */
    private void initDemoSchema(DataSource demoDataSource) {
        try (var conn = demoDataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS teacher_workspace_demo DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            stmt.execute("USE teacher_workspace_demo");
            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.addScript(new ClassPathResource("schema.sql"));
            populator.setContinueOnError(true);
            populator.populate(conn);
            log.info("【动态数据源】测试库 teacher_workspace_demo 表结构 schema.sql 初始化完毕");
        } catch (Exception e) {
            log.warn("【动态数据源】测试库 schema.sql 执行异常（若表已存在可忽略）：{}", e.getMessage());
        }
    }
}
