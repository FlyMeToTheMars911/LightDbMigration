package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 达梦 SQL 方言、当前模式及批量重复键回退的隔离回归检查。
 * Author: Codex
 * Date: 2026-10-22
 */
public class DamengSqlCompatibilityCheck {

    private static final int DUPLICATE_KEY_CODE = -6602;
    private static final int NAMED_DUPLICATE_KEY_CODE = -6625;
    private static final int FOREIGN_KEY_CODE = -6607;
    private static final int MISSING_TABLE_CODE = -2106;
    private static final int FIRST_ROW = 1;
    private static final int SECOND_ROW = 2;
    private static final int BATCH_SIZE = 2;
    private static final int ZERO_COUNT = 0;
    private static final int SINGLE_COMMIT_COUNT = 1;
    private static final int SINGLE_ROLLBACK_COUNT = 1;
    private static final int TWO_IDENTITY_SETTINGS = 2;
    private static final int ARCHIVE_TARGET_SWITCHES = 3;
    private static final int SINGLE_METADATA_QUERY = 1;
    private static final String PHYSICAL_TABLE_METADATA_SQL = "SELECT * FROM \"APP\".\"PARENT_H\" WHERE 1 = 0";
    private static final String INTEGRITY_STATE = "23000";
    private static final String CURRENT_SCHEMA = "ARCHIVE_DATA";
    private static final String USERNAME = "LOGIN_USER";
    private static final String FIELD_NAME = "ID";
    private static final String TABLE_NAME = "DATA_ROWS";
    private static final String INSERT_SQL = "INSERT INTO DATA_ROWS (\"ID\") VALUES (?)";

    /**
     * 执行无需数据库的回归检查。
     * @param args 无需命令行参数
     * @return 无返回值
     * @throws Exception 检查失败
     */
    public static void main(String[] args) throws Exception {
        verifyDuplicateCodes();
        verifySchemaAndSql();
        verifyBatchFallback(MigrationConstants.INSERT_IGNORE, DUPLICATE_KEY_CODE, true);
        verifyBatchFallback(MigrationConstants.INSERT_IGNORE, NAMED_DUPLICATE_KEY_CODE, true);
        verifyBatchFallback(MigrationConstants.INSERT_STRICT, DUPLICATE_KEY_CODE, false);
        verifyBatchFallback(MigrationConstants.INSERT_IGNORE, FOREIGN_KEY_CODE, false);
        verifyBatchFallback(MigrationConstants.INSERT_IGNORE, DUPLICATE_KEY_CODE, true, true);
        verifyBatchFallback(MigrationConstants.INSERT_STRICT, DUPLICATE_KEY_CODE, false, true);
        verifyArchiveIdentitySwitches();
        verifyDerivedNullability();
        System.out.println("[RESULT] DAMENG_SQL_COMPATIBILITY_CHECK_PASSED");
    }

    /**
     * 检查唯一键错误识别，其他完整性错误不得被忽略。
     * @return 无返回值
     */
    private static void verifyDuplicateCodes() {
        SQLException chained = new SQLException("batch error", INTEGRITY_STATE);
        chained.setNextException(new SQLException("duplicate", INTEGRITY_STATE, NAMED_DUPLICATE_KEY_CODE));
        require(SqlSupport.isDuplicateKey(chained, MigrationConstants.DB_DM), "chained duplicate not detected");
        require(SqlSupport.isDuplicateKey(new SQLException("duplicate", INTEGRITY_STATE, DUPLICATE_KEY_CODE),
            MigrationConstants.DB_DM), "legacy duplicate not detected");
        require(!SqlSupport.isDuplicateKey(new SQLException("foreign key", INTEGRITY_STATE, FOREIGN_KEY_CODE),
            MigrationConstants.DB_DM), "foreign-key violation must fail");
        require(!SqlSupport.isDuplicateKey(chained, MigrationConstants.DB_ORACLE), "DM code leaked into Oracle");
        require("\"ID\"".equals(SqlSupport.quoteIdentifier(FIELD_NAME, MigrationConstants.DB_DM)),
            "DM identifiers must use double quotes");
    }

    /**
     * 检查切换后的模式名、分页、脚本和缺失表识别。
     * @return 无返回值
     * @throws Exception 检查失败
     */
    private static void verifySchemaAndSql() throws Exception {
        MigrationEngine migration = new MigrationEngine();
        DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getUserName": return USERNAME;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Connection connection = (Connection) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getSchema": return CURRENT_SCHEMA;
                    case "getMetaData": return metadata;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Method locationMethod = method(MigrationEngine.class, "resolveMetadataLocation", Connection.class,
            QualifiedName.class, String.class);
        Object currentLocation = locationMethod.invoke(migration, connection, SqlSupport.parseTargetTable(TABLE_NAME),
            MigrationConstants.DB_DM);
        require(CURRENT_SCHEMA.equals(field(currentLocation, "schema")), "metadata ignored current DM schema");
        Object explicitLocation = locationMethod.invoke(migration, connection,
            SqlSupport.parseTargetTable("CUSTOM.DATA_ROWS"), MigrationConstants.DB_DM);
        require("CUSTOM".equals(field(explicitLocation, "schema")), "explicit schema was overwritten");
        Method missing = method(MigrationEngine.class, "isMissingTable", Exception.class, String.class);
        require((Boolean) missing.invoke(migration, new SQLException("missing", null, MISSING_TABLE_CODE),
            MigrationConstants.DB_DM), "missing DM table was not recognized");
        require(!(Boolean) missing.invoke(migration, new SQLException("permission denied", null, FOREIGN_KEY_CODE),
            MigrationConstants.DB_DM), "unrelated DM error was treated as missing table");
        Class<?> columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Object approximateColumn = migrationColumn(Types.DOUBLE, "DOUBLE");
        Object nationalLob = migrationColumn(Types.NCLOB, "NCLOB");
        Method typeDefinition = method(MigrationEngine.class, "targetTypeDefinition", columnClass, String.class);
        require("DOUBLE".equals(typeDefinition.invoke(migration, approximateColumn, MigrationConstants.DB_DM)),
            "DM floating-point script must use DOUBLE");
        require("CLOB".equals(typeDefinition.invoke(migration, nationalLob, MigrationConstants.DB_DM)),
            "DM national LOB script must use CLOB");
        ArchiveEngine archive = new ArchiveEngine();
        Method qualified = method(ArchiveEngine.class, "quoteQualifiedName", QualifiedName.class, String.class);
        require("\"ARCHIVE_DATA\".\"DATA_ROWS\"".equals(qualified.invoke(archive,
            SqlSupport.parseTargetTable("archive_data.data_rows"), MigrationConstants.DB_DM)),
            "archive unquoted names must normalize to uppercase");
        Class<?> archiveColumn = Class.forName(ArchiveEngine.class.getName() + "$ColumnMeta");
        Constructor<?> archiveColumnConstructor = archiveColumn.getDeclaredConstructor(String.class, int.class,
            String.class, int.class, int.class, int.class);
        archiveColumnConstructor.setAccessible(true);
        Object keyColumn = archiveColumnConstructor.newInstance(FIELD_NAME, Types.INTEGER, "INTEGER", 10, 0,
            DatabaseMetaData.columnNoNulls);
        Method page = method(ArchiveEngine.class, "buildPageSql", String.class, archiveColumn, String.class,
            int.class, List.class, Object.class, Object.class);
        Object query = page.invoke(archive, "SELECT ID FROM DATA_ROWS", keyColumn, MigrationConstants.DB_DM,
            BATCH_SIZE, Collections.emptyList(), FIRST_ROW, SECOND_ROW);
        String pageSql = (String) field(query, "sql");
        require(pageSql.endsWith("WHERE ROWNUM <= " + BATCH_SIZE), "DM archive pagination must use ROWNUM");
        require(Arrays.asList(FIRST_ROW, SECOND_ROW).equals(field(query, "parameters")),
            "archive pagination parameters lost their order");
    }

    /**
     * 检查达梦批量失败后先回滚，且仅忽略模式允许逐行跳过唯一键冲突。
     * @param insertMode 写入模式
     * @param errorCode 模拟驱动错误码
     * @param expectFallback 是否预期逐行重放
     * @return 无返回值
     * @throws Exception 检查失败
     */
    private static void verifyBatchFallback(String insertMode, int errorCode, boolean expectFallback) throws Exception {
        verifyBatchFallback(insertMode, errorCode, expectFallback, false);
    }

    /**
     * 检查包含自增列时，原始批次和重复回退均在写入前启用达梦显式赋值。
     * @param insertMode 写入模式
     * @param errorCode 模拟驱动错误码
     * @param expectFallback 是否预期逐行重放
     * @param identity 目标字段是否为自增列
     * @return 无返回值
     * @throws Exception 检查失败
     */
    private static void verifyBatchFallback(String insertMode, int errorCode, boolean expectFallback,
        boolean identity) throws Exception {
        final int[] rollbacks = {0};
        final int[] commits = {0};
        final int[] executions = {0};
        final int[] identitySets = {0};
        final Object[] bound = {null};
        Statement identityStatement = (Statement) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute":
                        require("SET IDENTITY_INSERT \"DATA_ROWS\" ON".equals(args[0]),
                            "identity setting target differs from insert target");
                        identitySets[0]++;
                        return false;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "setObject": bound[0] = args[1]; return null;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "addBatch": case "close": return null;
                    case "executeBatch":
                        require(!identity || identitySets[0] > ZERO_COUNT, "identity must be set before batch");
                        throw new BatchUpdateException("batch failed", INTEGRITY_STATE, errorCode, new int[]{1});
                    case "executeUpdate":
                        require(rollbacks[0] > ZERO_COUNT, "batch must roll back before row fallback");
                        require(!identity || identitySets[0] == TWO_IDENTITY_SETTINGS,
                            "identity must be re-enabled after batch rollback");
                        executions[0]++;
                        if (Integer.valueOf(FIRST_ROW).equals(bound[0])) {
                            throw new SQLException("duplicate row", INTEGRITY_STATE, errorCode);
                        }
                        return 1;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Connection connection = (Connection) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createStatement": return identityStatement;
                    case "prepareStatement": return statement;
                    case "rollback": rollbacks[0]++; return null;
                    case "commit": commits[0]++; return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Collections.emptyList());
        MigrationTask task = new MigrationTask("DM-BATCH-CHECK", request);
        List<Object> columns = Collections.singletonList(migrationColumn(Types.INTEGER, "INTEGER", identity));
        Class<?> stepClass = Class.forName(MigrationEngine.class.getName() + "$PreparedStep");
        Constructor<?> stepConstructor = stepClass.getDeclaredConstructor(int.class, String.class, String.class,
            QualifiedName.class, List.class, List.class, int.class, long.class, String.class, String.class,
            String.class, boolean.class);
        stepConstructor.setAccessible(true);
        Object step = stepConstructor.newInstance(0, "DM", "SELECT ID FROM DATA_ROWS",
            SqlSupport.parseTargetTable(TABLE_NAME), columns, columns, 0, 2L, insertMode,
            INSERT_SQL, INSERT_SQL, false);
        Method write = method(MigrationEngine.class, "writeBatch", MigrationTask.class, Connection.class,
            stepClass, List.class, String.class);
        try {
            Object result = write.invoke(new MigrationEngine(), task, connection, step,
                Arrays.asList(new Object[]{FIRST_ROW}, new Object[]{SECOND_ROW}), MigrationConstants.DB_DM);
            require(expectFallback, "strict/non-duplicate failure was swallowed");
            require(Long.valueOf(1L).equals(field(result, "inserted")), "fallback inserted count wrong");
            require(Long.valueOf(1L).equals(field(result, "ignored")), "fallback ignored count wrong");
            require(executions[0] == BATCH_SIZE, "fallback did not replay the complete batch");
            require(commits[0] == SINGLE_COMMIT_COUNT, "fallback must commit once after replay");
        } catch (InvocationTargetException exception) {
            require(!expectFallback && exception.getCause() instanceof SQLException,
                "unexpected fallback failure: " + exception.getCause());
            require(executions[0] == ZERO_COUNT && commits[0] == ZERO_COUNT,
                "strict failure must not replay or commit");
        }
        require(rollbacks[0] == SINGLE_ROLLBACK_COUNT, "failed batch must roll back once");
        int expectedSettings = identity ? (expectFallback ? TWO_IDENTITY_SETTINGS : SINGLE_COMMIT_COUNT) : ZERO_COUNT;
        require(identitySets[0] == expectedSettings, "unexpected identity setting count");
    }

    /**
     * 检查归档读取既有结果元数据，且在同一连接父子表来回切换时逐批设置目标表。
     * @return 无返回值
     * @throws Exception 检查失败
     */
    private static void verifyArchiveIdentitySwitches() throws Exception {
        ArchiveEngine archive = new ArchiveEngine();
        ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{ResultSetMetaData.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getColumnCount": return 1;
                    case "getColumnLabel": return FIELD_NAME;
                    case "getColumnType": return Types.INTEGER;
                    case "getColumnTypeName": return "INTEGER";
                    case "getPrecision": return 10;
                    case "getScale": return 0;
                    case "isNullable": return DatabaseMetaData.columnNoNulls;
                    case "isAutoIncrement": return true;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Object columns = method(ArchiveEngine.class, "readColumnMetadata", ResultSetMetaData.class, boolean.class)
            .invoke(archive, metadata, true);
        require((Boolean) method(ArchiveEngine.class, "hasAutoIncrement", List.class).invoke(archive, columns),
            "archive identity flag must come from existing result metadata");
        final String[] currentIdentity = {null};
        final int[] settings = {0};
        final int[] inserts = {0};
        final int[] metadataQueries = {0};
        final String[] insertTarget = {null};
        ResultSet metadataResult = (ResultSet) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getMetaData": return metadata;
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        PreparedStatement metadataStatement = (PreparedStatement) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeQuery": metadataQueries[0]++; return metadataResult;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Statement identityStatement = (Statement) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{Statement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "execute": currentIdentity[0] = (String) args[0]; settings[0]++; return false;
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        PreparedStatement insertStatement = (PreparedStatement) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "executeBatch":
                        require(("SET IDENTITY_INSERT \"APP\".\"" + insertTarget[0] + "\" ON")
                            .equals(currentIdentity[0]), "archive target switched without resetting identity");
                        inserts[0]++;
                        return new int[]{1};
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "setObject": case "addBatch": case "close": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        Connection connection = (Connection) Proxy.newProxyInstance(
            DamengSqlCompatibilityCheck.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "createStatement": return identityStatement;
                    case "prepareStatement":
                        String sql = (String) args[0];
                        if (sql.startsWith("SELECT")) {
                            require(PHYSICAL_TABLE_METADATA_SQL.equals(sql),
                                "DM table metadata must query the physical table directly to retain identity flags");
                            return metadataStatement;
                        }
                        return insertStatement;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Collections.emptyList());
        MigrationTask task = new MigrationTask("DM-ARCHIVE-IDENTITY-CHECK", request);
        Object physicalColumns = method(ArchiveEngine.class, "readTableColumns", MigrationTask.class,
            Connection.class, QualifiedName.class, String.class).invoke(archive, task, connection,
                SqlSupport.parseTargetTable("APP.PARENT_H"), MigrationConstants.DB_DM);
        require((Boolean) method(ArchiveEngine.class, "hasAutoIncrement", List.class).invoke(archive, physicalColumns),
            "DM physical-table metadata identity flag was lost");
        require(metadataQueries[0] == SINGLE_METADATA_QUERY, "DM identity must reuse one existing metadata query");
        Class<?> tableClass = Class.forName(ArchiveEngine.class.getName() + "$PreparedArchiveTable");
        Constructor<?> constructor = tableClass.getDeclaredConstructor(int.class, ArchiveTableRequest.class,
            QualifiedName.class, QualifiedName.class, String.class, String.class, String.class,
            List.class, List.class, List.class, boolean.class, String.class, long.class, boolean.class);
        constructor.setAccessible(true);
        Method insert = method(ArchiveEngine.class, "insertRows", MigrationTask.class, Connection.class,
            tableClass, List.class);
        for (String table : Arrays.asList("PARENT_H", "CHILD_H", "PARENT_H")) {
            insertTarget[0] = table;
            ArchiveTableRequest tableRequest = new ArchiveTableRequest();
            tableRequest.setName(table);
            QualifiedName target = SqlSupport.parseTargetTable("APP." + table);
            Object prepared = constructor.newInstance(0, tableRequest, target, target, table, table,
                "SELECT ID FROM SRC", columns, Collections.singletonList(0), columns, false, INSERT_SQL, 1L, true);
            Object count = insert.invoke(archive, task, connection, prepared,
                Collections.singletonList(new Object[]{FIRST_ROW}));
            require(Long.valueOf(1L).equals(count), "archive identity insert count changed");
        }
        require(settings[0] == ARCHIVE_TARGET_SWITCHES && inserts[0] == ARCHIVE_TARGET_SWITCHES,
            "archive identity must be enabled for each target switch");
    }

    /**
     * 检查仅达梦派生查询允许可空元数据差异，物理表同构与字段类型仍严格校验。
     * @return 无返回值
     * @throws Exception 检查失败
     */
    private static void verifyDerivedNullability() throws Exception {
        ArchiveEngine archive = new ArchiveEngine();
        Class<?> columnClass = Class.forName(ArchiveEngine.class.getName() + "$ColumnMeta");
        Constructor<?> constructor = columnClass.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object physical = constructor.newInstance(FIELD_NAME, Types.INTEGER, "INTEGER", 10, 0,
            DatabaseMetaData.columnNoNulls);
        Object nullableQuery = constructor.newInstance(FIELD_NAME, Types.INTEGER, "INTEGER", 10, 0,
            DatabaseMetaData.columnNullable);
        List<Object> physicalColumns = Collections.singletonList(physical);
        List<Object> nullableColumns = Collections.singletonList(nullableQuery);
        Method queryValidation = method(ArchiveEngine.class, "validateQueryCoversSourceTable", List.class,
            List.class, String.class, String.class);
        queryValidation.invoke(archive, nullableColumns, physicalColumns, "DM", MigrationConstants.DB_DM);
        require(rejects(queryValidation, archive, nullableColumns, physicalColumns, "MySQL",
            MigrationConstants.DB_MYSQL), "MySQL query nullability rule must remain strict");
        require(rejects(queryValidation, archive, nullableColumns, physicalColumns, "Oracle",
            MigrationConstants.DB_ORACLE), "Oracle query nullability rule must remain strict");
        Method physicalValidation = method(ArchiveEngine.class, "validateSameStructure", List.class,
            List.class, String.class);
        require(rejects(physicalValidation, archive, physicalColumns, nullableColumns, "DM"),
            "physical source/target nullability must remain exact");
        List<Object> incompatible = Arrays.asList(
            constructor.newInstance(FIELD_NAME, Types.BIGINT, "INTEGER", 10, 0, DatabaseMetaData.columnNullable),
            constructor.newInstance(FIELD_NAME, Types.INTEGER, "NUMBER", 10, 0, DatabaseMetaData.columnNullable),
            constructor.newInstance(FIELD_NAME, Types.INTEGER, "INTEGER", 12, 0, DatabaseMetaData.columnNullable),
            constructor.newInstance(FIELD_NAME, Types.INTEGER, "INTEGER", 10, 1, DatabaseMetaData.columnNullable));
        for (Object changed : incompatible) {
            require(rejects(queryValidation, archive, Collections.singletonList(changed), physicalColumns,
                "DM", MigrationConstants.DB_DM), "DM must still reject changed type/precision/scale");
        }
    }

    /**
     * 检查结构校验是否拒绝不兼容参数。
     * @param validation 校验方法
     * @param engine 校验引擎
     * @param arguments 校验参数
     * @return 是否抛出预期的配置错误
     * @throws Exception 非预期反射错误
     */
    private static boolean rejects(Method validation, Object engine, Object... arguments) throws Exception {
        try {
            validation.invoke(engine, arguments);
            return false;
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof IllegalArgumentException) {
                return true;
            }
            throw exception;
        }
    }

    /**
     * 构建迁移字段元数据。
     * @param jdbcType JDBC 类型
     * @param typeName 数据库类型名
     * @return 字段元数据
     * @throws Exception 反射失败
     */
    private static Object migrationColumn(int jdbcType, String typeName) throws Exception {
        return migrationColumn(jdbcType, typeName, false);
    }

    /**
     * 构建包含自增标志的迁移字段元数据。
     * @param jdbcType JDBC 类型
     * @param typeName 数据库类型名
     * @param identity 是否自增列
     * @return 字段元数据
     * @throws Exception 反射失败
     */
    private static Object migrationColumn(int jdbcType, String typeName, boolean identity) throws Exception {
        Class<?> type = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(FIELD_NAME, jdbcType, typeName, 10, 0, DatabaseMetaData.columnNoNulls, identity);
    }

    /**
     * 获取待检查的内部方法。
     * @param type 声明类
     * @param name 方法名称
     * @param arguments 参数类型
     * @return 可访问方法
     * @throws Exception 方法查找失败
     */
    private static Method method(Class<?> type, String name, Class<?>... arguments) throws Exception {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    /**
     * 读取内部结果字段。
     * @param target 结果对象
     * @param name 字段名称
     * @return 字段值
     * @throws Exception 字段读取失败
     */
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * 断言检查结果。
     * @param condition 预期为真的条件
     * @param message 失败提示
     * @return 无返回值
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
