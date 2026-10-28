package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.StructureSyncRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 通过一次真实登录和隔离表验证确认执行、重复拒绝及 DDL 部分生效状态。
 * Author: Codex
 * date: 2026-10-26
 */
public final class StructureSyncMysqlIntegrationCheck {

    private static final String SQL_CREATE = "CREATE TABLE ";
    private static final String SQL_ALTER = "ALTER TABLE ";
    private static final String SQL_DROP = "DROP TABLE ";
    private static final String STATUS_SUCCESS = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String METHOD_EXECUTE = "execute";
    private static final String METHOD_CLOSE = "close";
    private static final String METHOD_COMMIT = "commit";
    private static final String METHOD_ROLLBACK = "rollback";
    private static final String METHOD_AUTOCOMMIT = "setAutoCommit";
    private static final String DATA_SOURCE_SUFFIX = "S";
    private static final String DATA_TARGET_SUFFIX = "T";
    private static final int FULL_ALIGNMENT_STATEMENTS = 10;
    private final String prefix = "LDM_SE_" + Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
    private final List<String> ownedTables = new ArrayList<>();
    private final List<String> sqlRecord = new ArrayList<>();
    private final List<String> assertions = new ArrayList<>();
    private DatabaseConfig config;
    private Connection connection;
    private int opens;
    private int executedStatements;

    /** Params: args 未使用；密码只从标准输入读取。 Returns: 无。 */
    public static void main(String[] args) throws Exception {
        new StructureSyncMysqlIntegrationCheck().run();
    }

    /** Params: 无。 Returns: 无；真实登录失败立即退出且不重试。 */
    private void run() throws Exception {
        config = config();
        config.setPassword(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine());
        Class.forName("com.mysql.cj.jdbc.Driver");
        Connection physical;
        try {
            physical = DriverManager.getConnection(DatabaseSupport.buildJdbcUrl(config)
                + "&autoReconnect=false", config.getUsername(), config.getPassword());
        } catch (Exception exception) {
            System.out.println("[LOGIN_FAILED] no retry");
            throw exception;
        }
        System.out.println("[LOGIN_SUCCEEDED] single physical connection");
        connection = trace(physical);
        Throwable failure = null;
        try {
            check(count("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE()"
                + " AND TABLE_NAME LIKE '" + prefix + "_%'") == 0L, "隔离对象不得预先存在");
            verifySuccess(false);
            verifySuccess(true);
            verifyFullFieldAlignment(false);
            verifyFullFieldAlignment(true);
            verifyPartialFailure();
            assertions.add("迁移、归档确认执行及部分失败后的重复拒绝全部通过；源业务数据未自动迁移或删除。");
        } catch (Throwable exception) {
            failure = exception;
            throw exception;
        } finally {
            cleanup(physical, failure);
            config.clearPassword();
        }
        System.out.println("[RESULT] STRUCTURE_SYNC_MYSQL_INTEGRATION_CHECK_PASSED");
    }

    /** Params: archive 归档或迁移模式。 Returns: 无；全部真实字段差异均有ALTER，执行后字段定义完全一致。 */
    private void verifyFullFieldAlignment(boolean archive) throws Exception {
        String mode = archive ? "FA" : "FM";
        String source = prefix + "_" + mode + DATA_SOURCE_SUFFIX;
        String target = prefix + "_" + mode + DATA_TARGET_SUFFIX;
        create(source, "ID INT NOT NULL PRIMARY KEY, PROF_TYPE CHAR(1) NULL, TENANT_ID VARCHAR(32) NULL,"
            + " USER_ID VARCHAR(100) NULL, REQUIRED_VALUE VARCHAR(20) NOT NULL, VALUE_TEXT VARCHAR(12) NULL,"
            + " SCORE DECIMAL(8,2) NULL, TYPE_CHANGED VARCHAR(20) NULL, TO_ADD CHAR(2) NOT NULL, MEASURE FLOAT NULL");
        create(target, "ID INT NOT NULL PRIMARY KEY, PROF_TYPE CHAR(1) NOT NULL, TENANT_ID VARCHAR(32) NOT NULL,"
            + " USER_ID VARCHAR(100) NOT NULL, REQUIRED_VALUE VARCHAR(20) NULL, VALUE_TEXT VARCHAR(60) NULL,"
            + " SCORE DECIMAL(12,4) NULL, TYPE_CHANGED INT NULL, EXTRA_COLUMN VARCHAR(10) NULL, MEASURE DOUBLE NULL");
        update("INSERT INTO " + source + " VALUES (1,'A','tenant','user','required','short',12.30,'123','X',1.25)");
        MigrationTask task = failedTask(source, target, archive);
        List<String> statements = StructureSyncScript.parseStatements(task.getStructureSyncScript());
        check(statements.size() == FULL_ALIGNMENT_STATEMENTS, "十项真实字段差异应对应十条ALTER");
        for (String field : Arrays.asList("PROF_TYPE", "TENANT_ID", "USER_ID", "REQUIRED_VALUE",
            "VALUE_TEXT", "SCORE", "TYPE_CHANGED", "TO_ADD", "EXTRA_COLUMN", "MEASURE")) {
            check(statements.stream().anyMatch(sql -> sql.startsWith(SQL_ALTER) && sql.contains("`" + field + "`")),
                "每一项字段差异都有可执行ALTER：" + field);
        }
        check(statements.stream().anyMatch(sql -> sql.contains("DROP COLUMN `EXTRA_COLUMN`")),
            "目标多余字段有直接DROP COLUMN");
        service().execute(task, syncRequest(task));
        check(STATUS_SUCCESS.equals(execution(task).get("status")), "完整字段差异脚本执行成功");
        check(number(execution(task).get("executedStatements")) == FULL_ALIGNMENT_STATEMENTS,
            "完整字段差异脚本执行条数正确");
        check(columnDefinitions(source).equals(columnDefinitions(target)),
            "执行后目标字段集合、原生类型、长度精度和可空性与源完全相同");
        verifyRecheckPasses(task.getRequest(), archive);
        check(count("SELECT COUNT(*) FROM " + source) == 1L
            && count("SELECT COUNT(*) FROM " + target) == 0L, "字段对齐不启动业务数据任务");
        assertions.add((archive ? "归档" : "迁移")
            + "完整对齐：CHAR/VARCHAR纯非空差异、NULL/NOT NULL双向、长度缩小、数字精度缩小、类型变更、"
            + "FLOAT/DOUBLE差异、缺失字段和多余字段共十项均有直接ALTER；十条执行成功，"
            + "源目标字段元数据逐项一致，重新预检通过。");
        System.out.println("[CHECK] " + mode + "_ALL_TEN_FIELD_DIFFERENCES_ALIGNED_PASSED");
    }

    /** Params: table 隔离表名。 Returns: 不含历史数据的字段原生类型和可空性映射。 */
    private Map<String, String> columnDefinitions(String table) throws Exception {
        String sql = "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE FROM information_schema.columns"
            + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='" + table + "' ORDER BY ORDINAL_POSITION";
        Map<String, String> definitions = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                definitions.put(rows.getString(1), rows.getString(2) + ":" + rows.getString(3));
            }
        }
        return definitions;
    }

    /** Params: request 已执行结构同步的任务配置；archive 归档或迁移模式。 Returns: 无；重新预检必须通过。 */
    private void verifyRecheckPasses(CreateTaskRequest request, boolean archive) throws Exception {
        MigrationTask task = new MigrationTask("recheck-" + request.getName(), request);
        Object engine = archive ? new ArchiveEngine() : new MigrationEngine();
        Method prepare = archive ? ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class) : MigrationEngine.class.getDeclaredMethod("prepareAllSteps",
            MigrationTask.class, Connection.class, Connection.class, String.class, String.class);
        prepare.setAccessible(true);
        Object prepared = archive ? invoke(prepare, engine, task, connection, MigrationConstants.DB_MYSQL)
            : invoke(prepare, engine, task, connection, connection, MigrationConstants.DB_MYSQL,
                MigrationConstants.DB_MYSQL);
        check(prepared != null && task.getStructureSyncScript() == null, "全字段对齐后无残余结构问题");
    }

    /** Params: archive 是否归档。 Returns: 无；使用真实引擎预检生成脚本，再走公开执行服务。 */
    private void verifySuccess(boolean archive) throws Exception {
        String suffix = archive ? "A" : "M";
        String source = prefix + "_" + suffix + "S";
        String target = prefix + "_" + suffix + "T";
        create(source, "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(40) NOT NULL, AMOUNT DECIMAL(12,2) NULL");
        create(target, "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(5) NOT NULL");
        update("INSERT INTO " + source + " VALUES (1,'structure-check',12.50)");
        MigrationTask task = failedTask(source, target, archive);
        StructureSyncService service = service();
        StructureSyncRequest notConfirmed = syncRequest(task);
        notConfirmed.setConfirmed(Boolean.FALSE);
        int opensBefore = opens;
        expectRejected(service, task, notConfirmed);
        check(opens == opensBefore, "未确认不得请求数据库连接");
        StructureSyncRequest request = syncRequest(task);
        service.execute(task, request);
        Map<String, Object> execution = execution(task);
        check(STATUS_SUCCESS.equals(execution.get("status")), "结构建议执行成功");
        check(number(execution.get("executedStatements")) == 2L, "两条建议均记录成功");
        check(request.getDatabase().getPassword() == null, "执行后清除请求密码");
        check(count("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()"
            + " AND TABLE_NAME='" + target + "' AND COLUMN_NAME='AMOUNT'") == 1L, "新增字段真实生效");
        check(count("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()"
            + " AND TABLE_NAME='" + target + "' AND COLUMN_NAME='NAME' AND CHARACTER_MAXIMUM_LENGTH=40"
            + " AND IS_NULLABLE='NO'") == 1L, "扩容并保留原非空规则");
        check(count("SELECT COUNT(*) FROM " + source) == 1L
            && count("SELECT COUNT(*) FROM " + target) == 0L, "仅结构变更，未自动迁移或归档");
        int statementsBefore = executedStatements;
        opensBefore = opens;
        expectRejected(service, task, syncRequest(task));
        check(opens == opensBefore && executedStatements == statementsBefore, "执行成功后拒绝重复请求");
        check(STATUS_FAILED.equals(task.getStatus().name()), "原预检失败任务状态保持不变");
        assertions.add((archive ? "归档" : "迁移")
            + "：未确认零连接；确认执行两条 DDL 成功；扩容、增列及非空约束正确；重复请求拒绝；未执行数据任务。");
        System.out.println("[CHECK] " + suffix + "_CONFIRMED_EXECUTION_AND_DUPLICATE_REJECTION_PASSED");
    }

    /** Params: 无。 Returns: 无；第二条失败时第一条应真实生效，并禁止重新执行整个旧脚本。 */
    private void verifyPartialFailure() throws Exception {
        String source = prefix + "_PS";
        String target = prefix + "_PT";
        create(source, "ID INT NOT NULL PRIMARY KEY, A VARCHAR(20) NULL, B VARCHAR(30) NULL");
        create(target, "ID INT NOT NULL PRIMARY KEY");
        update("INSERT INTO " + source + " VALUES (1,'a','b')");
        MigrationTask task = failedTask(source, target, false);
        update(SQL_ALTER + target + " ADD COLUMN B VARCHAR(30) NULL");
        StructureSyncService service = service();
        service.execute(task, syncRequest(task));
        Map<String, Object> execution = execution(task);
        check(STATUS_FAILED.equals(execution.get("status")), "第二条DDL冲突必须记录失败");
        check(number(execution.get("executedStatements")) == 1L, "保留第一条成功计数");
        check(number(execution.get("totalStatements")) == 2L, "记录原计划总数");
        check(count("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()"
            + " AND TABLE_NAME='" + target + "' AND COLUMN_NAME='A'") == 1L, "第一条DDL已真实生效");
        int before = executedStatements;
        int opensBefore = opens;
        expectRejected(service, task, syncRequest(task));
        check(opensBefore == opens && before == executedStatements, "部分失败后不重连、不重放");
        check(count("SELECT COUNT(*) FROM " + source) == 1L, "部分失败未修改源数据");
        assertions.add("部分失败：人为在预检后补入第二个字段，执行第1条成功、第2条冲突失败；记录1/2，保留已生效DDL，重复请求零连接零执行。");
        System.out.println("[CHECK] PARTIAL_DDL_FAILURE_AND_NO_RETRY_PASSED");
    }

    /** Params: source/target 本轮表名；archive 模式。 Returns: 通过真实生产预检失败的任务。 */
    private MigrationTask failedTask(String source, String target, boolean archive) throws Exception {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("结构脚本确认执行实测");
        request.setTaskType(archive ? MigrationConstants.TASK_TYPE_ARCHIVE : MigrationConstants.TASK_TYPE_MIGRATION);
        request.setSource(copyConfig());
        request.setTarget(copyConfig());
        request.setBatchSize(2);
        request.setSteps(Collections.emptyList());
        if (archive) {
            ArchiveTableRequest table = new ArchiveTableRequest();
            table.setName("隔离主表");
            table.setNodeId("MAIN_1");
            table.setSourceTable(source);
            table.setTargetTable(target);
            table.setSourceSql("SELECT * FROM " + source);
            ArchiveRequest rules = new ArchiveRequest();
            rules.setConfirmSourceDelete(Boolean.TRUE);
            rules.setMainTables(Collections.singletonList(table));
            rules.setChildTables(Collections.emptyList());
            request.setArchive(rules);
        } else {
            MigrationStepRequest step = new MigrationStepRequest();
            step.setName("隔离迁移");
            step.setSourceSql("SELECT * FROM " + source);
            step.setTargetTable(target);
            step.setInsertMode(MigrationConstants.INSERT_STRICT);
            request.setSteps(Collections.singletonList(step));
        }
        MigrationTask task = new MigrationTask("sync-live-" + source, request);
        task.markStarted();
        Object engine = archive ? new ArchiveEngine() : new MigrationEngine();
        Method prepare = archive ? ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class) : MigrationEngine.class.getDeclaredMethod("prepareAllSteps",
            MigrationTask.class, Connection.class, Connection.class, String.class, String.class);
        prepare.setAccessible(true);
        boolean failed = false;
        try {
            if (archive) {
                invoke(prepare, engine, task, connection, MigrationConstants.DB_MYSQL);
            } else {
                invoke(prepare, engine, task, connection, connection, MigrationConstants.DB_MYSQL,
                    MigrationConstants.DB_MYSQL);
            }
        } catch (IllegalArgumentException expected) {
            failed = true;
            task.markFailed("隔离测试结构预检失败：" + expected.getMessage());
        }
        check(failed && task.getStructureSyncScript() != null, "预检必须汇总差异并生成建议");
        task.clearPasswords();
        return task;
    }

    /** Params: 无。 Returns: 走完整公开校验路径但复用唯一真实连接的服务。 */
    private StructureSyncService service() {
        return new StructureSyncService(database -> {
            opens++;
            sqlRecord.add("-- StructureSyncService connection claim " + opens + " (reused physical test connection)");
            return connection;
        });
    }

    /** Params: task 目标任务。 Returns: 已确认且摘要一致的临时请求。 */
    private StructureSyncRequest syncRequest(MigrationTask task) {
        StructureSyncRequest request = new StructureSyncRequest();
        request.setConfirmed(Boolean.TRUE);
        request.setScriptDigest(task.getStructureSyncDigest());
        request.setDatabase(copyConfig());
        return request;
    }

    /** Params: service/task/request 执行入口参数。 Returns: 无；必须拒绝且不执行。 */
    private void expectRejected(StructureSyncService service, MigrationTask task, StructureSyncRequest request)
        throws Exception {
        boolean rejected = false;
        try {
            service.execute(task, request);
        } catch (IllegalArgumentException | IllegalStateException expected) {
            rejected = true;
        }
        check(rejected, "未确认或重复执行必须拒绝");
    }

    /** Params: task 目标任务。 Returns: 当前独立执行状态。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> execution(MigrationTask task) {
        return (Map<String, Object>)task.snapshot(true, 0L).get("structureSyncExecution");
    }

    /** Params: value 返回计数。 Returns: long 数值。 */
    private long number(Object value) {
        return ((Number)value).longValue();
    }

    /** Params: 无。 Returns: 不含密码的环境连接信息。 */
    private DatabaseConfig config() {
        DatabaseConfig database = new DatabaseConfig();
        database.setType(MigrationConstants.DB_MYSQL);
        database.setHost(requiredEnv("LDM_TEST_MYSQL_HOST"));
        database.setPort(Integer.valueOf(requiredEnv("LDM_TEST_MYSQL_PORT")));
        database.setDatabase(requiredEnv("LDM_TEST_MYSQL_DATABASE"));
        database.setUsername(requiredEnv("LDM_TEST_MYSQL_USER"));
        return database;
    }

    /** Params: 无。 Returns: 本次请求独立使用的临时连接配置。 */
    private DatabaseConfig copyConfig() {
        DatabaseConfig database = config();
        database.setPassword(config.getPassword());
        return database;
    }

    /** Params: name 隔离表名；columns 字段定义。 Returns: 无；只登记本轮成功创建的表。 */
    private void create(String name, String columns) throws Exception {
        check(name.startsWith(prefix) && name.matches("[A-Z0-9_]+"), "隔离对象名合法");
        update(SQL_CREATE + name + " (" + columns + ") ENGINE=InnoDB");
        ownedTables.add(name);
    }

    /** Params: sql 本轮隔离 SQL。 Returns: 无。 */
    private void update(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** Params: sql 统计 SQL。 Returns: 标量统计。 */
    private long count(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            check(rows.next(), "统计返回结果");
            return rows.getLong(1);
        }
    }

    /** Params: delegate 唯一物理连接。 Returns: 记录 SQL 并将服务关闭操作限定为逻辑关闭的代理。 */
    private Connection trace(Connection delegate) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                String methodName = method.getName();
                if (METHOD_CLOSE.equals(methodName)) {
                    sqlRecord.add("-- JDBC logical close; physical connection remains owned by fixture");
                    return null;
                }
                Object result = invoke(method, delegate, args == null ? new Object[0] : args);
                if (result instanceof Statement) {
                    return traceStatement((Statement)result, args != null && args.length > 0 ? (String)args[0] : null);
                }
                if (result instanceof DatabaseMetaData) {
                    DatabaseMetaData metadata = (DatabaseMetaData)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (metaProxy, metaMethod, metaArgs) -> {
                            sqlRecord.add("-- JDBC metadata " + metaMethod.getName() + " " + Arrays.toString(metaArgs));
                            return invoke(metaMethod, metadata, metaArgs == null ? new Object[0] : metaArgs);
                        });
                }
                if (METHOD_COMMIT.equals(methodName) || METHOD_ROLLBACK.equals(methodName)
                    || METHOD_AUTOCOMMIT.equals(methodName)) {
                    sqlRecord.add("-- JDBC " + methodName + " " + Arrays.toString(args));
                }
                return result;
            });
    }

    /** Params: delegate JDBC 语句；sql 预编译 SQL。 Returns: 记录所有实际执行 SQL 的代理。 */
    private Statement traceStatement(Statement delegate, String sql) {
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if (method.getName().startsWith(METHOD_EXECUTE)) {
                    String actual = sql == null ? (String)args[0] : sql;
                    sqlRecord.add(actual + ";");
                    executedStatements++;
                }
                return invoke(method, delegate, args == null ? new Object[0] : args);
            });
    }

    /** Params: method、target、args 为代理调用。 Returns: 解包后的执行结果。 */
    private Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw (Error)cause;
        }
    }

    /** Params: physical 管理连接；original 原异常。 Returns: 无；逐表清理，保留原错误和清理异常。 */
    private void cleanup(Connection physical, Throwable original) throws Exception {
        Throwable failure = null;
        List<String> cleanup = new ArrayList<>(ownedTables);
        Collections.reverse(cleanup);
        for (String table : cleanup) {
            try {
                update(SQL_DROP + table);
            } catch (Throwable exception) {
                failure = accumulate(failure, exception);
            }
        }
        try {
            check(count("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE()"
                + " AND TABLE_NAME LIKE '" + prefix + "_%'") == 0L, "隔离表全部清理");
            assertions.add("隔离表残留数量为 0。");
            System.out.println("[CLEANUP] remaining owned tables=0");
        } catch (Throwable exception) {
            failure = accumulate(failure, exception);
        }
        try {
            StringBuilder record = new StringBuilder("# 结构脚本确认执行 MySQL SQL 记录\n\n")
                .append("日期：").append(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")))
                .append("。单次真实登录，关闭自动重连；前缀：`").append(prefix)
                .append("`。服务的连接开器注入唯一真实连接的记录代理，公开校验/执行路径不替换。\n\n")
                .append("记录全部应用侧 SQL 和元数据 API；驱动内部登录初始化与元数据内部 SQL 无法获得原文。")
                .append("本轮预检和DDL不使用绑定参数，不记录连接凭据。\n\n");
            for (String result : assertions) {
                record.append("- ").append(result).append('\n');
            }
            record.append("\n```sql\n");
            for (int i = 0; i < sqlRecord.size(); i++) {
                record.append("-- operation ").append(i + 1).append('\n').append(sqlRecord.get(i)).append('\n');
            }
            record.append("```\n");
            Files.write(Paths.get(requiredEnv("LDM_TEST_SQL_RECORD")), record.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable exception) {
            failure = accumulate(failure, exception);
        } finally {
            physical.close();
        }
        if (failure != null) {
            if (original != null) {
                original.addSuppressed(failure);
            } else if (failure instanceof Exception) {
                throw (Exception)failure;
            } else {
                throw (Error)failure;
            }
        }
    }

    /** Params: previous/next 累积异常。 Returns: 首个异常，后续加入抑制异常。 */
    private Throwable accumulate(Throwable previous, Throwable next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    /** Params: key 环境变量名。 Returns: 必填值。 */
    private String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + key);
        }
        return value;
    }

    /** Params: condition 断言；message 原因。 Returns: 无。 */
    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
