package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

/**
 * 使用轻量 JDBC 代理验证：同一步骤和后续步骤的结构问题会一次性汇总。
 */
public class StructureValidationAggregationCheck {

    private static final String SOURCE_ONE = "SOURCE_ONE";
    private static final String SOURCE_TWO = "SOURCE_TWO";
    private static final String TARGET_ONE = "TARGET_ONE";
    private static final String TARGET_TWO = "TARGET_TWO";
    private static final String ISSUE_LOG_PREFIX = "表结构校验问题";
    private static final int EXPECTED_ISSUES = 7;
    private static final int EXPECTED_STEPS = 2;
    private static final long MOCK_TOTAL_ROWS = 2L;
    private static final String APPLICATION_CURSOR = "APP_UUID";
    private static final String CURSOR_SOURCE = "CURSOR_SOURCE";
    private static final String CURSOR_TARGET = "CURSOR_TARGET";
    private static final long MOCK_BATCH_ELAPSED_MILLIS = 5L;
    private static final String BROKEN_SOURCE = "BROKEN_SOURCE";
    private static final int FIRST_NUMERIC_MINIMUM_INDEX = 2;
    private static final int SECOND_NUMERIC_MINIMUM_INDEX = 4;
    private static final String JDBC_PREPARE_STATEMENT = "prepareStatement";

    private final AtomicInteger sourceMetadataQueries = new AtomicInteger();
    private final AtomicInteger targetMetadataQueries = new AtomicInteger();
    private final AtomicInteger statisticsQueries = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        new StructureValidationAggregationCheck().run();
        System.out.println("[RESULT] STRUCTURE_VALIDATION_AGGREGATION_CHECK_PASSED");
    }

    private void run() throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSource(databaseConfig());
        request.setTarget(databaseConfig());
        request.setSteps(Arrays.asList(
            step("第一步多字段错误", "SELECT A, A, B, C FROM " + SOURCE_ONE, TARGET_ONE, "NO_CURSOR"),
            step("第二步继续校验", "SELECT X, Y FROM " + SOURCE_TWO, TARGET_TWO, null)));
        MigrationTask task = new MigrationTask("VALIDATION-AGGREGATION-CHECK", request);

        MigrationEngine engine = new MigrationEngine();
        Method method = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        String summary = null;
        try {
            method.invoke(engine, task, connection(true), connection(false),
                MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (!(cause instanceof IllegalArgumentException)) {
                throw exception;
            }
            summary = cause.getMessage();
        }

        assertContains(summary, "共发现 " + EXPECTED_ISSUES + " 个问题", "汇总异常的问题数量不正确");
        assertEquals(EXPECTED_STEPS, sourceMetadataQueries.get(), "后续源查询步骤没有继续校验");
        assertEquals(EXPECTED_STEPS, targetMetadataQueries.get(), "后续目标表没有继续校验");
        assertEquals(EXPECTED_STEPS, statisticsQueries.get(), "后续步骤没有继续执行预检统计");

        Map<String, Object> snapshot = task.snapshot(true, 0L);
        String structureSyncScript = String.valueOf(snapshot.get("structureSyncScript"));
        assertContains(structureSyncScript, "工具不会自动执行", "同步脚本缺少安全提示");
        assertContains(structureSyncScript,
            "ALTER TABLE `" + TARGET_ONE + "` MODIFY COLUMN `A` VARCHAR(20);",
            "同步脚本缺少文本扩容建议");
        assertContains(structureSyncScript,
            "ALTER TABLE `" + TARGET_ONE + "` ADD COLUMN `B` DECIMAL(10,0) NULL;",
            "同步脚本缺少字段新增建议");
        assertContains(structureSyncScript,
            "ALTER TABLE `" + TARGET_TWO + "` MODIFY COLUMN `X` DECIMAL(10,0);",
            "同步脚本缺少跨类型调整建议");
        assertContains(structureSyncScript,
            "ALTER TABLE `" + TARGET_TWO + "` ADD COLUMN `Y` VARCHAR(10) NULL;",
            "同步脚本缺少后续步骤的字段新增建议");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>)snapshot.get("logs");
        List<String> issueLogs = new ArrayList<>();
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.startsWith(ISSUE_LOG_PREFIX)) {
                issueLogs.add(message);
            }
        }
        assertEquals(EXPECTED_ISSUES, issueLogs.size(), "任务日志没有一次性输出全部问题");
        assertLogContains(issueLogs, "存在重复字段名或重复别名：A");
        assertLogContains(issueLogs, "目标长度 10 小于源长度 20");
        assertLogContains(issueLogs, "源查询字段 B 在目标表 " + TARGET_ONE + " 中不存在");
        assertLogContains(issueLogs, "字段 C 源类型为 VARCHAR，目标表 " + TARGET_ONE + " 类型为 INTEGER");
        assertLogContains(issueLogs, "游标字段 NO_CURSOR 不在源查询结果中");
        assertLogContains(issueLogs, "字段 X 源类型为 INTEGER，目标表 " + TARGET_TWO + " 类型为 VARCHAR");
        assertLogContains(issueLogs, "源查询字段 Y 在目标表 " + TARGET_TWO + " 中不存在");
        verifyOracleCreateTableScript(engine);
        verifyConnectionFailureDetection(engine);
        verifyConfiguredTextCursorAccepted(engine);
        verifyBatchTimingLog(engine);
        verifyMetadataFailureKeepsLaterSteps(engine);
        verifyNumericReadFailureKeepsOtherFields(engine);
        verifyScriptCommentsAndConflictingSuggestions(engine);
        verifyModifyPreservesTargetConstraints(engine);
        verifyDefaultNamespaceAliases(engine);
    }

    private void verifyDefaultNamespaceAliases(MigrationEngine engine) throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSource(databaseConfig());
        request.setTarget(databaseConfig());
        request.setSteps(Arrays.asList(
            step("未限定模式", "SELECT NAME FROM " + SOURCE_ONE, TARGET_ONE, null),
            step("限定默认模式", "SELECT NAME FROM " + SOURCE_TWO, "test_db." + TARGET_ONE, null)));
        MigrationTask task = new MigrationTask("DEFAULT-NAMESPACE-ALIASES-CHECK", request);
        Method create = MigrationEngine.class.getDeclaredMethod("createStructureStatements", MigrationTask.class,
            Connection.class, String.class, List.class);
        create.setAccessible(true);
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method addColumn = MigrationEngine.class.getDeclaredMethod("addColumnStatement", Set.class,
            QualifiedName.class, columnMetaClass, String.class);
        addColumn.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> statements = (Set<String>)create.invoke(engine, task, connection(false),
            MigrationConstants.DB_MYSQL, request.getSteps());
        QualifiedName unqualified = SqlSupport.parseTargetTable(TARGET_ONE);
        QualifiedName qualified = SqlSupport.parseTargetTable("test_db." + TARGET_ONE);
        Object column = columnMeta(TARGET_ONE, Types.VARCHAR, "VARCHAR", 20);
        addColumn.invoke(engine, statements, unqualified, column, MigrationConstants.DB_MYSQL);
        addColumn.invoke(engine, statements, qualified, column, MigrationConstants.DB_MYSQL);
        assertEquals(1, statements.size(), "默认模式别名下相同 DDL 未去重");
        assertContains(statements.iterator().next(), "ALTER TABLE `" + TARGET_ONE + "` ADD COLUMN `" + TARGET_ONE + "`",
            "归一化冲突键改变了实际输出的表名或同名字段引用");
        addColumn.invoke(engine, statements, qualified,
            columnMeta(TARGET_ONE, Types.VARCHAR, "VARCHAR", 40), MigrationConstants.DB_MYSQL);
        assertContains(statements.toString(), "存在冲突的结构建议", "默认模式别名绕过了字段建议冲突检查");
        for (String statement : statements) {
            assertNoExecutableLine(statement, "ALTER TABLE", "默认模式别名下冲突建议仍然可执行");
        }
        Connection unreadableNamespace = proxy(Connection.class, (proxy, method, arguments) -> {
            throw new SQLException("默认库元数据不可读取");
        });
        @SuppressWarnings("unchecked")
        Set<String> ambiguous = (Set<String>)create.invoke(engine, task, unreadableNamespace,
            MigrationConstants.DB_MYSQL, request.getSteps());
        addColumn.invoke(engine, ambiguous, unqualified, column, MigrationConstants.DB_MYSQL);
        addColumn.invoke(engine, ambiguous, qualified,
            columnMeta(TARGET_ONE, Types.VARCHAR, "VARCHAR", 40), MigrationConstants.DB_MYSQL);
        assertContains(ambiguous.toString(), "目标默认模式未知", "未知默认库未明确说明别名冲突风险");
        for (String statement : ambiguous) {
            assertNoExecutableLine(statement, "ALTER TABLE", "默认库不可读取时仍输出歧义目标的可执行建议");
        }
    }

    private void verifyMetadataFailureKeepsLaterSteps(MigrationEngine engine) throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSource(databaseConfig());
        request.setTarget(databaseConfig());
        request.setSteps(Arrays.asList(
            step("源字段结构读取失败", "SELECT A FROM " + BROKEN_SOURCE, TARGET_ONE, null),
            step("后续步骤仍比较", "SELECT X, Y FROM " + SOURCE_TWO, TARGET_TWO, null)));
        MigrationTask task = new MigrationTask("METADATA-FAILURE-CHECK", request);
        final Connection delegate = connection(true);
        Connection source = proxy(Connection.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                if (JDBC_PREPARE_STATEMENT.equals(method.getName())
                    && String.valueOf(arguments[0]).contains(BROKEN_SOURCE)) {
                    throw new SQLException("无法读取源查询\r\nDROP TABLE SHOULD_STAY_COMMENT;");
                }
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            }
        });
        Method method = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(engine, task, source, connection(false),
                MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
            throw new AssertionError("源字段结构读取失败应汇总报错");
        } catch (InvocationTargetException exception) {
            if (!(exception.getCause() instanceof IllegalArgumentException)) {
                throw exception;
            }
        }
        String script = String.valueOf(task.snapshot(true, 0L).get("structureSyncScript"));
        assertContains(script, "源查询无法读取字段结构", "普通元数据错误未收集");
        assertContains(script, "ALTER TABLE `" + TARGET_TWO + "` ADD COLUMN `Y`",
            "源字段结构读取失败阻止了后续步骤的结构建议");
        assertNoExecutableLine(script, "DROP TABLE", "异常消息换行逃逸了 SQL 注释");
    }

    private void verifyNumericReadFailureKeepsOtherFields(MigrationEngine engine) throws Exception {
        final ResultSet statistics = proxy(ResultSet.class, new InvocationHandler() {
            private boolean first = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) throws SQLException {
                switch (method.getName()) {
                    case "next":
                        boolean result = first;
                        first = false;
                        return result;
                    case "getLong":
                        return MOCK_TOTAL_ROWS;
                    case "getBigDecimal":
                        int index = (Integer)arguments[0];
                        if (index == FIRST_NUMERIC_MINIMUM_INDEX) {
                            throw new SQLException("A 的最小值无法转换");
                        }
                        return index == SECOND_NUMERIC_MINIMUM_INDEX ? BigDecimal.ZERO : new BigDecimal("999");
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        });
        Connection source = proxy(Connection.class, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                switch (method.getName()) {
                    case "prepareStatement":
                        return proxy(PreparedStatement.class, new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] arguments) {
                                switch (method.getName()) {
                                    case "executeQuery":
                                        return statistics;
                                    default:
                                        return defaultValue(method.getReturnType());
                                }
                            }
                        });
                    default:
                        return defaultValue(method.getReturnType());
                }
            }
        });
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method compatibility = MigrationEngine.class.getDeclaredMethod("validateTypeCompatibility",
            columnMetaClass, columnMetaClass, String.class);
        compatibility.setAccessible(true);
        List<Object> checks = Arrays.asList(
            compatibility.invoke(engine, columnMeta("A", Types.NUMERIC, "NUMBER", 20),
                columnMeta("A", Types.INTEGER, "INTEGER", 10), TARGET_ONE),
            compatibility.invoke(engine, columnMeta("B", Types.NUMERIC, "NUMBER", 20),
                columnMeta("B", Types.TINYINT, "TINYINT", 3, DatabaseMetaData.columnNoNulls), TARGET_ONE));
        Set<String> issues = new LinkedHashSet<>();
        Set<String> statements = new LinkedHashSet<>();
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Arrays.asList(step("数值范围独立校验", "SELECT A, B FROM NUMERIC_SOURCE", TARGET_ONE, null)));
        MigrationTask task = new MigrationTask("NUMERIC-READ-FAILURE-CHECK", request);
        Method statisticsMethod = MigrationEngine.class.getDeclaredMethod("queryCountAndValidateNumericRanges",
            MigrationTask.class, Connection.class, String.class, String.class, QualifiedName.class, String.class,
            columnMetaClass, List.class, Set.class, Set.class, String.class);
        statisticsMethod.setAccessible(true);
        statisticsMethod.invoke(engine, task, source, "SELECT A, B FROM NUMERIC_SOURCE",
            MigrationConstants.DB_MYSQL, SqlSupport.parseTargetTable(TARGET_ONE), MigrationConstants.DB_MYSQL,
            null, checks, issues, statements, "数值步骤");
        assertEquals(2, issues.size(), "一个数值字段读取失败阻止了其他数值字段的检查");
        assertLogContains(new ArrayList<>(issues), "字段 A 数值范围读取失败");
        assertLogContains(new ArrayList<>(issues), "字段 B 源查询实际范围");
        assertContains(statements.toString(), "MODIFY COLUMN `B` DECIMAL(20,0) NOT NULL;",
            "后续数值字段超限未生成保留目标非空约束的结构建议");
    }

    private void verifyModifyPreservesTargetConstraints(MigrationEngine engine) throws Exception {
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method modify = MigrationEngine.class.getDeclaredMethod("addModifyColumnStatement", Set.class,
            QualifiedName.class, columnMetaClass, columnMetaClass, String.class);
        modify.setAccessible(true);
        Object source = columnMeta("NAME", Types.VARCHAR, "VARCHAR", 40);
        QualifiedName table = SqlSupport.parseTargetTable(TARGET_ONE);
        Set<String> statements = new LinkedHashSet<>();
        modify.invoke(engine, statements, table, source,
            columnMeta("NAME", Types.VARCHAR, "VARCHAR", 10, DatabaseMetaData.columnNoNulls),
            MigrationConstants.DB_MYSQL);
        assertContains(statements.toString(), "MODIFY COLUMN `NAME` VARCHAR(40) NOT NULL;",
            "类型调整建议隐式放宽了目标已有非空约束");
        statements.clear();
        modify.invoke(engine, statements, table, source,
            columnMeta("NAME", Types.VARCHAR, "VARCHAR", 10, DatabaseMetaData.columnNullableUnknown),
            MigrationConstants.DB_MYSQL);
        assertContains(statements.toString(), "原有可空约束未知", "约束未知时未提示人工处理");
        assertNoExecutableLine(statements.iterator().next(), "ALTER TABLE", "约束未知时仍生成了可执行 MODIFY");
    }

    private void verifyScriptCommentsAndConflictingSuggestions(MigrationEngine engine) throws Exception {
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Class<?> statementsClass = Class.forName(MigrationEngine.class.getName() + "$StructureStatements");
        Constructor<?> statementsConstructor = statementsClass.getDeclaredConstructor();
        statementsConstructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> statements = (Set<String>)statementsConstructor.newInstance();
        QualifiedName target = SqlSupport.parseTargetTable(TARGET_ONE);
        Method addColumn = MigrationEngine.class.getDeclaredMethod("addColumnStatement", Set.class,
            QualifiedName.class, columnMetaClass, String.class);
        addColumn.setAccessible(true);
        Object first = columnMeta("NAME", Types.VARCHAR, "VARCHAR", 20);
        addColumn.invoke(engine, statements, target, first, MigrationConstants.DB_MYSQL);
        addColumn.invoke(engine, statements, target, first, MigrationConstants.DB_MYSQL);
        assertEquals(1, statements.size(), "相同字段建议未去重");
        addColumn.invoke(engine, statements, target,
            columnMeta("NAME", Types.VARCHAR, "VARCHAR", 40), MigrationConstants.DB_MYSQL);
        addColumn.invoke(engine, statements, target,
            columnMeta("UNSAFE\nDROP TABLE SHOULD_STAY_COMMENT;", Types.OTHER, "OTHER", 0),
            MigrationConstants.DB_MYSQL);
        Method buildScript = MigrationEngine.class.getDeclaredMethod("buildStructureSyncScript", String.class,
            Set.class, Set.class);
        buildScript.setAccessible(true);
        String script = (String)buildScript.invoke(engine, MigrationConstants.DB_MYSQL,
            new LinkedHashSet<>(Arrays.asList("错误\r\nDROP TABLE SHOULD_STAY_COMMENT;\u2028其他错误")), statements);
        assertContains(script, "存在冲突的结构建议", "跨步骤相同字段的不同建议未提示冲突");
        assertContains(script, "VARCHAR(20)", "冲突时丢失了第一项建议");
        assertContains(script, "VARCHAR(40)", "冲突时丢失了后续建议");
        assertContains(script, "字段名称包含空值或控制字符", "异常元数据字段名称未给人工建议");
        assertNoExecutableLine(script, "ALTER TABLE", "冲突建议仍生成可执行 DDL");
        assertNoExecutableLine(script, "DROP TABLE", "元数据字段或异常消息逃逸了 SQL 注释");
    }

    private Object columnMeta(String name, int jdbcType, String typeName, int precision) throws Exception {
        return columnMeta(name, jdbcType, typeName, precision, DatabaseMetaData.columnNullable);
    }

    private Object columnMeta(String name, int jdbcType, String typeName, int precision, int nullable) throws Exception {
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Constructor<?> constructor = columnMetaClass.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, jdbcType, typeName, precision, 0, nullable, false);
    }

    private void assertNoExecutableLine(String script, String prefix, String message) {
        for (String line : script.split("\\R")) {
            if (line.trim().startsWith(prefix)) {
                throw new AssertionError(message + "，实际=" + script);
            }
        }
    }

    private void verifyBatchTimingLog(MigrationEngine engine) throws Exception {
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Constructor<?> columnConstructor = columnMetaClass.getDeclaredConstructor(String.class, int.class,
            String.class, int.class, int.class, int.class, boolean.class);
        columnConstructor.setAccessible(true);
        Object idColumn = columnConstructor.newInstance("ID", Types.BIGINT, "BIGINT", 19, 0,
            DatabaseMetaData.columnNoNulls, false);
        List<Object> columns = Arrays.asList(idColumn);

        Class<?> preparedStepClass = Class.forName(MigrationEngine.class.getName() + "$PreparedStep");
        Constructor<?> stepConstructor = preparedStepClass.getDeclaredConstructor(int.class, String.class,
            String.class, QualifiedName.class, List.class, List.class, int.class, long.class, String.class,
            String.class, String.class, boolean.class);
        stepConstructor.setAccessible(true);
        Object preparedStep = stepConstructor.newInstance(0, "批次耗时测试", "SELECT ID FROM TIMING_SOURCE",
            SqlSupport.parseTargetTable("TIMING_TARGET"), columns, columns, -1, MOCK_TOTAL_ROWS,
            MigrationConstants.INSERT_IGNORE, "INSERT INTO TIMING_TARGET (`ID`) VALUES (?)",
            "INSERT INTO TIMING_TARGET (`ID`) VALUES (?)", false);

        CreateTaskRequest request = new CreateTaskRequest();
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSource(databaseConfig());
        request.setTarget(databaseConfig());
        request.setSteps(Arrays.asList(step("批次耗时测试", "SELECT ID FROM TIMING_SOURCE", "TIMING_TARGET", null)));
        MigrationTask task = new MigrationTask("BATCH-TIMING-CHECK", request);
        Method writeAndReport = MigrationEngine.class.getDeclaredMethod("writeAndReportBatch",
            MigrationTask.class, Connection.class, preparedStepClass, List.class, String.class, long.class);
        writeAndReport.setAccessible(true);
        List<Object[]> batch = Arrays.asList(new Object[] {1L}, new Object[] {2L});
        long batchStartedNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(MOCK_BATCH_ELAPSED_MILLIS);
        writeAndReport.invoke(engine, task, batchConnection(), preparedStep, batch, MigrationConstants.DB_MYSQL,
            batchStartedNanos);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>)task.snapshot(true, 0L).get("logs");
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.matches(".*完成第 1 批.*批次总耗时 [0-9]+ ms（目标写入及提交 [0-9]+ ms）")) {
                return;
            }
        }
        throw new AssertionError("批次完成日志缺少总耗时或目标写入提交耗时：" + logs);
    }

    private void verifyConfiguredTextCursorAccepted(MigrationEngine engine) throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSource(databaseConfig());
        request.setTarget(databaseConfig());
        request.setSteps(Arrays.asList(step("应用生成字符串游标",
            "SELECT APP_UUID, NAME FROM " + CURSOR_SOURCE, CURSOR_TARGET, APPLICATION_CURSOR)));
        MigrationTask task = new MigrationTask("TEXT-CURSOR-CHECK", request);
        AtomicReference<String> statisticsSql = new AtomicReference<>();

        Method method = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Object> preparedSteps = (List<Object>)method.invoke(engine, task,
            cursorConnection(true, statisticsSql), cursorConnection(false, statisticsSql),
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);

        assertEquals(1, preparedSteps.size(), "页面配置的字符串游标未被认可");
        assertContains(statisticsSql.get(), "COUNT(DISTINCT LM_STATS.`" + APPLICATION_CURSOR + "`)",
            "字符串游标未在同一次预检统计中校验唯一性");
        Map<String, Object> snapshot = task.snapshot(true, 0L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> logs = (List<Map<String, Object>>)snapshot.get("logs");
        boolean acceptedLogFound = false;
        for (Map<String, Object> log : logs) {
            if (String.valueOf(log.get("message")).contains("不依赖数据库自增标记或数值类型")) {
                acceptedLogFound = true;
                break;
            }
        }
        if (!acceptedLogFound) {
            throw new AssertionError("任务日志缺少页面游标配置认可说明");
        }
    }

    private void verifyOracleCreateTableScript(MigrationEngine engine) throws Exception {
        Class<?> columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Constructor<?> constructor = columnMetaClass.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        Object actId = constructor.newInstance("ACT_ID", Types.NUMERIC, "NUMBER", 20, 0,
            DatabaseMetaData.columnNullable, false);
        Object taskName = constructor.newInstance("TASK_NAME", Types.VARCHAR, "VARCHAR2", 50, 0,
            DatabaseMetaData.columnNullable, false);
        List<Object> columns = Arrays.asList(actId, taskName);
        Set<String> statements = new LinkedHashSet<>();
        QualifiedName targetName = SqlSupport.parseTargetTable("TEST_SCHEMA.TEST_TASK_NEW");
        Method addCreateTable = MigrationEngine.class.getDeclaredMethod("addCreateTableStatement", Set.class,
            QualifiedName.class, List.class, String.class);
        addCreateTable.setAccessible(true);
        addCreateTable.invoke(engine, statements, targetName, columns, MigrationConstants.DB_ORACLE);
        assertEquals(1, statements.size(), "Oracle CREATE TABLE 建议脚本数量不正确");
        String script = statements.iterator().next();
        assertContains(script, "CREATE TABLE \"TEST_SCHEMA\".\"TEST_TASK_NEW\"", "Oracle 表名引用不正确");
        assertContains(script, "\"ACT_ID\" NUMBER(20,0) NULL", "Oracle NUMBER 类型映射不正确");
        assertContains(script, "\"TASK_NAME\" VARCHAR2(50 CHAR) NULL", "Oracle VARCHAR2 类型映射不正确");
    }

    private void verifyConnectionFailureDetection(MigrationEngine engine) throws Exception {
        Method method = MigrationEngine.class.getDeclaredMethod("isConnectionFailure", Throwable.class);
        method.setAccessible(true);
        boolean connectionFailure = (Boolean)method.invoke(engine,
            new SQLException("connection closed", "08006"));
        boolean statementFailure = (Boolean)method.invoke(engine,
            new SQLException("table not found", "42S02"));
        if (!connectionFailure || statementFailure) {
            throw new AssertionError("数据库连接故障和普通结构错误的分类不正确");
        }
    }

    private MigrationStepRequest step(String name, String sourceSql, String targetTable, String cursorColumn) {
        MigrationStepRequest request = new MigrationStepRequest();
        request.setName(name);
        request.setSourceSql(sourceSql);
        request.setTargetTable(targetTable);
        request.setCursorColumn(cursorColumn);
        request.setInsertMode(MigrationConstants.INSERT_IGNORE);
        return request;
    }

    private DatabaseConfig databaseConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(MigrationConstants.DB_MYSQL);
        config.setHost("127.0.0.1");
        config.setPort(MigrationConstants.DEFAULT_MYSQL_PORT);
        config.setDatabase("test_db");
        config.setUsername("test");
        return config;
    }

    private Connection connection(final boolean source) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    return preparedStatement(String.valueOf(arguments[0]), source);
                }
                if ("getMetaData".equals(name)) {
                    return databaseMetaData();
                }
                if ("getCatalog".equals(name)) {
                    return "test_db";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(Connection.class, handler);
    }

    private Connection cursorConnection(final boolean source, final AtomicReference<String> statisticsSql) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    return cursorPreparedStatement(String.valueOf(arguments[0]), source, statisticsSql);
                }
                if ("getMetaData".equals(name)) {
                    return databaseMetaData();
                }
                if ("getCatalog".equals(name)) {
                    return "test_db";
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(Connection.class, handler);
    }

    private PreparedStatement cursorPreparedStatement(final String sql, final boolean source,
        final AtomicReference<String> statisticsSql) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("executeQuery".equals(name)) {
                    if (sql.contains("WHERE 1 = 0")) {
                        return metadataResultSet(Arrays.asList(
                            column(APPLICATION_CURSOR, Types.VARCHAR, "VARCHAR", 64),
                            column("NAME", Types.VARCHAR, "VARCHAR", 100)));
                    }
                    if (source) {
                        statisticsSql.set(sql);
                        return cursorStatisticsResultSet();
                    }
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(PreparedStatement.class, handler);
    }

    private Connection batchConnection() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("prepareStatement".equals(name)) {
                    return batchPreparedStatement();
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(Connection.class, handler);
    }

    private PreparedStatement batchPreparedStatement() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("executeBatch".equals(name)) {
                    return new int[] {1, 1};
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(PreparedStatement.class, handler);
    }

    private ResultSet cursorStatisticsResultSet() {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                if ("next".equals(method.getName())) {
                    boolean result = first;
                    first = false;
                    return result;
                }
                if ("getLong".equals(method.getName())) {
                    return MOCK_TOTAL_ROWS;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(ResultSet.class, handler);
    }

    private PreparedStatement preparedStatement(final String sql, final boolean source) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("executeQuery".equals(name)) {
                    if (sql.contains("WHERE 1 = 0")) {
                        if (source) {
                            sourceMetadataQueries.incrementAndGet();
                        } else {
                            targetMetadataQueries.incrementAndGet();
                        }
                        return metadataResultSet(columnsFor(sql, source));
                    }
                    statisticsQueries.incrementAndGet();
                    return countResultSet();
                }
                if ("hashCode".equals(name)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(name)) {
                    return proxy == arguments[0];
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(PreparedStatement.class, handler);
    }

    private List<ColumnDef> columnsFor(String sql, boolean source) {
        if (source && sql.contains(SOURCE_TWO)) {
            return Arrays.asList(column("X", Types.INTEGER, "INTEGER", 10),
                column("Y", Types.VARCHAR, "VARCHAR", 10));
        }
        if (source) {
            return Arrays.asList(column("A", Types.VARCHAR, "VARCHAR", 20),
                column("A", Types.VARCHAR, "VARCHAR", 20),
                column("B", Types.INTEGER, "INTEGER", 10),
                column("C", Types.VARCHAR, "VARCHAR", 30));
        }
        if (sql.contains(TARGET_TWO)) {
            return Arrays.asList(column("X", Types.VARCHAR, "VARCHAR", 10),
                column("Z", Types.VARCHAR, "VARCHAR", 10));
        }
        return Arrays.asList(column("A", Types.VARCHAR, "VARCHAR", 10),
            column("C", Types.INTEGER, "INTEGER", 10));
    }

    private ResultSet metadataResultSet(final List<ColumnDef> columns) {
        final ResultSetMetaData metaData = resultSetMetaData(columns);
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                if ("getMetaData".equals(method.getName())) {
                    return metaData;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(ResultSet.class, handler);
    }

    private ResultSet countResultSet() {
        InvocationHandler handler = new InvocationHandler() {
            private boolean first = true;

            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("next".equals(name)) {
                    boolean result = first;
                    first = false;
                    return result;
                }
                if ("getLong".equals(name)) {
                    return MOCK_TOTAL_ROWS;
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(ResultSet.class, handler);
    }

    private ResultSetMetaData resultSetMetaData(final List<ColumnDef> columns) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("getColumnCount".equals(name)) {
                    return columns.size();
                }
                if (arguments != null && arguments.length == 1 && arguments[0] instanceof Integer) {
                    ColumnDef column = columns.get(((Integer)arguments[0]) - 1);
                    if ("getColumnLabel".equals(name) || "getColumnName".equals(name)) {
                        return column.name;
                    }
                    if ("getColumnType".equals(name)) {
                        return column.jdbcType;
                    }
                    if ("getColumnTypeName".equals(name)) {
                        return column.typeName;
                    }
                    if ("getPrecision".equals(name)) {
                        return column.precision;
                    }
                    if ("getScale".equals(name)) {
                        return 0;
                    }
                    if ("isNullable".equals(name)) {
                        return ResultSetMetaData.columnNullable;
                    }
                    if ("isAutoIncrement".equals(name)) {
                        return false;
                    }
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(ResultSetMetaData.class, handler);
    }

    private DatabaseMetaData databaseMetaData() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                String methodName = method.getName();
                if ("getColumns".equals(methodName) || "getPrimaryKeys".equals(methodName)
                    || "getIndexInfo".equals(methodName)) {
                    return emptyResultSet();
                }
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(DatabaseMetaData.class, handler);
    }

    private ResultSet emptyResultSet() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] arguments) {
                return defaultValue(method.getReturnType());
            }
        };
        return proxy(ResultSet.class, handler);
    }

    private ColumnDef column(String name, int jdbcType, String typeName, int precision) {
        return new ColumnDef(name, jdbcType, typeName, precision);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (Boolean.TYPE.equals(type)) {
            return false;
        }
        if (Byte.TYPE.equals(type)) {
            return (byte)0;
        }
        if (Short.TYPE.equals(type)) {
            return (short)0;
        }
        if (Integer.TYPE.equals(type)) {
            return 0;
        }
        if (Long.TYPE.equals(type)) {
            return 0L;
        }
        if (Float.TYPE.equals(type)) {
            return 0F;
        }
        if (Double.TYPE.equals(type)) {
            return 0D;
        }
        if (Character.TYPE.equals(type)) {
            return (char)0;
        }
        return null;
    }

    private void assertLogContains(List<String> logs, String expected) {
        for (String log : logs) {
            if (log.contains(expected)) {
                return;
            }
        }
        throw new AssertionError("日志中缺少：" + expected + "，实际=" + logs);
    }

    private void assertContains(String actual, String expected, String message) {
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(message + "，实际=" + actual);
        }
    }

    private void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + "，expected=" + expected + "，actual=" + actual);
        }
    }

    private static final class ColumnDef {
        private final String name;
        private final int jdbcType;
        private final String typeName;
        private final int precision;

        private ColumnDef(String name, int jdbcType, String typeName, int precision) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.precision = precision;
        }
    }
}
