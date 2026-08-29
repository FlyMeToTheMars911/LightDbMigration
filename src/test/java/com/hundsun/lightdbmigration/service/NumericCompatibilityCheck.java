package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.DatabaseMetaData;
import java.sql.Types;
import java.util.Collections;

/**
 * 数值类型结构预检的无数据库边界回归，使用 main 方法便于 JDK 8 单文件检查和执行。
 */
public class NumericCompatibilityCheck {

    private static final String FIELD_NAME = "ACT_ID";
    private static final String TARGET_TABLE = "CCB_TASK";
    private static final int ORACLE_NUMBER_PRECISION_TWENTY = 20;
    private static final int ORACLE_NUMBER_PRECISION_NINETEEN = 19;
    private static final int ORACLE_NUMBER_PRECISION_EIGHTEEN = 18;
    private static final int ORACLE_NUMBER_PRECISION_EIGHT = 8;
    private static final int ORACLE_NUMBER_PRECISION_THREE = 3;
    private static final int ORACLE_NUMBER_PRECISION_FIVE = 5;
    private static final int MYSQL_BIGINT_PRECISION = 19;
    private static final int MYSQL_TINYINT_PRECISION = 3;
    private static final int MYSQL_MEDIUMINT_PRECISION = 7;
    private static final int INTEGER_SCALE = 0;
    private static final int FRACTION_SCALE = 2;
    private static final long NO_FRACTIONAL_ROWS = 0L;
    private static final long ONE_FRACTIONAL_ROW = 1L;
    private static final BigDecimal BIGINT_MAX = new BigDecimal("9223372036854775807");
    private static final BigDecimal BIGINT_OVERFLOW = new BigDecimal("9223372036854775808");
    private static final BigDecimal TINYINT_MAX = new BigDecimal("127");
    private static final BigDecimal TINYINT_OVERFLOW = new BigDecimal("128");
    private static final BigDecimal MEDIUMINT_MAX = new BigDecimal("8388607");
    private static final BigDecimal MEDIUMINT_OVERFLOW = new BigDecimal("8388608");

    private final MigrationEngine engine = new MigrationEngine();
    private final Class<?> columnMetaClass;
    private final Constructor<?> columnMetaConstructor;
    private final Method validateTypeCompatibility;
    private final Method validateActualNumericRange;
    private final MigrationTask task;

    public NumericCompatibilityCheck() throws Exception {
        columnMetaClass = Class.forName(MigrationEngine.class.getName() + "$ColumnMeta");
        columnMetaConstructor = columnMetaClass.getDeclaredConstructor(String.class, int.class, String.class,
            int.class, int.class, int.class, boolean.class);
        columnMetaConstructor.setAccessible(true);
        validateTypeCompatibility = MigrationEngine.class.getDeclaredMethod("validateTypeCompatibility",
            columnMetaClass, columnMetaClass, String.class);
        validateTypeCompatibility.setAccessible(true);
        Class<?> rangeCheckClass = Class.forName(MigrationEngine.class.getName() + "$NumericRangeCheck");
        validateActualNumericRange = MigrationEngine.class.getDeclaredMethod("validateActualNumericRange",
            MigrationTask.class, String.class, rangeCheckClass, BigDecimal.class, BigDecimal.class, long.class);
        validateActualNumericRange.setAccessible(true);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSteps(Collections.emptyList());
        task = new MigrationTask("NUMERIC-COMPATIBILITY-CHECK", request);
    }

    public static void main(String[] args) throws Exception {
        new NumericCompatibilityCheck().run();
        System.out.println("[RESULT] NUMERIC_COMPATIBILITY_CHECK_PASSED");
    }

    private void run() throws Exception {
        Object mysqlBigint = column(Types.BIGINT, "BIGINT", MYSQL_BIGINT_PRECISION, INTEGER_SCALE);
        Object numberTwenty = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_TWENTY, INTEGER_SCALE);
        Object numberNineteen = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_NINETEEN, INTEGER_SCALE);
        Object numberEighteen = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_EIGHTEEN, INTEGER_SCALE);

        Object numberTwentyCheck = validate(numberTwenty, mysqlBigint);
        assertNotNull(numberTwentyCheck, "NUMBER(20,0) -> BIGINT 应转为实际值范围校验");
        assertNotNull(validate(numberNineteen, mysqlBigint),
            "NUMBER(19,0) -> BIGINT 不能只因位数相同而直接放行");
        assertNull(validate(numberEighteen, mysqlBigint),
            "NUMBER(18,0) 的声明范围完整落在 BIGINT 内，不需要额外扫描");

        validateActual(numberTwentyCheck, BigDecimal.ONE, BIGINT_MAX, NO_FRACTIONAL_ROWS);
        expectIllegalArgument(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                validateActual(numberTwentyCheck, BigDecimal.ONE, BIGINT_OVERFLOW, NO_FRACTIONAL_ROWS);
            }
        }, "BIGINT 上界之外的实际值必须在写入前拦截");

        Object mysqlTinyint = column(Types.TINYINT, "TINYINT", MYSQL_TINYINT_PRECISION, INTEGER_SCALE);
        Object numberThree = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_THREE, INTEGER_SCALE);
        Object numberThreeCheck = validate(numberThree, mysqlTinyint);
        assertNotNull(numberThreeCheck, "NUMBER(3,0) -> TINYINT 应按真实边界检查");
        validateActual(numberThreeCheck, BigDecimal.ZERO, TINYINT_MAX, NO_FRACTIONAL_ROWS);
        expectIllegalArgument(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                validateActual(numberThreeCheck, BigDecimal.ZERO, TINYINT_OVERFLOW, NO_FRACTIONAL_ROWS);
            }
        }, "TINYINT 的 128 溢出必须在写入前拦截");

        Object mysqlMediumint = column(Types.INTEGER, "MEDIUMINT", MYSQL_MEDIUMINT_PRECISION, INTEGER_SCALE);
        Object numberEight = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_EIGHT, INTEGER_SCALE);
        final Object numberEightCheck = validate(numberEight, mysqlMediumint);
        assertNotNull(numberEightCheck, "MEDIUMINT 必须使用 24 位真实边界，不能按 JDBC INTEGER 判断");
        validateActual(numberEightCheck, BigDecimal.ZERO, MEDIUMINT_MAX, NO_FRACTIONAL_ROWS);
        expectIllegalArgument(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                validateActual(numberEightCheck, BigDecimal.ZERO, MEDIUMINT_OVERFLOW, NO_FRACTIONAL_ROWS);
            }
        }, "MEDIUMINT 上界之外的值必须在写入前拦截");

        Object fractionalNumber = column(Types.NUMERIC, "NUMBER", ORACLE_NUMBER_PRECISION_FIVE, FRACTION_SCALE);
        final Object fractionalCheck = validate(fractionalNumber, mysqlBigint);
        assertNotNull(fractionalCheck, "带小数 NUMBER -> BIGINT 应检查实际小数值");
        expectIllegalArgument(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                validateActual(fractionalCheck, BigDecimal.ONE, BigDecimal.TEN, ONE_FRACTIONAL_ROW);
            }
        }, "非整数数据不能写入 BIGINT");

        Object mysqlInteger = column(Types.INTEGER, "INTEGER", 10, INTEGER_SCALE);
        assertNull(validate(mysqlInteger, mysqlBigint), "INTEGER 的完整范围可安全写入 BIGINT");

        Object sourceDouble = column(Types.DOUBLE, "BINARY_DOUBLE", 15, -127);
        Object targetDouble = column(Types.DOUBLE, "DOUBLE", 7, INTEGER_SCALE);
        assertNull(validate(sourceDouble, targetDouble), "浮点 precision 语义不同，不应按十进制位数误拦截");

        final Object decimalTwentyTwo = column(Types.DECIMAL, "DECIMAL", ORACLE_NUMBER_PRECISION_TWENTY,
            FRACTION_SCALE);
        final Object decimalNineteenTwo = column(Types.DECIMAL, "DECIMAL", ORACLE_NUMBER_PRECISION_NINETEEN,
            FRACTION_SCALE);
        expectIllegalArgument(new CheckedRunnable() {
            @Override
            public void run() throws Exception {
                validate(decimalTwentyTwo, decimalNineteenTwo);
            }
        }, "精确 DECIMAL 的明确缩窄仍应拦截");
    }

    private Object column(int jdbcType, String typeName, int precision, int scale) throws Exception {
        return columnMetaConstructor.newInstance(FIELD_NAME, jdbcType, typeName, precision, scale,
            DatabaseMetaData.columnNullable, false);
    }

    private Object validate(Object source, Object target) throws Exception {
        return invoke(validateTypeCompatibility, engine, source, target, TARGET_TABLE);
    }

    private void validateActual(Object rangeCheck, BigDecimal min, BigDecimal max, long fractionalRows)
        throws Exception {
        invoke(validateActualNumericRange, engine, task, TARGET_TABLE, rangeCheck, min, max, fractionalRows);
    }

    private Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw exception;
        }
    }

    private void expectIllegalArgument(CheckedRunnable action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    private void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message);
        }
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
