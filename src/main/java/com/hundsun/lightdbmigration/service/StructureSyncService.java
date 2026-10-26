package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.StructureSyncRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 在用户明确确认后执行任务自身的结构建议，不自动重试或启动原数据任务。
 * Author: Codex
 * date: 2026-10-26
 */
public final class StructureSyncService {

    /** 每条结构 DDL 的最长等待时间，连接超时沿用既有数据库配置。 */
    private static final int STATEMENT_TIMEOUT_SECONDS = 300;
    private final ConnectionOpener connectionOpener;

    /** Params: 无。 Returns: 使用既有数据库连接管理的执行服务。 */
    public StructureSyncService() {
        this(DatabaseSupport::open);
    }

    /** Params: connectionOpener 单次连接入口，供隔离验证替换真实登录。 Returns: 执行服务。 */
    StructureSyncService(ConnectionOpener connectionOpener) {
        if (connectionOpener == null) {
            throw new IllegalArgumentException("结构同步连接入口不能为空");
        }
        this.connectionOpener = connectionOpener;
    }

    /**
     * 校验确认、摘要、目标连接与脚本操作后，原子占用一次执行机会并逐条执行。
     * Params: task 已失败的原任务；request 本次确认及临时目标连接，不接受 SQL。
     * Returns: 任务详情及独立执行结果；已开始的数据库失败保留成功数量，不自动重试。
     */
    public Map<String, Object> execute(MigrationTask task, StructureSyncRequest request) {
        DatabaseConfig database = request == null ? null : request.getDatabase();
        try {
            validateRequest(task, request);
            List<String> statements = StructureSyncScript.parseStatements(task.getStructureSyncScript());
            if (statements.isEmpty()) {
                throw new IllegalArgumentException("当前建议只有说明，没有可执行 DDL，请先人工处理对应问题");
            }
            validateTargets(task, statements);
            task.beginStructureSync(request.getScriptDigest(), statements.size());
            task.info("用户已确认执行结构建议，共 " + statements.size() + " 条；开始后不会自动重试，"
                + "部分 DDL 成功后不能承诺整体回滚");
            try (Connection connection = connectionOpener.open(database)) {
                connection.setAutoCommit(true);
                task.info("结构同步目标连接成功，开始依次执行已确认的建议");
                for (int i = 0; i < statements.size(); i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("结构同步执行线程已中断");
                    }
                    String sql = statements.get(i);
                    task.info("正在执行结构建议 " + (i + 1) + "/" + statements.size());
                    task.backendSql("SQL执行[用户确认的结构同步 " + (i + 1) + "/" + statements.size()
                        + "]：" + sql + "；参数=[]");
                    try (Statement statement = connection.createStatement()) {
                        statement.setQueryTimeout(STATEMENT_TIMEOUT_SECONDS);
                        statement.execute(sql);
                        task.structureSyncStatementSucceeded();
                        task.info("结构建议 " + (i + 1) + "/" + statements.size() + " 执行成功");
                    }
                }
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Map<String, Object> progress = task.structureSyncExecutionSnapshot();
                String message = "结构同步失败，已成功 " + progress.get("executedStatements") + "/"
                    + progress.get("totalStatements") + " 条；已生效的 DDL 不会自动回滚，后续语句未继续执行。"
                    + "请核对目标结构并重新创建任务预检，禁止直接重放本次脚本。原因=" + safeMessage(exception, database);
                task.finishStructureSync(false, message);
                task.error(message);
                return task.snapshot(true, 0L);
            }
            String message = "结构建议全部执行成功，共 " + statements.size()
                + " 条。原迁移/归档任务不会自动重启，请重新创建任务完成全量预检；仅人工说明的问题仍需处理。";
            task.finishStructureSync(true, message);
            task.info(message);
            return task.snapshot(true, 0L);
        } finally {
            if (database != null) {
                database.clearPassword();
            }
        }
    }

    /** Params: task 原任务；request 临时确认请求。 Returns: 无；不一致时在任何登录前拒绝。 */
    private void validateRequest(MigrationTask task, StructureSyncRequest request) {
        if (task == null || request == null || !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new IllegalArgumentException("请先审核结构建议并再次确认执行");
        }
        String expectedDigest = task.getStructureSyncDigest();
        if (expectedDigest.isEmpty() || !expectedDigest.equals(request.getScriptDigest())) {
            throw new IllegalArgumentException("结构建议与页面确认内容不一致，请刷新任务日志后重新确认");
        }
        DatabaseConfig database = request.getDatabase();
        DatabaseSupport.validate(database);
        DatabaseConfig original = task.getRequest().getTarget();
        if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(task.getRequest().getTaskType())) {
            original = task.getRequest().getSource();
        }
        boolean sameType = DatabaseSupport.normalizeType(original.getType())
            .equals(DatabaseSupport.normalizeType(database.getType()));
        boolean sameUrl = DatabaseSupport.buildJdbcUrl(original).equals(DatabaseSupport.buildJdbcUrl(database));
        boolean sameUsername = trim(original.getUsername()).equals(trim(database.getUsername()));
        if (!sameType || !sameUrl || !sameUsername) {
            throw new IllegalArgumentException("页面数据库配置与该任务原目标不一致，请恢复创建该任务时的目标连接后再执行；"
                + "归档使用该任务原源库连接");
        }
    }

    /** Params: task 原任务；statements 服务端已生成并通过操作校验的脚本。 Returns: 无。 */
    private void validateTargets(MigrationTask task, List<String> statements) {
        Set<String> targets = new LinkedHashSet<>();
        if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(task.getRequest().getTaskType())) {
            for (ArchiveTableRequest table : task.getRequest().getArchive().getMainTables()) {
                targets.add(identity(SqlSupport.parseTargetTable(table.getTargetTable())));
            }
            for (ArchiveTableRequest table : task.getRequest().getArchive().getChildTables()) {
                targets.add(identity(SqlSupport.parseTargetTable(table.getTargetTable())));
            }
        } else {
            for (MigrationStepRequest step : task.getRequest().getSteps()) {
                targets.add(identity(SqlSupport.parseTargetTable(step.getTargetTable())));
            }
        }
        for (String statement : statements) {
            if (!targets.contains(identity(StructureSyncScript.targetTable(statement)))) {
                throw new IllegalArgumentException("结构建议包含该任务未配置的目标表，不能执行，请重新预检");
            }
        }
    }

    /** Params: name 已校验的表名。 Returns: 与任务配置采用相同大小写约定的目标标识。 */
    private String identity(QualifiedName name) {
        return trim(name.getSchema()).toUpperCase(Locale.ROOT) + "." + name.getTable().toUpperCase(Locale.ROOT);
    }

    /** Params: exception 执行异常；database 临时连接。 Returns: 已移除临时密码的错误说明。 */
    private String safeMessage(Exception exception, DatabaseConfig database) {
        String message = SqlSupport.safeMessage(exception);
        String password = database == null ? null : database.getPassword();
        return password == null || password.isEmpty() ? message : message.replace(password, "******");
    }

    /** Params: value 可空文本。 Returns: 去首尾空白后的文本。 */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 结构同步的单次数据库登录边界，可注入代理验证无重试行为。
     * Author: Codex
     * date: 2026-10-26
     */
    interface ConnectionOpener {
        /** Params: database 临时目标连接。 Returns: 新连接；失败时不得自动重试。 */
        Connection open(DatabaseConfig database) throws Exception;
    }
}
