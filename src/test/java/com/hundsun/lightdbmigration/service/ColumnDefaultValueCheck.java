package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线验证默认值从物理定义传入两种任务的结构建议，且不会逃逸 SQL 或误合并冲突建议。
 * Author: Codex
 * date: 2026-10-30
 */
public final class ColumnDefaultValueCheck {

    /** 项目支持的三个数据库方言。 */
    private static final List<String> DIALECTS = Arrays.asList(MigrationConstants.DB_MYSQL,
        MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM);
    /** 此检查的目标表，只用于构造 SQL，不连接数据库。 */
    private static final QualifiedName TARGET = SqlSupport.parseTargetTable("DEFAULT_CHECK_TARGET");
    /** 同名字段用于验证默认值建议冲突。 */
    private static final String FIELD = "VIP_LEVEL";
    /** 源列长度。 */
    private static final int SOURCE_LENGTH = 3;
    /** 目标列长度，触发字段修改。 */
    private static final int TARGET_LENGTH = 1;
    /** 数值列精度。 */
    private static final int NUMBER_PRECISION = 8;
    /** 整数小数位。 */
    private static final int INTEGER_SCALE = 0;
    /** 单条建议数量。 */
    private static final int ONE_STATEMENT = 1;
    /** 没有可执行建议。 */
    private static final int NO_STATEMENTS = 0;
    /** 用于只读产品版本代理的 JDBC 方法名。 */
    private static final String JDBC_PRODUCT_VERSION = "getDatabaseProductVersion";
    /** 用于取得目录代理的 JDBC 方法名。 */
    private static final String JDBC_METADATA = "getMetaData";
    /** 校验计数。 */
    private static int assertions;

    /** Params: 无。 Returns: 不允许实例化检查类。 */
    private ColumnDefaultValueCheck() {
    }

    /** Params: args 未使用。 Returns: 无；断言失败直接抛出异常。 */
    public static void main(String[] args) throws Exception {
        verifyDefaultValues();
        verifyUnsafeValues();
        for (String dialect : DIALECTS) {
            verifyPhysicalMetadataDefaults(dialect);
            verifyArchiveStatements(dialect);
            verifyMigrationStatements(dialect);
            verifyMissingMetadataIsVisible(dialect);
            verifyDefaultSuggestionConflicts(dialect);
        }
        verifyCrossDialectStatements();
        verifyMysqlVersionFormats();
        verifyPhysicalMetadataDefaults(MigrationConstants.DB_MYSQL, "5.5.5-10.9.5-MariaDB");
        System.out.println("[RESULT] COLUMN_DEFAULT_VALUE_CHECK_PASSED assertions=" + assertions);
    }

    /** Params: dialect 目录方言。 Returns: 无；实际读取字段目录并补入迁移元数据，不仅检查 SQL 拼接。 */
    private static void verifyPhysicalMetadataDefaults(String dialect) throws Exception {
        verifyPhysicalMetadataDefaults(dialect, null);
    }

    /** Params: dialect 目录方言；version 可选的已引用目录产品版本。 Returns: 无；走实际目录读取链路。 */
    private static void verifyPhysicalMetadataDefaults(String dialect, String version) throws Exception {
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("COLUMN_NAME", FIELD);
        row.put("COLUMN_TYPE", "varchar(3)");
        row.put("DATA_TYPE", MigrationConstants.DB_MYSQL.equals(dialect) ? "varchar" : Types.VARCHAR);
        row.put("TYPE_NAME", textType(dialect));
        row.put("IS_NULLABLE", "NO");
        row.put("NULLABLE", DatabaseMetaData.columnNoNulls);
        row.put("CHARACTER_MAXIMUM_LENGTH", SOURCE_LENGTH);
        row.put("COLUMN_SIZE", SOURCE_LENGTH);
        row.put("DECIMAL_DIGITS", INTEGER_SCALE);
        row.put("COLUMN_DEFAULT", version == null ? " " : "' '");
        row.put("EXTRA", "");
        row.put("COLUMN_DEF", "' '");
        row.put("TABLE_NAME", "DEFAULT_CHECK_SOURCE");
        row.put("TABLE_SCHEM", "FIXTURE");
        row.put("TABLE_CAT", "fixture_catalog");
        DatabaseMetaData metadata = (DatabaseMetaData)Proxy.newProxyInstance(ColumnDefaultValueCheck.class
            .getClassLoader(), new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case JDBC_PRODUCT_VERSION: return version;
                    case "getUserName": return "FIXTURE";
                    case "getSearchStringEscape": return "\\";
                    case "getColumns":
                        equal("fixture_catalog", args[0], "物理目录读取限定 catalog");
                        equal("FIXTURE", args[1], "物理目录读取限定 schema");
                        equal("DEFAULT\\_CHECK\\_SOURCE", args[2], "目录表名中的通配符必须转义");
                        return singleMetadataRow(row);
                    default: return primitiveDefault(method.getReturnType());
                }
            });
        Connection connection = (Connection)Proxy.newProxyInstance(ColumnDefaultValueCheck.class.getClassLoader(),
            new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "getCatalog": return "fixture_catalog";
                    case "getSchema": return "FIXTURE";
                    case "getMetaData": return metadata;
                    case "prepareStatement":
                        contains((String)args[0], "COLUMN_DEFAULT", "MySQL 目录查询必须读取默认值");
                        contains((String)args[0], "EXTRA", "MySQL 目录查询必须区分表达式默认值");
                        return Proxy.newProxyInstance(ColumnDefaultValueCheck.class.getClassLoader(),
                            new Class<?>[] { PreparedStatement.class }, (statement, statementMethod, values) -> {
                                switch (statementMethod.getName()) {
                                    case "setString":
                                        bindings.put(String.valueOf(values[0]), (String)values[1]);
                                        return null;
                                    case "executeQuery": return singleMetadataRow(row);
                                    default: return primitiveDefault(statementMethod.getReturnType());
                                }
                            });
                    default: return primitiveDefault(method.getReturnType());
                }
            });
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Collections.<MigrationStepRequest>emptyList());
        MigrationTask task = new MigrationTask("default-metadata-check", request);
        Map<String, PhysicalColumnMetadataSupport.Definition> definitions = PhysicalColumnMetadataSupport
            .read(task, connection, dialect, SqlSupport.parseTargetTable("DEFAULT_CHECK_SOURCE"));
        equal(ONE_STATEMENT, definitions.size(), "目录应保留读取到的字段");
        equal(" DEFAULT ' '", definitions.get(FIELD).defaultValue.clause(dialect, dialect),
            "物理目录的空格默认值必须完整传递：" + dialect);
        if (MigrationConstants.DB_MYSQL.equals(dialect)) {
            equal("fixture_catalog", bindings.get("1"), "MySQL 目录查询绑定当前数据库");
            equal("DEFAULT_CHECK_SOURCE", bindings.get("2"), "MySQL 目录查询只绑定当前物理表");
        }
        Class<?> columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method supplement = method(MigrationEngine.class, "supplementColumn", columnClass,
            PhysicalColumnMetadataSupport.Definition.class, String.class);
        Method add = method(MigrationEngine.class, "addColumnStatement", Set.class, QualifiedName.class,
            columnClass, String.class, String.class);
        MigrationEngine engine = new MigrationEngine();
        Object query = migrationColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
            ColumnDefaultValue.unknown());
        Object supplemented = invoke(supplement, engine, query, definitions.get(FIELD), dialect);
        Set<String> statements = new LinkedHashSet<>();
        invoke(add, engine, statements, TARGET, supplemented, dialect, dialect);
        contains(onlyStatement(statements), " DEFAULT ' ' NOT NULL", "物理默认值补入查询后传到最终 ADD");
    }

    /** Params: 无。 Returns: 无；产品版本决定目录默认值格式，值的引号不决定格式。 */
    private static void verifyMysqlVersionFormats() throws Exception {
        Map<String, Boolean> versions = new LinkedHashMap<>();
        versions.put("8.0.33", false);
        versions.put("5.5.5-10.9.5-MariaDB", true);
        versions.put("10.2.0-MariaDB", false);
        versions.put("10.2.7-MariaDB", true);
        versions.put("5.5.5-10.1.48-MariaDB-0ubuntu0", false);
        versions.put("10.3.0-MariaDB", true);
        versions.put("11.0.0-MariaDB", true);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Collections.<MigrationStepRequest>emptyList());
        MigrationTask task = new MigrationTask("mysql-default-version-check", request);
        Method detect = method(PhysicalColumnMetadataSupport.class, "quotedMysqlDefaults",
            MigrationTask.class, Connection.class);
        for (Map.Entry<String, Boolean> item : versions.entrySet()) {
            equal(item.getValue(), invoke(detect, null, task, versionConnection(item.getKey())),
                "MySQL/MariaDB 版本格式识别：" + item.getKey());
        }
        for (String ambiguous : Arrays.asList("MariaDB unknown", "10.2.1-MariaDB", "5.5.5-10.2.6-MariaDB")) {
            boolean rejected = false;
            try {
                invoke(detect, null, task, versionConnection(ambiguous));
            } catch (java.sql.SQLException exception) {
                rejected = true;
            }
            check(rejected, "MariaDB 目录格式未知或不能区分表达式时不能猜测编码");
        }
    }

    /** Params: version 模拟产品版本。 Returns: 仅提供版本 API 的离线连接。 */
    private static Connection versionConnection(String version) {
        DatabaseMetaData metadata = (DatabaseMetaData)Proxy.newProxyInstance(ColumnDefaultValueCheck.class
            .getClassLoader(), new Class<?>[] { DatabaseMetaData.class }, (proxy, method, args) ->
                JDBC_PRODUCT_VERSION.equals(method.getName()) ? version : primitiveDefault(method.getReturnType()));
        return (Connection)Proxy.newProxyInstance(ColumnDefaultValueCheck.class.getClassLoader(),
            new Class<?>[] { Connection.class }, (proxy, method, args) ->
                JDBC_METADATA.equals(method.getName()) ? metadata : primitiveDefault(method.getReturnType()));
    }

    /** Params: row 单条 JDBC 目录行。 Returns: 全部属性从该目录行获取的只读结果集代理。 */
    private static ResultSet singleMetadataRow(Map<String, Object> row) {
        boolean[] state = { false, false };
        return (ResultSet)Proxy.newProxyInstance(ColumnDefaultValueCheck.class.getClassLoader(),
            new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next":
                        boolean available = !state[0];
                        state[0] = true;
                        return available;
                    case "getString":
                        Object text = row.get(args[0]);
                        state[1] = text == null;
                        return text == null ? null : text.toString();
                    case "getLong":
                        Object number = row.get(args[0]);
                        state[1] = number == null;
                        return number == null ? 0L : ((Number)number).longValue();
                    case "wasNull": return state[1];
                    default: return primitiveDefault(method.getReturnType());
                }
            });
    }

    /** Params: type 代理方法返回类型。 Returns: 未使用 JDBC 方法的空值或基本类型零值。 */
    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || void.class.equals(type)) {
            return null;
        }
        if (boolean.class.equals(type)) {
            return false;
        }
        if (long.class.equals(type)) {
            return 0L;
        }
        if (double.class.equals(type)) {
            return 0D;
        }
        if (float.class.equals(type)) {
            return 0F;
        }
        if (short.class.equals(type)) {
            return (short)0;
        }
        if (byte.class.equals(type)) {
            return (byte)0;
        }
        if (char.class.equals(type)) {
            return '\0';
        }
        return INTEGER_SCALE;
    }

    /** Params: 无。 Returns: 无；字面量、表达式、未知与无默认值均有明确区分。 */
    private static void verifyDefaultValues() {
        for (String dialect : DIALECTS) {
            equal("", ColumnDefaultValue.none().clause(dialect, dialect), "无默认值不虚构 DEFAULT");
            equal(null, ColumnDefaultValue.unknown().clause(dialect, dialect), "未知默认值不能当成无默认值");
            equal(" DEFAULT ' '", ColumnDefaultValue.jdbc("' '").clause(dialect, dialect),
                "保留引号内单个空格");
            equal(" DEFAULT 0", ColumnDefaultValue.jdbc("0").clause(dialect, dialect), "保留数字默认值");
            equal(" DEFAULT CURRENT_TIMESTAMP", ColumnDefaultValue.jdbc("CURRENT_TIMESTAMP")
                .clause(dialect, dialect), "保留时间表达式");
        }
        equal(" DEFAULT ''", ColumnDefaultValue.mysql("", "varchar", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MySQL 空串是有效默认值");
        equal(" DEFAULT ' '", ColumnDefaultValue.mysql(" ", "varchar", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MySQL 空格不能被 trim");
        equal(" DEFAULT 0", ColumnDefaultValue.mysql("0", "decimal", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MySQL 数值不能误加字符串引号");
        equal(" DEFAULT 'CURRENT_TIMESTAMP'", ColumnDefaultValue.mysql("CURRENT_TIMESTAMP", "varchar", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "文本默认值不能误当函数");
        String expression = ColumnDefaultValue.mysql("uuid()", "varchar", "DEFAULT_GENERATED")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        contains(expression, "uuid()", "MySQL 表达式默认值保留原函数");
        check(!expression.contains("'uuid()'"), "MySQL 表达式不能变成字面量");
        check(!ColumnDefaultValue.jdbc("'A'").signature().equals(ColumnDefaultValue.jdbc("'a'").signature()),
            "默认字符串大小写不同不能被语义去重");
        check(!ColumnDefaultValue.none().signature().equals(ColumnDefaultValue.unknown().signature()),
            "未知与无默认值的签名不能相同");
        equal(null, ColumnDefaultValue.jdbc("TEST_SEQUENCE.NEXTVAL")
            .clause(MigrationConstants.DB_ORACLE, MigrationConstants.DB_MYSQL), "跨库序列默认值不能照搬");
        equal(" DEFAULT N'中文'", ColumnDefaultValue.jdbc("N'中文'")
            .clause(MigrationConstants.DB_ORACLE, MigrationConstants.DB_ORACLE), "同库国家字符集字面量保留 N 前缀");
        equal(null, ColumnDefaultValue.jdbc("N'中文'")
            .clause(MigrationConstants.DB_ORACLE, MigrationConstants.DB_MYSQL), "国家字符集默认值跨库需人工确认");
        equal(null, ColumnDefaultValue.mysql("0x41", "varbinary", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "二进制目录展示不能变成文本默认值");
        contains(ColumnDefaultValue.mysql("0x41", "binary", "").describe(), "SHOW CREATE TABLE",
            "二进制默认值应给出人工核对方向");
        equal(" DEFAULT ('abc')", ColumnDefaultValue.mysql("'abc'", "text", "DEFAULT_GENERATED")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "TEXT 字面量表达式必须保留括号");
        equal(" DEFAULT (0)", ColumnDefaultValue.mysql("0", "json", "DEFAULT_GENERATED")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "JSON 数值表达式必须保留括号");
        equal(" DEFAULT ((abs(-1)) + 2)", ColumnDefaultValue.mysql("(abs(-1)) + 2", "int", "DEFAULT_GENERATED")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MySQL 表达式须整体加括号");
        equal(ColumnDefaultValue.mysql("abc", "varchar", "").signature(),
            ColumnDefaultValue.mysql("('abc')", "text", "DEFAULT_GENERATED").signature(),
            "同内容字面量及字面量表达式按语义合并");
        equal(" DEFAULT ' '", ColumnDefaultValue.mysql("' '", "varchar", "", true)
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MariaDB 已引用目录不能重复引用");
        equal(" DEFAULT 'O''Brien'", ColumnDefaultValue.mysql("'O''Brien'", "varchar", "", true)
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MariaDB 保留字面量中的单引号");
        equal(" DEFAULT ('abc')", ColumnDefaultValue.mysql("'abc'", "text", "", true)
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "MariaDB TEXT 常量也保留原生 MySQL 要求的括号");
        equal(" DEFAULT '''abc'''", ColumnDefaultValue.mysql("'abc'", "varchar", "", false)
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL), "原生 MySQL 值含引号时仍须按真实内容引用");
        for (String source : DIALECTS) {
            for (String target : DIALECTS) {
                if (!source.equals(target)) {
                    equal(null, ColumnDefaultValue.jdbc("''").clause(source, target),
                        "空串默认值跨库不能猜测 NULL 语义");
                }
            }
        }
    }

    /** Params: 无。 Returns: 无；实际字符串中的 SQL 标记保持字面量，目录注入不能成为 DDL。 */
    private static void verifyUnsafeValues() {
        String literal = "'note; -- ''quoted'''";
        for (String dialect : DIALECTS) {
            String clause = ColumnDefaultValue.jdbc(literal).clause(dialect, dialect);
            equal(" DEFAULT " + literal, clause, "字符串中的分号、注释及引号不能损坏默认值");
            for (String unsafe : Arrays.asList("0; DROP TABLE SHOULD_NOT_RUN", "0 -- ignored", "0 /* ignored */",
                "'unclosed", "(1 + 2", "1)")) {
                equal(null, ColumnDefaultValue.jdbc(unsafe).clause(dialect, dialect),
                    "不安全目录默认表达式不能生成可执行 SQL：" + unsafe);
            }
        }
        String clause = ColumnDefaultValue.mysql("O'Reilly; -- safe", "varchar", "")
            .clause(MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        check(clause != null, "MySQL 实际字符串需安全引用，不能仅因存在 SQL 标记而丢失默认值");
        equal(ONE_STATEMENT, StructureSyncScript.parseStatements("ALTER TABLE `DEFAULT_CHECK_TARGET`"
            + " ADD COLUMN `VALUE_TEXT` VARCHAR(60)" + clause + " NOT NULL;").size(),
            "MySQL 字符串默认值不能拆成多条 SQL");
    }

    /** Params: dialect 同库归档数据库类型。 Returns: 无；三个结构操作均继承默认值。 */
    private static void verifyArchiveStatements(String dialect) throws Exception {
        ArchiveStructureValidator validator = new ArchiveStructureValidator();
        Class<?> columnClass = Class.forName(ArchiveStructureValidator.class.getName() + "$Column");
        Method add = method(ArchiveStructureValidator.class, "addColumn", Set.class, QualifiedName.class,
            columnClass, columnClass, String.class);
        Method create = method(ArchiveStructureValidator.class, "addCreateTable", Set.class, QualifiedName.class,
            List.class, String.class);
        Object source = archiveColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
            textDefault(dialect, " "));
        Object target = archiveColumn(FIELD, dialect, TARGET_LENGTH, DatabaseMetaData.columnNoNulls,
            ColumnDefaultValue.none());
        Set<String> statements = new LinkedHashSet<>();
        invoke(add, validator, statements, TARGET, source, null, dialect);
        String sql = onlyStatement(statements);
        contains(sql, " DEFAULT ' ' NOT NULL", "归档新增列保留源默认空格及非空约束：" + dialect);
        statements.clear();
        invoke(add, validator, statements, TARGET, source, target, dialect);
        sql = onlyStatement(statements);
        contains(sql, " DEFAULT ' '", "归档 MODIFY 不能在省略重复 NULL 约束时丢 DEFAULT：" + dialect);
        if (!MigrationConstants.DB_MYSQL.equals(dialect)) {
            check(!sql.contains("NOT NULL"), "归档 Oracle/DM 修改不能重复设置同样的非空约束");
        }
        statements.clear();
        Object number = archiveNumber(dialect);
        invoke(create, validator, statements, TARGET, Arrays.asList(source, number), dialect);
        sql = onlyStatement(statements);
        contains(sql, " DEFAULT ' ' NOT NULL", "归档 CREATE 继承文本默认值：" + dialect);
        contains(sql, " DEFAULT 0 NOT NULL", "归档 CREATE 继承数字默认值：" + dialect);
    }

    /** Params: dialect 同方言迁移数据库类型。 Returns: 无；三个结构操作均继承物理默认值。 */
    private static void verifyMigrationStatements(String dialect) throws Exception {
        MigrationEngine engine = new MigrationEngine();
        Class<?> columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method add = method(MigrationEngine.class, "addColumnStatement", Set.class, QualifiedName.class,
            columnClass, String.class, String.class);
        Method modify = method(MigrationEngine.class, "addModifyColumnStatement", Set.class, QualifiedName.class,
            columnClass, columnClass, String.class, String.class);
        Method create = method(MigrationEngine.class, "addCreateTableStatement", Set.class, QualifiedName.class,
            List.class, String.class, String.class);
        Object source = migrationColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
            textDefault(dialect, " "));
        Object target = migrationColumn(FIELD, dialect, TARGET_LENGTH, DatabaseMetaData.columnNoNulls,
            ColumnDefaultValue.none());
        Set<String> statements = new LinkedHashSet<>();
        invoke(add, engine, statements, TARGET, source, dialect, dialect);
        String sql = onlyStatement(statements);
        contains(sql, " DEFAULT ' ' NOT NULL", "迁移 ADD 继承源默认值：" + dialect);
        statements.clear();
        invoke(modify, engine, statements, TARGET, source, target, dialect, dialect);
        sql = onlyStatement(statements);
        contains(sql, " DEFAULT ' '", "迁移 MODIFY 不能遗漏 DEFAULT：" + dialect);
        if (!MigrationConstants.DB_MYSQL.equals(dialect)) {
            check(!sql.contains("NOT NULL"), "迁移 Oracle/DM 修改不能重复设置同样的非空约束");
        }
        statements.clear();
        invoke(create, engine, statements, TARGET, Arrays.asList(source), dialect, dialect);
        contains(onlyStatement(statements), " DEFAULT ' ' NOT NULL", "迁移 CREATE 继承源默认值：" + dialect);
    }

    /** Params: dialect 当前数据库类型。 Returns: 无；目录未知或不安全时明确保留人工处理说明。 */
    private static void verifyMissingMetadataIsVisible(String dialect) throws Exception {
        ArchiveStructureValidator validator = new ArchiveStructureValidator();
        MigrationEngine engine = new MigrationEngine();
        Class<?> archiveClass = Class.forName(ArchiveStructureValidator.class.getName() + "$Column");
        Class<?> migrationClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method archiveAdd = method(ArchiveStructureValidator.class, "addColumn", Set.class, QualifiedName.class,
            archiveClass, archiveClass, String.class);
        Method migrationAdd = method(MigrationEngine.class, "addColumnStatement", Set.class, QualifiedName.class,
            migrationClass, String.class, String.class);
        for (ColumnDefaultValue value : Arrays.asList(ColumnDefaultValue.unknown(),
            ColumnDefaultValue.jdbc("0; DROP TABLE SHOULD_NOT_RUN"))) {
            Set<String> statements = new LinkedHashSet<>();
            invoke(archiveAdd, validator, statements, TARGET,
                archiveColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls, value), null, dialect);
            equal(NO_STATEMENTS, parsed(statements).size(), "归档默认值未知不能默默生成缺省 DEFAULT 的 DDL");
            check(!statements.isEmpty(), "归档应解释未生成默认值 DDL 的原因");
            statements.clear();
            invoke(migrationAdd, engine, statements, TARGET,
                migrationColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls, value), dialect, dialect);
            equal(NO_STATEMENTS, parsed(statements).size(), "迁移默认值未知不能默默生成缺省 DEFAULT 的 DDL");
            check(!statements.isEmpty(), "迁移应解释未生成默认值 DDL 的原因");
        }
    }

    /** Params: dialect 同库迁移数据库类型。 Returns: 无；默认值不同的同目标建议不会误合并或覆盖。 */
    private static void verifyDefaultSuggestionConflicts(String dialect) throws Exception {
        MigrationEngine engine = new MigrationEngine();
        Class<?> columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method add = method(MigrationEngine.class, "addColumnStatement", Set.class, QualifiedName.class,
            columnClass, String.class, String.class);
        Method create = method(MigrationEngine.class, "addCreateTableStatement", Set.class, QualifiedName.class,
            List.class, String.class, String.class);
        Object first = migrationColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
            textDefault(dialect, "A"));
        Object other = migrationColumn(FIELD, dialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
            textDefault(dialect, "B"));
        Set<String> statements = structureStatements();
        invoke(add, engine, statements, TARGET, first, dialect, dialect);
        invoke(add, engine, statements, TARGET, first, dialect, dialect);
        equal(ONE_STATEMENT, parsed(statements).size(), "相同源默认值的重复 ADD 应合并");
        invoke(add, engine, statements, TARGET, other, dialect, dialect);
        equal(NO_STATEMENTS, parsed(statements).size(), "默认值不同的 ADD 不能继续执行相互覆盖的候选");
        contains(String.join("\n", statements), "冲突", "默认值不同的 ADD 须说明冲突");
        statements = structureStatements();
        invoke(create, engine, statements, TARGET, Arrays.asList(first), dialect, dialect);
        invoke(create, engine, statements, TARGET, Arrays.asList(other), dialect, dialect);
        equal(NO_STATEMENTS, parsed(statements).size(), "默认值不同的 CREATE 不能误合并");
        Method register = method(MigrationEngine.class, "registerColumnExpectation", Set.class,
            QualifiedName.class, columnClass, String.class, String.class, boolean.class);
        statements = structureStatements();
        invoke(register, engine, statements, TARGET, first, dialect, dialect, true);
        invoke(add, engine, statements, TARGET, first, dialect, dialect);
        invoke(register, engine, statements, TARGET, other, dialect, dialect, true);
        equal(NO_STATEMENTS, parsed(statements).size(), "匹配目标的其他步骤仍须参与源默认值冲突检查");
    }

    /** Params: 无。 Returns: 无；跨库字面量继承，专属序列表达式有人工处理说明。 */
    private static void verifyCrossDialectStatements() throws Exception {
        MigrationEngine engine = new MigrationEngine();
        Class<?> columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Method add = method(MigrationEngine.class, "addColumnStatement", Set.class, QualifiedName.class,
            columnClass, String.class, String.class);
        for (String sourceDialect : DIALECTS) {
            for (String targetDialect : DIALECTS) {
                if (sourceDialect.equals(targetDialect)) {
                    continue;
                }
                Set<String> statements = new LinkedHashSet<>();
                Object column = migrationColumn(FIELD, sourceDialect, SOURCE_LENGTH, DatabaseMetaData.columnNoNulls,
                    textDefault(sourceDialect, " "));
                invoke(add, engine, statements, TARGET, column, sourceDialect, targetDialect);
                contains(onlyStatement(statements), " DEFAULT ' ' NOT NULL", "跨库迁移应携带安全默认字面量");
            }
        }
        Set<String> statements = new LinkedHashSet<>();
        Object column = migrationColumn(FIELD, MigrationConstants.DB_ORACLE, SOURCE_LENGTH,
            DatabaseMetaData.columnNoNulls, ColumnDefaultValue.jdbc("TEST_SEQUENCE.NEXTVAL"));
        invoke(add, engine, statements, TARGET, column, MigrationConstants.DB_ORACLE, MigrationConstants.DB_MYSQL);
        equal(NO_STATEMENTS, parsed(statements).size(), "Oracle 序列默认值不能直接拼进 MySQL DDL");
        check(!statements.isEmpty(), "无法映射跨库默认值须给人工处理方向");
    }

    /** Params: dialect 字段方言；value 字符串值。 Returns: 对应数据库读取到的文本默认值。 */
    private static ColumnDefaultValue textDefault(String dialect, String value) {
        return MigrationConstants.DB_MYSQL.equals(dialect) ? ColumnDefaultValue.mysql(value, "VARCHAR", "")
            : ColumnDefaultValue.jdbc("'" + value.replace("'", "''") + "'");
    }

    /** Params: dialect 字段方言。 Returns: 对应字符串类型名。 */
    private static String textType(String dialect) {
        return MigrationConstants.DB_ORACLE.equals(dialect) ? "VARCHAR2" : "VARCHAR";
    }

    /** Params: name 字段名；dialect 方言；length 长度；nullable 可空性；value 默认值。 Returns: 私有归档列。 */
    private static Object archiveColumn(String name, String dialect, int length, int nullable,
        ColumnDefaultValue value) throws Exception {
        Class<?> type = Class.forName(ArchiveStructureValidator.class.getName() + "$Column");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, String.class, ColumnDefaultValue.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, Types.VARCHAR, textType(dialect), length, INTEGER_SCALE, nullable,
            null, value);
    }

    /** Params: dialect 方言。 Returns: 数字默认 0 的私有归档列。 */
    private static Object archiveNumber(String dialect) throws Exception {
        Class<?> type = Class.forName(ArchiveStructureValidator.class.getName() + "$Column");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, String.class, ColumnDefaultValue.class);
        constructor.setAccessible(true);
        String numberType = MigrationConstants.DB_MYSQL.equals(dialect) ? "DECIMAL" : "NUMBER";
        return constructor.newInstance("AUTO_USED_FLAG", Types.NUMERIC, numberType, NUMBER_PRECISION,
            INTEGER_SCALE, DatabaseMetaData.columnNoNulls, null, ColumnDefaultValue.jdbc("0"));
    }

    /** Params: name 字段名；dialect 方言；length 长度；nullable 可空性；value 默认值。 Returns: 私有迁移列。 */
    private static Object migrationColumn(String name, String dialect, int length, int nullable,
        ColumnDefaultValue value) throws Exception {
        Class<?> type = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class, String.class, ColumnDefaultValue.class);
        constructor.setAccessible(true);
        return constructor.newInstance(name, Types.VARCHAR, textType(dialect), length, INTEGER_SCALE,
            nullable, false, null, value);
    }

    /** Params: 无。 Returns: 可检查同物理目标冲突与语义去重的私有建议集合。 */
    @SuppressWarnings("unchecked")
    private static Set<String> structureStatements() throws Exception {
        Class<?> type = Class.forName(MigrationEngine.class.getName() + "$StructureStatements");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (Set<String>)constructor.newInstance();
    }

    /** Params: type 所属类；name 方法名；parameters 参数类型。 Returns: 可调用的私有方法。 */
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    /** Params: method 方法；target 实例；arguments 参数。 Returns: 方法返回值，业务异常原样抛出。 */
    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw exception;
        }
    }

    /** Params: statements 建议集合。 Returns: 校验后的可执行语句，不包含人工建议注释。 */
    private static List<String> parsed(Set<String> statements) {
        return StructureSyncScript.parseStatements(String.join("\n", statements));
    }

    /** Params: statements 建议集合。 Returns: 唯一一条经过现有执行器分句检查的 DDL。 */
    private static String onlyStatement(Set<String> statements) {
        List<String> result = parsed(statements);
        equal(ONE_STATEMENT, result.size(), "应生成唯一可执行建议：" + statements);
        return result.get(INTEGER_SCALE);
    }

    /** Params: text 实际文本；expected 必须保留的内容；message 失败原因。 Returns: 无。 */
    private static void contains(String text, String expected, String message) {
        check(text != null && text.contains(expected), message + " expected=" + expected + " actual=" + text);
    }

    /** Params: expected 预期值；actual 实际值；message 失败原因。 Returns: 无。 */
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
            message + " expected=" + expected + " actual=" + actual);
    }

    /** Params: condition 断言条件；message 失败原因。 Returns: 无。 */
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
