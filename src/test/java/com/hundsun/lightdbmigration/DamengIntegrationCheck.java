package com.hundsun.lightdbmigration;

import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.TaskStatus;
import com.hundsun.lightdbmigration.service.ArchiveEngine;
import com.hundsun.lightdbmigration.service.MigrationEngine;
import com.hundsun.lightdbmigration.service.MigrationTask;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Runs isolated, real DM migration/archive checks and prints executed SQL with bind parameters.
 * Author: Codex
 * date: 2026-10-22
 */
public final class DamengIntegrationCheck {

    private static final String DM_TYPE = "DM";
    private static final String DM_DRIVER = "dm.jdbc.driver.DmDriver";
    private static final String DM_URL_PREFIX = "jdbc:dm://";
    private static final String DEFAULT_DM_PORT = "5236";
    private static final String MAIN_NODE = "MAIN_1";
    private static final String CHILD_NODE = "CHILD_1";
    private static final String ID_COLUMN = "ID";
    private static final String KEYSET_SUFFIX = "KEYSET";
    private static final String IDENTITY_SUFFIX = "IDENTITY";
    private static final String LOB_SOURCE_SUFFIX = "LOB_SRC";
    private static final String LOB_TARGET_SUFFIX = "LOB_DST";
    private static final String ARCHIVE_VALIDATION_PASSED = "全部归档表结构与关联关系校验通过";
    private static final String CREATE_TABLE_SQL = "CREATE TABLE";
    private static final String PREPARE_STATEMENT_METHOD = "prepareStatement";
    private static final String CREATE_STATEMENT_METHOD = "createStatement";
    private static final String GET_METADATA_METHOD = "getMetaData";
    private static final String ADD_BATCH_METHOD = "addBatch";
    private static final String CLEAR_BATCH_METHOD = "clearBatch";
    private static final String CLEAR_PARAMETERS_METHOD = "clearParameters";
    private static final String SET_NULL_METHOD = "setNull";
    private static final String EXECUTE_BATCH_METHOD = "executeBatch";
    private static final String EXECUTE_LARGE_BATCH_METHOD = "executeLargeBatch";
    private static final String EXECUTE_PREFIX = "execute";
    private static final String SET_PREFIX = "set";
    private static final String GET_PREFIX = "get";
    private static final String COMMIT_METHOD = "commit";
    private static final String ROLLBACK_METHOD = "rollback";
    private static final String CLOSE_METHOD = "close";
    private static final int BATCH_SIZE = 2;
    private static final int ARCHIVE_BATCH_SIZE = 1;
    private static final int CLOB_REPEAT_COUNT = 3000;
    private static final int MAX_LOG_PARAMETER_CHARS = 512;
    private static final int SOURCE_ROWS = 5;
    private static final int TOKEN_LENGTH = 8;
    private static final int FIRST_PARAMETER = 0;
    private static final int SECOND_PARAMETER = 1;
    private static final int TWO_PARAMETERS = 2;
    private static final int NO_ARGUMENTS = 0;
    private static final long ZERO_ROWS = 0L;
    private static final long ONE_ROW = 1L;
    private static final long TWO_ROWS = 2L;
    private static final long THREE_ROWS = 3L;
    private static final long FIVE_ROWS = 5L;
    private static final String EXPECTED_AMOUNT = "12.34";
    private static final String EXPECTED_TEXT = "达梦验证";
    private static final byte[] EXPECTED_BINARY = new byte[] {0, 1, 2, 127, (byte)255};

    private final String tablePrefix = "LDM_DM_"
        + UUID.randomUUID().toString().replace("-", "").substring(0, TOKEN_LENGTH).toUpperCase(Locale.ROOT);
    private final List<String> ownedTables = new ArrayList<>();
    private final DatabaseConfig config = databaseConfig();
    private final String schema = SqlSupport.normalizeColumnName(
        optionalEnv("LDM_TEST_DM_SCHEMA", requiredEnv("LDM_TEST_DM_USER")), "DM schema").toUpperCase(Locale.ROOT);
    private Connection verificationConnection;
    private int createdTableCount;

    private DamengIntegrationCheck() {
    }

    /**
     * Opens connections sequentially; any connection failure stops all subsequent connection attempts.
     * Params: args unused; connection settings come from LDM_TEST_DM_* environment variables.
     * Returns: none; throws when any assertion, connection, SQL, or cleanup fails.
     */
    public static void main(String[] args) throws Exception {
        DamengIntegrationCheck check = new DamengIntegrationCheck();
        TraceDriver traceDriver = TraceDriver.install();
        try {
            check.run();
        } finally {
            check.config.clearPassword();
            DriverManager.deregisterDriver(traceDriver);
        }
    }

    private void run() throws Exception {
        System.out.println("[TEST] Dedicated table prefix=" + tablePrefix + "; schema=" + schema);
        try {
            DatabaseConfig defaultSchemaConfig = copyConfig(config);
            defaultSchemaConfig.setDatabase("");
            try {
                System.out.println("[VERIFY] Empty schema connection test=" + DatabaseSupport.test(defaultSchemaConfig));
            } finally {
                defaultSchemaConfig.clearPassword();
            }
            verificationConnection = DatabaseSupport.open(config);
            DatabaseMetaData metadata = verificationConnection.getMetaData();
            System.out.println("[VERIFY] Product=" + metadata.getDatabaseProductName()
                + "; version=" + metadata.getDatabaseProductVersion()
                + "; driver=" + metadata.getDriverVersion());
            prepareMigrationTables();
            verifyStreamingAndKeyset();
            verifyDuplicateHandling();
            verifyIdentityMigration();
            verifyLobAndNullRoundtrip();
            verifyStructureSuggestion();
            prepareArchiveTables();
            verifyArchiveRollbackAndSuccess();
            System.out.println("DAMENG_INTEGRATION_CHECK_PASSED");
        } finally {
            if (verificationConnection != null) {
                try {
                    cleanupOwnedTables();
                } finally {
                    verificationConnection.close();
                }
            }
        }
    }

    private void prepareMigrationTables() throws Exception {
        String columns = "ID NUMBER(12,0) NOT NULL PRIMARY KEY, CODE VARCHAR(64) NOT NULL UNIQUE, "
            + "AMOUNT DECIMAL(12,2), CREATED_AT TIMESTAMP, NOTE VARCHAR(128)";
        for (String suffix : Arrays.asList("SRC", "STREAM", "KEYSET", "DUP", "STRICT")) {
            createTable(suffix, columns);
        }
        for (int id = 1; id <= SOURCE_ROWS; id++) {
            execute("INSERT INTO " + table("SRC")
                + " (ID,CODE,AMOUNT,CREATED_AT,NOTE) VALUES (?,?,?,TIMESTAMP '2026-10-22 12:34:56',?)",
                id, "C" + id, new BigDecimal(EXPECTED_AMOUNT), EXPECTED_TEXT);
        }
    }

    private void verifyStreamingAndKeyset() throws Exception {
        assertTask(runMigration("stream", "STREAM", null, MigrationConstants.INSERT_STRICT), TaskStatus.SUCCEEDED);
        assertTask(runMigration("keyset", KEYSET_SUFFIX, ID_COLUMN, MigrationConstants.INSERT_STRICT),
            TaskStatus.SUCCEEDED);
        assertCount("STREAM", FIVE_ROWS);
        assertCount("KEYSET", FIVE_ROWS);
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT AMOUNT, NOTE, CREATED_AT FROM " + table("KEYSET")
                 + " WHERE ID=1")) {
            require(rows.next(), "Keyset target row missing");
            require(rows.getBigDecimal(1).compareTo(new BigDecimal(EXPECTED_AMOUNT)) == 0,
                "Decimal value changed");
            require(EXPECTED_TEXT.equals(rows.getString(2)), "Chinese text changed");
            require(rows.getTimestamp(3) != null, "Timestamp was lost");
        }
        System.out.println("[PASS] DM streaming/keyset migration, decimal/timestamp/Chinese text");
    }

    private void verifyDuplicateHandling() throws Exception {
        execute("INSERT INTO " + table("DUP")
            + " (ID,CODE,NOTE) VALUES (1,'C1','PREEXISTING')");
        execute("INSERT INTO " + table("DUP")
            + " (ID,CODE,NOTE) VALUES (99,'C2','SECONDARY_UNIQUE')");
        assertTask(runMigration("duplicates", "DUP", ID_COLUMN, MigrationConstants.INSERT_IGNORE),
            TaskStatus.SUCCEEDED);
        assertCount("DUP", FIVE_ROWS);
        assertScalar("SELECT COUNT(*) FROM " + table("DUP") + " WHERE ID=1 AND NOTE='PREEXISTING'", ONE_ROW);
        assertScalar("SELECT COUNT(*) FROM " + table("DUP") + " WHERE ID=2", ZERO_ROWS);
        try {
            execute("INSERT INTO " + table("DUP") + " (ID,CODE) VALUES (1,'NEW_CODE')");
            throw new AssertionError("Duplicate primary key unexpectedly inserted");
        } catch (SQLException expected) {
            require(SqlSupport.isDuplicateKey(expected, DM_TYPE), "Actual DM duplicate error was not recognized");
            System.out.println("[VERIFY] Actual duplicate errorCode=" + expected.getErrorCode()
                + "; sqlState=" + expected.getSQLState());
        }
        execute("INSERT INTO " + table("STRICT") + " (ID,CODE,NOTE) VALUES (2,'C2','PREEXISTING')");
        assertTask(runMigration("strict", "STRICT", ID_COLUMN, MigrationConstants.INSERT_STRICT),
            TaskStatus.FAILED);
        assertCount("STRICT", ONE_ROW);
        System.out.println("[PASS] Primary/secondary duplicate skipping and strict batch rollback");
    }

    private void verifyStructureSuggestion() throws Exception {
        MigrationTask task = runMigration("missing-table", "MISSING", null, MigrationConstants.INSERT_STRICT);
        assertTask(task, TaskStatus.FAILED);
        String script = String.valueOf(task.snapshot(true, ZERO_ROWS).get("structureSyncScript"));
        require(script.contains(CREATE_TABLE_SQL) && script.contains(tablePrefix + "_MISSING"),
            "DM missing-table structure suggestion was not generated: " + script);
        System.out.println("[VERIFY] Suggested SQL (not executed):\n" + script);
        System.out.println("[PASS] DM missing-table detection and structure suggestion");
    }

    private void verifyIdentityMigration() throws Exception {
        createTable(IDENTITY_SUFFIX, "ID INT IDENTITY(100,1) PRIMARY KEY, CODE VARCHAR(64) NOT NULL UNIQUE, "
            + "AMOUNT DECIMAL(12,2), CREATED_AT TIMESTAMP, NOTE VARCHAR(128)");
        assertIdentityMetadata(IDENTITY_SUFFIX);
        String sql = "SELECT CAST(ID AS INTEGER) AS ID,CODE,AMOUNT,CREATED_AT,NOTE FROM " + table("SRC");
        MigrationTask task = runMigrationQuery("identity-preservation", IDENTITY_SUFFIX, ID_COLUMN,
            MigrationConstants.INSERT_STRICT, sql);
        assertTask(task, TaskStatus.SUCCEEDED);
        assertCount(IDENTITY_SUFFIX, FIVE_ROWS);
        assertScalar("SELECT COUNT(*) FROM " + table(IDENTITY_SUFFIX) + " WHERE ID BETWEEN 1 AND 5", FIVE_ROWS);
        System.out.println("[PASS] DM IDENTITY target preserves explicitly supplied source IDs");
    }

    private void verifyLobAndNullRoundtrip() throws Exception {
        String columns = "ID INT NOT NULL PRIMARY KEY, TEXT_DATA CLOB, BINARY_DATA BLOB, OPTIONAL_TEXT VARCHAR(128)";
        createTable(LOB_SOURCE_SUFFIX, columns);
        createTable(LOB_TARGET_SUFFIX, columns);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < CLOB_REPEAT_COUNT; i++) {
            text.append(EXPECTED_TEXT);
        }
        String expectedClob = text.toString();
        String insertSql = "INSERT INTO " + table(LOB_SOURCE_SUFFIX)
            + " (ID,TEXT_DATA,BINARY_DATA,OPTIONAL_TEXT) VALUES (?,?,?,?)";
        execute(insertSql, 1, expectedClob, EXPECTED_BINARY, EXPECTED_TEXT);
        execute(insertSql, 2, null, null, null);
        String sourceSql = "SELECT ID,TEXT_DATA,BINARY_DATA,OPTIONAL_TEXT FROM " + table(LOB_SOURCE_SUFFIX);
        MigrationTask task = runMigrationQuery("lob-roundtrip", LOB_TARGET_SUFFIX, ID_COLUMN,
            MigrationConstants.INSERT_STRICT, sourceSql);
        assertTask(task, TaskStatus.SUCCEEDED);
        assertCount(LOB_TARGET_SUFFIX, TWO_ROWS);
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TEXT_DATA,BINARY_DATA,OPTIONAL_TEXT FROM "
                 + table(LOB_TARGET_SUFFIX) + " WHERE ID=1")) {
            require(rows.next(), "LOB target row missing");
            require(expectedClob.equals(rows.getString(1)), "CLOB content changed");
            require(Arrays.equals(EXPECTED_BINARY, rows.getBytes(2)), "BLOB content changed");
            require(EXPECTED_TEXT.equals(rows.getString(3)), "Optional non-null text changed");
        }
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT TEXT_DATA,BINARY_DATA,OPTIONAL_TEXT FROM "
                 + table(LOB_TARGET_SUFFIX) + " WHERE ID=2")) {
            require(rows.next(), "NULL target row missing");
            require(rows.getString(1) == null, "NULL CLOB changed");
            require(rows.getBytes(2) == null, "NULL BLOB changed");
            require(rows.getString(3) == null, "NULL VARCHAR changed");
        }
        System.out.println("[PASS] DM CLOB/BLOB roundtrip and NULL preservation; CLOB chars=" + expectedClob.length());
    }

    private void prepareArchiveTables() throws Exception {
        createTable("MAIN", "ID INT NOT NULL PRIMARY KEY, NOTE VARCHAR(128)");
        createTable("MAIN_H", "ID INT IDENTITY(100,1) PRIMARY KEY, NOTE VARCHAR(128)");
        createTable("CHILD", "ID INT NOT NULL PRIMARY KEY, MAIN_ID INT NOT NULL, NOTE VARCHAR(128)");
        createTable("CHILD_H", "ID INT IDENTITY(100,1) PRIMARY KEY, MAIN_ID INT NOT NULL, NOTE VARCHAR(128)");
        assertIdentityMetadata("MAIN_H");
        assertIdentityMetadata("CHILD_H");
        execute("INSERT INTO " + table("MAIN") + " (ID,NOTE) VALUES (1,'ARCHIVE_ONE')");
        execute("INSERT INTO " + table("MAIN") + " (ID,NOTE) VALUES (2,'ARCHIVE_TWO')");
        execute("INSERT INTO " + table("MAIN") + " (ID,NOTE) VALUES (3,'KEEP')");
        execute("INSERT INTO " + table("CHILD") + " (ID,MAIN_ID,NOTE) VALUES (11,1,'CHILD_ONE')");
        execute("INSERT INTO " + table("CHILD") + " (ID,MAIN_ID,NOTE) VALUES (12,2,'CHILD_TWO')");
        execute("INSERT INTO " + table("CHILD") + " (ID,MAIN_ID,NOTE) VALUES (13,3,'KEEP')");
    }

    private void verifyArchiveRollbackAndSuccess() throws Exception {
        // Child conflict occurs after the parent archive insert, proving rollback covers both levels.
        try (Statement statement = verificationConnection.createStatement()) {
            statement.execute("SET IDENTITY_INSERT " + table("CHILD_H") + " ON");
        }
        execute("INSERT INTO " + table("CHILD_H") + " (ID,MAIN_ID,NOTE) VALUES (11,1,'CONFLICT')");
        MigrationTask conflict = archiveTask("archive-conflict", ID_COLUMN);
        new ArchiveEngine().execute(conflict);
        assertTask(conflict, TaskStatus.FAILED);
        Map<String, Object> conflictSnapshot = conflict.snapshot(true, ZERO_ROWS);
        require(String.valueOf(conflictSnapshot.get("logs")).contains(ARCHIVE_VALIDATION_PASSED),
            "Archive conflict task failed before preflight completed: " + conflictSnapshot.get("currentMessage"));
        assertCount("MAIN", THREE_ROWS);
        assertCount("CHILD", THREE_ROWS);
        assertCount("MAIN_H", ZERO_ROWS);
        assertCount("CHILD_H", ONE_ROW);
        execute("DELETE FROM " + table("CHILD_H") + " WHERE ID=11");
        MigrationTask successful = archiveTask("archive-success", ID_COLUMN);
        new ArchiveEngine().execute(successful);
        assertTask(successful, TaskStatus.SUCCEEDED);
        assertCount("MAIN", ONE_ROW);
        assertCount("CHILD", ONE_ROW);
        assertCount("MAIN_H", TWO_ROWS);
        assertCount("CHILD_H", TWO_ROWS);
        assertScalar("SELECT COUNT(*) FROM " + table("MAIN") + " WHERE ID=3", ONE_ROW);
        assertScalar("SELECT COUNT(*) FROM " + table("CHILD") + " WHERE MAIN_ID=3", ONE_ROW);
        System.out.println("[PASS] DM IDENTITY parent/child archive, multi-batch table switching and cross-level rollback");
    }

    private MigrationTask runMigration(String name, String targetSuffix, String cursor, String mode) {
        return runMigrationQuery(name, targetSuffix, cursor, mode,
            "SELECT ID,CODE,AMOUNT,CREATED_AT,NOTE FROM " + table("SRC"));
    }

    private MigrationTask runMigrationQuery(String name, String targetSuffix, String cursor, String mode,
        String sourceSql) {
        MigrationStepRequest step = new MigrationStepRequest();
        step.setName(name);
        step.setSourceSql(sourceSql);
        step.setTargetTable(KEYSET_SUFFIX.equals(targetSuffix) ? tablePrefix + "_" + targetSuffix : table(targetSuffix));
        step.setCursorColumn(cursor);
        step.setInsertMode(mode);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName(name);
        request.setTaskType(MigrationConstants.TASK_TYPE_MIGRATION);
        request.setBatchSize(BATCH_SIZE);
        request.setSource(copyConfig(config));
        request.setTarget(copyConfig(config));
        request.setSteps(Collections.singletonList(step));
        MigrationTask task = new MigrationTask(tablePrefix + "-" + name, request);
        new MigrationEngine().execute(task);
        return task;
    }

    private MigrationTask archiveTask(String name, String cursor) {
        ArchiveTableRequest main = archiveTable("MAIN", "MAIN_H", MAIN_NODE, cursor);
        main.setSourceSql("SELECT ID,NOTE FROM " + table("MAIN") + " WHERE ID<=2");
        ArchiveTableRequest child = archiveTable("CHILD", "CHILD_H", CHILD_NODE, null);
        child.setParentNodeId(MAIN_NODE);
        ArchiveRelationRequest relation = new ArchiveRelationRequest();
        relation.setChildColumn("MAIN_ID");
        relation.setMainColumn(ID_COLUMN);
        child.setRelations(Collections.singletonList(relation));
        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        archive.setMainTable(main);
        archive.setMainTables(Collections.singletonList(main));
        archive.setChildTables(Collections.singletonList(child));
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName(name);
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setBatchSize(ARCHIVE_BATCH_SIZE);
        request.setSource(copyConfig(config));
        request.setTarget(copyConfig(config));
        request.setSteps(Collections.emptyList());
        request.setArchive(archive);
        return new MigrationTask(tablePrefix + "-" + name, request);
    }

    private ArchiveTableRequest archiveTable(String source, String target, String nodeId, String cursor) {
        ArchiveTableRequest result = new ArchiveTableRequest();
        result.setName(source);
        result.setNodeId(nodeId);
        result.setSourceTable(table(source));
        result.setTargetTable(table(target));
        result.setCursorColumn(cursor);
        result.setRelations(Collections.emptyList());
        return result;
    }

    private void assertIdentityMetadata(String suffix) throws SQLException {
        try (Statement statement = verificationConnection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT ID FROM " + table(suffix) + " WHERE 1=0")) {
            boolean identity = rows.getMetaData().isAutoIncrement(1);
            System.out.println("[METADATA] " + table(suffix) + ".ID isAutoIncrement=" + identity);
            require(identity, "DM driver did not expose IDENTITY metadata for " + table(suffix));
        }
    }

    private void createTable(String suffix, String columns) throws Exception {
        String name = table(suffix);
        execute(CREATE_TABLE_SQL + " " + name + " (" + columns + ")");
        ownedTables.add(name);
        createdTableCount++;
    }

    private String table(String suffix) {
        return schema + "." + tablePrefix + "_" + suffix;
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = verificationConnection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private void assertCount(String suffix, long expected) throws SQLException {
        assertScalar("SELECT COUNT(*) FROM " + table(suffix), expected);
    }

    private void assertScalar(String sql, long expected) throws SQLException {
        try (Statement statement = verificationConnection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Scalar query returned no row: " + sql);
            long actual = result.getLong(1);
            require(actual == expected, "Scalar mismatch expected=" + expected + ", actual=" + actual + ": " + sql);
            System.out.println("[VERIFY] Scalar result=" + actual);
        }
    }

    private void cleanupOwnedTables() throws Exception {
        List<String> remaining = new ArrayList<>(ownedTables);
        Collections.reverse(remaining);
        List<Exception> errors = new ArrayList<>();
        for (String name : remaining) {
            try {
                execute("DROP TABLE " + name);
                ownedTables.remove(name);
            } catch (Exception exception) {
                errors.add(exception);
                System.out.println("[CLEANUP][FAILED] " + name + ": " + SqlSupport.safeMessage(exception));
            }
        }
        String sql = "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND SUBSTR(TABLE_NAME,1,?)=?";
        try (PreparedStatement statement = verificationConnection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setInt(2, tablePrefix.length());
            statement.setString(3, tablePrefix);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next() && rows.getLong(1) == ZERO_ROWS, "Dedicated test tables remain after cleanup");
            }
        }
        if (!errors.isEmpty()) {
            Exception failure = new Exception("Some dedicated test tables could not be dropped");
            for (Exception error : errors) {
                failure.addSuppressed(error);
            }
            throw failure;
        }
        System.out.println("[CLEANUP] Dedicated tables created=" + createdTableCount
            + "; dropped=" + (createdTableCount - ownedTables.size()) + "; catalog count=0");
    }

    private static DatabaseConfig databaseConfig() {
        DatabaseConfig result = new DatabaseConfig();
        result.setType(DM_TYPE);
        result.setHost(requiredEnv("LDM_TEST_DM_HOST"));
        result.setPort(Integer.valueOf(optionalEnv("LDM_TEST_DM_PORT", DEFAULT_DM_PORT)));
        result.setDatabase(optionalEnv("LDM_TEST_DM_SCHEMA", ""));
        result.setUsername(requiredEnv("LDM_TEST_DM_USER"));
        result.setPassword(requiredEnv("LDM_TEST_DM_PASSWORD"));
        result.setJdbcUrl("");
        return result;
    }

    private static DatabaseConfig copyConfig(DatabaseConfig source) {
        DatabaseConfig result = new DatabaseConfig();
        result.setType(source.getType());
        result.setHost(source.getHost());
        result.setPort(source.getPort());
        result.setDatabase(source.getDatabase());
        result.setUsername(source.getUsername());
        result.setPassword(source.getPassword());
        result.setJdbcUrl(source.getJdbcUrl());
        return result;
    }

    private static void assertTask(MigrationTask task, TaskStatus expected) {
        Map<String, Object> snapshot = task.snapshot(true, ZERO_ROWS);
        require(task.getStatus() == expected, "Task " + task.getId() + " expected=" + expected
            + ", actual=" + task.getStatus() + ", message=" + snapshot.get("currentMessage")
            + ", logs=" + snapshot.get("logs"));
        System.out.println("[VERIFY] Task=" + task.getId() + "; status=" + expected
            + "; processedRows=" + snapshot.get("processedRows"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + name);
        }
        return value;
    }

    private static String optionalEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Object traceParameter(Object parameter) {
        if (parameter instanceof byte[]) {
            return Arrays.toString((byte[])parameter);
        }
        if (parameter instanceof String && ((String)parameter).length() > MAX_LOG_PARAMETER_CHARS) {
            String value = (String)parameter;
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                String hash = String.format(Locale.ROOT, "%064x", new BigInteger(1, digest));
                return "String(chars=" + value.length() + ",utf8Bytes=" + bytes.length + ",sha256=" + hash + ")";
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 unavailable", exception);
            }
        }
        return parameter;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(DamengIntegrationCheck.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Connection traceConnection(final Connection connection) {
        return proxy(Connection.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (PREPARE_STATEMENT_METHOD.equals(name)) {
                    System.out.println("[PREPARE] " + args[FIRST_PARAMETER]);
                }
                Object result = DamengIntegrationCheck.invoke(connection, method, args);
                if (PREPARE_STATEMENT_METHOD.equals(name)) {
                    return traceStatement((PreparedStatement)result, (String)args[FIRST_PARAMETER]);
                }
                if (CREATE_STATEMENT_METHOD.equals(name)) {
                    return traceStatement((Statement)result, null);
                }
                if (GET_METADATA_METHOD.equals(name)) {
                    return traceMetadata((DatabaseMetaData)result);
                }
                if (COMMIT_METHOD.equals(name) || ROLLBACK_METHOD.equals(name) || CLOSE_METHOD.equals(name)) {
                    System.out.println("[JDBC] " + name);
                }
                return result;
            }
        });
    }

    private static DatabaseMetaData traceMetadata(final DatabaseMetaData metadata) {
        return proxy(DatabaseMetaData.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if (method.getName().startsWith(GET_PREFIX)) {
                    System.out.println("[METADATA] " + method.getName() + " "
                        + (args == null ? "[]" : Arrays.deepToString(args)));
                }
                return DamengIntegrationCheck.invoke(metadata, method, args);
            }
        });
    }

    private static Statement traceStatement(final Statement statement, final String sql) {
        InvocationHandler handler = new InvocationHandler() {
            private final Map<Integer, Object> parameters = new LinkedHashMap<>();
            private final List<Map<Integer, Object>> batches = new ArrayList<>();

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if (sql != null && name.startsWith(SET_PREFIX) && args != null
                    && args.length >= TWO_PARAMETERS && args[FIRST_PARAMETER] instanceof Integer) {
                    Object parameter = SET_NULL_METHOD.equals(name) ? null : args[SECOND_PARAMETER];
                    parameters.put((Integer)args[FIRST_PARAMETER], traceParameter(parameter));
                }
                if (CLEAR_PARAMETERS_METHOD.equals(name)) {
                    parameters.clear();
                }
                if (CLEAR_BATCH_METHOD.equals(name)) {
                    batches.clear();
                }
                if (ADD_BATCH_METHOD.equals(name) && (args == null || args.length == NO_ARGUMENTS)) {
                    batches.add(new LinkedHashMap<>(parameters));
                }
                if (name.startsWith(EXECUTE_PREFIX)) {
                    String executingSql = sql == null && args != null && args.length > NO_ARGUMENTS
                        && args[FIRST_PARAMETER] instanceof String ? (String)args[FIRST_PARAMETER] : sql;
                    boolean batch = EXECUTE_BATCH_METHOD.equals(name) || EXECUTE_LARGE_BATCH_METHOD.equals(name);
                    System.out.println("[SQL] " + executingSql + (batch ? " [batches=" + batches + "]"
                        : " [binds=" + parameters + "]"));
                }
                try {
                    return DamengIntegrationCheck.invoke(statement, method, args);
                } finally {
                    if (EXECUTE_BATCH_METHOD.equals(name) || EXECUTE_LARGE_BATCH_METHOD.equals(name)) {
                        batches.clear();
                    }
                }
            }
        };
        Class<?> statementType = sql == null ? Statement.class : PreparedStatement.class;
        return (Statement)Proxy.newProxyInstance(DamengIntegrationCheck.class.getClassLoader(),
            new Class<?>[] {statementType}, handler);
    }

    /**
     * Wraps only the DM driver; records operations and prevents reconnecting after a connection failure.
     * Author: Codex
     * date: 2026-10-22
     */
    private static final class TraceDriver implements Driver {
        private final Driver delegate;
        private final AtomicBoolean connectionFailed = new AtomicBoolean(false);

        private TraceDriver(Driver delegate) {
            this.delegate = delegate;
        }

        private static TraceDriver install() throws Exception {
            Class<?> driverClass = Class.forName(DM_DRIVER);
            Driver actualDriver = (Driver)driverClass.getDeclaredConstructor().newInstance();
            Enumeration<Driver> registered = DriverManager.getDrivers();
            while (registered.hasMoreElements()) {
                Driver driver = registered.nextElement();
                if (DM_DRIVER.equals(driver.getClass().getName())) {
                    DriverManager.deregisterDriver(driver);
                }
            }
            TraceDriver result = new TraceDriver(actualDriver);
            DriverManager.registerDriver(result);
            return result;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            if (connectionFailed.get()) {
                throw new SQLNonTransientConnectionException("Previous connection failed; no further login attempts allowed");
            }
            System.out.println("[CONNECT] DM endpoint=" + url.replaceAll("\\?.*", ""));
            try {
                return traceConnection(delegate.connect(url, info));
            } catch (SQLException exception) {
                connectionFailed.set(true);
                System.out.println("[CONNECT][FAILED] errorCode=" + exception.getErrorCode()
                    + "; sqlState=" + exception.getSQLState());
                throw exception;
            }
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith(DM_URL_PREFIX);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(DamengIntegrationCheck.class.getName());
        }
    }
}
