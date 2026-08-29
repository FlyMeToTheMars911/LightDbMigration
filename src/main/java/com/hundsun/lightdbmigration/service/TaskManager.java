package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final LastDatabaseConfigStore lastDatabaseConfigStore;
    private final ExecutorService executorService;

    public TaskManager(MigrationEngine migrationEngine, LastDatabaseConfigStore lastDatabaseConfigStore) {
        this.migrationEngine = migrationEngine;
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
                    migrationEngine.execute(task);
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
        result.put("version", "1.1.0");
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
