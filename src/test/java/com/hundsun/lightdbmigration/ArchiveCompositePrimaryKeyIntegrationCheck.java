package com.hundsun.lightdbmigration;

import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.TaskStatus;
import com.hundsun.lightdbmigration.service.ArchiveEngine;
import com.hundsun.lightdbmigration.service.MigrationTask;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public final class ArchiveCompositePrimaryKeyIntegrationCheck {

    private static final String MAIN_SOURCE = "LDM_CPK_MAIN_S";
    private static final String MAIN_TARGET = "LDM_CPK_MAIN_H";
    private static final String CHILD_SOURCE = "LDM_CPK_CHILD_S";
    private static final String CHILD_TARGET = "LDM_CPK_CHILD_H";
    private static final String ARCHIVE_NAME_PREFIX = "ARCHIVE_";
    private static final int TEST_BATCH_SIZE = 2;

    private ArchiveCompositePrimaryKeyIntegrationCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Usage: <host> <port> <database> <username> <MYSQL>");
        }
        char[] password = readPassword();
        DatabaseConfig config = databaseConfig(args, password);
        Arrays.fill(password, '\0');
        Connection setupConnection = null;
        boolean objectsCreated = false;
        try {
            setupConnection = DatabaseSupport.open(config);
            assertObjectsAbsent(setupConnection);
            objectsCreated = true;
            createTables(setupConnection);
            insertTestData(setupConnection);
            executeArchive(config, setupConnection);
            System.out.println("MAIN_THREE_COLUMN_PRIMARY_KEY_ARCHIVE_PASSED");
            System.out.println("CHILD_FOUR_COLUMN_PRIMARY_KEY_ARCHIVE_PASSED");
            System.out.println("COMPOSITE_PRIMARY_KEY_LIVE_CHECK_PASSED");
        } finally {
            if (setupConnection != null) {
                if (objectsCreated) {
                    cleanup(setupConnection);
                }
                assertObjectsAbsent(setupConnection);
                setupConnection.close();
            }
            config.clearPassword();
        }
    }

    private static void executeArchive(DatabaseConfig config, Connection verificationConnection) throws Exception {
        MigrationTask task = new MigrationTask("composite-primary-key", archiveRequest(copyConfig(config)));
        new ArchiveEngine().execute(task);
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            Map<String, Object> snapshot = task.snapshot(true, 0L);
            throw new AssertionError("复合主键归档任务未成功：" + snapshot.get("currentMessage"));
        }
        assertCount(verificationConnection, MAIN_SOURCE, 1L);
        assertCount(verificationConnection, MAIN_TARGET, 2L);
        assertCount(verificationConnection, CHILD_SOURCE, 1L);
        assertCount(verificationConnection, CHILD_TARGET, 3L);
        assertScalar(verificationConnection, "SELECT RECORD_NO FROM " + MAIN_SOURCE, 3L);
        assertScalar(verificationConnection, "SELECT RECORD_NO FROM " + CHILD_SOURCE, 3L);
    }

    private static CreateTaskRequest archiveRequest(DatabaseConfig config) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("复合主键归档验证");
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setBatchSize(TEST_BATCH_SIZE);
        request.setSource(config);
        request.setTarget(config);
        request.setSteps(Collections.emptyList());

        ArchiveTableRequest mainTable = table("三列复合主键主表", MAIN_SOURCE, MAIN_TARGET,
            "SELECT TENANT_ID, BIZ_GROUP, RECORD_NO, NAME FROM " + MAIN_SOURCE
                + " WHERE NAME LIKE '" + ARCHIVE_NAME_PREFIX + "%'");
        mainTable.setNodeId("MAIN_1");

        ArchiveTableRequest childTable = table("四列复合主键子表", CHILD_SOURCE, CHILD_TARGET, null);
        childTable.setNodeId("CHILD_1");
        childTable.setParentNodeId("MAIN_1");
        childTable.setRelations(Arrays.asList(relation("TENANT_ID", "TENANT_ID"),
            relation("BIZ_GROUP", "BIZ_GROUP"), relation("RECORD_NO", "RECORD_NO")));

        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        archive.setMainTables(Collections.singletonList(mainTable));
        archive.setMainTable(mainTable);
        archive.setChildTables(Collections.singletonList(childTable));
        request.setArchive(archive);
        return request;
    }

    private static ArchiveTableRequest table(String name, String source, String target, String sourceSql) {
        ArchiveTableRequest request = new ArchiveTableRequest();
        request.setName(name);
        request.setSourceTable(source);
        request.setTargetTable(target);
        request.setCursorColumn(null);
        request.setSourceSql(sourceSql);
        request.setRelations(Collections.<ArchiveRelationRequest>emptyList());
        return request;
    }

    private static ArchiveRelationRequest relation(String childColumn, String mainColumn) {
        ArchiveRelationRequest relation = new ArchiveRelationRequest();
        relation.setChildColumn(childColumn);
        relation.setMainColumn(mainColumn);
        return relation;
    }

    private static void createTables(Connection connection) throws Exception {
        executeUpdate(connection, "CREATE TABLE " + MAIN_SOURCE
            + " (TENANT_ID VARCHAR(16) NOT NULL, BIZ_GROUP VARCHAR(32) NOT NULL, RECORD_NO BIGINT NOT NULL,"
            + " NAME VARCHAR(64) NOT NULL, PRIMARY KEY (TENANT_ID, BIZ_GROUP, RECORD_NO))");
        executeUpdate(connection, "CREATE TABLE " + MAIN_TARGET + " LIKE " + MAIN_SOURCE);
        executeUpdate(connection, "CREATE TABLE " + CHILD_SOURCE
            + " (TENANT_ID VARCHAR(16) NOT NULL, BIZ_GROUP VARCHAR(32) NOT NULL, RECORD_NO BIGINT NOT NULL,"
            + " DETAIL_NO BIGINT NOT NULL, DETAIL_VALUE VARCHAR(64) NOT NULL,"
            + " PRIMARY KEY (TENANT_ID, BIZ_GROUP, RECORD_NO, DETAIL_NO))");
        executeUpdate(connection, "CREATE TABLE " + CHILD_TARGET + " LIKE " + CHILD_SOURCE);
        System.out.println("DEDICATED_COMPOSITE_OBJECTS_CREATED");
    }

    private static void insertTestData(Connection connection) throws Exception {
        executeUpdate(connection, "INSERT INTO " + MAIN_SOURCE + " VALUES"
            + " ('T1', 'G1', 1, 'ARCHIVE_A'), ('T1', 'G1', 2, 'ARCHIVE_B'),"
            + " ('T1', 'G1', 3, 'KEEP')");
        executeUpdate(connection, "INSERT INTO " + CHILD_SOURCE + " VALUES"
            + " ('T1', 'G1', 1, 1, 'A1'), ('T1', 'G1', 1, 2, 'A2'),"
            + " ('T1', 'G1', 2, 1, 'B1'), ('T1', 'G1', 3, 1, 'KEEP')");
        System.out.println("DEDICATED_COMPOSITE_TEST_DATA_INSERTED");
    }

    private static void cleanup(Connection connection) throws Exception {
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_TARGET);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + MAIN_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + MAIN_TARGET);
        System.out.println("DEDICATED_COMPOSITE_OBJECTS_CLEANED");
    }

    private static void assertObjectsAbsent(Connection connection) throws Exception {
        String[] tables = tableNames();
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
            + " AND table_name IN (" + placeholders(tables.length) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < tables.length; i++) {
                statement.setString(i + 1, tables[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getLong(1) != 0L) {
                    throw new IllegalStateException("复合主键专用测试表已存在，为避免影响已有对象，本次测试停止");
                }
            }
        }
        System.out.println("DEDICATED_COMPOSITE_OBJECT_PRECHECK_PASSED");
    }

    private static String[] tableNames() {
        return new String[] {MAIN_SOURCE, MAIN_TARGET, CHILD_SOURCE, CHILD_TARGET};
    }

    private static String placeholders(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append('?');
        }
        return result.toString();
    }

    private static void assertCount(Connection connection, String table, long expected) throws Exception {
        assertScalar(connection, "SELECT COUNT(*) FROM " + table, expected);
    }

    private static void assertScalar(Connection connection, String sql, long expected) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next() || resultSet.getLong(1) != expected) {
                throw new AssertionError("校验失败：" + sql + "，期望=" + expected);
            }
        }
    }

    private static void executeUpdate(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static DatabaseConfig databaseConfig(String[] args, char[] password) {
        DatabaseConfig config = new DatabaseConfig();
        config.setHost(args[0]);
        config.setPort(Integer.valueOf(args[1]));
        config.setDatabase(args[2]);
        config.setUsername(args[3]);
        config.setPassword(new String(password));
        config.setType(args[4]);
        return config;
    }

    private static DatabaseConfig copyConfig(DatabaseConfig source) {
        DatabaseConfig copy = new DatabaseConfig();
        copy.setHost(source.getHost());
        copy.setPort(source.getPort());
        copy.setDatabase(source.getDatabase());
        copy.setUsername(source.getUsername());
        copy.setPassword(source.getPassword());
        copy.setType(source.getType());
        copy.setOracleMode(source.getOracleMode());
        copy.setJdbcUrl(source.getJdbcUrl());
        return copy;
    }

    private static char[] readPassword() throws Exception {
        if (System.console() != null) {
            return System.console().readPassword("Database password: ");
        }
        System.out.print("Database password: ");
        return new BufferedReader(new InputStreamReader(System.in)).readLine().toCharArray();
    }
}
