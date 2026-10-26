package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在归档写入和删除前独立检查全部节点，汇总字段差异及人工审核用的同库 DDL。
 * Author: Codex
 * date: 2026-10-26
 */
final class ArchiveStructureValidator {

    /** 校验中的节点状态。 */
    private static final String STEP_VALIDATING = "VALIDATING";
    /** 校验失败的节点状态。 */
    private static final String STEP_FAILED = "FAILED";
    /** 数据库连接故障的 SQLState 类别。 */
    private static final String CONNECTION_SQL_STATE = "08";
    /** MySQL 表不存在的 SQLState。 */
    private static final String MISSING_TABLE_SQL_STATE = "42S02";
    /** MySQL 表不存在的错误号。 */
    private static final int MYSQL_MISSING_TABLE = 1146;
    /** Oracle 表不存在或不可见的错误号。 */
    private static final int ORACLE_MISSING_TABLE = 942;
    /** 达梦表不存在或不可见的错误号。 */
    private static final int DM_MISSING_TABLE = -2106;
    /** 未知精度边界。 */
    private static final int UNKNOWN_PRECISION = 0;
    /** MySQL 时间字段支持的最大小数秒位数。 */
    private static final int MYSQL_MAX_TIME_SCALE = 6;
    /** Oracle 和达梦时间字段支持的最大小数秒位数。 */
    private static final int MAX_TIME_SCALE = 9;
    /** 脚本统一换行符。 */
    private static final String LINE_BREAK = "\n";
    /** MySQL 无符号类型后缀。 */
    private static final String UNSIGNED_SUFFIX = " UNSIGNED";
    /** 原样保留且无需追加长度的已知同库字段类型。 */
    private static final Set<String> SIMPLE_TYPES = new LinkedHashSet<>(Arrays.asList(
        "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "BIT", "BOOLEAN", "BOOL",
        "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL", "BINARY_FLOAT", "BINARY_DOUBLE", "DATE",
        "CLOB", "NCLOB", "BLOB", "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT",
        "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "LONG", "LONG RAW", "JSON"));
    /** 必须保留字段长度的已知同库类型。 */
    private static final Set<String> LENGTH_TYPES = new LinkedHashSet<>(Arrays.asList(
        "CHAR", "VARCHAR", "VARCHAR2", "NCHAR", "NVARCHAR", "NVARCHAR2", "BINARY", "VARBINARY", "RAW"));
    /** 必须保留数字精度和小数位的同库类型。 */
    private static final Set<String> DECIMAL_TYPES = new LinkedHashSet<>(Arrays.asList(
        "DECIMAL", "DEC", "NUMERIC", "NUMBER"));
    /** 支持小数秒精度的同库时间类型。 */
    private static final Set<String> TIME_TYPES = new LinkedHashSet<>(Arrays.asList(
        "TIME", "DATETIME", "TIMESTAMP", "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE"));
    /** NUMBER 类型可以不指定精度。 */
    private static final String NUMBER_TYPE = "NUMBER";
    /** BIT 类型的长度含义为位数。 */
    private static final String BIT_TYPE = "BIT";
    /** Oracle 时间类型的时区修饰起点。 */
    private static final String TIME_ZONE_SEPARATOR = " WITH ";
    /** 禁止进入可执行建议的字段名控制字符。 */
    private static final String UNSAFE_FIELD_NAME_PATTERN = "(?s).*[\\p{Cc}\\p{Zl}\\p{Zp}].*";

    /**
     * 独立遍历全部配置表；结构异常继续收集，控制请求和连接故障立即上抛。
     * Params: task 当前任务；connection 归档连接；databaseType 数据库类型。
     * Returns: 无；发现任何问题时设置建议脚本并抛出汇总异常。
     */
    void validate(MigrationTask task, Connection connection, String databaseType) throws Exception {
        ArchiveRequest archive = task.getRequest().getArchive();
        Set<String> issues = new LinkedHashSet<>();
        Set<String> statements = new LinkedHashSet<>();
        List<ArchiveTableRequest> nodes = new ArrayList<>(archive.getMainTables());
        nodes.addAll(archive.getChildTables());
        checkControl(task);
        String namespace = readNamespace(task, connection, databaseType, issues);
        Set<String> unsafeTargets = unsafeTargets(nodes, namespace, issues);
        int index = 0;
        for (ArchiveTableRequest request : archive.getMainTables()) {
            validateNode(task, connection, request, index++, true, databaseType, issues, statements,
                namespace, unsafeTargets);
        }
        for (ArchiveTableRequest request : archive.getChildTables()) {
            validateNode(task, connection, request, index++, false, databaseType, issues, statements,
                namespace, unsafeTargets);
        }
        checkControl(task);
        if (!issues.isEmpty()) {
            task.setStructureSyncScript(buildScript(databaseType, issues, statements));
            task.info("已生成归档表结构同步建议脚本，请在任务日志下方审核、复制或下载；工具不会自动执行");
            task.error("归档表结构校验未通过，共发现 " + issues.size() + " 个问题：");
            int number = 1;
            for (String issue : issues) {
                task.error("归档表结构校验问题 " + number++ + "：" + issue);
            }
            throw new IllegalArgumentException("归档表结构校验未通过，共发现 " + issues.size()
                + " 个问题，已一次性输出到任务日志");
        }
        task.info("全部归档主表和子表的字段结构预检通过，继续校验关联关系及归档范围");
    }

    /**
     * 校验单节点的物理表与主表查询，不依赖上级节点的准备结果。
     * Params: task/connection 当前任务及连接；request/index 节点配置及序号；mainTable 是否主表；
     * databaseType 数据库类型；issues/statements 全任务差异与建议；namespace 默认模式；unsafeTargets 禁止建议执行的目标。
     * Returns: 无；当前节点问题写入集合，后续节点仍可执行。
     */
    private void validateNode(MigrationTask task, Connection connection, ArchiveTableRequest request, int index,
        boolean mainTable, String databaseType, Set<String> issues, Set<String> statements,
        String namespace, Set<String> unsafeTargets) throws Exception {
        checkControl(task);
        int before = issues.size();
        String label = "归档节点 " + (index + 1) + "（" + request.getName() + "）";
        task.setStepStatus(index, STEP_VALIDATING);
        task.markValidating("正在汇总检查" + label + "的字段结构");
        QualifiedName sourceName = parseName(request.getSourceTable(), label + "源表", issues);
        QualifiedName targetName = parseName(request.getTargetTable(), label + "归档表", issues);
        Set<String> nodeStatements = new LinkedHashSet<>();
        List<Column> source = readTable(task, connection, sourceName, databaseType, label + "源表", issues);
        List<Column> target = null;
        if (targetName != null) {
            try {
                target = readColumns(task, connection, "SELECT * FROM " + quoteTable(targetName, databaseType)
                    + " WHERE 1 = 0");
            } catch (Exception exception) {
                rethrowFatal(task, exception);
                issues.add(label + "归档表 " + targetName.toSql() + " 无法读取字段：" + SqlSupport.safeMessage(exception));
                if (source != null && isMissingTable(exception, databaseType)) {
                    addCreateTable(nodeStatements, targetName, source, databaseType);
                }
            }
        }
        if (source != null) {
            uniqueColumns(source, label + "源表", issues);
        }
        if (target != null) {
            uniqueColumns(target, label + "归档表", issues);
        }
        if (source != null && target != null) {
            compareTables(source, target, targetName, databaseType, label, issues, nodeStatements);
        }
        if (mainTable) {
            try {
                String sql = SqlSupport.normalizeSourceQuery(request.getSourceSql());
                List<Column> query = readColumns(task, connection,
                    "SELECT * FROM (" + sql + ") LM_ARCHIVE_META WHERE 1 = 0");
                uniqueColumns(query, label + "归档查询", issues);
                if (source != null) {
                    compareQuery(source, query, databaseType, label, issues);
                }
            } catch (Exception exception) {
                rethrowFatal(task, exception);
                issues.add(label + "归档查询无法读取字段：" + SqlSupport.safeMessage(exception));
            }
        }
        if (issues.size() > before) {
            task.setStepStatus(index, STEP_FAILED);
            task.warn(label + "发现 " + (issues.size() - before) + " 个结构问题，继续检查其余归档表");
        }
        if (targetName != null && unsafeTargets.contains(tableIdentity(targetName, namespace))) {
            statements.add("-- 目标表 " + comment(quoteTable(targetName, databaseType))
                + " 的模式或物理表引用存在歧义、重复或交叉复用，必须先核实配置；下列 DDL 草稿全部禁用。");
            for (String statement : nodeStatements) {
                statements.add("-- " + statement.replace(LINE_BREAK, LINE_BREAK + "-- "));
            }
        } else {
            statements.addAll(nodeStatements);
        }
    }

    /**
     * 识别默认模式别名下的重复目标和源目标交叉复用，避免生成相互覆盖或修改源表的 DDL。
     * Params: nodes 全部配置节点；namespace 连接默认模式；issues 汇总问题。
     * Returns: 必须禁用其可执行建议的物理目标集合。
     */
    private Set<String> unsafeTargets(List<ArchiveTableRequest> nodes, String namespace, Set<String> issues) {
        Set<String> sources = new LinkedHashSet<>();
        Set<String> targets = new LinkedHashSet<>();
        Set<String> unsafe = new LinkedHashSet<>();
        boolean unresolvedAlias = false;
        for (ArchiveTableRequest node : nodes) {
            try {
                QualifiedName source = SqlSupport.parseTableName(node.getSourceTable(), "源表");
                unresolvedAlias |= namespace == null && source.getSchema() == null;
                sources.add(tableIdentity(source, namespace));
            } catch (IllegalArgumentException ignored) {
                // 节点字段预检会报告具体的表名格式错误，并继续检查另一侧。
            }
            try {
                QualifiedName target = SqlSupport.parseTableName(node.getTargetTable(), "归档表");
                unresolvedAlias |= namespace == null && target.getSchema() == null;
                String identity = tableIdentity(target, namespace);
                if (!targets.add(identity)) {
                    unsafe.add(identity);
                    issues.add("归档配置重复使用同一物理归档表：" + identity + "，其全部 DDL 草稿需人工重新核对");
                }
            } catch (IllegalArgumentException ignored) {
                // 节点字段预检会报告具体的表名格式错误。
            }
        }
        for (String target : targets) {
            if (sources.contains(target)) {
                unsafe.add(target);
                issues.add("源表与归档表存在物理对象交叉复用：" + target + "，禁止对该表生成可执行 DDL");
            }
        }
        if (unresolvedAlias) {
            unsafe.addAll(targets);
            issues.add("归档连接默认模式未知，无法排除未限定模式表名的重复或交叉复用，全部 DDL 草稿需人工核对");
        }
        return unsafe;
    }

    /**
     * 获取物理表别名核对所需的默认模式，普通元数据错误不能遮蔽后续表字段差异。
     * Params: task 当前任务；connection 当前连接；databaseType 方言；issues 汇总问题。
     * Returns: 连接默认模式，无法可靠识别时返回 null。
     */
    private String readNamespace(MigrationTask task, Connection connection, String databaseType,
        Set<String> issues) throws Exception {
        try {
            String namespace = MigrationConstants.DB_MYSQL.equals(databaseType)
                ? connection.getCatalog() : connection.getSchema();
            if (namespace == null || namespace.trim().isEmpty()) {
                DatabaseMetaData metadata = connection.getMetaData();
                namespace = metadata == null ? null : metadata.getUserName();
            }
            return namespace == null || namespace.trim().isEmpty() ? null : namespace;
        } catch (Exception exception) {
            rethrowFatal(task, exception);
            issues.add("无法识别归档连接默认模式：" + SqlSupport.safeMessage(exception));
            return null;
        }
    }

    /**
     * 对未填写模式的表补齐当前连接的默认模式。
     * Params: name 配置表名；namespace 当前默认模式。
     * Returns: 与归档执行阶段相同大小写约定的物理表标识。
     */
    private String tableIdentity(QualifiedName name, String namespace) {
        return normalize(name.getSchema() == null ? namespace : name.getSchema()) + "." + normalize(name.getTable());
    }

    /**
     * 解析单侧表名，不让另一侧配置错误遮蔽后续节点。
     * Params: value 表名；label 错误标签；issues 汇总问题。
     * Returns: 合法表名，解析失败时返回 null。
     */
    private QualifiedName parseName(String value, String label, Set<String> issues) {
        try {
            return SqlSupport.parseTableName(value, label);
        } catch (IllegalArgumentException exception) {
            issues.add(label + "：" + SqlSupport.safeMessage(exception));
            return null;
        }
    }

    /**
     * 独立读取一张物理表的完整字段元数据。
     * Params: task/connection 当前任务及连接；name 表名；databaseType 方言；label 标签；issues 差异集合。
     * Returns: 字段列表，普通元数据读取错误返回 null。
     */
    private List<Column> readTable(MigrationTask task, Connection connection, QualifiedName name,
        String databaseType, String label, Set<String> issues) throws Exception {
        if (name == null) {
            return null;
        }
        try {
            return readColumns(task, connection, "SELECT * FROM " + quoteTable(name, databaseType) + " WHERE 1 = 0");
        } catch (Exception exception) {
            rethrowFatal(task, exception);
            issues.add(label + " " + name.toSql() + " 无法读取字段：" + SqlSupport.safeMessage(exception));
            return null;
        }
    }

    /**
     * 执行零行元数据查询，登记可取消语句并仅向后台记录 SQL。
     * Params: task 当前任务；connection 当前连接；sql 零行查询。
     * Returns: JDBC 字段定义。
     */
    private List<Column> readColumns(MigrationTask task, Connection connection, String sql) throws Exception {
        checkControl(task);
        task.backendSql("SQL执行[归档字段结构汇总预检]：" + sql + "；参数=[]");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<Column> columns = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    String name = metadata.getColumnLabel(i);
                    if (name == null || name.trim().isEmpty()) {
                        name = metadata.getColumnName(i);
                    }
                    columns.add(new Column(name, metadata.getColumnType(i), metadata.getColumnTypeName(i),
                        metadata.getPrecision(i), metadata.getScale(i), metadata.isNullable(i)));
                }
                checkControl(task);
                return columns;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    /**
     * 汇总字段数量、缺失、多余及定义差异；字段数量不同仍继续逐字段比较。
     * Params: source/target 两侧字段；targetName 归档表；databaseType 方言；label 节点标签；
     * issues/statements 全任务差异与脚本。
     * Returns: 无。
     */
    private void compareTables(List<Column> source, List<Column> target, QualifiedName targetName,
        String databaseType, String label, Set<String> issues, Set<String> statements) {
        if (source.size() != target.size()) {
            issues.add(label + "源表与归档表字段数量不同：源表 " + source.size() + " 个，归档表 " + target.size() + " 个");
        }
        Map<String, Column> sourceByName = index(source);
        Map<String, Column> targetByName = index(target);
        for (Column column : sourceByName.values()) {
            Column targetColumn = targetByName.get(normalize(column.name));
            if (targetColumn == null) {
                issues.add(label + "归档表缺少字段：" + column.name);
                addColumn(statements, targetName, column, null, databaseType);
            } else if (!sameDefinition(column, targetColumn, true)) {
                boolean relaxNotNull = column.nullable == DatabaseMetaData.columnNullable
                    && targetColumn.nullable == DatabaseMetaData.columnNoNulls;
                boolean unknownTargetNullable = targetColumn.nullable == DatabaseMetaData.columnNullableUnknown;
                issues.add(label + "字段结构不同：" + column.name + "，源表=" + column.describe()
                    + "，归档表=" + targetColumn.describe()
                    + (relaxNotNull ? "；归档表非空约束严于源表，须人工确认，禁止直接放宽非空约束" : ""));
                if (relaxNotNull) {
                    statements.add("-- 归档表 " + comment(quoteTable(targetName, databaseType)) + " 的字段 "
                        + comment(column.name) + " 为 NOT NULL，源表可空；须人工核对数据和约束，"
                        + "不生成直接放宽非空约束的可执行 DDL；其他类型或长度差异也请一并人工处理。");
                } else if (unknownTargetNullable) {
                    statements.add("-- 归档表 " + comment(quoteTable(targetName, databaseType)) + " 的字段 "
                        + comment(column.name) + " 可空约束未知，请人工确认后再调整字段定义；不生成可执行 MODIFY。");
                } else {
                    addColumn(statements, targetName, column, targetColumn, databaseType);
                }
            }
        }
        for (Column column : targetByName.values()) {
            if (!sourceByName.containsKey(normalize(column.name))) {
                issues.add(label + "归档表存在源表没有的字段：" + column.name);
                statements.add("-- 归档表 " + comment(quoteTable(targetName, databaseType)) + " 多余字段 "
                    + comment(column.name) + "：请人工确认数据、约束和业务用途后处理；不自动生成 DROP COLUMN。");
            }
        }
    }

    /**
     * 汇总主表查询的字段覆盖问题，不将查询表达式当成源物理表生成改表脚本。
     * Params: source/query 源物理表及查询字段；databaseType 方言；label 节点标签；issues 汇总问题。
     * Returns: 无。
     */
    private void compareQuery(List<Column> source, List<Column> query, String databaseType,
        String label, Set<String> issues) {
        Map<String, Column> sourceByName = index(source);
        Map<String, Column> queryByName = index(query);
        if (source.size() != query.size()) {
            issues.add(label + "归档查询必须返回源表全部字段：源表 " + source.size() + " 个，查询 " + query.size() + " 个");
        }
        for (Column column : sourceByName.values()) {
            if (!queryByName.containsKey(normalize(column.name))) {
                issues.add(label + "归档查询缺少源表字段：" + column.name);
            }
        }
        for (Column column : queryByName.values()) {
            Column sourceColumn = sourceByName.get(normalize(column.name));
            if (sourceColumn == null) {
                issues.add(label + "归档查询返回了非源表字段或字段别名：" + column.name);
            } else if (!sameDefinition(column, sourceColumn, !MigrationConstants.DB_DM.equals(databaseType))) {
                issues.add(label + "归档查询字段类型与源表不同：" + column.name + "，查询="
                    + column.describe() + "，源表=" + sourceColumn.describe());
            }
        }
    }

    /**
     * 汇总全部重复字段名。
     * Params: columns 字段；label 标签；issues 问题集合。
     * Returns: 无。
     */
    private void uniqueColumns(List<Column> columns, String label, Set<String> issues) {
        Set<String> names = new LinkedHashSet<>();
        for (Column column : columns) {
            if (!names.add(normalize(column.name))) {
                issues.add(label + "存在重复字段名：" + column.name);
            }
        }
    }

    /**
     * 按忽略大小写的字段名建立索引。
     * Params: columns 元数据字段。
     * Returns: 保持字段顺序的索引。
     */
    private Map<String, Column> index(List<Column> columns) {
        Map<String, Column> result = new LinkedHashMap<>();
        for (Column column : columns) {
            result.put(normalize(column.name), column);
        }
        return result;
    }

    /**
     * 保持现有归档的严格字段定义判断。
     * Params: left/right 两侧字段；compareNullable 是否比较可空性。
     * Returns: JDBC 类型、数据库类型名、精度、小数位和可空性是否相同。
     */
    private boolean sameDefinition(Column left, Column right, boolean compareNullable) {
        return left.jdbcType == right.jdbcType && normalize(left.typeName).equals(normalize(right.typeName))
            && left.precision == right.precision && left.scale == right.scale
            && (!compareNullable || left.nullable == right.nullable);
    }

    /**
     * 将源字段定义用于新增或修改归档列，未知类型只给人工处理说明。
     * Params: statements 建议集合；table 归档表；source 源字段；target 现有目标字段，新增列时为 null；databaseType 方言。
     * Returns: 无。
     */
    private void addColumn(Set<String> statements, QualifiedName table, Column source, Column target,
        String databaseType) {
        String definition = columnDefinition(source, databaseType);
        String quotedTable = quoteTable(table, databaseType);
        if (definition == null) {
            statements.add("-- 归档表 " + comment(quotedTable) + " 的字段 " + comment(source.name)
                + " 无法仅凭 JDBC 元数据还原定义（" + comment(source.describe()) + "），请人工生成 DDL。");
            return;
        }
        boolean modify = target != null;
        if (modify && !MigrationConstants.DB_MYSQL.equals(databaseType) && source.nullable == target.nullable) {
            definition = SqlSupport.quoteIdentifier(source.name, databaseType) + " "
                + typeDefinition(source, databaseType);
        }
        String operation;
        if (MigrationConstants.DB_MYSQL.equals(databaseType)) {
            operation = (modify ? " MODIFY COLUMN " : " ADD COLUMN ") + definition;
        } else {
            operation = (modify ? " MODIFY (" : " ADD (") + definition + ")";
        }
        statements.add("-- 人工确认后执行：类型转换、缩短长度、收紧精度或 NOT NULL 可能失败或损失数据；"
            + "MySQL MODIFY 还须补齐原 DEFAULT、COMMENT、自增和字符集属性。" + LINE_BREAK
            + "ALTER TABLE " + quotedTable + operation + ";");
    }

    /**
     * 缺表时根据源物理列生成同库建表草稿，不把权限不足当成已确认不存在。
     * Params: statements 建议集合；table 归档表；source 源物理字段；databaseType 方言。
     * Returns: 无。
     */
    private void addCreateTable(Set<String> statements, QualifiedName table, List<Column> source,
        String databaseType) {
        List<String> definitions = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Column column : source) {
            String definition = columnDefinition(column, databaseType);
            if (definition == null || !names.add(normalize(column.name))) {
                statements.add("-- 未生成 " + comment(quoteTable(table, databaseType))
                    + " 的 CREATE TABLE：源字段有重复或其类型/可空定义无法安全还原，请人工处理。");
                return;
            }
            definitions.add("    " + definition);
        }
        statements.add("-- 先确认归档表确实不存在且账号具有查询权限；主键、索引、默认值、自增、字符集及注释须人工补齐。"
            + LINE_BREAK + "CREATE TABLE " + quoteTable(table, databaseType) + " (" + LINE_BREAK
            + String.join("," + LINE_BREAK, definitions) + LINE_BREAK + ");");
    }

    /**
     * 仅为可以从元数据还原的列生成定义，字段名控制字符不能进入可执行 SQL。
     * Params: column 源物理字段；databaseType 同库方言。
     * Returns: 带可空约束的字段定义，未知属性返回 null。
     */
    private String columnDefinition(Column column, String databaseType) {
        String type = typeDefinition(column, databaseType);
        if (type == null || column.name == null || column.name.matches(UNSAFE_FIELD_NAME_PATTERN)) {
            return null;
        }
        String nullable;
        if (column.nullable == DatabaseMetaData.columnNoNulls) {
            nullable = " NOT NULL";
        } else if (column.nullable == DatabaseMetaData.columnNullable) {
            nullable = " NULL";
        } else {
            return null;
        }
        return SqlSupport.quoteIdentifier(column.name, databaseType) + " " + type + nullable;
    }

    /**
     * 按同库类型名白名单保留类型，不把任意驱动文本拼入可执行 DDL。
     * Params: column 源物理字段；databaseType 同库方言。
     * Returns: 可还原的类型定义，枚举、用户类型等未知类型返回 null。
     */
    private String typeDefinition(Column column, String databaseType) {
        String type = normalize(column.typeName);
        boolean unsigned = type.endsWith(UNSIGNED_SUFFIX);
        if (unsigned) {
            type = type.substring(0, type.length() - UNSIGNED_SUFFIX.length());
        }
        String suffix = unsigned ? UNSIGNED_SUFFIX : "";
        if (LENGTH_TYPES.contains(type)) {
            return column.precision > UNKNOWN_PRECISION ? type + "(" + column.precision + ")" : null;
        }
        if (DECIMAL_TYPES.contains(type)) {
            if (column.precision <= UNKNOWN_PRECISION) {
                return NUMBER_TYPE.equals(type) ? type : null;
            }
            return type + "(" + column.precision + "," + column.scale + ")" + suffix;
        }
        if (TIME_TYPES.contains(type)) {
            int maxScale = MigrationConstants.DB_MYSQL.equals(databaseType) ? MYSQL_MAX_TIME_SCALE : MAX_TIME_SCALE;
            if (column.scale < UNKNOWN_PRECISION || column.scale > maxScale) {
                return null;
            }
            int modifierIndex = type.indexOf(TIME_ZONE_SEPARATOR);
            return modifierIndex < UNKNOWN_PRECISION ? type + "(" + column.scale + ")"
                : type.substring(0, modifierIndex) + "(" + column.scale + ")" + type.substring(modifierIndex);
        }
        if (BIT_TYPE.equals(type) && MigrationConstants.DB_MYSQL.equals(databaseType)) {
            return column.precision > UNKNOWN_PRECISION ? type + "(" + column.precision + ")" : null;
        }
        return SIMPLE_TYPES.contains(type) ? type + suffix : null;
    }

    /**
     * 汇总脚本及完整问题说明；所有动态注释都移除换行和控制字符。
     * Params: databaseType 方言；issues 全部问题；statements 全部脚本建议。
     * Returns: 人工审核、复制及下载使用的 SQL 文本。
     */
    private String buildScript(String databaseType, Set<String> issues, Set<String> statements) {
        StringBuilder script = new StringBuilder("-- Light DB Migration 归档表结构同步建议脚本\n")
            .append("-- 数据库类型：").append(databaseType).append(LINE_BREAK)
            .append("-- 以源物理表字段为准；工具不会自动执行，归档写入和删除尚未开始。\n")
            .append("-- 请先备份并核对默认值、自增、主键、索引、字符集、排序规则、长度语义和注释。\n")
            .append("-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。\n\n")
            .append("-- 本次校验问题\n");
        int number = 1;
        for (String issue : issues) {
            script.append("-- ").append(number++).append(". ").append(comment(issue)).append(LINE_BREAK);
        }
        script.append("\n-- 建议 DDL\n");
        if (statements.isEmpty()) {
            script.append("-- 当前问题无法转换为字段 DDL，请按问题说明人工调整查询或配置。\n");
        } else {
            for (String statement : statements) {
                script.append(statement).append(LINE_BREAK).append(LINE_BREAK);
            }
        }
        return script.toString();
    }

    /**
     * 对配置表名使用归档现有的大小写约定和方言引用。
     * Params: name 配置表名；databaseType 方言。
     * Returns: 安全引用的物理表名。
     */
    private String quoteTable(QualifiedName name, String databaseType) {
        boolean uppercase = !MigrationConstants.DB_MYSQL.equals(databaseType);
        String table = uppercase ? normalize(name.getTable()) : name.getTable();
        String quoted = SqlSupport.quoteIdentifier(table, databaseType);
        if (name.getSchema() == null) {
            return quoted;
        }
        String schema = uppercase ? normalize(name.getSchema()) : name.getSchema();
        return SqlSupport.quoteIdentifier(schema, databaseType) + "." + quoted;
    }

    /**
     * 统一忽略大小写的比较值。
     * Params: value 字段名或类型名。
     * Returns: 大写比较值。
     */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 清除脚本注释中的所有换行及控制字符，避免元数据或错误消息注入 SQL。
     * Params: value 动态注释。
     * Returns: 单行安全注释内容。
     */
    private String comment(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
    }

    /**
     * 在每个查询前后响应终止、中断及暂停。
     * Params: task 当前任务。
     * Returns: 无。
     */
    private void checkControl(MigrationTask task) throws Exception {
        task.stopCheckpoint();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("归档结构预检线程被中断");
        }
        task.checkpoint();
    }

    /**
     * 普通 SQL 或字段问题允许继续，连接故障和任务控制信号立即退出。
     * Params: task 当前任务；exception 待分类异常。
     * Returns: 无；致命异常原样抛出。
     */
    private void rethrowFatal(MigrationTask task, Exception exception) throws Exception {
        if (exception instanceof StopRequestedException || exception instanceof InterruptedException) {
            throw exception;
        }
        checkControl(task);
        for (Throwable current : exceptionChain(exception)) {
            if (current instanceof SQLNonTransientConnectionException || current instanceof SQLRecoverableException
                || current instanceof SQLTransientConnectionException) {
                throw exception;
            }
            if (current instanceof SQLException) {
                SQLException sql = (SQLException) current;
                String state = sql.getSQLState();
                if (state != null && state.startsWith(CONNECTION_SQL_STATE)) {
                    throw exception;
                }
            }
        }
    }

    /**
     * 识别缺表或表不可见错误，生成的建表建议仍要求先人工核实权限。
     * Params: exception 元数据异常；databaseType 方言。
     * Returns: 是否为该方言的缺表错误。
     */
    private boolean isMissingTable(Exception exception, String databaseType) {
        for (Throwable current : exceptionChain(exception)) {
            if (current instanceof SQLException) {
                SQLException sql = (SQLException) current;
                boolean mysqlMissing = MigrationConstants.DB_MYSQL.equals(databaseType)
                    && (sql.getErrorCode() == MYSQL_MISSING_TABLE || MISSING_TABLE_SQL_STATE.equals(sql.getSQLState()));
                boolean oracleMissing = MigrationConstants.DB_ORACLE.equals(databaseType)
                    && sql.getErrorCode() == ORACLE_MISSING_TABLE;
                boolean dmMissing = MigrationConstants.DB_DM.equals(databaseType)
                    && sql.getErrorCode() == DM_MISSING_TABLE;
                if (mysqlMissing || oracleMissing || dmMissing) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 遍历原因和 JDBC 后继异常，防止嵌套连接错误被当成普通结构问题吞掉。
     * Params: exception 起始异常。
     * Returns: 无重复且不会因循环引用卡住的异常链。
     */
    private List<Throwable> exceptionChain(Throwable exception) {
        List<Throwable> pending = new ArrayList<>();
        List<Throwable> result = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        pending.add(exception);
        for (int i = 0; i < pending.size(); i++) {
            Throwable current = pending.get(i);
            if (current == null || !seen.add(current)) {
                continue;
            }
            result.add(current);
            pending.add(current.getCause());
            if (current instanceof SQLException) {
                pending.add(((SQLException) current).getNextException());
            }
        }
        return result;
    }

    /**
     * 归档结构预检所需的物理列和查询列定义。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Column {
        /** 字段名。 */
        private final String name;
        /** JDBC 类型。 */
        private final int jdbcType;
        /** 同库类型名。 */
        private final String typeName;
        /** 字段长度或精度。 */
        private final int precision;
        /** 小数位或小数秒位数。 */
        private final int scale;
        /** JDBC 可空标志。 */
        private final int nullable;

        /**
         * Params: name 字段名；jdbcType/typeName 类型；precision/scale 精度；nullable 可空标志。
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

        /**
         * Params: 无。
         * Returns: 日志和脚本说明使用的字段定义。
         */
        private String describe() {
            return typeName + "(" + precision + "," + scale + "),jdbcType=" + jdbcType + ",nullable=" + nullable;
        }
    }
}
