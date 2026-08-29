package com.hundsun.lightdbmigration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指定现场库的隔离集成检查。连接信息只从进程环境读取，不写入源码、文件或输出。
 */
public class LiveDatabaseIntegrationCheck {

    private static final String DEFAULT_API_BASE = "http://127.0.0.1:8088/api";
    private static final String MYSQL_SOURCE = "LDM_IT_MY_SRC_829";
    private static final String MYSQL_TARGET = "LDM_IT_MY_DST_829";
    private static final String ORACLE_SOURCE = "LDM_IT_ORA_SRC_829";
    private static final String ORACLE_TARGET_ONE = "LDM_IT_ORA_D1_829";
    private static final String ORACLE_TARGET_TWO = "LDM_IT_ORA_D2_829";
    private static final String ORACLE_CONTROL_TARGET = "LDM_IT_ORA_CTL_829";
    private static final int MYSQL_SOURCE_ROWS = 250;
    private static final int ORACLE_SOURCE_ROWS = 120;
    private static final int SECOND_STEP_ROWS = 13;
    private static final int HTTP_TIMEOUT_MS = 15000;
    private static final int TASK_TIMEOUT_SECONDS = 120;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Set<String> createdMysqlTables = new LinkedHashSet<>();
    private final Set<String> createdOracleTables = new LinkedHashSet<>();
    private final List<String> taskIds = new ArrayList<>();
    private String apiBase = DEFAULT_API_BASE;
    private String mysqlSchema;
    private String oracleSchema;
    private Connection mysqlConnection;
    private Connection oracleConnection;

    public static void main(String[] args) throws Exception {
        new LiveDatabaseIntegrationCheck().run();
    }

    private void run() throws Exception {
        Throwable failure = null;
        try {
            openConnectionsOnce();
            assertTestTablesAbsent();
            createAndSeedTables();
            runConcurrentBidirectionalMigration();
            verifyMainMigration();
            runPauseResumeTerminateCheck();
            System.out.println("[RESULT] ALL_INTEGRATION_CHECKS_PASSED");
        } catch (Throwable throwable) {
            failure = throwable;
            System.out.println("[RESULT] INTEGRATION_CHECK_FAILED: " + safeMessage(throwable));
            throw throwable;
        } finally {
            stopRemainingTasks();
            Exception cleanupFailure = null;
            try {
                cleanupOwnedTables();
            } catch (Exception exception) {
                cleanupFailure = exception;
                System.out.println("[CLEANUP][ERROR] " + safeMessage(exception));
            }
            closeQuietly(oracleConnection);
            closeQuietly(mysqlConnection);
            if (cleanupFailure != null && failure != null) {
                failure.addSuppressed(cleanupFailure);
            } else if (cleanupFailure != null) {
                throw cleanupFailure;
            } else if (failure == null) {
                System.out.println("[RESULT] TEST_TABLES_CLEANED");
            }
        }
    }

    private void openConnectionsOnce() throws Exception {
        String mysqlHost = requiredEnv("LDM_TEST_MYSQL_HOST");
        String mysqlPort = requiredEnv("LDM_TEST_MYSQL_PORT");
        mysqlSchema = requiredEnv("LDM_TEST_MYSQL_DATABASE");
        String mysqlUser = requiredEnv("LDM_TEST_MYSQL_USER");
        String mysqlPassword = requiredEnv("LDM_TEST_MYSQL_PASSWORD");
        String oracleHost = requiredEnv("LDM_TEST_ORACLE_HOST");
        String oraclePort = requiredEnv("LDM_TEST_ORACLE_PORT");
        String oracleService = requiredEnv("LDM_TEST_ORACLE_SERVICE");
        oracleSchema = requiredEnv("LDM_TEST_ORACLE_SCHEMA");
        String oracleUser = requiredEnv("LDM_TEST_ORACLE_USER");
        String oraclePassword = requiredEnv("LDM_TEST_ORACLE_PASSWORD");

        Class.forName("com.mysql.cj.jdbc.Driver");
        Class.forName("oracle.jdbc.OracleDriver");
        apiBase = optionalEnv("LDM_TEST_API_BASE", DEFAULT_API_BASE);
        String mysqlUrl = "jdbc:mysql://" + mysqlHost + ":" + mysqlPort + "/" + mysqlSchema
            + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai";
        String oracleUrl = "jdbc:oracle:thin:@//" + oracleHost + ":" + oraclePort + "/" + oracleService;
        mysqlConnection = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
        System.out.println("[CONNECT] MySQL connected once");
        oracleConnection = DriverManager.getConnection(oracleUrl, oracleUser, oraclePassword);
        System.out.println("[CONNECT] Oracle connected once");
    }

    private void assertTestTablesAbsent() throws SQLException {
        List<String> mysqlTables = Arrays.asList(MYSQL_SOURCE, MYSQL_TARGET);
        for (String table : mysqlTables) {
            String sql = "SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = ?";
            System.out.println("[SQL][MySQL][PRECHECK] " + sql + " [table_name=" + table + "]");
            if (queryCount(mysqlConnection, sql, table) != 0L) {
                throw new IllegalStateException("专用测试表已存在，未执行任何建表操作：" + table);
            }
        }
        List<String> oracleTables = Arrays.asList(ORACLE_SOURCE, ORACLE_TARGET_ONE, ORACLE_TARGET_TWO,
            ORACLE_CONTROL_TARGET);
        for (String table : oracleTables) {
            String sql = "SELECT COUNT(*) FROM user_tables WHERE table_name = ?";
            System.out.println("[SQL][Oracle][PRECHECK] " + sql + " [table_name=" + table + "]");
            if (queryCount(oracleConnection, sql, table) != 0L) {
                throw new IllegalStateException("专用测试表已存在，未执行任何建表操作：" + table);
            }
        }
        System.out.println("[CHECK] All dedicated table names are absent; setup is isolated");
    }

    private void createAndSeedTables() throws SQLException {
        executeDdl(mysqlConnection, mysqlSourceDdl(), "MySQL");
        createdMysqlTables.add(MYSQL_SOURCE);
        executeDdl(mysqlConnection, mysqlTargetDdl(), "MySQL");
        createdMysqlTables.add(MYSQL_TARGET);

        executeDdl(oracleConnection, oracleSourceDdl(), "Oracle");
        createdOracleTables.add(ORACLE_SOURCE);
        executeDdl(oracleConnection, oracleTargetDdl(ORACLE_TARGET_ONE), "Oracle");
        createdOracleTables.add(ORACLE_TARGET_ONE);
        executeDdl(oracleConnection, oracleTargetDdl(ORACLE_TARGET_TWO), "Oracle");
        createdOracleTables.add(ORACLE_TARGET_TWO);
        executeDdl(oracleConnection, oracleTargetDdl(ORACLE_CONTROL_TARGET), "Oracle");
        createdOracleTables.add(ORACLE_CONTROL_TARGET);

        mysqlConnection.setAutoCommit(false);
        String mysqlInsert = "INSERT INTO " + MYSQL_SOURCE
            + " (CODE, AMOUNT, CREATED_AT, NOTE) VALUES (?, ?, ?, ?)";
        System.out.println("[SQL][MySQL][BATCH x" + MYSQL_SOURCE_ROWS + "] " + mysqlInsert);
        try (PreparedStatement statement = mysqlConnection.prepareStatement(mysqlInsert)) {
            for (int i = 1; i <= MYSQL_SOURCE_ROWS; i++) {
                statement.setString(1, String.format("C%04d", i));
                statement.setBigDecimal(2, new BigDecimal(i + ".11"));
                statement.setTimestamp(3, new Timestamp(1704067200000L + i * 1000L));
                statement.setString(4, "MYSQL_ROW_" + i);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        mysqlConnection.commit();
        mysqlConnection.setAutoCommit(true);

        oracleConnection.setAutoCommit(false);
        String oracleInsert = "INSERT INTO " + ORACLE_SOURCE
            + " (ID, CODE, AMOUNT, CREATED_AT, NOTE) VALUES (?, ?, ?, ?, ?)";
        System.out.println("[SQL][Oracle][BATCH x" + ORACLE_SOURCE_ROWS + "] " + oracleInsert);
        try (PreparedStatement statement = oracleConnection.prepareStatement(oracleInsert)) {
            for (int i = 1; i <= ORACLE_SOURCE_ROWS; i++) {
                statement.setLong(1, i);
                statement.setString(2, String.format("O%04d", i));
                statement.setBigDecimal(3, new BigDecimal(i + ".22"));
                statement.setTimestamp(4, new Timestamp(1711929600000L + i * 1000L));
                statement.setString(5, "ORACLE_ROW_" + i);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        oracleConnection.commit();
        oracleConnection.setAutoCommit(true);

        String oracleDuplicate = "INSERT INTO " + ORACLE_TARGET_ONE
            + " (ID, CODE, AMOUNT, CREATED_AT, NOTE) VALUES (1, 'C0001', 1.11,"
            + " TIMESTAMP '2024-01-01 00:00:01', 'PREEXISTING_ORACLE')";
        executeUpdate(oracleConnection, oracleDuplicate, "Oracle");
        String mysqlDuplicate = "INSERT INTO " + MYSQL_TARGET
            + " (ID, CODE, AMOUNT, CREATED_AT, NOTE) VALUES"
            + " (1, 'O0001', 1.22, '2024-04-01 00:00:01', 'PREEXISTING_MYSQL')";
        executeUpdate(mysqlConnection, mysqlDuplicate, "MySQL");
        System.out.println("[SETUP] Dedicated test data created");
    }

    private void runConcurrentBidirectionalMigration() throws Exception {
        Map<String, Object> mysqlToOracle = taskPayload("IT-MySQL-to-Oracle-multi-sql", 37,
            mysqlDatabaseConfig(), oracleDatabaseConfig(), Arrays.asList(
                step("MySQL全量游标迁移",
                    "SELECT ID, CODE, AMOUNT, CREATED_AT, NOTE FROM " + MYSQL_SOURCE,
                    oracleSchema + "." + ORACLE_TARGET_ONE, "ID"),
                step("MySQL子集游标迁移",
                    "SELECT ID, CODE, AMOUNT, CREATED_AT, NOTE FROM " + MYSQL_SOURCE + " WHERE ID <= 13",
                    oracleSchema + "." + ORACLE_TARGET_TWO, "ID")));
        Map<String, Object> oracleToMysql = taskPayload("IT-Oracle-to-MySQL-stream", 29,
            oracleDatabaseConfig(), mysqlDatabaseConfig(), Collections.singletonList(
                step("Oracle流式迁移",
                    "SELECT ID, CODE, AMOUNT, CREATED_AT, NOTE FROM " + oracleSchema + "." + ORACLE_SOURCE
                        + " ORDER BY ID",
                    mysqlSchema + "." + MYSQL_TARGET, "")));

        String taskOne = createTask(mysqlToOracle);
        String taskTwo = createTask(oracleToMysql);
        taskIds.add(taskOne);
        taskIds.add(taskTwo);
        post("/tasks/" + taskOne + "/start", null);
        post("/tasks/" + taskTwo + "/start", null);
        System.out.println("[API] Two migration tasks started concurrently");
        assertTaskSucceeded(waitForTerminal(taskOne, TASK_TIMEOUT_SECONDS));
        assertTaskSucceeded(waitForTerminal(taskTwo, TASK_TIMEOUT_SECONDS));
    }

    private void verifyMainMigration() throws SQLException {
        assertEquals(MYSQL_SOURCE_ROWS,
            queryCount(oracleConnection, "SELECT COUNT(*) FROM " + ORACLE_TARGET_ONE),
            "MySQL -> Oracle row count");
        assertEquals(SECOND_STEP_ROWS,
            queryCount(oracleConnection, "SELECT COUNT(*) FROM " + ORACLE_TARGET_TWO),
            "multi-SQL second step row count");
        assertEquals(ORACLE_SOURCE_ROWS,
            queryCount(mysqlConnection, "SELECT COUNT(*) FROM " + MYSQL_TARGET),
            "Oracle -> MySQL row count");
        assertEquals("PREEXISTING_ORACLE",
            queryString(oracleConnection, "SELECT NOTE FROM " + ORACLE_TARGET_ONE + " WHERE ID = 1"),
            "Oracle duplicate row preserved");
        assertEquals("PREEXISTING_MYSQL",
            queryString(mysqlConnection, "SELECT NOTE FROM " + MYSQL_TARGET + " WHERE ID = 1"),
            "MySQL duplicate row preserved");
        System.out.println("[VERIFY] Bidirectional counts, multi-SQL order and duplicate-ignore behavior passed");
    }

    private void runPauseResumeTerminateCheck() throws Exception {
        Map<String, Object> payload = taskPayload("IT-pause-resume-terminate", 1,
            mysqlDatabaseConfig(), oracleDatabaseConfig(), Collections.singletonList(
                step("批次控制验证",
                    "SELECT ID, CODE, AMOUNT, CREATED_AT, NOTE FROM " + MYSQL_SOURCE,
                    oracleSchema + "." + ORACLE_CONTROL_TARGET, "ID")));
        String taskId = createTask(payload);
        taskIds.add(taskId);
        post("/tasks/" + taskId + "/start", null);
        post("/tasks/" + taskId + "/pause", null);
        Map<String, Object> paused = waitForStatus(taskId, Arrays.asList("PAUSED"), 30);
        System.out.println("[VERIFY] Pause reached status=" + paused.get("status"));
        post("/tasks/" + taskId + "/resume", null);
        waitForProcessedRows(taskId, 1L, 30);
        post("/tasks/" + taskId + "/terminate", null);
        Map<String, Object> terminated = waitForStatus(taskId, Arrays.asList("TERMINATED"), 30);
        long controlCount = queryCount(oracleConnection, "SELECT COUNT(*) FROM " + ORACLE_CONTROL_TARGET);
        if (controlCount <= 0L || controlCount >= MYSQL_SOURCE_ROWS) {
            throw new IllegalStateException("终止验证目标表行数不在预期范围内：" + controlCount);
        }
        System.out.println("[VERIFY] Resume and terminate passed; committed control rows=" + controlCount
            + ", finalStatus=" + terminated.get("status"));
    }

    private String createTask(Map<String, Object> payload) throws Exception {
        Map<String, Object> response = post("/tasks", payload);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)response.get("data");
        String id = String.valueOf(data.get("id"));
        System.out.println("[API] Task created: " + id);
        return id;
    }

    private Map<String, Object> waitForTerminal(String taskId, int timeoutSeconds) throws Exception {
        return waitForStatus(taskId, Arrays.asList("SUCCEEDED", "FAILED", "TERMINATED"), timeoutSeconds);
    }

    private Map<String, Object> waitForStatus(String taskId, List<String> expectedStatuses, int timeoutSeconds)
        throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> task = getTask(taskId);
            String status = String.valueOf(task.get("status"));
            if (expectedStatuses.contains(status)) {
                return task;
            }
            if (Arrays.asList("FAILED", "TERMINATED", "SUCCEEDED").contains(status)) {
                throw new IllegalStateException("任务提前进入状态 " + status + "：" + lastLog(task));
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("等待任务状态超时：" + taskId + " -> " + expectedStatuses);
    }

    private void waitForProcessedRows(String taskId, long minimumRows, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> task = getTask(taskId);
            long processed = ((Number)task.get("processedRows")).longValue();
            if (processed >= minimumRows) {
                return;
            }
            String status = String.valueOf(task.get("status"));
            if (Arrays.asList("FAILED", "TERMINATED", "SUCCEEDED").contains(status)) {
                throw new IllegalStateException("任务在达到终止验证点前结束：" + status + "，" + lastLog(task));
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("等待任务产生已提交批次超时：" + taskId);
    }

    private Map<String, Object> getTask(String taskId) throws Exception {
        Map<String, Object> response = request("GET", "/tasks/" + taskId + "?afterLog=0", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>)response.get("data");
        return data;
    }

    private void assertTaskSucceeded(Map<String, Object> task) {
        if (!"SUCCEEDED".equals(String.valueOf(task.get("status")))) {
            throw new IllegalStateException("迁移任务未成功：" + task.get("id") + "，" + lastLog(task));
        }
        System.out.println("[VERIFY] Task succeeded: " + task.get("id") + ", processedRows="
            + task.get("processedRows"));
    }

    private String lastLog(Map<String, Object> task) {
        Object logsValue = task.get("logs");
        if (!(logsValue instanceof List) || ((List<?>)logsValue).isEmpty()) {
            return String.valueOf(task.get("currentMessage"));
        }
        List<?> logs = (List<?>)logsValue;
        Object last = logs.get(logs.size() - 1);
        return last instanceof Map ? String.valueOf(((Map<?, ?>)last).get("message")) : String.valueOf(last);
    }

    private void stopRemainingTasks() {
        for (String taskId : taskIds) {
            try {
                Map<String, Object> task = getTask(taskId);
                String status = String.valueOf(task.get("status"));
                if (!Arrays.asList("SUCCEEDED", "FAILED", "TERMINATED").contains(status)) {
                    post("/tasks/" + taskId + "/terminate", null);
                    waitForTerminal(taskId, 10);
                }
            } catch (Exception exception) {
                System.out.println("[CLEANUP][WARN] Could not confirm task stop: " + taskId + " "
                    + safeMessage(exception));
            }
        }
    }

    private void cleanupOwnedTables() throws Exception {
        List<String> cleanupErrors = new ArrayList<>();
        List<String> oracleTables = new ArrayList<>(createdOracleTables);
        Collections.reverse(oracleTables);
        for (String table : oracleTables) {
            try {
                String sql = "DROP TABLE " + table + " PURGE";
                executeDdl(oracleConnection, sql, "Oracle");
                createdOracleTables.remove(table);
            } catch (Exception exception) {
                cleanupErrors.add("Oracle table cleanup failed: " + table + " " + safeMessage(exception));
            }
        }
        List<String> mysqlTables = new ArrayList<>(createdMysqlTables);
        Collections.reverse(mysqlTables);
        for (String table : mysqlTables) {
            try {
                String sql = "DROP TABLE " + table;
                executeDdl(mysqlConnection, sql, "MySQL");
                createdMysqlTables.remove(table);
            } catch (Exception exception) {
                cleanupErrors.add("MySQL table cleanup failed: " + table + " " + safeMessage(exception));
            }
        }
        try {
            assertAllTestTablesAbsentAfterCleanup();
        } catch (Exception exception) {
            cleanupErrors.add("Post-cleanup check failed: " + safeMessage(exception));
        }
        if (!cleanupErrors.isEmpty()) {
            throw new IllegalStateException(String.join("; ", cleanupErrors));
        }
    }

    private void assertAllTestTablesAbsentAfterCleanup() throws SQLException {
        for (String table : Arrays.asList(MYSQL_SOURCE, MYSQL_TARGET)) {
            long count = queryCount(mysqlConnection,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                table);
            if (count != 0L) {
                throw new IllegalStateException("MySQL 测试表仍存在：" + table);
            }
        }
        for (String table : Arrays.asList(ORACLE_SOURCE, ORACLE_TARGET_ONE, ORACLE_TARGET_TWO,
            ORACLE_CONTROL_TARGET)) {
            long count = queryCount(oracleConnection, "SELECT COUNT(*) FROM user_tables WHERE table_name = ?", table);
            if (count != 0L) {
                throw new IllegalStateException("Oracle 测试表仍存在：" + table);
            }
        }
        System.out.println("[CLEANUP] Post-check confirmed all dedicated test tables are absent");
    }

    private Map<String, Object> mysqlDatabaseConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "MYSQL");
        result.put("host", requiredEnv("LDM_TEST_MYSQL_HOST"));
        result.put("port", Integer.parseInt(requiredEnv("LDM_TEST_MYSQL_PORT")));
        result.put("database", mysqlSchema);
        result.put("username", requiredEnv("LDM_TEST_MYSQL_USER"));
        result.put("password", requiredEnv("LDM_TEST_MYSQL_PASSWORD"));
        result.put("oracleMode", "SERVICE");
        result.put("jdbcUrl", "");
        return result;
    }

    private Map<String, Object> oracleDatabaseConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "ORACLE");
        result.put("host", requiredEnv("LDM_TEST_ORACLE_HOST"));
        result.put("port", Integer.parseInt(requiredEnv("LDM_TEST_ORACLE_PORT")));
        result.put("database", requiredEnv("LDM_TEST_ORACLE_SERVICE"));
        result.put("username", requiredEnv("LDM_TEST_ORACLE_USER"));
        result.put("password", requiredEnv("LDM_TEST_ORACLE_PASSWORD"));
        result.put("oracleMode", "SERVICE");
        result.put("jdbcUrl", "");
        return result;
    }

    private Map<String, Object> taskPayload(String name, int batchSize, Map<String, Object> source,
        Map<String, Object> target, List<Map<String, Object>> steps) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("batchSize", batchSize);
        result.put("source", source);
        result.put("target", target);
        result.put("steps", steps);
        return result;
    }

    private Map<String, Object> step(String name, String sourceSql, String targetTable, String cursorColumn) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("sourceSql", sourceSql);
        result.put("targetTable", targetTable);
        result.put("cursorColumn", cursorColumn);
        result.put("insertMode", "IGNORE_DUPLICATE");
        return result;
    }

    private Map<String, Object> post(String path, Object body) throws Exception {
        return request("POST", path, body);
    }

    private Map<String, Object> request(String method, String path, Object body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(apiBase + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(body);
            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String responseText = readAll(stream);
        Map<String, Object> response = OBJECT_MAPPER.readValue(responseText,
            new TypeReference<Map<String, Object>>() { });
        if (status >= 400 || Boolean.FALSE.equals(response.get("success"))) {
            throw new IllegalStateException("HTTP " + status + " " + response.get("message"));
        }
        return response;
    }

    private String readAll(InputStream inputStream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private long queryCount(Connection connection, String sql, String... parameters) throws SQLException {
        System.out.println("[SQL][QUERY] " + sql + (parameters.length == 0 ? "" : " [binds="
            + Arrays.toString(parameters) + "]"));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("COUNT query returned no row");
                }
                return resultSet.getLong(1);
            }
        }
    }

    private String queryString(Connection connection, String sql) throws SQLException {
        System.out.println("[SQL][QUERY] " + sql);
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new SQLException("String query returned no row");
            }
            return resultSet.getString(1);
        }
    }

    private void executeDdl(Connection connection, String sql, String database) throws SQLException {
        System.out.println("[SQL][" + database + "][DDL] " + sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void executeUpdate(Connection connection, String sql, String database) throws SQLException {
        System.out.println("[SQL][" + database + "][DML] " + sql);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private String mysqlSourceDdl() {
        return "CREATE TABLE " + MYSQL_SOURCE + " ("
            + "ID BIGINT NOT NULL AUTO_INCREMENT, CODE VARCHAR(32) NOT NULL, AMOUNT DECIMAL(12,2),"
            + " CREATED_AT DATETIME, NOTE VARCHAR(200), PRIMARY KEY (ID), UNIQUE KEY UK_LDM_MY_SRC_829 (CODE))"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    private String mysqlTargetDdl() {
        return "CREATE TABLE " + MYSQL_TARGET + " ("
            + "ID BIGINT NOT NULL, CODE VARCHAR(32) NOT NULL, AMOUNT DECIMAL(12,2),"
            + " CREATED_AT DATETIME, NOTE VARCHAR(200), PRIMARY KEY (ID), UNIQUE KEY UK_LDM_MY_DST_829 (CODE))"
            + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    private String oracleSourceDdl() {
        return "CREATE TABLE " + ORACLE_SOURCE + " ("
            + "ID NUMBER(19,0) NOT NULL, CODE VARCHAR2(32 CHAR) NOT NULL, AMOUNT NUMBER(12,2),"
            + " CREATED_AT TIMESTAMP, NOTE VARCHAR2(200 CHAR), CONSTRAINT PK_LDM_ORA_SRC_829 PRIMARY KEY (ID),"
            + " CONSTRAINT UK_LDM_ORA_SRC_829 UNIQUE (CODE))";
    }

    private String oracleTargetDdl(String table) {
        String suffix;
        if (ORACLE_TARGET_ONE.equals(table)) {
            suffix = "D1";
        } else if (ORACLE_TARGET_TWO.equals(table)) {
            suffix = "D2";
        } else {
            suffix = "CTL";
        }
        return "CREATE TABLE " + table + " ("
            + "ID NUMBER(19,0) NOT NULL, CODE VARCHAR2(32 CHAR) NOT NULL, AMOUNT NUMBER(12,2),"
            + " CREATED_AT TIMESTAMP, NOTE VARCHAR2(200 CHAR), CONSTRAINT PK_LDM_ORA_" + suffix
            + "_829 PRIMARY KEY (ID), CONSTRAINT UK_LDM_ORA_" + suffix + "_829 UNIQUE (CODE))";
    }

    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required integration-test environment variable: " + name);
        }
        return value;
    }

    private String optionalEnv(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? defaultValue : value;
    }

    private void assertEquals(long expected, long actual, String subject) {
        if (expected != actual) {
            throw new IllegalStateException(subject + " expected=" + expected + " actual=" + actual);
        }
    }

    private void assertEquals(String expected, String actual, String subject) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(subject + " expected=" + expected + " actual=" + actual);
        }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 测试结束，清理结果已经单独输出。
            }
        }
    }
}
