package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@Component
public class ArchiveEngine {

    private static final String STEP_VALIDATING = "VALIDATING";
    private static final String STEP_VALIDATED = "VALIDATED";
    private static final String STEP_RUNNING = "RUNNING";
    private static final String STEP_SUCCEEDED = "SUCCEEDED";
    private static final String STEP_FAILED = "FAILED";
    private static final String PARENT_ALIAS = "LM_PARENT";
    private static final String CHILD_ALIAS = "LM_CHILD";
    private static final String PAGE_ALIAS = "LM_ARCHIVE_PAGE";
    private static final int MAX_SQL_LOG_PARAMETERS = 50;
    private static final int MAX_SQL_LOG_VALUE_LENGTH = 160;

    public void execute(MigrationTask task) {
        Connection connection = null;
        try {
            task.checkpoint();
            task.markStarted();
            String databaseType = DatabaseSupport.normalizeType(task.getRequest().getSource().getType());
            task.info("正在连接归档数据库 " + databaseType + " "
                + DatabaseSupport.displayEndpoint(task.getRequest().getSource()));
            connection = DatabaseSupport.open(task.getRequest().getSource());
            connection.setAutoCommit(false);
            task.info("归档数据库连接成功；每张主表的写入、关联子表写入、子表删除和主表删除"
                + "将在各自主表批次内共同提交");

            PreparedArchive prepared = prepare(task, connection, databaseType);
            task.setTotalRows(prepared.totalRows);
            task.info("全部归档表结构与关联关系校验通过，预计处理 " + prepared.totalRows
                + " 行；归档采用严格写入，任何重复键或删除数量不一致都会回滚当前主表批次");
            markAllSteps(task, prepared, STEP_RUNNING);
            task.markRunning("正在按主表批次执行同库归档");
            archiveAllMainTables(task, connection, prepared, task.getRequest().getBatchSize(), databaseType);
            markAllSteps(task, prepared, STEP_SUCCEEDED);
            task.checkpoint();
            task.markSucceeded();
        } catch (StopRequestedException exception) {
            rollbackQuietly(connection);
            task.markTerminated();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            rollbackQuietly(connection);
            if (task.isStopRequested()) {
                task.markTerminated();
            } else {
                markRunningStepsFailed(task);
                task.markFailed("归档线程被中断，当前未提交批次已回滚");
            }
        } catch (Exception exception) {
            rollbackQuietly(connection);
            if (task.isStopRequested()) {
                task.markTerminated();
            } else {
                markRunningStepsFailed(task);
                task.markFailed("归档失败，当前未提交批次已回滚：" + SqlSupport.safeMessage(exception));
            }
        } finally {
            closeQuietly(connection);
            task.clearPasswords();
        }
    }

    private PreparedArchive prepare(MigrationTask task, Connection connection, String databaseType) throws Exception {
        ArchiveRequest archiveRequest = task.getRequest().getArchive();
        List<PreparedArchiveTable> mainTables = new ArrayList<>();
        List<PreparedArchiveTable> childTables = new ArrayList<>();
        Map<String, PreparedArchiveTable> preparedByNodeId = new LinkedHashMap<>();
        for (int i = 0; i < archiveRequest.getMainTables().size(); i++) {
            ArchiveTableRequest mainRequest = archiveRequest.getMainTables().get(i);
            task.setStepStatus(i, STEP_VALIDATING);
            task.markValidating("正在校验主业务表归档配置：" + mainRequest.getName());
            String mainSql = SqlSupport.normalizeSourceQuery(mainRequest.getSourceSql());
            PreparedArchiveTable mainTable = prepareTable(task, connection, mainRequest, i, mainSql, databaseType);
            mainTables.add(mainTable);
            preparedByNodeId.put(mainRequest.getNodeId(), mainTable);
        }
        int childStepOffset = mainTables.size();
        for (int i = 0; i < archiveRequest.getChildTables().size(); i++) {
            task.checkpoint();
            ArchiveTableRequest childRequest = archiveRequest.getChildTables().get(i);
            int stepIndex = childStepOffset + i;
            task.setStepStatus(stepIndex, STEP_VALIDATING);
            task.markValidating("正在校验子业务表归档配置：" + childRequest.getName());
            PreparedArchiveTable parentTable = preparedByNodeId.get(childRequest.getParentNodeId());
            if (parentTable == null) {
                throw new IllegalArgumentException("子业务表 " + childRequest.getName() + " 的上级节点尚未准备："
                    + childRequest.getParentNodeId());
            }
            String childSql = buildAllRelatedChildrenSql(childRequest, parentTable.sourceSql, databaseType);
            PreparedArchiveTable childTable = prepareTable(task, connection, childRequest, stepIndex, childSql,
                databaseType);
            childTable.parentRelationIndexes = resolveParentRelationIndexes(childRequest, parentTable.columns);
            childTables.add(childTable);
            preparedByNodeId.put(childRequest.getNodeId(), childTable);
        }
        validateNoPhysicalTableOverlap(mainTables, childTables);
        Map<String, List<PreparedArchiveTable>> childrenByParentNodeId = buildChildrenByParentNodeId(childTables);

        long totalRows = 0L;
        for (PreparedArchiveTable mainTable : mainTables) {
            totalRows += mainTable.totalRows;
            task.setStepTotal(mainTable.index, mainTable.totalRows);
            task.setStepStatus(mainTable.index, STEP_VALIDATED);
            task.info("归档校验通过：" + mainTable.displayName + "，预计 " + mainTable.totalRows + " 行");
        }
        for (PreparedArchiveTable childTable : childTables) {
            totalRows += childTable.totalRows;
            task.setStepTotal(childTable.index, childTable.totalRows);
            task.setStepStatus(childTable.index, STEP_VALIDATED);
            task.info("归档校验通过：" + childTable.displayName + "，预计 " + childTable.totalRows + " 行");
        }
        return new PreparedArchive(mainTables, childTables, childrenByParentNodeId, totalRows);
    }

    private PreparedArchiveTable prepareTable(MigrationTask task, Connection connection, ArchiveTableRequest request,
        int index, String sourceSql, String databaseType) throws Exception {
        QualifiedName sourceName = SqlSupport.parseTableName(request.getSourceTable(), request.getName() + "源表");
        QualifiedName targetName = SqlSupport.parseTableName(request.getTargetTable(), request.getName() + "归档表");
        String sourceIdentity = canonicalTableIdentity(connection, sourceName, databaseType);
        String targetIdentity = canonicalTableIdentity(connection, targetName, databaseType);
        if (sourceIdentity.equals(targetIdentity)) {
            throw new IllegalArgumentException(request.getName() + "的源表与归档表解析为同一个物理表，禁止归档");
        }
        List<ColumnMeta> sourceTableColumns = readTableColumns(task, connection, sourceName, databaseType);
        List<ColumnMeta> targetTableColumns = readTableColumns(task, connection, targetName, databaseType);
        List<ColumnMeta> queryColumns = readQueryColumns(task, connection, sourceSql);
        validateUniqueColumnNames(sourceTableColumns, request.getName() + "源表");
        validateUniqueColumnNames(targetTableColumns, request.getName() + "归档表");
        validateUniqueColumnNames(queryColumns, request.getName() + "归档查询");
        validateSameStructure(sourceTableColumns, targetTableColumns, request.getName());
        validateQueryCoversSourceTable(queryColumns, sourceTableColumns, request.getName());
        boolean keysetPagination = request.getCursorColumn() != null
            && !request.getCursorColumn().trim().isEmpty();
        String keyColumn = resolveArchiveKeyColumn(task, connection, request, sourceName, sourceTableColumns,
            queryColumns, databaseType, keysetPagination);
        String keyLabel = keysetPagination ? "唯一游标字段" : "单列主键删除键";
        int keyIndex = resolveColumnIndex(keyColumn, queryColumns, request.getName() + keyLabel);
        long totalRows = countAndValidateArchiveKey(task, connection, sourceSql, queryColumns.get(keyIndex),
            databaseType, request.getName(), keyLabel);
        String insertSql = buildInsertSql(targetName, queryColumns, databaseType);
        return new PreparedArchiveTable(index, request, sourceName, targetName, sourceIdentity, targetIdentity,
            sourceSql, queryColumns, keyIndex, keysetPagination, insertSql, totalRows);
    }

    private void archiveAllMainTables(MigrationTask task, Connection connection, PreparedArchive prepared,
        int batchSize, String databaseType) throws Exception {
        for (int i = 0; i < prepared.mainTables.size(); i++) {
            PreparedArchiveTable mainTable = prepared.mainTables.get(i);
            task.checkpoint();
            task.info("开始归档主业务表 " + (i + 1) + "/" + prepared.mainTables.size() + "："
                + mainTable.displayName + "（节点 " + mainTable.request.getNodeId() + "）");
            archiveByMainBatch(task, connection, prepared, mainTable, batchSize, databaseType);
        }
    }

    private void archiveByMainBatch(MigrationTask task, Connection connection, PreparedArchive prepared,
        PreparedArchiveTable mainTable, int batchSize, String databaseType) throws Exception {
        Object upperBound = mainTable.keysetPagination ? queryUpperBound(task, connection, mainTable.sourceSql,
            mainTable.keyColumn(), databaseType, new ArrayList<Object>()) : null;
        if (mainTable.keysetPagination && upperBound == null) {
            task.info("主业务表 " + mainTable.displayName + " 归档查询没有待处理数据，无需变更数据库");
            return;
        }
        Object lastKey = null;
        long mainBatchNumber = 0L;
        while (true) {
            task.checkpoint();
            long batchStartedNanos = System.nanoTime();
            QueryWithParameters mainPage = mainTable.keysetPagination
                ? buildPageSql(mainTable.sourceSql, mainTable.keyColumn(), databaseType, batchSize,
                    new ArrayList<Object>(), lastKey, upperBound)
                : buildCurrentFirstPageSql(mainTable.sourceSql, mainTable.keyColumn(), databaseType, batchSize,
                    new ArrayList<Object>());
            List<Object[]> mainRows = queryRows(task, connection, mainPage, mainTable.columns, batchSize);
            if (mainRows.isEmpty()) {
                if (mainBatchNumber == 0L) {
                    task.info("主业务表 " + mainTable.displayName + " 归档查询没有待处理数据，无需变更数据库");
                }
                break;
            }
            Object currentLastKey = null;
            if (mainTable.keysetPagination) {
                currentLastKey = mainRows.get(mainRows.size() - 1)[mainTable.keyIndex];
                if (lastKey != null && cursorValuesEqual(lastKey, currentLastKey)) {
                    throw new IllegalStateException("主业务表 " + mainTable.displayName + " 游标未推进，请检查字段 "
                        + mainTable.keyColumn().name);
                }
            }
            mainBatchNumber++;
            executeArchiveTransaction(task, connection, prepared, mainTable, mainRows, mainBatchNumber,
                databaseType, batchStartedNanos);
            if (mainTable.keysetPagination) {
                lastKey = currentLastKey;
                if (mainRows.size() < batchSize || cursorValuesEqual(lastKey, upperBound)) {
                    break;
                }
            }
        }
    }

    private void executeArchiveTransaction(MigrationTask task, Connection connection, PreparedArchive prepared,
        PreparedArchiveTable mainTable, List<Object[]> mainRows, long mainBatchNumber, String databaseType,
        long batchStartedNanos) throws Exception {
        Map<Integer, ArchiveBatchStats> childStats = new LinkedHashMap<>();
        try {
            long mainInserted = insertRows(task, connection, mainTable, mainRows);
            if (mainInserted != mainRows.size()) {
                throw new SQLException("主业务归档表写入数量不一致，期望 " + mainRows.size() + " 行，实际 "
                    + mainInserted + " 行");
            }
            for (PreparedArchiveTable childTable : prepared.childrenOf(mainTable.request.getNodeId())) {
                task.stopCheckpoint();
                archiveRelatedSubtree(task, connection, prepared, childTable, mainRows,
                    task.getRequest().getBatchSize(), databaseType, childStats);
            }
            for (PreparedArchiveTable childTable : prepared.childrenOf(mainTable.request.getNodeId())) {
                assertNoRelatedChildrenRemain(task, connection, childTable, mainRows, databaseType);
            }
            long mainDeleted = deleteRows(task, connection, mainTable, mainRows, databaseType);
            if (mainDeleted != mainRows.size()) {
                throw new SQLException("主业务源表删除数量不一致，期望 " + mainRows.size() + " 行，实际 "
                    + mainDeleted + " 行；已回滚当前批次");
            }
            task.stopCheckpoint();
            connection.commit();

            task.addArchiveBatch(mainTable.index, mainRows.size(), mainInserted, mainDeleted);
            for (PreparedArchiveTable childTable : prepared.childTables) {
                ArchiveBatchStats stats = childStats.get(childTable.index);
                if (stats != null && stats.processed > 0L) {
                    task.addArchiveBatch(childTable.index, stats.processed, stats.inserted, stats.deleted);
                }
            }
            task.info("归档主表 " + mainTable.displayName + " 批次 " + mainBatchNumber
                + " 已提交：主表写入/删除 " + mainRows.size()
                + " 行，子表合计写入/删除 " + sumProcessed(childStats) + " 行，耗时 "
                + elapsedMillis(batchStartedNanos) + " ms");
        } catch (Exception exception) {
            rollbackQuietly(connection);
            throw exception;
        }
    }

    private void archiveRelatedSubtree(MigrationTask task, Connection connection, PreparedArchive prepared,
        PreparedArchiveTable childTable, List<Object[]> parentRows, int batchSize, String databaseType,
        Map<Integer, ArchiveBatchStats> childStats) throws Exception {
        RelationQuery relationQuery = buildRelationQuery(childTable, parentRows, databaseType);
        if (relationQuery.parameters.isEmpty()) {
            return;
        }
        Object upperBound = childTable.keysetPagination ? queryUpperBound(task, connection, relationQuery.sql,
            childTable.keyColumn(), databaseType, relationQuery.parameters) : null;
        if (childTable.keysetPagination && upperBound == null) {
            return;
        }
        ArchiveBatchStats stats = childStats.get(childTable.index);
        if (stats == null) {
            stats = new ArchiveBatchStats();
            childStats.put(childTable.index, stats);
        }
        Object lastKey = null;
        while (true) {
            task.stopCheckpoint();
            QueryWithParameters childPage = childTable.keysetPagination
                ? buildPageSql(relationQuery.sql, childTable.keyColumn(), databaseType, batchSize,
                    relationQuery.parameters, lastKey, upperBound)
                : buildCurrentFirstPageSql(relationQuery.sql, childTable.keyColumn(), databaseType, batchSize,
                    relationQuery.parameters);
            List<Object[]> childRows = queryRows(task, connection, childPage, childTable.columns, batchSize);
            if (childRows.isEmpty()) {
                break;
            }
            Object currentLastKey = null;
            if (childTable.keysetPagination) {
                currentLastKey = childRows.get(childRows.size() - 1)[childTable.keyIndex];
                if (lastKey != null && cursorValuesEqual(lastKey, currentLastKey)) {
                    throw new IllegalStateException("子业务表 " + childTable.displayName + " 游标未推进");
                }
            }
            long inserted = insertRows(task, connection, childTable, childRows);
            if (inserted != childRows.size()) {
                throw new SQLException("子业务归档表 " + childTable.targetName.toSql() + " 写入数量不一致");
            }
            for (PreparedArchiveTable nestedChild : prepared.childrenOf(childTable.request.getNodeId())) {
                archiveRelatedSubtree(task, connection, prepared, nestedChild, childRows, batchSize, databaseType,
                    childStats);
            }
            for (PreparedArchiveTable nestedChild : prepared.childrenOf(childTable.request.getNodeId())) {
                assertNoRelatedChildrenRemain(task, connection, nestedChild, childRows, databaseType);
            }
            long deleted = deleteRows(task, connection, childTable, childRows, databaseType);
            if (deleted != childRows.size()) {
                throw new SQLException("子业务源表 " + childTable.sourceName.toSql() + " 删除数量不一致，期望 "
                    + childRows.size() + " 行，实际 " + deleted + " 行；已回滚当前批次");
            }
            stats.processed += childRows.size();
            stats.inserted += inserted;
            stats.deleted += deleted;
            if (childTable.keysetPagination) {
                lastKey = currentLastKey;
                if (childRows.size() < batchSize || cursorValuesEqual(lastKey, upperBound)) {
                    break;
                }
            }
        }
    }

    private RelationQuery buildRelationQuery(PreparedArchiveTable childTable, List<Object[]> parentRows,
        String databaseType) {
        List<RelationKey> relationKeys = distinctRelationKeys(childTable, parentRows);
        if (relationKeys.isEmpty()) {
            return new RelationQuery("", new ArrayList<Object>());
        }
        List<ArchiveRelationRequest> relations = childTable.request.getRelations();
        StringBuilder predicate = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        if (relations.size() == 1) {
            predicate.append(CHILD_ALIAS).append('.').append(SqlSupport.quoteIdentifier(
                configuredIdentifier(relations.get(0).getChildColumn(), databaseType), databaseType)).append(" IN (")
                .append(placeholders(relationKeys.size())).append(')');
            for (RelationKey relationKey : relationKeys) {
                parameters.add(relationKey.values[0]);
            }
        } else {
            for (int i = 0; i < relationKeys.size(); i++) {
                if (i > 0) {
                    predicate.append(" OR ");
                }
                predicate.append('(');
                for (int j = 0; j < relations.size(); j++) {
                    if (j > 0) {
                        predicate.append(" AND ");
                    }
                    predicate.append(CHILD_ALIAS).append('.').append(SqlSupport.quoteIdentifier(
                        configuredIdentifier(relations.get(j).getChildColumn(), databaseType), databaseType))
                        .append(" = ?");
                    parameters.add(relationKeys.get(i).values[j]);
                }
                predicate.append(')');
            }
        }
        String sql = "SELECT " + CHILD_ALIAS + ".* FROM " + quoteQualifiedName(childTable.sourceName, databaseType)
            + " " + CHILD_ALIAS + " WHERE " + predicate;
        return new RelationQuery(sql, parameters);
    }

    private List<RelationKey> distinctRelationKeys(PreparedArchiveTable childTable, List<Object[]> parentRows) {
        Set<RelationKey> result = new LinkedHashSet<>();
        for (Object[] parentRow : parentRows) {
            Object[] values = new Object[childTable.parentRelationIndexes.length];
            boolean hasNull = false;
            for (int i = 0; i < childTable.parentRelationIndexes.length; i++) {
                values[i] = parentRow[childTable.parentRelationIndexes[i]];
                if (values[i] == null) {
                    hasNull = true;
                }
            }
            if (!hasNull) {
                result.add(new RelationKey(values));
            }
        }
        return new ArrayList<>(result);
    }

    private void assertNoRelatedChildrenRemain(MigrationTask task, Connection connection,
        PreparedArchiveTable childTable, List<Object[]> parentRows, String databaseType) throws Exception {
        RelationQuery relationQuery = buildRelationQuery(childTable, parentRows, databaseType);
        if (relationQuery.parameters.isEmpty()) {
            return;
        }
        String sql = "SELECT COUNT(*) FROM (" + relationQuery.sql + ") LM_ARCHIVE_REMAIN";
        long remaining = queryLong(task, connection, sql, relationQuery.parameters);
        if (remaining != 0L) {
            throw new SQLException("子业务源表 " + childTable.sourceName.toSql() + " 仍有 " + remaining
                + " 行关联数据，禁止删除当前主表批次");
        }
    }

    private long insertRows(MigrationTask task, Connection connection, PreparedArchiveTable table,
        List<Object[]> rows) throws Exception {
        logBatchSql(task, "归档写入 " + table.displayName, table.insertSql, rows);
        try (PreparedStatement statement = connection.prepareStatement(table.insertSql)) {
            task.setCurrentStatement(statement);
            try {
                for (Object[] row : rows) {
                    for (int i = 0; i < table.columns.size(); i++) {
                        setStatementValue(statement, i + 1, row[i], table.columns.get(i).jdbcType);
                    }
                    statement.addBatch();
                }
                int[] counts = statement.executeBatch();
                return countAffectedRows(counts, rows.size());
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private long deleteRows(MigrationTask task, Connection connection, PreparedArchiveTable table,
        List<Object[]> rows, String databaseType) throws Exception {
        String quotedKey = SqlSupport.quoteIdentifier(table.keyColumn().name, databaseType);
        String sql = "DELETE FROM " + quoteQualifiedName(table.sourceName, databaseType) + " WHERE " + quotedKey
            + " IN (" + placeholders(rows.size()) + ")";
        List<Object> parameters = new ArrayList<>();
        for (Object[] row : rows) {
            parameters.add(row[table.keyIndex]);
        }
        logSql(task, "源表删除 " + table.displayName, sql, parameters);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try {
                for (int i = 0; i < rows.size(); i++) {
                    setStatementValue(statement, i + 1, rows.get(i)[table.keyIndex], table.keyColumn().jdbcType);
                }
                return statement.executeUpdate();
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private Object queryUpperBound(MigrationTask task, Connection connection, String sourceSql,
        ColumnMeta cursorColumn, String databaseType, List<Object> parameters) throws Exception {
        String quotedCursor = SqlSupport.quoteIdentifier(cursorColumn.name, databaseType);
        String sql = "SELECT MAX(" + PAGE_ALIAS + "." + quotedCursor + ") FROM (" + sourceSql + ") "
            + PAGE_ALIAS;
        logSql(task, "游标上界", sql, parameters);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getObject(1) : null;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private QueryWithParameters buildPageSql(String sourceSql, ColumnMeta cursorColumn, String databaseType,
        int batchSize, List<Object> sourceParameters, Object lastKey, Object upperBound) {
        String quotedCursor = SqlSupport.quoteIdentifier(cursorColumn.name, databaseType);
        StringBuilder ordered = new StringBuilder("SELECT ").append(PAGE_ALIAS).append(".* FROM (")
            .append(sourceSql).append(") ").append(PAGE_ALIAS).append(" WHERE ");
        List<Object> parameters = new ArrayList<>(sourceParameters);
        if (lastKey != null) {
            ordered.append(PAGE_ALIAS).append('.').append(quotedCursor).append(" > ? AND ");
            parameters.add(lastKey);
        }
        ordered.append(PAGE_ALIAS).append('.').append(quotedCursor).append(" <= ? ORDER BY ")
            .append(PAGE_ALIAS).append('.').append(quotedCursor).append(" ASC");
        parameters.add(upperBound);
        String sql;
        if (MigrationConstants.DB_MYSQL.equals(databaseType)) {
            sql = ordered.append(" LIMIT ").append(batchSize).toString();
        } else {
            sql = "SELECT * FROM (" + ordered + ") WHERE ROWNUM <= " + batchSize;
        }
        return new QueryWithParameters(sql, parameters);
    }

    private QueryWithParameters buildCurrentFirstPageSql(String sourceSql, ColumnMeta keyColumn,
        String databaseType, int batchSize, List<Object> sourceParameters) {
        String quotedKey = SqlSupport.quoteIdentifier(keyColumn.name, databaseType);
        String ordered = "SELECT " + PAGE_ALIAS + ".* FROM (" + sourceSql + ") " + PAGE_ALIAS + " ORDER BY "
            + PAGE_ALIAS + "." + quotedKey + " ASC";
        String sql = MigrationConstants.DB_MYSQL.equals(databaseType) ? ordered + " LIMIT " + batchSize
            : "SELECT * FROM (" + ordered + ") WHERE ROWNUM <= " + batchSize;
        return new QueryWithParameters(sql, new ArrayList<>(sourceParameters));
    }

    private List<Object[]> queryRows(MigrationTask task, Connection connection, QueryWithParameters query,
        List<ColumnMeta> columns, int fetchSize) throws Exception {
        List<Object[]> result = new ArrayList<>();
        logSql(task, "批次查询", query.sql, query.parameters);
        try (PreparedStatement statement = connection.prepareStatement(query.sql)) {
            bindParameters(statement, query.parameters);
            statement.setFetchSize(fetchSize);
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(readRow(resultSet, columns));
                }
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
        return result;
    }

    private List<ColumnMeta> readTableColumns(MigrationTask task, Connection connection, QualifiedName tableName,
        String databaseType) throws Exception {
        String sql = "SELECT * FROM " + quoteQualifiedName(tableName, databaseType) + " WHERE 1 = 0";
        return readQueryColumns(task, connection, sql);
    }

    private String resolveArchiveKeyColumn(MigrationTask task, Connection connection, ArchiveTableRequest request,
        QualifiedName sourceName, List<ColumnMeta> sourceColumns, List<ColumnMeta> queryColumns, String databaseType,
        boolean keysetPagination) throws SQLException {
        String configuredCursor = request.getCursorColumn();
        String resolvedCursor;
        if (!keysetPagination) {
            List<String> primaryKeyColumns = readPrimaryKeyColumns(connection, sourceName, databaseType);
            if (primaryKeyColumns.isEmpty()) {
                throw new IllegalArgumentException(request.getName() + "未配置唯一游标字段，且源表 "
                    + sourceName.toSql() + " 未检测到主键；无游标模式需要单列主键作为精确删除键");
            }
            if (primaryKeyColumns.size() != 1) {
                throw new IllegalArgumentException(request.getName() + "未配置唯一游标字段，且源表 "
                    + sourceName.toSql() + " 使用 " + primaryKeyColumns.size()
                    + " 列复合主键；无游标模式当前仅支持单列主键，请手工填写递增唯一游标字段");
            }
            resolvedCursor = primaryKeyColumns.get(0);
            task.info(request.getName() + "未配置递增唯一游标字段；单列主键 " + resolvedCursor
                + " 仅用于稳定排序和精确删除，将采用删除后重复读取当前首批的非游标模式");
        } else {
            resolvedCursor = SqlSupport.normalizeColumnName(configuredCursor, request.getName() + "唯一游标字段");
            request.setCursorColumn(resolvedCursor);
        }
        String keyLabel = keysetPagination ? "唯一游标字段" : "单列主键删除键";
        resolveColumnIndex(resolvedCursor, sourceColumns, request.getName() + "源表" + keyLabel);
        resolveColumnIndex(resolvedCursor, queryColumns, request.getName() + "归档查询" + keyLabel);
        return resolvedCursor;
    }

    private List<String> readPrimaryKeyColumns(Connection connection, QualifiedName tableName, String databaseType)
        throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = null;
        String schema = null;
        if (MigrationConstants.DB_MYSQL.equals(databaseType)) {
            catalog = tableName.getSchema() == null ? connection.getCatalog() : tableName.getSchema();
        } else {
            schema = tableName.getSchema() == null ? metadata.getUserName() : tableName.getSchema();
        }
        String metadataTable = configuredIdentifier(tableName.getTable(), databaseType);
        String metadataSchema = schema == null ? null : configuredIdentifier(schema, databaseType);
        SortedMap<Short, String> primaryKeyBySequence = new TreeMap<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, metadataSchema, metadataTable)) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                if (columnName != null && !columnName.trim().isEmpty()) {
                    primaryKeyBySequence.put(resultSet.getShort("KEY_SEQ"), columnName);
                }
            }
        }
        return new ArrayList<>(primaryKeyBySequence.values());
    }

    private List<ColumnMeta> readQueryColumns(MigrationTask task, Connection connection, String sourceSql)
        throws Exception {
        String sql = "SELECT * FROM (" + sourceSql + ") LM_ARCHIVE_META WHERE 1 = 0";
        logSql(task, "字段元数据校验", sql, java.util.Collections.<Object>emptyList());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                return readColumnMetadata(resultSet.getMetaData());
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private List<ColumnMeta> readColumnMetadata(ResultSetMetaData metadata) throws SQLException {
        List<ColumnMeta> result = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String name = metadata.getColumnLabel(i);
            if (name == null || name.trim().isEmpty()) {
                name = metadata.getColumnName(i);
            }
            result.add(new ColumnMeta(name, metadata.getColumnType(i), metadata.getColumnTypeName(i),
                metadata.getPrecision(i), metadata.getScale(i), metadata.isNullable(i)));
        }
        return result;
    }

    private void validateUniqueColumnNames(List<ColumnMeta> columns, String label) {
        Set<String> names = new LinkedHashSet<>();
        for (ColumnMeta column : columns) {
            if (!names.add(normalizeName(column.name))) {
                throw new IllegalArgumentException(label + "存在重复字段名：" + column.name);
            }
        }
    }

    private void validateSameStructure(List<ColumnMeta> sourceColumns, List<ColumnMeta> targetColumns,
        String displayName) {
        if (sourceColumns.size() != targetColumns.size()) {
            throw new IllegalArgumentException(displayName + "源表与归档表字段数量不同：源表 " + sourceColumns.size()
                + " 个，归档表 " + targetColumns.size() + " 个");
        }
        Map<String, ColumnMeta> targetByName = indexColumns(targetColumns);
        for (ColumnMeta source : sourceColumns) {
            ColumnMeta target = targetByName.get(normalizeName(source.name));
            if (target == null) {
                throw new IllegalArgumentException(displayName + "归档表缺少字段：" + source.name);
            }
            if (!sameColumnDefinition(source, target)) {
                throw new IllegalArgumentException(displayName + "字段结构不同：" + source.name + "，源表="
                    + source.describe() + "，归档表=" + target.describe());
            }
        }
    }

    private void validateQueryCoversSourceTable(List<ColumnMeta> queryColumns, List<ColumnMeta> sourceColumns,
        String displayName) {
        if (queryColumns.size() != sourceColumns.size()) {
            throw new IllegalArgumentException(displayName + "归档查询必须返回源表全部字段，不允许只归档部分字段");
        }
        Map<String, ColumnMeta> sourceByName = indexColumns(sourceColumns);
        for (ColumnMeta queryColumn : queryColumns) {
            ColumnMeta sourceColumn = sourceByName.get(normalizeName(queryColumn.name));
            if (sourceColumn == null) {
                throw new IllegalArgumentException(displayName + "归档查询返回了非源表字段或字段别名："
                    + queryColumn.name);
            }
            if (!sameColumnDefinition(queryColumn, sourceColumn)) {
                throw new IllegalArgumentException(displayName + "归档查询字段类型与源表不同：" + queryColumn.name);
            }
        }
    }

    private boolean sameColumnDefinition(ColumnMeta left, ColumnMeta right) {
        return left.jdbcType == right.jdbcType
            && normalizeName(left.typeName).equals(normalizeName(right.typeName))
            && left.precision == right.precision
            && left.scale == right.scale
            && left.nullable == right.nullable;
    }

    private long countAndValidateArchiveKey(MigrationTask task, Connection connection, String sourceSql,
        ColumnMeta keyColumn, String databaseType, String displayName, String keyLabel) throws Exception {
        String quotedKey = SqlSupport.quoteIdentifier(keyColumn.name, databaseType);
        String reference = "LM_ARCHIVE_STATS." + quotedKey;
        String sql = "SELECT COUNT(*), COUNT(" + reference + "), COUNT(DISTINCT " + reference + ") FROM ("
            + sourceSql + ") LM_ARCHIVE_STATS";
        logSql(task, "归档范围统计", sql, java.util.Collections.<Object>emptyList());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("归档统计查询未返回结果");
                }
                long totalRows = resultSet.getLong(1);
                long nonNullRows = resultSet.getLong(2);
                long distinctRows = resultSet.getLong(3);
                if (nonNullRows != totalRows) {
                    throw new IllegalArgumentException(displayName + keyLabel + " " + keyColumn.name
                        + " 存在空值");
                }
                if (distinctRows != totalRows) {
                    throw new IllegalArgumentException(displayName + keyLabel + " " + keyColumn.name
                        + " 在本次归档范围内不唯一");
                }
                return totalRows;
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private String buildAllRelatedChildrenSql(ArchiveTableRequest childRequest, String parentSql,
        String databaseType) {
        StringBuilder relation = new StringBuilder();
        for (int i = 0; i < childRequest.getRelations().size(); i++) {
            if (i > 0) {
                relation.append(" AND ");
            }
            ArchiveRelationRequest item = childRequest.getRelations().get(i);
            relation.append(CHILD_ALIAS).append('.').append(SqlSupport.quoteIdentifier(
                configuredIdentifier(item.getChildColumn(), databaseType), databaseType)).append(" = ")
                .append(PARENT_ALIAS).append('.').append(
                SqlSupport.quoteIdentifier(configuredIdentifier(item.getMainColumn(), databaseType), databaseType));
        }
        QualifiedName childSource = SqlSupport.parseTableName(childRequest.getSourceTable(), "子业务源表");
        return "SELECT " + CHILD_ALIAS + ".* FROM " + quoteQualifiedName(childSource, databaseType) + " "
            + CHILD_ALIAS + " WHERE EXISTS (SELECT 1 FROM (" + parentSql + ") " + PARENT_ALIAS + " WHERE "
            + relation + ")";
    }

    private int[] resolveParentRelationIndexes(ArchiveTableRequest childRequest, List<ColumnMeta> parentColumns) {
        int[] indexes = new int[childRequest.getRelations().size()];
        for (int i = 0; i < childRequest.getRelations().size(); i++) {
            ArchiveRelationRequest relation = childRequest.getRelations().get(i);
            indexes[i] = resolveColumnIndex(relation.getMainColumn(), parentColumns,
                childRequest.getName() + "上级表关联字段");
        }
        return indexes;
    }

    private Map<String, List<PreparedArchiveTable>> buildChildrenByParentNodeId(
        List<PreparedArchiveTable> childTables) {
        Map<String, List<PreparedArchiveTable>> result = new LinkedHashMap<>();
        for (PreparedArchiveTable childTable : childTables) {
            String parentNodeId = childTable.request.getParentNodeId();
            List<PreparedArchiveTable> children = result.get(parentNodeId);
            if (children == null) {
                children = new ArrayList<>();
                result.put(parentNodeId, children);
            }
            children.add(childTable);
        }
        return result;
    }

    private int resolveColumnIndex(String columnName, List<ColumnMeta> columns, String label) {
        String normalized = normalizeName(columnName);
        for (int i = 0; i < columns.size(); i++) {
            if (normalized.equals(normalizeName(columns.get(i).name))) {
                return i;
            }
        }
        throw new IllegalArgumentException(label + "不在数据字段中：" + columnName);
    }

    private String buildInsertSql(QualifiedName targetName, List<ColumnMeta> columns, String databaseType) {
        List<String> quotedColumns = new ArrayList<>();
        for (ColumnMeta column : columns) {
            quotedColumns.add(SqlSupport.quoteIdentifier(column.name, databaseType));
        }
        return "INSERT INTO " + quoteQualifiedName(targetName, databaseType) + " ("
            + join(quotedColumns, ", ") + ") VALUES (" + placeholders(columns.size()) + ")";
    }

    private String quoteQualifiedName(QualifiedName name, String databaseType) {
        String quotedTable = SqlSupport.quoteIdentifier(configuredIdentifier(name.getTable(), databaseType),
            databaseType);
        return name.getSchema() == null ? quotedTable
            : SqlSupport.quoteIdentifier(configuredIdentifier(name.getSchema(), databaseType), databaseType) + "."
                + quotedTable;
    }

    private String configuredIdentifier(String identifier, String databaseType) {
        return MigrationConstants.DB_ORACLE.equals(databaseType) ? identifier.toUpperCase(Locale.ROOT) : identifier;
    }

    private String canonicalTableIdentity(Connection connection, QualifiedName name, String databaseType)
        throws SQLException {
        String namespace = name.getSchema();
        if (namespace == null || namespace.trim().isEmpty()) {
            namespace = MigrationConstants.DB_MYSQL.equals(databaseType) ? connection.getCatalog()
                : connection.getSchema();
        }
        if (namespace == null || namespace.trim().isEmpty()) {
            namespace = connection.getMetaData().getUserName();
        }
        return normalizeName(namespace) + "." + normalizeName(name.getTable());
    }

    private void logSql(MigrationTask task, String operation, String sql, List<Object> parameters) {
        task.backendSql("SQL执行[" + operation + "]：" + sql + "；参数=" + formatSqlParameters(parameters));
    }

    private void logBatchSql(MigrationTask task, String operation, String sql, List<Object[]> rows) {
        task.backendSql("SQL执行[" + operation + "]：" + sql + "；JDBC批量行数=" + rows.size()
            + "；业务字段参数不逐行展开，避免后台日志写入完整业务数据");
    }

    private String formatSqlParameters(List<Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "[]";
        }
        StringBuilder result = new StringBuilder("[");
        int loggedCount = Math.min(parameters.size(), MAX_SQL_LOG_PARAMETERS);
        for (int i = 0; i < loggedCount; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(formatSqlParameter(parameters.get(i)));
        }
        if (parameters.size() > loggedCount) {
            result.append(", ... 共").append(parameters.size()).append("个参数");
        }
        return result.append(']').toString();
    }

    private String formatSqlParameter(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[]) {
            return "[BINARY length=" + ((byte[])value).length + "]";
        }
        String text = String.valueOf(value).replace("'", "''").replace("\r", "\\r").replace("\n", "\\n");
        if (text.length() > MAX_SQL_LOG_VALUE_LENGTH) {
            text = text.substring(0, MAX_SQL_LOG_VALUE_LENGTH) + "...";
        }
        return value instanceof Number || value instanceof Boolean ? text : "'" + text + "'";
    }

    private void validateNoPhysicalTableOverlap(List<PreparedArchiveTable> mainTables,
        List<PreparedArchiveTable> childTables) {
        Set<String> sourceIdentities = new LinkedHashSet<>();
        Set<String> targetIdentities = new LinkedHashSet<>();
        for (PreparedArchiveTable mainTable : mainTables) {
            if (!sourceIdentities.add(mainTable.sourceIdentity)) {
                throw new IllegalArgumentException("重复配置物理源表：" + mainTable.sourceName.toSql());
            }
            if (!targetIdentities.add(mainTable.targetIdentity)) {
                throw new IllegalArgumentException("重复配置物理归档表：" + mainTable.targetName.toSql());
            }
        }
        for (PreparedArchiveTable childTable : childTables) {
            if (!sourceIdentities.add(childTable.sourceIdentity)) {
                throw new IllegalArgumentException("重复配置物理源表：" + childTable.sourceName.toSql());
            }
            if (!targetIdentities.add(childTable.targetIdentity)) {
                throw new IllegalArgumentException("重复配置物理归档表：" + childTable.targetName.toSql());
            }
        }
        Set<String> overlap = new LinkedHashSet<>(sourceIdentities);
        overlap.retainAll(targetIdentities);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("源表与归档表存在物理对象交叉复用，禁止执行：" + overlap.iterator().next());
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    private void setStatementValue(PreparedStatement statement, int parameterIndex, Object value, int jdbcType)
        throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, jdbcType);
        } else if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB || jdbcType == Types.LONGVARCHAR
            || jdbcType == Types.LONGNVARCHAR) {
            statement.setString(parameterIndex, String.valueOf(value));
        } else if (jdbcType == Types.BLOB || jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
            || jdbcType == Types.LONGVARBINARY) {
            statement.setBytes(parameterIndex, (byte[])value);
        } else {
            statement.setObject(parameterIndex, value);
        }
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

    private long countAffectedRows(int[] counts, int expectedRows) throws SQLException {
        if (counts == null || counts.length != expectedRows) {
            throw new SQLException("数据库批量写入返回数量不完整");
        }
        long affected = 0L;
        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new SQLException("数据库批量写入存在失败记录");
            }
            affected += count == Statement.SUCCESS_NO_INFO ? 1L : Math.max(0, count);
        }
        return affected;
    }

    private long queryLong(MigrationTask task, Connection connection, String sql, List<Object> parameters)
        throws Exception {
        logSql(task, "归档完整性校验", sql, parameters);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            task.setCurrentStatement(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("校验查询未返回结果");
                }
                return resultSet.getLong(1);
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }

    private Map<String, ColumnMeta> indexColumns(List<ColumnMeta> columns) {
        Map<String, ColumnMeta> result = new LinkedHashMap<>();
        for (ColumnMeta column : columns) {
            result.put(normalizeName(column.name), column);
        }
        return result;
    }

    private String placeholders(int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append('?');
        }
        return result.toString();
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                result.append(delimiter);
            }
            result.append(values.get(i));
        }
        return result.toString();
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean cursorValuesEqual(Object left, Object right) {
        if (left instanceof byte[] && right instanceof byte[]) {
            return Arrays.equals((byte[])left, (byte[])right);
        }
        return left == null ? right == null : left.equals(right);
    }

    private long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private long sumProcessed(Map<Integer, ArchiveBatchStats> childStats) {
        long result = 0L;
        for (ArchiveBatchStats stats : childStats.values()) {
            result += stats.processed;
        }
        return result;
    }

    private void markAllSteps(MigrationTask task, PreparedArchive prepared, String status) {
        for (PreparedArchiveTable mainTable : prepared.mainTables) {
            task.setStepStatus(mainTable.index, status);
        }
        for (PreparedArchiveTable childTable : prepared.childTables) {
            task.setStepStatus(childTable.index, status);
        }
    }

    private void markRunningStepsFailed(MigrationTask task) {
        ArchiveRequest archive = task.getRequest().getArchive();
        int mainTableCount = archive == null || archive.getMainTables() == null ? 0 : archive.getMainTables().size();
        int childTableCount = archive == null || archive.getChildTables() == null ? 0 : archive.getChildTables().size();
        int tableCount = mainTableCount + childTableCount;
        for (int i = 0; i < tableCount; i++) {
            task.setStepStatus(i, STEP_FAILED);
        }
    }

    private void rollbackQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.rollback();
            } catch (Exception ignored) {
                // 原始异常更重要，连接关闭继续兜底。
            }
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
                // 任务结果已确定，不覆盖主异常。
            }
        }
    }

    private static final class PreparedArchive {
        private final List<PreparedArchiveTable> mainTables;
        private final List<PreparedArchiveTable> childTables;
        private final Map<String, List<PreparedArchiveTable>> childrenByParentNodeId;
        private final long totalRows;

        private PreparedArchive(List<PreparedArchiveTable> mainTables, List<PreparedArchiveTable> childTables,
            Map<String, List<PreparedArchiveTable>> childrenByParentNodeId, long totalRows) {
            this.mainTables = mainTables;
            this.childTables = childTables;
            this.childrenByParentNodeId = childrenByParentNodeId;
            this.totalRows = totalRows;
        }

        private List<PreparedArchiveTable> childrenOf(String parentNodeId) {
            List<PreparedArchiveTable> children = childrenByParentNodeId.get(parentNodeId);
            return children == null ? java.util.Collections.<PreparedArchiveTable>emptyList() : children;
        }
    }

    private static final class PreparedArchiveTable {
        private final int index;
        private final ArchiveTableRequest request;
        private final String displayName;
        private final QualifiedName sourceName;
        private final QualifiedName targetName;
        private final String sourceIdentity;
        private final String targetIdentity;
        private final String sourceSql;
        private final List<ColumnMeta> columns;
        private final int keyIndex;
        private final boolean keysetPagination;
        private final String insertSql;
        private final long totalRows;
        private int[] parentRelationIndexes = new int[0];

        private PreparedArchiveTable(int index, ArchiveTableRequest request, QualifiedName sourceName,
            QualifiedName targetName, String sourceIdentity, String targetIdentity, String sourceSql,
            List<ColumnMeta> columns, int keyIndex, boolean keysetPagination, String insertSql, long totalRows) {
            this.index = index;
            this.request = request;
            this.displayName = request.getName();
            this.sourceName = sourceName;
            this.targetName = targetName;
            this.sourceIdentity = sourceIdentity;
            this.targetIdentity = targetIdentity;
            this.sourceSql = sourceSql;
            this.columns = columns;
            this.keyIndex = keyIndex;
            this.keysetPagination = keysetPagination;
            this.insertSql = insertSql;
            this.totalRows = totalRows;
        }

        private ColumnMeta keyColumn() {
            return columns.get(keyIndex);
        }
    }

    private static final class ColumnMeta {
        private final String name;
        private final int jdbcType;
        private final String typeName;
        private final int precision;
        private final int scale;
        private final int nullable;

        private ColumnMeta(String name, int jdbcType, String typeName, int precision, int scale, int nullable) {
            this.name = name;
            this.jdbcType = jdbcType;
            this.typeName = typeName;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
        }

        private String describe() {
            return typeName + "(" + precision + "," + scale + "),nullable=" + nullable;
        }
    }

    private static final class RelationKey {
        private final Object[] values;

        private RelationKey(Object[] values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RelationKey && Arrays.deepEquals(values, ((RelationKey)other).values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }
    }

    private static final class RelationQuery {
        private final String sql;
        private final List<Object> parameters;

        private RelationQuery(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
        }
    }

    private static final class QueryWithParameters {
        private final String sql;
        private final List<Object> parameters;

        private QueryWithParameters(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
        }
    }

    private static final class ArchiveBatchStats {
        private long processed;
        private long inserted;
        private long deleted;
    }
}
