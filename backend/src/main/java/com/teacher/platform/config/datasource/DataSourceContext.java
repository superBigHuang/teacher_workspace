package com.teacher.platform.config.datasource;

/**
 * 数据源上下文：基于 ThreadLocal 维护当前请求/线程的数据源标识。
 * 默认值为 master（正式库）。
 */
public final class DataSourceContext {

    public static final String MASTER = "master";
    public static final String DEMO = "demo";

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private DataSourceContext() {}

    public static void set(String dsKey) {
        if (dsKey == null || dsKey.isBlank()) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(dsKey.trim().toLowerCase());
        }
    }

    public static String get() {
        String key = CONTEXT.get();
        return key == null || key.isBlank() ? MASTER : key;
    }

    public static boolean isDemo() {
        return DEMO.equalsIgnoreCase(get());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
