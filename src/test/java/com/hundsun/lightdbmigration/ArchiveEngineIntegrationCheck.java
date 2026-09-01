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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ArchiveEngineIntegrationCheck {

    private static final String MAIN_SOURCE = "LDM_ARC_IT_MAIN_S";
    private static final String MAIN_TARGET = "LDM_ARC_IT_MAIN_H";
    private static final String CHILD_ONE_SOURCE = "LDM_ARC_IT_CHILD1_S";
    private static final String CHILD_ONE_TARGET = "LDM_ARC_IT_CHILD1_H";
    private static final String CHILD_TWO_SOURCE = "LDM_ARC_IT_CHILD2_S";
    private static final String CHILD_TWO_TARGET = "LDM_ARC_IT_CHILD2_H";
    private static final String CHILD_THREE_SOURCE = "LDM_ARC_IT_CHILD3_S";
    private static final String CHILD_THREE_TARGET = "LDM_ARC_IT_CHILD3_H";
    private static final String UUID_SOURCE = "LDM_ARC_IT_UUID_S";
    private static final String UUID_TARGET = "LDM_ARC_IT_UUID_H";
    private static final String ARCHIVE_NAME_PREFIX = "ARCHIVE_";
    private static final String TEST_CURSOR_COLUMN = "ID";
    private static final String EXPLICIT_CURSOR_CHILD_NODE_ID = "CHILD_3";
    private static final int TEST_BATCH_SIZE = 1;

    private ArchiveEngineIntegrationCheck() {
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

            executeExpectedRollback(config, setupConnection);
            executeSuccessfulArchive(config, setupConnection);
            System.out.println("ARCHIVE_ENGINE_LIVE_CHECK_PASSED");
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

    private static void executeExpectedRollback(DatabaseConfig config, Connection verificationConnection)
        throws Exception {
        executeUpdate(verificationConnection, "INSERT INTO " + MAIN_TARGET
            + " (ID, TENANT_ID, BIZ_GROUP, NAME) VALUES (101, 'T1', 'G1', 'CONFLICT')");
        MigrationTask conflictTask = new MigrationTask("archive-conflict", archiveRequest(copyConfig(config)));
        new ArchiveEngine().execute(conflictTask);
        if (conflictTask.getStatus() != TaskStatus.FAILED) {
            throw new AssertionError("目标表存在重复键时归档任务应失败");
        }
        assertCount(verificationConnection, MAIN_SOURCE, 3L);
        assertCount(verificationConnection, CHILD_ONE_SOURCE, 3L);
        assertCount(verificationConnection, CHILD_TWO_SOURCE, 3L);
        assertCount(verificationConnection, CHILD_THREE_SOURCE, 3L);
        assertCount(verificationConnection, CHILD_ONE_TARGET, 0L);
        assertCount(verificationConnection, CHILD_TWO_TARGET, 0L);
        assertCount(verificationConnection, CHILD_THREE_TARGET, 0L);
        assertCount(verificationConnection, UUID_SOURCE, 3L);
        assertCount(verificationConnection, UUID_TARGET, 0L);
        executeUpdate(verificationConnection, "DELETE FROM " + MAIN_TARGET + " WHERE ID = 101");
        System.out.println("EXPECTED_DUPLICATE_ROLLBACK_PASSED");
    }

    private static void executeSuccessfulArchive(DatabaseConfig config, Connection verificationConnection)
        throws Exception {
        MigrationTask task = new MigrationTask("archive-success", archiveRequest(copyConfig(config)));
        new ArchiveEngine().execute(task);
        if (task.getStatus() != TaskStatus.SUCCEEDED) {
            Map<String, Object> snapshot = task.snapshot(true, 0L);
            throw new AssertionError("归档任务未成功：" + snapshot.get("currentMessage"));
        }
        assertPaginationModesPreserved(task);
        assertCount(verificationConnection, MAIN_SOURCE, 1L);
        assertCount(verificationConnection, MAIN_TARGET, 2L);
        assertCount(verificationConnection, CHILD_ONE_SOURCE, 1L);
        assertCount(verificationConnection, CHILD_ONE_TARGET, 2L);
        assertCount(verificationConnection, CHILD_TWO_SOURCE, 1L);
        assertCount(verificationConnection, CHILD_TWO_TARGET, 2L);
        assertCount(verificationConnection, CHILD_THREE_SOURCE, 1L);
        assertCount(verificationConnection, CHILD_THREE_TARGET, 2L);
        assertScalar(verificationConnection, "SELECT ID FROM " + MAIN_SOURCE, 201L);
        assertScalar(verificationConnection, "SELECT MAIN_ID FROM " + CHILD_ONE_SOURCE, 201L);
        assertScalar(verificationConnection, "SELECT ID FROM " + CHILD_TWO_SOURCE, 2003L);
        assertScalar(verificationConnection, "SELECT ID FROM " + CHILD_THREE_SOURCE, 3001L);
        assertCount(verificationConnection, UUID_SOURCE, 1L);
        assertCount(verificationConnection, UUID_TARGET, 2L);
        assertText(verificationConnection, "SELECT ID FROM " + UUID_SOURCE,
            "7c9e6679-7425-40de-944b-e07fc1f90ae7");
        assertSqlNotExposedInTaskLogs(task);
        System.out.println("MULTIPLE_MAIN_TABLES_AND_DIRECTED_CHILD_GRAPH_ARCHIVE_PASSED");
    }

    private static void assertPaginationModesPreserved(MigrationTask task) {
        ArchiveRequest archive = task.getRequest().getArchive();
        List<ArchiveTableRequest> mainTables = archive.getMainTables();
        if (mainTables.size() != 2 || !TEST_CURSOR_COLUMN.equalsIgnoreCase(mainTables.get(0).getCursorColumn())) {
            throw new AssertionError("第一张主业务表显式游标配置未保留");
        }
        if (mainTables.get(1).getCursorColumn() != null) {
            throw new AssertionError("随机UUID主表空游标不应被改写为递增游标");
        }
        for (ArchiveTableRequest child : archive.getChildTables()) {
            boolean explicitCursor = EXPLICIT_CURSOR_CHILD_NODE_ID.equals(child.getNodeId());
            if (explicitCursor && !TEST_CURSOR_COLUMN.equalsIgnoreCase(child.getCursorColumn())) {
                throw new AssertionError(child.getName() + "显式游标配置未保留");
            }
            if (!explicitCursor && child.getCursorColumn() != null) {
                throw new AssertionError(child.getName() + "空游标不应被改写为主键游标");
            }
        }
        System.out.println("EXPLICIT_KEYSET_AND_BLANK_NON_KEYSET_MODES_PASSED");
    }

    private static CreateTaskRequest archiveRequest(DatabaseConfig config) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("归档集成验证");
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setBatchSize(TEST_BATCH_SIZE);
        request.setSource(config);
        request.setTarget(config);
        request.setSteps(Collections.emptyList());

        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        ArchiveTableRequest businessMain = table("主业务表", MAIN_SOURCE, MAIN_TARGET, TEST_CURSOR_COLUMN,
            "SELECT ID, TENANT_ID, BIZ_GROUP, NAME FROM " + MAIN_SOURCE
                + " WHERE NAME LIKE '" + ARCHIVE_NAME_PREFIX + "%'");
        businessMain.setNodeId("MAIN_1");
        ArchiveTableRequest uuidMain = table("随机UUID主表", UUID_SOURCE, UUID_TARGET, null,
            "SELECT ID, NAME FROM " + UUID_SOURCE + " WHERE NAME LIKE '" + ARCHIVE_NAME_PREFIX + "%'");
        uuidMain.setNodeId("MAIN_2");
        archive.setMainTables(Arrays.asList(businessMain, uuidMain));
        archive.setMainTable(businessMain);
        List<ArchiveTableRequest> children = new ArrayList<>();
        ArchiveTableRequest childOne = table("主键关联子表", CHILD_ONE_SOURCE, CHILD_ONE_TARGET, null, null);
        childOne.setNodeId("CHILD_1");
        childOne.setParentNodeId("MAIN_1");
        childOne.setRelations(Collections.singletonList(relation("MAIN_ID", "ID")));
        children.add(childOne);
        ArchiveTableRequest childTwo = table("二级从表", CHILD_TWO_SOURCE, CHILD_TWO_TARGET, null, null);
        childTwo.setNodeId("CHILD_2");
        childTwo.setParentNodeId("CHILD_1");
        childTwo.setRelations(Collections.singletonList(relation("CHILD1_ID", "ID")));
        children.add(childTwo);
        ArchiveTableRequest childThree = table("复合字段直属从表", CHILD_THREE_SOURCE, CHILD_THREE_TARGET,
            TEST_CURSOR_COLUMN, null);
        childThree.setNodeId(EXPLICIT_CURSOR_CHILD_NODE_ID);
        childThree.setParentNodeId("MAIN_1");
        childThree.setRelations(Arrays.asList(relation("TENANT_ID", "TENANT_ID"),
            relation("BIZ_GROUP", "BIZ_GROUP")));
        children.add(childThree);
        archive.setChildTables(children);
        request.setArchive(archive);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static void assertSqlNotExposedInTaskLogs(MigrationTask task) {
        List<Map<String, Object>> logs = (List<Map<String, Object>>)task.snapshot(true, 0L).get("logs");
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.contains("SQL执行[")) {
                throw new AssertionError("前端任务日志不应暴露具体执行SQL：" + message);
            }
        }
        System.out.println("ARCHIVE_SQL_NOT_EXPOSED_IN_TASK_LOGS_PASSED");
    }

    private static ArchiveTableRequest table(String name, String source, String target, String cursor,
        String sourceSql) {
        ArchiveTableRequest request = new ArchiveTableRequest();
        request.setName(name);
        request.setSourceTable(source);
        request.setTargetTable(target);
        request.setCursorColumn(cursor);
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
            + " (ID BIGINT NOT NULL, TENANT_ID VARCHAR(16) NOT NULL, BIZ_GROUP VARCHAR(32) NOT NULL,"
            + " NAME VARCHAR(64) NOT NULL, PRIMARY KEY (ID), UNIQUE KEY UK_MAIN_REL (TENANT_ID, BIZ_GROUP))");
        executeUpdate(connection, "CREATE TABLE " + MAIN_TARGET + " LIKE " + MAIN_SOURCE);
        executeUpdate(connection, "CREATE TABLE " + CHILD_ONE_SOURCE
            + " (ID BIGINT NOT NULL, MAIN_ID BIGINT NOT NULL, DETAIL_VALUE VARCHAR(64) NOT NULL, PRIMARY KEY (ID),"
            + " CONSTRAINT FK_ARC_IT_C1_S FOREIGN KEY (MAIN_ID) REFERENCES " + MAIN_SOURCE + " (ID))");
        executeUpdate(connection, "CREATE TABLE " + CHILD_ONE_TARGET + " LIKE " + CHILD_ONE_SOURCE);
        executeUpdate(connection, "ALTER TABLE " + CHILD_ONE_TARGET
            + " ADD CONSTRAINT FK_ARC_IT_C1_H FOREIGN KEY (MAIN_ID) REFERENCES " + MAIN_TARGET + " (ID)");
        executeUpdate(connection, "CREATE TABLE " + CHILD_TWO_SOURCE
            + " (ID BIGINT NOT NULL, CHILD1_ID BIGINT NOT NULL, NOTE_VALUE VARCHAR(64) NOT NULL, PRIMARY KEY (ID),"
            + " CONSTRAINT FK_ARC_IT_C2_S FOREIGN KEY (CHILD1_ID) REFERENCES " + CHILD_ONE_SOURCE + " (ID))");
        executeUpdate(connection, "CREATE TABLE " + CHILD_TWO_TARGET + " LIKE " + CHILD_TWO_SOURCE);
        executeUpdate(connection, "ALTER TABLE " + CHILD_TWO_TARGET
            + " ADD CONSTRAINT FK_ARC_IT_C2_H FOREIGN KEY (CHILD1_ID) REFERENCES " + CHILD_ONE_TARGET + " (ID)");
        executeUpdate(connection, "CREATE TABLE " + CHILD_THREE_SOURCE
            + " (ID BIGINT NOT NULL, TENANT_ID VARCHAR(16) NOT NULL, BIZ_GROUP VARCHAR(32) NOT NULL,"
            + " NOTE_VALUE VARCHAR(64) NOT NULL, PRIMARY KEY (ID), CONSTRAINT FK_ARC_IT_C3_S"
            + " FOREIGN KEY (TENANT_ID, BIZ_GROUP) REFERENCES " + MAIN_SOURCE + " (TENANT_ID, BIZ_GROUP))");
        executeUpdate(connection, "CREATE TABLE " + CHILD_THREE_TARGET + " LIKE " + CHILD_THREE_SOURCE);
        executeUpdate(connection, "ALTER TABLE " + CHILD_THREE_TARGET
            + " ADD CONSTRAINT FK_ARC_IT_C3_H FOREIGN KEY (TENANT_ID, BIZ_GROUP) REFERENCES " + MAIN_TARGET
            + " (TENANT_ID, BIZ_GROUP)");
        executeUpdate(connection, "CREATE TABLE " + UUID_SOURCE
            + " (ID VARCHAR(36) NOT NULL, NAME VARCHAR(64) NOT NULL, PRIMARY KEY (ID))");
        executeUpdate(connection, "CREATE TABLE " + UUID_TARGET + " LIKE " + UUID_SOURCE);
        System.out.println("DEDICATED_OBJECTS_CREATED");
    }

    private static void insertTestData(Connection connection) throws Exception {
        executeUpdate(connection, "INSERT INTO " + MAIN_SOURCE + " VALUES"
            + " (101, 'T1', 'G1', 'ARCHIVE_A'), (102, 'T1', 'G2', 'ARCHIVE_B'), (201, 'T1', 'G3', 'KEEP')");
        executeUpdate(connection, "INSERT INTO " + CHILD_ONE_SOURCE + " VALUES"
            + " (1001, 101, 'A'), (1002, 102, 'B'), (1003, 201, 'KEEP')");
        executeUpdate(connection, "INSERT INTO " + CHILD_TWO_SOURCE + " VALUES"
            + " (2001, 1001, 'A'), (2002, 1002, 'B'), (2003, 1003, 'KEEP')");
        executeUpdate(connection, "INSERT INTO " + CHILD_THREE_SOURCE + " VALUES"
            + " (3001, 'T1', 'G3', 'KEEP'), (3002, 'T1', 'G1', 'A'), (3003, 'T1', 'G2', 'B')");
        executeUpdate(connection, "INSERT INTO " + UUID_SOURCE + " VALUES"
            + " ('f47ac10b-58cc-4372-a567-0e02b2c3d479', 'ARCHIVE_A'),"
            + " ('0f8fad5b-d9cb-469f-a165-70867728950e', 'ARCHIVE_B'),"
            + " ('7c9e6679-7425-40de-944b-e07fc1f90ae7', 'KEEP')");
        System.out.println("DEDICATED_TEST_DATA_INSERTED");
    }

    private static void cleanup(Connection connection) throws Exception {
        executeUpdate(connection, "DROP TABLE IF EXISTS " + UUID_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + UUID_TARGET);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_TWO_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_ONE_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_THREE_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_TWO_TARGET);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_ONE_TARGET);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + CHILD_THREE_TARGET);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + MAIN_SOURCE);
        executeUpdate(connection, "DROP TABLE IF EXISTS " + MAIN_TARGET);
        System.out.println("DEDICATED_OBJECTS_CLEANED");
    }

    private static void assertObjectsAbsent(Connection connection) throws Exception {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"
            + " AND table_name IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String[] tables = tableNames();
            for (int i = 0; i < tables.length; i++) {
                statement.setString(i + 1, tables[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                if (resultSet.getLong(1) != 0L) {
                    throw new IllegalStateException("专用测试表已存在，为避免影响已有对象，本次测试停止");
                }
            }
        }
        System.out.println("DEDICATED_OBJECT_PRECHECK_PASSED");
    }

    private static String[] tableNames() {
        return new String[] {MAIN_SOURCE, MAIN_TARGET, CHILD_ONE_SOURCE, CHILD_ONE_TARGET, CHILD_TWO_SOURCE,
            CHILD_TWO_TARGET, CHILD_THREE_SOURCE, CHILD_THREE_TARGET, UUID_SOURCE, UUID_TARGET};
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

    private static void assertText(Connection connection, String sql, String expected) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next() || !expected.equals(resultSet.getString(1))) {
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
