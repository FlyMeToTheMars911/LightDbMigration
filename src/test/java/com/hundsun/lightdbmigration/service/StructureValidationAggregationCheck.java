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
