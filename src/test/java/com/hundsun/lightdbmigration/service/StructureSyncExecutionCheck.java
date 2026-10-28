package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.StructureSyncRequest;
import com.hundsun.lightdbmigration.model.TaskStatus;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 使用轻量 JDBC 代理验证结构建议执行的确认、目标约束、逐条计数和单次执行边界。
 * Author: Codex
 * date: 2026-10-26
 */
public final class StructureSyncExecutionCheck {

    private static final String TARGET_ONE = "SYNC_TARGET";
    private static final String TARGET_TWO = "SYNC_TARGET_TWO";
    private static final String TARGET_THREE = "SYNC_TARGET_THREE";
    private static final String TARGET_HOST = "target.fixture.invalid";
    private static final String SOURCE_HOST = "source.fixture.invalid";
    private static final String DATABASE = "sync_fixture";
    private static final String USERNAME = "sync_fixture_user";
    private static final String TEST_SECRET = "synthetic-test-credential-never-used-to-login";
    private static final String READY = "READY";
    private static final String RUNNING = "RUNNING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String SCRIPT_ONE = "ALTER TABLE `" + TARGET_ONE + "` ADD COLUMN `A` INT NULL;";
    private static final String SCRIPT_TWO = "ALTER TABLE `" + TARGET_TWO + "` ADD COLUMN `B` VARCHAR(20) NULL;";
    private static final String SCRIPT_THREE = "ALTER TABLE `" + TARGET_THREE + "` ADD COLUMN `C` INT NULL;";
    private static final String EXECUTION_FIELD = "structureSyncExecution";
    private static final String STATUS_FIELD = "status";
    private static final String TOTAL_FIELD = "totalStatements";
    private static final String EXECUTED_FIELD = "executedStatements";
    private static final String JDBC_CREATE_STATEMENT = "createStatement";
    private static final String JDBC_EXECUTE = "execute";
    private static final String JDBC_EXECUTE_UPDATE = "executeUpdate";
    private static final String JDBC_EXECUTE_LARGE_UPDATE = "executeLargeUpdate";
    private static final String JDBC_CLOSE = "close";
    private static final String JDBC_IS_CLOSED = "isClosed";
    private static final int NO_CALLS = 0;
    private static final int SINGLE_CALL = 1;
    private static final int TWO_CALLS = 2;
    private static final int THREE_STATEMENTS = 3;
    private static final long WAIT_SECONDS = 5L;

    /**
     * Params: args 未使用。
     * Returns: 无；任一校验失败时抛出断言错误。
     */
    public static void main(String[] args) throws Exception {
        verifyParserHandlesCommentsAndQuotedSemicolons();
        verifyParserRejectsUnsupportedStatements();
        verifyDropColumnSuggestionsAreExecutable();
        verifyUnconfirmedAndStalePlansDoNotLogin();
        verifyTargetMismatchDoesNotLogin();
        verifyDescriptionAndWrongTaskStateDoNotLogin();
        verifySuccessfulExecutionAndCredentialLifetime();
        verifyLoginFailureStopsWithoutRetry();
        verifyPartialFailureStopsWithoutRetry();
        verifyConcurrentExecutionAndRemovalAreRejected();
        verifyArchiveUsesItsOriginalDatabase();
        System.out.println("[RESULT] STRUCTURE_SYNC_EXECUTION_CHECK_PASSED");
    }

    /**
     * Params: 无。
     * Returns: 无；注释和引用内部的分号不得错误拆分为新的 SQL。
     */
    private static void verifyParserHandlesCommentsAndQuotedSemicolons() {
        String sql = "-- suggestion; DROP TABLE business;\r\n"
            + "/* explanation ; SELECT 1; */\n"
            + "CREATE TABLE `" + TARGET_ONE + "` (`semi;colon` VARCHAR(30) DEFAULT 'a'';--b/*c*/');\n"
            + "ALTER TABLE `" + TARGET_TWO + "` ADD COLUMN `quote``;name` VARCHAR(20) NULL;\n"
            + "ALTER TABLE \"" + TARGET_THREE + "\" MODIFY (\"text;name\" VARCHAR2(40));\n"
            + "-- no more SQL;";
        List<String> statements = StructureSyncScript.parseStatements(sql);
        assertEquals(THREE_STATEMENTS, statements.size(), "引用或注释中的分号被当成分隔符");
        assertContains(statements.get(NO_CALLS), "DEFAULT 'a'';--b/*c*/'", "字符串字面量或单引号转义被修改");
        assertContains(statements.get(SINGLE_CALL), "`quote``;name`", "反引号转义内容被修改");
        assertContains(statements.get(TWO_CALLS), "\"text;name\"", "双引号标识符内容被修改");
        assertEquals(NO_CALLS, StructureSyncScript.parseStatements("-- manual only;\n/* manual */").size(),
            "纯说明脚本不应生成执行语句");
        assertEquals(StructureSyncScript.digest(SCRIPT_ONE), StructureSyncScript.digest(SCRIPT_ONE),
            "同一脚本摘要不稳定");
        assertTrue(!StructureSyncScript.digest(SCRIPT_ONE).equals(StructureSyncScript.digest(SCRIPT_TWO)),
            "不同脚本未产生不同摘要");
    }

    /**
     * Params: 无。
     * Returns: 无；确认接口只能执行结构建议允许的 SQL。
     */
    private static void verifyParserRejectsUnsupportedStatements() throws Exception {
        for (String script : Arrays.asList(
            "DROP TABLE " + TARGET_ONE + ";",
            "DELETE FROM " + TARGET_ONE + ";",
            "INSERT INTO " + TARGET_ONE + " VALUES (1);",
            "UPDATE " + TARGET_ONE + " SET A=1;",
            "ALTER TABLE " + TARGET_ONE + " DROP CONSTRAINT SOME_CONSTRAINT;",
            "ALTER TABLE " + TARGET_ONE + " DROP PRIMARY KEY;",
            "ALTER TABLE " + TARGET_ONE + " DROP COLUMN A, DROP COLUMN B;",
            "ALTER TABLE " + TARGET_ONE + " DROP COLUMN A CASCADE CONSTRAINTS;",
            "ALTER TABLE " + TARGET_ONE + " RENAME TO renamed;",
            SCRIPT_ONE + "\nDROP TABLE " + TARGET_TWO + ";",
            "CREATE TABLE `unterminated (A INT);",
            "/* unterminated comment",
            "ALTER TABLE " + TARGET_ONE + " ADD COLUMN A VARCHAR(20) DEFAULT 'unterminated;")) {
            expectRejected(() -> StructureSyncScript.parseStatements(script), "不允许的脚本未被拒绝：" + script);
        }
    }

    /** Params: 无。 Returns: 无；用户确认后的单列删除建议执行，其他删除操作仍拒绝。 */
    private static void verifyDropColumnSuggestionsAreExecutable() throws Exception {
        String script = "ALTER TABLE `" + TARGET_ONE + "` DROP COLUMN `EXTRA`;";
        Fixture fixture = new Fixture();
        MigrationTask task = failedTask(script, false);
        new StructureSyncService(fixture::open).execute(task, request(task, targetConfig()));
        assertEquals(SUCCEEDED, execution(task).get(STATUS_FIELD), "目标额外字段删除建议未执行");
        assertEquals(SINGLE_CALL, fixture.statements.size(), "单列删除建议执行次数不正确");
        for (String statement : Arrays.asList("ALTER TABLE \"" + TARGET_ONE + "\" DROP COLUMN \"EXTRA\";",
            "ALTER TABLE `" + TARGET_ONE + "` DROP COLUMN `extra;part`;")) {
            assertEquals(SINGLE_CALL, StructureSyncScript.parseStatements(statement).size(),
                "方言引用或字段名内分号影响单列删除建议解析");
        }
    }

    /**
     * Params: 无。
     * Returns: 无；未确认或过期摘要不会消耗执行资格或登录数据库。
     */
    private static void verifyUnconfirmedAndStalePlansDoNotLogin() throws Exception {
        Fixture fixture = new Fixture();
        MigrationTask task = failedTask(SCRIPT_ONE, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        StructureSyncRequest unconfirmed = request(task, targetConfig());
        unconfirmed.setConfirmed(Boolean.FALSE);
        expectRejected(() -> service.execute(task, unconfirmed), "未二次确认仍执行结构脚本");
        StructureSyncRequest stale = request(task, targetConfig());
        stale.setScriptDigest(StructureSyncScript.digest(SCRIPT_TWO));
        expectRejected(() -> service.execute(task, stale), "过期脚本摘要仍执行结构脚本");
        assertEquals(NO_CALLS, fixture.openCalls.get(), "无效确认在校验前登录了数据库");
        assertEquals(READY, execution(task).get(STATUS_FIELD), "无效确认消耗了执行资格");
    }

    /**
     * Params: 无。
     * Returns: 无；用户当前表单必须对应原任务目标数据库。
     */
    private static void verifyTargetMismatchDoesNotLogin() throws Exception {
        Fixture fixture = new Fixture();
        MigrationTask task = failedTask(SCRIPT_ONE, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        DatabaseConfig wrongHost = targetConfig();
        wrongHost.setHost(SOURCE_HOST);
        expectRejected(() -> service.execute(task, request(task, wrongHost)), "源库被误当作迁移目标库执行");
        DatabaseConfig wrongDatabase = targetConfig();
        wrongDatabase.setDatabase(DATABASE + "_other");
        expectRejected(() -> service.execute(task, request(task, wrongDatabase)), "不同数据库仍执行结构脚本");
        DatabaseConfig wrongUser = targetConfig();
        wrongUser.setUsername(USERNAME + "_other");
        expectRejected(() -> service.execute(task, request(task, wrongUser)), "不同数据库账号仍执行结构脚本");
        assertEquals(NO_CALLS, fixture.openCalls.get(), "不匹配目标在拒绝前登录了数据库");
    }

    /**
     * Params: 无。
     * Returns: 无；纯说明脚本和未失败的任务不能触发数据库登录。
     */
    private static void verifyDescriptionAndWrongTaskStateDoNotLogin() throws Exception {
        Fixture fixture = new Fixture();
        StructureSyncService service = new StructureSyncService(fixture::open);
        MigrationTask manual = failedTask("-- 需要人工核对约束，不生成DDL。\n", false);
        expectRejected(() -> service.execute(manual, request(manual, targetConfig())), "纯说明脚本仍登录执行");
        MigrationTask unsupported = failedTask(SCRIPT_ONE + "\nDROP TABLE " + TARGET_TWO + ";", false);
        expectRejected(() -> service.execute(unsupported, request(unsupported, targetConfig())),
            "含不支持后续语句的完整脚本未在登录前拒绝");
        MigrationTask created = newTask(false);
        created.setStructureSyncScript(SCRIPT_ONE);
        expectRejected(() -> service.execute(created, request(created, targetConfig())), "待启动任务允许执行结构脚本");
        MigrationTask succeeded = newTask(false);
        succeeded.setStructureSyncScript(SCRIPT_ONE);
        succeeded.markSucceeded();
        expectRejected(() -> service.execute(succeeded, request(succeeded, targetConfig())), "业务成功任务仍允许执行脚本");
        assertEquals(NO_CALLS, fixture.openCalls.get(), "不可执行脚本或任务状态仍触发登录");
    }

    /**
     * Params: 无。
     * Returns: 无；成功逐条执行、保存结果且不保留重新提交的密码。
     */
    private static void verifySuccessfulExecutionAndCredentialLifetime() throws Exception {
        Fixture fixture = new Fixture();
        MigrationTask task = failedTask(SCRIPT_ONE + "\n" + SCRIPT_TWO, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        StructureSyncRequest execute = request(task, targetConfig());
        Map<String, Object> result = service.execute(task, execute);
        assertEquals(SINGLE_CALL, fixture.openCalls.get(), "同一执行没有只登录一次");
        assertEquals(TWO_CALLS, fixture.statements.size(), "未逐条执行完整脚本");
        assertEquals(SUCCEEDED, execution(task).get(STATUS_FIELD), "成功结果状态未保存");
        assertEquals(TWO_CALLS, ((Number)execution(task).get(TOTAL_FIELD)).intValue(), "总语句数错误");
        assertEquals(TWO_CALLS, ((Number)execution(task).get(EXECUTED_FIELD)).intValue(), "成功语句数错误");
        assertEquals(TaskStatus.FAILED, task.getStatus(), "执行结构脚本不应重启或改写原迁移状态");
        assertTrue(execute.getDatabase().getPassword() == null, "执行后重新提交的密码未清除");
        assertTrue(task.getRequest().getTarget().getPassword() == null, "原任务密码被重新保存");
        assertTrue(!result.toString().contains(TEST_SECRET), "接口详情或日志泄露了密码");
        assertEquals(SINGLE_CALL, fixture.closeCalls.get(), "执行结束后未关闭唯一连接");
        expectRejected(() -> service.execute(task, request(task, targetConfig())), "已成功脚本允许重复执行");
        assertEquals(SINGLE_CALL, fixture.openCalls.get(), "重复执行被拒绝前再次登录数据库");
    }

    /**
     * Params: 无。
     * Returns: 无；登录异常只尝试一次，保留失败结果且禁止自动重新执行。
     */
    private static void verifyLoginFailureStopsWithoutRetry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.loginFailure = new SQLException("synthetic access denied", "28000");
        MigrationTask task = failedTask(SCRIPT_ONE, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        StructureSyncRequest execute = request(task, targetConfig());
        service.execute(task, execute);
        assertEquals(SINGLE_CALL, fixture.openCalls.get(), "登录失败后发生重试");
        assertEquals(NO_CALLS, fixture.statements.size(), "登录失败后仍执行DDL");
        assertEquals(FAILED, execution(task).get(STATUS_FIELD), "登录失败结果未保存");
        assertEquals(NO_CALLS, ((Number)execution(task).get(EXECUTED_FIELD)).intValue(), "登录失败成功数非零");
        assertTrue(execute.getDatabase().getPassword() == null, "登录失败后密码未清除");
        expectRejected(() -> service.execute(task, request(task, targetConfig())), "登录失败后同一计划允许重放");
        assertEquals(SINGLE_CALL, fixture.openCalls.get(), "登录失败结果查询触发了重新登录");
    }

    /**
     * Params: 无。
     * Returns: 无；中途失败立即停止，前面的DDL仍按已执行计数显示。
     */
    private static void verifyPartialFailureStopsWithoutRetry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failOnStatement = TWO_CALLS;
        MigrationTask task = failedTask(SCRIPT_ONE + "\n" + SCRIPT_TWO + "\n" + SCRIPT_THREE, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        service.execute(task, request(task, targetConfig()));
        assertEquals(TWO_CALLS, fixture.statements.size(), "第二条失败后仍继续第三条或重试失败语句");
        assertEquals(SINGLE_CALL, fixture.openCalls.get(), "DDL失败触发重新登录");
        assertEquals(FAILED, execution(task).get(STATUS_FIELD), "部分失败状态未保存");
        assertEquals(THREE_STATEMENTS, ((Number)execution(task).get(TOTAL_FIELD)).intValue(), "部分失败丢失计划总数");
        assertEquals(SINGLE_CALL, ((Number)execution(task).get(EXECUTED_FIELD)).intValue(), "已生效DDL计数不正确");
        assertContains(String.valueOf(execution(task).get("message")), "已", "失败没有说明已有执行结果");
        assertTrue(!task.snapshot(true, 0L).toString().contains(TEST_SECRET), "失败详情泄露了密码");
        expectRejected(() -> service.execute(task, request(task, targetConfig())), "部分失败计划允许从头重放");
        assertEquals(TWO_CALLS, fixture.statements.size(), "重放拒绝时仍执行了SQL");
    }

    /**
     * Params: 无。
     * Returns: 无；运行中任务不能再次执行、清除或被容量淘汰。
     */
    private static void verifyConcurrentExecutionAndRemovalAreRejected() throws Exception {
        Fixture fixture = new Fixture();
        fixture.pauseFirstStatement = true;
        MigrationTask task = failedTask(SCRIPT_ONE, false);
        StructureSyncService service = new StructureSyncService(fixture::open);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                service.execute(task, request(task, targetConfig()));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        }, "structure-sync-concurrency-check");
        TaskManager manager = new TaskManager(new MigrationEngine(), new ArchiveEngine(), null);
        Map<String, MigrationTask> tasks = tasks(manager);
        tasks.put(task.getId(), task);
        worker.start();
        try {
            assertTrue(fixture.statementEntered.await(WAIT_SECONDS, TimeUnit.SECONDS), "执行线程未进入阻塞DDL");
            assertEquals(RUNNING, execution(task).get(STATUS_FIELD), "执行中轮询状态不正确");
            expectRejected(() -> service.execute(task, request(task, targetConfig())), "执行中允许并发重放同脚本");
            expectRejected(() -> manager.delete(task.getId()), "执行中任务被清除");
            assertTrue(tasks.containsKey(task.getId()), "拒绝清除后任务仍被移除");
            for (int i = SINGLE_CALL; i < MigrationConstants.MAX_TASKS; i++) {
                tasks.put("created-" + i, newTask(false));
            }
            Method evict = TaskManager.class.getDeclaredMethod("removeOldTerminalTasksIfNecessary");
            evict.setAccessible(true);
            evict.invoke(manager);
            assertTrue(tasks.containsKey(task.getId()), "执行中的终态业务任务被容量淘汰");
            assertEquals(SINGLE_CALL, fixture.openCalls.get(), "并发请求触发额外数据库登录");
        } finally {
            fixture.statementRelease.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            manager.destroy();
        }
        assertTrue(!worker.isAlive(), "执行线程未结束");
        if (failure.get() != null) {
            throw new AssertionError("后台执行异常", failure.get());
        }
        assertEquals(SUCCEEDED, execution(task).get(STATUS_FIELD), "并发拒绝影响原执行结果");
    }

    /**
     * Params: 无。
     * Returns: 无；归档只在原单库连接上执行建议，不能接受其他迁移目标配置。
     */
    private static void verifyArchiveUsesItsOriginalDatabase() throws Exception {
        Fixture fixture = new Fixture();
        MigrationTask task = failedTask(SCRIPT_ONE, true);
        DatabaseConfig sameArchiveDatabase = sourceConfig();
        StructureSyncService service = new StructureSyncService(fixture::open);
        service.execute(task, request(task, sameArchiveDatabase));
        assertEquals(SOURCE_HOST, fixture.openedHost, "归档建议未使用原归档连接");
        assertEquals(SUCCEEDED, execution(task).get(STATUS_FIELD), "归档建议执行失败");
    }

    /** Params: manager 被测任务管理器。 Returns: 仅供回归登记任务的内存映射。 */
    @SuppressWarnings("unchecked")
    private static Map<String, MigrationTask> tasks(TaskManager manager) throws Exception {
        Field field = TaskManager.class.getDeclaredField("tasks");
        field.setAccessible(true);
        return (Map<String, MigrationTask>)field.get(manager);
    }

    /** Params: task 被测任务。 Returns: 任务详情中的结构执行结果。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> execution(MigrationTask task) {
        return (Map<String, Object>)task.snapshot(true, 0L).get(EXECUTION_FIELD);
    }

    /** Params: task 被测任务；database 此次显式传入的凭据。 Returns: 二次确认请求。 */
    private static StructureSyncRequest request(MigrationTask task, DatabaseConfig database) {
        StructureSyncRequest request = new StructureSyncRequest();
        request.setConfirmed(Boolean.TRUE);
        request.setScriptDigest(task.getStructureSyncDigest());
        request.setDatabase(database);
        return request;
    }

    /** Params: script 服务器保存的脚本；archive 是否归档。 Returns: 已完成预检失败且密码已清空的任务。 */
    private static MigrationTask failedTask(String script, boolean archive) {
        MigrationTask task = newTask(archive);
        task.setStructureSyncScript(script);
        task.markFailed("synthetic structure validation failure");
        task.clearPasswords();
        return task;
    }

    /** Params: archive 是否归档。 Returns: 仅含合成测试配置的任务，无网络操作。 */
    private static MigrationTask newTask(boolean archive) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("结构建议执行离线回归");
        request.setTaskType(archive ? MigrationConstants.TASK_TYPE_ARCHIVE : MigrationConstants.TASK_TYPE_MIGRATION);
        request.setBatchSize(MigrationConstants.DEFAULT_BATCH_SIZE);
        request.setSource(sourceConfig());
        request.setTarget(archive ? request.getSource() : targetConfig());
        List<MigrationStepRequest> steps = new ArrayList<>();
        for (String target : Arrays.asList(TARGET_ONE, TARGET_TWO, TARGET_THREE)) {
            MigrationStepRequest step = new MigrationStepRequest();
            step.setName(target);
            step.setSourceSql("SELECT A FROM SOURCE_FIXTURE");
            step.setTargetTable(target);
            steps.add(step);
        }
        request.setSteps(steps);
        if (archive) {
            ArchiveRequest archiveRequest = new ArchiveRequest();
            ArchiveTableRequest main = new ArchiveTableRequest();
            main.setName("archive-main");
            main.setNodeId("MAIN_1");
            main.setSourceTable("SOURCE_FIXTURE");
            main.setTargetTable(TARGET_ONE);
            main.setSourceSql("SELECT A FROM SOURCE_FIXTURE");
            archiveRequest.setMainTables(Collections.singletonList(main));
            archiveRequest.setChildTables(Collections.emptyList());
            request.setArchive(archiveRequest);
        }
        return new MigrationTask("sync-execution-fixture-" + System.nanoTime(), request);
    }

    /** Params: 无。 Returns: 合成目标数据库配置，不会用于真实登录。 */
    private static DatabaseConfig targetConfig() {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(MigrationConstants.DB_MYSQL);
        config.setHost(TARGET_HOST);
        config.setPort(MigrationConstants.DEFAULT_MYSQL_PORT);
        config.setDatabase(DATABASE);
        config.setUsername(USERNAME);
        config.setPassword(TEST_SECRET);
        return config;
    }

    /** Params: 无。 Returns: 与目标不同的合成源连接。 */
    private static DatabaseConfig sourceConfig() {
        DatabaseConfig config = targetConfig();
        config.setHost(SOURCE_HOST);
        return config;
    }

    /** Params: action 应被拒绝的调用；message 失败说明。 Returns: 无。 */
    private static void expectRejected(CheckedRunnable action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    /** Params: expected/actual 预期和实际值；message 失败说明。 Returns: 无。 */
    private static void assertEquals(Object expected, Object actual, String message) {
        assertTrue(expected == null ? actual == null : expected.equals(actual),
            message + "，expected=" + expected + "，actual=" + actual);
    }

    /** Params: value 说明；fragment 必须包含的内容；message 失败说明。 Returns: 无。 */
    private static void assertContains(String value, String fragment, String message) {
        assertTrue(value != null && value.contains(fragment), message + "：" + value);
    }

    /** Params: condition 条件；message 失败说明。 Returns: 无。 */
    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Author: Codex. date: 2026-10-26. */
    private interface CheckedRunnable {
        /** Params: 无。 Returns: 无。 */
        void run() throws Exception;
    }

    /**
     * 统计登录与DDL调用，并模拟SQL失败及并发执行时间窗。
     * Author: Codex
     * date: 2026-10-26
     */
    private static final class Fixture {
        private final AtomicInteger openCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final List<String> statements = new ArrayList<>();
        private final CountDownLatch statementEntered = new CountDownLatch(SINGLE_CALL);
        private final CountDownLatch statementRelease = new CountDownLatch(SINGLE_CALL);
        private SQLException loginFailure;
        private int failOnStatement;
        private boolean pauseFirstStatement;
        private String openedHost;

        /** Params: config 此次确认提供的连接。 Returns: 不连接网络的JDBC代理。 */
        private Connection open(DatabaseConfig config) throws Exception {
            openCalls.incrementAndGet();
            openedHost = config.getHost();
            assertEquals(TEST_SECRET, config.getPassword(), "登录前凭据过早清空");
            if (loginFailure != null) {
                throw loginFailure;
            }
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (JDBC_CREATE_STATEMENT.equals(name)) {
                        return statement();
                    }
                    if (JDBC_CLOSE.equals(name)) {
                        closeCalls.incrementAndGet();
                        return null;
                    }
                    if (JDBC_IS_CLOSED.equals(name)) {
                        return closeCalls.get() > NO_CALLS;
                    }
                    return defaultValue(method.getReturnType());
                });
        }

        /** Params: 无。 Returns: 可统计和注入失败的DDL语句代理。 */
        private Statement statement() {
            return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    boolean execution = JDBC_EXECUTE.equals(name) || JDBC_EXECUTE_UPDATE.equals(name)
                        || JDBC_EXECUTE_LARGE_UPDATE.equals(name);
                    if (execution) {
                        statements.add((String)args[NO_CALLS]);
                        if (pauseFirstStatement && statements.size() == SINGLE_CALL) {
                            statementEntered.countDown();
                            assertTrue(statementRelease.await(WAIT_SECONDS, TimeUnit.SECONDS), "并发测试释放超时");
                        }
                        if (failOnStatement > NO_CALLS && statements.size() == failOnStatement) {
                            throw new SQLException("synthetic second DDL failed", "42000");
                        }
                    }
                    return defaultValue(method.getReturnType());
                });
        }

        /** Params: type 方法返回类型。 Returns: 代理未使用方法的中性返回值。 */
        private Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return Boolean.FALSE;
            }
            if (type == int.class) {
                return NO_CALLS;
            }
            if (type == long.class) {
                return 0L;
            }
            return null;
        }
    }
}
