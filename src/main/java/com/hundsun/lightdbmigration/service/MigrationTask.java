package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.StepProgress;
import com.hundsun.lightdbmigration.model.TaskLog;
import com.hundsun.lightdbmigration.model.TaskStatus;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

public class MigrationTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationTask.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String LOG_INFO = "INFO";
    private static final String LOG_WARN = "WARN";
    private static final String LOG_ERROR = "ERROR";

    private final String id;
    private final CreateTaskRequest request;
    private final String createdAt = LocalDateTime.now().format(FORMATTER);
    private final Object controlMonitor = new Object();
    private final ConcurrentLinkedDeque<TaskLog> logs = new ConcurrentLinkedDeque<>();
    private final AtomicLong logSequence = new AtomicLong(0L);
    private final AtomicInteger logCount = new AtomicInteger(0);
    private final Set<Statement> activeStatements = java.util.Collections.newSetFromMap(
        new ConcurrentHashMap<Statement, Boolean>());
    private final List<StepProgress> stepProgressList = new ArrayList<>();
    private volatile TaskStatus status = TaskStatus.CREATED;
    private volatile String startedAt;
    private volatile String finishedAt;
    private volatile String currentMessage = "等待启动";
    private volatile long totalRows;
    private volatile long processedRows;
    private volatile boolean pauseRequested;
    private volatile boolean stopRequested;
    private volatile String structureSyncScript;

    public MigrationTask(String id, CreateTaskRequest request) {
        this.id = id;
        this.request = request;
        if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(request.getTaskType())) {
            List<ArchiveTableRequest> mainTables = request.getArchive().getMainTables();
            for (int i = 0; i < mainTables.size(); i++) {
                stepProgressList.add(new StepProgress(i + 1, mainTables.get(i)));
            }
            List<ArchiveTableRequest> childTables = request.getArchive().getChildTables();
            for (int i = 0; i < childTables.size(); i++) {
                stepProgressList.add(new StepProgress(mainTables.size() + i + 1, childTables.get(i)));
            }
            info("归档任务已创建，共 " + mainTables.size() + " 张主业务表、" + childTables.size()
                + " 张子业务表；启动后将逐个主表按批次写入归档表并删除源数据");
        } else {
            List<MigrationStepRequest> steps = request.getSteps();
            for (int i = 0; i < steps.size(); i++) {
                stepProgressList.add(new StepProgress(i + 1, steps.get(i)));
            }
            info("任务已创建，共 " + steps.size() + " 个迁移步骤");
        }
    }

    public String getId() {
        return id;
    }

    public CreateTaskRequest getRequest() {
        return request;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void markQueued() {
        status = TaskStatus.QUEUED;
        currentMessage = "等待执行线程";
        info("任务已进入执行队列");
    }

    public void markStarted() {
        startedAt = LocalDateTime.now().format(FORMATTER);
        status = TaskStatus.VALIDATING;
        currentMessage = "正在连接数据库并校验表结构";
    }

    public void markValidating(String message) {
        status = TaskStatus.VALIDATING;
        currentMessage = message;
    }

    public void markRunning(String message) {
        status = TaskStatus.RUNNING;
        currentMessage = message;
    }

    public void markSucceeded() {
        status = TaskStatus.SUCCEEDED;
        boolean archiveTask = MigrationConstants.TASK_TYPE_ARCHIVE.equals(request.getTaskType());
        currentMessage = archiveTask ? "归档完成" : "迁移完成";
        finishedAt = LocalDateTime.now().format(FORMATTER);
        info(archiveTask ? "全部主表及子表归档完成，已归档数据对应的源记录已删除"
            : "全部迁移步骤执行完成");
    }

    public void markFailed(String message) {
        status = TaskStatus.FAILED;
        currentMessage = message;
        finishedAt = LocalDateTime.now().format(FORMATTER);
        error(message);
    }

    public void markTerminated() {
        status = TaskStatus.TERMINATED;
        currentMessage = "任务已终止";
        finishedAt = LocalDateTime.now().format(FORMATTER);
        warn("任务已按用户要求终止，已提交的批次不会回滚");
    }

    public void pause() {
        synchronized (controlMonitor) {
            if (isTerminal()) {
                throw new IllegalStateException("当前任务已经结束，不能暂停");
            }
            if (status == TaskStatus.CREATED) {
                throw new IllegalStateException("任务尚未启动");
            }
            if (!pauseRequested) {
                pauseRequested = true;
                status = TaskStatus.PAUSING;
                currentMessage = "等待当前批次结束后暂停";
                info("已收到暂停请求，将在当前查询或写入批次结束后暂停");
            }
        }
    }

    public void resume() {
        synchronized (controlMonitor) {
            if (!pauseRequested) {
                throw new IllegalStateException("当前任务不在暂停状态");
            }
            pauseRequested = false;
            status = TaskStatus.RUNNING;
            currentMessage = "任务继续执行";
            controlMonitor.notifyAll();
            info("已收到继续请求");
        }
    }

    public void stop() {
        synchronized (controlMonitor) {
            if (isTerminal()) {
                throw new IllegalStateException("当前任务已经结束");
            }
            stopRequested = true;
            pauseRequested = false;
            status = TaskStatus.STOPPING;
            currentMessage = "正在终止当前数据库操作";
            controlMonitor.notifyAll();
            warn("已收到终止请求，正在取消当前数据库操作");
        }
        for (Statement statement : activeStatements) {
            try {
                statement.cancel();
            } catch (Exception exception) {
                warn("数据库驱动未完成即时取消，将在当前数据库操作返回后按终止标记退出；原因="
                    + exception.getClass().getSimpleName());
            }
        }
    }

    public void checkpoint() throws StopRequestedException, InterruptedException {
        synchronized (controlMonitor) {
            if (stopRequested) {
                throw new StopRequestedException();
            }
            if (pauseRequested) {
                status = TaskStatus.PAUSED;
                currentMessage = "任务已暂停";
                info("任务已暂停，可点击继续恢复执行");
                while (pauseRequested && !stopRequested) {
                    controlMonitor.wait();
                }
                if (stopRequested) {
                    throw new StopRequestedException();
                }
                status = TaskStatus.RUNNING;
            }
        }
    }

    public void stopCheckpoint() throws StopRequestedException {
        if (stopRequested) {
            throw new StopRequestedException();
        }
    }

    public void setCurrentStatement(Statement statement) {
        if (statement == null) {
            return;
        }
        activeStatements.add(statement);
        if (stopRequested) {
            try {
                statement.cancel();
            } catch (Exception exception) {
                warn("任务已请求终止，新数据库操作未能即时取消；原因="
                    + exception.getClass().getSimpleName());
            }
        }
    }

    public void clearCurrentStatement(Statement statement) {
        activeStatements.remove(statement);
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public void setStepTotal(int stepIndex, long rows) {
        stepProgressList.get(stepIndex).setTotalRows(rows);
    }

    public void setStepStatus(int stepIndex, String stepStatus) {
        stepProgressList.get(stepIndex).setStatus(stepStatus);
    }

    public void setStructureSyncScript(String structureSyncScript) {
        this.structureSyncScript = structureSyncScript;
    }

    public synchronized void addBatch(int stepIndex, long processed, long inserted, long ignored) {
        processedRows += processed;
        stepProgressList.get(stepIndex).addBatch(processed, inserted, ignored);
    }

    public synchronized void addArchiveBatch(int stepIndex, long processed, long inserted, long deleted) {
        processedRows += processed;
        stepProgressList.get(stepIndex).addArchiveBatch(processed, inserted, deleted);
    }

    public boolean isStopRequested() {
        return stopRequested;
    }

    public boolean isTerminal() {
        return status == TaskStatus.TERMINATED || status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED;
    }

    public void clearPasswords() {
        if (request.getSource() != null) {
            request.getSource().clearPassword();
        }
        if (request.getTarget() != null && request.getTarget() != request.getSource()) {
            request.getTarget().clearPassword();
        }
    }

    public void info(String message) {
        appendLog(LOG_INFO, message);
        LOGGER.info("[taskId={}] {}", id, message);
    }

    public void warn(String message) {
        appendLog(LOG_WARN, message);
        LOGGER.warn("[taskId={}] {}", id, message);
    }

    public void error(String message) {
        appendLog(LOG_ERROR, message);
        LOGGER.error("[taskId={}] {}", id, message);
    }

    void backendSql(String message) {
        LOGGER.info("[taskId={}] {}", id, message);
    }

    public Map<String, Object> snapshot(boolean includeLogs, long afterSequence) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", request.getName());
        result.put("taskType", request.getTaskType());
        result.put("status", status.name());
        result.put("createdAt", createdAt);
        result.put("startedAt", startedAt);
        result.put("finishedAt", finishedAt);
        result.put("batchSize", request.getBatchSize());
        result.put("currentMessage", currentMessage);
        result.put("source", databaseSummary(request.getSource()));
        result.put("target", databaseSummary(request.getTarget()));
        result.put("totalRows", totalRows);
        result.put("processedRows", processedRows);
        long percent = totalRows <= 0L ? (status == TaskStatus.SUCCEEDED ? 100L : 0L)
            : Math.min(100L, processedRows * 100L / totalRows);
        result.put("percent", percent);
        List<Map<String, Object>> stepSnapshots = new ArrayList<>();
        for (StepProgress stepProgress : stepProgressList) {
            stepSnapshots.add(stepProgress.toMap());
        }
        result.put("steps", stepSnapshots);
        if (includeLogs) {
            result.put("structureSyncScript", structureSyncScript);
            List<Map<String, Object>> logSnapshots = new ArrayList<>();
            for (TaskLog taskLog : logs) {
                if (taskLog.getSequence() > afterSequence) {
                    logSnapshots.add(taskLog.toMap());
                }
            }
            result.put("logs", logSnapshots);
            result.put("lastLogSequence", logSequence.get());
        }
        return result;
    }

    private Map<String, Object> databaseSummary(com.hundsun.lightdbmigration.model.DatabaseConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", config.getType());
        result.put("endpoint", DatabaseSupport.displayEndpoint(config));
        result.put("username", config.getUsername());
        return result;
    }

    private void appendLog(String level, String message) {
        long sequence = logSequence.incrementAndGet();
        logs.addLast(new TaskLog(sequence, level, message));
        int currentCount = logCount.incrementAndGet();
        while (currentCount > MigrationConstants.MAX_TASK_LOGS) {
            if (logs.pollFirst() != null) {
                currentCount = logCount.decrementAndGet();
            } else {
                break;
            }
        }
    }
}
