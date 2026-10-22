package com.hundsun.lightdbmigration.support;

import com.hundsun.lightdbmigration.model.DatabaseConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseSupport.class);
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String ORACLE_DRIVER = "oracle.jdbc.OracleDriver";
    private static final String DM_DRIVER = "dm.jdbc.driver.DmDriver";
    private static final String MYSQL_URL_PREFIX = "jdbc:mysql://";
    private static final String ORACLE_URL_PREFIX = "jdbc:oracle:thin:@";
    private static final String DM_URL_PREFIX = "jdbc:dm://";
    private static final String TEST_MYSQL_SQL = "SELECT 1";
    private static final String TEST_ORACLE_SQL = "SELECT 1 FROM DUAL";
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private DatabaseSupport() {
    }

    public static Connection open(final DatabaseConfig config) throws Exception {
        validate(config);
        final String type = normalizeType(config.getType());
        String driver = MigrationConstants.DB_MYSQL.equals(type) ? MYSQL_DRIVER
            : MigrationConstants.DB_DM.equals(type) ? DM_DRIVER : ORACLE_DRIVER;
        Class.forName(driver);

        final Properties properties = new Properties();
        properties.setProperty("user", config.getUsername().trim());
        properties.setProperty("password", config.getPassword());
        properties.setProperty("connectTimeout",
            String.valueOf(MigrationConstants.CONNECTION_TIMEOUT_SECONDS * 1000));
        if (MigrationConstants.DB_ORACLE.equals(type)) {
            properties.setProperty("oracle.net.CONNECT_TIMEOUT",
                String.valueOf(MigrationConstants.CONNECTION_TIMEOUT_SECONDS * 1000));
        }
        DriverManager.setLoginTimeout(MigrationConstants.CONNECTION_TIMEOUT_SECONDS);
        final String jdbcUrl = buildJdbcUrl(config);
        LOGGER.info("Opening database connection: type={}, endpoint={}", type, displayEndpoint(config));
        final AtomicBoolean abandoned = new AtomicBoolean(false);
        ExecutorService connectionExecutor = Executors.newSingleThreadExecutor(new ConnectionThreadFactory());
        Future<Connection> connectionFuture = connectionExecutor.submit(new Callable<Connection>() {
            @Override
            public Connection call() throws Exception {
                Connection connection = DriverManager.getConnection(jdbcUrl, properties);
                if (abandoned.get()) {
                    closeQuietly(connection);
                    throw new SQLException("数据库连接请求已超时并取消");
                }
                return connection;
            }
        });
        try {
            Connection connection = connectionFuture.get(MigrationConstants.CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            LOGGER.info("Database connection established: type={}, endpoint={}", type, displayEndpoint(config));
            return connection;
        } catch (TimeoutException exception) {
            abandoned.set(true);
            boolean cancelled = connectionFuture.cancel(true);
            if (!cancelled && connectionFuture.isDone()) {
                closeCompletedConnection(connectionFuture);
            }
            throw new SQLException("连接数据库超时（" + MigrationConstants.CONNECTION_TIMEOUT_SECONDS
                + " 秒），请检查地址、端口、网络和数据库监听状态");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            LOGGER.warn("Database connection failed: type={}, endpoint={}, reason={}",
                type, displayEndpoint(config), SqlSupport.safeMessage(cause));
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw new SQLException("数据库连接失败", cause);
        } catch (InterruptedException exception) {
            abandoned.set(true);
            connectionFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            connectionExecutor.shutdownNow();
        }
    }

    public static Map<String, Object> test(DatabaseConfig config) throws Exception {
        long begin = System.currentTimeMillis();
        try (Connection connection = open(config); Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(MigrationConstants.CONNECTION_TIMEOUT_SECONDS);
            String type = normalizeType(config.getType());
            String sql = MigrationConstants.DB_MYSQL.equals(type) ? TEST_MYSQL_SQL : TEST_ORACLE_SQL;
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("连接查询未返回结果");
                }
            }
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", Boolean.TRUE);
            result.put("message", "连接成功");
            result.put("databaseProduct", metaData.getDatabaseProductName());
            result.put("databaseVersion", metaData.getDatabaseProductVersion());
            result.put("costMs", System.currentTimeMillis() - begin);
            LOGGER.info("Database connection test passed: type={}, product={}, version={}, costMs={}",
                type, result.get("databaseProduct"), result.get("databaseVersion"), result.get("costMs"));
            return result;
        }
    }

    public static void validate(DatabaseConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("数据库配置不能为空");
        }
        String type = normalizeType(config.getType());
        requireText(config.getUsername(), "数据库用户名不能为空");
        if (config.getPassword() == null) {
            throw new IllegalArgumentException("数据库密码不能为空");
        }

        String customUrl = trim(config.getJdbcUrl());
        if (!customUrl.isEmpty()) {
            if (containsLineBreak(customUrl)) {
                throw new IllegalArgumentException("JDBC URL 不能包含换行");
            }
            String lowerUrl = customUrl.toLowerCase(Locale.ROOT);
            if (lowerUrl.contains("password=") || lowerUrl.contains("pwd=") || lowerUrl.contains("user=")) {
                throw new IllegalArgumentException("JDBC URL 中不能包含账号或密码，请使用页面独立字段填写");
            }
            if (MigrationConstants.DB_MYSQL.equals(type) && !lowerUrl.startsWith(MYSQL_URL_PREFIX)) {
                throw new IllegalArgumentException("MySQL 的 JDBC URL 必须以 jdbc:mysql:// 开头");
            }
            if (MigrationConstants.DB_DM.equals(type) && !lowerUrl.startsWith(DM_URL_PREFIX)) {
                throw new IllegalArgumentException("达梦的 JDBC URL 必须以 jdbc:dm:// 开头");
            }
            if (MigrationConstants.DB_MYSQL.equals(type) || MigrationConstants.DB_DM.equals(type)) {
                String prefix = MigrationConstants.DB_MYSQL.equals(type) ? MYSQL_URL_PREFIX : DM_URL_PREFIX;
                int authorityEnd = lowerUrl.indexOf('/', prefix.length());
                String authority = authorityEnd < 0 ? lowerUrl.substring(prefix.length())
                    : lowerUrl.substring(prefix.length(), authorityEnd);
                if (authority.indexOf('@') >= 0) {
                    throw new IllegalArgumentException("JDBC URL 中不能包含账号或密码，请使用页面独立字段填写");
                }
            }
            if (MigrationConstants.DB_ORACLE.equals(type) && !lowerUrl.startsWith(ORACLE_URL_PREFIX)) {
                throw new IllegalArgumentException("Oracle 的 JDBC URL 必须以 jdbc:oracle:thin:@ 开头");
            }
            return;
        }

        requireText(config.getHost(), "数据库地址不能为空");
        if (MigrationConstants.DB_DM.equals(type)) {
            if (!trim(config.getDatabase()).isEmpty()) {
                SqlSupport.normalizeColumnName(config.getDatabase(), "达梦 Schema");
            }
        } else {
            requireText(config.getDatabase(),
                MigrationConstants.DB_MYSQL.equals(type) ? "数据库名不能为空" : "Oracle 服务名或 SID 不能为空");
        }
        if (containsLineBreak(config.getHost()) || containsLineBreak(config.getDatabase())) {
            throw new IllegalArgumentException("数据库地址和库名不能包含换行");
        }
        int port = resolvePort(config);
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException("数据库端口必须在 1 到 65535 之间");
        }
    }

    public static String normalizeType(String type) {
        String normalized = trim(type).toUpperCase(Locale.ROOT);
        if (!MigrationConstants.DB_MYSQL.equals(normalized)
            && !MigrationConstants.DB_ORACLE.equals(normalized)
            && !MigrationConstants.DB_DM.equals(normalized)) {
            throw new IllegalArgumentException("数据库类型只支持 MYSQL、ORACLE 或 DM");
        }
        return normalized;
    }

    public static int resolvePort(DatabaseConfig config) {
        if (config.getPort() != null) {
            return config.getPort();
        }
        String type = normalizeType(config.getType());
        if (MigrationConstants.DB_DM.equals(type)) {
            return MigrationConstants.DEFAULT_DM_PORT;
        }
        return MigrationConstants.DB_MYSQL.equals(type)
            ? MigrationConstants.DEFAULT_MYSQL_PORT : MigrationConstants.DEFAULT_ORACLE_PORT;
    }

    public static String displayEndpoint(DatabaseConfig config) {
        String customUrl = trim(config.getJdbcUrl());
        if (!customUrl.isEmpty()) {
            int queryIndex = customUrl.indexOf('?');
            return queryIndex >= 0 ? customUrl.substring(0, queryIndex) : customUrl;
        }
        return trim(config.getHost()) + ":" + resolvePort(config) + "/" + trim(config.getDatabase());
    }

    public static String buildJdbcUrl(DatabaseConfig config) {
        String customUrl = trim(config.getJdbcUrl());
        if (!customUrl.isEmpty()) {
            return customUrl;
        }
        String type = normalizeType(config.getType());
        String host = trim(config.getHost());
        String database = trim(config.getDatabase());
        int port = resolvePort(config);
        if (MigrationConstants.DB_MYSQL.equals(type)) {
            return MYSQL_URL_PREFIX + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai"
                + "&rewriteBatchedStatements=true&useServerPrepStmts=true&useCursorFetch=true"
                + "&connectTimeout=" + MigrationConstants.CONNECTION_TIMEOUT_SECONDS * 1000;
        }
        if (MigrationConstants.DB_DM.equals(type)) {
            String schema = database.isEmpty() ? "" : "&schema=" + database.toUpperCase(Locale.ROOT);
            return DM_URL_PREFIX + host + ":" + port + "?connectTimeout="
                + MigrationConstants.CONNECTION_TIMEOUT_SECONDS * 1000 + schema;
        }
        String oracleMode = trim(config.getOracleMode()).toUpperCase(Locale.ROOT);
        if (MigrationConstants.ORACLE_SID.equals(oracleMode)) {
            return ORACLE_URL_PREFIX + host + ":" + port + ":" + database;
        }
        return ORACLE_URL_PREFIX + "//" + host + ":" + port + "/" + database;
    }

    private static void requireText(String value, String message) {
        if (trim(value).isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean containsLineBreak(String value) {
        return value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void closeCompletedConnection(Future<Connection> connectionFuture) {
        try {
            closeQuietly(connectionFuture.get(0L, TimeUnit.MILLISECONDS));
        } catch (Exception ignored) {
            // 超时异常是主结果，迟到连接由执行线程中的 abandoned 标记继续兜底关闭。
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 连接已经超时，不覆盖主异常。
            }
        }
    }

    private static final class ConnectionThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "database-connection-opener");
            thread.setDaemon(true);
            return thread;
        }
    }
}
