package com.hundsun.lightdbmigration.support;

import java.sql.SQLException;
import java.util.Locale;
import java.util.regex.Pattern;

public final class SqlSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9_$#]+");
    private static final String SELECT_PREFIX = "SELECT";
    private static final String WITH_PREFIX = "WITH";

    private SqlSupport() {
    }

    public static String normalizeSourceQuery(String sql) {
        String result = sql == null ? "" : sql.trim();
        while (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("源库查询 SQL 不能为空");
        }
        String upper = result.toUpperCase(Locale.ROOT);
        if (!upper.startsWith(SELECT_PREFIX) && !upper.startsWith(WITH_PREFIX)) {
            throw new IllegalArgumentException("源库 SQL 只允许 SELECT 或 WITH 查询");
        }
        if (result.indexOf(';') >= 0) {
            throw new IllegalArgumentException("每个迁移步骤只允许一条查询 SQL，请拆分为多个步骤");
        }
        if (result.indexOf('?') >= 0) {
            throw new IllegalArgumentException("第一版暂不支持 SQL 占位参数，请在查询 SQL 中写明筛选值");
        }
        return result;
    }

    public static QualifiedName parseTargetTable(String targetTable) {
        String value = targetTable == null ? "" : targetTable.trim();
        String[] parts = value.split("\\.", -1);
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("目标表只支持 表名 或 模式名.表名 格式");
        }
        for (String part : parts) {
            if (!SAFE_IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("目标表名只能包含字母、数字、下划线、$ 或 #");
            }
        }
        return parts.length == 1 ? new QualifiedName(null, parts[0]) : new QualifiedName(parts[0], parts[1]);
    }

    public static String quoteIdentifier(String identifier, String databaseType) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("字段名不能为空");
        }
        if (MigrationConstants.DB_MYSQL.equals(databaseType)) {
            return "`" + identifier.replace("`", "``") + "`";
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String safeMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = current.getClass().getSimpleName();
        }
        message = message.replaceAll("(?i)(password|pwd)\\s*[=:]\\s*[^,;\\s]+", "$1=******");
        message = message.replaceAll("(?i)(jdbc:[^?\\s]+)\\?[^\\s]+", "$1?******");
        if (message.length() > 1000) {
            return message.substring(0, 1000) + "...";
        }
        return message;
    }

    public static boolean isDuplicateKey(SQLException exception, String databaseType) {
        SQLException current = exception;
        while (current != null) {
            if (MigrationConstants.DB_ORACLE.equals(databaseType) && current.getErrorCode() == 1) {
                return true;
            }
            String sqlState = current.getSQLState();
            if (MigrationConstants.DB_MYSQL.equals(databaseType) && "23000".equals(sqlState)
                && current.getErrorCode() == 1062) {
                return true;
            }
            current = current.getNextException();
        }
        return false;
    }

    public static final class QualifiedName {

        private final String schema;
        private final String table;

        private QualifiedName(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        public String getSchema() {
            return schema;
        }

        public String getTable() {
            return table;
        }

        public String toSql() {
            return schema == null ? table : schema + "." + table;
        }
    }
}
