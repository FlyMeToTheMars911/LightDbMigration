package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public class MigrationEngine {

    private static final String STEP_VALIDATING = "VALIDATING";
    private static final String STEP_VALIDATED = "VALIDATED";
    private static final String STEP_RUNNING = "RUNNING";
    private static final String STEP_SUCCEEDED = "SUCCEEDED";
    private static final String STEP_FAILED = "FAILED";
    private static final String VALIDATION_ISSUE_PREFIX = "表结构校验问题";
    private static final int JDBC_TIMESTAMP_WITH_TIMEZONE = 2014;
    private static final int JDBC_TIME_WITH_TIMEZONE = 2013;
    private static final int UNKNOWN_PRECISION = 0;
    private static final int INTEGER_SCALE = 0;
    private static final int DEFAULT_BIT_PRECISION = 1;
    private static final int MAX_DECLARED_DECIMAL_PRECISION = 1000;
    private static final long NO_FRACTIONAL_ROWS = 0L;
    private static final String UNSIGNED_TYPE_MARKER = "UNSIGNED";
    private static final String TYPE_BOOLEAN = "BOOLEAN";
    private static final String TYPE_BOOL = "BOOL";
    private static final String TYPE_BIT = "BIT";
    private static final String TYPE_TINYINT = "TINYINT";
    private static final String TYPE_SMALLINT = "SMALLINT";
    private static final String TYPE_MEDIUMINT = "MEDIUMINT";
    private static final String TYPE_INTEGER = "INTEGER";
    private static final String TYPE_INT = "INT";
    private static final String TYPE_BIGINT = "BIGINT";
    private static final String SCRIPT_LINE_BREAK = "\n";
    private static final String MYSQL_TABLE_NOT_FOUND_STATE = "42S02";
    private static final String SQLSTATE_CONNECTION_PREFIX = "08";
    private static final int MYSQL_TABLE_NOT_FOUND_CODE = 1146;
    private static final int ORACLE_TABLE_NOT_FOUND_CODE = 942;
    private static final int MYSQL_MAX_DECIMAL_PRECISION = 65;
    private static final int MYSQL_MAX_DECIMAL_SCALE = 30;
    private static final int ORACLE_MAX_NUMBER_PRECISION = 38;
    private static final int MYSQL_SUGGESTED_VARCHAR_LIMIT = 4000;
    private static final int ORACLE_MAX_VARCHAR_LENGTH = 4000;
    private static final int ORACLE_MAX_NVARCHAR_LENGTH = 2000;
    private static final BigDecimal BOOLEAN_MIN = BigDecimal.ZERO;
    private static final BigDecimal BOOLEAN_MAX = BigDecimal.ONE;
    private static final BigDecimal TINYINT_MIN = BigDecimal.valueOf(Byte.MIN_VALUE);
    private static final BigDecimal TINYINT_MAX = BigDecimal.valueOf(Byte.MAX_VALUE);
    private static final BigDecimal UNSIGNED_TINYINT_MAX = new BigDecimal("255");
    private static final BigDecimal SMALLINT_MIN = BigDecimal.valueOf(Short.MIN_VALUE);
    private static final BigDecimal SMALLINT_MAX = BigDecimal.valueOf(Short.MAX_VALUE);
    private static final BigDecimal UNSIGNED_SMALLINT_MAX = new BigDecimal("65535");
    private static final BigDecimal MEDIUMINT_MIN = new BigDecimal("-8388608");
    private static final BigDecimal MEDIUMINT_MAX = new BigDecimal("8388607");
    private static final BigDecimal UNSIGNED_MEDIUMINT_MAX = new BigDecimal("16777215");
    private static final BigDecimal INTEGER_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INTEGER_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal UNSIGNED_INTEGER_MAX = new BigDecimal("4294967295");
    private static final BigDecimal BIGINT_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal BIGINT_MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final BigDecimal UNSIGNED_BIGINT_MAX = new BigDecimal("18446744073709551615");

    public void execute(MigrationTask task) {
        Connection sourceConnection = null;
        Connection targetConnection = null;
        int currentStep = -1;
        try {
            task.checkpoint();
            task.markStarted();
            CreateTaskRequest request = task.getRequest();
            String sourceType = DatabaseSupport.normalizeType(request.getSource().getType());
            String targetType = DatabaseSupport.normalizeType(request.getTarget().getType());
            task.info("正在连接源库 " + sourceType + " " + DatabaseSupport.displayEndpoint(request.getSource()));
            sourceConnection = DatabaseSupport.open(request.getSource());
            try {
                sourceConnection.setReadOnly(true);
            } catch (SQLException exception) {
                task.warn("源库驱动不支持只读连接标记，将继续使用仅允许 SELECT/WITH 的查询限制");
            }
            task.info("源库连接成功");
            task.checkpoint();

            task.info("正在连接目标库 " + targetType + " " + DatabaseSupport.displayEndpoint(request.getTarget()));
            targetConnection = DatabaseSupport.open(request.getTarget());
            targetConnection.setAutoCommit(false);
            task.info("目标库连接成功");

            List<PreparedStep> preparedSteps = prepareAllSteps(task, sourceConnection, targetConnection,
                sourceType, targetType);
            long totalRows = 0L;
            for (PreparedStep preparedStep : preparedSteps) {
                totalRows += preparedStep.totalRows;
            }
            task.setTotalRows(totalRows);
            task.info("全部表结构校验通过，预计迁移 " + totalRows + " 行，开始按配置顺序执行");

            for (PreparedStep step : preparedSteps) {
                currentStep = step.index;
                task.checkpoint();
                task.setStepStatus(step.index, STEP_RUNNING);
                task.markRunning("正在执行步骤 " + (step.index + 1) + "：" + step.displayName);
                task.info("开始步骤 " + (step.index + 1) + "/" + preparedSteps.size() + "："
                    + step.displayName + " -> " + step.targetName.toSql());
                if (step.cursorIndex >= 0) {
                    migrateByKeyset(task, sourceConnection, targetConnection, step, request.getBatchSize(), sourceType,
                        targetType);
                } else {
                    migrateByStreaming(task, sourceConnection, targetConnection, step, request.getBatchSize(), targetType);
                }
                task.setStepStatus(step.index, STEP_SUCCEEDED);
                task.info("步骤 " + step.displayName + " 执行完成");
            }
            task.checkpoint();
            task.markSucceeded();
        } catch (StopRequestedException exception) {
            rollbackQuietly(targetConnection);
            task.markTerminated();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rollbackQuietly(targetConnection);
            if (task.isStopRequested()) {
                task.markTerminated();
            } else {
                if (currentStep >= 0) {
                    task.setStepStatus(currentStep, STEP_FAILED);
                }
                task.markFailed("迁移线程被中断");
            }
        } catch (Exception exception) {
            rollbackQuietly(targetConnection);
            if (task.isStopRequested()) {
                task.markTerminated();
            } else {
                if (currentStep >= 0) {
                    task.setStepStatus(currentStep, STEP_FAILED);
                }
                task.markFailed("迁移失败：" + SqlSupport.safeMessage(exception));
            }
        } finally {
            closeQuietly(targetConnection);
            closeQuietly(sourceConnection);
            task.clearPasswords();
        }
    }

    private List<PreparedStep> prepareAllSteps(MigrationTask task, Connection sourceConnection,
        Connection targetConnection, String sourceType, String targetType) throws Exception {
        List<PreparedStep> result = new ArrayList<>();
        Set<String> validationIssues = new LinkedHashSet<>();
        Set<String> synchronizationStatements = new LinkedHashSet<>();
        List<MigrationStepRequest> steps = task.getRequest().getSteps();
        for (int i = 0; i < steps.size(); i++) {
            task.checkpoint();
            MigrationStepRequest request = steps.get(i);
            String stepLabel = stepLabel(request, i);
            int issueCountBeforeStep = validationIssues.size();
            task.setStepStatus(i, STEP_VALIDATING);
            task.markValidating("正在校验" + stepLabel);
            try {
                PreparedStep preparedStep = prepareStep(task, sourceConnection, targetConnection, request, i,
                    sourceType, targetType, validationIssues, synchronizationStatements, stepLabel);
                if (preparedStep != null && validationIssues.size() == issueCountBeforeStep) {
                    result.add(preparedStep);
                    task.setStepTotal(i, preparedStep.totalRows);
                    task.setStepStatus(i, STEP_VALIDATED);
                    task.info("结构校验通过：" + preparedStep.displayName + "，匹配 "
                        + preparedStep.sourceColumns.size() + " 个字段，预计 " + preparedStep.totalRows + " 行");
                }
            } catch (StopRequestedException exception) {
                throw exception;
            } catch (InterruptedException exception) {
                throw exception;
            } catch (Exception exception) {
                throwIfControlRequested(task);
                if (isConnectionFailure(exception)) {
                    throw exception;
                }
                addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
            }
            if (validationIssues.size() > issueCountBeforeStep) {
                task.setStepStatus(i, STEP_FAILED);
                task.warn(stepLabel + "校验不通过，发现 "
                    + (validationIssues.size() - issueCountBeforeStep) + " 个问题，继续校验后续步骤");
            }
        }
        if (!validationIssues.isEmpty()) {
            task.setStructureSyncScript(buildStructureSyncScript(targetType, validationIssues,
                synchronizationStatements));
            task.info("已生成目标表结构同步建议脚本，请在任务日志下方审核、复制或下载；工具不会自动执行");
            task.error("表结构校验未通过，共发现 " + validationIssues.size() + " 个问题：");
            int issueNumber = 1;
            for (String validationIssue : validationIssues) {
                task.error(VALIDATION_ISSUE_PREFIX + " " + issueNumber++ + "：" + validationIssue);
            }
            throw new IllegalArgumentException("表结构校验未通过，共发现 " + validationIssues.size()
                + " 个问题，已一次性输出到任务日志");
        }
        return result;
    }

    private PreparedStep prepareStep(MigrationTask task, Connection sourceConnection, Connection targetConnection,
        MigrationStepRequest request, int index, String sourceType, String targetType,
        Set<String> validationIssues, Set<String> synchronizationStatements, String stepLabel) throws Exception {
        int issueCountBeforeStep = validationIssues.size();
        String displayName = isBlank(request.getName()) ? "SQL " + (index + 1) : request.getName().trim();
        String sourceSql = null;
        QualifiedName targetName = null;
        try {
            sourceSql = SqlSupport.normalizeSourceQuery(request.getSourceSql());
        } catch (Exception exception) {
            addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
        }
        try {
            targetName = SqlSupport.parseTargetTable(request.getTargetTable());
        } catch (Exception exception) {
            addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
        }
        if (sourceSql == null || targetName == null) {
            return null;
        }

        List<ColumnMeta> sourceColumns = null;
        List<ColumnMeta> allTargetColumns = null;
        Exception targetMetadataException = null;
        try {
            sourceColumns = readQueryColumns(task, sourceConnection, sourceSql);
        } catch (Exception exception) {
            throwIfControlRequested(task);
            if (isConnectionFailure(exception)) {
                throw exception;
            }
            addValidationIssue(validationIssues, stepLabel,
                "源查询无法读取字段结构：" + SqlSupport.safeMessage(exception));
        }
        try {
            allTargetColumns = readTargetColumns(task, targetConnection, targetName);
        } catch (Exception exception) {
            throwIfControlRequested(task);
            if (isConnectionFailure(exception)) {
                throw exception;
            }
            targetMetadataException = exception;
            addValidationIssue(validationIssues, stepLabel,
                "目标表 " + targetName.toSql() + " 无法读取字段结构：" + SqlSupport.safeMessage(exception));
        }
        if (sourceColumns != null && allTargetColumns == null
            && isMissingTable(targetMetadataException, targetType)) {
            addCreateTableStatement(synchronizationStatements, targetName, sourceColumns, targetType);
        }
        if (sourceColumns == null || allTargetColumns == null) {
            return null;
        }
        if (sourceColumns.isEmpty()) {
            addValidationIssue(validationIssues, stepLabel, "源查询没有返回字段");
            return null;
        }

        Map<String, ColumnMeta> targetColumnMap = toColumnMap(allTargetColumns,
            "目标表 " + targetName.toSql(), validationIssues, stepLabel);
        toColumnMap(sourceColumns, "源查询结果", validationIssues, stepLabel);
        List<ColumnMeta> selectedTargetColumns = new ArrayList<>();
        List<NumericRangeCheck> numericRangeChecks = new ArrayList<>();
        Set<String> selectedNames = new LinkedHashSet<>();
        for (ColumnMeta sourceColumn : sourceColumns) {
            String normalizedName = normalizeName(sourceColumn.name);
            ColumnMeta targetColumn = targetColumnMap.get(normalizedName);
            if (targetColumn == null) {
                addValidationIssue(validationIssues, stepLabel, "源查询字段 " + sourceColumn.name
                    + " 在目标表 " + targetName.toSql() + " 中不存在");
                addColumnStatement(synchronizationStatements, targetName, sourceColumn, targetType);
                continue;
            }
            try {
                NumericRangeCheck numericRangeCheck = validateTypeCompatibility(sourceColumn, targetColumn,
                    targetName.toSql());
                if (numericRangeCheck != null) {
                    numericRangeChecks.add(numericRangeCheck);
                }
            } catch (IllegalArgumentException exception) {
                addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
                addModifyColumnStatement(synchronizationStatements, targetName, sourceColumn, targetType);
            }
            selectedTargetColumns.add(targetColumn);
            selectedNames.add(normalizedName);
        }

        try {
            validateRequiredTargetColumns(task, targetConnection, targetName, targetType, allTargetColumns,
                selectedNames, validationIssues, stepLabel);
        } catch (Exception exception) {
            throwIfControlRequested(task);
            if (isConnectionFailure(exception)) {
                throw exception;
            }
            addValidationIssue(validationIssues, stepLabel,
                "目标表必填字段规则读取失败：" + SqlSupport.safeMessage(exception));
        }
        int cursorIndex = -1;
        try {
            cursorIndex = resolveCursorIndex(request.getCursorColumn(), sourceColumns);
        } catch (IllegalArgumentException exception) {
            addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
        }
        long totalRows = 0L;
        try {
            ColumnMeta cursorColumn = cursorIndex >= 0 ? sourceColumns.get(cursorIndex) : null;
            totalRows = queryCountAndValidateNumericRanges(task, sourceConnection, sourceSql, sourceType,
                targetName, targetType, cursorColumn, numericRangeChecks, validationIssues,
                synchronizationStatements, stepLabel);
        } catch (Exception exception) {
            throwIfControlRequested(task);
            if (isConnectionFailure(exception)) {
                throw exception;
            }
            addValidationIssue(validationIssues, stepLabel,
                "源查询统计失败：" + SqlSupport.safeMessage(exception));
        }
        if (validationIssues.size() > issueCountBeforeStep) {
            return null;
        }

        List<String> uniqueKey = loadUsableUniqueKey(targetConnection, targetName, targetType, selectedTargetColumns);
        String insertMode = normalizeInsertMode(request.getInsertMode());
        String plainInsertSql = buildInsertSql(targetName, selectedTargetColumns, targetType,
            MigrationConstants.INSERT_IGNORE.equals(insertMode));
        String writeSql = plainInsertSql;
        boolean oracleMerge = false;
        if (MigrationConstants.DB_ORACLE.equals(targetType)
            && MigrationConstants.INSERT_IGNORE.equals(insertMode) && !uniqueKey.isEmpty()) {
            writeSql = buildOracleMergeSql(targetName, selectedTargetColumns, uniqueKey);
            oracleMerge = true;
            task.info("步骤 " + request.getName() + "：Oracle 重复数据将按唯一键 " + uniqueKey + " 使用 MERGE 跳过");
        } else if (MigrationConstants.DB_ORACLE.equals(targetType)
            && MigrationConstants.INSERT_IGNORE.equals(insertMode)) {
            task.warn("步骤 " + request.getName()
                + "：目标表没有可用于 MERGE 的完整主键/唯一键，发生 ORA-00001 时将回退逐行跳过重复数据");
        }

        if (cursorIndex >= 0) {
            task.info("步骤 " + displayName + " 按页面配置启用游标分页，字段="
                + sourceColumns.get(cursorIndex).name + "；不依赖数据库自增标记或数值类型");
        } else {
            task.info("步骤 " + displayName + " 使用单次流式查询并分批写入");
        }
        return new PreparedStep(index, displayName, sourceSql, targetName, sourceColumns, selectedTargetColumns,
            cursorIndex, totalRows, insertMode, writeSql, buildStrictInsertSql(targetName, selectedTargetColumns, targetType),
            oracleMerge);
    }

    private String stepLabel(MigrationStepRequest request, int index) {
        String displayName = request == null || isBlank(request.getName())
            ? "SQL " + (index + 1) : request.getName().trim();
        return "步骤 " + (index + 1) + "（" + displayName + "）";
    }

    private void addValidationIssue(Set<String> validationIssues, String stepLabel, String message) {
        validationIssues.add(stepLabel + "：" + message);
    }

    private void throwIfControlRequested(MigrationTask task) throws StopRequestedException, InterruptedException {
        if (task.isStopRequested()) {
            throw new StopRequestedException();
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("迁移线程被中断");
        }
    }

    private String buildStructureSyncScript(String targetType, Set<String> validationIssues,
        Set<String> synchronizationStatements) {
        StringBuilder script = new StringBuilder();
        script.append("-- Light DB Migration 表结构同步建议脚本").append(SCRIPT_LINE_BREAK)
            .append("-- 目标数据库类型：").append(targetType).append(SCRIPT_LINE_BREAK)
            .append("-- 重要：本脚本仅根据源查询结果元数据生成，工具不会自动执行。").append(SCRIPT_LINE_BREAK)
            .append("-- 执行前必须备份目标表，并由开发/DBA 核对主键、索引、默认值、非空约束、")
            .append("字符集、排序规则和字段注释。").append(SCRIPT_LINE_BREAK)
            .append(SCRIPT_LINE_BREAK)
            .append("-- 本次校验问题").append(SCRIPT_LINE_BREAK);
        int issueNumber = 1;
        for (String validationIssue : validationIssues) {
            script.append("-- ").append(issueNumber++).append(". ")
                .append(sanitizeScriptComment(validationIssue)).append(SCRIPT_LINE_BREAK);
        }
        script.append(SCRIPT_LINE_BREAK).append("-- 建议 DDL").append(SCRIPT_LINE_BREAK);
        if (synchronizationStatements.isEmpty()) {
            script.append("-- 当前问题无法安全转换为自动 DDL，请按上方问题人工调整源 SQL、游标配置或目标约束。")
                .append(SCRIPT_LINE_BREAK);
        } else {
            for (String statement : synchronizationStatements) {
                script.append(statement).append(SCRIPT_LINE_BREAK).append(SCRIPT_LINE_BREAK);
            }
        }
        return script.toString().trim() + SCRIPT_LINE_BREAK;
    }

    private void addCreateTableStatement(Set<String> statements, QualifiedName targetName,
        List<ColumnMeta> sourceColumns, String targetType) {
        Set<String> columnNames = new LinkedHashSet<>();
        List<String> definitions = new ArrayList<>();
        for (ColumnMeta sourceColumn : sourceColumns) {
            String normalizedName = normalizeName(sourceColumn.name);
            if (!columnNames.add(normalizedName)) {
                statements.add("-- 未生成 " + quotedQualifiedName(targetName, targetType)
                    + " 的 CREATE TABLE：源查询存在重复字段 "
                    + sanitizeScriptComment(sourceColumn.name) + "，请先修改查询别名。");
                return;
            }
            String typeDefinition = targetTypeDefinition(sourceColumn, targetType);
            if (typeDefinition == null) {
                statements.add("-- 未生成 " + quotedQualifiedName(targetName, targetType)
                    + " 的 CREATE TABLE：字段 " + sanitizeScriptComment(sourceColumn.name)
                    + " 的源类型 " + sanitizeScriptComment(sourceColumn.typeName) + " 无法安全映射，请人工定义。");
                return;
            }
            definitions.add("    " + quotedColumnName(sourceColumn.name, targetType) + " " + typeDefinition
                + " NULL");
        }
        statements.add("-- 已确认目标表确实不存在后再执行；主键、索引和注释需要另行补充。"
            + SCRIPT_LINE_BREAK + "CREATE TABLE " + quotedQualifiedName(targetName, targetType)
            + " (" + SCRIPT_LINE_BREAK + join(definitions, "," + SCRIPT_LINE_BREAK)
            + SCRIPT_LINE_BREAK + ");");
    }

    private void addColumnStatement(Set<String> statements, QualifiedName targetName, ColumnMeta sourceColumn,
        String targetType) {
        String typeDefinition = targetTypeDefinition(sourceColumn, targetType);
        if (typeDefinition == null) {
            statements.add("-- 目标表 " + quotedQualifiedName(targetName, targetType) + " 缺少字段 "
                + quotedColumnName(sourceColumn.name, targetType) + "，但源类型 "
                + sanitizeScriptComment(sourceColumn.typeName) + " 无法安全映射，请人工补充。");
            return;
        }
        if (MigrationConstants.DB_MYSQL.equals(targetType)) {
            statements.add("ALTER TABLE " + quotedQualifiedName(targetName, targetType) + " ADD COLUMN "
                + quotedColumnName(sourceColumn.name, targetType) + " " + typeDefinition + " NULL;");
        } else {
            statements.add("ALTER TABLE " + quotedQualifiedName(targetName, targetType) + " ADD ("
                + quotedColumnName(sourceColumn.name, targetType) + " " + typeDefinition + " NULL);");
        }
    }

    private void addModifyColumnStatement(Set<String> statements, QualifiedName targetName, ColumnMeta sourceColumn,
        String targetType) {
        String typeDefinition = targetTypeDefinition(sourceColumn, targetType);
        if (typeDefinition == null) {
            statements.add("-- 目标表 " + quotedQualifiedName(targetName, targetType) + " 的字段 "
                + quotedColumnName(sourceColumn.name, targetType) + " 需要调整，但源类型 "
                + sanitizeScriptComment(sourceColumn.typeName) + " 无法安全映射，请人工处理。");
            return;
        }
        if (MigrationConstants.DB_MYSQL.equals(targetType)) {
            statements.add("-- MySQL MODIFY COLUMN 可能覆盖原 DEFAULT、COMMENT、字符集等属性，执行前请用 SHOW CREATE TABLE 补齐。"
                + SCRIPT_LINE_BREAK + "ALTER TABLE " + quotedQualifiedName(targetName, targetType)
                + " MODIFY COLUMN " + quotedColumnName(sourceColumn.name, targetType) + " "
                + typeDefinition + ";");
        } else {
            statements.add("ALTER TABLE " + quotedQualifiedName(targetName, targetType) + " MODIFY ("
                + quotedColumnName(sourceColumn.name, targetType) + " " + typeDefinition + ");");
        }
    }

    private String targetTypeDefinition(ColumnMeta sourceColumn, String targetType) {
        int sourceGroup = typeGroup(sourceColumn.jdbcType);
        if (sourceGroup == TypeGroup.NUMBER) {
            if (isApproximateNumeric(sourceColumn.jdbcType)) {
                return MigrationConstants.DB_MYSQL.equals(targetType) ? "DOUBLE" : "BINARY_DOUBLE";
            }
            if (sourceColumn.jdbcType == Types.BOOLEAN) {
                return MigrationConstants.DB_MYSQL.equals(targetType) ? "TINYINT(1)" : "NUMBER(1,0)";
            }
            if (sourceColumn.jdbcType == Types.BIT && MigrationConstants.DB_MYSQL.equals(targetType)) {
                int precision = sourceColumn.precision > UNKNOWN_PRECISION
                    ? sourceColumn.precision : DEFAULT_BIT_PRECISION;
                return "BIT(" + precision + ")";
            }
            int precision = sourceColumn.precision;
            int scale = sourceColumn.scale;
            if (MigrationConstants.DB_MYSQL.equals(targetType)) {
                if (precision <= UNKNOWN_PRECISION || precision > MYSQL_MAX_DECIMAL_PRECISION
                    || scale < INTEGER_SCALE || scale > MYSQL_MAX_DECIMAL_SCALE || scale > precision) {
                    return null;
                }
                return "DECIMAL(" + precision + "," + scale + ")";
            }
            if (precision <= UNKNOWN_PRECISION) {
                return "NUMBER";
            }
            if (precision > ORACLE_MAX_NUMBER_PRECISION) {
                return null;
            }
            return "NUMBER(" + precision + "," + scale + ")";
        }
        if (sourceGroup == TypeGroup.TEXT) {
            boolean nationalText = sourceColumn.jdbcType == Types.NCHAR || sourceColumn.jdbcType == Types.NVARCHAR
                || sourceColumn.jdbcType == Types.LONGNVARCHAR || sourceColumn.jdbcType == Types.NCLOB;
            boolean longText = sourceColumn.jdbcType == Types.LONGVARCHAR
                || sourceColumn.jdbcType == Types.LONGNVARCHAR || sourceColumn.jdbcType == Types.CLOB
                || sourceColumn.jdbcType == Types.NCLOB || sourceColumn.precision <= UNKNOWN_PRECISION;
            if (MigrationConstants.DB_MYSQL.equals(targetType)) {
                return longText || sourceColumn.precision > MYSQL_SUGGESTED_VARCHAR_LIMIT
                    ? "LONGTEXT" : "VARCHAR(" + sourceColumn.precision + ")";
            }
            if (nationalText) {
                return longText || sourceColumn.precision > ORACLE_MAX_NVARCHAR_LENGTH
                    ? "NCLOB" : "NVARCHAR2(" + sourceColumn.precision + ")";
            }
            return longText || sourceColumn.precision > ORACLE_MAX_VARCHAR_LENGTH
                ? "CLOB" : "VARCHAR2(" + sourceColumn.precision + " CHAR)";
        }
        if (sourceGroup == TypeGroup.TIME) {
            if (MigrationConstants.DB_MYSQL.equals(targetType)) {
                if (sourceColumn.jdbcType == Types.DATE) {
                    return "DATE";
                }
                if (sourceColumn.jdbcType == Types.TIME || sourceColumn.jdbcType == JDBC_TIME_WITH_TIMEZONE) {
                    return "TIME(6)";
                }
                return "DATETIME(6)";
            }
            return sourceColumn.jdbcType == Types.DATE ? "DATE" : "TIMESTAMP(6)";
        }
        if (sourceGroup == TypeGroup.BINARY) {
            return MigrationConstants.DB_MYSQL.equals(targetType) ? "LONGBLOB" : "BLOB";
        }
        return null;
    }

    private String quotedQualifiedName(QualifiedName targetName, String targetType) {
        String table = scriptIdentifier(targetName.getTable(), targetType);
        if (targetName.getSchema() == null) {
            return SqlSupport.quoteIdentifier(table, targetType);
        }
        String schema = scriptIdentifier(targetName.getSchema(), targetType);
        return SqlSupport.quoteIdentifier(schema, targetType) + "." + SqlSupport.quoteIdentifier(table, targetType);
    }

    private String quotedColumnName(String columnName, String targetType) {
        return SqlSupport.quoteIdentifier(scriptIdentifier(columnName, targetType), targetType);
    }

    private String scriptIdentifier(String identifier, String targetType) {
        return MigrationConstants.DB_ORACLE.equals(targetType) ? identifier.toUpperCase(Locale.ROOT) : identifier;
    }

    private String sanitizeScriptComment(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private boolean isMissingTable(Exception exception, String targetType) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException)current;
                while (sqlException != null) {
                    if (MigrationConstants.DB_MYSQL.equals(targetType)
                        && (sqlException.getErrorCode() == MYSQL_TABLE_NOT_FOUND_CODE
                        || MYSQL_TABLE_NOT_FOUND_STATE.equals(sqlException.getSQLState()))) {
                        return true;
                    }
                    if (MigrationConstants.DB_ORACLE.equals(targetType)
                        && sqlException.getErrorCode() == ORACLE_TABLE_NOT_FOUND_CODE) {
                        return true;
                    }
                    sqlException = sqlException.getNextException();
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectionFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.sql.SQLNonTransientConnectionException
                || current instanceof java.sql.SQLRecoverableException
                || current instanceof java.sql.SQLTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException) {
                SQLException sqlException = (SQLException)current;
                while (sqlException != null) {
                    String sqlState = sqlException.getSQLState();
                    if (sqlState != null && sqlState.startsWith(SQLSTATE_CONNECTION_PREFIX)) {
                        return true;
                    }
                    sqlException = sqlException.getNextException();
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private List<ColumnMeta> readQueryColumns(MigrationTask task, Connection connection, String sourceSql)
        throws SQLException {
        String metadataSql = "SELECT * FROM (" + sourceSql + ") LM_SOURCE WHERE 1 = 0";
        try (PreparedStatement statement = connection.prepareStatement(metadataSql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readColumns(resultSet.getMetaData());
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private List<ColumnMeta> readTargetColumns(MigrationTask task, Connection connection, QualifiedName targetName)
        throws SQLException {
        String sql = "SELECT * FROM " + targetName.toSql() + " WHERE 1 = 0";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readColumns(resultSet.getMetaData());
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private List<ColumnMeta> readColumns(ResultSetMetaData metaData) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            if (isBlank(label)) {
                label = metaData.getColumnName(i);
            }
            columns.add(new ColumnMeta(label, metaData.getColumnType(i), metaData.getColumnTypeName(i),
                metaData.getPrecision(i), metaData.getScale(i), metaData.isNullable(i), metaData.isAutoIncrement(i)));
        }
        return columns;
    }

    private Map<String, ColumnMeta> toColumnMap(List<ColumnMeta> columns, String subject,
        Set<String> validationIssues, String stepLabel) {
        Map<String, ColumnMeta> result = new LinkedHashMap<>();
        Set<String> reportedDuplicates = new LinkedHashSet<>();
        for (ColumnMeta column : columns) {
            String key = normalizeName(column.name);
            if (result.containsKey(key)) {
                if (reportedDuplicates.add(key)) {
                    addValidationIssue(validationIssues, stepLabel,
                        subject + " 存在重复字段名或重复别名：" + column.name);
                }
                continue;
            }
            result.put(key, column);
        }
        return result;
    }

    private NumericRangeCheck validateTypeCompatibility(ColumnMeta source, ColumnMeta target, String targetTable) {
        int sourceGroup = typeGroup(source.jdbcType);
        int targetGroup = typeGroup(target.jdbcType);
        if (sourceGroup != targetGroup && sourceGroup != TypeGroup.UNKNOWN && targetGroup != TypeGroup.UNKNOWN) {
            throw new IllegalArgumentException("结构不一致：字段 " + source.name + " 源类型为 " + source.typeName
                + "，目标表 " + targetTable + " 类型为 " + target.typeName);
        }
        if (sourceGroup == TypeGroup.TEXT && source.precision > 0 && target.precision > 0
            && target.precision < source.precision && target.jdbcType != Types.CLOB && target.jdbcType != Types.NCLOB) {
            throw new IllegalArgumentException("结构不一致：字段 " + source.name + " 目标长度 " + target.precision
                + " 小于源长度 " + source.precision);
        }
        if (sourceGroup != TypeGroup.NUMBER || targetGroup != TypeGroup.NUMBER) {
            return null;
        }

        NumericRange targetIntegralRange = integralRange(target);
        if (targetIntegralRange != null) {
            boolean requireWholeNumberCheck = source.scale != INTEGER_SCALE || isApproximateNumeric(source.jdbcType);
            if (requireWholeNumberCheck || !declaredNumericRangeFits(source, targetIntegralRange)) {
                return new NumericRangeCheck(source, target, targetIntegralRange, requireWholeNumberCheck);
            }
            return null;
        }

        if (isExactNumeric(source.jdbcType) && isExactNumeric(target.jdbcType)
            && source.precision > UNKNOWN_PRECISION && target.precision > UNKNOWN_PRECISION
            && source.scale >= INTEGER_SCALE && target.scale >= INTEGER_SCALE) {
            int sourceIntegerDigits = source.precision - Math.max(0, source.scale);
            int targetIntegerDigits = target.precision - Math.max(0, target.scale);
            if (targetIntegerDigits < sourceIntegerDigits || target.scale < source.scale) {
                throw new IllegalArgumentException("结构不一致：字段 " + source.name + " 目标精度/小数位 "
                    + target.precision + "/" + target.scale + " 小于源 " + source.precision + "/" + source.scale);
            }
        }
        return null;
    }

    private void validateRequiredTargetColumns(MigrationTask task, Connection connection, QualifiedName targetName,
        String targetType, List<ColumnMeta> allTargetColumns, Set<String> selectedNames,
        Set<String> validationIssues, String stepLabel) throws SQLException {
        Map<String, TargetColumnRule> rules = loadTargetColumnRules(connection, targetName, targetType);
        if (rules.isEmpty()) {
            task.warn("未能读取目标表 " + targetName.toSql() + " 的默认值元数据，仅校验查询返回字段");
            return;
        }
        for (ColumnMeta column : allTargetColumns) {
            if (selectedNames.contains(normalizeName(column.name))) {
                continue;
            }
            TargetColumnRule rule = rules.get(normalizeName(column.name));
            if (rule != null && !rule.nullable && !rule.autoIncrement && !rule.hasDefault) {
                addValidationIssue(validationIssues, stepLabel, "目标表必填字段 " + column.name
                    + " 未包含在源查询结果中，且没有默认值");
            }
        }
    }

    private Map<String, TargetColumnRule> loadTargetColumnRules(Connection connection, QualifiedName targetName,
        String targetType) throws SQLException {
        MetadataLocation location = resolveMetadataLocation(connection, targetName, targetType);
        DatabaseMetaData metaData = connection.getMetaData();
        Map<String, TargetColumnRule> result = new HashMap<>();
        for (String tableCandidate : tableCandidates(targetName.getTable())) {
            try (ResultSet columns = metaData.getColumns(location.catalog, location.schema, tableCandidate, null)) {
                while (columns.next()) {
                    String name = columns.getString("COLUMN_NAME");
                    boolean nullable = columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                    String defaultValue = columns.getString("COLUMN_DEF");
                    boolean autoIncrement = "YES".equalsIgnoreCase(getOptionalString(columns, "IS_AUTOINCREMENT"));
                    result.put(normalizeName(name), new TargetColumnRule(nullable, autoIncrement, defaultValue != null));
                }
            }
            if (!result.isEmpty()) {
                break;
            }
        }
        return result;
    }

    private int resolveCursorIndex(String cursorColumn, List<ColumnMeta> sourceColumns) {
        if (isBlank(cursorColumn)) {
            return -1;
        }
        String normalized = normalizeName(cursorColumn);
        for (int i = 0; i < sourceColumns.size(); i++) {
            if (normalizeName(sourceColumns.get(i).name).equals(normalized)) {
                return i;
            }
        }
        throw new IllegalArgumentException("游标字段 " + cursorColumn + " 不在源查询结果中");
    }

    private long queryCountAndValidateNumericRanges(MigrationTask task, Connection connection, String sourceSql,
        String sourceType, QualifiedName targetName, String targetType, ColumnMeta cursorColumn,
        List<NumericRangeCheck> rangeChecks, Set<String> validationIssues,
        Set<String> synchronizationStatements, String stepLabel) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*)");
        if (cursorColumn != null) {
            String cursorReference = "LM_STATS." + SqlSupport.quoteIdentifier(cursorColumn.name, sourceType);
            sql.append(", COUNT(").append(cursorReference).append("), COUNT(DISTINCT ")
                .append(cursorReference).append(")");
        }
        for (NumericRangeCheck rangeCheck : rangeChecks) {
            String columnReference = "LM_STATS."
                + SqlSupport.quoteIdentifier(rangeCheck.source.name, sourceType);
            sql.append(", MIN(").append(columnReference).append("), MAX(").append(columnReference).append(")");
            if (rangeCheck.requireWholeNumberCheck) {
                sql.append(", SUM(CASE WHEN ").append(columnReference).append(" IS NOT NULL AND ")
                    .append(columnReference).append(" <> FLOOR(").append(columnReference)
                    .append(") THEN 1 ELSE 0 END)");
            }
        }
        sql.append(" FROM (").append(sourceSql).append(") LM_STATS");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("查询总数未返回结果");
                }
                long totalRows = resultSet.getLong(1);
                int resultIndex = 2;
                if (cursorColumn != null) {
                    long nonNullCursorRows = resultSet.getLong(resultIndex++);
                    long distinctCursorRows = resultSet.getLong(resultIndex++);
                    if (nonNullCursorRows != totalRows) {
                        addValidationIssue(validationIssues, stepLabel, "游标字段 " + cursorColumn.name
                            + " 存在 " + (totalRows - nonNullCursorRows) + " 条空值，无法安全进行游标分页");
                    }
                    if (distinctCursorRows != totalRows) {
                        addValidationIssue(validationIssues, stepLabel, "游标字段 " + cursorColumn.name
                            + " 在本次查询结果中不是唯一字段，共 " + totalRows + " 行、"
                            + distinctCursorRows + " 个非空唯一值");
                    }
                    if (nonNullCursorRows == totalRows && distinctCursorRows == totalRows) {
                        task.info("游标字段 " + cursorColumn.name
                            + " 已按页面配置认可，非空与唯一性校验通过；字段类型=" + cursorColumn.typeName);
                    }
                }
                for (NumericRangeCheck rangeCheck : rangeChecks) {
                    BigDecimal actualMin = resultSet.getBigDecimal(resultIndex++);
                    BigDecimal actualMax = resultSet.getBigDecimal(resultIndex++);
                    long fractionalRows = rangeCheck.requireWholeNumberCheck
                        ? resultSet.getLong(resultIndex++) : NO_FRACTIONAL_ROWS;
                    try {
                        validateActualNumericRange(task, targetName.toSql(), rangeCheck, actualMin, actualMax,
                            fractionalRows);
                    } catch (IllegalArgumentException exception) {
                        addValidationIssue(validationIssues, stepLabel, SqlSupport.safeMessage(exception));
                        addModifyColumnStatement(synchronizationStatements, targetName, rangeCheck.source,
                            targetType);
                    }
                }
                return totalRows;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private void validateActualNumericRange(MigrationTask task, String targetTable, NumericRangeCheck rangeCheck,
        BigDecimal actualMin, BigDecimal actualMax, long fractionalRows) {
        if (fractionalRows > NO_FRACTIONAL_ROWS) {
            throw new IllegalArgumentException("结构不一致：字段 " + rangeCheck.source.name + " 源数据存在 "
                + fractionalRows + " 条非整数值，目标表 " + targetTable + " 类型 "
                + rangeCheck.target.typeName + " 只能保存整数");
        }
        if ((actualMin != null && actualMin.compareTo(rangeCheck.targetRange.min) < 0)
            || (actualMax != null && actualMax.compareTo(rangeCheck.targetRange.max) > 0)) {
            throw new IllegalArgumentException("结构不一致：字段 " + rangeCheck.source.name + " 源查询实际范围 "
                + formatRange(actualMin, actualMax) + " 超出目标表 " + targetTable + " 类型 "
                + rangeCheck.target.typeName + " 可表示范围 "
                + formatRange(rangeCheck.targetRange.min, rangeCheck.targetRange.max));
        }
        task.info("字段 " + rangeCheck.source.name + "：源类型 " + describeNumericType(rangeCheck.source)
            + " 与目标类型 " + describeNumericType(rangeCheck.target) + " 的精度定义不同，已按本次查询实际范围 "
            + formatRange(actualMin, actualMax) + " 校验通过");
    }

    private boolean declaredNumericRangeFits(ColumnMeta source, NumericRange targetRange) {
        NumericRange sourceIntegralRange = integralRange(source);
        if (sourceIntegralRange != null) {
            return targetRange.contains(sourceIntegralRange);
        }
        if (!isExactNumeric(source.jdbcType) || source.precision <= UNKNOWN_PRECISION
            || source.scale < INTEGER_SCALE || source.precision > MAX_DECLARED_DECIMAL_PRECISION) {
            return false;
        }
        int integerDigits = Math.max(INTEGER_SCALE, source.precision - source.scale);
        BigDecimal declaredMax = BigDecimal.TEN.pow(integerDigits).subtract(BigDecimal.ONE);
        BigDecimal declaredMin = isUnsigned(source) ? BigDecimal.ZERO : declaredMax.negate();
        return targetRange.contains(new NumericRange(declaredMin, declaredMax));
    }

    private NumericRange integralRange(ColumnMeta column) {
        boolean unsigned = isUnsigned(column);
        String baseTypeName = baseTypeName(column.typeName);
        if (TYPE_BOOLEAN.equals(baseTypeName) || TYPE_BOOL.equals(baseTypeName)) {
            return new NumericRange(BOOLEAN_MIN, BOOLEAN_MAX);
        }
        if (TYPE_BIT.equals(baseTypeName)) {
            return bitRange(column.precision);
        }
        if (TYPE_TINYINT.equals(baseTypeName)) {
            return new NumericRange(unsigned ? BigDecimal.ZERO : TINYINT_MIN,
                unsigned ? UNSIGNED_TINYINT_MAX : TINYINT_MAX);
        }
        if (TYPE_SMALLINT.equals(baseTypeName)) {
            return new NumericRange(unsigned ? BigDecimal.ZERO : SMALLINT_MIN,
                unsigned ? UNSIGNED_SMALLINT_MAX : SMALLINT_MAX);
        }
        if (TYPE_MEDIUMINT.equals(baseTypeName)) {
            return new NumericRange(unsigned ? BigDecimal.ZERO : MEDIUMINT_MIN,
                unsigned ? UNSIGNED_MEDIUMINT_MAX : MEDIUMINT_MAX);
        }
        if (TYPE_INTEGER.equals(baseTypeName) || TYPE_INT.equals(baseTypeName)) {
            return new NumericRange(unsigned ? BigDecimal.ZERO : INTEGER_MIN,
                unsigned ? UNSIGNED_INTEGER_MAX : INTEGER_MAX);
        }
        if (TYPE_BIGINT.equals(baseTypeName)) {
            return new NumericRange(unsigned ? BigDecimal.ZERO : BIGINT_MIN,
                unsigned ? UNSIGNED_BIGINT_MAX : BIGINT_MAX);
        }
        switch (column.jdbcType) {
            case Types.BOOLEAN:
                return new NumericRange(BOOLEAN_MIN, BOOLEAN_MAX);
            case Types.BIT:
                return bitRange(column.precision);
            case Types.TINYINT:
                return new NumericRange(unsigned ? BigDecimal.ZERO : TINYINT_MIN,
                    unsigned ? UNSIGNED_TINYINT_MAX : TINYINT_MAX);
            case Types.SMALLINT:
                return new NumericRange(unsigned ? BigDecimal.ZERO : SMALLINT_MIN,
                    unsigned ? UNSIGNED_SMALLINT_MAX : SMALLINT_MAX);
            case Types.INTEGER:
                return new NumericRange(unsigned ? BigDecimal.ZERO : INTEGER_MIN,
                    unsigned ? UNSIGNED_INTEGER_MAX : INTEGER_MAX);
            case Types.BIGINT:
                return new NumericRange(unsigned ? BigDecimal.ZERO : BIGINT_MIN,
                    unsigned ? UNSIGNED_BIGINT_MAX : BIGINT_MAX);
            default:
                return null;
        }
    }

    private NumericRange bitRange(int precision) {
        int bitPrecision = precision > UNKNOWN_PRECISION ? precision : DEFAULT_BIT_PRECISION;
        BigInteger bitMax = BigInteger.ONE.shiftLeft(bitPrecision).subtract(BigInteger.ONE);
        return new NumericRange(BigDecimal.ZERO, new BigDecimal(bitMax));
    }

    private boolean isExactNumeric(int jdbcType) {
        return jdbcType == Types.TINYINT || jdbcType == Types.SMALLINT || jdbcType == Types.INTEGER
            || jdbcType == Types.BIGINT || jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL
            || jdbcType == Types.BIT || jdbcType == Types.BOOLEAN;
    }

    private boolean isApproximateNumeric(int jdbcType) {
        return jdbcType == Types.FLOAT || jdbcType == Types.REAL || jdbcType == Types.DOUBLE;
    }

    private boolean isUnsigned(ColumnMeta column) {
        return column.typeName != null
            && column.typeName.toUpperCase(Locale.ROOT).contains(UNSIGNED_TYPE_MARKER);
    }

    private String baseTypeName(String typeName) {
        if (typeName == null) {
            return "";
        }
        return typeName.toUpperCase(Locale.ROOT).replace(UNSIGNED_TYPE_MARKER, "").trim();
    }

    private String describeNumericType(ColumnMeta column) {
        if (column.precision <= UNKNOWN_PRECISION) {
            return column.typeName;
        }
        return column.typeName + "(" + column.precision + "," + column.scale + ")";
    }

    private String formatRange(BigDecimal min, BigDecimal max) {
        return "[" + (min == null ? "NULL" : min.toPlainString()) + ", "
            + (max == null ? "NULL" : max.toPlainString()) + "]";
    }

    private void migrateByStreaming(MigrationTask task, Connection sourceConnection, Connection targetConnection,
        PreparedStep step, int batchSize, String targetType) throws Exception {
        try (PreparedStatement statement = sourceConnection.prepareStatement(step.sourceSql,
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(batchSize);
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Object[]> batch = new ArrayList<>(batchSize);
                while (resultSet.next()) {
                    batch.add(readRow(resultSet, step.sourceColumns));
                    if (batch.size() >= batchSize) {
                        writeAndReportBatch(task, targetConnection, step, batch, targetType);
                        batch.clear();
                        task.checkpoint();
                    }
                }
                if (!batch.isEmpty()) {
                    writeAndReportBatch(task, targetConnection, step, batch, targetType);
                    task.checkpoint();
                }
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private void migrateByKeyset(MigrationTask task, Connection sourceConnection, Connection targetConnection,
        PreparedStep step, int batchSize, String sourceType, String targetType) throws Exception {
        ColumnMeta cursorColumn = step.sourceColumns.get(step.cursorIndex);
        Object upperBound = queryCursorUpperBound(task, sourceConnection, step, cursorColumn, sourceType);
        if (upperBound == null) {
            return;
        }
        Object lastKey = null;
        while (true) {
            task.checkpoint();
            KeysetSql keysetSql = buildKeysetSql(step, cursorColumn, sourceType, batchSize, lastKey != null);
            List<Object[]> batch = new ArrayList<>(batchSize);
            Object currentLastKey = null;
            try (PreparedStatement statement = sourceConnection.prepareStatement(keysetSql.sql)) {
                int parameterIndex = 1;
                if (lastKey != null) {
                    statement.setObject(parameterIndex++, lastKey);
                }
                statement.setObject(parameterIndex, upperBound);
                statement.setFetchSize(batchSize);
                task.setCurrentStatement(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Object[] row = readRow(resultSet, step.sourceColumns);
                        Object key = row[step.cursorIndex];
                        if (key == null) {
                            throw new IllegalArgumentException("游标字段 " + cursorColumn.name + " 存在空值");
                        }
                        if (currentLastKey != null && cursorValuesEqual(currentLastKey, key)) {
                            throw new IllegalArgumentException("游标字段 " + cursorColumn.name
                                + " 出现重复值，当前值=" + key);
                        }
                        currentLastKey = key;
                        batch.add(row);
                    }
                } finally {
                    task.clearCurrentStatement(statement);
                }
            }
            if (batch.isEmpty()) {
                break;
            }
            if (lastKey != null && cursorValuesEqual(lastKey, currentLastKey)) {
                throw new IllegalArgumentException("游标字段未推进，当前值=" + currentLastKey);
            }
            writeAndReportBatch(task, targetConnection, step, batch, targetType);
            lastKey = currentLastKey;
            if (batch.size() < batchSize || cursorValuesEqual(lastKey, upperBound)) {
                break;
            }
        }
    }

    private Object queryCursorUpperBound(MigrationTask task, Connection connection, PreparedStep step,
        ColumnMeta cursorColumn, String sourceType) throws SQLException {
        String quotedCursor = SqlSupport.quoteIdentifier(cursorColumn.name, sourceType);
        String sql = "SELECT MAX(LM_SOURCE." + quotedCursor + ") FROM (" + step.sourceSql + ") LM_SOURCE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getObject(1) : null;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private KeysetSql buildKeysetSql(PreparedStep step, ColumnMeta cursorColumn, String sourceType, int batchSize,
        boolean hasLastKey) {
        String quotedCursor = SqlSupport.quoteIdentifier(cursorColumn.name, sourceType);
        StringBuilder conditions = new StringBuilder();
        if (hasLastKey) {
            conditions.append("LM_SOURCE.").append(quotedCursor).append(" > ? AND ");
        }
        conditions.append("LM_SOURCE.").append(quotedCursor).append(" <= ?");
        String ordered = "SELECT LM_SOURCE.* FROM (" + step.sourceSql + ") LM_SOURCE WHERE " + conditions
            + " ORDER BY LM_SOURCE." + quotedCursor + " ASC";
        if (MigrationConstants.DB_MYSQL.equals(sourceType)) {
            return new KeysetSql(ordered + " LIMIT " + batchSize);
        }
        return new KeysetSql("SELECT * FROM (" + ordered + ") WHERE ROWNUM <= " + batchSize);
    }

    private Object[] readRow(ResultSet resultSet, List<ColumnMeta> columns) throws SQLException {
        Object[] row = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            int columnIndex = i + 1;
            int jdbcType = columns.get(i).jdbcType;
            if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB || jdbcType == Types.LONGVARCHAR
                || jdbcType == Types.LONGNVARCHAR) {
                row[i] = resultSet.getString(columnIndex);
            } else if (jdbcType == Types.BLOB || jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY) {
                row[i] = resultSet.getBytes(columnIndex);
            } else if (jdbcType == Types.SQLXML) {
                java.sql.SQLXML sqlxml = resultSet.getSQLXML(columnIndex);
                row[i] = sqlxml == null ? null : sqlxml.getString();
            } else {
                row[i] = resultSet.getObject(columnIndex);
            }
        }
        return row;
    }

    private void writeAndReportBatch(MigrationTask task, Connection targetConnection, PreparedStep step,
        List<Object[]> batch, String targetType) throws Exception {
        BatchResult batchResult = writeBatch(task, targetConnection, step, batch, targetType);
        task.addBatch(step.index, batch.size(), batchResult.inserted, batchResult.ignored);
        task.info("步骤 " + step.displayName + " 完成第 " + batchResult.batchNumberHint
            + " 批：读取 " + batch.size() + " 行，写入 " + batchResult.inserted
            + " 行，跳过 " + batchResult.ignored + " 行");
    }

    private BatchResult writeBatch(MigrationTask task, Connection connection, PreparedStep step, List<Object[]> batch,
        String targetType) throws Exception {
        try {
            BatchResult result = executePreparedBatch(task, connection, step.writeSql, step.targetColumns, batch);
            connection.commit();
            result.batchNumberHint = step.nextBatchNumber();
            return result;
        } catch (SQLException exception) {
            rollbackQuietly(connection);
            boolean ignoreMode = MigrationConstants.INSERT_IGNORE.equals(step.insertMode);
            if (MigrationConstants.DB_ORACLE.equals(targetType) && ignoreMode
                && SqlSupport.isDuplicateKey(exception, targetType)) {
                task.warn("步骤 " + step.displayName + " 批量写入遇到重复键，回退逐行识别并跳过重复数据");
                BatchResult result = executeOracleRowsWithDuplicateIgnore(task, connection, step, batch, targetType);
                result.batchNumberHint = step.nextBatchNumber();
                return result;
            }
            throw exception;
        }
    }

    private BatchResult executePreparedBatch(MigrationTask task, Connection connection, String sql,
        List<ColumnMeta> targetColumns, List<Object[]> batch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Object[] row : batch) {
                bindRow(statement, targetColumns, row);
                statement.addBatch();
            }
            task.setCurrentStatement(statement);
            try {
                int[] counts = statement.executeBatch();
                return countBatchResult(counts, batch.size());
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private BatchResult executeOracleRowsWithDuplicateIgnore(MigrationTask task, Connection connection,
        PreparedStep step, List<Object[]> batch, String targetType) throws SQLException, StopRequestedException,
        InterruptedException {
        long inserted = 0L;
        long ignored = 0L;
        try (PreparedStatement statement = connection.prepareStatement(step.strictInsertSql)) {
            task.setCurrentStatement(statement);
            try {
                for (Object[] row : batch) {
                    if (task.isStopRequested()) {
                        throw new StopRequestedException();
                    }
                    bindRow(statement, step.targetColumns, row);
                    try {
                        inserted += Math.max(0, statement.executeUpdate());
                    } catch (SQLException exception) {
                        if (SqlSupport.isDuplicateKey(exception, targetType)) {
                            ignored++;
                        } else {
                            throw exception;
                        }
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
        return new BatchResult(inserted, ignored);
    }

    private void bindRow(PreparedStatement statement, List<ColumnMeta> targetColumns, Object[] row)
        throws SQLException {
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            if (value == null) {
                statement.setNull(i + 1, targetColumns.get(i).jdbcType);
            } else if (targetColumns.get(i).jdbcType == Types.CLOB
                || targetColumns.get(i).jdbcType == Types.NCLOB) {
                statement.setString(i + 1, String.valueOf(value));
            } else if (targetColumns.get(i).jdbcType == Types.BLOB && value instanceof byte[]) {
                statement.setBytes(i + 1, (byte[])value);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    private BatchResult countBatchResult(int[] counts, int expectedSize) {
        if (counts == null || counts.length == 0) {
            return new BatchResult(expectedSize, 0L);
        }
        long inserted = 0L;
        long ignored = 0L;
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException("数据库驱动报告批次内存在执行失败的记录");
            }
            if (count == Statement.SUCCESS_NO_INFO) {
                inserted++;
            } else if (count > 0) {
                inserted += count;
            } else {
                ignored++;
            }
        }
        long accounted = inserted + ignored;
        if (accounted < expectedSize) {
            inserted += expectedSize - accounted;
        }
        if (inserted + ignored > expectedSize) {
            inserted = Math.max(0L, expectedSize - ignored);
        }
        return new BatchResult(inserted, ignored);
    }

    private String buildInsertSql(QualifiedName targetName, List<ColumnMeta> columns, String targetType,
        boolean ignoreDuplicate) {
        String prefix = ignoreDuplicate && MigrationConstants.DB_MYSQL.equals(targetType)
            ? "INSERT IGNORE INTO " : "INSERT INTO ";
        return prefix + targetName.toSql() + " (" + quotedColumnList(columns, targetType, null) + ") VALUES ("
            + placeholders(columns.size()) + ")";
    }

    private String buildStrictInsertSql(QualifiedName targetName, List<ColumnMeta> columns, String targetType) {
        return "INSERT INTO " + targetName.toSql() + " (" + quotedColumnList(columns, targetType, null)
            + ") VALUES (" + placeholders(columns.size()) + ")";
    }

    private String buildOracleMergeSql(QualifiedName targetName, List<ColumnMeta> columns, List<String> keyColumns) {
        List<String> sourceBindings = new ArrayList<>();
        List<String> onConditions = new ArrayList<>();
        List<String> insertValues = new ArrayList<>();
        for (ColumnMeta column : columns) {
            String quoted = SqlSupport.quoteIdentifier(column.name, MigrationConstants.DB_ORACLE);
            sourceBindings.add("? " + quoted);
            insertValues.add("LM_S." + quoted);
        }
        for (String keyColumn : keyColumns) {
            String quoted = SqlSupport.quoteIdentifier(keyColumn, MigrationConstants.DB_ORACLE);
            onConditions.add("LM_T." + quoted + " = LM_S." + quoted);
        }
        return "MERGE INTO " + targetName.toSql() + " LM_T USING (SELECT "
            + join(sourceBindings, ", ") + " FROM DUAL) LM_S ON (" + join(onConditions, " AND ")
            + ") WHEN NOT MATCHED THEN INSERT (" + quotedColumnList(columns, MigrationConstants.DB_ORACLE, null)
            + ") VALUES (" + join(insertValues, ", ") + ")";
    }

    private List<String> loadUsableUniqueKey(Connection connection, QualifiedName targetName, String targetType,
        List<ColumnMeta> selectedColumns) throws SQLException {
        Set<String> selected = new LinkedHashSet<>();
        Map<String, String> actualNames = new HashMap<>();
        for (ColumnMeta column : selectedColumns) {
            selected.add(normalizeName(column.name));
            actualNames.put(normalizeName(column.name), column.name);
        }
        MetadataLocation location = resolveMetadataLocation(connection, targetName, targetType);
        DatabaseMetaData metaData = connection.getMetaData();
        for (String tableCandidate : tableCandidates(targetName.getTable())) {
            TreeMap<Short, String> primaryKey = new TreeMap<>();
            try (ResultSet keys = metaData.getPrimaryKeys(location.catalog, location.schema, tableCandidate)) {
                while (keys.next()) {
                    primaryKey.put(keys.getShort("KEY_SEQ"), keys.getString("COLUMN_NAME"));
                }
            }
            List<String> usablePrimaryKey = normalizeUsableKey(primaryKey.values(), selected, actualNames);
            if (!usablePrimaryKey.isEmpty()) {
                return usablePrimaryKey;
            }

            Map<String, TreeMap<Short, String>> indexes = new LinkedHashMap<>();
            try (ResultSet indexInfo = metaData.getIndexInfo(location.catalog, location.schema, tableCandidate,
                true, false)) {
                while (indexInfo.next()) {
                    String indexName = indexInfo.getString("INDEX_NAME");
                    String columnName = indexInfo.getString("COLUMN_NAME");
                    if (indexName == null || columnName == null || indexInfo.getBoolean("NON_UNIQUE")) {
                        continue;
                    }
                    TreeMap<Short, String> indexColumns = indexes.get(indexName);
                    if (indexColumns == null) {
                        indexColumns = new TreeMap<>();
                        indexes.put(indexName, indexColumns);
                    }
                    indexColumns.put(indexInfo.getShort("ORDINAL_POSITION"), columnName);
                }
            }
            List<List<String>> candidates = new ArrayList<>();
            for (TreeMap<Short, String> index : indexes.values()) {
                List<String> usable = normalizeUsableKey(index.values(), selected, actualNames);
                if (!usable.isEmpty()) {
                    candidates.add(usable);
                }
            }
            if (!candidates.isEmpty()) {
                Collections.sort(candidates, Comparator.comparingInt(List::size));
                return candidates.get(0);
            }
        }
        return Collections.emptyList();
    }

    private List<String> normalizeUsableKey(Iterable<String> columns, Set<String> selected,
        Map<String, String> actualNames) {
        List<String> result = new ArrayList<>();
        for (String column : columns) {
            String normalized = normalizeName(column);
            if (!selected.contains(normalized)) {
                return Collections.emptyList();
            }
            result.add(actualNames.get(normalized));
        }
        return result;
    }

    private MetadataLocation resolveMetadataLocation(Connection connection, QualifiedName targetName,
        String targetType) throws SQLException {
        if (MigrationConstants.DB_MYSQL.equals(targetType)) {
            String catalog = targetName.getSchema() == null ? connection.getCatalog() : targetName.getSchema();
            return new MetadataLocation(catalog, null);
        }
        String schema = targetName.getSchema();
        if (isBlank(schema)) {
            schema = connection.getMetaData().getUserName();
        }
        return new MetadataLocation(null, schema == null ? null : schema.toUpperCase(Locale.ROOT));
    }

    private List<String> tableCandidates(String table) {
        List<String> result = new ArrayList<>();
        result.add(table);
        if (!table.toUpperCase(Locale.ROOT).equals(table)) {
            result.add(table.toUpperCase(Locale.ROOT));
        }
        if (!table.toLowerCase(Locale.ROOT).equals(table)) {
            result.add(table.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private String getOptionalString(ResultSet resultSet, String columnLabel) {
        try {
            return resultSet.getString(columnLabel);
        } catch (SQLException exception) {
            return null;
        }
    }

    private String normalizeInsertMode(String insertMode) {
        String normalized = isBlank(insertMode) ? MigrationConstants.INSERT_IGNORE
            : insertMode.trim().toUpperCase(Locale.ROOT);
        if (!MigrationConstants.INSERT_IGNORE.equals(normalized)
            && !MigrationConstants.INSERT_STRICT.equals(normalized)) {
            throw new IllegalArgumentException("写入方式只支持 IGNORE_DUPLICATE 或 STRICT_INSERT");
        }
        return normalized;
    }

    private String quotedColumnList(List<ColumnMeta> columns, String targetType, String prefix) {
        List<String> names = new ArrayList<>();
        for (ColumnMeta column : columns) {
            String quoted = SqlSupport.quoteIdentifier(column.name, targetType);
            names.add(prefix == null ? quoted : prefix + "." + quoted);
        }
        return join(names, ", ");
    }

    private String placeholders(int count) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add("?");
        }
        return join(result, ", ");
    }

    private String join(List<String> values, String separator) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(separator);
            }
            result.append(values.get(i));
        }
        return result.toString();
    }

    private boolean cursorValuesEqual(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return left == null ? right == null : left.equals(right);
    }

    private int typeGroup(int jdbcType) {
        if (isNumeric(jdbcType)) {
            return TypeGroup.NUMBER;
        }
        switch (jdbcType) {
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
            case Types.CLOB:
            case Types.NCLOB:
                return TypeGroup.TEXT;
            case Types.DATE:
            case Types.TIME:
            case Types.TIMESTAMP:
            case JDBC_TIME_WITH_TIMEZONE:
            case JDBC_TIMESTAMP_WITH_TIMEZONE:
                return TypeGroup.TIME;
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
                return TypeGroup.BINARY;
            default:
                return TypeGroup.UNKNOWN;
        }
    }

    private boolean isNumeric(int jdbcType) {
        return jdbcType == Types.TINYINT || jdbcType == Types.SMALLINT || jdbcType == Types.INTEGER
            || jdbcType == Types.BIGINT || jdbcType == Types.FLOAT || jdbcType == Types.REAL
            || jdbcType == Types.DOUBLE || jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL
            || jdbcType == Types.BIT || jdbcType == Types.BOOLEAN;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void rollbackQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (Exception ignored) {
                // 原始异常更重要，回滚失败由数据库连接关闭兜底。
            }
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 任务已经结束，不覆盖主流程结果。
            }
        }
    }

    private static final class TypeGroup {
        private static final int UNKNOWN = 0;
        private static final int NUMBER = 1;
        private static final int TEXT = 2;
        private static final int TIME = 3;
        private static final int BINARY = 4;
    }

    private static final class NumericRange {
        private final BigDecimal min;
        private final BigDecimal max;

        private NumericRange(BigDecimal min, BigDecimal max) {
            this.min = min;
            this.max = max;
        }

        private boolean contains(NumericRange other) {
            return min.compareTo(other.min) <= 0 && max.compareTo(other.max) >= 0;
        }
    }

    private static final class NumericRangeCheck {
        private final ColumnMeta source;
        private final ColumnMeta target;
        private final NumericRange targetRange;
        private final boolean requireWholeNumberCheck;

        private NumericRangeCheck(ColumnMeta source, ColumnMeta target, NumericRange targetRange,
            boolean requireWholeNumberCheck) {
            this.source = source;
            this.target = target;
            this.targetRange = targetRange;
            this.requireWholeNumberCheck = requireWholeNumberCheck;
        }
    }

    private static final class ColumnMeta {
        private final String name;
        private final int jdbcType;
        private final String typeName;
        private final int precision;
        private final int scale;
        private final int nullable;
        private final boolean autoIncrement;

        private ColumnMeta(String name, int jdbcType, String typeName, int precision, int scale, int nullable,
            boolean autoIncrement) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
            this.autoIncrement = autoIncrement;
        }
    }

    private static final class TargetColumnRule {
        private final boolean nullable;
        private final boolean autoIncrement;
        private final boolean hasDefault;

        private TargetColumnRule(boolean nullable, boolean autoIncrement, boolean hasDefault) {
            this.nullable = nullable;
            this.autoIncrement = autoIncrement;
            this.hasDefault = hasDefault;
        }
    }

    private static final class MetadataLocation {
        private final String catalog;
        private final String schema;

        private MetadataLocation(String catalog, String schema) {
            this.catalog = catalog;
            this.schema = schema;
        }
    }

    private static final class PreparedStep {
        private final int index;
        private final String displayName;
        private final String sourceSql;
        private final QualifiedName targetName;
        private final List<ColumnMeta> sourceColumns;
        private final List<ColumnMeta> targetColumns;
        private final int cursorIndex;
        private final long totalRows;
        private final String insertMode;
        private final String writeSql;
        private final String strictInsertSql;
        private final boolean oracleMerge;
        private long batchNumber;

        private PreparedStep(int index, String displayName, String sourceSql, QualifiedName targetName,
            List<ColumnMeta> sourceColumns, List<ColumnMeta> targetColumns, int cursorIndex, long totalRows,
            String insertMode, String writeSql, String strictInsertSql, boolean oracleMerge) {
            this.index = index;
            this.displayName = displayName;
            this.sourceSql = sourceSql;
            this.targetName = targetName;
            this.sourceColumns = sourceColumns;
            this.targetColumns = targetColumns;
            this.cursorIndex = cursorIndex;
            this.totalRows = totalRows;
            this.insertMode = insertMode;
            this.writeSql = writeSql;
            this.strictInsertSql = strictInsertSql;
            this.oracleMerge = oracleMerge;
        }

        private long nextBatchNumber() {
            return ++batchNumber;
        }
    }

    private static final class KeysetSql {
        private final String sql;

        private KeysetSql(String sql) {
            this.sql = sql;
        }
    }

    private static final class BatchResult {
        private final long inserted;
        private final long ignored;
        private long batchNumberHint;

        private BatchResult(long inserted, long ignored) {
            this.inserted = inserted;
            this.ignored = ignored;
        }
    }
}
