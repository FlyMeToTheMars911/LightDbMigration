package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.sql.Types;
import java.util.Locale;

/**
 * 统一归档两阶段对派生查询元数据的判断，物理表结构比较仍由调用方严格执行。
 * Author: Codex
 * date: 2026-10-28
 */
final class ArchiveQueryMetadataSupport {

    /** Oracle 未指定精度的 NUMBER 查询元数据标记。 */
    private static final int ORACLE_UNKNOWN_NUMBER_PRECISION = 0;
    /** Oracle 未指定小数位的 NUMBER 查询元数据标记。 */
    private static final int ORACLE_UNKNOWN_NUMBER_SCALE = -127;
    /** Oracle 原生 NUMBER 类型名。 */
    private static final String ORACLE_NUMBER_TYPE = "NUMBER";

    /**
     * Params: 无。
     * Returns: 不允许创建工具类实例。
     */
    private ArchiveQueryMetadataSupport() {
    }

    /**
     * 仅忽略 Oracle 派生查询未知 NUMBER 精度和 Oracle/达梦派生查询可空标记。
     * Params: databaseType 数据库类型；queryJdbcType/queryTypeName/queryPrecision/queryScale/queryNullable 查询字段；
     * physicalJdbcType/physicalTypeName/physicalPrecision/physicalScale/physicalNullable 源物理字段。
     * Returns: 字段类型和全部可靠的查询元数据是否与源物理字段一致。
     */
    static boolean matches(String databaseType, int queryJdbcType, String queryTypeName,
        int queryPrecision, int queryScale, int queryNullable, int physicalJdbcType, String physicalTypeName,
        int physicalPrecision, int physicalScale, int physicalNullable) {
        boolean oracle = MigrationConstants.DB_ORACLE.equals(databaseType);
        String queryType = normalize(queryTypeName);
        String physicalType = normalize(physicalTypeName);
        boolean oracleNumber = oracle && ORACLE_NUMBER_TYPE.equals(queryType)
            && ORACLE_NUMBER_TYPE.equals(physicalType) && numericJdbcType(queryJdbcType)
            && numericJdbcType(physicalJdbcType);
        if (queryJdbcType != physicalJdbcType || !queryType.equals(physicalType)) {
            return false;
        }
        boolean unknownOraclePrecision = oracleNumber
            && queryPrecision == ORACLE_UNKNOWN_NUMBER_PRECISION && queryScale == ORACLE_UNKNOWN_NUMBER_SCALE;
        if (!unknownOraclePrecision && (queryPrecision != physicalPrecision || queryScale != physicalScale)) {
            return false;
        }
        return oracle || MigrationConstants.DB_DM.equals(databaseType) || queryNullable == physicalNullable;
    }

    /**
     * Params: jdbcType JDBC 类型。
     * Returns: 是否为 Oracle NUMBER 可对应的精确数值 JDBC 类型。
     */
    private static boolean numericJdbcType(int jdbcType) {
        return jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL;
    }

    /**
     * Params: typeName 驱动类型名。
     * Returns: 忽略大小写和首尾空格的类型名。
     */
    private static String normalize(String typeName) {
        return typeName == null ? "" : typeName.trim().toUpperCase(Locale.ROOT);
    }
}
