package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 可推导跨库数值、Oracle INTERVAL 与 MySQL 原生显示修饰的离线回归。
 * Author: Codex
 * date: 2026-10-28
 */
public final class ColumnMappingCheck {
    private final MigrationEngine engine = new MigrationEngine();
    private final Class<?> columnClass;
    private final Constructor<?> columnConstructor;
    private final Method mapping;
    private final Method supplement;
    private final Method modify;

    /** Params: 无。 Returns: 初始化生产私有入口，执行期间不连接数据库。 */
    private ColumnMappingCheck() throws Exception {
        columnClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        columnConstructor = columnClass.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class);
        columnConstructor.setAccessible(true);
        mapping = MigrationEngine.class.getDeclaredMethod("targetTypeDefinition", columnClass, String.class, String.class);
        mapping.setAccessible(true);
        supplement = MigrationEngine.class.getDeclaredMethod("supplementColumn", columnClass,
            PhysicalColumnMetadataSupport.Definition.class, String.class);
        supplement.setAccessible(true);
        modify = MigrationEngine.class.getDeclaredMethod("addModifyColumnStatement", Set.class, QualifiedName.class,
            columnClass, columnClass, String.class, String.class);
        modify.setAccessible(true);
    }

    /** Params: args 未使用。 Returns: 无；失败抛出断言。 */
    public static void main(String[] args) throws Exception {
        new ColumnMappingCheck().run();
        System.out.println("[RESULT] COLUMN_MAPPING_CHECK_PASSED");
    }

    /** Params: 无。 Returns: 验证跨库映射、对应 DDL 和映射后目标元数据的二次比较。 */
    private void run() throws Exception {
        Object negativeScale = column(Types.NUMERIC, "NUMBER", 10, -2);
        checkRoundTrip(negativeScale, MigrationConstants.DB_ORACLE, MigrationConstants.DB_MYSQL,
            column(Types.DECIMAL, "DECIMAL", 12, 0), "DECIMAL(12,0)");
        checkRoundTrip(column(Types.NUMERIC, "NUMBER", 4, 5), MigrationConstants.DB_ORACLE,
            MigrationConstants.DB_MYSQL, column(Types.DECIMAL, "DECIMAL", 5, 5), "DECIMAL(5,5)");
        Object bit = column(Types.BIT, "BIT", 64, 0);
        checkRoundTrip(bit, MigrationConstants.DB_MYSQL, MigrationConstants.DB_ORACLE,
            column(Types.NUMERIC, "NUMBER", 20, 0), "NUMBER(20,0)");
        checkRoundTrip(bit, MigrationConstants.DB_MYSQL, MigrationConstants.DB_DM,
            column(Types.DECIMAL, "DECIMAL", 20, 0), "DECIMAL(20,0)");
        checkRoundTrip(negativeScale, MigrationConstants.DB_ORACLE, MigrationConstants.DB_DM,
            column(Types.DECIMAL, "DECIMAL", 12, 0), "DECIMAL(12,0)");
        if (mapping.invoke(engine, column(Types.DECIMAL, "DECIMAL", 65, 0),
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_ORACLE) != null) {
            throw new AssertionError("超出 Oracle 表示能力的精度被伪造为可对齐定义");
        }
        checkMysqlNative(Types.INTEGER, "INT UNSIGNED", 10, 0,
            "int(10) unsigned zerofill", "INT(10) UNSIGNED ZEROFILL");
        checkMysqlNative(Types.DECIMAL, "DECIMAL UNSIGNED", 8, 2,
            "decimal(8,2) unsigned zerofill", "DECIMAL(8,2) UNSIGNED ZEROFILL");
        checkMysqlNative(Types.REAL, "FLOAT", 12, 31, "float(10,2)", "FLOAT(10,2)");
        Object ordinary = supplement.invoke(engine, column(Types.INTEGER, "INT UNSIGNED", 10, 0),
            physical("int(11) unsigned", "int", 10, 0), MigrationConstants.DB_MYSQL);
        assertEquals("INT UNSIGNED", String.valueOf(mapping.invoke(engine, ordinary,
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL)), "普通整数显示宽度被当成真实类型差异");
        checkOracleInterval("INTERVALYM", 3, 0, "INTERVAL YEAR(3) TO MONTH");
        checkOracleInterval("INTERVALDS", 4, 6, "INTERVAL DAY(4) TO SECOND(6)");
    }

    /** Params: typeName Oracle 驱动类型名；precision/scale 模拟不可靠访问器参数；expected 目录完整声明。 Returns: 无。 */
    private void checkOracleInterval(String typeName, int precision, int scale, String expected) throws Exception {
        Object projected = column(Types.OTHER, typeName, precision, scale);
        if (mapping.invoke(engine, projected, MigrationConstants.DB_ORACLE, MigrationConstants.DB_ORACLE) != null) {
            throw new AssertionError("Oracle INTERVAL 访问器参数被误当成 DDL 前导精度");
        }
        PhysicalColumnMetadataSupport.Definition catalog = new PhysicalColumnMetadataSupport.Definition("VALUE",
            expected, typeName, Types.OTHER, 5, scale, DatabaseMetaData.columnNullable);
        Object interval = supplement.invoke(engine, projected, catalog, MigrationConstants.DB_ORACLE);
        Object after = supplement.invoke(engine, column(Types.OTHER, typeName, 5, scale), catalog,
            MigrationConstants.DB_ORACLE);
        checkRoundTrip(interval, MigrationConstants.DB_ORACLE, MigrationConstants.DB_ORACLE,
            after, expected);
        assertEquals(expected, String.valueOf(mapping.invoke(engine, column(Types.OTHER, expected, precision, scale),
            MigrationConstants.DB_ORACLE, MigrationConstants.DB_ORACLE)), "完整 INTERVAL 定义没有保持同义规范");
        Method add = MigrationEngine.class.getDeclaredMethod("addColumnStatement", Set.class,
            QualifiedName.class, columnClass, String.class);
        add.setAccessible(true);
        Set<String> additions = new LinkedHashSet<>();
        QualifiedName target = SqlSupport.parseTargetTable("MAP_TARGET");
        add.invoke(engine, additions, target, interval, MigrationConstants.DB_ORACLE);
        assertContains(additions.toString(), "ADD (\"VALUE\" " + expected + " NULL)", "Oracle INTERVAL ADD 非法");
        Method create = MigrationEngine.class.getDeclaredMethod("addCreateTableStatement", Set.class,
            QualifiedName.class, List.class, String.class);
        create.setAccessible(true);
        Set<String> creates = new LinkedHashSet<>();
        create.invoke(engine, creates, target, Collections.singletonList(interval), MigrationConstants.DB_ORACLE);
        assertContains(creates.toString(), "CREATE TABLE", "Oracle INTERVAL 缺少建表建议");
        assertContains(creates.toString(), "\"VALUE\" " + expected + " NULL", "Oracle INTERVAL CREATE 非法");
        if (mapping.invoke(engine, column(Types.OTHER, typeName, 10, scale),
            MigrationConstants.DB_ORACLE, MigrationConstants.DB_ORACLE) != null) {
            throw new AssertionError("未知或越界的 INTERVAL 精度被默认或伪造");
        }
    }

    /** Params: source 源列；sourceType/targetType 方言；after 对齐后元数据；expected 规范定义。 Returns: 无。 */
    private void checkRoundTrip(Object source, String sourceType, String targetType, Object after, String expected)
        throws Exception {
        assertEquals(expected, String.valueOf(mapping.invoke(engine, source, sourceType, targetType)), "跨库映射不正确");
        assertEquals(expected, String.valueOf(mapping.invoke(engine, after, targetType, targetType)), "映射回读不幂等");
        Set<String> statements = new LinkedHashSet<>();
        modify.invoke(engine, statements, SqlSupport.parseTargetTable("MAP_TARGET"), source,
            column(Types.VARCHAR, "VARCHAR", 5, 0), sourceType, targetType);
        assertContains(statements.toString(), expected, "可推导差异未生成对应 DDL");
        assertContains(statements.toString(), "ALTER TABLE", "同步建议只有注释");
    }

    /** Params: jdbcType/typeName/precision/scale 查询投射；nativeType 真实目录原文；expected 规范定义。 Returns: 无。 */
    private void checkMysqlNative(int jdbcType, String typeName, int precision, int scale, String nativeType,
        String expected) throws Exception {
        Object projected = column(jdbcType, typeName, precision, scale);
        String physicalType = nativeType.substring(0, nativeType.indexOf('('));
        Object complete = supplement.invoke(engine, projected, physical(nativeType, physicalType, precision, scale),
            MigrationConstants.DB_MYSQL);
        assertEquals(expected, String.valueOf(mapping.invoke(engine, complete,
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL)), "完整原生修饰或浮点声明被丢失");
        checkRoundTrip(complete, MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL, complete, expected);
    }

    /** Params: nativeType/typeName/precision/scale 原始目录数据。 Returns: 本轮虚拟物理字段定义。 */
    private PhysicalColumnMetadataSupport.Definition physical(String nativeType, String typeName, int precision, int scale) {
        return new PhysicalColumnMetadataSupport.Definition("VALUE", nativeType, typeName, null, precision, scale,
            DatabaseMetaData.columnNullable);
    }

    /** Params: jdbcType/typeName/precision/scale 元数据。 Returns: 生产列描述。 */
    private Object column(int jdbcType, String typeName, int precision, int scale) throws Exception {
        return columnConstructor.newInstance("VALUE", jdbcType, typeName, precision, scale,
            DatabaseMetaData.columnNullable, false);
    }

    /** Params: expected 期望值；actual 实际值；message 说明。 Returns: 无。 */
    private void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + "，expected=" + expected + "，actual=" + actual);
        }
    }

    /** Params: actual 实际文本；expected 期望片段；message 说明。 Returns: 无。 */
    private void assertContains(String actual, String expected, String message) {
        if (!actual.contains(expected)) {
            throw new AssertionError(message + "，actual=" + actual);
        }
    }
}
