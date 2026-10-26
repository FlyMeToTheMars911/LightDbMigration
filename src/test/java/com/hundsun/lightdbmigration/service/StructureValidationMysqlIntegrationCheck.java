package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.support.MigrationConstants;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用单次登录和隔离表验证迁移、归档的全量结构预检及修复建议。
 * Author: Codex
 * date: 2026-10-26
 */
public final class StructureValidationMysqlIntegrationCheck {

    private static final String EXPECTED_FAILURE = "校验";
    private static final String TABLE_PREFIX = "LDM_SV_";
    private static final String IDENTIFIER_PATTERN = "[A-Z0-9_]+";
    private static final String SQL_COMMENT_PREFIX = "--";
    private static final String SQL_ALTER_PREFIX = "ALTER TABLE";
    private static final String SQL_CREATE_OPERATION = "CREATE";
    private static final String NAME_IDENTIFIER = "`NAME`";
    private static final String JDBC_COMMIT = "commit";
    private static final String JDBC_ROLLBACK = "rollback";
    private static final String JDBC_AUTO_COMMIT = "setAutoCommit";
    private static final String JDBC_SET_PREFIX = "set";
    private static final String JDBC_SET_NULL = "setNull";
    private static final String JDBC_ADD_BATCH = "addBatch";
    private static final String JDBC_EXECUTE_PREFIX = "execute";
    private static final String JDBC_EXECUTE_BATCH = "executeBatch";
    private static final String JDBC_CLEAR_BATCH = "clearBatch";
    private static final int BIND_ARGUMENT_COUNT = 2;
    private static final int DDL_OPERATION_GROUP = 1;
    private static final int DDL_TABLE_GROUP = 2;
    private static final List<String> DATA_MUTATION_PREFIXES = Arrays.asList("INSERT", "DELETE", "UPDATE", "MERGE");
    private final String prefix = TABLE_PREFIX + Long.toHexString(System.currentTimeMillis()).toUpperCase(Locale.ROOT);
    private final Set<String> ownedTables = new LinkedHashSet<>();
    private final List<String> operations = new ArrayList<>();
    private final List<String> assertions = new ArrayList<>();
    private final String sourceOne = prefix + "_S1";
    private final String sourceTwo = prefix + "_S2";
    private final String childOne = prefix + "_C1";
    private final String childTwo = prefix + "_C2";
    private final String targetOne = prefix + "_T1";
    private final String targetTwo = prefix + "_T2";
    private final String childTargetOne = prefix + "_CT1";
    private final String childTargetTwo = prefix + "_CT2";
    private final String migrationOne = prefix + "_M1";
    private final String migrationTwo = prefix + "_M2";
    private Connection connection;
    private int dataMutations;

    /**
     * Params: args 未使用；连接参数从环境读取，密码只从标准输入读取。
     * Returns: 无；失败抛出异常且不会再次尝试登录。
     */
    public static void main(String[] args) throws Exception {
        new StructureValidationMysqlIntegrationCheck().run();
    }

    /**
     * Params: 无。
     * Returns: 无；记录全部应用侧 SQL、元数据 API 和清理结果。
     */
    private void run() throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        String password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        String url = "jdbc:mysql://" + requiredEnv("LDM_TEST_MYSQL_HOST") + ":"
            + requiredEnv("LDM_TEST_MYSQL_PORT") + "/" + requiredEnv("LDM_TEST_MYSQL_DATABASE")
            + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai"
            + "&autoReconnect=false&connectTimeout=10000&socketTimeout=15000";
        Connection physical;
        try {
            physical = DriverManager.getConnection(url, requiredEnv("LDM_TEST_MYSQL_USER"), password);
        } catch (Exception exception) {
            System.out.println("[LOGIN_FAILED] stopped; no retry");
            throw exception;
        }
        System.out.println("[LOGIN_SUCCEEDED] single physical connection");
        password = null;
        connection = traceConnection(physical);
        Throwable failure = null;
        try {
            connection.setAutoCommit(false);
            assertNamesUnused();
            createFixtures();
            verifyArchive();
            verifyMigration();
            assertions.add("迁移和归档跨表汇总、脚本修复、重新预检及实际数据处理全部通过。");
        } catch (Throwable exception) {
            failure = exception;
            throw exception;
        } finally {
            cleanup(physical, failure);
        }
        System.out.println("[RESULT] STRUCTURE_VALIDATION_MYSQL_INTEGRATION_PASSED");
    }

    /** Params: physical 唯一真实连接；original 主流程异常。 Returns: 无；逐表尽力清理并聚合异常。 */
    private void cleanup(Connection physical, Throwable original) throws Exception {
        Throwable cleanupFailure = null;
        try {
            connection.rollback();
        } catch (Throwable exception) {
            cleanupFailure = exception;
        }
        List<String> cleanup = new ArrayList<>(ownedTables);
        Collections.reverse(cleanup);
        for (String table : cleanup) {
            try {
                update("DROP TABLE " + table);
            } catch (Throwable exception) {
                cleanupFailure = accumulate(cleanupFailure, exception);
            }
        }
        try {
            check(count("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE()"
                + " AND TABLE_NAME LIKE '" + prefix + "_%'") == 0L, "本轮隔离表已全部清理");
            assertions.add("清理完成，本轮隔离表残留数量 0。");
            System.out.println("[CLEANUP] owned test tables remaining=0");
        } catch (Throwable exception) {
            cleanupFailure = accumulate(cleanupFailure, exception);
        }
        if (cleanupFailure != null) {
            assertions.add("清理异常：" + cleanupFailure.getClass().getSimpleName());
        }
        try {
            writeRecord();
        } catch (Throwable exception) {
            cleanupFailure = accumulate(cleanupFailure, exception);
        } finally {
            try {
                physical.close();
            } catch (Throwable exception) {
                cleanupFailure = accumulate(cleanupFailure, exception);
            }
        }
        if (cleanupFailure != null) {
            if (original != null) {
                original.addSuppressed(cleanupFailure);
            } else if (cleanupFailure instanceof Exception) {
                throw (Exception)cleanupFailure;
            } else {
                throw (Error)cleanupFailure;
            }
        }
    }

    /** Params: previous 已记录异常；next 新异常。 Returns: 包含全部异常的首个异常。 */
    private Throwable accumulate(Throwable previous, Throwable next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    /**
     * Params: 无。
     * Returns: 无；同名前缀存在任何对象时停止，绝不清理已有对象。
     */
    private void assertNamesUnused() throws Exception {
        check(count("SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE()"
            + " AND TABLE_NAME LIKE '" + prefix + "_%'") == 0L, "本轮隔离对象名未被占用");
    }

    /**
     * Params: 无。
     * Returns: 无；只创建并填充本次隔离表。
     */
    private void createFixtures() throws Exception {
        String mainColumns = "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(40) NOT NULL, AMOUNT DECIMAL(12,2) NULL";
        create(sourceOne, mainColumns);
        create(sourceTwo, mainColumns);
        create(childOne, "ID INT NOT NULL PRIMARY KEY, PARENT_ID INT NULL, NOTE VARCHAR(40) NULL");
        create(childTwo, "ID INT NOT NULL PRIMARY KEY, PARENT_ID INT NULL, NOTE VARCHAR(40) NULL");
        create(targetOne, "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(10) NOT NULL, EXTRA VARCHAR(5) NULL");
        create(childTargetOne, "ID INT NOT NULL PRIMARY KEY, PARENT_ID VARCHAR(10) NULL, NOTE VARCHAR(5) NULL");
        create(childTargetTwo, "ID INT NOT NULL PRIMARY KEY, PARENT_ID INT NULL");
        create(migrationOne, "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(5) NOT NULL");
        create(migrationTwo, "ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(6) NULL");
        update("INSERT INTO " + sourceOne + " VALUES (1,'first-main',12.50)");
        update("INSERT INTO " + sourceTwo + " VALUES (2,'second-main',25.00)");
        update("INSERT INTO " + childOne + " VALUES (11,1,'first-child')");
        update("INSERT INTO " + childTwo + " VALUES (21,11,'second-child')");
        connection.commit();
    }

    /**
     * Params: 无。
     * Returns: 无；校验父表失败后全部子表仍被检查，建议可用于修复隔离对象。
     */
    private void verifyArchive() throws Exception {
        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        ArchiveTableRequest first = table("主表一", "MAIN_1", null, sourceOne, targetOne);
        ArchiveTableRequest second = table("主表二", "MAIN_2", null, sourceTwo, targetTwo);
        ArchiveTableRequest child = table("子表一", "CHILD_1", "MAIN_1", childOne, childTargetOne);
        ArchiveTableRequest grandchild = table("子表二", "CHILD_2", "CHILD_1", childTwo, childTargetTwo);
        archive.setMainTables(Arrays.asList(first, second));
        archive.setChildTables(Arrays.asList(child, grandchild));
        CreateTaskRequest request = request(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setArchive(archive);
        ArchiveEngine engine = new ArchiveEngine();
        Method prepare = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class, Connection.class,
            String.class);
        prepare.setAccessible(true);
        MigrationTask task = new MigrationTask("live-archive-differences", request);
        int before = dataMutations;
        expectValidationFailure(prepare, engine, task, connection, MigrationConstants.DB_MYSQL);
        check(dataMutations == before, "归档结构预检失败时零写入、零删除");
        String script = String.valueOf(task.snapshot(true, 0L).get("structureSyncScript"));
        for (String name : Arrays.asList(targetOne, targetTwo, childTargetOne, childTargetTwo)) {
            check(script.contains(name), "归档同步建议包含 " + name);
        }
        check(script.contains("AMOUNT") && script.contains("EXTRA") && script.contains("NOTE"),
            "归档同时列出缺字段、多字段及子表定义差异");
        check(count("SELECT COUNT(*) FROM " + sourceOne) == 1L
            && count("SELECT COUNT(*) FROM " + childTwo) == 1L, "归档预检失败保留源数据");
        operations.add("-- 人工审核后，仅对本轮隔离测试表执行生成的建议 DDL。额外字段按测试预期手工删除。");
        applySuggestions(script);
        update("ALTER TABLE " + targetOne + " DROP COLUMN EXTRA");
        MigrationTask repaired = new MigrationTask("live-archive-repaired", request);
        Object prepared = invoke(prepare, engine, repaired, connection, MigrationConstants.DB_MYSQL);
        Method execute = ArchiveEngine.class.getDeclaredMethod("archiveAllMainTables", MigrationTask.class,
            Connection.class, prepared.getClass(), int.class, String.class);
        execute.setAccessible(true);
        invoke(execute, engine, repaired, connection, prepared, 2, MigrationConstants.DB_MYSQL);
        for (String table : Arrays.asList(sourceOne, sourceTwo, childOne, childTwo)) {
            check(count("SELECT COUNT(*) FROM " + table) == 0L, "成功归档后源隔离表清零 " + table);
        }
        for (String table : Arrays.asList(targetOne, targetTwo, childTargetOne, childTargetTwo)) {
            check(count("SELECT COUNT(*) FROM " + table) == 1L, "成功归档后归档表记录正确 " + table);
        }
        assertions.add("归档：两张主表和两级子表多处差异一次汇总；失败时零数据变更；建议 DDL 执行后重新预检通过，并完成实际归档。");
        System.out.println("[CHECK] ARCHIVE_ALL_TABLES_SCRIPT_AND_DATA_SAFETY_PASSED");
    }

    /**
     * Params: 无。
     * Returns: 无；使用已归档的隔离数据验证迁移预检与后续写入。
     */
    private void verifyMigration() throws Exception {
        verifyMigrationAliases();
        CreateTaskRequest request = request(MigrationConstants.TASK_TYPE_MIGRATION);
        request.setSteps(Arrays.asList(step("迁移一", targetOne, migrationOne), step("迁移二", targetTwo, migrationTwo)));
        MigrationEngine engine = new MigrationEngine();
        Method prepare = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        prepare.setAccessible(true);
        MigrationTask task = new MigrationTask("live-migration-differences", request);
        int before = dataMutations;
        expectValidationFailure(prepare, engine, task, connection, connection, MigrationConstants.DB_MYSQL,
            MigrationConstants.DB_MYSQL);
        check(before == dataMutations, "迁移结构预检失败时零写入");
        String script = String.valueOf(task.snapshot(true, 0L).get("structureSyncScript"));
        check(script.contains(migrationOne) && script.contains(migrationTwo)
            && script.contains("AMOUNT") && script.contains("NAME"), "迁移脚本包含全部步骤的缺列和扩容建议");
        applySuggestions(script);
        check(count("SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE()"
            + " AND TABLE_NAME='" + migrationOne + "' AND COLUMN_NAME='NAME' AND IS_NULLABLE='NO'") == 1L,
            "迁移扩容建议保留目标原有 NOT NULL");
        MigrationTask repaired = new MigrationTask("live-migration-repaired", request);
        List<?> prepared = (List<?>)invoke(prepare, engine, repaired, connection, connection,
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        for (Object preparedStep : prepared) {
            Method execute = MigrationEngine.class.getDeclaredMethod("migrateByStreaming", MigrationTask.class,
                Connection.class, Connection.class, preparedStep.getClass(), int.class, String.class);
            execute.setAccessible(true);
            invoke(execute, engine, repaired, connection, connection, preparedStep, 2, MigrationConstants.DB_MYSQL);
        }
        check(count("SELECT COUNT(*) FROM " + migrationOne) == 1L
            && count("SELECT COUNT(*) FROM " + migrationTwo) == 1L, "修复后的两步迁移实际写入成功");
        assertions.add("迁移：两步缺列和长度不足一次汇总；失败时零写入；执行建议保留原 NOT NULL，重新预检及两步真实写入通过。");
        System.out.println("[CHECK] MIGRATION_ALL_STEPS_SCRIPT_AND_DATA_SAFETY_PASSED");
    }

    /** Params: 无。 Returns: 无；真实默认库下的表名别名必须共享冲突与去重规则。 */
    private void verifyMigrationAliases() throws Exception {
        MigrationEngine engine = new MigrationEngine();
        Method prepare = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        prepare.setAccessible(true);
        String qualifiedTarget = requiredEnv("LDM_TEST_MYSQL_DATABASE") + "." + migrationOne;
        MigrationStepRequest wider = step("限定表名冲突", targetTwo, qualifiedTarget);
        wider.setSourceSql("SELECT ID, CAST(NAME AS CHAR(80)) AS NAME, AMOUNT FROM " + targetTwo);
        CreateTaskRequest conflictRequest = request(MigrationConstants.TASK_TYPE_MIGRATION);
        conflictRequest.setSteps(Arrays.asList(step("默认库表名", targetOne, migrationOne), wider));
        MigrationTask conflict = new MigrationTask("live-migration-alias-conflict", conflictRequest);
        int before = dataMutations;
        expectValidationFailure(prepare, engine, conflict, connection, connection,
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        String conflictScript = String.valueOf(conflict.snapshot(true, 0L).get("structureSyncScript"));
        check(conflictScript.contains("冲突"), "默认库别名下的不同建议报告冲突");
        for (String line : conflictScript.split("\\R")) {
            check(line.trim().startsWith("--") || !line.contains("`NAME`"),
                "同物理字段冲突时撤销全部可执行修改建议");
        }
        CreateTaskRequest duplicateRequest = request(MigrationConstants.TASK_TYPE_MIGRATION);
        duplicateRequest.setSteps(Arrays.asList(step("未限定表名", targetOne, migrationOne),
            step("显式默认库", targetTwo, qualifiedTarget)));
        MigrationTask duplicate = new MigrationTask("live-migration-alias-duplicate", duplicateRequest);
        expectValidationFailure(prepare, engine, duplicate, connection, connection,
            MigrationConstants.DB_MYSQL, MigrationConstants.DB_MYSQL);
        String duplicateScript = String.valueOf(duplicate.snapshot(true, 0L).get("structureSyncScript"));
        int nameStatements = 0;
        for (String line : duplicateScript.split("\\R")) {
            if (line.trim().startsWith(SQL_ALTER_PREFIX) && line.contains(NAME_IDENTIFIER)) {
                nameStatements++;
            }
        }
        check(nameStatements == 1, "同物理字段经表名别名产生的相同建议仅保留一次");
        check(before == dataMutations, "表名别名冲突及去重预检零写入");
        assertions.add("迁移默认库别名：未限定表名与显式库名指向同表；不同字段建议只注释，相同建议去重。");
    }

    /** Params: script 已人工限定为本轮隔离对象的建议。 Returns: 无。 */
    private void applySuggestions(String script) throws Exception {
        StringBuilder executable = new StringBuilder();
        for (String line : script.split("\\R")) {
            if (!line.trim().startsWith(SQL_COMMENT_PREFIX)) {
                executable.append(line).append('\n');
            }
        }
        for (String statement : executable.toString().split(";")) {
            String sql = statement.trim();
            if (sql.isEmpty()) {
                continue;
            }
            Matcher ddl = Pattern.compile("(?is)^(ALTER|CREATE)\\s+TABLE\\s+`?([A-Z0-9_]+)`?\\s+.*$").matcher(sql);
            check(ddl.matches(), "建议必须是简单表级 ALTER 或 CREATE");
            String target = ddl.group(DDL_TABLE_GROUP);
            check(Arrays.asList(targetOne, targetTwo, childTargetOne, childTargetTwo,
                migrationOne, migrationTwo).contains(target), "建议目标必须属于本轮隔离目标表白名单");
            update(sql);
            if (SQL_CREATE_OPERATION.equalsIgnoreCase(ddl.group(DDL_OPERATION_GROUP))) {
                ownedTables.add(target);
            }
        }
    }

    /** Params: name、node、parent、source、target 为隔离归档表配置。 Returns: 归档表请求。 */
    private ArchiveTableRequest table(String name, String node, String parent, String source, String target) {
        ArchiveTableRequest table = new ArchiveTableRequest();
        table.setName(name);
        table.setNodeId(node);
        table.setParentNodeId(parent);
        table.setSourceTable(source);
        table.setTargetTable(target);
        table.setSourceSql("SELECT * FROM " + source);
        if (parent != null) {
            ArchiveRelationRequest relation = new ArchiveRelationRequest();
            relation.setChildColumn("PARENT_ID");
            relation.setMainColumn("ID");
            table.setRelations(Collections.singletonList(relation));
        }
        return table;
    }

    /** Params: type 任务类型。 Returns: 使用同一测试数据库的任务请求。 */
    private CreateTaskRequest request(String type) {
        DatabaseConfig config = new DatabaseConfig();
        config.setType(MigrationConstants.DB_MYSQL);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("结构预检隔离实测");
        request.setTaskType(type);
        request.setBatchSize(2);
        request.setSource(config);
        request.setTarget(config);
        request.setSteps(Collections.emptyList());
        return request;
    }

    /** Params: name、source、target 为迁移步骤配置。 Returns: 迁移步骤。 */
    private MigrationStepRequest step(String name, String source, String target) {
        MigrationStepRequest step = new MigrationStepRequest();
        step.setName(name);
        step.setSourceSql("SELECT ID, NAME, AMOUNT FROM " + source);
        step.setTargetTable(target);
        step.setInsertMode(MigrationConstants.INSERT_STRICT);
        return step;
    }

    /** Params: table 表名；columns 字段定义。 Returns: 无；创建成功后登记清理所有权。 */
    private void create(String table, String columns) throws Exception {
        check(table.matches(IDENTIFIER_PATTERN), "隔离表名合法");
        update("CREATE TABLE " + table + " (" + columns + ") ENGINE=InnoDB");
        ownedTables.add(table);
    }

    /** Params: sql 只作用于已验证隔离表的语句。 Returns: 无。 */
    private void update(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** Params: sql 标量统计 SQL。 Returns: 统计结果。 */
    private long count(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            check(rows.next(), "统计 SQL 返回结果");
            return rows.getLong(1);
        }
    }

    /** Params: method、target、args 为被测生产预检调用。 Returns: 无；必须产生汇总失败。 */
    private void expectValidationFailure(Method method, Object target, Object... args) throws Exception {
        try {
            invoke(method, target, args);
            throw new AssertionError("预检应因多处结构差异失败");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(EXPECTED_FAILURE), "预检返回校验汇总失败");
        }
    }

    /** Params: method、target、args 为反射调用参数。 Returns: 真实调用结果，解包原始异常。 */
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

    /** Params: delegate 唯一真实连接。 Returns: 记录应用侧 SQL、事务和元数据 API 的连接。 */
    private Connection traceConnection(Connection delegate) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                Object result = invoke(method, delegate, args == null ? new Object[0] : args);
                if (result instanceof Statement) {
                    return traceStatement((Statement)result, args != null && args.length > 0 ? (String)args[0] : null);
                }
                if (result instanceof DatabaseMetaData) {
                    return traceMetadata((DatabaseMetaData)result);
                }
                if (JDBC_COMMIT.equals(method.getName()) || JDBC_ROLLBACK.equals(method.getName())
                    || JDBC_AUTO_COMMIT.equals(method.getName())) {
                    operations.add("-- JDBC " + method.getName() + " " + Arrays.toString(args));
                }
                return result;
            });
    }

    /** Params: delegate JDBC 语句；preparedSql 预编译模板，可为空。 Returns: 带绑定参数记录的语句。 */
    private Statement traceStatement(Statement delegate, String preparedSql) {
        Map<Integer, Object> parameters = new LinkedHashMap<>();
        List<Map<Integer, Object>> batches = new ArrayList<>();
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                String name = method.getName();
                if (name.startsWith(JDBC_SET_PREFIX) && args != null && args.length >= BIND_ARGUMENT_COUNT
                    && args[0] instanceof Integer) {
                    parameters.put((Integer)args[0], JDBC_SET_NULL.equals(name) ? null : args[1]);
                }
                if (JDBC_ADD_BATCH.equals(name)) {
                    batches.add(new LinkedHashMap<>(parameters));
                }
                if (name.startsWith(JDBC_EXECUTE_PREFIX)) {
                    String sql = preparedSql != null ? preparedSql : (String)args[0];
                    operations.add(sql + ";" + (parameters.isEmpty() ? "" : " -- bind=" + parameters)
                        + (batches.isEmpty() ? "" : " -- batches=" + batches));
                    String normalized = sql.trim().toUpperCase(Locale.ROOT);
                    if (DATA_MUTATION_PREFIXES.stream().anyMatch(normalized::startsWith)) {
                        dataMutations++;
                    }
                }
                Object result = invoke(method, delegate, args == null ? new Object[0] : args);
                if (JDBC_EXECUTE_BATCH.equals(name) || JDBC_CLEAR_BATCH.equals(name)) {
                    batches.clear();
                }
                return result;
            });
    }

    /** Params: delegate JDBC 元数据。 Returns: 记录元数据 API 调用的代理。 */
    private DatabaseMetaData traceMetadata(DatabaseMetaData delegate) {
        return (DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                operations.add("-- JDBC metadata " + method.getName() + " " + Arrays.toString(args));
                return invoke(method, delegate, args == null ? new Object[0] : args);
            });
    }

    /** Params: 无。 Returns: 无；写入不含连接凭据的可审阅 SQL 记录。 */
    private void writeRecord() throws Exception {
        String path = requiredEnv("LDM_TEST_SQL_RECORD");
        StringBuilder text = new StringBuilder("# 结构差异预检 MySQL 实测记录\n\n")
            .append("日期：2026-10-26。单次物理登录，关闭自动重连；只操作随机前缀 `")
            .append(prefix).append("` 的本轮隔离对象。连接凭据未保存。\n\n")
            .append("通过连接代理记录下列全部应用侧 SQL、绑定参数、批次参数、事务和 JDBC 元数据 API。")
            .append("驱动登录初始化及元数据 API 内部 SQL 不由应用显式执行，无法由该代理获取原文。\n\n");
        for (String assertion : assertions) {
            text.append("- ").append(assertion).append('\n');
        }
        text.append("\n## 实际执行顺序\n\n```sql\n");
        for (int i = 0; i < operations.size(); i++) {
            text.append("-- operation ").append(i + 1).append('\n').append(operations.get(i)).append('\n');
        }
        text.append("```\n");
        if (Files.exists(Paths.get(path))) {
            text.insert(0, "\n---\n\n");
        }
        Files.write(Paths.get(path), text.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Params: key 环境变量名称。 Returns: 必填变量值。 */
    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + key);
        }
        return value;
    }

    /** Params: condition 断言条件；message 失败说明。 Returns: 无。 */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
