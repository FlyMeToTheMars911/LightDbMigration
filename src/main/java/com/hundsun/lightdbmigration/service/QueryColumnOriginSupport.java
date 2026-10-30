package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 仅为普通单表 SELECT 补充直列物理来源；不猜测复杂 SQL 或大小写敏感标识符。
 * Author: Codex
 * date: 2026-10-30
 */
final class QueryColumnOriginSupport {

    /** 普通标识符遵循项目现有 SqlSupport 范围，不接受 SQL 片段。 */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$#][A-Za-z0-9_$#]*");
    /** 双引号中仅大写普通标识符与现有未引用名称规则兼容。 */
    private static final Pattern UPPER_IDENTIFIER = Pattern.compile("[A-Z_$#][A-Z0-9_$#]*");
    /** 普通数值投影包含科学计数法，不把数值尾部误认为输出别名。 */
    private static final Pattern NUMBER_LITERAL = Pattern.compile("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    /** 一旦出现便不能再按普通单表查询推断的顶层操作。 */
    private static final Set<String> UNSUPPORTED = set("JOIN", "UNION", "INTERSECT", "EXCEPT", "MINUS",
        "WITH", "APPLY", "PIVOT", "UNPIVOT", "MODEL", "MATCH_RECOGNIZE");
    /** 这些语法不进入物理表名和别名解析。 */
    private static final Set<String> RESERVED = set("SELECT", "FROM", "AS", "WHERE", "ORDER", "BY",
        "GROUP", "HAVING", "CONNECT", "START", "FOR", "LIMIT", "OFFSET", "FETCH", "FIRST", "ROWS",
        "ONLY", "WINDOW", "QUALIFY", "DISTINCT", "ALL", "TOP", "LEFT", "RIGHT", "FULL", "INNER",
        "OUTER", "CROSS", "NATURAL", "ON", "USING", "CASE", "WHEN", "THEN", "ELSE", "END",
        "NULL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
        "TRUE", "FALSE");
    /** 本次解析允许单表读取中出现的尾部子句。 */
    private static final Set<String> TAIL = set("WHERE", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR");
    /** 不在本次普通单表解析范围的尾部子句。 */
    private static final Set<String> COMPLEX_TAIL = set("GROUP", "HAVING", "CONNECT", "START", "WINDOW", "QUALIFY");
    /** 不依赖物理列的明确表达式关键字。 */
    private static final Set<String> VALUE_KEYWORDS = set("NULL", "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE",
        "CURRENT_TIME", "CURRENT_TIMESTAMP", "TRUE", "FALSE");
    /** 明确改变投影值的运算符。 */
    private static final Set<String> OPERATORS = set("+", "-", "*", "/", "%", "|", "&", "^", "=", "<", ">");
    /** 未知来源和已确认计算表达式不能相混。 */
    private static final Origin UNKNOWN_ORIGIN = new Origin(null, null, false);
    /** 计算结果没有源物理列默认值。 */
    private static final Origin EXPRESSION_ORIGIN = new Origin(null, null, true);
    /** 无法确认的查询不对任何投影猜测来源。 */
    private static final QueryColumnOriginSupport UNKNOWN = new QueryColumnOriginSupport(null,
        Collections.<Origin>emptyList(), false);
    /** 词法字符统一声明，便于检查 SQL 边界。 */
    private static final char SINGLE_QUOTE = '\'';
    private static final char DOUBLE_QUOTE = '"';
    private static final char BACKTICK = '`';
    private static final char BACKSLASH = '\\';
    private static final char DASH = '-';
    private static final char SLASH = '/';
    private static final char ASTERISK = '*';
    private static final char OPEN = '(';
    private static final char CLOSE = ')';
    private static final char LINE_FEED = '\n';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char DOT = '.';
    private static final char UNDERSCORE = '_';
    private static final char DOLLAR = '$';
    private static final char HASH = '#';
    private static final char NONE = '\0';
    private static final int ZERO = 0;
    private static final int ONE = 1;
    private static final int TWO = 2;
    private static final int THREE = 3;
    private static final int FOUR = 4;
    private static final int FIVE = 5;
    private static final String DOT_TOKEN = ".";
    private static final String STAR_TOKEN = "*";
    private static final String COMMA_TOKEN = ",";
    private static final String SEMICOLON_TOKEN = ";";
    private static final String OPEN_TOKEN = "(";
    private static final String CLOSE_TOKEN = ")";
    /** 参与控制分支的 SQL 标记集中声明，避免散落字符串判定。 */
    private static final String SELECT_TOKEN = "SELECT";
    private static final String FROM_TOKEN = "FROM";
    private static final String DISTINCT_TOKEN = "DISTINCT";
    private static final String ALL_TOKEN = "ALL";
    private static final String AS_TOKEN = "AS";
    private static final String ORDER_TOKEN = "ORDER";
    private static final String BY_TOKEN = "BY";
    private static final String CASE_TOKEN = "CASE";
    private static final String END_TOKEN = "END";
    private static final String NATIONAL_PREFIX = "N";
    private static final String BLOCK_START = "/*";
    private static final String BLOCK_END = "*/";
    /** 项目普通标识符采用 ASCII 字母范围。 */
    private static final char UPPER_FIRST = 'A';
    private static final char UPPER_LAST = 'Z';
    private static final char LOWER_FIRST = 'a';
    private static final char LOWER_LAST = 'z';

    /** 可以确认的单张物理表。 */
    private final QualifiedName table;
    /** 明确投影按 JDBC 顺序索引。 */
    private final List<Origin> columns;
    /** 单独星号的列数由 JDBC 元数据确定，不能同其他投影混用。 */
    private final boolean wildcard;

    /** Params: table 物理表；columns 显式投影；wildcard 单独星号。 Returns: 只读来源解析结果。 */
    private QueryColumnOriginSupport(QualifiedName table, List<Origin> columns, boolean wildcard) {
        this.table = table;
        this.columns = columns;
        this.wildcard = wildcard;
    }

    /**
     * 不执行 SQL，也不为 JOIN、CTE、集合查询、派生表或不完整语法推测物理来源。
     * Params: sql 原始查询。
     * Returns: 可逐列取来源的结果；不可确认时所有列均返回未知，绝不以别名猜作源表字段。
     */
    static QueryColumnOriginSupport parse(String sql) {
        List<Token> tokens = tokenize(sql);
        if (tokens == null || tokens.isEmpty() || !tokens.get(ZERO).keyword(SELECT_TOKEN)) {
            return UNKNOWN;
        }
        if (tokens.get(tokens.size() - ONE).symbol(SEMICOLON_TOKEN)) {
            tokens.remove(tokens.size() - ONE);
        }
        int from = -ONE;
        int tail = -ONE;
        for (int i = ONE; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.symbol(SEMICOLON_TOKEN)) {
                return UNKNOWN;
            }
            if (token.depth != ZERO || token.kind != Kind.WORD) {
                continue;
            }
            if (UNSUPPORTED.contains(token.upper()) || COMPLEX_TAIL.contains(token.upper())) {
                return UNKNOWN;
            }
            if (token.keyword(FROM_TOKEN)) {
                if (from >= ZERO) {
                    return UNKNOWN;
                }
                from = i;
            } else if (from >= ZERO && tail < ZERO && TAIL.contains(token.upper())) {
                tail = i;
            }
        }
        if (from <= ONE) {
            return UNKNOWN;
        }
        if (tail < ZERO) {
            tail = tokens.size();
        }
        TableReference reference = table(tokens.subList(from + ONE, tail));
        if (reference == null || !validTail(tokens.subList(tail, tokens.size()))) {
            return UNKNOWN;
        }
        int projectionStart = ONE;
        if (tokens.get(projectionStart).keyword(DISTINCT_TOKEN) || tokens.get(projectionStart).keyword(ALL_TOKEN)) {
            projectionStart++;
        }
        List<List<Token>> projections = split(tokens.subList(projectionStart, from));
        if (projections == null || projections.isEmpty()) {
            return UNKNOWN;
        }
        if (projections.size() == ONE && wildcard(projections.get(ZERO), reference)) {
            return new QueryColumnOriginSupport(reference.table, Collections.<Origin>emptyList(), true);
        }
        List<Origin> origins = new ArrayList<>();
        for (List<Token> projection : projections) {
            if (looksLikeWildcard(projection)) {
                // 混合星号会展开不确定的列数，不能继续按位置猜测后续投影。
                return UNKNOWN;
            }
            origins.add(projection(projection, reference));
        }
        return new QueryColumnOriginSupport(reference.table, Collections.unmodifiableList(origins), false);
    }

    /**
     * 显式投影按位置返回真实列名；单独星号才使用 JDBC 给出的真实字段名。
     * Params: jdbcOneBasedIndex JDBC 一起始列序号；metadataColumnName JDBC 物理字段名，不是用户别名。
     * Returns: 物理来源、确认表达式或未知来源；未知结果不会携带猜测表名。
     */
    Origin column(int jdbcOneBasedIndex, String metadataColumnName) {
        if (jdbcOneBasedIndex < ONE || table == null) {
            return UNKNOWN_ORIGIN;
        }
        if (wildcard) {
            if (metadataColumnName == null || !IDENTIFIER.matcher(metadataColumnName).matches()) {
                return UNKNOWN_ORIGIN;
            }
            return new Origin(table, metadataColumnName, false);
        }
        return jdbcOneBasedIndex <= columns.size() ? columns.get(jdbcOneBasedIndex - ONE) : UNKNOWN_ORIGIN;
    }

    /** Params: tokens FROM 中的单表引用。 Returns: 物理表与可选别名；其余语法均拒绝。 */
    private static TableReference table(List<Token> tokens) {
        if (tokens.isEmpty() || !tokens.get(ZERO).identifier()) {
            return null;
        }
        int position = ONE;
        String schema = null;
        String tableName = tokens.get(ZERO).text;
        if (position < tokens.size() && tokens.get(position).symbol(DOT_TOKEN)) {
            if (tokens.size() < THREE || !tokens.get(TWO).identifier()) {
                return null;
            }
            schema = tableName;
            tableName = tokens.get(TWO).text;
            position = THREE;
        }
        String alias = null;
        if (position < tokens.size() && tokens.get(position).keyword(AS_TOKEN)) {
            position++;
            if (position == tokens.size()) {
                return null;
            }
        }
        if (position < tokens.size() && tokens.get(position).identifier()) {
            alias = tokens.get(position++).text;
        }
        if (position != tokens.size()) {
            return null;
        }
        try {
            QualifiedName name = SqlSupport.parseTableName(schema == null ? tableName : schema + DOT_TOKEN + tableName,
                "源查询物理来源");
            return new TableReference(name, alias);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /** Params: tokens 单表后的筛选及排序子句。 Returns: 尾部边界是否仍在普通查询范围。 */
    private static boolean validTail(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return true;
        }
        if (!TAIL.contains(tokens.get(ZERO).upper()) || tokens.size() < TWO) {
            return false;
        }
        for (int i = ZERO; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.depth == ZERO && token.keyword(ORDER_TOKEN)
                && (i + TWO >= tokens.size() || !tokens.get(i + ONE).keyword(BY_TOKEN))) {
                return false;
            }
        }
        return true;
    }

    /** Params: tokens 投影区。 Returns: 忽略函数和字符串内部逗号的显式投影；空项返回 null。 */
    private static List<List<Token>> split(List<Token> tokens) {
        if (tokens.isEmpty()) {
            return null;
        }
        List<List<Token>> result = new ArrayList<>();
        int start = ZERO;
        for (int i = ZERO; i < tokens.size(); i++) {
            if (tokens.get(i).depth == ZERO && tokens.get(i).symbol(COMMA_TOKEN)) {
                if (start == i) {
                    return null;
                }
                result.add(tokens.subList(start, i));
                start = i + ONE;
            }
        }
        if (start == tokens.size()) {
            return null;
        }
        result.add(tokens.subList(start, tokens.size()));
        return result;
    }

    /** Params: tokens 单个投影；reference 已确认的单物理表。 Returns: 直列来源或确认表达式，其他未知。 */
    private static Origin projection(List<Token> tokens, TableReference reference) {
        List<Token> body = tokens;
        for (int i = ZERO; i < tokens.size(); i++) {
            if (tokens.get(i).depth == ZERO && tokens.get(i).keyword(AS_TOKEN)) {
                if (i != tokens.size() - TWO || !tokens.get(i + ONE).identifier()) {
                    return UNKNOWN_ORIGIN;
                }
                body = tokens.subList(ZERO, i);
                break;
            }
        }
        Origin result = value(body, reference);
        if (result != UNKNOWN_ORIGIN || body != tokens || tokens.size() < TWO
            || !tokens.get(tokens.size() - ONE).identifier()) {
            return result;
        }
        // 只在去掉末尾别名后可确认为直列或计算式时接受隐式别名。
        return value(tokens.subList(ZERO, tokens.size() - ONE), reference);
    }

    /** Params: tokens 无别名投影；reference 单表来源。 Returns: 可确认的投影语义。 */
    private static Origin value(List<Token> tokens, TableReference reference) {
        if (tokens.isEmpty()) {
            return UNKNOWN_ORIGIN;
        }
        List<Token> body = unwrap(tokens);
        String direct = directColumn(body, reference);
        if (direct != null) {
            return new Origin(reference.table, direct, false);
        }
        if (body.size() == ONE) {
            Token token = body.get(ZERO);
            if (token.kind == Kind.STRING || token.kind == Kind.NUMBER
                || token.kind == Kind.WORD && VALUE_KEYWORDS.contains(token.upper())) {
                return EXPRESSION_ORIGIN;
            }
        }
        if (body.size() >= THREE && body.get(ZERO).keyword(CASE_TOKEN)
            && body.get(body.size() - ONE).keyword(END_TOKEN)) {
            return EXPRESSION_ORIGIN;
        }
        if (function(body)) {
            return EXPRESSION_ORIGIN;
        }
        int baseDepth = body.get(ZERO).depth;
        for (Token token : body) {
            if (token.depth == baseDepth && token.kind == Kind.SYMBOL && OPERATORS.contains(token.text)) {
                return EXPRESSION_ORIGIN;
            }
        }
        return UNKNOWN_ORIGIN;
    }

    /** Params: tokens 投影；reference 单表来源。 Returns: 真实列名；来源限定不匹配时为 null。 */
    private static String directColumn(List<Token> tokens, TableReference reference) {
        if (tokens.size() == ONE && tokens.get(ZERO).identifier()) {
            return tokens.get(ZERO).text;
        }
        if (tokens.size() == THREE && tokens.get(ZERO).identifier()
            && tokens.get(ONE).symbol(DOT_TOKEN) && tokens.get(TWO).identifier()
            && reference.qualifier(tokens.get(ZERO).text)) {
            return tokens.get(TWO).text;
        }
        if (tokens.size() == FIVE && tokens.get(ZERO).identifier() && tokens.get(TWO).identifier()
            && tokens.get(FOUR).identifier() && tokens.get(ONE).symbol(DOT_TOKEN)
            && tokens.get(THREE).symbol(DOT_TOKEN) && reference.table.getSchema() != null
            && reference.table.getSchema().equalsIgnoreCase(tokens.get(ZERO).text)
            && reference.table.getTable().equalsIgnoreCase(tokens.get(TWO).text)) {
            return tokens.get(FOUR).text;
        }
        return null;
    }

    /** Params: tokens 无别名投影。 Returns: 是否为完整函数调用，不把括号外附加的片段算进来。 */
    private static boolean function(List<Token> tokens) {
        int opening = -ONE;
        if (tokens.size() >= THREE && tokens.get(ZERO).kind == Kind.WORD
            && tokens.get(ONE).symbol(OPEN_TOKEN)) {
            opening = ONE;
        } else if (tokens.size() >= FIVE && tokens.get(ZERO).identifier()
            && tokens.get(ONE).symbol(DOT_TOKEN) && tokens.get(TWO).identifier()
            && tokens.get(THREE).symbol(OPEN_TOKEN)) {
            opening = THREE;
        }
        return opening >= ZERO && matchingClose(tokens, opening) == tokens.size() - ONE;
    }

    /** Params: tokens 投影；reference 单表来源。 Returns: 是否为可定位到该表的单独星号。 */
    private static boolean wildcard(List<Token> tokens, TableReference reference) {
        if (tokens.size() == ONE && tokens.get(ZERO).symbol(STAR_TOKEN)) {
            return true;
        }
        return tokens.size() == THREE && tokens.get(ZERO).identifier()
            && tokens.get(ONE).symbol(DOT_TOKEN) && tokens.get(TWO).symbol(STAR_TOKEN)
            && reference.qualifier(tokens.get(ZERO).text);
    }

    /** Params: tokens 单个投影。 Returns: 是否含独立或限定星号，混用时不能按 JDBC 下标推断。 */
    private static boolean looksLikeWildcard(List<Token> tokens) {
        return tokens.size() == ONE && tokens.get(ZERO).symbol(STAR_TOKEN)
            || tokens.size() >= THREE && tokens.get(tokens.size() - ONE).symbol(STAR_TOKEN)
            && tokens.get(tokens.size() - TWO).symbol(DOT_TOKEN);
    }

    /** Params: tokens 投影。 Returns: 只去掉确实包围整个投影的括号，不改变内部字符。 */
    private static List<Token> unwrap(List<Token> tokens) {
        List<Token> result = tokens;
        while (result.size() >= THREE && result.get(ZERO).symbol(OPEN_TOKEN)
            && matchingClose(result, ZERO) == result.size() - ONE) {
            result = result.subList(ONE, result.size() - ONE);
        }
        return result;
    }

    /** Params: tokens 当前片段；opening 左括号下标。 Returns: 对应右括号下标，缺失为 -1。 */
    private static int matchingClose(List<Token> tokens, int opening) {
        int depth = tokens.get(opening).depth;
        for (int i = opening + ONE; i < tokens.size(); i++) {
            if (tokens.get(i).symbol(CLOSE_TOKEN) && tokens.get(i).depth == depth) {
                return i;
            }
        }
        return -ONE;
    }

    /**
     * 单次扫描隔离注释、引用与括号层级；未知引号形式或未闭合内容直接拒绝。
     * Params: sql 原始查询。
     * Returns: 带层级的 token；不能可靠分词时返回 null。
     */
    private static List<Token> tokenize(String sql) {
        if (sql == null) {
            return null;
        }
        List<Token> result = new ArrayList<>();
        int depth = ZERO;
        for (int i = ZERO; i < sql.length();) {
            char current = sql.charAt(i);
            char next = i + ONE < sql.length() ? sql.charAt(i + ONE) : NONE;
            if (Character.isWhitespace(current)) {
                i++;
                continue;
            }
            if (current == DASH && next == DASH) {
                i += TWO;
                while (i < sql.length() && sql.charAt(i) != LINE_FEED && sql.charAt(i) != CARRIAGE_RETURN) {
                    i++;
                }
                continue;
            }
            if (current == SLASH && next == ASTERISK) {
                int end = sql.indexOf(BLOCK_END, i + TWO);
                if (end < ZERO || sql.substring(i + TWO, end).contains(BLOCK_START)) {
                    return null;
                }
                i = end + TWO;
                continue;
            }
            if (current == SINGLE_QUOTE || current == DOUBLE_QUOTE || current == BACKTICK) {
                if (current == BACKTICK) {
                    // 本解析器不具备 MySQL lower_case_table_names/sql_mode，不能猜测引用名称的语义。
                    return null;
                }
                int end = quotedEnd(sql, i, current);
                if (end < ZERO) {
                    return null;
                }
                String text = sql.substring(i + ONE, end - ONE);
                if (current == DOUBLE_QUOTE && !UPPER_IDENTIFIER.matcher(text).matches()) {
                    return null;
                }
                result.add(new Token(text, current == SINGLE_QUOTE ? Kind.STRING : Kind.QUOTED, depth));
                i = end;
                continue;
            }
            if (current == OPEN) {
                result.add(new Token(OPEN_TOKEN, Kind.SYMBOL, depth++));
                i++;
                continue;
            }
            if (current == CLOSE) {
                if (--depth < ZERO) {
                    return null;
                }
                result.add(new Token(CLOSE_TOKEN, Kind.SYMBOL, depth));
                i++;
                continue;
            }
            if (letter(current)) {
                int end = i + ONE;
                while (end < sql.length() && (letter(sql.charAt(end)) || Character.isDigit(sql.charAt(end)))) {
                    end++;
                }
                String word = sql.substring(i, end);
                // Oracle q'...' 或 N'...' 不能与紧邻字符串分开猜测；N 字符串仅确认表达式。
                if (end < sql.length() && sql.charAt(end) == SINGLE_QUOTE) {
                    if (!word.equalsIgnoreCase(NATIONAL_PREFIX)) {
                        return null;
                    }
                    int literalEnd = quotedEnd(sql, end, SINGLE_QUOTE);
                    if (literalEnd < ZERO) {
                        return null;
                    }
                    result.add(new Token(sql.substring(i, literalEnd), Kind.STRING, depth));
                    i = literalEnd;
                    continue;
                }
                result.add(new Token(word, Kind.WORD, depth));
                i = end;
                continue;
            }
            if (Character.isDigit(current) || current == DOT && Character.isDigit(next)) {
                java.util.regex.Matcher numberMatcher = NUMBER_LITERAL.matcher(sql).region(i, sql.length());
                if (!numberMatcher.lookingAt()) {
                    return null;
                }
                int end = numberMatcher.end();
                String number = sql.substring(i, end);
                result.add(new Token(number, Kind.NUMBER, depth));
                i = end;
                continue;
            }
            if (current == BACKSLASH || Character.isISOControl(current)
                || Character.getType(current) == Character.LINE_SEPARATOR
                || Character.getType(current) == Character.PARAGRAPH_SEPARATOR) {
                return null;
            }
            result.add(new Token(String.valueOf(current), Kind.SYMBOL, depth));
            i++;
        }
        return depth == ZERO ? result : null;
    }

    /** Params: sql 原文；start 起始引号；quote 引号字符。 Returns: 闭合引号后一位；不可靠时为 -1。 */
    private static int quotedEnd(String sql, int start, char quote) {
        for (int i = start + ONE; i < sql.length(); i++) {
            char current = sql.charAt(i);
            if (current == BACKSLASH) {
                // 跨方言 SQL 模式未知时，拒绝依赖反斜杠逃逸的边界判断。
                return -ONE;
            }
            if (current == quote) {
                if (i + ONE < sql.length() && sql.charAt(i + ONE) == quote) {
                    i++;
                } else {
                    return i + ONE;
                }
            }
        }
        return -ONE;
    }

    /** Params: value 当前字符。 Returns: 普通标识符起始字符。 */
    private static boolean letter(char value) {
        return value >= UPPER_FIRST && value <= UPPER_LAST || value >= LOWER_FIRST && value <= LOWER_LAST
            || value == UNDERSCORE || value == DOLLAR || value == HASH;
    }

    /** Params: values 关键词。 Returns: 只读关键词集合。 */
    private static Set<String> set(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    /**
     * 单列来源：table 为空且 expression 为 false 表示未知，不能按查询别名猜源默认值。
     * Author: Codex
     * date: 2026-10-30
     */
    static final class Origin {
        /** 确认的物理表；表达式与未知来源均为 null。 */
        final QualifiedName table;
        /** 确认的真实物理字段名；不使用输出别名替代。 */
        final String columnName;
        /** 已确认发生值计算，不应继承源字段默认值。 */
        final boolean expression;

        /** Params: table 物理表；columnName 真实列；expression 计算式标志。 Returns: 不可变来源。 */
        private Origin(QualifiedName table, String columnName, boolean expression) {
            this.table = table;
            this.columnName = columnName;
            this.expression = expression;
        }
    }

    /**
     * 单张物理表及其查询别名。
     * Author: Codex
     * date: 2026-10-30
     */
    private static final class TableReference {
        /** 原文普通表名，不改变 MySQL 表名大小写。 */
        private final QualifiedName table;
        /** 原文查询别名。 */
        private final String alias;

        /** Params: table 物理表；alias 可选别名。 Returns: 单表引用。 */
        private TableReference(QualifiedName table, String alias) {
            this.table = table;
            this.alias = alias;
        }

        /** Params: value 投影限定符。 Returns: 是否明确指向当前单表。 */
        private boolean qualifier(String value) {
            return alias == null ? table.getTable().equalsIgnoreCase(value) : alias.equalsIgnoreCase(value);
        }
    }

    /** 词法 token 类型。 */
    private enum Kind {
        WORD, QUOTED, STRING, NUMBER, SYMBOL
    }

    /**
     * 已隔离引用和注释的词法项。
     * Author: Codex
     * date: 2026-10-30
     */
    private static final class Token {
        /** 原始文字；安全双引号名称仅移除引号，保留大写。 */
        private final String text;
        /** 词法类型。 */
        private final Kind kind;
        /** 当前括号层级，左右括号记录其外层深度。 */
        private final int depth;

        /** Params: text 内容；kind 类型；depth 层级。 Returns: 不可变词法项。 */
        private Token(String text, Kind kind, int depth) {
            this.text = text;
            this.kind = kind;
            this.depth = depth;
        }

        /** Params: value 关键词。 Returns: 仅未引用 WORD 可以充当关键词。 */
        private boolean keyword(String value) {
            return kind == Kind.WORD && text.equalsIgnoreCase(value);
        }

        /** Params: value 运算或分隔符。 Returns: 字符串和引用标识符绝不充当 SQL 边界。 */
        private boolean symbol(String value) {
            return kind == Kind.SYMBOL && text.equals(value);
        }

        /** Params: 无。 Returns: 可安全还原为普通 SQL 标识符，引用关键词仍不猜测其来源。 */
        private boolean identifier() {
            return (kind == Kind.WORD || kind == Kind.QUOTED) && IDENTIFIER.matcher(text).matches()
                && !RESERVED.contains(upper()) && !UNSUPPORTED.contains(upper());
        }

        /** Params: 无。 Returns: 仅作关键词比较的大写值，不改写实际名称。 */
        private String upper() {
            return text.toUpperCase(Locale.ROOT);
        }
    }
}
