package com.hundsun.lightdbmigration.support;

import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对服务端结构建议做摘要及分句，只允许受支持的建表、增列和改列建议。
 * Author: Codex
 * date: 2026-10-26
 */
public final class StructureSyncScript {

    private static final char SINGLE_QUOTE = '\'';
    private static final char DOUBLE_QUOTE = '"';
    private static final char BACKTICK = '`';
    private static final char SEMICOLON = ';';
    private static final char DASH = '-';
    private static final char SLASH = '/';
    private static final char ASTERISK = '*';
    private static final char LINE_FEED = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char BACKSLASH = '\\';
    private static final char NO_QUOTE = '\0';
    private static final int HEX_RADIX = 16;
    private static final int HEX_MASK = 0x0f;
    private static final int HEX_SHIFT = 4;
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String CREATE_OPERATION = "CREATE";
    private static final String ALTER_OPERATION = "ALTER";
    private static final String OPEN_PARENTHESIS = "(";
    private static final String SIMPLE_NAME = "(?:[A-Za-z0-9_$#]+|`[A-Za-z0-9_$#]+`|\"[A-Za-z0-9_$#]+\")";
    private static final Pattern DDL_PREFIX = Pattern.compile("(?is)^\\s*(CREATE|ALTER)\\s+TABLE\\s+("
        + SIMPLE_NAME + "(?:\\s*\\.\\s*" + SIMPLE_NAME + ")?)\\s+(.*)$");
    private static final Pattern ALTER_COLUMNS = Pattern.compile("(?is)^(?:ADD|MODIFY)\\s+(?:COLUMN\\s+)?(.*)$");
    private static final Pattern UNSUPPORTED_ACTION = Pattern.compile(
        "(?i)\\b(?:DROP|TRUNCATE|DELETE|UPDATE|INSERT|MERGE|GRANT|REVOKE|RENAME|SELECT|CALL|EXECUTE|BEGIN|COMMIT|ROLLBACK)\\b");
    private static final Pattern NON_COLUMN_ADD = Pattern.compile(
        "(?is)^(?:CONSTRAINT|INDEX|KEY|UNIQUE|PRIMARY|FOREIGN|CHECK)\\b.*");

    private StructureSyncScript() {
    }

    /** Params: script 服务端生成的脚本。 Returns: SHA-256 十六进制摘要，空脚本返回空字符串。 */
    public static String digest(String script) {
        if (script == null || script.trim().isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(script.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(Character.forDigit((value >>> HEX_SHIFT) & HEX_MASK, HEX_RADIX));
                result.append(Character.forDigit(value & HEX_MASK, HEX_RADIX));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持结构脚本摘要", exception);
        }
    }

    /**
     * 忽略注释并识别引号内分号，不把注释中的候选建议作为可执行 SQL。
     * Params: script 服务端生成的脚本。
     * Returns: 按原顺序排列且已通过操作类型校验的 DDL。
     */
    public static List<String> parseStatements(String script) {
        List<String> statements = new ArrayList<>();
        if (script == null || script.trim().isEmpty()) {
            return statements;
        }
        StringBuilder statement = new StringBuilder();
        char quote = NO_QUOTE;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char current = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : NO_QUOTE;
            if (lineComment) {
                if (current == LINE_FEED || current == CARRIAGE_RETURN) {
                    lineComment = false;
                    statement.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == ASTERISK && next == SLASH) {
                    blockComment = false;
                    statement.append(' ');
                    i++;
                }
                continue;
            }
            if (quote != NO_QUOTE) {
                statement.append(current);
                if (current == BACKSLASH && quote == SINGLE_QUOTE && next != NO_QUOTE) {
                    statement.append(next);
                    i++;
                } else if (current == quote) {
                    if (next == quote) {
                        statement.append(next);
                        i++;
                    } else {
                        quote = NO_QUOTE;
                    }
                }
                continue;
            }
            if (current == DASH && next == DASH) {
                lineComment = true;
                i++;
            } else if (current == SLASH && next == ASTERISK) {
                blockComment = true;
                i++;
            } else if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                quote = current;
                statement.append(current);
            } else if (current == SEMICOLON) {
                addStatement(statements, statement);
            } else {
                statement.append(current);
            }
        }
        if (quote != NO_QUOTE || blockComment) {
            throw new IllegalArgumentException("结构建议脚本存在未闭合的引号或注释，不能执行");
        }
        addStatement(statements, statement);
        return statements;
    }

    /** Params: statement 已分离的 DDL。 Returns: 经校验的结构建议目标表。 */
    public static QualifiedName targetTable(String statement) {
        Matcher prefix = DDL_PREFIX.matcher(statement);
        if (!prefix.matches()) {
            throw new IllegalArgumentException("结构同步只允许当前建议中的 CREATE TABLE、ALTER TABLE ADD/MODIFY");
        }
        String operation = prefix.group(1).toUpperCase(Locale.ROOT);
        String body = prefix.group(3).trim();
        if (CREATE_OPERATION.equals(operation)) {
            if (!body.startsWith(OPEN_PARENTHESIS)) {
                throw new IllegalArgumentException("结构建议仅允许按列定义创建目标表");
            }
        } else if (ALTER_OPERATION.equals(operation)) {
            Matcher columns = ALTER_COLUMNS.matcher(body);
            if (!columns.matches() || NON_COLUMN_ADD.matcher(columns.group(1)).matches()) {
                throw new IllegalArgumentException("结构建议仅允许增加或修改目标字段");
            }
        }
        if (UNSUPPORTED_ACTION.matcher(unquotedTokens(body)).find()) {
            throw new IllegalArgumentException("结构建议包含不支持的操作，请先人工处理");
        }
        String name = prefix.group(2).replace("`", "").replace("\"", "").replaceAll("\\s+", "");
        return SqlSupport.parseTargetTable(name);
    }

    /** Params: statements 结果集合；statement 当前分句。 Returns: 无；完整校验后加入集合。 */
    private static void addStatement(List<String> statements, StringBuilder statement) {
        String sql = statement.toString().trim();
        statement.setLength(0);
        if (!sql.isEmpty()) {
            targetTable(sql);
            statements.add(sql);
        }
    }

    /** Params: sql 语句正文。 Returns: 去除引号内容后的操作标记，避免误判字段名或字符串值。 */
    private static String unquotedTokens(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        char quote = NO_QUOTE;
        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : NO_QUOTE;
            if (quote != NO_QUOTE) {
                if (current == BACKSLASH && quote == SINGLE_QUOTE && next != NO_QUOTE) {
                    i++;
                } else if (current == quote) {
                    if (next == quote) {
                        i++;
                    } else {
                        quote = NO_QUOTE;
                    }
                }
                result.append(' ');
            } else if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                quote = current;
                result.append(' ');
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
