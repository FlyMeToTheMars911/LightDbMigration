package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.sql.Types;
import java.sql.DatabaseMetaData;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 统一归档两阶段对派生查询元数据的判断，物理表结构比较仍由调用方严格执行。
 * Author: Codex
 * date: 2026-10-28
 */
final class ArchiveQueryMetadataSupport {

    /** Oracle 和达梦未指定数值精度的查询元数据标记。 */
    private static final int UNKNOWN_NUMBER_PRECISION = 0;
    /** Oracle 和达梦未指定数值小数位的查询元数据标记。 */
    private static final int UNKNOWN_NUMBER_SCALE = -127;
    /** Oracle 原生 NUMBER 类型名。 */
    private static final String ORACLE_NUMBER_TYPE = "NUMBER";
    /** 达梦驱动可能使用的同义精确数值类型名。 */
    private static final Set<String> DM_EXACT_NUMERIC_TYPES = new LinkedHashSet<>(
        Arrays.asList("NUMBER", "DECIMAL", "NUMERIC", "DEC"));

    /**
     * Params: 无。
     * Returns: 不允许创建工具类实例。
     */
    private ArchiveQueryMetadataSupport() {
    }

    /**
     * 仅兼容 Oracle/达梦有限数值族的未知查询精度和派生查询可空标记。
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
        boolean dmExactNumeric = dmExactNumericFamily(databaseType, queryJdbcType, queryType,
            physicalJdbcType, physicalType);
        if (!dmExactNumeric && (queryJdbcType != physicalJdbcType || !queryType.equals(physicalType))) {
            return false;
        }
        boolean unknownNumericPrecision = usesUnknownNumericPrecision(databaseType, queryJdbcType, queryType,
            queryPrecision, queryScale, physicalJdbcType, physicalType);
        if (!unknownNumericPrecision && (queryPrecision != physicalPrecision || queryScale != physicalScale)) {
            return false;
        }
        return oracle || MigrationConstants.DB_DM.equals(databaseType)
            || queryNullable == DatabaseMetaData.columnNullableUnknown || queryNullable == physicalNullable;
    }

    /**
     * 判断查询字段是否使用可兼容的未知数值精度标记，供节点汇总日志说明。
     * Params: databaseType 数据库类型；queryJdbcType/queryTypeName/queryPrecision/queryScale 查询字段；
     * physicalJdbcType/physicalTypeName 源物理字段类型。
     * Returns: 是否属于已确认类型语义相同的 Oracle/达梦未知查询精度。
     */
    static boolean usesUnknownNumericPrecision(String databaseType, int queryJdbcType, String queryTypeName,
        int queryPrecision, int queryScale, int physicalJdbcType, String physicalTypeName) {
        if (queryPrecision != UNKNOWN_NUMBER_PRECISION || queryScale != UNKNOWN_NUMBER_SCALE) {
            return false;
        }
        String queryType = normalize(queryTypeName);
        String physicalType = normalize(physicalTypeName);
        boolean oracleNumber = MigrationConstants.DB_ORACLE.equals(databaseType)
            && ORACLE_NUMBER_TYPE.equals(queryType) && ORACLE_NUMBER_TYPE.equals(physicalType)
            && queryJdbcType == physicalJdbcType && numericJdbcType(queryJdbcType);
        return oracleNumber || dmExactNumericFamily(databaseType, queryJdbcType, queryType,
            physicalJdbcType, physicalType);
    }

    /**
     * 仅在达梦的有限原生数值同义族内允许 NUMERIC/DECIMAL 驱动标记互换。
     * Params: databaseType 数据库类型；queryJdbcType/queryType 查询类型；physicalJdbcType/physicalType 物理字段类型。
     * Returns: 两侧是否为达梦相同精确数值语义。
     */
    private static boolean dmExactNumericFamily(String databaseType, int queryJdbcType, String queryType,
        int physicalJdbcType, String physicalType) {
        return MigrationConstants.DB_DM.equals(databaseType)
            && DM_EXACT_NUMERIC_TYPES.contains(queryType) && DM_EXACT_NUMERIC_TYPES.contains(physicalType)
            && numericJdbcType(queryJdbcType) && numericJdbcType(physicalJdbcType);
    }

    /**
     * Params: jdbcType JDBC 类型。
     * Returns: 是否为 Oracle/达梦 NUMBER 可对应的精确数值 JDBC 类型。
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
