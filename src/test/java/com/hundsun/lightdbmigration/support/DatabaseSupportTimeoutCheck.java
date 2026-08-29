package com.hundsun.lightdbmigration.support;

import com.hundsun.lightdbmigration.model.DatabaseConfig;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

public class DatabaseSupportTimeoutCheck {

    public static void main(String[] args) throws Exception {
        DriverManager.registerDriver(new SlowMysqlDriver());
        DatabaseConfig config = new DatabaseConfig();
        config.setType(MigrationConstants.DB_MYSQL);
        config.setHost("timeout-check.invalid");
        config.setPort(3306);
        config.setDatabase("timeout_check");
        config.setUsername("timeout_check");
        config.setPassword("not-a-real-password");

        long begin = System.currentTimeMillis();
        try {
            DatabaseSupport.open(config);
            throw new IllegalStateException("连接看门狗未触发");
        } catch (SQLException exception) {
            long costMs = System.currentTimeMillis() - begin;
            long minimumMs = MigrationConstants.CONNECTION_TIMEOUT_SECONDS * 1000L;
            if (costMs < minimumMs || costMs > minimumMs + 5000L) {
                throw new IllegalStateException("连接超时时间不符合预期：" + costMs + "ms", exception);
            }
            if (!exception.getMessage().contains("连接数据库超时")) {
                throw exception;
            }
            System.out.println("[PASS] application connection watchdog costMs=" + costMs);
        }
    }

    private static final class SlowMysqlDriver implements Driver {

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            long deadline = System.currentTimeMillis() + 60000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignored) {
                    // 模拟不响应线程中断的现场 JDBC 驱动。
                }
            }
            throw new SQLException("simulated late connection failure");
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:mysql://timeout-check.invalid");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
