package com.hundsun.lightdbmigration.support;

import com.hundsun.lightdbmigration.model.DatabaseConfig;

/**
 * 达梦连接配置边界检查，不连接数据库。
 * Author: Codex
 * date: 2026-10-22
 */
public final class DamengConnectionConfigCheck {

    private static final String DM_DEFAULT_URL = "jdbc:dm://localhost:5236?connectTimeout=15000";
    private static final String CUSTOM_URL = "jdbc:dm://localhost:5237?schema=OTHER";
    private static final int CUSTOM_PORT = 5237;
    private static final int INVALID_PORT = 65536;

    private DamengConnectionConfigCheck() {
    }

    /**
     * Params: args 未使用。
     * Returns: 无；任一断言失败时抛出异常。
     */
    public static void main(String[] args) {
        DatabaseConfig config = config(MigrationConstants.DB_DM);
        DatabaseSupport.validate(config);
        equal(MigrationConstants.DB_DM, DatabaseSupport.normalizeType(" dm "));
        equal(MigrationConstants.DEFAULT_DM_PORT, DatabaseSupport.resolvePort(config));
        equal(DM_DEFAULT_URL, DatabaseSupport.buildJdbcUrl(config));

        config.setDatabase(" app_schema ");
        DatabaseSupport.validate(config);
        equal(DM_DEFAULT_URL + "&schema=APP_SCHEMA", DatabaseSupport.buildJdbcUrl(config));
        config.setPort(CUSTOM_PORT);
        equal(CUSTOM_PORT, DatabaseSupport.resolvePort(config));
        config.setJdbcUrl(CUSTOM_URL);
        config.setHost(null);
        equal(CUSTOM_URL, DatabaseSupport.buildJdbcUrl(config));
        DatabaseSupport.validate(config);

        String[] invalidUrls = {"jdbc:mysql://localhost/test", "jdbc:dm://user:secret@localhost:5236",
            "jdbc:dm://localhost:5236?user=someone", "jdbc:dm://localhost:5236?PASSWORD=secret",
            "jdbc:dm://localhost:5236?pwd=secret", "jdbc:dm://local\nhost:5236"};
        for (String url : invalidUrls) {
            config.setJdbcUrl(url);
            invalid(config);
        }
        config.setJdbcUrl("");
        config.setHost("localhost");
        config.setDatabase("APP&password=bad");
        invalid(config);
        config.setDatabase("");
        config.setPort(INVALID_PORT);
        invalid(config);

        DatabaseConfig mysql = config(MigrationConstants.DB_MYSQL);
        mysql.setDatabase("test");
        DatabaseSupport.validate(mysql);
        equal(MigrationConstants.DEFAULT_MYSQL_PORT, DatabaseSupport.resolvePort(mysql));
        DatabaseConfig oracle = config(MigrationConstants.DB_ORACLE);
        oracle.setDatabase("service");
        DatabaseSupport.validate(oracle);
        equal(MigrationConstants.DEFAULT_ORACLE_PORT, DatabaseSupport.resolvePort(oracle));
        equal("jdbc:oracle:thin:@//localhost:1521/service", DatabaseSupport.buildJdbcUrl(oracle));
        oracle.setOracleMode(MigrationConstants.ORACLE_SID);
        equal("jdbc:oracle:thin:@localhost:1521:service", DatabaseSupport.buildJdbcUrl(oracle));
        System.out.println("DAMENG_CONNECTION_CONFIG_CHECK_PASSED");
    }

    /**
     * Params: type 数据库类型。
     * Returns: 不含真实凭据的测试配置。
     */
    private static DatabaseConfig config(String type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setHost("localhost");
        config.setUsername("TEST_USER");
        config.setPassword("dummy");
        return config;
    }

    /**
     * Params: config 应被拒绝的配置。
     * Returns: 无；配置意外通过时抛出异常。
     */
    private static void invalid(DatabaseConfig config) {
        try {
            DatabaseSupport.validate(config);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Invalid connection config was accepted");
    }

    /**
     * Params: expected 预期值，actual 实际值。
     * Returns: 无；不一致时抛出异常。
     */
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("expected=" + expected + ", actual=" + actual);
        }
    }
}
