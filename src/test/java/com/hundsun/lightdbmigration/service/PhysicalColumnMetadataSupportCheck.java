package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用内存目录代理验证补读保留原文、精确限定物理表以及控制/失败边界。
 * Author: Codex
 * date: 2026-10-28
 */
public final class PhysicalColumnMetadataSupportCheck {

    private static final String MYSQL = MigrationConstants.DB_MYSQL;
    private static final String ORACLE = MigrationConstants.DB_ORACLE;
    private static final String DM = MigrationConstants.DB_DM;
    private static final String SCHEMA = "APP_ONE";
    private static final String TABLE = "T_A";
    private static final String CATALOG = "TEST_CATALOG";
    private static final String ENUM_TYPE = "enum('Alpha','beta','it\\'s','X;Y')";
    private static final String SET_TYPE = "set('First','SECOND')";
    /** Oracle 间隔类型只从物理字段目录声明补读精度。 */
    private static final String ORACLE_INTERVAL_VIEW = "ALL_TAB_COLUMNS";
    private static final String GET_CATALOG = "getCatalog";
    private static final String GET_SCHEMA = "getSchema";
    private static final String GET_METADATA = "getMetaData";
    private static final String PREPARE_STATEMENT = "prepareStatement";
    private static final String GET_COLUMNS = "getColumns";
    private static final String GET_ESCAPE = "getSearchStringEscape";
    private static final String GET_USER = "getUserName";
    private static final String SET_STRING = "setString";
    private static final String EXECUTE_QUERY = "executeQuery";
    private static final String NEXT = "next";
    private static final String GET_STRING = "getString";
    private static final String GET_LONG = "getLong";
    private static final String WAS_NULL = "wasNull";
    private static final int NO_CALLS = 0;
    private static final int ONE_CALL = 1;
    private static final int MYSQL_SCHEMA_PARAMETER = 1;
    private static final int MYSQL_TABLE_PARAMETER = 2;
    private static final long MAX_UNSIGNED_TEXT_LENGTH = 4294967295L;

    /** Params: args 未使用。 Returns: 无；失败抛出断言。 */
    public static void main(String[] args) throws Exception {
        verifyMysqlPreservesDefinitions();
        verifyJdbcUsesExactTableAndDoesNotGuess();
        verifyOracleIntervalUsesCompleteCatalogDeclaration();
        verifyMetadataFailuresAreNotRetried();
        verifyControlsAndUnknownNamespaceDoNotRead();
        System.out.println("[RESULT] PHYSICAL_COLUMN_METADATA_SUPPORT_CHECK_PASSED");
    }

    /** Params: 无。 Returns: 无；枚举成员、转义和未知属性必须原样保留。 */
    private static void verifyMysqlPreservesDefinitions() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows.add(row("COLUMN_NAME", "STATE", "COLUMN_TYPE", ENUM_TYPE, "DATA_TYPE", "enum",
            "IS_NULLABLE", "YES", "CHARACTER_MAXIMUM_LENGTH", 12L));
        fixture.rows.add(row("COLUMN_NAME", "OPTIONS", "COLUMN_TYPE", SET_TYPE, "DATA_TYPE", "set",
            "IS_NULLABLE", "NO", "CHARACTER_MAXIMUM_LENGTH", 30L));
        fixture.rows.add(row("COLUMN_NAME", "MISSING", "IS_NULLABLE", "UNKNOWN"));
        fixture.rows.add(row("COLUMN_NAME", "HUGE_TEXT", "COLUMN_TYPE", "longtext", "DATA_TYPE", "longtext",
            "CHARACTER_MAXIMUM_LENGTH", MAX_UNSIGNED_TEXT_LENGTH));
        Map<String, PhysicalColumnMetadataSupport.Definition> definitions = PhysicalColumnMetadataSupport.read(
            task(), fixture.connection(), MYSQL, SqlSupport.parseTableName(TABLE, "表"));
        check(ENUM_TYPE.equals(definitions.get("STATE").nativeType), "ENUM 大小写、转义或分号被改写");
        check(SET_TYPE.equals(definitions.get("OPTIONS").nativeType), "SET 成员大小写被改写");
        check(definitions.get("STATE").nullable == DatabaseMetaData.columnNullable, "MySQL 可空性读取错误");
        check(definitions.get("OPTIONS").nullable == DatabaseMetaData.columnNoNulls, "MySQL 非空性读取错误");
        PhysicalColumnMetadataSupport.Definition unknown = definitions.get("MISSING");
        check(unknown.typeName == null && unknown.nativeType == null && unknown.jdbcType == null
            && unknown.precision == null && unknown.scale == null
            && unknown.nullable == DatabaseMetaData.columnNullableUnknown, "缺失元数据被推测为定义");
        check(definitions.get("HUGE_TEXT").precision == null, "大于 JDBC 整数范围的长度被截断或溢出");
        check(CATALOG.equals(fixture.bindings.get(MYSQL_SCHEMA_PARAMETER))
            && TABLE.equals(fixture.bindings.get(MYSQL_TABLE_PARAMETER)), "MySQL 未按当前数据库和表绑定读取");
        check(fixture.queryCalls == ONE_CALL && fixture.metadataCalls == NO_CALLS,
            "MySQL 补读扩大为额外目录扫描");
        check(fixture.sql.contains("TABLE_SCHEMA = ?") && fixture.sql.contains("TABLE_NAME = ?"),
            "MySQL 字段目录未绑定限定具体物理表");
    }

    /** Params: 无。 Returns: 无；Oracle/DM 必须转义下划线并过滤驱动返回的其他模式/表。 */
    private static void verifyJdbcUsesExactTableAndDoesNotGuess() throws Exception {
        for (String type : Arrays.asList(ORACLE, DM)) {
            Fixture fixture = new Fixture();
            fixture.rows.add(jdbcRow("OTHER_SCHEMA", TABLE, "WRONG_SCHEMA", Types.INTEGER, "INTEGER", 10, 0));
            fixture.rows.add(jdbcRow(SCHEMA, "T_AA", "WRONG_TABLE", Types.INTEGER, "INTEGER", 10, 0));
            Map<String, Object> wrongCatalog = jdbcRow(SCHEMA, TABLE, "WRONG_CATALOG", Types.INTEGER, "INTEGER", 10, 0);
            wrongCatalog.put("TABLE_CAT", "OTHER_CATALOG");
            fixture.rows.add(wrongCatalog);
            fixture.rows.add(jdbcRow(SCHEMA, TABLE, "AMOUNT", Types.NUMERIC, "NUMBER", 0, -127));
            fixture.rows.add(jdbcRow(SCHEMA, TABLE, "UNKNOWN", null, null, null, null));
            Map<String, PhysicalColumnMetadataSupport.Definition> definitions = PhysicalColumnMetadataSupport.read(
                task(), fixture.connection(), type, SqlSupport.parseTableName("app_one.t_a", "表"));
            check(definitions.size() == 2 && definitions.containsKey("AMOUNT") && definitions.containsKey("UNKNOWN"),
                type + " 没有按真实模式/表/库精确过滤元数据");
            check("APP\\_ONE".equals(fixture.columnArguments[1])
                && "T\\_A".equals(fixture.columnArguments[2]), type + " 元数据模式中的下划线未转义");
            check(definitions.get("AMOUNT").precision == 0 && definitions.get("AMOUNT").scale == -127,
                type + " 改写了物理元数据真实无精度值");
            check(definitions.get("AMOUNT").nativeType == null && definitions.get("UNKNOWN").precision == null
                && definitions.get("UNKNOWN").nullable == DatabaseMetaData.columnNullableUnknown,
                type + " 虚构了原生类型定义或未知元数据");
            check(fixture.queryCalls == NO_CALLS, type + " 无间隔字段时扩大为额外 SQL 目录读取");
        }
    }

    /** Params: 无。 Returns: 无；间隔声明以单表目录为准，不能把驱动存储长度伪造为 leading precision。 */
    private static void verifyOracleIntervalUsesCompleteCatalogDeclaration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.rows.add(jdbcRow(SCHEMA, TABLE, "TERM", Types.OTHER, "INTERVALYM", 5, 0));
        fixture.rows.add(jdbcRow(SCHEMA, TABLE, "WAIT_TIME", Types.OTHER, "INTERVALDS", 11, 6));
        fixture.oracleRows.add(row("COLUMN_NAME", "TERM", "DATA_TYPE", "INTERVAL YEAR(3) TO MONTH",
            "DATA_PRECISION", 3L, "DATA_SCALE", 0L));
        fixture.oracleRows.add(row("COLUMN_NAME", "WAIT_TIME", "DATA_TYPE", "INTERVAL DAY(4) TO SECOND(7)",
            "DATA_PRECISION", 4L, "DATA_SCALE", 7L));
        Map<String, PhysicalColumnMetadataSupport.Definition> definitions = PhysicalColumnMetadataSupport.read(
            task(), fixture.connection(), ORACLE, SqlSupport.parseTableName("app_one.t_a", "表"));
        check("INTERVAL YEAR(3) TO MONTH".equals(definitions.get("TERM").nativeType)
            && "INTERVAL DAY(4) TO SECOND(7)".equals(definitions.get("WAIT_TIME").nativeType),
            "Oracle 间隔字段未保留完整物理声明，或以驱动存储长度推测DDL精度");
        check(definitions.get("TERM").precision == 5 && definitions.get("WAIT_TIME").precision == 11,
            "目录原生声明与驱动元数据被混为一个属性");
        check(fixture.queryCalls == ONE_CALL && fixture.metadataCalls == ONE_CALL,
            "Oracle 间隔字段目录发生重复或扩大读取");
        check(SCHEMA.equals(fixture.bindings.get(MYSQL_SCHEMA_PARAMETER))
            && TABLE.equals(fixture.bindings.get(MYSQL_TABLE_PARAMETER))
            && fixture.sql.contains("OWNER = ?") && fixture.sql.contains("TABLE_NAME = ?"),
            "Oracle 间隔字段目录没有绑定到精确模式及物理表");
        Fixture failed = new Fixture();
        failed.rows.add(jdbcRow(SCHEMA, TABLE, "TERM", Types.OTHER, "INTERVALYM", 5, 0));
        failed.queryFailure = new SQLException("catalog fixture failure", "HY000");
        check(failure(task(), failed, ORACLE) == failed.queryFailure && failed.queryCalls == ONE_CALL,
            "Oracle 间隔字段目录失败被吞掉或自动重试");
    }

    /** Params: 无。 Returns: 无；普通异常和断连均原样上抛且不重试。 */
    private static void verifyMetadataFailuresAreNotRetried() throws Exception {
        for (String state : Arrays.asList("08006", "HY000")) {
            Fixture fixture = new Fixture();
            fixture.failure = new SQLException("fixture failure", state);
            Throwable failure = failure(task(), fixture, MYSQL);
            check(failure == fixture.failure && fixture.queryCalls == ONE_CALL, "MySQL 目录失败被吞掉或重试");
            Fixture jdbc = new Fixture();
            jdbc.failure = new SQLException("fixture failure", state);
            Throwable jdbcFailure = failure(task(), jdbc, DM);
            check(jdbcFailure == jdbc.failure && jdbc.metadataCalls == ONE_CALL, "JDBC 目录失败被吞掉或重试");
        }
    }

    /** Params: 无。 Returns: 无；用户停止、中断及无法定位模式时不得读取其他表。 */
    private static void verifyControlsAndUnknownNamespaceDoNotRead() throws Exception {
        Fixture stopped = new Fixture();
        MigrationTask task = task();
        task.stop();
        check(failure(task, stopped, MYSQL) instanceof StopRequestedException && stopped.queryCalls == NO_CALLS,
            "已终止任务仍执行物理字段读取");
        Fixture interrupted = new Fixture();
        Thread.currentThread().interrupt();
        try {
            check(failure(task(), interrupted, DM) instanceof InterruptedException
                && interrupted.metadataCalls == NO_CALLS, "中断未在目录读取前生效");
        } finally {
            Thread.interrupted();
        }
        Fixture unknown = new Fixture();
        unknown.catalog = null;
        check(failure(task(), unknown, MYSQL) instanceof SQLException && unknown.queryCalls == NO_CALLS,
            "数据库未知时扩大了 MySQL 读取范围");
        Fixture unknownSchema = new Fixture();
        unknownSchema.schema = null;
        unknownSchema.username = null;
        check(failure(task(), unknownSchema, DM) instanceof SQLException && unknownSchema.metadataCalls == NO_CALLS,
            "模式未知时扩大了 JDBC 读取范围");
        Fixture noEscape = new Fixture();
        noEscape.escape = "";
        check(failure(task(), noEscape, ORACLE) instanceof SQLException && noEscape.metadataCalls == NO_CALLS,
            "驱动无法转义下划线时执行了过宽模式");
    }

    /** Params: task 当前任务；fixture JDBC 代理；type 数据库类型。 Returns: 必须发生的失败。 */
    private static Throwable failure(MigrationTask task, Fixture fixture, String type) {
        try {
            PhysicalColumnMetadataSupport.read(task, fixture.connection(), type, SqlSupport.parseTableName(TABLE, "表"));
            throw new AssertionError("目录补读应失败");
        } catch (Exception exception) {
            return exception;
        }
    }

    /** Params: 无。 Returns: 不会建立真实连接的最小任务。 */
    private static MigrationTask task() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(MYSQL);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskType(MigrationConstants.TASK_TYPE_MIGRATION);
        request.setSource(config);
        request.setTarget(config);
        request.setSteps(Collections.emptyList());
        return new MigrationTask("physical-column-metadata-check", request);
    }

    /** Params: values 交替排列的目录字段及值。 Returns: 一行目录数据。 */
    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            row.put((String)values[i], values[i + 1]);
        }
        return row;
    }

    /** Params: schema/table 模式和表；name 列名；jdbcType/type 类型；precision/scale 精度。 Returns: 目录行。 */
    private static Map<String, Object> jdbcRow(String schema, String table, String name, Integer jdbcType,
        String type, Integer precision, Integer scale) {
        return row("TABLE_CAT", CATALOG, "TABLE_SCHEM", schema, "TABLE_NAME", table, "COLUMN_NAME", name,
            "DATA_TYPE", jdbcType, "TYPE_NAME", type, "COLUMN_SIZE", precision, "DECIMAL_DIGITS", scale);
    }

    /** Params: condition 断言条件；message 失败说明。 Returns: 无。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 只实现字段目录读取的轻量 JDBC 代理。
     * Author: Codex
     * date: 2026-10-28
     */
    private static final class Fixture {
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final List<Map<String, Object>> oracleRows = new ArrayList<>();
        private final Map<Integer, Object> bindings = new LinkedHashMap<>();
        private String catalog = CATALOG;
        private String schema = SCHEMA;
        private String username = SCHEMA;
        private String escape = "\\";
        private String sql;
        private int queryCalls;
        private int metadataCalls;
        private Object[] columnArguments;
        private SQLException failure;
        private SQLException queryFailure;

        /** Params: 无。 Returns: 不建立网络连接的 Connection 代理。 */
        private Connection connection() {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (GET_CATALOG.equals(name)) { return catalog; }
                    if (GET_SCHEMA.equals(name)) { return schema; }
                    if (GET_METADATA.equals(name)) { return metadata(); }
                    if (PREPARE_STATEMENT.equals(name)) { sql = (String)args[0]; return statement(); }
                    return primitive(method.getReturnType());
                });
        }

        /** Params: 无。 Returns: 元数据 API 代理。 */
        private DatabaseMetaData metadata() {
            return (DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (GET_ESCAPE.equals(name)) { return escape; }
                    if (GET_USER.equals(name)) { return username; }
                    if (GET_COLUMNS.equals(name)) {
                        metadataCalls++;
                        columnArguments = args;
                        if (failure != null) { throw failure; }
                        return rows();
                    }
                    return primitive(method.getReturnType());
                });
        }

        /** Params: 无。 Returns: 绑定 SQL 代理。 */
        private PreparedStatement statement() {
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (SET_STRING.equals(name)) { bindings.put((Integer)args[0], args[1]); }
                    if (EXECUTE_QUERY.equals(name)) {
                        queryCalls++;
                        if (failure != null) { throw failure; }
                        if (queryFailure != null) { throw queryFailure; }
                        return rows(sql != null && sql.contains(ORACLE_INTERVAL_VIEW) ? oracleRows : rows);
                    }
                    return primitive(method.getReturnType());
                });
        }

        /** Params: 无。 Returns: 字段目录结果代理。 */
        private ResultSet rows() {
            return rows(rows);
        }

        /** Params: data 当前目录结果集。 Returns: 只读取指定目录数据的结果代理。 */
        private ResultSet rows(List<Map<String, Object>> data) {
            int[] index = {-1};
            boolean[] wasNull = {false};
            return (ResultSet)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (NEXT.equals(name)) { return ++index[0] < data.size(); }
                    if (WAS_NULL.equals(name)) { return wasNull[0]; }
                    if (GET_STRING.equals(name) || GET_LONG.equals(name)) {
                        Object value = data.get(index[0]).get(args[0]);
                        wasNull[0] = value == null;
                        return GET_STRING.equals(name) ? value == null ? null : value.toString()
                            : value == null ? 0L : ((Number)value).longValue();
                    }
                    return primitive(method.getReturnType());
                });
        }

        /** Params: type Java 返回类型。 Returns: 最小代理所需的空返回值。 */
        private Object primitive(Class<?> type) {
            if (type == boolean.class) { return false; }
            if (type == int.class) { return 0; }
            if (type == long.class) { return 0L; }
            return null;
        }
    }
}
