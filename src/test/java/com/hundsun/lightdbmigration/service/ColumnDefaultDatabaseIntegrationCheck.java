package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 单次登录 MySQL 或 Oracle，以本轮六张独立表验证两种生产准备路径的默认值同步。
 * Author: Codex
 * date: 2026-10-30
 */
public final class ColumnDefaultDatabaseIntegrationCheck {
    private static final String MYSQL = MigrationConstants.DB_MYSQL;
    private static final String ORACLE = MigrationConstants.DB_ORACLE;
    private static final String CREATE = "CREATE TABLE ";
    private static final String ALTER = "ALTER TABLE ";
    private static final String CLOSE = "close";
    private static final String SET_PREFIX = "set";
    private static final String EXECUTE_PREFIX = "execute";
    private static final String GET_METADATA = "getMetaData";
    private static final String GET_COLUMNS = "getColumns";
    private static final String SET_NULL = "setNull";
    private static final String OLD_OWNER = "old";
    private static final String OWNER = "O'Brien";
    private static final int FIRST = 0;
    private static final int SECOND = 1;
    private static final int TWO_ARGUMENTS = 2;
    private static final int ALTER_COUNT = 4;
    private static final long ZERO = 0L;
    private static final long ONE = 1L;
    private static final long TWO = 2L;
    private static final List<String> MODES = Arrays.asList("M", "A");
    private static final List<String> KINDS = Arrays.asList("S", "T", "C");
    private static final List<String> MUTATIONS = Arrays.asList("INSERT", "UPDATE", "DELETE", "MERGE");
    /** 本轮唯一对象前缀；Oracle 标识符保持在 30 字符以内。 */
    private final String prefix = "LDM_DEF_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    /** 只登记本轮已经创建成功的表。 */
    private final List<String> owned = new ArrayList<>();
    /** 记录全部应用 SQL、绑定参数和 JDBC 元数据调用。 */
    private final List<String> operations = new ArrayList<>();
    /** 记录断言及清理结果。 */
    private final List<String> evidence = new ArrayList<>();
    /** 环境提供的连接信息，结束时清空密码。 */
    private DatabaseConfig config;
    /** 唯一物理连接的记录代理。 */
    private Connection connection;
    /** 实际物理数据库或模式名。 */
    private String namespace;
    /** 已选择的受支持方言。 */
    private String type;
    /** Markdown 记录路径。 */
    private String report;
    /** 区分准备阶段与种子数据写入的计数。 */
    private int mutations;

    /** Params: args 可选数据库类型及记录路径；其他连接属性从 LDM_DEFAULT_DB_* 读取。 Returns: 无。 */
    public static void main(String[] args) throws Exception {
        new ColumnDefaultDatabaseIntegrationCheck().run(args);
    }

    /** Params: args 类型、报告路径。 Returns: 无；每次运行只尝试一次真实登录。 */
    private void run(String[] args) throws Exception {
        type = (args.length > FIRST ? args[FIRST] : env("TYPE")).toUpperCase(Locale.ROOT);
        if (!MYSQL.equals(type) && !ORACLE.equals(type)) {
            throw new IllegalArgumentException("本检查只支持 MYSQL 或 ORACLE");
        }
        report = args.length > SECOND ? args[SECOND] : env("REPORT");
        config = new DatabaseConfig();
        config.setType(type);
        config.setHost(env("HOST"));
        config.setPort(Integer.valueOf(env("PORT")));
        config.setUsername(env("USER"));
        config.setPassword(env("PASSWORD"));
        config.setDatabase(MYSQL.equals(type) ? env("DATABASE") : env("SERVICE"));
        config.setOracleMode("SERVICE");
        Class.forName(MYSQL.equals(type) ? "com.mysql.cj.jdbc.Driver" : "oracle.jdbc.OracleDriver");
        DriverManager.setLoginTimeout(MigrationConstants.CONNECTION_TIMEOUT_SECONDS);
        String url = DatabaseSupport.buildJdbcUrl(config) + (MYSQL.equals(type) ? "&autoReconnect=false" : "");
        Connection physical;
        try {
            physical = DriverManager.getConnection(url, config.getUsername(), config.getPassword());
        } catch (Exception exception) {
            evidence.add("单次登录失败，立即停止，未重试、未执行 SQL：" + safe(exception));
            try { writeReport(); } finally { config.clearPassword(); }
            throw new IllegalStateException(type + " 登录失败；未重试，详见脱敏报告。");
        }
        System.out.println("[LOGIN_SUCCEEDED] " + type + " one physical connection; no reconnect");
        connection = trace(physical);
        Throwable failure = null;
        try {
            DatabaseMetaData databaseMetadata = connection.getMetaData();
            evidence.add("实际数据库产品=" + databaseMetadata.getDatabaseProductName()
                + "；版本=" + databaseMetadata.getDatabaseProductVersion());
            namespace = MYSQL.equals(type) ? connection.getCatalog() : connection.getSchema();
            if (namespace == null || namespace.isEmpty()) { namespace = config.getUsername().toUpperCase(Locale.ROOT); }
            String requestedSchema = System.getenv("LDM_DEFAULT_DB_SCHEMA");
            if (ORACLE.equals(type) && requestedSchema != null && !requestedSchema.trim().isEmpty()) {
                namespace = SqlSupport.normalizeColumnName(requestedSchema, "测试 Oracle Schema").toUpperCase(Locale.ROOT);
                if (!namespace.equalsIgnoreCase(config.getUsername())) {
                    execute("ALTER SESSION SET CURRENT_SCHEMA = " + SqlSupport.quoteIdentifier(namespace, ORACLE));
                }
            }
            check(remaining() == ZERO, "本轮六张精确独立表预先不存在");
            for (String mode : MODES) { verifyMode(mode); }
            evidence.add("两种生产 prepare 路径全部通过；未启动业务迁移或归档数据循环。");
        } catch (Throwable exception) {
            failure = new IllegalStateException("独立对象验证失败：" + safe(exception));
            evidence.add(safe(failure));
            throw (IllegalStateException)failure;
        } finally {
            cleanup(physical, failure);
        }
        System.out.println("[RESULT] COLUMN_DEFAULT_" + type + "_INTEGRATION_CHECK_PASSED");
    }

    /** Params: mode 迁移 M 或归档 A。 Returns: 无；覆盖 ADD/MODIFY/CREATE、历史行及后续插入。 */
    private void verifyMode(String mode) throws Exception {
        String source = table(mode, "S"), target = table(mode, "T"), missing = table(mode, "C");
        String text = MYSQL.equals(type) ? "VARCHAR" : "VARCHAR2";
        String id = MYSQL.equals(type) ? "INT" : "NUMBER(10,0)";
        String number = MYSQL.equals(type) ? "DECIMAL(2,0)" : "NUMBER(2,0)";
        create(source, "ID " + id + " NOT NULL PRIMARY KEY, OWNER_NAME " + text + "(30) DEFAULT 'O''Brien' NOT NULL,"
            + " VIP_LEVEL " + text + "(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL,"
            + " AUTO_USED_FLAG " + number + " DEFAULT 0 NOT NULL");
        create(target, "ID " + id + " NOT NULL PRIMARY KEY, OWNER_NAME " + text + "(12) DEFAULT 'old' NOT NULL");
        execute("INSERT INTO " + source + " (ID) VALUES (1)");
        execute("INSERT INTO " + target + " (ID) VALUES (1)");
        defaults(source);
        verifyRow(source, ONE, OWNER);
        int before = mutations;
        MigrationTask failed = failedTask(source, target, mode);
        check(mutations == before && count(source) == ONE && count(target) == ONE, mode + "失败预检零写入/删除");
        executeSuggestions(failed, target, false);
        verifyRow(target, ONE, OLD_OWNER);
        execute("INSERT INTO " + target + " (ID) VALUES (2)");
        verifyRow(target, TWO, OWNER);
        verifyRecheck(source, target, mode);
        check(defaults(source).equals(defaults(target)), mode + "ALTER后源目标物理默认值一致");
        before = mutations;
        MigrationTask createTask = failedTask(source, missing, mode);
        check(mutations == before, mode + "缺表预检零写入/删除");
        executeSuggestions(createTask, missing, true);
        check(count(missing) == ZERO, "CREATE 不复制任何业务行");
        execute("INSERT INTO " + missing + " (ID) VALUES (1)");
        verifyRow(missing, ONE, OWNER);
        verifyRecheck(source, missing, mode);
        check(defaults(source).equals(defaults(missing)), mode + "CREATE后源目标物理默认值一致");
        System.out.println("[CHECK] " + type + "_" + mode + "_ADD_MODIFY_CREATE_PASSED");
    }

    /** Params: task 实际失败任务；target 唯一许可目标；create 是否缺表。 Returns: 无；只执行本轮生成建议。 */
    private void executeSuggestions(MigrationTask task, String target, boolean create) throws Exception {
        String script = task.getStructureSyncScript();
        operations.add("-- actual generated script; executed statements are recorded separately\n" + script);
        List<String> sqls = StructureSyncScript.parseStatements(script);
        check(sqls.size() == (create ? ONE : ALTER_COUNT), "实际建议条数符合预期");
        check(script.contains("DEFAULT ' '") && script.contains("DEFAULT 'O''Brien'") && script.contains("DEFAULT 0"),
            "实际建议保留空格、引号及数值默认值");
        for (String sql : sqls) {
            SqlSupport.QualifiedName name = StructureSyncScript.targetTable(sql);
            check(target.equals(name.getTable()) && (name.getSchema() == null || namespace.equals(name.getSchema()))
                && sql.startsWith(create ? CREATE : ALTER), "执行对象限定为本轮精确目标");
            execute(sql);
            if (create) { owned.add(target); }
        }
    }

    /** Params: source/target 精确测试表；mode 测试模式。 Returns: 无；重检不得发生业务数据变更。 */
    private void verifyRecheck(String source, String target, String mode) throws Exception {
        int before = mutations;
        check(prepare(task(source, target, mode)) != null && mutations == before, mode + "同步后完整预检通过且零写入/删除");
    }

    /** Params: source/target 精确测试表；mode 测试模式。 Returns: 具有实际同步建议的失败任务。 */
    private MigrationTask failedTask(String source, String target, String mode) throws Exception {
        MigrationTask task = task(source, target, mode);
        try { prepare(task); throw new AssertionError("真实结构差异未被拦截"); }
        catch (IllegalArgumentException expected) {
            check(task.getStructureSyncScript() != null, "失败预检同步返回建议脚本");
            operations.add("-- expected preparation failure: " + safe(expected));
            return task;
        }
    }

    /** Params: task 内存测试任务。 Returns: 生产完整准备结果；不调用任务 execute。 */
    private Object prepare(MigrationTask task) throws Exception {
        boolean archive = MigrationConstants.TASK_TYPE_ARCHIVE.equals(task.getRequest().getTaskType());
        Method method = archive ? ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class, Connection.class, String.class)
            : MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class, Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        return archive ? invoke(method, new ArchiveEngine(), task, connection, type)
            : invoke(method, new MigrationEngine(), task, connection, connection, type, type);
    }

    /** Params: source/target 精确测试表；mode 模式。 Returns: 显式 ID 游标避免依赖生成 CREATE 的主键。 */
    private MigrationTask task(String source, String target, String mode) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setSource(config); request.setTarget(config); request.setBatchSize(MigrationConstants.MIN_BATCH_SIZE);
        request.setName(prefix + "-" + mode); request.setSteps(Collections.emptyList());
        if (MODES.get(SECOND).equals(mode)) {
            request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
            ArchiveTableRequest main = new ArchiveTableRequest();
            main.setName(source); main.setNodeId("MAIN"); main.setSourceTable(source); main.setTargetTable(target);
            main.setSourceSql("SELECT * FROM " + source); main.setCursorColumn("ID");
            ArchiveRequest archive = new ArchiveRequest();
            archive.setMainTables(Collections.singletonList(main)); archive.setChildTables(Collections.emptyList());
            archive.setConfirmSourceDelete(Boolean.TRUE); request.setArchive(archive);
        } else {
            request.setTaskType(MigrationConstants.TASK_TYPE_MIGRATION);
            MigrationStepRequest step = new MigrationStepRequest();
            step.setName(source); step.setSourceSql("SELECT * FROM " + source); step.setTargetTable(target);
            step.setInsertMode(MigrationConstants.INSERT_STRICT); request.setSteps(Collections.singletonList(step));
        }
        return new MigrationTask(prefix + "-" + target, request);
    }

    /** Params: table 测试表。 Returns: 真实目录默认值；SQL 与绑定参数记录到报告。 */
    private Map<String, String> defaults(String table) throws Exception {
        String sql = MYSQL.equals(type) ? "SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION"
            : "SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID";
        Map<String, String> values = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1), value = rows.getString(2);
                    operations.add("-- actual default " + table + "." + name + "=" + (value == null ? "<SQL NULL>" : "[" + value + "]"));
                    values.put(name, value == null || MYSQL.equals(type) ? value : value.trim());
                }
            }
        }
        check(!values.isEmpty(), "精确表默认值目录可读取");
        return values;
    }

    /** Params: table 精确表；id 测试主键；owner 预期字符值。 Returns: 无；默认空格不能退化为空串或 NULL。 */
    private void verifyRow(String table, long id, String owner) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM " + table + " WHERE ID=?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next(), "默认值验证行存在");
                String actualOwner = rows.getString(1), vip = rows.getString(2), manual = rows.getString(3);
                long automatic = rows.getLong(4);
                check(!rows.wasNull() && owner.equals(actualOwner) && " ".equals(vip) && "0".equals(manual) && automatic == ZERO,
                    table + "/" + id + "默认值应用正确");
                operations.add("-- actual row owner=[" + actualOwner + "]; vip=[" + vip + "]; manual=[" + manual + "]; automatic=" + automatic);
            }
        }
    }

    /** Params: mode/kind 对象用途。 Returns: 本轮精确表名。 */
    private String table(String mode, String kind) { return prefix + "_" + mode + kind; }
    /** Params: table/definition 本轮对象及字段声明。 Returns: 无；成功后才登记清理。 */
    private void create(String table, String definition) throws Exception { execute(CREATE + table + " (" + definition + ")"); owned.add(table); }
    /** Params: sql 本轮 DDL 或种子数据操作。 Returns: 无。 */
    private void execute(String sql) throws Exception { try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); } }
    /** Params: table 本轮对象。 Returns: 行数，并记录标量结果。 */
    private long count(String table) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            check(rows.next(), "行数查询返回结果"); long value = rows.getLong(1); operations.add("-- count=" + value); return value;
        }
    }
    /** Params: 无。 Returns: 本轮六个精确表名的目录残留数。 */
    private long remaining() throws Exception {
        String sql = MYSQL.equals(type) ? "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?,?,?,?)"
            : "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, namespace); int index = 2;
            for (String mode : MODES) { for (String kind : KINDS) { statement.setString(index++, table(mode, kind)); } }
            try (ResultSet rows = statement.executeQuery()) { check(rows.next(), "目录残留查询返回结果"); long value = rows.getLong(1); operations.add("-- exact table remaining=" + value); return value; }
        }
    }

    /** Params: physical 唯一物理连接。 Returns: 记录 SQL 和元数据调用且不重连的连接代理。 */
    private Connection trace(Connection physical) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            if (CLOSE.equals(method.getName())) { return null; }
            Object result = invoke(method, physical, args == null ? new Object[0] : args);
            if (result instanceof Statement) { return traceStatement((Statement)result, args == null ? null : String.valueOf(args[FIRST])); }
            if (result instanceof DatabaseMetaData) {
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class}, (meta, called, params) -> {
                    operations.add("-- JDBC metadata " + called.getName() + " " + Arrays.toString(params));
                    Object answer = invoke(called, result, params == null ? new Object[0] : params);
                    if (GET_COLUMNS.equals(called.getName())) { operations.add("-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF"); }
                    return answer;
                });
            }
            return result;
        });
    }

    /** Params: delegate 真实语句；sql 预编译 SQL。 Returns: 包含每次执行及实际绑定值的记录代理。 */
    private Statement traceStatement(Statement delegate, String sql) {
        Map<Integer, Object> binds = new LinkedHashMap<>();
        return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class}, (proxy, method, args) -> {
            String name = method.getName();
            if (name.startsWith(SET_PREFIX) && args != null && args.length >= TWO_ARGUMENTS && args[FIRST] instanceof Integer) {
                binds.put((Integer)args[FIRST], SET_NULL.equals(name) ? null : args[SECOND]);
            }
            if (GET_METADATA.equals(name)) { operations.add("-- PreparedStatement.getMetaData SQL=" + sql); }
            boolean execute = name.startsWith(EXECUTE_PREFIX);
            if (execute) {
                String actual = sql == null ? String.valueOf(args[FIRST]) : sql;
                operations.add(actual + "; -- bindings=" + binds);
                for (String mutation : MUTATIONS) { if (actual.trim().toUpperCase(Locale.ROOT).startsWith(mutation)) { mutations++; break; } }
            }
            try {
                Object result = invoke(method, delegate, args == null ? new Object[0] : args);
                if (execute) { operations.add("-- execution result=" + (result instanceof ResultSet ? "ResultSet" : result)); }
                return result;
            } catch (Exception exception) { operations.add("-- operation failed=" + safe(exception)); throw exception; }
        });
    }

    /** Params: method/object/args 反射调用参数。 Returns: 原异常解包上抛，不重试。 */
    private Object invoke(Method method, Object object, Object... args) throws Exception {
        try { return method.invoke(object, args); }
        catch (InvocationTargetException exception) { Throwable cause = exception.getCause(); if (cause instanceof Exception) { throw (Exception)cause; } throw (Error)cause; }
    }

    /** Params: physical 唯一物理连接；original 主流程失败。 Returns: 无；精确清理并报告残留。 */
    private void cleanup(Connection physical, Throwable original) throws Exception {
        Throwable failure = null;
        try {
            if (!connection.getAutoCommit()) { connection.rollback(); }
            List<String> reverse = new ArrayList<>(owned); Collections.reverse(reverse);
            for (String table : reverse) {
                try { execute("DROP TABLE " + table + (ORACLE.equals(type) ? " PURGE" : "")); owned.remove(table); }
                catch (Throwable exception) { evidence.add("清理失败：" + table + "；" + safe(exception)); if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); } }
            }
            check(remaining() == ZERO, "本轮精确独立表清理残留=0");
            System.out.println("[CLEANUP] exact tables remaining=0");
        } catch (Throwable exception) { if (failure == null) { failure = exception; } else { failure.addSuppressed(exception); } }
        finally { try { writeReport(); } finally { try { physical.close(); } finally { config.clearPassword(); } } }
        if (failure != null) { if (original != null) { original.addSuppressed(failure); } else { throw new IllegalStateException("清理失败：" + safe(failure)); } }
    }

    /** Params: 无。 Returns: 无；追加完整脱敏远程操作报告，不保存配置或密码。 */
    private void writeReport() throws Exception {
        StringBuilder text = new StringBuilder("\n# ").append(type).append(" 源默认值同步实测\n\n日期：2026-10-30；前缀：`").append(prefix)
            .append("`。单次物理登录；仅本轮独立对象。驱动内部 SQL 无法取得原文；以下为全部应用 SQL、绑定参数、元数据 API、结果及清理。\n\n");
        for (String value : evidence) { text.append("- ").append(redact(value)).append('\n'); }
        text.append("\n```sql\n"); int index = 1;
        for (String value : operations) { text.append("-- operation ").append(index++).append('\n').append(redact(value)).append('\n'); }
        text.append("```\n"); Files.write(Paths.get(report), text.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    /** Params: suffix 必填环境变量后缀。 Returns: 环境原值；密码不 trim、不落盘。 */
    private String env(String suffix) { String value = System.getenv("LDM_DEFAULT_DB_" + suffix); if (value == null || value.isEmpty()) { throw new IllegalArgumentException("缺少环境变量 LDM_DEFAULT_DB_" + suffix); } return value; }
    /** Params: value 待记录内容。 Returns: 移除密码及连接标识的内容。 */
    private String redact(String value) {
        String result = value == null ? "" : value;
        if (config != null) {
            for (String secret : Arrays.asList(config.getPassword(), config.getHost(), namespace, config.getUsername(), config.getDatabase())) {
                if (secret != null && !secret.isEmpty()) { result = result.replace(secret, "<REDACTED>"); }
            }
        }
        return result;
    }
    /** Params: error 异常。 Returns: 单行脱敏异常信息。 */
    private String safe(Throwable error) { return redact(SqlSupport.safeMessage(error)).replace('\r', ' ').replace('\n', ' '); }
    /** Params: condition 断言；message 说明。 Returns: 无；失败即停止当前用例。 */
    private void check(boolean condition, String message) { if (!condition) { throw new AssertionError(message); } evidence.add("通过：" + message); }
}
