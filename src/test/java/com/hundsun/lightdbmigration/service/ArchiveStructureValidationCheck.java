package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 验证归档结构预检汇总主子表差异并阻止数据变更，使用内存 JDBC 代理而非真实连接。
 * Author: Codex
 * date: 2026-10-26
 */
public class ArchiveStructureValidationCheck {

    private static final String MYSQL = MigrationConstants.DB_MYSQL;
    private static final String SELECT_PREFIX = "SELECT";
    private static final String ISSUE_PREFIX = "归档表结构校验问题";
    private static final String FIRST_SOURCE = "CHECK_FIRST_SOURCE";
    private static final String FIRST_TARGET = "CHECK_FIRST_ARCHIVE";
    private static final String SECOND_SOURCE = "CHECK_SECOND_SOURCE";
    private static final String SECOND_TARGET = "CHECK_SECOND_ARCHIVE";
    private static final String CHILD_SOURCE = "CHECK_CHILD_SOURCE";
    private static final String CHILD_TARGET = "CHECK_CHILD_ARCHIVE";
    private static final String MISSING_SOURCE = "CHECK_MISSING_SOURCE";
    private static final String MISSING_TARGET = "CHECK_MISSING_ARCHIVE";
    private static final String ID = "ID";
    private static final String NAME = "NAME";
    private static final String MISSING_COLUMN = "MISSING_COL";
    private static final String EXTRA_COLUMN = "EXTRA_COL";
    private static final String SECOND_COLUMN = "SECOND_VALUE";
    private static final String CHILD_COLUMN = "CHILD_NOTE";
    private static final String AMOUNT_COLUMN = "AMOUNT";
    private static final String REQUIRED_COLUMN = "REQUIRED_VALUE";
    private static final String TIGHTEN_COLUMN = "TIGHTEN_VALUE";
    private static final String INTEGER_TYPE = "INT";
    private static final String TEXT_TYPE = "VARCHAR";
    private static final String UNKNOWN_TYPE = "ENUM";
    private static final String MISSING_STATE = "42S02";
    private static final String CONNECTION_STATE = "08006";
    private static final int MISSING_CODE = 1146;
    private static final int ORACLE_MISSING_CODE = 942;
    private static final int DM_MISSING_CODE = -2106;
    private static final int EXPECTED_PHYSICAL_TABLES = 8;
    private static final int NO_WRITES = 0;
    private static final int FIRST_QUERY = 1;
    private static final String BUSINESS_QUERY_MARKER = "BUSINESS_QUERY";
    private static final String NUMBER_TYPE = "NUMBER";
    private static final String ORACLE_TEXT_TYPE = "VARCHAR2";
    private static final String COUNT_QUERY_PREFIX = "SELECT COUNT(*)";
    private static final String DERIVED_QUERY_PREFIX = "FROM (";
    private static final int EXPECTED_METADATA_PHASES = 2;
    private static final long SINGLE_ROW = 1L;
    private static final int EXPECTED_DM_NUMERIC_COLUMNS = 4;
    private static final String DM_UNKNOWN_PRECISION_NOTICE = "已兼容 DM 归档查询数值字段的未知精度标记";
    private static final String MYSQL_COLUMNS_MARKER = "information_schema.COLUMNS";
    private static final String FIXTURE_SCHEMA = "FIXTURE";
    private static final String FIXTURE_CATALOG = "fixture_db";
    private static final int MYSQL_TABLE_BINDING = 2;
    private static final String ORACLE_INTERVAL_COLUMNS_MARKER = "FROM ALL_TAB_COLUMNS";
    private static final String INTERVAL_DECLARATION_PREFIX = "INTERVAL";
    private static final int ORACLE_JDBC_INTERVAL_YEAR_MONTH = -103;
    private static final int ORACLE_JDBC_INTERVAL_DAY_SECOND = -104;

    /**
     * 执行不依赖真实数据库的结构校验回归。
     * Params: args 未使用。
     * Returns: 无。
     */
    public static void main(String[] args) throws Exception {
        verifyAggregationThroughEnginePrepare();
        verifyCompatibleTablesPass();
        verifyConnectionFailureStopsScanning();
        verifyStopAndInterruptionStopScanning();
        verifyUnknownTypeNeedsManualHandling();
        verifyNullabilityChangesGenerateDdl();
        verifyOracleAndDmScripts();
        verifySharedTargetNeedsManualHandling();
        verifyOracleUnknownQueryMetadataThroughPrepare();
        verifyDamengUnknownQueryMetadataThroughPrepare();
        verifyKnownQueryMetadataDifferencesRemainRejected();
        verifyOracleUnknownQueryDoesNotHidePhysicalDifferences();
        verifyDamengUnknownQueryDoesNotHidePhysicalDifferences();
        verifyNativeTypesProduceExecutableDdl();
        verifyMysqlEnumAndSetDefinitions();
        verifyMysqlUnsignedZerofillDefinitions();
        verifyMysqlLobCapacityIsNotDeclaredPrecision();
        verifyFloatingDefinitionsCanBeRechecked();
        verifyPhysicalMetadataSupplementation();
        verifyQueryUnknownNullableIsNotARealDifference();
        verifyOracleIntervalDeclarations();
        System.out.println("[RESULT] ARCHIVE_STRUCTURE_VALIDATION_CHECK_PASSED");
    }

    /**
     * 从引擎准备入口验证父表失败仍扫描其余主表及子表，且不会执行数据操作。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyAggregationThroughEnginePrepare() throws Exception {
        Fixture fixture = differences();
        MigrationTask task = task(fixture, MYSQL);
        Method prepare = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class);
        prepare.setAccessible(true);
        Throwable failure = null;
        try {
            prepare.invoke(new ArchiveEngine(), task, fixture.connection(), MYSQL);
        } catch (InvocationTargetException exception) {
            failure = exception.getCause();
        }
        assertTrue(failure instanceof IllegalArgumentException, "引擎没有以汇总结构错误中止");
        assertContains(failure.getMessage(), "一次性", "失败信息缺少一次性汇总说明");
        assertEquals(EXPECTED_PHYSICAL_TABLES, fixture.physicalTablesRead.size(),
            "前一表或父表差异导致后续物理表未检查");
        String logs = issueLogs(task);
        for (String expected : Arrays.asList(NAME, MISSING_COLUMN, EXTRA_COLUMN, AMOUNT_COLUMN,
            REQUIRED_COLUMN, SECOND_COLUMN, CHILD_COLUMN, MISSING_TARGET)) {
            assertContains(logs, expected, "汇总日志遗漏差异 " + expected);
        }
        String script = script(task);
        for (String expected : Arrays.asList(FIRST_TARGET, SECOND_TARGET, CHILD_TARGET, MISSING_TARGET,
            "ALTER TABLE", "CREATE TABLE", "ADD", "MODIFY", MISSING_COLUMN, EXTRA_COLUMN)) {
            assertContains(script, expected, "汇总建议脚本遗漏 " + expected);
        }
        assertContains(script, "工具不会未经确认执行", "脚本缺少确认要求");
        assertExecutableStatement(script, "ALTER TABLE `" + FIRST_TARGET + "` DROP COLUMN `"
            + EXTRA_COLUMN + "`;", "目标多余字段未生成可执行 DROP COLUMN 建议");
        assertEquals(NO_WRITES, fixture.writes, "结构不通过时出现数据写入或删除");
    }

    /**
     * 验证结构一致时不产生差异脚本且不执行写入。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyCompatibleTablesPass() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 30));
        fixture.add(FIRST_TARGET, integer(ID), text(NAME, 30));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, MYSQL);
        new ArchiveStructureValidator().validate(task, fixture.connection(), MYSQL);
        Object script = task.snapshot(true, 0L).get("structureSyncScript");
        assertTrue(script == null || String.valueOf(script).isEmpty(), "一致结构生成了差异脚本");
        assertEquals(NO_WRITES, fixture.writes, "预检成功后不应自行执行数据操作");
    }

    /**
     * 验证数据库连接异常不能作为普通结构差异继续扫描。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyConnectionFailureStopsScanning() throws Exception {
        Fixture fixture = differences();
        fixture.failTable = FIRST_SOURCE;
        fixture.failure = new SQLException("fixture connection lost", CONNECTION_STATE);
        MigrationTask task = task(fixture, MYSQL);
        Throwable failure = validateFailure(task, fixture, MYSQL);
        assertTrue(failure instanceof SQLException, "数据库断连被当成普通差异");
        assertEquals(FIRST_QUERY, fixture.queries.size(), "断连后仍继续查询其它表");
        assertEquals(NO_WRITES, fixture.writes, "断连后执行了数据操作");
    }

    /**
     * 验证预检前终止和线程中断均直接退出，且不访问数据库。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyStopAndInterruptionStopScanning() throws Exception {
        Fixture stoppedFixture = differences();
        MigrationTask stopped = task(stoppedFixture, MYSQL);
        stopped.stop();
        Throwable stopFailure = validateFailure(stopped, stoppedFixture, MYSQL);
        assertTrue(stopFailure instanceof StopRequestedException, "停止请求没有从结构预检退出");
        assertEquals(NO_WRITES, stoppedFixture.queries.size(), "停止后仍读取数据库");

        Fixture interruptedFixture = differences();
        MigrationTask interrupted = task(interruptedFixture, MYSQL);
        Throwable interruption;
        Thread.currentThread().interrupt();
        try {
            interruption = validateFailure(interrupted, interruptedFixture, MYSQL);
        } finally {
            Thread.interrupted();
        }
        assertTrue(interruption instanceof InterruptedException, "线程中断没有从结构预检退出");
        assertEquals(NO_WRITES, interruptedFixture.queries.size(), "中断后仍读取数据库");
    }

    /**
     * 验证无法安全映射的源字段只给人工处理说明。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyUnknownTypeNeedsManualHandling() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), new Column("SPECIAL_DATA", Types.OTHER,
            UNKNOWN_TYPE, 0, 0, DatabaseMetaData.columnNullable));
        fixture.add(FIRST_TARGET, integer(ID));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, MYSQL);
        assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
            "未知类型差异没有阻止执行");
        String script = script(task);
        assertContains(script, "SPECIAL_DATA", "未知字段未出现在脚本说明中");
        assertContains(script, "人工", "未知字段缺少人工处理说明");
        for (String line : script.split("\\r?\\n")) {
            assertTrue(line.trim().startsWith("--") || !line.contains(UNKNOWN_TYPE),
                "未知源类型被直接插入可执行 DDL");
        }
    }

    /**
     * 三种数据库均按源定义生成可空性双向修改，覆盖单独约束差异和合并长度差异。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyNullabilityChangesGenerateDdl() throws Exception {
        for (String databaseType : Arrays.asList(MYSQL, MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM)) {
            boolean mysql = MYSQL.equals(databaseType);
            String textType = MigrationConstants.DB_ORACLE.equals(databaseType) ? ORACLE_TEXT_TYPE : TEXT_TYPE;
            for (boolean sourceNullable : Arrays.asList(Boolean.TRUE, Boolean.FALSE)) {
                for (boolean changeLength : Arrays.asList(Boolean.FALSE, Boolean.TRUE)) {
                    int sourceLength = changeLength ? 30 : 20;
                    int targetLength = changeLength ? 10 : 20;
                    int sourceRule = sourceNullable ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls;
                    int targetRule = sourceNullable ? DatabaseMetaData.columnNoNulls : DatabaseMetaData.columnNullable;
                    Fixture fixture = new Fixture();
                    fixture.add(FIRST_SOURCE, integer(ID),
                        new Column(NAME, Types.VARCHAR, textType, sourceLength, 0, sourceRule));
                    fixture.add(FIRST_TARGET, integer(ID),
                        new Column(NAME, Types.VARCHAR, textType, targetLength, 0, targetRule));
                    fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
                    MigrationTask task = task(fixture, databaseType);
                    assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
                        databaseType + "可空性差异未阻止数据执行");
                    assertContains(issueLogs(task), NAME, databaseType + "可空性差异未汇总");
                    String quote = mysql ? "`" : "\"";
                    String columnDefinition = quote + NAME + quote + " " + textType + "(" + sourceLength + ")"
                        + (sourceNullable ? " NULL" : " NOT NULL");
                    String statement = "ALTER TABLE " + quote + FIRST_TARGET + quote
                        + (mysql ? " MODIFY COLUMN " + columnDefinition : " MODIFY (" + columnDefinition + ")") + ";";
                    assertExecutableStatement(script(task), statement,
                        databaseType + "未按源定义生成可执行约束变更: sourceNullable=" + sourceNullable
                            + ", changeLength=" + changeLength);
                    assertEquals(NO_WRITES, fixture.writes, "可空性预检执行了DDL或数据操作");
                }
            }
        }
    }

    /**
     * 验证 Oracle、达梦建议脚本的模式引用、原生类型、增列、改列和建表方言。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyOracleAndDmScripts() throws Exception {
        for (String databaseType : Arrays.asList(MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM)) {
            Fixture fixture = new Fixture();
            boolean oracle = MigrationConstants.DB_ORACLE.equals(databaseType);
            String textType = oracle ? "VARCHAR2" : TEXT_TYPE;
            String decimalType = oracle ? "NUMBER" : "DECIMAL";
            int jdbcDecimal = oracle ? Types.NUMERIC : Types.DECIMAL;
            Column id = new Column(ID, jdbcDecimal, decimalType, 12, 0, DatabaseMetaData.columnNoNulls);
            fixture.add(FIRST_SOURCE, id,
                new Column(NAME, Types.VARCHAR, textType, 80, 0, DatabaseMetaData.columnNullable),
                new Column(MISSING_COLUMN, Types.VARCHAR, textType, 20, 0, DatabaseMetaData.columnNullable),
                new Column(AMOUNT_COLUMN, jdbcDecimal, decimalType, 12, 2, DatabaseMetaData.columnNullable),
                new Column(REQUIRED_COLUMN, Types.VARCHAR, textType, 80, 0, DatabaseMetaData.columnNoNulls),
                new Column(TIGHTEN_COLUMN, Types.VARCHAR, textType, 40, 0, DatabaseMetaData.columnNoNulls));
            fixture.add(FIRST_TARGET, id,
                new Column(NAME, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNullable),
                new Column(AMOUNT_COLUMN, jdbcDecimal, decimalType, 6, 0, DatabaseMetaData.columnNullable),
                new Column(REQUIRED_COLUMN, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNoNulls),
                new Column(TIGHTEN_COLUMN, Types.VARCHAR, textType, 10, 0, DatabaseMetaData.columnNullable),
                new Column(EXTRA_COLUMN, Types.VARCHAR, textType, 8, 0, DatabaseMetaData.columnNullable));
            fixture.add(MISSING_SOURCE, id,
                new Column("PAYLOAD", Types.VARCHAR, textType, 24, 0, DatabaseMetaData.columnNullable));
            fixture.tables.put(MISSING_TARGET, null);
            fixture.missingState = "42000";
            fixture.missingCode = oracle ? ORACLE_MISSING_CODE : DM_MISSING_CODE;
            ArchiveTableRequest first = table("fixture." + FIRST_SOURCE, "fixture." + FIRST_TARGET,
                "ROOT", null);
            ArchiveTableRequest missing = table("fixture." + MISSING_SOURCE, "fixture." + MISSING_TARGET,
                "MISSING", null);
            fixture.mains.add(first);
            fixture.mains.add(missing);
            MigrationTask task = task(fixture, databaseType);
            assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
                databaseType + "没有汇总全部结构差异");
            String script = script(task);
            String quotedTarget = "\"FIXTURE\".\"" + FIRST_TARGET + "\"";
            assertContains(script, "ALTER TABLE " + quotedTarget + " ADD (\"" + MISSING_COLUMN
                + "\" " + textType + "(20) NULL);", databaseType + "增列语法或模式引用错误");
            assertContains(script, "ALTER TABLE " + quotedTarget + " MODIFY (\"" + NAME
                + "\" " + textType + "(80));", databaseType + "可空字段扩容重复声明NULL或原生类型错误");
            assertContains(script, "MODIFY (\"" + AMOUNT_COLUMN + "\" " + decimalType + "(12,2));",
                databaseType + "改列丢失数字精度或小数位");
            assertContains(script, "MODIFY (\"" + REQUIRED_COLUMN + "\" " + textType + "(80));",
                databaseType + "原已非空字段扩容重复声明NOT NULL");
            assertContains(script, "MODIFY (\"" + TIGHTEN_COLUMN + "\" " + textType + "(40) NOT NULL);",
                databaseType + "需要收紧非空约束时遗漏NOT NULL");
            assertContains(script, "CREATE TABLE \"FIXTURE\".\"" + MISSING_TARGET + "\" (",
                databaseType + "缺表错误号未识别或建表引用错误");
            assertContains(script, "\"" + ID + "\" " + decimalType + "(12,0) NOT NULL",
                databaseType + "建表丢失源字段非空约束");
            assertExecutableStatement(script, "ALTER TABLE " + quotedTarget + " DROP COLUMN \""
                + EXTRA_COLUMN + "\";", databaseType + "目标多余字段未生成 DROP COLUMN");
            assertEquals(NO_WRITES, fixture.writes, databaseType + "预检执行了DDL或数据写入");
        }
    }

    /**
     * 同一物理归档表通过默认库和显式库名重复配置时，不给出互相冲突的可执行 DDL。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifySharedTargetNeedsManualHandling() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 40));
        fixture.add(SECOND_SOURCE, integer(ID), text(NAME, 80));
        fixture.add(FIRST_TARGET, integer(ID), text(NAME, 5));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "FIRST", null));
        fixture.mains.add(table(SECOND_SOURCE, "fixture_db." + FIRST_TARGET, "SECOND", null));
        MigrationTask task = task(fixture, MYSQL);
        assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
            "重复物理归档目标未阻止执行");
        String script = script(task);
        assertContains(script, FIRST_TARGET, "重复目标未出现在建议脚本");
        for (String line : script.split("\\r?\\n")) {
            String statement = line.trim();
            assertTrue(statement.isEmpty() || statement.startsWith("--"),
                "重复归档目标仍产生可执行 DDL: " + line);
        }
        assertTrue(fixture.physicalTablesRead.containsKey(SECOND_SOURCE), "目标重复导致后续源表不再检查");
        assertEquals(NO_WRITES, fixture.writes, "目标重复时执行了数据操作");
    }

    /**
     * Oracle 业务派生查询报告未知 NUMBER 精度及可空性时，两阶段校验均通过且仍不执行写删。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyOracleUnknownQueryMetadataThroughPrepare() throws Exception {
        for (Column queryId : Arrays.asList(
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 0, DatabaseMetaData.columnNullable))) {
            Fixture fixture = oracleQueryFixture(queryId);
            MigrationTask task = task(fixture, MigrationConstants.DB_ORACLE);
            Object prepared = prepare(task, fixture, MigrationConstants.DB_ORACLE);
            assertTrue(prepared != null, "Oracle 未知派生精度或可空性在最终归档准备阶段仍被拦截");
            assertEquals(EXPECTED_METADATA_PHASES, fixture.businessMetadataReads,
                "没有同时检查结构预检和引擎最终查询元数据");
            assertEquals(FIRST_QUERY, fixture.statisticsQueries, "完整归档准备未执行键范围校验");
            assertTrue(issueLogs(task).isEmpty(), "合法的 Oracle 派生元数据仍被报为结构差异");
            for (String sql : fixture.queries) {
                if (!sql.contains(BUSINESS_QUERY_MARKER)) {
                    assertTrue(!sql.contains(DERIVED_QUERY_PREFIX),
                        "Oracle 物理表元数据不能使用派生包装，避免丢失真实精度: " + sql);
                }
            }
            assertEquals(NO_WRITES, fixture.writes, "完整准备阶段执行了DDL或数据写删");
        }
    }

    /**
     * 达梦多字段派生查询的未知数值精度和可空性在两个准备阶段都能兼容。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyDamengUnknownQueryMetadataThroughPrepare() throws Exception {
        for (String nativeType : Arrays.asList(NUMBER_TYPE, "DECIMAL")) {
            for (int jdbcType : Arrays.asList(Types.NUMERIC, Types.DECIMAL)) {
                for (String queryType : Arrays.asList(NUMBER_TYPE, "DECIMAL", "NUMERIC", "DEC")) {
                    for (int queryJdbcType : Arrays.asList(Types.NUMERIC, Types.DECIMAL)) {
                        Fixture fixture = damengQueryFixture(nativeType, jdbcType, queryType, queryJdbcType);
                        MigrationTask task = task(fixture, MigrationConstants.DB_DM);
                        Object prepared = prepare(task, fixture, MigrationConstants.DB_DM);
                        assertTrue(prepared != null, "达梦未知数值精度在最终归档准备阶段仍被拦截: "
                            + nativeType + ", jdbcType=" + jdbcType + ", queryType=" + queryType
                            + ", queryJdbcType=" + queryJdbcType);
                        assertEquals(EXPECTED_METADATA_PHASES, fixture.businessMetadataReads,
                            "达梦没有同时检查结构预检和最终查询元数据");
                        assertEquals(FIRST_QUERY, fixture.statisticsQueries, "达梦完整归档准备未校验键范围");
                        assertTrue(issueLogs(task).isEmpty(), "达梦有效的多字段派生元数据仍被报为差异");
                        assertSingleDamengUnknownPrecisionNotice(task);
                        Object script = task.snapshot(true, 0L).get("structureSyncScript");
                        assertTrue(script == null || String.valueOf(script).isEmpty(),
                            "达梦有效查询元数据生成了不必要的改表脚本");
                        for (String sql : fixture.queries) {
                            if (!sql.contains(BUSINESS_QUERY_MARKER)) {
                                assertTrue(!sql.contains(DERIVED_QUERY_PREFIX),
                                    "达梦物理表元数据不能被派生包装丢失真实精度: " + sql);
                            }
                        }
                        assertEquals(NO_WRITES, fixture.writes, "达梦归档准备阶段执行了DDL或数据写删");
                    }
                }
            }
        }
    }

    /**
     * 兼容未知精度只在节点级说明一次，并列出兼容字段数，避免逐字段重复日志。
     * Params: task 当前达梦归档任务。
     * Returns: 无。
     */
    @SuppressWarnings("unchecked")
    private static void assertSingleDamengUnknownPrecisionNotice(MigrationTask task) {
        int notices = 0;
        List<Map<String, Object>> logs = (List<Map<String, Object>>)task.snapshot(true, 0L).get("logs");
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.contains(DM_UNKNOWN_PRECISION_NOTICE)) {
                notices++;
                assertContains(message, "共 " + EXPECTED_DM_NUMERIC_COLUMNS + " 列", "未知精度兼容字段数量不准确");
            }
        }
        assertEquals(FIRST_QUERY, notices, "同一达梦节点的未知精度兼容说明未按节点汇总一次");
    }

    /**
     * 已知精度、小数位、原生类型及 JDBC 类型错误不能套用未知元数据兼容规则。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyKnownQueryMetadataDifferencesRemainRejected() throws Exception {
        List<Column> invalid = Arrays.asList(
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 19, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 1, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 0, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, "DECIMAL", 0, -127, DatabaseMetaData.columnNullable),
            new Column(ID, Types.DECIMAL, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable),
            new Column(ID, Types.VARCHAR, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable));
        for (Column queryColumn : invalid) {
            assertKnownQueryMetadataRejected(queryColumn, MigrationConstants.DB_ORACLE);
        }
        List<Column> invalidDameng = Arrays.asList(
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 19, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 1, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 0, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.DECIMAL, "DECIMAL", 19, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.DECIMAL, "DECIMAL", 20, 1, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, "FLOAT", 0, -127, DatabaseMetaData.columnNullable),
            new Column(ID, Types.FLOAT, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable),
            new Column(ID, Types.VARCHAR, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable));
        for (Column queryColumn : invalidDameng) {
            assertKnownQueryMetadataRejected(queryColumn, MigrationConstants.DB_DM);
        }
        Column oracleUnknown = new Column(ID, Types.NUMERIC, NUMBER_TYPE, 0, -127,
            DatabaseMetaData.columnNullable);
        assertKnownQueryMetadataRejected(oracleUnknown, MYSQL);
    }

    /**
     * 同时验证汇总预检及引擎最终字段校验拒绝真实的查询字段差异。
     * Params: queryColumn 查询 ID 字段；databaseType 方言。
     * Returns: 无。
     */
    private static void assertKnownQueryMetadataRejected(Column queryColumn, String databaseType) throws Exception {
        Fixture fixture = oracleQueryFixture(queryColumn);
        MigrationTask task = task(fixture, databaseType);
        assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
            databaseType + "已知查询字段定义错误被预检放过");
        assertContains(issueLogs(task), ID, databaseType + "已知字段定义错误没有汇总");
        assertFinalQueryValidationRejected(queryColumn, databaseType);
    }

    /**
     * Oracle 查询元数据兼容不能掩盖源物理表与归档物理表的真实差异。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyOracleUnknownQueryDoesNotHidePhysicalDifferences() throws Exception {
        Fixture fixture = oracleQueryFixture(
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 0, -127, DatabaseMetaData.columnNullable));
        fixture.add(FIRST_TARGET,
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 19, 0, DatabaseMetaData.columnNoNulls),
            new Column(NAME, Types.VARCHAR, ORACLE_TEXT_TYPE, 30, 0, DatabaseMetaData.columnNoNulls));
        MigrationTask task = task(fixture, MigrationConstants.DB_ORACLE);
        assertTrue(validateFailure(task, fixture, MigrationConstants.DB_ORACLE) instanceof IllegalArgumentException,
            "查询未知精度兼容规则掩盖了物理字段的真实精度差异");
        assertContains(issueLogs(task), ID, "物理字段精度差异未汇总");
        assertExecutableStatement(script(task), "ALTER TABLE \"" + FIRST_TARGET + "\" MODIFY (\""
            + ID + "\" NUMBER(20,0));", "物理字段精度差异没有生成准确的建议 DDL");
        assertEquals(NO_WRITES, fixture.writes, "物理结构不通过时执行了数据变更");
    }

    /**
     * 达梦派生查询兼容不改变物理表精度和非空约束的严格比对及 DDL 建议。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyDamengUnknownQueryDoesNotHidePhysicalDifferences() throws Exception {
        List<Column> targets = Arrays.asList(
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 19, 0, DatabaseMetaData.columnNoNulls),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 0, DatabaseMetaData.columnNullable),
            new Column(ID, Types.NUMERIC, NUMBER_TYPE, 19, 0, DatabaseMetaData.columnNullable));
        for (Column targetId : targets) {
            Fixture fixture = damengQueryFixture(NUMBER_TYPE, Types.NUMERIC);
            List<Column> targetColumns = new ArrayList<>(fixture.tables.get(FIRST_TARGET));
            targetColumns.set(0, targetId);
            fixture.add(FIRST_TARGET, targetColumns.toArray(new Column[targetColumns.size()]));
            MigrationTask task = task(fixture, MigrationConstants.DB_DM);
            assertTrue(validateFailure(task, fixture, MigrationConstants.DB_DM) instanceof IllegalArgumentException,
                "达梦未知查询元数据掩盖了物理字段的真实精度或非空差异");
            assertContains(issueLogs(task), ID, "达梦真实物理字段差异未汇总");
            String nullableClause = targetId.nullable == DatabaseMetaData.columnNullable ? " NOT NULL" : "";
            assertExecutableStatement(script(task), "ALTER TABLE \"" + FIRST_TARGET + "\" MODIFY (\""
                + ID + "\" NUMBER(20,0)" + nullableClause + ");", "达梦物理差异未生成准确的建议 DDL");
            assertEquals(NO_WRITES, fixture.writes, "达梦物理结构失败时执行了数据变更");
        }
    }

    /**
     * 已有完整原生类型信息可以直接用于归档增列，不能仅因不在旧白名单中退为人工说明。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyNativeTypesProduceExecutableDdl() throws Exception {
        verifyNativeTypeAdditions(MYSQL, Arrays.asList(
            new Column("YEAR_VALUE", Types.DATE, "YEAR", 4, 0, DatabaseMetaData.columnNullable),
            new Column("BIT_VALUE", Types.BIT, "BIT(8)", 8, 0, DatabaseMetaData.columnNullable),
            new Column("INT_VALUE", Types.INTEGER, "INT(11)", 10, 0, DatabaseMetaData.columnNullable),
            new Column("DEC_VALUE", Types.DECIMAL, "DECIMAL(10,2) UNSIGNED", 10, 2,
                DatabaseMetaData.columnNullable),
            new Column("NATIVE_VALUE", Types.OTHER, "CUSTOM_NATIVE", 0, 0, DatabaseMetaData.columnNullable)));
        verifyNativeTypeAdditions(MigrationConstants.DB_ORACLE, Arrays.asList(
            new Column("CHAR_VALUE", Types.VARCHAR, "VARCHAR2(80 CHAR)", 80, 0,
                DatabaseMetaData.columnNullable),
            new Column("BYTE_VALUE", Types.VARCHAR, "VARCHAR2(80 BYTE)", 80, 0,
                DatabaseMetaData.columnNullable),
            new Column("RID_VALUE", Types.ROWID, "UROWID(4000)", 4000, 0,
                DatabaseMetaData.columnNullable),
            new Column("NATIVE_VALUE", Types.OTHER, "CUSTOM_NUMBER(12,3)", 12, 3,
                DatabaseMetaData.columnNullable)));
        verifyNativeTypeAdditions(MigrationConstants.DB_DM, Arrays.asList(
            new Column("NATIVE_VALUE", Types.OTHER, "APP.CUSTOM_NATIVE", 0, 0,
                DatabaseMetaData.columnNullable)));
    }

    /**
     * 检查完整原生类型是否出现在未注释的生产建议 SQL 中。
     * Params: databaseType 方言；nativeColumns 含完整原生类型的字段。
     * Returns: 无。
     */
    private static void verifyNativeTypeAdditions(String databaseType, List<Column> nativeColumns)
        throws Exception {
        Fixture fixture = new Fixture();
        List<Column> sourceColumns = new ArrayList<>();
        sourceColumns.add(integer(ID));
        sourceColumns.addAll(nativeColumns);
        fixture.add(FIRST_SOURCE, sourceColumns.toArray(new Column[sourceColumns.size()]));
        fixture.add(FIRST_TARGET, integer(ID));
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        MigrationTask task = task(fixture, databaseType);
        assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
            databaseType + "原生字段缺失没有阻止数据执行");
        boolean mysql = MYSQL.equals(databaseType);
        String quote = mysql ? "`" : "\"";
        for (Column column : nativeColumns) {
            String definition = quote + column.name + quote + " " + column.typeName + " NULL";
            String statement = "ALTER TABLE " + quote + FIRST_TARGET + quote
                + (mysql ? " ADD COLUMN " + definition : " ADD (" + definition + ")") + ";";
            assertExecutableStatement(script(task), statement,
                databaseType + "完整原生类型没有生成可执行建议: " + column.typeName);
        }
        assertEquals(NO_WRITES, fixture.writes, "原生类型预检执行了DDL或数据写入");
    }

    /**
     * MySQL 即使把枚举投射为 CHAR，也按完整成员识别差异并保留大小写、转义和顺序。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyMysqlEnumAndSetDefinitions() throws Exception {
        List<String> sourceTypes = Arrays.asList("enum('Blue','O\\'Reilly','Back\\\\Slash')", "set('Red','BLUE')");
        List<String> targetTypes = Arrays.asList("enum('blue','O\\'Reilly','Back\\\\Slash')", "set('Red','GOLD')");
        Column projected = new Column("STATE", Types.CHAR, "CHAR", 32, 0, DatabaseMetaData.columnNullable);
        for (int i = 0; i < sourceTypes.size(); i++) {
            String nativeBase = sourceTypes.get(i).substring(0, sourceTypes.get(i).indexOf('('))
                .toUpperCase(Locale.ROOT);
            Fixture fixture = new Fixture();
            fixture.add(FIRST_SOURCE, integer(ID), projected);
            fixture.add(FIRST_TARGET, integer(ID), projected);
            fixture.catalogDefinitions.put(FIRST_SOURCE, Arrays.asList(integer(ID),
                new Column(projected.name, Types.CHAR, nativeBase, 32, 0, projected.nullable, sourceTypes.get(i))));
            fixture.catalogDefinitions.put(FIRST_TARGET, Arrays.asList(integer(ID),
                new Column(projected.name, Types.CHAR, nativeBase, 32, 0, projected.nullable, targetTypes.get(i))));
            addMainWithCursor(fixture);
            MigrationTask task = task(fixture, MYSQL);
            assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
                "相同投射长度的 ENUM/SET 成员差异未拦截");
            assertExecutableStatement(script(task), "ALTER TABLE `" + FIRST_TARGET + "` MODIFY COLUMN `STATE` "
                + sourceTypes.get(i) + " NULL;", "ENUM/SET MODIFY 丢失原成员大小写或转义");
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
            assertTrue(prepare(task(fixture, MYSQL), fixture, MYSQL) != null,
                "ENUM/SET 成员同步后不能通过完整两阶段归档准备");
            assertEquals(NO_WRITES, fixture.writes, "ENUM/SET 预检执行了结构或数据变更");
        }
        for (boolean missingTable : Arrays.asList(Boolean.FALSE, Boolean.TRUE)) {
            Fixture fixture = new Fixture();
            Column enumColumn = new Column("ENUM_VALUE", Types.CHAR, "CHAR", 32, 0,
                DatabaseMetaData.columnNullable, sourceTypes.get(0));
            Column setColumn = new Column("SET_VALUE", Types.CHAR, "CHAR", 32, 0,
                DatabaseMetaData.columnNullable, sourceTypes.get(1));
            fixture.add(FIRST_SOURCE, integer(ID), enumColumn, setColumn);
            fixture.add(FIRST_TARGET, integer(ID));
            if (missingTable) {
                fixture.tables.put(FIRST_TARGET, null);
            }
            fixture.catalogDefinitions.put(FIRST_SOURCE, Arrays.asList(integer(ID),
                new Column(enumColumn.name, Types.CHAR, "ENUM", 32, 0, enumColumn.nullable, enumColumn.nativeType),
                new Column(setColumn.name, Types.CHAR, "SET", 32, 0, setColumn.nullable, setColumn.nativeType)));
            addMainWithCursor(fixture);
            MigrationTask task = task(fixture, MYSQL);
            assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
                "枚举缺表或缺列没有阻止数据执行");
            if (missingTable) {
                assertExecutableStatement(script(task), "CREATE TABLE `" + FIRST_TARGET + "` (",
                    "完整 ENUM/SET 定义未生成建表建议");
                assertContains(script(task), "`ENUM_VALUE` " + enumColumn.nativeType + " NULL",
                    "ENUM CREATE 丢失原文定义");
                assertContains(script(task), "`SET_VALUE` " + setColumn.nativeType + " NULL",
                    "SET CREATE 丢失原文定义");
            } else {
                assertExecutableStatement(script(task), "ALTER TABLE `" + FIRST_TARGET + "` ADD COLUMN `ENUM_VALUE` "
                    + enumColumn.nativeType + " NULL;", "ENUM ADD 丢失原文定义");
                assertExecutableStatement(script(task), "ALTER TABLE `" + FIRST_TARGET + "` ADD COLUMN `SET_VALUE` "
                    + setColumn.nativeType + " NULL;", "SET ADD 丢失原文定义");
            }
            fixture.tables.put(FIRST_TARGET, fixture.tables.get(FIRST_SOURCE));
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
            assertTrue(prepare(task(fixture, MYSQL), fixture, MYSQL) != null,
                "枚举增列或建表后不能通过两阶段准备");
            assertEquals(NO_WRITES, fixture.writes, "枚举增列或建表预检执行了DDL");
        }
    }

    /**
     * 真实数值字段的 UNSIGNED ZEROFILL 修饰必须用于增列、改列和建表，不能被比较拦截后只留注释。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyMysqlUnsignedZerofillDefinitions() throws Exception {
        Column integerValue = new Column("INT_VALUE", Types.INTEGER, "INT UNSIGNED", 10, 0,
            DatabaseMetaData.columnNullable, "int(10) unsigned zerofill");
        Column decimalValue = new Column("DEC_VALUE", Types.DECIMAL, "DECIMAL UNSIGNED", 10, 2,
            DatabaseMetaData.columnNullable, "decimal(10,2) unsigned zerofill");
        List<Column> sourceCatalog = Arrays.asList(integer(ID),
            new Column(integerValue.name, Types.INTEGER, "INT", 10, 0, integerValue.nullable, integerValue.nativeType),
            new Column(decimalValue.name, Types.DECIMAL, "DECIMAL", 10, 2, decimalValue.nullable, decimalValue.nativeType));
        for (String operation : Arrays.asList("MODIFY", "ADD", "CREATE")) {
            Fixture fixture = new Fixture();
            fixture.add(FIRST_SOURCE, integer(ID), integerValue, decimalValue);
            fixture.catalogDefinitions.put(FIRST_SOURCE, sourceCatalog);
            switch (operation) {
                case "MODIFY":
                    fixture.add(FIRST_TARGET, integer(ID), integerValue, decimalValue);
                    fixture.catalogDefinitions.put(FIRST_TARGET, Arrays.asList(integer(ID),
                        new Column(integerValue.name, Types.INTEGER, "INT", 10, 0,
                            integerValue.nullable, "int(10) unsigned"),
                        new Column(decimalValue.name, Types.DECIMAL, "DECIMAL", 10, 2,
                            decimalValue.nullable, "decimal(10,2) unsigned")));
                    break;
                case "ADD":
                    fixture.add(FIRST_TARGET, integer(ID));
                    fixture.catalogDefinitions.put(FIRST_TARGET, Arrays.asList(integer(ID)));
                    break;
                case "CREATE":
                    fixture.tables.put(FIRST_TARGET, null);
                    break;
                default:
                    throw new AssertionError("未定义的 ZEROFILL 验证场景");
            }
            addMainWithCursor(fixture);
            MigrationTask task = task(fixture, MYSQL);
            assertTrue(validateFailure(task, fixture, MYSQL) instanceof IllegalArgumentException,
                "MySQL " + operation + " 未报告真实 ZEROFILL 字段差异");
            String ddl = script(task);
            for (Column column : Arrays.asList(integerValue, decimalValue)) {
                String definition = "`" + column.name + "` " + column.nativeType.toUpperCase(Locale.ROOT) + " NULL";
                switch (operation) {
                    case "MODIFY":
                        assertExecutableStatement(ddl, "ALTER TABLE `" + FIRST_TARGET + "` MODIFY COLUMN "
                            + definition + ";", "MODIFY 遗漏数值 ZEROFILL 修饰");
                        break;
                    case "ADD":
                        assertExecutableStatement(ddl, "ALTER TABLE `" + FIRST_TARGET + "` ADD COLUMN "
                            + definition + ";", "ADD 遗漏数值 ZEROFILL 修饰");
                        break;
                    case "CREATE":
                        assertExecutableStatement(ddl, "CREATE TABLE `" + FIRST_TARGET + "` (",
                            "ZEROFILL 源字段未生成建表建议");
                        assertContains(ddl, definition, "CREATE 遗漏数值 ZEROFILL 修饰");
                        break;
                    default:
                        throw new AssertionError("未定义的 ZEROFILL 脚本验证场景");
                }
            }
            fixture.tables.put(FIRST_TARGET, fixture.tables.get(FIRST_SOURCE));
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
            assertTrue(prepare(task(fixture, MYSQL), fixture, MYSQL) != null,
                "MySQL ZEROFILL 字段同步后两阶段准备仍不能通过: " + operation);
            assertEquals(NO_WRITES, fixture.writes, "ZEROFILL 结构预检执行了DDL或数据变更");
        }
    }

    /**
     * MySQL 同型 TEXT/BLOB 的字符集容量投射不构成声明差异，真实非空和 VARCHAR 长度差异仍报告。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyMysqlLobCapacityIsNotDeclaredPrecision() throws Exception {
        for (String type : Arrays.asList("TEXT", "BLOB")) {
            int jdbcType = type.equals("TEXT") ? Types.LONGVARCHAR : Types.BLOB;
            Column source = new Column("PAYLOAD", jdbcType, type, 65535, 0,
                DatabaseMetaData.columnNullable, type.toLowerCase(Locale.ROOT));
            Column target = new Column("PAYLOAD", jdbcType, type, 16383, 0,
                DatabaseMetaData.columnNullable, type.toLowerCase(Locale.ROOT));
            Fixture fixture = new Fixture();
            fixture.add(FIRST_SOURCE, integer(ID), source);
            fixture.add(FIRST_TARGET, integer(ID), target);
            fixture.catalogDefinitions.put(FIRST_SOURCE, fixture.tables.get(FIRST_SOURCE));
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.tables.get(FIRST_TARGET));
            addMainWithCursor(fixture);
            MigrationTask compatible = task(fixture, MYSQL);
            assertTrue(prepare(compatible, fixture, MYSQL) != null,
                "同型 MySQL " + type + " 因派生容量不同在两阶段准备中被误拦截");
            assertTrue(issueLogs(compatible).isEmpty(), "TEXT/BLOB 容量投射被报为真实字段差异");
            fixture.add(FIRST_TARGET, integer(ID), new Column("PAYLOAD", jdbcType, type, 16383, 0,
                DatabaseMetaData.columnNoNulls, type.toLowerCase(Locale.ROOT)));
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.tables.get(FIRST_TARGET));
            MigrationTask nullableDifference = task(fixture, MYSQL);
            assertTrue(validateFailure(nullableDifference, fixture, MYSQL) instanceof IllegalArgumentException,
                "同型 TEXT/BLOB 容量兼容掩盖了真实非空差异");
            assertExecutableStatement(script(nullableDifference), "ALTER TABLE `" + FIRST_TARGET
                + "` MODIFY COLUMN `PAYLOAD` " + type + " NULL;", "真实 TEXT/BLOB 非空差异没有建议 DDL");
            assertEquals(NO_WRITES, fixture.writes, "TEXT/BLOB 字段预检执行了结构或数据变更");
        }
        assertTrue(!ArchiveStructureValidator.samePhysicalPrecision(MYSQL, 30, 10, "varchar(30)", "varchar(30)"),
            "仅容量投射兼容不应扩展到 VARCHAR 的真实声明精度");
        Fixture varcharFixture = new Fixture();
        varcharFixture.add(FIRST_SOURCE, integer(ID), new Column(NAME, Types.VARCHAR, TEXT_TYPE, 30, 0,
            DatabaseMetaData.columnNullable, "varchar(30)"));
        varcharFixture.add(FIRST_TARGET, integer(ID), new Column(NAME, Types.VARCHAR, TEXT_TYPE, 10, 0,
            DatabaseMetaData.columnNullable, "varchar(10)"));
        varcharFixture.catalogDefinitions.put(FIRST_SOURCE, varcharFixture.tables.get(FIRST_SOURCE));
        varcharFixture.catalogDefinitions.put(FIRST_TARGET, varcharFixture.tables.get(FIRST_TARGET));
        addMainWithCursor(varcharFixture);
        MigrationTask varcharDifference = task(varcharFixture, MYSQL);
        assertTrue(validateFailure(varcharDifference, varcharFixture, MYSQL) instanceof IllegalArgumentException,
            "MySQL VARCHAR 长度差异被容量兼容规则错误忽略");
        assertExecutableStatement(script(varcharDifference), "ALTER TABLE `" + FIRST_TARGET
            + "` MODIFY COLUMN `NAME` VARCHAR(30) NULL;", "VARCHAR 真实长度差异没有 MODIFY 建议");
        assertEquals(NO_WRITES, varcharFixture.writes, "VARCHAR 预检执行了结构或数据变更");
    }

    /**
     * 浮点真实精度必须写入建议，协议 scale=31 不能拼成无效 FLOAT(M,31)。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyFloatingDefinitionsCanBeRechecked() throws Exception {
        verifyFloatingPair(MigrationConstants.DB_ORACLE,
            new Column("RATE", Types.FLOAT, "FLOAT", 20, -127, DatabaseMetaData.columnNullable),
            new Column("RATE", Types.FLOAT, "FLOAT", 126, -127, DatabaseMetaData.columnNullable), "FLOAT(20)");
        verifyFloatingPair(MigrationConstants.DB_ORACLE,
            new Column("RATE", Types.FLOAT, "FLOAT", 126, -127, DatabaseMetaData.columnNullable),
            new Column("RATE", Types.FLOAT, "FLOAT", 20, -127, DatabaseMetaData.columnNullable), "FLOAT(126)");
        Column fixed = new Column("RATE", Types.FLOAT, "FLOAT", 10, 2, DatabaseMetaData.columnNullable, "float(10,2)");
        Column protocol = new Column("RATE", Types.FLOAT, "FLOAT", 12, 31,
            DatabaseMetaData.columnNullable, "float");
        verifyFloatingPair(MYSQL, fixed, protocol, "FLOAT(10,2)");
        verifyFloatingPair(MYSQL, protocol, fixed, "FLOAT");
    }

    /**
     * 对浮点差异校验建议原文，并模拟目标应用源定义后通过完整准备。
     * Params: databaseType 方言；source/target 两侧 RATE 字段；expectedType 建议类型。
     * Returns: 无。
     */
    private static void verifyFloatingPair(String databaseType, Column source, Column target, String expectedType)
        throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), source);
        fixture.add(FIRST_TARGET, integer(ID), target);
        fixture.catalogDefinitions.put(FIRST_SOURCE, fixture.tables.get(FIRST_SOURCE));
        fixture.catalogDefinitions.put(FIRST_TARGET, fixture.tables.get(FIRST_TARGET));
        addMainWithCursor(fixture);
        MigrationTask task = task(fixture, databaseType);
        assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
            databaseType + " FLOAT 真实精度差异没有拦截");
        boolean mysql = MYSQL.equals(databaseType);
        String statement = mysql ? "ALTER TABLE `" + FIRST_TARGET + "` MODIFY COLUMN `RATE` " + expectedType + " NULL;"
            : "ALTER TABLE \"" + FIRST_TARGET + "\" MODIFY (\"RATE\" " + expectedType + ");";
        assertExecutableStatement(script(task), statement, databaseType + "浮点建议丢失真实精度或误用了协议scale");
        fixture.tables.put(FIRST_TARGET, fixture.tables.get(FIRST_SOURCE));
        fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
        assertTrue(prepare(task(fixture, databaseType), fixture, databaseType) != null,
            databaseType + "浮点同步后完整归档准备仍失败");
        assertEquals(NO_WRITES, fixture.writes, "浮点预检执行了结构或数据写入");
    }

    /**
     * 三种数据库从受限物理目录补齐缺类型和未知可空性，建议和最终准备必须一致。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyPhysicalMetadataSupplementation() throws Exception {
        for (String databaseType : Arrays.asList(MYSQL, MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM)) {
            Fixture fixture = new Fixture();
            boolean mysql = MYSQL.equals(databaseType);
            String type = MigrationConstants.DB_ORACLE.equals(databaseType) ? ORACLE_TEXT_TYPE : TEXT_TYPE;
            Column incomplete = new Column(NAME, mysql ? Types.VARCHAR : Types.OTHER, "", 0, 0,
                DatabaseMetaData.columnNullableUnknown);
            Column actualSource = new Column(NAME, Types.VARCHAR, type, 30, 0,
                DatabaseMetaData.columnNullable, mysql ? "varchar(30)" : null);
            Column actualTarget = new Column(NAME, Types.VARCHAR, type, 10, 0,
                DatabaseMetaData.columnNoNulls, mysql ? "varchar(10)" : null);
            fixture.add(FIRST_SOURCE, integer(ID), incomplete);
            fixture.add(FIRST_TARGET, integer(ID), incomplete);
            fixture.catalogDefinitions.put(FIRST_SOURCE, Arrays.asList(integer(ID), actualSource));
            fixture.catalogDefinitions.put(FIRST_TARGET, Arrays.asList(integer(ID), actualTarget));
            fixture.businessColumns = Arrays.asList(integer(ID), actualSource);
            addMainWithCursor(fixture);
            MigrationTask task = task(fixture, databaseType);
            assertTrue(validateFailure(task, fixture, databaseType) instanceof IllegalArgumentException,
                databaseType + "目录补齐后真实长度/可空差异未拦截");
            String statement = mysql ? "ALTER TABLE `" + FIRST_TARGET + "` MODIFY COLUMN `NAME` VARCHAR(30) NULL;"
                : "ALTER TABLE \"" + FIRST_TARGET + "\" MODIFY (\"NAME\" " + type + "(30) NULL);";
            assertExecutableStatement(script(task), statement, databaseType + "补齐的真实物理定义未用于生成DDL");
            fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
            assertTrue(prepare(task(fixture, databaseType), fixture, databaseType) != null,
                databaseType + "最终准备未使用相同补齐定义");
            assertEquals(NO_WRITES, fixture.writes, "物理目录补齐期间执行了结构或数据变更");
        }
    }

    /**
     * 查询未知可空性不构成真实字段差异，MySQL 明确已知的可空性冲突仍须拒绝。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyQueryUnknownNullableIsNotARealDifference() throws Exception {
        for (String databaseType : Arrays.asList(MYSQL, MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM)) {
            Fixture fixture = new Fixture();
            Column name = new Column(NAME, Types.VARCHAR, TEXT_TYPE, 20, 0, DatabaseMetaData.columnNoNulls);
            fixture.add(FIRST_SOURCE, integer(ID), name);
            fixture.add(FIRST_TARGET, integer(ID), name);
            fixture.businessColumns = Arrays.asList(
                new Column(ID, Types.INTEGER, INTEGER_TYPE, 10, 0, DatabaseMetaData.columnNullableUnknown),
                new Column(NAME, Types.VARCHAR, TEXT_TYPE, 20, 0, DatabaseMetaData.columnNullableUnknown));
            addMainWithCursor(fixture);
            assertTrue(prepare(task(fixture, databaseType), fixture, databaseType) != null,
                databaseType + "查询未知可空性被当成真实差异");
            if (MYSQL.equals(databaseType)) {
                fixture.businessColumns = Arrays.asList(integer(ID),
                    new Column(NAME, Types.VARCHAR, TEXT_TYPE, 20, 0, DatabaseMetaData.columnNullable));
                assertTrue(validateFailure(task(fixture, MYSQL), fixture, MYSQL) instanceof IllegalArgumentException,
                    "MySQL 已知查询可空性冲突被忽略");
                assertFinalQueryValidationRejected(new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 0,
                    DatabaseMetaData.columnNullable), MYSQL);
            }
            assertEquals(NO_WRITES, fixture.writes, "查询可空性校验执行了数据变更");
        }
    }

    /**
     * Oracle INTERVAL 只使用目录真实声明精度；驱动 5/11 字节长度不能变成 YEAR/DAY 精度。
     * Params: 无。
     * Returns: 无。
     */
    private static void verifyOracleIntervalDeclarations() throws Exception {
        for (String operation : Arrays.asList("MODIFY", "ADD", "CREATE")) {
            verifyOracleIntervalOperation(operation, "INTERVAL YEAR(3) TO MONTH", "INTERVAL DAY(4) TO SECOND(7)");
        }
        verifyOracleIntervalOperation("MODIFY", "INTERVAL YEAR(0) TO MONTH", "INTERVAL DAY(0) TO SECOND(0)");
        Fixture incomplete = new Fixture();
        Column year = intervalYearColumn(null);
        Column day = intervalDayColumn(null);
        incomplete.add(FIRST_SOURCE, integer(ID), year, day);
        incomplete.add(FIRST_TARGET, integer(ID));
        incomplete.catalogDefinitions.put(FIRST_SOURCE, incomplete.tables.get(FIRST_SOURCE));
        addMainWithCursor(incomplete);
        MigrationTask task = task(incomplete, MigrationConstants.DB_ORACLE);
        assertTrue(validateFailure(task, incomplete, MigrationConstants.DB_ORACLE) instanceof IllegalArgumentException,
            "缺真实 INTERVAL 声明的字段没有报告缺列");
        for (String line : script(task).split("\\r?\\n")) {
            String statement = line.trim();
            assertTrue(statement.startsWith("--") || !statement.contains(INTERVAL_DECLARATION_PREFIX),
                "把驱动字节长度或默认精度虚构成可执行 INTERVAL 定义: " + line);
        }
        assertEquals(NO_WRITES, incomplete.writes, "缺失 INTERVAL 目录定义时执行了结构或数据变更");
    }

    /**
     * 验证 INTERVAL 三种建议路径，并在模拟目录同步后重新完成两阶段准备。
     * Params: operation MODIFY/ADD/CREATE；yearDeclaration 真实 YEAR 声明；dayDeclaration 真实 DAY 声明。
     * Returns: 无。
     */
    private static void verifyOracleIntervalOperation(String operation, String yearDeclaration,
        String dayDeclaration) throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), intervalYearColumn(null), intervalDayColumn(null));
        fixture.catalogDefinitions.put(FIRST_SOURCE, Arrays.asList(integer(ID),
            intervalYearColumn(yearDeclaration), intervalDayColumn(dayDeclaration)));
        switch (operation) {
            case "MODIFY":
                fixture.add(FIRST_TARGET, integer(ID), intervalYearColumn(null), intervalDayColumn(null));
                fixture.catalogDefinitions.put(FIRST_TARGET, Arrays.asList(integer(ID),
                    intervalYearColumn("INTERVAL YEAR(2) TO MONTH"),
                    intervalDayColumn("INTERVAL DAY(2) TO SECOND(6)")));
                break;
            case "ADD":
                fixture.add(FIRST_TARGET, integer(ID));
                break;
            case "CREATE":
                fixture.tables.put(FIRST_TARGET, null);
                fixture.missingState = "42000";
                fixture.missingCode = ORACLE_MISSING_CODE;
                break;
            default:
                throw new AssertionError("未定义的 INTERVAL 验证场景");
        }
        addMainWithCursor(fixture);
        MigrationTask task = task(fixture, MigrationConstants.DB_ORACLE);
        assertTrue(validateFailure(task, fixture, MigrationConstants.DB_ORACLE) instanceof IllegalArgumentException,
            "Oracle " + operation + " 未报告真实 INTERVAL 字段差异");
        String ddl = script(task);
        for (Column column : fixture.catalogDefinitions.get(FIRST_SOURCE)) {
            if (column.nativeType != null) {
                String definition = "\"" + column.name + "\" " + column.nativeType;
                switch (operation) {
                    case "MODIFY":
                        assertExecutableStatement(ddl, "ALTER TABLE \"" + FIRST_TARGET + "\" MODIFY ("
                            + definition + ");", "INTERVAL MODIFY 未使用目录真实声明精度");
                        break;
                    case "ADD":
                        assertExecutableStatement(ddl, "ALTER TABLE \"" + FIRST_TARGET + "\" ADD ("
                            + definition + " NULL);", "INTERVAL ADD 未使用目录真实声明精度");
                        break;
                    case "CREATE":
                        assertExecutableStatement(ddl, "CREATE TABLE \"" + FIRST_TARGET + "\" (",
                            "INTERVAL 缺表未生成建表建议");
                        assertContains(ddl, definition + " NULL", "INTERVAL CREATE 丢失目录真实精度");
                        break;
                    default:
                        throw new AssertionError("未定义的 INTERVAL 脚本验证场景");
                }
            }
        }
        fixture.tables.put(FIRST_TARGET, fixture.tables.get(FIRST_SOURCE));
        fixture.catalogDefinitions.put(FIRST_TARGET, fixture.catalogDefinitions.get(FIRST_SOURCE));
        assertTrue(prepare(task(fixture, MigrationConstants.DB_ORACLE), fixture, MigrationConstants.DB_ORACLE) != null,
            "Oracle INTERVAL 目录同步后完整两阶段准备仍失败: " + operation);
        assertEquals(NO_WRITES, fixture.writes, "INTERVAL 预检执行了结构或数据变更");
    }

    /**
     * Oracle JDBC 将 YEAR TO MONTH 元数据精度投射为存储字节数 5。
     * Params: declaration 目录真实声明，缺失时为 null。
     * Returns: 原始驱动元数据及独立目录声明。
     */
    private static Column intervalYearColumn(String declaration) {
        return new Column("YM_VALUE", ORACLE_JDBC_INTERVAL_YEAR_MONTH, "INTERVALYM", 5, 0,
            DatabaseMetaData.columnNullable, declaration);
    }

    /**
     * Oracle JDBC 将 DAY TO SECOND 元数据精度投射为存储字节数 11。
     * Params: declaration 目录真实声明，缺失时为 null。
     * Returns: 原始驱动元数据及独立目录声明。
     */
    private static Column intervalDayColumn(String declaration) {
        return new Column("DS_VALUE", ORACLE_JDBC_INTERVAL_DAY_SECOND, "INTERVALDS", 11, 0,
            DatabaseMetaData.columnNullable, declaration);
    }

    /**
     * 为单表场景配置显式游标和可独立模拟的业务查询。
     * Params: fixture 当前验证数据。
     * Returns: 无。
     */
    private static void addMainWithCursor(Fixture fixture) {
        ArchiveTableRequest main = table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null);
        main.setCursorColumn(ID);
        main.setSourceSql("SELECT * FROM " + FIRST_SOURCE + " /* " + BUSINESS_QUERY_MARKER + " */");
        fixture.mains.add(main);
    }

    /**
     * 独立调用引擎最终查询字段校验，防止两阶段兼容规则不一致。
     * Params: queryColumn 业务查询的 ID 字段；databaseType 方言。
     * Returns: 无。
     */
    private static void assertFinalQueryValidationRejected(Column queryColumn, String databaseType)
        throws Exception {
        Class<?> columnClass = Class.forName(ArchiveEngine.class.getName() + "$ColumnMeta");
        java.lang.reflect.Constructor<?> constructor = columnClass.getDeclaredConstructor(String.class,
            int.class, String.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        Object source = constructor.newInstance(ID, Types.NUMERIC, NUMBER_TYPE, 20, 0,
            DatabaseMetaData.columnNoNulls);
        Object query = constructor.newInstance(queryColumn.name, queryColumn.jdbcType, queryColumn.typeName,
            queryColumn.precision, queryColumn.scale, queryColumn.nullable);
        Method validate = ArchiveEngine.class.getDeclaredMethod("validateQueryCoversSourceTable", List.class,
            List.class, String.class, String.class);
        validate.setAccessible(true);
        Throwable failure = null;
        try {
            validate.invoke(new ArchiveEngine(), Arrays.asList(query), Arrays.asList(source), "QUERY_CHECK",
                databaseType);
        } catch (InvocationTargetException exception) {
            failure = exception.getCause();
        }
        assertTrue(failure instanceof IllegalArgumentException,
            databaseType + "最终引擎查询校验放过了已知字段差异");
    }

    /**
     * 创建 Oracle 物理元数据稳定、业务派生查询元数据独立的主表。
     * Params: queryId 派生查询 ID 字段。
     * Returns: JDBC 验证数据。
     */
    private static Fixture oracleQueryFixture(Column queryId) {
        Fixture fixture = new Fixture();
        Column id = new Column(ID, Types.NUMERIC, NUMBER_TYPE, 20, 0, DatabaseMetaData.columnNoNulls);
        Column name = new Column(NAME, Types.VARCHAR, ORACLE_TEXT_TYPE, 30, 0, DatabaseMetaData.columnNoNulls);
        fixture.add(FIRST_SOURCE, id, name);
        fixture.add(FIRST_TARGET, id, name);
        fixture.businessColumns = Arrays.asList(queryId,
            new Column(NAME, Types.VARCHAR, ORACLE_TEXT_TYPE, 30, 0, DatabaseMetaData.columnNullable));
        ArchiveTableRequest main = table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null);
        main.setSourceSql("SELECT ID, NAME FROM " + FIRST_SOURCE + " /* " + BUSINESS_QUERY_MARKER + " */");
        main.setCursorColumn(ID);
        fixture.mains.add(main);
        return fixture;
    }

    /**
     * 构造截图对应的四种物理数值精度，并让业务派生查询统一返回未知精度。
     * Params: nativeType NUMBER 或 DECIMAL 原生类型；jdbcType 同侧精确数值 JDBC 类型。
     * Returns: 物理元数据与业务查询元数据独立的达梦 JDBC 数据。
     */
    private static Fixture damengQueryFixture(String nativeType, int jdbcType) {
        return damengQueryFixture(nativeType, jdbcType, nativeType, jdbcType);
    }

    /**
     * 构造物理列和查询列原生类型/JDBC 同义表示不同的达梦驱动元数据。
     * Params: nativeType/jdbcType 物理数值字段类型；queryType/queryJdbcType 查询数值字段类型。
     * Returns: 独立的物理与业务查询验证数据。
     */
    private static Fixture damengQueryFixture(String nativeType, int jdbcType, String queryType, int queryJdbcType) {
        Fixture fixture = new Fixture();
        List<Column> physical = Arrays.asList(
            new Column(ID, jdbcType, nativeType, 20, 0, DatabaseMetaData.columnNoNulls),
            new Column("RECORD_ID", jdbcType, nativeType, 14, 0, DatabaseMetaData.columnNoNulls),
            new Column("COUNT_NO", jdbcType, nativeType, 8, 0, DatabaseMetaData.columnNullable),
            new Column(AMOUNT_COLUMN, jdbcType, nativeType, 12, 4, DatabaseMetaData.columnNullable),
            new Column(NAME, Types.VARCHAR, TEXT_TYPE, 30, 0, DatabaseMetaData.columnNoNulls));
        fixture.add(FIRST_SOURCE, physical.toArray(new Column[physical.size()]));
        fixture.add(FIRST_TARGET, physical.toArray(new Column[physical.size()]));
        fixture.businessColumns = new ArrayList<>();
        for (Column column : physical) {
            boolean numeric = column.jdbcType == Types.NUMERIC || column.jdbcType == Types.DECIMAL;
            fixture.businessColumns.add(new Column(column.name, numeric ? queryJdbcType : column.jdbcType,
                numeric ? queryType : column.typeName,
                numeric ? 0 : column.precision, numeric ? -127 : column.scale, DatabaseMetaData.columnNullable));
        }
        ArchiveTableRequest main = table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null);
        main.setSourceSql("SELECT ID, RECORD_ID, COUNT_NO, AMOUNT, NAME FROM " + FIRST_SOURCE
            + " /* " + BUSINESS_QUERY_MARKER + " */");
        main.setCursorColumn(ID);
        fixture.mains.add(main);
        return fixture;
    }

    /**
     * 调用归档完整准备入口，让结构预检和执行前最终校验都走生产代码。
     * Params: task 当前任务；fixture JDBC 验证数据；databaseType 方言。
     * Returns: 生产准备结果。
     */
    private static Object prepare(MigrationTask task, Fixture fixture, String databaseType) throws Exception {
        Method prepare = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class);
        prepare.setAccessible(true);
        try {
            return prepare.invoke(new ArchiveEngine(), task, fixture.connection(), databaseType);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw exception;
        }
    }

    /**
     * 获取预检失败而不隐藏原始异常类型。
     * Params: task 当前任务；fixture JDBC 数据；databaseType 方言。
     * Returns: 预检异常，成功时为 null。
     */
    private static Throwable validateFailure(MigrationTask task, Fixture fixture, String databaseType) {
        try {
            new ArchiveStructureValidator().validate(task, fixture.connection(), databaseType);
            return null;
        } catch (Exception exception) {
            return exception;
        }
    }

    /**
     * 构造跨多表、同表多处差异和缺表的验证数据。
     * Params: 无。
     * Returns: 数据集。
     */
    private static Fixture differences() {
        Fixture fixture = new Fixture();
        fixture.add(FIRST_SOURCE, integer(ID), text(NAME, 40), text(MISSING_COLUMN, 12),
            new Column(AMOUNT_COLUMN, Types.DECIMAL, "DECIMAL", 10, 2, DatabaseMetaData.columnNullable),
            new Column(REQUIRED_COLUMN, Types.VARCHAR, TEXT_TYPE, 20, 0, DatabaseMetaData.columnNoNulls));
        fixture.add(FIRST_TARGET, text(ID, 8), text(NAME, 5), text(EXTRA_COLUMN, 9),
            new Column(AMOUNT_COLUMN, Types.DECIMAL, "DECIMAL", 4, 0, DatabaseMetaData.columnNullable),
            text(REQUIRED_COLUMN, 20));
        fixture.add(SECOND_SOURCE, integer(ID), text(SECOND_COLUMN, 30));
        fixture.add(SECOND_TARGET, integer(ID));
        fixture.add(CHILD_SOURCE, integer(ID), integer("PARENT_ID"), text(CHILD_COLUMN, 20));
        fixture.add(CHILD_TARGET, integer(ID), integer("PARENT_ID"), text(CHILD_COLUMN, 5));
        fixture.add(MISSING_SOURCE, integer(ID), text("PAYLOAD", 12));
        fixture.tables.put(MISSING_TARGET, null);
        fixture.mains.add(table(FIRST_SOURCE, FIRST_TARGET, "ROOT", null));
        fixture.mains.add(table(SECOND_SOURCE, SECOND_TARGET, "ROOT_TWO", null));
        fixture.mains.add(table(MISSING_SOURCE, MISSING_TARGET, "ROOT_MISSING", null));
        fixture.children.add(table(CHILD_SOURCE, CHILD_TARGET, "CHILD", "ROOT"));
        return fixture;
    }

    /**
     * 创建归档节点。
     * Params: source 源表；target 归档表；node 节点；parent 父节点。
     * Returns: 归档节点。
     */
    private static ArchiveTableRequest table(String source, String target, String node, String parent) {
        ArchiveTableRequest result = new ArchiveTableRequest();
        result.setName(source);
        result.setNodeId(node);
        result.setParentNodeId(parent);
        result.setSourceTable(source);
        result.setTargetTable(target);
        result.setSourceSql("SELECT * FROM " + source);
        return result;
    }

    /**
     * 创建仅用于内存预检的任务。
     * Params: fixture 节点集；databaseType 数据库类型。
     * Returns: 任务。
     */
    private static MigrationTask task(Fixture fixture, String databaseType) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(databaseType);
        config.setDatabase("fixture_db");
        ArchiveRequest archive = new ArchiveRequest();
        archive.setMainTables(fixture.mains);
        archive.setChildTables(fixture.children);
        archive.setConfirmSourceDelete(Boolean.TRUE);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setName("Archive structure fixture");
        request.setSource(config);
        request.setTarget(config);
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSteps(Collections.emptyList());
        request.setArchive(archive);
        return new MigrationTask("ARCHIVE-STRUCTURE-CHECK", request);
    }

    /**
     * 读取逐项差异日志。
     * Params: task 当前任务。
     * Returns: 日志文本。
     */
    @SuppressWarnings("unchecked")
    private static String issueLogs(MigrationTask task) {
        StringBuilder result = new StringBuilder();
        List<Map<String, Object>> logs = (List<Map<String, Object>>)task.snapshot(true, 0L).get("logs");
        for (Map<String, Object> log : logs) {
            String message = String.valueOf(log.get("message"));
            if (message.startsWith(ISSUE_PREFIX)) {
                result.append(message).append('\n');
            }
        }
        return result.toString();
    }

    /**
     * 获取建议脚本。
     * Params: task 当前任务。
     * Returns: SQL 文本。
     */
    private static String script(MigrationTask task) {
        return String.valueOf(task.snapshot(true, 0L).get("structureSyncScript"));
    }

    /**
     * 创建非空整数元数据。
     * Params: name 字段名。
     * Returns: 字段。
     */
    private static Column integer(String name) {
        return new Column(name, Types.INTEGER, INTEGER_TYPE, 10, 0, DatabaseMetaData.columnNoNulls);
    }

    /**
     * 创建可空文本元数据。
     * Params: name 字段名；length 长度。
     * Returns: 字段。
     */
    private static Column text(String name, int length) {
        return new Column(name, Types.VARCHAR, TEXT_TYPE, length, 0, DatabaseMetaData.columnNullable);
    }

    /**
     * 断言文本包含内容。
     * Params: actual 实际；expected 期望；message 失败说明。
     * Returns: 无。
     */
    private static void assertContains(String actual, String expected, String message) {
        assertTrue(actual != null && actual.contains(expected), message + ": " + actual);
    }

    /**
     * 断言脚本中存在未注释的完整 SQL 行，而非仅在人工说明中出现。
     * Params: script 脚本；expected 期望的可执行语句；message 失败说明。
     * Returns: 无。
     */
    private static void assertExecutableStatement(String script, String expected, String message) {
        for (String line : script.split("\\r?\\n")) {
            if (expected.equals(line.trim())) {
                return;
            }
        }
        throw new AssertionError(message + ": " + script);
    }

    /**
     * 断言数值相等。
     * Params: expected 期望；actual 实际；message 失败说明。
     * Returns: 无。
     */
    private static void assertEquals(int expected, int actual, String message) {
        assertTrue(expected == actual, message + ": expected=" + expected + ", actual=" + actual);
    }

    /**
     * 断言条件。
     * Params: condition 条件；message 失败说明。
     * Returns: 无。
     */
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * 创建强类型 JDBC 代理。
     * Params: type 接口；handler 处理器。
     * Returns: 代理。
     */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    /**
     * 返回未使用 JDBC 方法的默认值。
     * Params: type 返回类型。
     * Returns: 默认值。
     */
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        return null;
    }

    /**
     * 内存 JDBC 数据和读写计数。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Fixture {
        /** 物理表字段，null 表示该表不存在。 */
        private final Map<String, List<Column>> tables = new LinkedHashMap<>();
        /** 与驱动零行字段投射独立的完整物理字段目录。 */
        private final Map<String, List<Column>> catalogDefinitions = new LinkedHashMap<>();
        /** 主表节点。 */
        private final List<ArchiveTableRequest> mains = new ArrayList<>();
        /** 子表节点。 */
        private final List<ArchiveTableRequest> children = new ArrayList<>();
        /** 所有发往 JDBC 的查询。 */
        private final List<String> queries = new ArrayList<>();
        /** 去重后的物理表访问记录。 */
        private final Map<String, Boolean> physicalTablesRead = new LinkedHashMap<>();
        /** 需要模拟异常的表。 */
        private String failTable;
        /** 模拟的连接异常。 */
        private SQLException failure;
        /** 当前方言下缺表的 SQLState。 */
        private String missingState = MISSING_STATE;
        /** 当前方言下缺表的数据库错误号。 */
        private int missingCode = MISSING_CODE;
        /** 写入操作数。 */
        private int writes;
        /** 业务派生查询专用元数据，物理表元数据不受影响。 */
        private List<Column> businessColumns;
        /** 业务查询元数据读取次数。 */
        private int businessMetadataReads;
        /** 归档键统计查询次数。 */
        private int statisticsQueries;

        /**
         * 注册物理表。
         * Params: table 表名；columns 字段。
         * Returns: 无。
         */
        private void add(String table, Column... columns) {
            tables.put(table, Arrays.asList(columns));
        }

        /**
         * 创建只允许零行字段查询的连接。
         * Params: 无。
         * Returns: JDBC 连接代理。
         */
        private Connection connection() {
            return proxy(Connection.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "prepareStatement":
                        return statement(String.valueOf(arguments[0]));
                    case "createStatement":
                        throw new AssertionError("预检不应创建可执行任意写入的 Statement");
                    case "getCatalog":
                        return FIXTURE_CATALOG;
                    case "getSchema":
                        return FIXTURE_SCHEMA;
                    case "getMetaData":
                        return databaseMetadata();
                    case "hashCode":
                        return System.identityHashCode(object);
                    case "equals":
                        return object == arguments[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 创建查询代理并拒绝写入。
         * Params: sql SQL。
         * Returns: 预编译语句代理。
         */
        private PreparedStatement statement(final String sql) {
            final Map<Integer, Object> parameters = new LinkedHashMap<>();
            return proxy(PreparedStatement.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "setString":
                    case "setObject":
                        parameters.put((Integer)arguments[0], arguments[1]);
                        return null;
                    case "executeQuery":
                        return query(sql, parameters);
                    case "execute":
                    case "executeUpdate":
                    case "executeBatch":
                    case "executeLargeBatch":
                    case "executeLargeUpdate":
                    case "addBatch":
                        writes++;
                        throw new AssertionError("归档预检执行了数据或结构写入: " + sql);
                    case "hashCode":
                        return System.identityHashCode(object);
                    case "equals":
                        return object == arguments[0];
                    default:
                        return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 返回指定物理表的结果集元数据。
         * Params: sql 查询。
         * Returns: 零行结果集。
         */
        private ResultSet query(String sql, Map<Integer, Object> parameters) throws SQLException {
            queries.add(sql);
            assertTrue(sql.trim().toUpperCase(Locale.ROOT).startsWith(SELECT_PREFIX),
                "预检不是 SELECT 查询: " + sql);
            if (sql.contains(MYSQL_COLUMNS_MARKER)) {
                String table = String.valueOf(parameters.get(MYSQL_TABLE_BINDING));
                return catalogRows(table);
            }
            if (sql.contains(ORACLE_INTERVAL_COLUMNS_MARKER)) {
                String table = String.valueOf(parameters.get(MYSQL_TABLE_BINDING));
                return catalogRows(table, true);
            }
            if (sql.trim().toUpperCase(Locale.ROOT).startsWith(COUNT_QUERY_PREFIX)) {
                statisticsQueries++;
                final boolean[] firstRow = {true};
                return proxy(ResultSet.class, (object, method, arguments) -> {
                    switch (method.getName()) {
                        case "next":
                            boolean next = firstRow[0];
                            firstRow[0] = false;
                            return next;
                        case "getLong":
                            return SINGLE_ROW;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
            }
            for (Map.Entry<String, List<Column>> entry : tables.entrySet()) {
                String table = entry.getKey();
                if (Pattern.compile("\\b" + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(sql).find()) {
                    physicalTablesRead.put(table, Boolean.TRUE);
                    if (table.equals(failTable)) {
                        throw failure;
                    }
                    List<Column> columns = entry.getValue();
                    if (columns == null) {
                        throw new SQLException("Table " + table + " doesn't exist", missingState, missingCode);
                    }
                    if (sql.contains(BUSINESS_QUERY_MARKER) && businessColumns != null) {
                        columns = businessColumns;
                        businessMetadataReads++;
                    }
                    ResultSetMetaData metadata = metadata(columns);
                    return proxy(ResultSet.class, (object, method, arguments) -> {
                        switch (method.getName()) {
                            case "getMetaData": return metadata;
                            default: return defaultValue(method.getReturnType());
                        }
                    });
                }
            }
            throw new AssertionError("意外查询: " + sql);
        }

        /**
         * 提供 Oracle/达梦补读所需的精确表字段目录 API。
         * Params: 无。
         * Returns: JDBC 字段目录代理。
         */
        private DatabaseMetaData databaseMetadata() {
            return proxy(DatabaseMetaData.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "getUserName": return FIXTURE_SCHEMA;
                    case "getSearchStringEscape": return "\\";
                    case "getColumns":
                        String tablePattern = String.valueOf(arguments[2]);
                        return catalogRows(tablePattern.replace("\\_", "_").replace("\\%", "%"));
                    default: return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 同时支持 MySQL COLUMN_TYPE SQL 与 JDBC getColumns 的独立真实物理定义。
         * Params: table 已绑定或模式转义还原后的物理表名。
         * Returns: 字段目录结果；没有登记的表返回空目录，不凭空构造定义。
         */
        private ResultSet catalogRows(String table) {
            return catalogRows(table, false);
        }

        /**
         * 字段目录与 Oracle INTERVAL 声明目录分别返回，后者绝不以驱动字节长度替代声明。
         * Params: table 物理表；intervalDictionary 是否读取受限 INTERVAL 声明目录。
         * Returns: 目录结果。
         */
        private ResultSet catalogRows(String table, boolean intervalDictionary) {
            List<Column> candidates = catalogDefinitions.containsKey(table)
                ? catalogDefinitions.get(table) : Collections.emptyList();
            final List<Column> columns = new ArrayList<>();
            for (Column column : candidates) {
                if (!intervalDictionary || column.nativeType != null
                    && column.nativeType.startsWith(INTERVAL_DECLARATION_PREFIX)) {
                    columns.add(column);
                }
            }
            final int[] index = {-1};
            final boolean[] lastNull = {false};
            return proxy(ResultSet.class, (object, method, arguments) -> {
                switch (method.getName()) {
                    case "next": return ++index[0] < columns.size();
                    case "wasNull": return lastNull[0];
                    case "getString":
                    case "getLong":
                    case "getInt":
                        Object value = catalogValue(columns.get(index[0]), table, String.valueOf(arguments[0]),
                            method.getReturnType() == String.class, intervalDictionary);
                        lastNull[0] = value == null;
                        switch (method.getName()) {
                            case "getString": return value == null ? null : String.valueOf(value);
                            case "getLong": return value == null ? 0L : ((Number)value).longValue();
                            default: return value == null ? 0 : ((Number)value).intValue();
                        }
                    default: return defaultValue(method.getReturnType());
                }
            });
        }

        /**
         * 按目录列名返回已登记的真实属性，保留完整原生类型的大小写和转义。
         * Params: column 字段定义；table 物理表名；key 目录列名；textValue 是否按文本读取；intervalDictionary 真实声明目录。
         * Returns: 原始字段属性。
         */
        private Object catalogValue(Column column, String table, String key, boolean textValue,
            boolean intervalDictionary) {
            switch (key) {
                case "COLUMN_NAME": return column.name;
                case "COLUMN_TYPE": return column.nativeType;
                case "DATA_TYPE": return textValue ? intervalDictionary ? column.nativeType : column.typeName : column.jdbcType;
                case "TYPE_NAME": return column.typeName;
                case "IS_NULLABLE": return column.nullable == DatabaseMetaData.columnNoNulls ? "NO"
                    : column.nullable == DatabaseMetaData.columnNullable ? "YES" : null;
                case "NULLABLE": return column.nullable;
                case "DATA_PRECISION": return intervalDictionary ? null : column.precision;
                case "DATA_SCALE": return intervalDictionary ? null : column.scale;
                case "COLUMN_SIZE":
                case "CHARACTER_MAXIMUM_LENGTH":
                case "NUMERIC_PRECISION": return column.precision;
                case "DECIMAL_DIGITS":
                case "NUMERIC_SCALE":
                case "DATETIME_PRECISION": return column.scale;
                case "TABLE_NAME": return table;
                case "TABLE_SCHEM": return FIXTURE_SCHEMA;
                case "TABLE_CAT": return FIXTURE_CATALOG;
                default: return null;
            }
        }

        /**
         * 创建字段元数据代理。
         * Params: columns 字段。
         * Returns: 元数据。
         */
        private ResultSetMetaData metadata(List<Column> columns) {
            return proxy(ResultSetMetaData.class, (object, method, arguments) -> {
                String methodName = method.getName();
                switch (methodName) {
                    case "getColumnCount": return columns.size();
                    default: break;
                }
                Column column = columns.get((Integer)arguments[0] - 1);
                switch (methodName) {
                    case "getColumnLabel":
                    case "getColumnName": return column.name;
                    case "getColumnType": return column.jdbcType;
                    case "getColumnTypeName": return column.typeName;
                    case "getPrecision": return column.precision;
                    case "getScale": return column.scale;
                    case "isNullable": return column.nullable;
                    default: return defaultValue(method.getReturnType());
                }
            });
        }
    }

    /**
     * 不可变字段元数据。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Column {
        /** 字段名。 */
        private final String name;
        /** JDBC 类型。 */
        private final int jdbcType;
        /** 数据库原始类型。 */
        private final String typeName;
        /** 字段精度或长度。 */
        private final int precision;
        /** 小数位。 */
        private final int scale;
        /** 可空状态。 */
        private final int nullable;
        /** 字段目录中完整的原生定义，不等同于驱动投射的 typeName。 */
        private final String nativeType;

        /**
         * 记录字段定义。
         * Params: name 字段名；jdbcType JDBC类型；typeName 原始类型；precision 精度；scale 小数位；nullable 可空。
         * Returns: 字段定义。
         */
        private Column(String name, int jdbcType, String typeName, int precision, int scale, int nullable) {
            this(name, jdbcType, typeName, precision, scale, nullable, null);
        }

        /**
         * 同时记录字段目录原文，保留 ENUM/SET 成员等不能从投射元数据推导的属性。
         * Params: name 字段名；jdbcType/typeName 投射类型；precision/scale 精度；nullable 可空；nativeType 目录原文。
         * Returns: 字段定义。
         */
        private Column(String name, int jdbcType, String typeName, int precision, int scale, int nullable,
            String nativeType) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
            this.nativeType = nativeType;
        }
    }
}
