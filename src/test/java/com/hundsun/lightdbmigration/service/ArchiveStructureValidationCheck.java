package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 验证归档结构预检汇总主子表差异并阻止数据变更，使用内存 JDBC 代理而非真实连接。
 * Author: Codex
 * date: 2026-10-26
 */
public class ArchiveStructureValidationCheck {

    private static final String MYSQL = MigrationConstants.DB_MYSQL;
    private static final String SELECT_PREFIX = "SELECT";
    private static final String ISSUE_PREFIX = "归档表结构校验问题";
    private static final String FIRST_SOURCE = "CHECK_FIRST_SOURCE";
    private static final String FIRST_TARGET = "CHECK_FIRST_ARCHIVE";
    private static final String SECOND_SOURCE = "CHECK_SECOND_SOURCE";
    private static final String SECOND_TARGET = "CHECK_SECOND_ARCHIVE";
    private static final String CHILD_SOURCE = "CHECK_CHILD_SOURCE";
    private static final String CHILD_TARGET = "CHECK_CHILD_ARCHIVE";
    private static final String MISSING_SOURCE = "CHECK_MISSING_SOURCE";
    private static final String MISSING_TARGET = "CHECK_MISSING_ARCHIVE";
    private static final String ID = "ID";
    private static final String NAME = "NAME";
    private static final String MISSING_COLUMN = "MISSING_COL";
    private static final String EXTRA_COLUMN = "EXTRA_COL";
    private static final String SECOND_COLUMN = "SECOND_VALUE";
    private static final String CHILD_COLUMN = "CHILD_NOTE";
    private static final String AMOUNT_COLUMN = "AMOUNT";
    private static final String REQUIRED_COLUMN = "REQUIRED_VALUE";
    private static final String TIGHTEN_COLUMN = "TIGHTEN_VALUE";
    private static final String INTEGER_TYPE = "INT";
    private static final String TEXT_TYPE = "VARCHAR";
    private static final String UNKNOWN_TYPE = "CUSTOM_UNSUPPORTED";
    private static final String MISSING_STATE = "42S02";
    private static final String CONNECTION_STATE = "08006";
    private static final int MISSING_CODE = 1146;
    private static final int ORACLE_MISSING_CODE = 942;
    private static final int DM_MISSING_CODE = -2106;
    private static final int EXPECTED_PHYSICAL_TABLES = 8;
    private static final int NO_WRITES = 0;
    private static final int FIRST_QUERY = 1;

    /**
     * 执行不依赖真实数据库的结构校验回归。
     * Params: args 未使用。
     * Returns: 无。
     */
    public static void main(String[] args) throws Exception {
        verifyAggregationThroughEnginePrepare();
        verifyCompatibleTablesPass();
        verifyConnectionFailureStopsScanning();
        verifyStopAndInterruptionStopScanning();
        verifyUnknownTypeNeedsManualHandling();
        verifyNullableSourceNeedsManualHandling();
        verifyOracleAndDmScripts();
        verifySharedTargetNeedsManualHandling();
        System.out.println("[RESULT] ARCHIVE_STRUCTURE_VALIDATION_CHECK_PASSED");
    }

    /**
     * 从引擎准备入口验证父表失败仍扫描其余主表及子表，且不会执行数据操作。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyAggregationThroughEnginePrepare() throws Exception {
        Fixture fixture = differences();
        MigrationTask task = task(fixture, MYSQL);
        Method prepare = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class);
        prepare.setAccessible(true);
        Throwable failure = null;
        try {
            prepare.invoke(new ArchiveEngine(), task, fixture.connection(), MYSQL);
        } catch (InvocationTargetException exception) {
            failure = exception.getCause();
        }
        assertTrue(failure instanceof IllegalArgumentException, "引擎没有以汇总结构错误中止");
        assertContains(failure.getMessage(), "一次性", "失败信息缺少一次性汇总说明");
        assertEquals(EXPECTED_PHYSICAL_TABLES, fixture.physicalTablesRead.size(),
            "前一表或父表差异导致后续物理表未检查");
        String logs = issueLogs(task);
        for (String expected : Arrays.asList(NAME, MISSING_COLUMN, EXTRA_COLUMN, AMOUNT_COLUMN,
            REQUIRED_COLUMN, SECOND_COLUMN, CHILD_COLUMN, MISSING_TARGET)) {
            assertContains(logs, expected, "汇总日志遗漏差异 " + expected);
        }
        String script = script(task);
        for (String expected : Arrays.asList(FIRST_TARGET, SECOND_TARGET, CHILD_TARGET, MISSING_TARGET,
            "ALTER TABLE", "CREATE TABLE", "ADD", "MODIFY", MISSING_COLUMN, EXTRA_COLUMN)) {
            assertContains(script, expected, "汇总建议脚本遗漏 " + expected);
        }
        assertContains(script, "工具不会自动执行", "脚本缺少仅供建议的说明");
        for (String line : script.split("\\r?\\n")) {
            String statement = line.trim().toUpperCase(Locale.ROOT);
            assertTrue(statement.startsWith("--") || !statement.matches(".*\\bDROP\\b.*"),
                "多余字段不应生成直接执行的删除 DDL");
        }
        assertEquals(NO_WRITES, fixture.writes, "结构不通过时出现数据写入或删除");
    }

    /**
     * 验证结构一致时不产生差异脚本且不执行写入。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyCompatibleTablesPass() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 30));
        fixture.add(FIRST_TARGET, integer(ID), text(NAME, 30));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, MYSQL);
        new ArchiveStructureValidator().validate(task, fixture.connection(), MYSQL);
        Object script = task.snapshot(true, 0L).get("structureSyncScript");
        assertTrue(script == null || String.valueOf(script).isEmpty(), "一致结构生成了差异脚本");
        assertEquals(NO_WRITES, fixture.writes, "预检成功后不应自行执行数据操作");
    }

    /**
     * 验证数据库连接异常不能作为普通结构差异继续扫描。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyConnectionFailureStopsScanning() throws Exception {
        Fixture fixture = differences();
        fixture.failTable = FIRST_SOURCE;
        fixture.failure = new SQLException("fixture connection lost", CONNECTION_STATE);
        MigrationTask task = task(fixture, MYSQL);
        Throwable failure = validateFailure(task, fixture, MYSQL);
        assertTrue(failure instanceof SQLException, "数据库断连被当成普通差异");
        assertEquals(FIRST_QUERY, fixture.queries.size(), "断连后仍继续查询其它表");
        assertEquals(NO_WRITES, fixture.writes, "断连后执行了数据操作");
    }

    /**
     * 验证预检前终止和线程中断均直接退出，且不访问数据库。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyStopAndInterruptionStopScanning() throws Exception {
        Fixture stoppedFixture = differences();
        MigrationTask stopped = task(stoppedFixture, MYSQL);
        stopped.stop();
        Throwable stopFailure = validateFailure(stopped, stoppedFixture, MYSQL);
        assertTrue(stopFailure instanceof StopRequestedException, "停止请求没有从结构预检退出");
        assertEquals(NO_WRITES, stoppedFixture.queries.size(), "停止后仍读取数据库");

        Fixture interruptedFixture = differences();
        MigrationTask interrupted = task(interruptedFixture, MYSQL);
        Throwable interruption;
        Thread.currentThread().interrupt();
        try {
            interruption = validateFailure(interrupted, interruptedFixture, MYSQL);
        } finally {
            Thread.interrupted();
        }
        assertTrue(interruption instanceof InterruptedException, "线程中断没有从结构预检退出");
        assertEquals(NO_WRITES, interruptedFixture.queries.size(), "中断后仍读取数据库");
    }

    /**
     * 验证无法安全映射的源字段只给人工处理说明。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyUnknownTypeNeedsManualHandling() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), new Column("SPECIAL_DATA", Types.OTHER,
            UNKNOWN_TYPE, 0, 0, DatabaseMetaData.columnNullable));
        fixture.add(FIRST_TARGET, integer(ID));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, MYSQL);
        assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
            "未知类型差异没有阻止执行");
        String script = script(task);
        assertContains(script, "SPECIAL_DATA", "未知字段未出现在脚本说明中");
        assertContains(script, "人工", "未知字段缺少人工处理说明");
        for (String line : script.split("\\r?\\n")) {
            assertTrue(line.trim().startsWith("--") || !line.contains(UNKNOWN_TYPE),
                "未知源类型被直接插入可执行 DDL");
        }
    }

    /**
     * 源可空而目标非空时仍报告差异，但不能生成直接放宽目标约束的可执行 DDL。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyNullableSourceNeedsManualHandling() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 30));
        fixture.add(FIRST_TARGET, integer(ID),
            new Column(NAME, Types.VARCHAR, TEXT_TYPE, 10, 0, DatabaseMetaData.columnNoNulls));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, MYSQL);
        assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
            "目标更严格的非空约束未阻止执行");
        assertContains(issueLogs(task), NAME, "非空约束差异未汇总");
        String script = script(task);
        assertContains(script, "不生成直接放宽非空约束的可执行 DDL", "缺少人工处理非空约束说明");
        for (String line : script.split("\\r?\\n")) {
            String statement = line.trim();
            assertTrue(statement.startsWith("--") || !statement.contains(NAME),
                "目标非空字段被写入可执行修改建议: " + line);
        }
        assertEquals(NO_WRITES, fixture.writes, "非空约束不通过后执行了数据操作");
    }

    /**
     * 验证 Oracle、达梦建议脚本的模式引用、原生类型、增列、改列和建表方言。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyOracleAndDmScripts() throws Exception {
        for (String databaseType : Arrays.asList(MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM)) {
            Fixture fixture = new Fixture();
            boolean oracle = MigrationConstants.DB_ORACLE.equals(databaseType);
            String textType = oracle ? "VARCHAR2" : TEXT_TYPE;
            String decimalType = oracle ? "NUMBER" : "DECIMAL";
            int jdbcDecimal = oracle ? Types.NUMERIC : Types.DECIMAL;
            Column id = new Column(ID, jdbcDecimal, decimalType, 12, 0, DatabaseMetaData.columnNoNulls);
            fixture.add(FIRST_SOURCE, id,
                new Column(NAME, Types.VARCHAR, textType, 80, 0, DatabaseMetaData.columnNullable),
                new Column(MISSING_COLUMN, Types.VARCHAR, textType, 20, 0, DatabaseMetaData.columnNullable),
                new Column(AMOUNT_COLUMN, jdbcDecimal, decimalType, 12, 2, DatabaseMetaData.columnNullable),
                new Column(REQUIRED_COLUMN, Types.VARCHAR, textType, 80, 0, DatabaseMetaData.columnNoNulls),
                new Column(TIGHTEN_COLUMN, Types.VARCHAR, textType, 40, 0, DatabaseMetaData.columnNoNulls));
            fixture.add(FIRST_TARGET, id,
                new Column(NAME, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNullable),
                new Column(AMOUNT_COLUMN, jdbcDecimal, decimalType, 6, 0, DatabaseMetaData.columnNullable),
                new Column(REQUIRED_COLUMN, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNoNulls),
                new Column(TIGHTEN_COLUMN, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNullable));
            fixture.add(MISSING_SOURCE, id,
                new Column("PAYLOAD", Types.VARCHAR, textType, 24, 0, DatabaseMetaData.columnNullable));
            fixture.tables.put(MISSING_TARGET, null);
            fixture.missingState = "42000";
            fixture.missingCode = oracle ? ORACLE_MISSING_CODE : DM_MISSING_CODE;
            ArchiveTableRequest first = table("fixture." + FIRST_SOURCE, "fixture." + FIRST_TARGET,
                "ROOT", null);
            ArchiveTableRequest missing = table("fixture." + MISSING_SOURCE, "fixture." + MISSING_TARGET,
                "MISSING", null);
            fixture.mains.add(first);
            fixture.mains.add(missing);
            MigrationTask task = task(fixture, databaseType);
            assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
                databaseType + "没有汇总全部结构差异");
            String script = script(task);
            String quotedTarget = "\"FIXTURE\".\"" + FIRST_TARGET + "\"";
            assertContains(script, "ALTER TABLE " + quotedTarget + " ADD (\"" + MISSING_COLUMN
                + "\" " + textType + "(20) NULL);", databaseType + "增列语法或模式引用错误");
            assertContains(script, "ALTER TABLE " + quotedTarget + " MODIFY (\"" + NAME
                + "\" " + textType + "(80));", databaseType + "可空字段扩容重复声明NULL或原生类型错误");
            assertContains(script, "MODIFY (\"" + AMOUNT_COLUMN + "\" " + decimalType + "(12,2));",
                databaseType + "改列丢失数字精度或小数位");
            assertContains(script, "MODIFY (\"" + REQUIRED_COLUMN + "\" " + textType + "(80));",
                databaseType + "原已非空字段扩容重复声明NOT NULL");
            assertContains(script, "MODIFY (\"" + TIGHTEN_COLUMN + "\" " + textType + "(40) NOT NULL);",
                databaseType + "需要收紧非空约束时遗漏NOT NULL");
            assertContains(script, "CREATE TABLE \"FIXTURE\".\"" + MISSING_TARGET + "\" (",
                databaseType + "缺表错误号未识别或建表引用错误");
            assertContains(script, "\"" + ID + "\" " + decimalType + "(12,0) NOT NULL",
                databaseType + "建表丢失源字段非空约束");
            assertEquals(NO_WRITES, fixture.writes, databaseType + "预检执行了DDL或数据写入");
        }
    }

    /**
     * 同一物理归档表通过默认库和显式库名重复配置时，不给出互相冲突的可执行 DDL。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifySharedTargetNeedsManualHandling() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 40));
        fixture.add(SECOND_SOURCE, integer(ID), text(NAME, 80));
        fixture.add(FIRST_TARGET, integer(ID), text(NAME, 5));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "FIRST", null));
        fixture.mains.add(table(SECOND_SOURCE, "fixture_db." + FIRST_TARGET, "SECOND", null));
        MigrationTask task = task(fixture, MYSQL);
        assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
            "重复物理归档目标未阻止执行");
        String script = script(task);
        assertContains(script, FIRST_TARGET, "重复目标未出现在建议脚本");
        for (String line : script.split("\\r?\\n")) {
            String statement = line.trim();
            assertTrue(statement.isEmpty() || statement.startsWith("--"),
                "重复归档目标仍产生可执行 DDL: " + line);
        }
        assertTrue(fixture.physicalTablesRead.containsKey(SECOND_SOURCE), "目标重复导致后续源表不再检查");
        assertEquals(NO_WRITES, fixture.writes, "目标重复时执行了数据操作");
    }

    /**
     * 获取预检失败而不隐藏原始异常类型。
     * Params: task 当前任务；fixture JDBC 数据；databaseType 方言。
     * Returns: 预检异常，成功时为 null。
     */
    private static Throwable validateFailure(MigrationTask task, Fixture fixture, String databaseType) {
        try {
            new ArchiveStructureValidator().validate(task, fixture.connection(), databaseType);
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    /**
     * 构造跨多表、同表多处差异和缺表的验证数据。
     * Params: 无。
     * Returns: 数据集。
     */
    private static Fixture differences() {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 40), text(MISSING_COLUMN, 12),
            new Column(AMOUNT_COLUMN, Types.DECIMAL, "DECIMAL", 10, 2, DatabaseMetaData.columnNullable),
            new Column(REQUIRED_COLUMN, Types.VARCHAR, TEXT_TYPE, 20, 0, DatabaseMetaData.columnNoNulls));
        fixture.add(FIRST_TARGET, text(ID, 8), text(NAME, 5), text(EXTRA_COLUMN, 9),
            new Column(AMOUNT_COLUMN, Types.DECIMAL, "DECIMAL", 4, 0, DatabaseMetaData.columnNullable),
            text(REQUIRED_COLUMN, 20));
        fixture.add(SECOND_SOURCE, integer(ID), text(SECOND_COLUMN, 30));
        fixture.add(SECOND_TARGET, integer(ID));
        fixture.add(CHILD_SOURCE, integer(ID), integer("PARENT_ID"), text(CHILD_COLUMN, 20));
        fixture.add(CHILD_TARGET, integer(ID), integer("PARENT_ID"), text(CHILD_COLUMN, 5));
        fixture.add(MISSING_SOURCE, integer(ID), text("PAYLOAD", 12));
        fixture.tables.put(MISSING_TARGET, null);
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        fixture.mains.add(table(SECOND_SOURCE, SECOND_TARGET, "ROOT_TWO", null));
        fixture.mains.add(table(MISSING_SOURCE, MISSING_TARGET, "ROOT_MISSING", null));
        fixture.children.add(table(CHILD_SOURCE, CHILD_TARGET, "CHILD", "ROOT"));
        return fixture;
    }

    /**
     * 创建归档节点。
     * Params: source 源表；target 归档表；node 节点；parent 父节点。
     * Returns: 归档节点。
     */
    private static ArchiveTableRequest table(String source, String target, String node, String parent) {
        ArchiveTableRequest result = new ArchiveTableRequest();
        result.setName(source);
        result.setNodeId(node);
        result.setParentNodeId(parent);
        result.setSourceTable(source);
        result.setTargetTable(target);
        result.setSourceSql("SELECT * FROM " + source);
        return result;
    }

    /**
     * 创建仅用于内存预检的任务。
     * Params: fixture 节点集；databaseType 数据库类型。
     * Returns: 任务。
     */
    private static MigrationTask task(Fixture fixture, String databaseType) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(databaseType);
        config.setDatabase("fixture_db");
        ArchiveRequest archive = new ArchiveRequest();
        archive.setMainTables(fixture.mains);
        archive.setChildTables(fixture.children);
        archive.setConfirmSourceDelete(Boolean.TRUE);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setName("Archive structure fixture");
        request.setSource(config);
        request.setTarget(config);
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSteps(Collections.emptyList());
        request.setArchive(archive);
        return new MigrationTask("ARCHIVE-STRUCTURE-CHECK", request);
    }

    /**
     * 读取逐项差异日志。
     * Params: task 当前任务。
     * Returns: 日志文本。
     */
    @SuppressWarnings("unchecked")
    private static String issueLogs(MigrationTask task) {
        StringBuilder result = new StringBuilder();
        List<Map<String, Object>> logs = (List<Map<String, Object>>)task.snapshot(true, 0L).get("logs");
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.startsWith(ISSUE_PREFIX)) {
                result.append(message).append('\n');
            }
        }
        return result.toString();
    }

    /**
     * 获取建议脚本。
     * Params: task 当前任务。
     * Returns: SQL 文本。
     */
    private static String script(MigrationTask task) {
        return String.valueOf(task.snapshot(true, 0L).get("structureSyncScript"));
    }

    /**
     * 创建非空整数元数据。
     * Params: name 字段名。
     * Returns: 字段。
     */
    private static Column integer(String name) {
        return new Column(name, Types.INTEGER, INTEGER_TYPE, 10, 0, DatabaseMetaData.columnNoNulls);
    }

    /**
     * 创建可空文本元数据。
     * Params: name 字段名；length 长度。
     * Returns: 字段。
     */
    private static Column text(String name, int length) {
        return new Column(name, Types.VARCHAR, TEXT_TYPE, length, 0, DatabaseMetaData.columnNullable);
    }

    /**
     * 断言文本包含内容。
     * Params: actual 实际；expected 期望；message 失败说明。
     * Returns: 无。
     */
    private static void assertContains(String actual, String expected, String message) {
        assertTrue(actual != null && actual.contains(expected), message + ": " + actual);
    }

    /**
     * 断言数值相等。
     * Params: expected 期望；actual 实际；message 失败说明。
     * Returns: 无。
     */
    private static void assertEquals(int expected, int actual, String message) {
        assertTrue(expected == actual, message + ": expected=" + expected + ", actual=" + actual);
    }

    /**
     * 断言条件。
     * Params: condition 条件；message 失败说明。
     * Returns: 无。
     */
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 创建强类型 JDBC 代理。
     * Params: type 接口；handler 处理器。
     * Returns: 代理。
     */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /**
     * 返回未使用 JDBC 方法的默认值。
     * Params: type 返回类型。
     * Returns: 默认值。
     */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        return null;
    }

    /**
     * 内存 JDBC 数据和读写计数。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Fixture {
        /** 物理表字段，null 表示该表不存在。 */
        private final Map<String, List<Column>> tables = new LinkedHashMap<>();
        /** 主表节点。 */
        private final List<ArchiveTableRequest> mains = new ArrayList<>();
        /** 子表节点。 */
        private final List<ArchiveTableRequest> children = new ArrayList<>();
        /** 所有发往 JDBC 的查询。 */
        private final List<String> queries = new ArrayList<>();
        /** 去重后的物理表访问记录。 */
        private final Map<String, Boolean> physicalTablesRead = new LinkedHashMap<>();
        /** 需要模拟异常的表。 */
        private String failTable;
        /** 模拟的连接异常。 */
        private SQLException failure;
        /** 当前方言下缺表的 SQLState。 */
        private String missingState = MISSING_STATE;
        /** 当前方言下缺表的数据库错误号。 */
        private int missingCode = MISSING_CODE;
        /** 写入操作数。 */
        private int writes;

        /**
         * 注册物理表。
         * Params: table 表名；columns 字段。
         * Returns: 无。
         */
        private void add(String table, Column... columns) {
            tables.put(table, Arrays.asList(columns));
        }

        /**
         * 创建只允许零行字段查询的连接。
         * Params: 无。
         * Returns: JDBC 连接代理。
         */
        private Connection connection() {
            return proxy(Connection.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                        return statement(String.valueOf(arguments[0]));
                    case "createStatement":
                        throw new AssertionError("预检不应创建可执行任意写入的 Statement");
                    case "getCatalog":
                        return "fixture_db";
                    case "getSchema":
                        return "FIXTURE";
                    case "hashCode":
                        return System.identityHashCode(object);
                    case "equals":
                        return object == arguments[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 创建查询代理并拒绝写入。
         * Params: sql SQL。
         * Returns: 预编译语句代理。
         */
        private PreparedStatement statement(final String sql) {
            return proxy(PreparedStatement.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "executeQuery":
                        return query(sql);
                    case "execute":
                    case "executeUpdate":
                    case "executeBatch":
                    case "executeLargeBatch":
                    case "executeLargeUpdate":
                    case "addBatch":
                        writes++;
                        throw new AssertionError("归档预检执行了数据或结构写入: " + sql);
                    case "hashCode":
                        return System.identityHashCode(object);
                    case "equals":
                        return object == arguments[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 返回指定物理表的结果集元数据。
         * Params: sql 查询。
         * Returns: 零行结果集。
         */
        private ResultSet query(String sql) throws SQLException {
            queries.add(sql);
            assertTrue(sql.trim().toUpperCase(Locale.ROOT).startsWith(SELECT_PREFIX),
                "预检不是 SELECT 查询: " + sql);
            for (Map.Entry<String, List<Column>> entry : tables.entrySet()) {
                String table = entry.getKey();
                if (Pattern.compile("\\b" + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(sql).find()) {
                    physicalTablesRead.put(table, Boolean.TRUE);
                    if (table.equals(failTable)) {
                        throw failure;
                    }
                    List<Column> columns = entry.getValue();
                    if (columns == null) {
                        throw new SQLException("Table " + table + " doesn't exist", missingState, missingCode);
                    }
                    ResultSetMetaData metadata = metadata(columns);
                    return proxy(ResultSet.class, (object, method, arguments) -> {
                        switch (method.getName()) {
                            case "getMetaData": return metadata;
                            default: return defaultValue(method.getReturnType());
                        }
                    });
                }
            }
            throw new AssertionError("意外查询: " + sql);
        }

        /**
         * 创建字段元数据代理。
         * Params: columns 字段。
         * Returns: 元数据。
         */
        private ResultSetMetaData metadata(List<Column> columns) {
            return proxy(ResultSetMetaData.class, (object, method, arguments) -> {
                String methodName = method.getName();
                switch (methodName) {
                    case "getColumnCount": return columns.size();
                    default: break;
                }
                Column column = columns.get((Integer)arguments[0] - 1);
                switch (methodName) {
                    case "getColumnLabel":
                    case "getColumnName": return column.name;
                    case "getColumnType": return column.jdbcType;
                    case "getColumnTypeName": return column.typeName;
                    case "getPrecision": return column.precision;
                    case "getScale": return column.scale;
                    case "isNullable": return column.nullable;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }
    }

    /**
     * 不可变字段元数据。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Column {
        /** 字段名。 */
        private final String name;
        /** JDBC 类型。 */
        private final int jdbcType;
        /** 数据库原始类型。 */
        private final String typeName;
        /** 字段精度或长度。 */
        private final int precision;
        /** 小数位。 */
        private final int scale;
        /** 可空状态。 */
        private final int nullable;

        /**
         * 记录字段定义。
         * Params: name 字段名；jdbcType JDBC类型；typeName 原始类型；precision 精度；scale 小数位；nullable 可空。
         * Returns: 字段定义。
         */
        private Column(String name, int jdbcType, String typeName, int precision, int scale, int nullable) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
        }
    }
}
