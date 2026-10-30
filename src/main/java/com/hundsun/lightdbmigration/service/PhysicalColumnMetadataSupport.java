package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按明确的单张物理表补读字段定义，不将缺失的元数据推测为实际字段属性。
 * Author: Codex
 * date: 2026-10-28
 */
final class PhysicalColumnMetadataSupport {

    /** MySQL 字段目录查询只绑定指定数据库及表名，不扫描其他表。 */
    private static final String MYSQL_COLUMNS_SQL = "SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, "
        + "CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA "
        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
    /** Oracle 间隔类型按精确单表取完整声明，不把 JDBC 字节长度误当作前导精度。 */
    private static final String ORACLE_INTERVAL_COLUMNS_SQL = "SELECT COLUMN_NAME, DATA_TYPE, DATA_PRECISION, DATA_SCALE "
        + "FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? AND DATA_TYPE LIKE 'INTERVAL%' ORDER BY COLUMN_ID";
    /** Oracle 原生间隔类型及驱动别名的共同前缀。 */
    private static final String INTERVAL_TYPE_PREFIX = "INTERVAL";
    /** JDBC 元数据当前物理表的全部字段，不扩展为全部表。 */
    private static final String ALL_COLUMNS_PATTERN = "%";
    /** MySQL 字段允许空值的目录标记。 */
    private static final String MYSQL_NULLABLE = "YES";
    /** MySQL 字段不允许空值的目录标记。 */
    private static final String MYSQL_NOT_NULL = "NO";
    /** MySQL 数据库绑定参数序号。 */
    private static final int MYSQL_SCHEMA_PARAMETER = 1;
    /** MySQL 表绑定参数序号。 */
    private static final int MYSQL_TABLE_PARAMETER = 2;
    /** JDBC 元数据模式中的任意长度匹配字符。 */
    private static final String PATTERN_MANY = "%";
    /** JDBC 元数据模式中的单字符匹配字符。 */
    private static final String PATTERN_ONE = "_";
    /** JDBC 字段目录最大可表示的精度。 */
    private static final long MAX_METADATA_INTEGER = Integer.MAX_VALUE;
    /** JDBC 字段目录最小可表示的小数位。 */
    private static final long MIN_METADATA_INTEGER = Integer.MIN_VALUE;
    /** MariaDB 10.2.7 起默认值目录使用带 SQL 引号的字面量；可带 MySQL 握手兼容前缀。 */
    private static final Pattern MARIADB_VERSION = Pattern.compile(
        "(?i)(?:5\\.5\\.5-)?([0-9]+)\\.([0-9]+)\\.([0-9]+).*MariaDB.*");
    /** 用于区分原生 MySQL 与 MariaDB 的产品版本标记。 */
    private static final String MARIADB_MARKER = "MARIADB";
    /** 带引用目录格式首次发布的版本。 */
    private static final int QUOTED_DEFAULT_MAJOR = 10;
    private static final int QUOTED_DEFAULT_MINOR = 2;
    private static final int QUOTED_DEFAULT_PATCH = 7;
    /** MariaDB 已支持表达式但目录尚未区分字面量的首个补丁版本。 */
    private static final int AMBIGUOUS_DEFAULT_FIRST_PATCH = 1;
    /** 版本正则中的各数字分组。 */
    private static final int VERSION_MAJOR_GROUP = 1;
    private static final int VERSION_MINOR_GROUP = 2;
    private static final int VERSION_PATCH_GROUP = 3;

    /** Params: 无。 Returns: 不允许创建工具类实例。 */
    private PhysicalColumnMetadataSupport() {
    }

    /**
     * 仅补读指定物理表；普通读取异常、连接错误及控制请求均由调用方接收并分类。
     * Params: task 当前任务；connection 当前连接；databaseType 数据库类型；table 明确的物理表。
     * Returns: 按字段名去首尾空白并转大写索引的真实定义；不可读的属性保持 null 或 nullableUnknown。
     */
    static Map<String, Definition> read(MigrationTask task, Connection connection, String databaseType,
        QualifiedName table) throws Exception {
        checkControl(task);
        if (connection == null || table == null) {
            throw new IllegalArgumentException("物理字段补读需要明确的数据库连接及表名");
        }
        if (MigrationConstants.DB_MYSQL.equals(databaseType)) {
            return readMysql(task, connection, table);
        }
        Map<String, Definition> definitions = readJdbc(task, connection, table);
        if (MigrationConstants.DB_ORACLE.equals(databaseType)) {
            boolean interval = false;
            for (Definition definition : definitions.values()) {
                interval |= definition.typeName != null
                    && definition.typeName.trim().toUpperCase(Locale.ROOT).startsWith(INTERVAL_TYPE_PREFIX);
            }
            if (interval) {
                readOracleIntervals(task, connection, table, definitions);
            }
        }
        return definitions;
    }

    /**
     * 以 Oracle 物理目录的完整 INTERVAL 声明为准，不将 JDBC precision/scale 猜作 SQL 参数。
     * Params: task 当前任务；connection 当前连接；table 精确物理表；definitions 已读取的真实列元数据。
     * Returns: 无；仅为对应字段补充原生声明，原驱动类型与维度继续保留。
     */
    private static void readOracleIntervals(MigrationTask task, Connection connection, QualifiedName table,
        Map<String, Definition> definitions) throws Exception {
        String schema = nullableText(table.getSchema());
        if (schema == null) {
            try {
                schema = nullableText(connection.getSchema());
            } catch (SQLFeatureNotSupportedException exception) {
                schema = null;
            } catch (AbstractMethodError error) {
                schema = null;
            }
        }
        if (schema == null) {
            schema = nullableText(connection.getMetaData().getUserName());
        }
        if (schema == null) {
            throw new SQLException("无法确定 Oracle 间隔字段所属模式，未执行全库字段扫描");
        }
        schema = schema.toUpperCase(Locale.ROOT);
        String tableName = table.getTable().toUpperCase(Locale.ROOT);
        task.backendSql("SQL执行[Oracle间隔字段完整定义补读]：" + ORACLE_INTERVAL_COLUMNS_SQL
            + "；参数=[" + schema + ", " + tableName + "]");
        checkControl(task);
        try (PreparedStatement statement = connection.prepareStatement(ORACLE_INTERVAL_COLUMNS_SQL)) {
            statement.setString(MYSQL_SCHEMA_PARAMETER, schema);
            statement.setString(MYSQL_TABLE_PARAMETER, tableName);
            task.setCurrentStatement(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    checkControl(task);
                    String name = nullableText(rows.getString("COLUMN_NAME"));
                    String nativeType = nullableText(rows.getString("DATA_TYPE"));
                    if (name == null || nativeType == null) {
                        continue;
                    }
                    Definition original = definitions.get(name.trim().toUpperCase(Locale.ROOT));
                    if (original != null) {
                        definitions.put(name.trim().toUpperCase(Locale.ROOT),
                            new Definition(original.name, nativeType, original.typeName,
                                original.jdbcType, original.precision, original.scale, original.nullable,
                                original.defaultValue));
                    }
                }
                checkControl(task);
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    /**
     * COLUMN_TYPE 直接保留目录原文，尤其保留 ENUM/SET 成员的大小写、转义及顺序。
     * Params: task 当前任务；connection 当前连接；table 物理表。
     * Returns: MySQL 实际字段定义；该目录不包含 JDBC 类型号，jdbcType 保持 null。
     */
    private static Map<String, Definition> readMysql(MigrationTask task, Connection connection,
        QualifiedName table) throws Exception {
        String schema = nullableText(table.getSchema());
        if (schema == null) {
            schema = nullableText(connection.getCatalog());
        }
        if (schema == null) {
            throw new SQLException("无法确定 MySQL 物理表所属数据库，未执行全库字段扫描");
        }
        String tableName = table.getTable();
        boolean quotedDefaults = quotedMysqlDefaults(task, connection);
        task.backendSql("SQL执行[物理字段定义补读]：" + MYSQL_COLUMNS_SQL + "；参数=[" + schema + ", "
            + tableName + "]");
        Map<String, Definition> definitions = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(MYSQL_COLUMNS_SQL)) {
            statement.setString(MYSQL_SCHEMA_PARAMETER, schema);
            statement.setString(MYSQL_TABLE_PARAMETER, tableName);
            task.setCurrentStatement(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    checkControl(task);
                    String name = nullableText(rows.getString("COLUMN_NAME"));
                    String nativeType = nullableText(rows.getString("COLUMN_TYPE"));
                    String typeName = nullableText(rows.getString("DATA_TYPE"));
                    String nullable = rows.getString("IS_NULLABLE");
                    int nullableCode = MYSQL_NULLABLE.equalsIgnoreCase(nullable)
                        ? DatabaseMetaData.columnNullable : MYSQL_NOT_NULL.equalsIgnoreCase(nullable)
                        ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullableUnknown;
                    Integer precision = integer(rows, "NUMERIC_PRECISION");
                    if (precision == null) {
                        precision = integer(rows, "CHARACTER_MAXIMUM_LENGTH");
                    }
                    Integer scale = integer(rows, "NUMERIC_SCALE");
                    if (scale == null) {
                        scale = integer(rows, "DATETIME_PRECISION");
                    }
                    add(definitions, new Definition(name, nativeType, typeName, null, precision, scale,
                        nullableCode, ColumnDefaultValue.mysql(rows.getString("COLUMN_DEFAULT"), typeName,
                            rows.getString("EXTRA"), quotedDefaults)));
                }
                checkControl(task);
            } finally {
                task.clearCurrentStatement(statement);
            }
        } catch (Exception exception) {
            checkControl(task);
            throw exception;
        }
        return definitions;
    }

    /**
     * Params: task 当前任务；connection 已登录的 MySQL 兼容连接。
     * Returns: 是否使用 MariaDB 的已引用目录格式；不能通过文本外观猜测默认值编码。
     */
    private static boolean quotedMysqlDefaults(MigrationTask task, Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String version = metadata == null ? null : metadata.getDatabaseProductVersion();
        if (version == null || !version.toUpperCase(Locale.ROOT).contains(MARIADB_MARKER)) {
            return false;
        }
        Matcher matcher = MARIADB_VERSION.matcher(version);
        if (!matcher.matches()) {
            throw new SQLException("无法确认 MySQL 兼容数据库的默认值目录格式，请核对服务端版本并人工生成默认值");
        }
        try {
            int major = Integer.parseInt(matcher.group(VERSION_MAJOR_GROUP));
            int minor = Integer.parseInt(matcher.group(VERSION_MINOR_GROUP));
            int patch = Integer.parseInt(matcher.group(VERSION_PATCH_GROUP));
            if (major == QUOTED_DEFAULT_MAJOR && minor == QUOTED_DEFAULT_MINOR
                && patch >= AMBIGUOUS_DEFAULT_FIRST_PATCH && patch < QUOTED_DEFAULT_PATCH) {
                throw new SQLException("MariaDB 10.2.1～10.2.6 的目录无法可靠区分字符串与默认表达式，请人工核对源默认值");
            }
            boolean quoted = major > QUOTED_DEFAULT_MAJOR || major == QUOTED_DEFAULT_MAJOR
                && (minor > QUOTED_DEFAULT_MINOR || minor == QUOTED_DEFAULT_MINOR && patch >= QUOTED_DEFAULT_PATCH);
            task.backendSql("MySQL 兼容默认值目录格式：version=" + version + "；字符串已引用=" + quoted);
            return quoted;
        } catch (NumberFormatException exception) {
            throw new SQLException("MySQL 兼容数据库版本无法解析，不能推测默认值目录格式", exception);
        }
    }

    /**
     * 对 Oracle/达梦使用受限的 JDBC 字段目录，转义通配符后仍按实际模式和表名精确筛选。
     * Params: task 当前任务；connection 当前连接；table 物理表。
     * Returns: 驱动实际返回的字段定义；nativeType 不伪装为完整原生 DDL，保持 null。
     */
    private static Map<String, Definition> readJdbc(MigrationTask task, Connection connection,
        QualifiedName table) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = nullableText(table.getSchema());
        if (schema == null) {
            try {
                schema = nullableText(connection.getSchema());
            } catch (SQLFeatureNotSupportedException exception) {
                schema = null;
            } catch (AbstractMethodError error) {
                schema = null;
            }
        }
        if (schema == null) {
            schema = nullableText(metadata.getUserName());
        }
        if (schema == null) {
            throw new SQLException("无法确定物理表所属模式，未执行全库字段扫描");
        }
        schema = schema.toUpperCase(Locale.ROOT);
        String tableName = table.getTable().toUpperCase(Locale.ROOT);
        String escape = metadata.getSearchStringEscape();
        String schemaPattern = exactPattern(schema, escape);
        String tablePattern = exactPattern(tableName, escape);
        String catalog = nullableText(connection.getCatalog());
        task.backendSql("JDBC元数据[物理字段定义补读]：getColumns；catalog=" + catalog + "；schemaPattern="
            + schemaPattern + "；tablePattern=" + tablePattern + "；columnPattern=" + ALL_COLUMNS_PATTERN);
        Map<String, Definition> definitions = new LinkedHashMap<>();
        checkControl(task);
        try (ResultSet rows = metadata.getColumns(catalog, schemaPattern, tablePattern, ALL_COLUMNS_PATTERN)) {
            while (rows.next()) {
                checkControl(task);
                String actualTable = nullableText(rows.getString("TABLE_NAME"));
                String actualSchema = nullableText(rows.getString("TABLE_SCHEM"));
                if (!tableName.equals(actualTable) || !schema.equals(actualSchema)) {
                    continue;
                }
                String actualCatalog = nullableText(rows.getString("TABLE_CAT"));
                if (catalog != null && actualCatalog != null && !catalog.equals(actualCatalog)) {
                    continue;
                }
                Integer nullable = integer(rows, "NULLABLE");
                int nullableCode = nullable != null && (nullable == DatabaseMetaData.columnNoNulls
                    || nullable == DatabaseMetaData.columnNullable) ? nullable : DatabaseMetaData.columnNullableUnknown;
                add(definitions, new Definition(nullableText(rows.getString("COLUMN_NAME")), null,
                    nullableText(rows.getString("TYPE_NAME")), integer(rows, "DATA_TYPE"),
                    integer(rows, "COLUMN_SIZE"), integer(rows, "DECIMAL_DIGITS"), nullableCode,
                    ColumnDefaultValue.jdbc(rows.getString("COLUMN_DEF"))));
            }
            checkControl(task);
        } catch (Exception exception) {
            checkControl(task);
            throw exception;
        }
        return definitions;
    }

    /** Params: value 精确标识符；escape 驱动的模式转义文本。 Returns: 不扩展为其他表的目录模式。 */
    private static String exactPattern(String value, String escape) throws SQLException {
        if (escape == null || escape.isEmpty()) {
            if (value.contains(PATTERN_MANY) || value.contains(PATTERN_ONE)) {
                throw new SQLException("驱动未提供元数据模式转义字符，未扩大物理表字段读取范围");
            }
            return value;
        }
        return value.replace(escape, escape + escape).replace(PATTERN_MANY, escape + PATTERN_MANY)
            .replace(PATTERN_ONE, escape + PATTERN_ONE);
    }

    /** Params: rows 当前字段目录行；name 整数目录字段。 Returns: 实际整数值，SQL NULL 或越界时为 null。 */
    private static Integer integer(ResultSet rows, String name) throws SQLException {
        long value = rows.getLong(name);
        if (rows.wasNull() || value > MAX_METADATA_INTEGER || value < MIN_METADATA_INTEGER) {
            return null;
        }
        return (int)value;
    }

    /** Params: definitions 字段目录结果；definition 当前真实字段。 Returns: 无；无名或重复字段直接报错。 */
    private static void add(Map<String, Definition> definitions, Definition definition) throws SQLException {
        if (definition.name == null) {
            throw new SQLException("物理字段目录缺少字段名，不能猜测其归属");
        }
        String key = definition.name.trim().toUpperCase(Locale.ROOT);
        if (definitions.put(key, definition) != null) {
            throw new SQLException("物理字段目录返回重复字段名：" + definition.name);
        }
    }

    /** Params: value 可能为空的目录文本。 Returns: 空白文本为 null，非空内容原样保留。 */
    private static String nullableText(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    /** Params: task 当前任务。 Returns: 无；暂停、终止和中断在目录读取边界生效。 */
    private static void checkControl(MigrationTask task) throws Exception {
        task.stopCheckpoint();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("物理字段元数据补读线程已中断");
        }
        task.checkpoint();
    }

    /**
     * 真实物理字段元数据；空属性表示目录未提供，不代表默认定义。
     * Author: Codex
     * date: 2026-10-28
     */
    static final class Definition {
        /** 字段目录原始字段名。 */
        final String name;
        /** MySQL COLUMN_TYPE 或 Oracle 物理目录完整 INTERVAL 声明，保持目录原文。 */
        final String nativeType;
        /** 目录实际提供的类型名，不补造长度或精度。 */
        final String typeName;
        /** 目录实际提供的 JDBC 类型，未提供时为 null。 */
        final Integer jdbcType;
        /** 实际字段长度或数值精度，未知或不可表示时为 null。 */
        final Integer precision;
        /** 实际小数位或小数秒精度，未知时为 null。 */
        final Integer scale;
        /** JDBC 可空标志，未可靠提供时为 columnNullableUnknown。 */
        final int nullable;
        /** 已读取的源默认值；未知状态不能当作源没有默认值。 */
        final ColumnDefaultValue defaultValue;

        /**
         * Params: name 字段名；nativeType 原生定义；typeName/jdbcType 类型；precision/scale 精度；nullable 可空性。
         * Returns: 不推测缺失属性的字段定义。
         */
        Definition(String name, String nativeType, String typeName, Integer jdbcType, Integer precision,
            Integer scale, int nullable) {
            this(name, nativeType, typeName, jdbcType, precision, scale, nullable, ColumnDefaultValue.none());
        }

        /**
         * Params: name 字段名；nativeType 原生定义；typeName/jdbcType 类型；precision/scale 精度；
         * nullable 可空性；defaultValue 可靠取得的默认值状态。
         * Returns: 保留默认值且不推测缺失属性的字段定义。
         */
        Definition(String name, String nativeType, String typeName, Integer jdbcType, Integer precision,
            Integer scale, int nullable, ColumnDefaultValue defaultValue) {
            this.name = name;
            this.nativeType = nativeType;
            this.typeName = typeName;
            this.jdbcType = jdbcType;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
            this.defaultValue = defaultValue == null ? ColumnDefaultValue.unknown() : defaultValue;
        }
    }
}
