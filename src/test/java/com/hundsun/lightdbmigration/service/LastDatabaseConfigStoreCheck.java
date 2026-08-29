package com.hundsun.lightdbmigration.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.LastDatabaseConfig;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 验证数据库配置加密持久化、重启式重载及主动清除。
 */
public class LastDatabaseConfigStoreCheck {

    private static final String SOURCE_PASSWORD = "source-secret-check";
    private static final String TARGET_PASSWORD = "target-secret-check";

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("lightdb-config-check-");
        LastDatabaseConfigStore store = new LastDatabaseConfigStore(new ObjectMapper(), directory.toString());
        try {
            store.save(config(MigrationConstants.DB_ORACLE, "source-host", 1521, "ORCL", "source-user",
                SOURCE_PASSWORD), config(MigrationConstants.DB_MYSQL, "target-host", 3306, "target_db",
                "target-user", TARGET_PASSWORD));

            Path encryptedFile = directory.resolve("last-database-config.dat");
            String rawFile = new String(Files.readAllBytes(encryptedFile), StandardCharsets.ISO_8859_1);
            assertFalse(rawFile.contains(SOURCE_PASSWORD), "源库密码以明文写入配置文件");
            assertFalse(rawFile.contains(TARGET_PASSWORD), "目标库密码以明文写入配置文件");

            LastDatabaseConfigStore restartedStore = new LastDatabaseConfigStore(new ObjectMapper(),
                directory.toString());
            LastDatabaseConfig loaded = restartedStore.load();
            assertEquals(SOURCE_PASSWORD, loaded.getSource().getPassword(), "服务重启式重载后源库密码不一致");
            assertEquals(TARGET_PASSWORD, loaded.getTarget().getPassword(), "服务重启式重载后目标库密码不一致");
            assertEquals("ORCL", loaded.getSource().getDatabase(), "源库配置未完整恢复");
            assertEquals("target_db", loaded.getTarget().getDatabase(), "目标库配置未完整恢复");

            restartedStore.clear();
            assertFalse(Files.exists(encryptedFile), "清除后仍残留配置密文");
            assertFalse(Files.exists(directory.resolve("last-config.key")), "清除后仍残留配置密钥");
        } finally {
            Files.deleteIfExists(directory.resolve("last-database-config.dat"));
            Files.deleteIfExists(directory.resolve("last-config.key"));
            Files.deleteIfExists(directory);
        }
        System.out.println("[RESULT] LAST_DATABASE_CONFIG_STORE_CHECK_PASSED");
    }

    private static DatabaseConfig config(String type, String host, int port, String database, String username,
        String password) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(type);
        config.setHost(host);
        config.setPort(port);
        config.setDatabase(database);
        config.setOracleMode(MigrationConstants.ORACLE_SERVICE);
        config.setUsername(username);
        config.setPassword(password);
        return config;
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + "，expected=" + expected + "，actual=" + actual);
        }
    }
}
