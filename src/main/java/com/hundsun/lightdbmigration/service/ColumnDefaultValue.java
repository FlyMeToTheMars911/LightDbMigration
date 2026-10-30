package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 保存物理字段的默认值状态，只有可确认的默认表达式才能进入结构同步建议。
 * Author: Codex
 * date: 2026-10-30
 */
final class ColumnDefaultValue {

    /** 默认子句与明确无默认值的不可变实例。 */
    private static final String DEFAULT_PREFIX = " DEFAULT ";
    /** 无默认值与未取得元数据必须分别记录。 */
    private static final ColumnDefaultValue NONE = new ColumnDefaultValue(Kind.NONE, null);
    /** 未取得目录值，不能当作无默认值。 */
    private static final ColumnDefaultValue UNKNOWN = new ColumnDefaultValue(Kind.UNKNOWN, null);
    /** 普通数值字面量，不允许夹带其他 SQL 标记。 */
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    /** 三种方言均明确支持的当前时间戳；跨库只转换公共精度范围。 */
    private static final Pattern CURRENT_TIMESTAMP = Pattern.compile("(?i)CURRENT_TIMESTAMP(?:\\s*\\(\\s*([0-6]?)\\s*\\))?");
    /** MySQL 位字段的目录默认值，可无损转换为十进制数值字面量。 */
    private static final Pattern BIT_LITERAL = Pattern.compile("(?i)b'([01]+)'");
    /** MySQL 数值类型目录基名。 */
    private static final Set<String> MYSQL_NUMERIC_TYPES = new HashSet<>(Arrays.asList(
        "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT", "DECIMAL", "DEC", "NUMERIC",
        "FIXED", "FLOAT", "DOUBLE", "REAL", "BIT", "BOOL", "BOOLEAN"));
    /** MySQL 时间字段的默认 CURRENT_TIMESTAMP 在旧版本可能没有 DEFAULT_GENERATED 标记。 */
    private static final Set<String> MYSQL_TEMPORAL_TYPES = new HashSet<>(Arrays.asList("TIMESTAMP", "DATETIME"));
    /** 二进制默认值的目录文本可能是十六进制展示，不能重新引用为普通字符串。 */
    private static final Set<String> MYSQL_BINARY_TYPES = new HashSet<>(Arrays.asList(
        "BINARY", "VARBINARY", "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB"));
    /** 原生 MySQL 即使是常量也要求这些类型使用默认表达式括号。 */
    private static final Set<String> MYSQL_EXPRESSION_DEFAULT_TYPES = new HashSet<>(Arrays.asList(
        "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "JSON"));
    /** MySQL 目录表达式标志。 */
    private static final String DEFAULT_GENERATED = "DEFAULT_GENERATED";
    /** SQL 空默认值、标识符与语义签名分隔符。 */
    private static final String NULL_TOKEN = "NULL";
    /** 签名的类型与内容分隔符，避免字面量与表达式相混。 */
    private static final String SIGNATURE_SEPARATOR = ":";
    /** SQL 词法字符，集中定义以避免条件中的魔法值。 */
    private static final char SINGLE_QUOTE = '\'';
    private static final char DOUBLE_QUOTE = '"';
    private static final char BACKTICK = '`';
    private static final char BACKSLASH = '\\';
    private static final char OPEN_PARENTHESIS = '(';
    private static final char CLOSE_PARENTHESIS = ')';
    private static final char SEMICOLON = ';';
    private static final char DASH = '-';
    private static final char SLASH = '/';
    private static final char ASTERISK = '*';
    private static final char HASH = '#';
    private static final char COMMA = ',';
    private static final char NATIONAL_PREFIX_UPPER = 'N';
    private static final char NATIONAL_PREFIX_LOWER = 'n';
    private static final char NO_QUOTE = '\0';
    private static final int ZERO = 0;
    private static final int ONE = 1;
    private static final int TWO = 2;
    /** 二进制字面量的进制。 */
    private static final int BINARY_RADIX = 2;

    /** 默认值语义类别。 */
    private final Kind kind;
    /** 字符串内容或目录原始表达式；字符串内容保留空格和大小写。 */
    private final String value;

    /** Params: kind 状态；value 字面量或原表达式。 Returns: 不可变默认值。 */
    private ColumnDefaultValue(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    /** Params: 无。 Returns: 目录明确没有默认值。 */
    static ColumnDefaultValue none() {
        return NONE;
    }

    /** Params: 无。 Returns: 尚未取得默认值元数据。 */
    static ColumnDefaultValue unknown() {
        return UNKNOWN;
    }

    /**
     * Oracle/达梦 JDBC COLUMN_DEF 已是 SQL 定义，外部空白可去除，引号内部内容必须保留。
     * Params: expression 驱动返回的 COLUMN_DEF；SQL NULL 表示明确没有默认值。
     * Returns: 字面量或待按源方言审查的目录表达式。
     */
    static ColumnDefaultValue jdbc(String expression) {
        if (expression == null) {
            return none();
        }
        String sql = expression.trim();
        if (sql.isEmpty()) {
            return unknown();
        }
        String unwrapped = unwrap(sql);
        String literal = stringLiteral(unwrapped);
        if (literal != null) {
            if (unwrapped.charAt(ZERO) == NATIONAL_PREFIX_UPPER
                || unwrapped.charAt(ZERO) == NATIONAL_PREFIX_LOWER) {
                // N 前缀指定国家字符集；不能退化成普通字符串后再拼回 SQL。
                return new ColumnDefaultValue(Kind.EXPRESSION, sql);
            }
            return new ColumnDefaultValue(Kind.STRING, literal);
        }
        if (NUMBER.matcher(unwrapped).matches()) {
            return new ColumnDefaultValue(Kind.NUMBER, unwrapped);
        }
        if (NULL_TOKEN.equalsIgnoreCase(unwrapped)) {
            return new ColumnDefaultValue(Kind.NULL, NULL_TOKEN);
        }
        return new ColumnDefaultValue(Kind.EXPRESSION, sql);
    }

    /**
     * MySQL COLUMN_DEFAULT 的字符串没有 SQL 引号；表达式由 EXTRA 的 DEFAULT_GENERATED 区分。
     * Params: value 目录默认值；typeName DATA_TYPE；extra EXTRA，SQL NULL 默认值不转换为空串。
     * Returns: 保留空串、空格、单引号的值；不能确认的数值定义保持未知。
     */
    static ColumnDefaultValue mysql(String value, String typeName, String extra) {
        return mysql(value, typeName, extra, false);
    }

    /**
     * Params: value/typeName/extra 目录定义；quotedDefaults MariaDB 10.2.7+ 的已引用目录格式。
     * Returns: 按已确认服务端格式解析，不能用默认值是否含引号来猜测数据库产品。
     */
    static ColumnDefaultValue mysql(String value, String typeName, String extra, boolean quotedDefaults) {
        if (value == null) {
            return none();
        }
        String type = typeName == null ? "" : typeName.trim().toUpperCase(Locale.ROOT);
        boolean generated = extra != null && extra.toUpperCase(Locale.ROOT).contains(DEFAULT_GENERATED);
        if (quotedDefaults && !MYSQL_BINARY_TYPES.contains(type)) {
            ColumnDefaultValue literal = jdbc(value);
            return MYSQL_EXPRESSION_DEFAULT_TYPES.contains(type) && literal.kind != Kind.NULL
                ? new ColumnDefaultValue(Kind.EXPRESSION, value.trim()) : literal;
        }
        if (generated) {
            return new ColumnDefaultValue(Kind.EXPRESSION, value.trim());
        }
        if (MYSQL_TEMPORAL_TYPES.contains(type) && CURRENT_TIMESTAMP.matcher(value.trim()).matches()) {
            return jdbc(value);
        }
        if (MYSQL_BINARY_TYPES.contains(type)) {
            return new ColumnDefaultValue(Kind.UNKNOWN,
                "MySQL 二进制默认值的目录文本不能确认原始字节，请用 SHOW CREATE TABLE 核对并人工处理");
        }
        if (MYSQL_NUMERIC_TYPES.contains(type)) {
            Matcher bit = BIT_LITERAL.matcher(value.trim());
            if (bit.matches()) {
                return new ColumnDefaultValue(Kind.NUMBER, new BigInteger(bit.group(ONE), BINARY_RADIX).toString());
            }
            return NUMBER.matcher(value.trim()).matches()
                ? new ColumnDefaultValue(Kind.NUMBER, value.trim())
                : new ColumnDefaultValue(Kind.EXPRESSION, value.trim());
        }
        return new ColumnDefaultValue(Kind.STRING, value);
    }

    /**
     * 将源默认值带入建议 DDL；跨库只使用字面量和已确认的公共时间戳函数。
     * Params: sourceType 源数据库方言；targetType 目标数据库方言。
     * Returns: 含前导空格的 DEFAULT 子句；明确无默认值返回空串；未知或不能安全转换返回 null。
     */
    String clause(String sourceType, String targetType) {
        if (kind == Kind.NONE) {
            return "";
        }
        if (kind == Kind.UNKNOWN || sourceType == null || targetType == null) {
            return null;
        }
        if (kind == Kind.STRING) {
            if (value.isEmpty() && !sourceType.equals(targetType)) {
                return null;
            }
            // sql_mode 与脚本分句器对反斜杠的解释不同，此处不猜测其转义规则。
            if (value.indexOf(BACKSLASH) >= ZERO) {
                return null;
            }
            return DEFAULT_PREFIX + quoteString(value);
        }
        if (kind == Kind.NUMBER || kind == Kind.NULL) {
            return DEFAULT_PREFIX + value;
        }
        if (!safeExpression(value)) {
            return null;
        }
        String expression = unwrap(value);
        Matcher timestamp = CURRENT_TIMESTAMP.matcher(expression);
        if (timestamp.matches()) {
            String scale = timestamp.group(ONE);
            return DEFAULT_PREFIX + "CURRENT_TIMESTAMP" + (scale == null || scale.isEmpty() ? "" : "(" + scale + ")");
        }
        if (!sourceType.equals(targetType)) {
            ColumnDefaultValue literal = jdbc(value);
            if (literal.kind == Kind.STRING || literal.kind == Kind.NUMBER || literal.kind == Kind.NULL) {
                return literal.clause(sourceType, targetType);
            }
            return null;
        }
        if (MigrationConstants.DB_MYSQL.equals(targetType)) {
            return DEFAULT_PREFIX + OPEN_PARENTHESIS + value + CLOSE_PARENTHESIS;
        }
        return DEFAULT_PREFIX + value;
    }

    /** Params: 无。 Returns: 日志和人工建议使用的默认值及不能自动生成的原因。 */
    String describe() {
        if (kind == Kind.NONE) {
            return "无默认值";
        }
        if (kind == Kind.UNKNOWN) {
            return value == null ? "默认值元数据未知" : value;
        }
        String text = kind == Kind.STRING ? quoteString(value) : value;
        return text + (kind == Kind.STRING && value.isEmpty()
            ? "（空串在不同数据库的 NULL 语义可能不同，跨库须人工处理）" : "")
            + (value.indexOf(BACKSLASH) >= ZERO
            ? "（包含反斜杠；须人工确认数据库及脚本分句器的转义规则）" : "")
            + (kind == Kind.EXPRESSION && !safeExpression(value) ? "（目录表达式边界不安全，须人工处理）" : "");
    }

    /**
     * 字符串以真实内容去重；数值移除不影响语义的小数尾零，表达式只规范化引号外的大小写和空白。
     * Params: 无。
     * Returns: 可用于建议冲突识别的稳定签名，不改变字面量中的空格和大小写。
     */
    String signature() {
        String canonical = value;
        if (kind == Kind.NUMBER) {
            try {
                canonical = new BigDecimal(value).stripTrailingZeros().toString();
            } catch (NumberFormatException exception) {
                canonical = value;
            }
        } else if (kind == Kind.EXPRESSION) {
            ColumnDefaultValue literal = jdbc(value);
            if (literal.kind == Kind.STRING || literal.kind == Kind.NUMBER || literal.kind == Kind.NULL) {
                return literal.signature();
            }
            String expression = unwrap(value);
            Matcher timestamp = CURRENT_TIMESTAMP.matcher(expression);
            if (timestamp.matches()) {
                String scale = timestamp.group(ONE);
                canonical = "CURRENT_TIMESTAMP" + (scale == null || scale.isEmpty() ? "" : "(" + scale + ")");
            } else {
                canonical = normalizeExpression(expression);
            }
        }
        return kind.name() + SIGNATURE_SEPARATOR + (canonical == null ? "" : canonical);
    }

    /** Params: value 字符串内容。 Returns: 按 SQL 标准双写单引号且保留内部空格的字符串字面量。 */
    private static String quoteString(String value) {
        return SINGLE_QUOTE + value.replace("'", "''") + SINGLE_QUOTE;
    }

    /** Params: sql 候选字面量。 Returns: 完整单个 SQL 字符串的内容，非字面量时返回 null。 */
    private static String stringLiteral(String sql) {
        int start = ZERO;
        if (sql.length() > ONE && (sql.charAt(ZERO) == NATIONAL_PREFIX_UPPER || sql.charAt(ZERO) == NATIONAL_PREFIX_LOWER)) {
            start = ONE;
        }
        if (sql.length() < start + TWO || sql.charAt(start) != SINGLE_QUOTE) {
            return null;
        }
        StringBuilder result = new StringBuilder();
        for (int i = start + ONE; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == SINGLE_QUOTE) {
                if (i == sql.length() - ONE) {
                    return result.toString();
                }
                if (sql.charAt(i + ONE) != SINGLE_QUOTE) {
                    return null;
                }
                i++;
            }
            result.append(current);
        }
        return null;
    }

    /** Params: sql 目录表达式。 Returns: 去除确实包围整个表达式的冗余括号后的内容。 */
    private static String unwrap(String sql) {
        String result = sql.trim();
        while (result.length() >= TWO && result.charAt(ZERO) == OPEN_PARENTHESIS
            && outerParentheses(result)) {
            result = result.substring(ONE, result.length() - ONE).trim();
        }
        return result;
    }

    /** Params: sql 带左括号的表达式。 Returns: 首个左括号是否在最后一个字符处闭合。 */
    private static boolean outerParentheses(String sql) {
        int depth = ZERO;
        char quote = NO_QUOTE;
        for (int i = ZERO; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (quote != NO_QUOTE) {
                if (current == quote) {
                    if (i + ONE < sql.length() && sql.charAt(i + ONE) == quote) {
                        i++;
                    } else {
                        quote = NO_QUOTE;
                    }
                }
            } else if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                quote = current;
            } else if (current == OPEN_PARENTHESIS) {
                depth++;
            } else if (current == CLOSE_PARENTHESIS && --depth == ZERO) {
                return i == sql.length() - ONE;
            }
        }
        return false;
    }

    /**
     * 目录内容仍须通过单个表达式边界检查，不能借默认值引入其他 SQL 语句、注释或字段定义。
     * Params: expression 目录表达式。
     * Returns: 引号和括号均闭合，且无语句/注释/顶层逗号边界。
     */
    private static boolean safeExpression(String expression) {
        int depth = ZERO;
        char quote = NO_QUOTE;
        for (int i = ZERO; i < expression.length(); i++) {
            char current = expression.charAt(i);
            char next = i + ONE < expression.length() ? expression.charAt(i + ONE) : NO_QUOTE;
            if (current == BACKSLASH || Character.isISOControl(current)) {
                return false;
            }
            if (quote != NO_QUOTE) {
                if (current == quote) {
                    if (next == quote) {
                        i++;
                    } else {
                        quote = NO_QUOTE;
                    }
                }
                continue;
            }
            if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                quote = current;
            } else if (current == SEMICOLON || current == HASH || current == DASH && next == DASH
                || current == SLASH && next == ASTERISK || current == ASTERISK && next == SLASH
                || current == COMMA && depth == ZERO) {
                return false;
            } else if (current == OPEN_PARENTHESIS) {
                depth++;
            } else if (current == CLOSE_PARENTHESIS && --depth < ZERO) {
                return false;
            }
        }
        return quote == NO_QUOTE && depth == ZERO && !expression.trim().isEmpty();
    }

    /** Params: expression 表达式。 Returns: 保留引号内容、折叠引号外空白和大小写的比较值。 */
    private static String normalizeExpression(String expression) {
        StringBuilder result = new StringBuilder();
        char quote = NO_QUOTE;
        boolean space = false;
        for (int i = ZERO; i < expression.length(); i++) {
            char current = expression.charAt(i);
            if (quote == NO_QUOTE && Character.isWhitespace(current)) {
                space = result.length() > ZERO;
                continue;
            }
            if (space) {
                result.append(' ');
                space = false;
            }
            if (quote != NO_QUOTE) {
                result.append(current);
                if (current == quote) {
                    if (i + ONE < expression.length() && expression.charAt(i + ONE) == quote) {
                        result.append(expression.charAt(++i));
                    } else {
                        quote = NO_QUOTE;
                    }
                }
            } else if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                quote = current;
                result.append(current);
            } else {
                result.append(Character.toUpperCase(current));
            }
        }
        return result.toString();
    }

    /**
     * 默认值状态与字面量类型。
     * Author: Codex
     * date: 2026-10-30
     */
    private enum Kind {
        NONE, UNKNOWN, STRING, NUMBER, NULL, EXPRESSION
    }
}
