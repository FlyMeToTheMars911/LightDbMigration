package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.LastDatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.TaskStatus;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TaskManager implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskManager.class);

    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();
    private final MigrationEngine migrationEngine;
    private final ArchiveEngine archiveEngine;
    private final LastDatabaseConfigStore lastDatabaseConfigStore;
    private final ExecutorService executorService;

    public TaskManager(MigrationEngine migrationEngine, ArchiveEngine archiveEngine,
        LastDatabaseConfigStore lastDatabaseConfigStore) {
        this.migrationEngine = migrationEngine;
        this.archiveEngine = archiveEngine;
        this.lastDatabaseConfigStore = lastDatabaseConfigStore;
        this.executorService = Executors.newFixedThreadPool(MigrationConstants.EXECUTOR_SIZE,
            new MigrationThreadFactory());
    }

    public Map<String, Object> create(CreateTaskRequest request) {
        validateRequest(request);
        removeOldTerminalTasksIfNecessary();
        if (tasks.size() >= MigrationConstants.MAX_TASKS) {
            throw new IllegalStateException("任务数量已达到上限，请先清除已结束任务");
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MigrationTask task = new MigrationTask(id, request);
        try {
            lastDatabaseConfigStore.save(request.getSource(), request.getTarget());
            LOGGER.info("最近一次数据库配置已加密保存到 {}", lastDatabaseConfigStore.displayLocation());
        } catch (Exception exception) {
            LOGGER.warn("最近一次数据库配置保存失败，迁移任务仍可继续：{}",
                SqlSupport.safeMessage(exception));
            task.warn("最近一次数据库配置保存失败，本次任务仍可继续；服务重启后页面将无法自动回填");
        }
        tasks.put(id, task);
        return task.snapshot(true, 0L);
    }

    public LastDatabaseConfig getLastDatabaseConfig() {
        try {
            return lastDatabaseConfigStore.load();
        } catch (Exception exception) {
            LOGGER.warn("最近一次数据库配置读取失败：{}", SqlSupport.safeMessage(exception));
            throw new IllegalStateException("最近一次数据库配置读取失败，请清除已保存配置后重新创建任务");
        }
    }

    public void saveLastDatabaseConfig(LastDatabaseConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("数据库配置不能为空");
        }
        DatabaseSupport.validate(config.getSource());
        DatabaseSupport.validate(config.getTarget());
        try {
            lastDatabaseConfigStore.save(config.getSource(), config.getTarget());
            LOGGER.info("最近一次数据库配置已由页面加密保存到 {}", lastDatabaseConfigStore.displayLocation());
        } catch (Exception exception) {
            LOGGER.warn("最近一次数据库配置保存失败：{}", SqlSupport.safeMessage(exception));
            throw new IllegalStateException("数据库配置保存失败，请检查 data 目录权限");
        }
    }

    public void clearLastDatabaseConfig() {
        try {
            lastDatabaseConfigStore.clear();
            LOGGER.info("已清除最近一次数据库配置");
        } catch (Exception exception) {
            LOGGER.warn("最近一次数据库配置清除失败：{}", SqlSupport.safeMessage(exception));
            throw new IllegalStateException("清除最近一次数据库配置失败，请检查 data 目录权限");
        }
    }

    public List<Map<String, Object>> list() {
        List<MigrationTask> taskList = new ArrayList<>(tasks.values());
        Collections.sort(taskList, Comparator.comparing((MigrationTask task) ->
            String.valueOf(task.snapshot(false, 0L).get("createdAt"))).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (MigrationTask task : taskList) {
            result.add(task.snapshot(false, 0L));
        }
        return result;
    }

    public Map<String, Object> get(String id, long afterSequence) {
        return requireTask(id).snapshot(true, Math.max(0L, afterSequence));
    }

    public Map<String, Object> start(String id) {
        final MigrationTask task = requireTask(id);
        synchronized (task) {
            if (task.getStatus() != TaskStatus.CREATED) {
                throw new IllegalStateException("只有待启动任务可以启动");
            }
            task.markQueued();
            executorService.submit(new Runnable() {
                @Override
                public void run() {
                    if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(task.getRequest().getTaskType())) {
                        archiveEngine.execute(task);
                    } else {
                        migrationEngine.execute(task);
                    }
                }
            });
        }
        return task.snapshot(false, 0L);
    }

    public Map<String, Object> pause(String id) {
        MigrationTask task = requireTask(id);
        task.pause();
        return task.snapshot(false, 0L);
    }

    public Map<String, Object> resume(String id) {
        MigrationTask task = requireTask(id);
        task.resume();
        return task.snapshot(false, 0L);
    }

    public Map<String, Object> stop(String id) {
        MigrationTask task = requireTask(id);
        task.stop();
        return task.snapshot(false, 0L);
    }

    public void delete(String id) {
        MigrationTask task = requireTask(id);
        if (!task.isTerminal() && task.getStatus() != TaskStatus.CREATED) {
            throw new IllegalStateException("运行中的任务不能清除，请先终止任务");
        }
        task.clearPasswords();
        tasks.remove(id, task);
    }

    public Map<String, Object> systemInfo() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "Light DB Migration");
        result.put("version", "1.2.0");
        result.put("supportedDatabases", new String[] {MigrationConstants.DB_MYSQL, MigrationConstants.DB_ORACLE});
        result.put("maxConcurrentTasks", MigrationConstants.EXECUTOR_SIZE);
        result.put("taskDataStorage", "MEMORY_ONLY");
        result.put("lastDatabaseConfigStorage", "LOCAL_AES_GCM");
        return result;
    }

    private void validateRequest(CreateTaskRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("任务配置不能为空");
        }
        String taskType = normalizeTaskType(request.getTaskType());
        request.setTaskType(taskType);
        if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(taskType)) {
            validateArchiveRequest(request);
            return;
        }
        DatabaseSupport.validate(request.getSource());
        DatabaseSupport.validate(request.getTarget());
        if (request.getBatchSize() == null) {
            request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        }
        if (request.getBatchSize() < MigrationConstants.MIN_BATCH_SIZE
            || request.getBatchSize() > MigrationConstants.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("批次大小必须在 " + MigrationConstants.MIN_BATCH_SIZE + " 到 "
                + MigrationConstants.MAX_BATCH_SIZE + " 之间");
        }
        if (request.getSteps() == null || request.getSteps().isEmpty()) {
            throw new IllegalArgumentException("至少需要配置一个迁移 SQL");
        }
        if (request.getSteps().size() > MigrationConstants.MAX_STEPS) {
            throw new IllegalArgumentException("单个任务最多支持 " + MigrationConstants.MAX_STEPS + " 个迁移步骤");
        }
        if (isBlank(request.getName())) {
            request.setName("迁移任务-" + System.currentTimeMillis());
        } else {
            request.setName(request.getName().trim());
        }
        for (int i = 0; i < request.getSteps().size(); i++) {
            MigrationStepRequest step = request.getSteps().get(i);
            if (step == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个迁移步骤不能为空");
            }
            if (isBlank(step.getName())) {
                step.setName("SQL " + (i + 1));
            } else {
                step.setName(step.getName().trim());
            }
            step.setSourceSql(SqlSupport.normalizeSourceQuery(step.getSourceSql()));
            SqlSupport.parseTargetTable(step.getTargetTable());
            if (isBlank(step.getInsertMode())) {
                step.setInsertMode(MigrationConstants.INSERT_IGNORE);
            } else {
                String insertMode = step.getInsertMode().trim().toUpperCase(Locale.ROOT);
                if (!MigrationConstants.INSERT_IGNORE.equals(insertMode)
                    && !MigrationConstants.INSERT_STRICT.equals(insertMode)) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " 个步骤的写入方式不合法");
                }
                step.setInsertMode(insertMode);
            }
        }
    }

    private void validateArchiveRequest(CreateTaskRequest request) {
        DatabaseSupport.validate(request.getSource());
        request.setTarget(request.getSource());
        if (request.getBatchSize() == null) {
            request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        }
        if (request.getBatchSize() < MigrationConstants.MIN_BATCH_SIZE
            || request.getBatchSize() > MigrationConstants.MAX_ARCHIVE_BATCH_SIZE) {
            throw new IllegalArgumentException("归档批次大小必须在 " + MigrationConstants.MIN_BATCH_SIZE + " 到 "
                + MigrationConstants.MAX_ARCHIVE_BATCH_SIZE + " 之间");
        }
        if (isBlank(request.getName())) {
            request.setName("归档任务-" + System.currentTimeMillis());
        } else {
            request.setName(request.getName().trim());
        }
        ArchiveRequest archive = request.getArchive();
        if (archive == null) {
            throw new IllegalArgumentException("归档配置不能为空");
        }
        if (!Boolean.TRUE.equals(archive.getConfirmSourceDelete())) {
            throw new IllegalArgumentException("必须明确勾选确认：归档成功后删除源表数据");
        }
        ArchiveTableRequest mainTable = archive.getMainTable();
        if (mainTable == null) {
            throw new IllegalArgumentException("主业务表归档配置不能为空");
        }
        normalizeArchiveTable(mainTable, "主业务表", true);
        mainTable.setNodeId(MigrationConstants.ARCHIVE_MAIN_NODE_ID);
        mainTable.setParentNodeId(null);
        List<ArchiveTableRequest> childTables = archive.getChildTables();
        if (childTables == null) {
            childTables = new ArrayList<>();
            archive.setChildTables(childTables);
        }
        if (childTables.size() + 1 > MigrationConstants.MAX_STEPS) {
            throw new IllegalArgumentException("单个任务最多支持 " + MigrationConstants.MAX_STEPS + " 张归档表");
        }
        Set<String> sourceTables = new LinkedHashSet<>();
        Set<String> targetTables = new LinkedHashSet<>();
        Map<String, ArchiveTableRequest> childByNodeId = new LinkedHashMap<>();
        sourceTables.add(normalizeTableIdentity(mainTable.getSourceTable()));
        targetTables.add(normalizeTableIdentity(mainTable.getTargetTable()));
        for (int i = 0; i < childTables.size(); i++) {
            ArchiveTableRequest childTable = childTables.get(i);
            if (childTable == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 张子业务表配置不能为空");
            }
            String label = "第 " + (i + 1) + " 张子业务表";
            normalizeArchiveTable(childTable, label, false);
            String nodeId = isBlank(childTable.getNodeId()) ? MigrationConstants.ARCHIVE_CHILD_NODE_PREFIX + (i + 1)
                : childTable.getNodeId().trim().toUpperCase(Locale.ROOT);
            nodeId = SqlSupport.normalizeColumnName(nodeId, label + "节点标识").toUpperCase(Locale.ROOT);
            if (MigrationConstants.ARCHIVE_MAIN_NODE_ID.equals(nodeId)) {
                throw new IllegalArgumentException(label + "节点标识不能使用保留值 MAIN");
            }
            if (childByNodeId.put(nodeId, childTable) != null) {
                throw new IllegalArgumentException("从表节点标识重复：" + nodeId);
            }
            childTable.setNodeId(nodeId);
            String parentNodeId = isBlank(childTable.getParentNodeId())
                ? MigrationConstants.ARCHIVE_MAIN_NODE_ID
                : childTable.getParentNodeId().trim().toUpperCase(Locale.ROOT);
            childTable.setParentNodeId(SqlSupport.normalizeColumnName(parentNodeId, label + "上级节点标识")
                .toUpperCase(Locale.ROOT));
            String sourceIdentity = normalizeTableIdentity(childTable.getSourceTable());
            String targetIdentity = normalizeTableIdentity(childTable.getTargetTable());
            if (!sourceTables.add(sourceIdentity)) {
                throw new IllegalArgumentException(label + "的源表重复配置：" + childTable.getSourceTable());
            }
            if (!targetTables.add(targetIdentity)) {
                throw new IllegalArgumentException(label + "的归档表重复配置：" + childTable.getTargetTable());
            }
        }
        Set<String> overlap = new LinkedHashSet<>(sourceTables);
        overlap.retainAll(targetTables);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("源表不能同时作为任一归档目标表：" + overlap.iterator().next());
        }
        archive.setChildTables(sortArchiveChildren(childTables, childByNodeId));
        request.setSteps(Collections.<MigrationStepRequest>emptyList());
    }

    private void normalizeArchiveTable(ArchiveTableRequest table, String defaultName, boolean mainTable) {
        if (isBlank(table.getName())) {
            table.setName(defaultName);
        } else {
            table.setName(table.getName().trim());
        }
        table.setSourceTable(SqlSupport.parseTableName(table.getSourceTable(), defaultName + "源表").toSql());
        table.setTargetTable(SqlSupport.parseTableName(table.getTargetTable(), defaultName + "归档表").toSql());
        if (normalizeTableIdentity(table.getSourceTable()).equals(normalizeTableIdentity(table.getTargetTable()))) {
            throw new IllegalArgumentException(defaultName + "的源表与归档表不能相同");
        }
        table.setCursorColumn(SqlSupport.normalizeColumnName(table.getCursorColumn(), defaultName + "唯一游标字段"));
        if (mainTable) {
            table.setSourceSql(SqlSupport.normalizeSourceQuery(table.getSourceSql()));
            table.setRelations(Collections.<ArchiveRelationRequest>emptyList());
            return;
        }
        table.setSourceSql(null);
        List<ArchiveRelationRequest> relations = table.getRelations();
        if (relations == null || relations.isEmpty()) {
            throw new IllegalArgumentException(defaultName + "至少需要一组与上级表的关联字段");
        }
        if (relations.size() > MigrationConstants.MAX_ARCHIVE_RELATIONS) {
            throw new IllegalArgumentException(defaultName + "最多支持 " + MigrationConstants.MAX_ARCHIVE_RELATIONS
                + " 组关联字段");
        }
        Set<String> childColumns = new LinkedHashSet<>();
        for (int i = 0; i < relations.size(); i++) {
            ArchiveRelationRequest relation = relations.get(i);
            if (relation == null) {
                throw new IllegalArgumentException(defaultName + "第 " + (i + 1) + " 组关联字段不能为空");
            }
            relation.setChildColumn(SqlSupport.normalizeColumnName(relation.getChildColumn(),
                defaultName + "子表关联字段"));
            relation.setMainColumn(SqlSupport.normalizeColumnName(relation.getMainColumn(),
                defaultName + "上级表关联字段"));
            if (!childColumns.add(relation.getChildColumn().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException(defaultName + "重复配置子表关联字段：" + relation.getChildColumn());
            }
        }
    }

    private List<ArchiveTableRequest> sortArchiveChildren(List<ArchiveTableRequest> childTables,
        Map<String, ArchiveTableRequest> childByNodeId) {
        List<ArchiveTableRequest> sorted = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (ArchiveTableRequest childTable : childTables) {
            visitArchiveNode(childTable, childByNodeId, visiting, visited, sorted);
        }
        return sorted;
    }

    private void visitArchiveNode(ArchiveTableRequest childTable, Map<String, ArchiveTableRequest> childByNodeId,
        Set<String> visiting, Set<String> visited, List<ArchiveTableRequest> sorted) {
        String nodeId = childTable.getNodeId();
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            throw new IllegalArgumentException("归档从表关系存在循环，涉及节点：" + nodeId);
        }
        String parentNodeId = childTable.getParentNodeId();
        if (!MigrationConstants.ARCHIVE_MAIN_NODE_ID.equals(parentNodeId)) {
            ArchiveTableRequest parent = childByNodeId.get(parentNodeId);
            if (parent == null) {
                throw new IllegalArgumentException("归档从表节点 " + nodeId + " 的上级节点不存在："
                    + parentNodeId);
            }
            visitArchiveNode(parent, childByNodeId, visiting, visited, sorted);
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
        sorted.add(childTable);
    }

    private String normalizeTaskType(String taskType) {
        String normalized = isBlank(taskType) ? MigrationConstants.TASK_TYPE_MIGRATION
            : taskType.trim().toUpperCase(Locale.ROOT);
        if (!MigrationConstants.TASK_TYPE_MIGRATION.equals(normalized)
            && !MigrationConstants.TASK_TYPE_ARCHIVE.equals(normalized)) {
            throw new IllegalArgumentException("任务类型只支持 MIGRATION 或 ARCHIVE");
        }
        return normalized;
    }

    private String normalizeTableIdentity(String tableName) {
        return tableName == null ? "" : tableName.trim().toUpperCase(Locale.ROOT);
    }

    private MigrationTask requireTask(String id) {
        MigrationTask task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在或服务重启后已清空：" + id);
        }
        return task;
    }

    private void removeOldTerminalTasksIfNecessary() {
        if (tasks.size() < MigrationConstants.MAX_TASKS) {
            return;
        }
        for (Map.Entry<String, MigrationTask> entry : tasks.entrySet()) {
            if (entry.getValue().isTerminal()) {
                tasks.remove(entry.getKey(), entry.getValue());
                if (tasks.size() < MigrationConstants.MAX_TASKS) {
                    return;
                }
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void destroy() {
        for (MigrationTask task : tasks.values()) {
            if (!task.isTerminal() && task.getStatus() != TaskStatus.CREATED) {
                try {
                    task.stop();
                } catch (Exception ignored) {
                    // 应用退出时继续关闭线程池。
                }
            }
            task.clearPasswords();
        }
        executorService.shutdownNow();
    }

    private static final class MigrationThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "migration-worker-" + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
