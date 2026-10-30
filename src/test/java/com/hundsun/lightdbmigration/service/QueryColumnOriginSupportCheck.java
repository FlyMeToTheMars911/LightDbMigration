package com.hundsun.lightdbmigration.service;

import java.util.Arrays;

/**
 * 验证普通单表来源回退解析与复杂查询拒绝边界，防止把表达式或输出别名误当物理列。
 * Author: Codex
 * date: 2026-10-30
 */
public final class QueryColumnOriginSupportCheck {

    /** JDBC 第一列。 */
    private static final int FIRST = 1;
    /** JDBC 第二列。 */
    private static final int SECOND = 2;
    /** JDBC 第三列。 */
    private static final int THIRD = 3;
    /** JDBC 第四列。 */
    private static final int FOURTH = 4;
    /** 非法或未解析的下标。 */
    private static final int INVALID = 0;
    /** 断言计数。 */
    private static int assertions;

    /** Params: 无。 Returns: 不允许实例化检查类。 */
    private QueryColumnOriginSupportCheck() {
    }

    /** Params: args 未使用。 Returns: 无；失败直接抛出断言。 */
    public static void main(String[] args) {
        verifyPlainSources();
        verifyProjectionAliases();
        verifyExpressions();
        verifyLexicalBoundaries();
        verifyUnsupportedQueries();
        verifyUnknownProjections();
        System.out.println("[RESULT] QUERY_COLUMN_ORIGIN_SUPPORT_CHECK_PASSED assertions=" + assertions);
    }

    /** Params: 无。 Returns: 无；普通名称、模式及单独星号可准确还原。 */
    private static void verifyPlainSources() {
        physical(QueryColumnOriginSupport.parse("SELECT * FROM SOURCE_TABLE"), FIRST,
            "VIP_LEVEL", "SOURCE_TABLE", "VIP_LEVEL");
        physical(QueryColumnOriginSupport.parse("select t.* from app.SourceTable t WHERE t.ID > 0 ORDER BY t.ID"),
            SECOND, "VIP_LEVEL", "app.SourceTable", "VIP_LEVEL");
        physical(QueryColumnOriginSupport.parse("SELECT T.* FROM APP.SOURCE_TABLE AS T"), THIRD,
            "AUTO_USED_FLAG", "APP.SOURCE_TABLE", "AUTO_USED_FLAG");
        physical(QueryColumnOriginSupport.parse("SELECT SOURCE_TABLE.* FROM APP.SOURCE_TABLE"), FIRST,
            "ID", "APP.SOURCE_TABLE", "ID");
        physical(QueryColumnOriginSupport.parse("SELECT id FROM app.SourceTable"), FIRST,
            "OUTPUT_ALIAS", "app.SourceTable", "id");
        physical(QueryColumnOriginSupport.parse("SELECT DISTINCT id, name FROM SOURCE_TABLE ORDER BY id"), SECOND,
            "NAME", "SOURCE_TABLE", "name");
        physical(QueryColumnOriginSupport.parse("SELECT ALL id FROM SOURCE_TABLE;"), FIRST,
            "ID", "SOURCE_TABLE", "id");
        physical(QueryColumnOriginSupport.parse("SELECT \"VIP_LEVEL\" FROM \"APP\".\"SOURCE_TABLE\""), FIRST,
            "VIP_LEVEL", "APP.SOURCE_TABLE", "VIP_LEVEL");
        unknown(QueryColumnOriginSupport.parse("SELECT * FROM SOURCE_TABLE"), FIRST, "UNSAFE COLUMN");
        unknown(QueryColumnOriginSupport.parse("SELECT * FROM SOURCE_TABLE"), FIRST, null);
        unknown(QueryColumnOriginSupport.parse("SELECT * FROM SOURCE_TABLE"), INVALID, "ID");
        unknown(QueryColumnOriginSupport.parse("SELECT id FROM SOURCE_TABLE"), SECOND, "ID");
    }

    /** Params: 无。 Returns: 无；投影别名与源列名不同仍返回源定义名。 */
    private static void verifyProjectionAliases() {
        QueryColumnOriginSupport query = QueryColumnOriginSupport.parse("SELECT id AS result_id, name name_alias,"
            + " t.vip_level AS v FROM APP.SOURCE_TABLE t WHERE t.id > 0 ORDER BY result_id");
        physical(query, FIRST, "RESULT_ID", "APP.SOURCE_TABLE", "id");
        physical(query, SECOND, "NAME_ALIAS", "APP.SOURCE_TABLE", "name");
        physical(query, THIRD, "V", "APP.SOURCE_TABLE", "vip_level");
        physical(QueryColumnOriginSupport.parse("SELECT APP.SOURCE_TABLE.id AS result FROM APP.SOURCE_TABLE"),
            FIRST, "RESULT", "APP.SOURCE_TABLE", "id");
        physical(QueryColumnOriginSupport.parse("SELECT ((t.id)) AS result FROM SOURCE_TABLE t"), FIRST,
            "RESULT", "SOURCE_TABLE", "id");
        physical(QueryColumnOriginSupport.parse("SELECT t.id result FROM SOURCE_TABLE t"), FIRST,
            "RESULT", "SOURCE_TABLE", "id");
        physical(QueryColumnOriginSupport.parse("SELECT id \"RESULT\" FROM SOURCE_TABLE"), FIRST,
            "RESULT", "SOURCE_TABLE", "id");
    }

    /** Params: 无。 Returns: 无；计算表达式明确没有可继承的源列默认值。 */
    private static void verifyExpressions() {
        QueryColumnOriginSupport query = QueryColumnOriginSupport.parse("SELECT CAST(t.id AS VARCHAR(10)) AS id,"
            + " 0 AS id, trim(t.name) name, t.id + 1 id FROM SOURCE_TABLE t");
        expression(query, FIRST);
        expression(query, SECOND);
        expression(query, THIRD);
        expression(query, FOURTH);
        query = QueryColumnOriginSupport.parse("SELECT CURRENT_TIMESTAMP, 1e2, -1.2, NULL FROM SOURCE_TABLE");
        expression(query, FIRST);
        expression(query, SECOND);
        expression(query, THIRD);
        expression(query, FOURTH);
        expression(QueryColumnOriginSupport.parse("SELECT CASE WHEN id = 0 THEN 'FROM fake' ELSE 'x' END AS id"
            + " FROM SOURCE_TABLE"), FIRST);
        expression(QueryColumnOriginSupport.parse("SELECT ((id + 0)) AS id FROM SOURCE_TABLE"), FIRST);
        expression(QueryColumnOriginSupport.parse("SELECT N'中文 FROM fake' AS id FROM SOURCE_TABLE"), FIRST);
        expression(QueryColumnOriginSupport.parse("SELECT pkg.f(id, ',FROM fake') AS id FROM SOURCE_TABLE"), FIRST);
        expression(QueryColumnOriginSupport.parse("SELECT CURRENT_TIMESTAMP ts FROM SOURCE_TABLE"), FIRST);
    }

    /** Params: 无。 Returns: 无；字符串、注释及嵌套括号内的 FROM 和分隔符不改变物理表。 */
    private static void verifyLexicalBoundaries() {
        QueryColumnOriginSupport query = QueryColumnOriginSupport.parse("SELECT 'FROM FAKE JOIN WRONG' AS id,"
            + " id FROM SOURCE_TABLE WHERE note = 'UNION FROM BAD' ORDER BY id");
        expression(query, FIRST);
        physical(query, SECOND, "ID", "SOURCE_TABLE", "id");
        query = QueryColumnOriginSupport.parse("SELECT '(', ')', ',', '*' FROM SOURCE_TABLE");
        expression(query, FIRST);
        expression(query, SECOND);
        expression(query, THIRD);
        expression(query, FOURTH);
        expression(QueryColumnOriginSupport.parse("SELECT ';' AS id FROM SOURCE_TABLE"), FIRST);
        expression(QueryColumnOriginSupport.parse("SELECT 'it''s FROM not_a_table' AS id FROM SOURCE_TABLE"), FIRST);
        physical(QueryColumnOriginSupport.parse("SELECT /* FROM FAKE JOIN A */ id FROM SOURCE_TABLE"
            + " -- FROM SECOND_SOURCE\n WHERE id IN (SELECT id FROM OTHER_TABLE) ORDER BY id"), FIRST,
            "ID", "SOURCE_TABLE", "id");
        physical(QueryColumnOriginSupport.parse("SELECT t./* column boundary */id AS result"
            + " FROM APP./* table boundary */SOURCE_TABLE t WHERE note = '/* FROM fake */'"), FIRST,
            "RESULT", "APP.SOURCE_TABLE", "id");
        expression(QueryColumnOriginSupport.parse("SELECT COALESCE(name, 'FROM fake, JOIN x') AS name"
            + " FROM SOURCE_TABLE"), FIRST);
    }

    /** Params: 无。 Returns: 无；超出普通单表范围的查询一律不给物理来源。 */
    private static void verifyUnsupportedQueries() {
        for (String sql : Arrays.asList(null, "", "DELETE FROM SOURCE_TABLE", "SELECT id", "SELECT FROM SOURCE_TABLE",
            "WITH q AS (SELECT * FROM SOURCE_TABLE) SELECT id FROM q",
            "SELECT a.id FROM SOURCE_TABLE a JOIN OTHER_TABLE b ON a.id = b.id",
            "SELECT a.id FROM SOURCE_TABLE a LEFT JOIN OTHER_TABLE b ON a.id = b.id",
            "SELECT a.id FROM SOURCE_TABLE a, OTHER_TABLE b",
            "SELECT id FROM SOURCE_TABLE UNION SELECT id FROM OTHER_TABLE",
            "SELECT id FROM SOURCE_TABLE INTERSECT SELECT id FROM OTHER_TABLE",
            "SELECT id FROM SOURCE_TABLE MINUS SELECT id FROM OTHER_TABLE",
            "SELECT id FROM SOURCE_TABLE EXCEPT SELECT id FROM OTHER_TABLE",
            "SELECT id FROM (SELECT id FROM SOURCE_TABLE) derived",
            "SELECT * FROM SOURCE_TABLE WHERE id > 0 UNION ALL SELECT * FROM OTHER_TABLE",
            "SELECT id FROM SOURCE_TABLE GROUP BY id",
            "SELECT * FROM SOURCE_TABLE t PIVOT (MAX(id) FOR type IN ('A'))",
            "SELECT * FROM SOURCE_TABLE@database_link",
            "SELECT * FROM SOURCE_TABLE PARTITION (PART_A)",
            "SELECT id FROM SOURCE_TABLE AS", "SELECT id FROM SOURCE_TABLE ORDER id",
            "SELECT id FROM SOURCE_TABLE WHERE", "SELECT id FROM SOURCE_TABLE; SELECT * FROM OTHER_TABLE",
            "SELECT id FROM SOURCE_TABLE;;", "SELECT id FROM SOURCE_TABLE /* unclosed",
            "SELECT id FROM SOURCE_TABLE /* nested /* comment */ SELECT * FROM WRONG */",
            "SELECT 'unclosed FROM SOURCE_TABLE", "SELECT (id FROM SOURCE_TABLE", "SELECT id) FROM SOURCE_TABLE",
            "SELECT q'[FROM fake]' AS id FROM SOURCE_TABLE", "SELECT id FROM `SourceTable`",
            "SELECT id FROM \"SourceTable\"", "SELECT \"VipLevel\" FROM SOURCE_TABLE",
            "SELECT id AS \"resultAlias\" FROM SOURCE_TABLE", "SELECT '*' FROM \"SourceTable\"",
            "SELECT 'escaped\\' FROM SOURCE_TABLE")) {
            unknown(QueryColumnOriginSupport.parse(sql), FIRST, "ID");
        }
        for (String sql : Arrays.asList("SELECT *, id FROM SOURCE_TABLE", "SELECT id, t.* FROM SOURCE_TABLE t",
            "SELECT t.*, id FROM SOURCE_TABLE t", "SELECT other.* FROM SOURCE_TABLE t")) {
            QueryColumnOriginSupport query = QueryColumnOriginSupport.parse(sql);
            unknown(query, FIRST, "ID");
            unknown(query, SECOND, "ID");
        }
    }

    /** Params: 无。 Returns: 无；无法确认的单个投影不污染其他明确列的来源。 */
    private static void verifyUnknownProjections() {
        QueryColumnOriginSupport query = QueryColumnOriginSupport.parse("SELECT other.id AS result, t.id"
            + " FROM SOURCE_TABLE t");
        unknown(query, FIRST, "RESULT");
        physical(query, SECOND, "ID", "SOURCE_TABLE", "id");
        query = QueryColumnOriginSupport.parse("SELECT id COLLATE unsupported_collation, id FROM SOURCE_TABLE");
        unknown(query, FIRST, "ID");
        physical(query, SECOND, "ID", "SOURCE_TABLE", "id");
        unknown(QueryColumnOriginSupport.parse("SELECT id strange extra FROM SOURCE_TABLE"), FIRST, "EXTRA");
        unknown(QueryColumnOriginSupport.parse("SELECT id AS strange extra FROM SOURCE_TABLE"), FIRST, "EXTRA");
        unknown(QueryColumnOriginSupport.parse("SELECT id FROM SOURCE_TABLE"), INVALID, "ID");
    }

    /** Params: query 解析结果；index 列序号；metadata JDBC 名；table 期望表；column 真实列。 Returns: 无。 */
    private static void physical(QueryColumnOriginSupport query, int index, String metadata,
        String table, String column) {
        QueryColumnOriginSupport.Origin origin = query.column(index, metadata);
        check(origin != null && origin.table != null, "应确认物理来源：" + table + "." + column);
        equal(table, origin.table.toSql(), "物理表名需保留原始普通名称大小写");
        equal(column, origin.columnName, "必须取真实列名，不能取输出别名");
        check(!origin.expression, "直列不能误作计算结果");
    }

    /** Params: query 解析结果；index 列序号。 Returns: 无；确认表达式不能继承默认值。 */
    private static void expression(QueryColumnOriginSupport query, int index) {
        QueryColumnOriginSupport.Origin origin = query.column(index, "OUTPUT_ALIAS");
        check(origin != null && origin.table == null && origin.columnName == null && origin.expression,
            "计算表达式必须与未知来源明确区分，index=" + index);
    }

    /** Params: query 解析结果；index 列序号；metadata JDBC 名。 Returns: 无；未知来源不能推测表或列。 */
    private static void unknown(QueryColumnOriginSupport query, int index, String metadata) {
        QueryColumnOriginSupport.Origin origin = query.column(index, metadata);
        check(origin != null && origin.table == null && origin.columnName == null && !origin.expression,
            "未支持的语法或未知投影不能猜测物理来源，index=" + index);
    }

    /** Params: expected 预期；actual 实际；message 失败原因。 Returns: 无。 */
    private static void equal(String expected, String actual, String message) {
        check(expected.equals(actual), message + " expected=" + expected + " actual=" + actual);
    }

    /** Params: condition 条件；message 失败原因。 Returns: 无。 */
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
