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
import java.sql.ResultSetMetaData;
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
 * 使用一次真实达梦登录和独立对象验证同步建议继承源字段默认值，不启动数据迁移或归档。
 * Author: Codex
 * date: 2026-10-30
 */
public final class ColumnDefaultDmIntegrationCheck {

    private static final String DRIVER = "dm.jdbc.driver.DmDriver";
    private static final String DM = MigrationConstants.DB_DM;
    private static final String CLOSE = "close";
    private static final String GET_METADATA = "getMetaData";
    private static final String SET_PREFIX = "set";
    private static final String EXECUTE_PREFIX = "execute";
    private static final String SET_NULL = "setNull";
    private static final String COMMIT = "commit";
    private static final String ROLLBACK = "rollback";
    private static final String AUTOCOMMIT = "setAutoCommit";
    private static final String SQL_CREATE = "CREATE TABLE ";
    private static final String SQL_ALTER = "ALTER TABLE ";
    private static final String DEFAULT_SPACE = "DEFAULT ' '";
    private static final String DEFAULT_ZERO = "DEFAULT 0";
    private static final String DEFAULT_QUOTED = "DEFAULT 'O''Brien'";
    private static final String OWNER_VALUE = "O'Brien";
    private static final String OLD_OWNER = "old";
    private static final String ID = "ID";
    private static final int TOKEN_LENGTH = 10;
    private static final int FIRST_ARGUMENT = 0;
    private static final int SECOND_ARGUMENT = 1;
    private static final int BIND_ARGUMENTS = 2;
    private static final int EXPECTED_ALTERS = 4;
    private static final long ZERO_ROWS = 0L;
    private static final long ONE_ROW = 1L;
    private static final long SECOND_ID = 2L;
    private static final List<String> MUTATING_SQL = Arrays.asList("INSERT", "UPDATE", "DELETE", "MERGE");

    /** 本轮唯一前缀，所有表名在单次真实登录前确定。 */
    private final String prefix = "LDM_DEF_" + UUID.randomUUID().toString().replace("-", "")
        .substring(0, TOKEN_LENGTH).toUpperCase(Locale.ROOT);
    /** 只允许清理本轮成功创建的精确对象。 */
    private final List<String> owned = new ArrayList<>();
    /** 全部远程 SQL、参数、元数据 API 和执行结果。 */
    private final List<String> operations = new ArrayList<>();
    /** 每项断言的实际结果。 */
    private final List<String> observations = new ArrayList<>();
    /** 密码只保留在运行期对象中。 */
    private DatabaseConfig config;
    /** 唯一真实连接的 SQL 记录代理。 */
    private Connection connection;
    /** 明确的测试模式。 */
    private String schema;
    /** 记录文件路径，从参数或环境提供。 */
    private String recordPath;
    /** 用于证明预检没有执行数据写入和删除的语句计数。 */
    private int businessMutations;

    /** Params: args 可选第一参数为 Markdown SQL 记录路径。 Returns: 无；真实登录仅尝试一次。 */
    public static void main(String[] args) throws Exception {
        new ColumnDefaultDmIntegrationCheck().run(args);
    }

    /** Params: args 可选记录文件路径。 Returns: 无；密码不进入源码、报告或控制台。 */
    private void run(String[] args) throws Exception {
        recordPath = args.length > FIRST_ARGUMENT ? args[FIRST_ARGUMENT] : requiredEnv("LDM_TEST_SQL_RECORD");
        config = configuration();
        schema = (config.getDatabase().isEmpty() ? config.getUsername() : config.getDatabase()).toUpperCase(Locale.ROOT);
        String password = System.getenv("LDM_TEST_DM_PASSWORD");
        if (password == null) {
            password = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        }
        config.setPassword(password);
        Class.forName(DRIVER);
        DriverManager.setLoginTimeout(MigrationConstants.CONNECTION_TIMEOUT_SECONDS);
        Connection physical;
        try {
            physical = DriverManager.getConnection(DatabaseSupport.buildJdbcUrl(config), config.getUsername(),
                config.getPassword());
        } catch (Exception exception) {
            observations.add("单次登录失败，立即停止；未重试，未执行 SQL。原因：" + safe(exception));
            try {
                writeRecord();
            } finally {
                config.clearPassword();
            }
            throw new IllegalStateException("达梦测试登录失败；未重试。详见脱敏记录。");
        }
        System.out.println("[LOGIN_SUCCEEDED] one physical DM connection; no reconnect");
        connection = trace(physical);
        Throwable original = null;
        try {
            check(remaining() == ZERO_ROWS, "本轮六张精确隔离表必须预先不存在");
            verifyMode(false);
            verifyMode(true);
            observations.add("迁移和归档均验证了 ADD、MODIFY、CREATE 继承源默认值；未启动任何业务数据任务。");
        } catch (Throwable exception) {
            observations.add("本轮未完成全部验证：" + safe(exception));
            original = new IllegalStateException("达梦默认值验证失败：" + safe(exception));
            throw (IllegalStateException)original;
        } finally {
            cleanup(physical, original);
        }
        System.out.println("[RESULT] COLUMN_DEFAULT_DM_INTEGRATION_CHECK_PASSED");
    }

    /** Params: archive 是否为归档准备路径。 Returns: 无；每种模式仅使用本轮三张独立表。 */
    private void verifyMode(boolean archive) throws Exception {
        String mode = archive ? "A" : "M";
        String source = table(mode, "S");
        String target = table(mode, "T");
        String missing = table(mode, "C");
        create(source, "ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,"
            + " VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL,"
            + " AUTO_USED_FLAG NUMBER(2,0) DEFAULT 0 NOT NULL");
        create(target, "ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL");
        execute("INSERT INTO " + source + " (ID) VALUES (1)");
        execute("INSERT INTO " + target + " (ID) VALUES (1)");
        verifySourceMetadata(source);
        verifyDefaults(source, ONE_ROW, OWNER_VALUE);
        int before = businessMutations;
        MigrationTask task = failedTask(source, target, archive);
        check(businessMutations == before, mode + "：失败预检零数据写入/删除");
        check(count(source) == ONE_ROW && count(target) == ONE_ROW, mode + "：预检失败保留两侧原有数据");
        String script = task.getStructureSyncScript();
        recordScript(mode + " ADD/MODIFY", script);
        List<String> statements = StructureSyncScript.parseStatements(script);
        check(statements.size() == EXPECTED_ALTERS, mode + "：三项 ADD 和一项 MODIFY 均生成可执行建议");
        check(script.contains(DEFAULT_SPACE), mode + "：空格默认值原样保留");
        check(script.contains(DEFAULT_ZERO), mode + "：数字零默认值保留");
        check(script.contains(DEFAULT_QUOTED), mode + "：引号默认值保留");
        for (String sql : statements) {
            check(sql.startsWith(SQL_ALTER), "只执行本轮预期 ALTER 建议");
            verifyTarget(sql, target);
            execute(sql);
        }
        verifyDefaults(target, ONE_ROW, OLD_OWNER);
        execute("INSERT INTO " + target + " (ID) VALUES (2)");
        verifyDefaults(target, SECOND_ID, OWNER_VALUE);
        before = businessMutations;
        check(prepare(task(source, target, archive)) != null, mode + "：执行同步后完整预检通过");
        check(businessMutations == before, mode + "：重检零数据写入/删除");
        compareDefaults(source, target);

        before = businessMutations;
        MigrationTask createTask = failedTask(source, missing, archive);
        check(businessMutations == before, mode + "：缺表预检零数据写入/删除");
        recordScript(mode + " CREATE", createTask.getStructureSyncScript());
        List<String> createStatements = StructureSyncScript.parseStatements(createTask.getStructureSyncScript());
        check(createStatements.size() == ONE_ROW, mode + "：缺表生成一项 CREATE 建议");
        String createSql = createStatements.get(FIRST_ARGUMENT);
        check(createSql.startsWith(SQL_CREATE) && createSql.contains(DEFAULT_SPACE)
            && createSql.contains(DEFAULT_ZERO) && createSql.contains(DEFAULT_QUOTED),
            mode + "：CREATE 包含全部可确认源默认值");
        verifyTarget(createSql, missing);
        execute(createSql);
        owned.add(missing);
        check(count(missing) == ZERO_ROWS, mode + "：CREATE 不复制业务数据");
        execute("INSERT INTO " + missing + " (ID) VALUES (1)");
        verifyDefaults(missing, ONE_ROW, OWNER_VALUE);
        before = businessMutations;
        check(prepare(task(source, missing, archive)) != null, mode + "：CREATE 后完整预检通过");
        check(businessMutations == before, mode + "：CREATE 后重检零数据写入/删除");
        compareDefaults(source, missing);
        System.out.println("[CHECK] " + mode + "_ADD_MODIFY_CREATE_DEFAULTS_PASSED");
    }

    /** Params: source 本轮源表。 Returns: 无；记录真实来源信息及两类目录返回的默认值。 */
    private void verifySourceMetadata(String source) throws Exception {
        for (String sql : Arrays.asList("SELECT * FROM " + source,
            "SELECT ID,OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM " + source,
            "SELECT ID AS ID_ALIAS,OWNER_NAME AS OWNER_ALIAS FROM " + source)) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                ResultSetMetaData metadata = statement.getMetaData();
                check(metadata != null, "原 SQL 预编译描述可读取");
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    operations.add("-- source origin: label=" + metadata.getColumnLabel(index)
                        + "; column=" + metadata.getColumnName(index) + "; table=" + metadata.getTableName(index)
                        + "; schema=" + metadata.getSchemaName(index) + "; catalog=" + metadata.getCatalogName(index));
                }
            }
        }
        DatabaseMetaData metadata = connection.getMetaData();
        String escape = metadata.getSearchStringEscape();
        String schemaPattern = exactPattern(schema, escape);
        String tablePattern = exactPattern(source, escape);
        try (ResultSet rows = metadata.getColumns(connection.getCatalog(), schemaPattern, tablePattern, "%")) {
            while (rows.next()) {
                operations.add("-- JDBC actual column: schema=" + rows.getString("TABLE_SCHEM")
                    + "; table=" + rows.getString("TABLE_NAME") + "; column=" + rows.getString("COLUMN_NAME")
                    + "; type=" + rows.getString("TYPE_NAME") + "; nullable=" + rows.getString("NULLABLE")
                    + "; COLUMN_DEF=" + visible(rows.getString("COLUMN_DEF")));
            }
        }
        catalogDefaults(source);
    }

    /** Params: value 物理名称；escape JDBC 模式转义符。 Returns: 不扩展读取范围的精确目录模式。 */
    private String exactPattern(String value, String escape) {
        check(escape != null && !escape.isEmpty(), "驱动必须提供元数据模式转义符");
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    /** Params: source/target 本轮表。 Returns: 无；源目标目录默认值实际相同。 */
    private void compareDefaults(String source, String target) throws Exception {
        Map<String, String> left = catalogDefaults(source);
        Map<String, String> right = catalogDefaults(target);
        check(left.equals(right), "执行后 ALL_TAB_COLUMNS 默认值与源表一致");
    }

    /** Params: table 本轮精确物理表。 Returns: 物理目录默认值原文，记录所有实际返回结果。 */
    private Map<String, String> catalogDefaults(String table) throws Exception {
        String sql = "SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID";
        Map<String, String> result = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString("COLUMN_NAME");
                    String value = rows.getString("DATA_DEFAULT");
                    operations.add("-- actual catalog: " + table + "." + name + "; DATA_DEFAULT=" + visible(value));
                    result.put(name, value == null ? null : value.trim());
                }
            }
        }
        return result;
    }

    /** Params: table 测试表；id 精确行；owner 应保持或应用的默认字段值。 Returns: 无；空格长度和值均检查。 */
    private void verifyDefaults(String table, long id, String owner) throws Exception {
        String sql = "SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM " + table + " WHERE ID=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next(), "默认值验证行必须存在");
                String actualOwner = rows.getString("OWNER_NAME");
                String vip = rows.getString("VIP_LEVEL");
                String manual = rows.getString("MANUAL_FLAG");
                long automatic = rows.getLong("AUTO_USED_FLAG");
                check(owner.equals(actualOwner) && " ".equals(vip) && "0".equals(manual) && automatic == ZERO_ROWS
                    && !rows.wasNull(), "行 " + table + "/" + id + " 的文本、空格及数字默认值符合预期");
                operations.add("-- actual row: owner=" + visible(actualOwner) + "; vip=" + visible(vip)
                    + "; manual=" + visible(manual) + "; automatic=" + automatic);
                check(!rows.next(), "精确主键不得返回多行");
            }
        }
    }

    /** Params: source/target 本轮表；archive 预检模式。 Returns: 已确认结构失败并提供建议的任务。 */
    private MigrationTask failedTask(String source, String target, boolean archive) throws Exception {
        MigrationTask task = task(source, target, archive);
        try {
            prepare(task);
            throw new AssertionError("预期结构差异未被拦截");
        } catch (IllegalArgumentException expected) {
            check(task.getStructureSyncScript() != null, "结构预检失败同时输出同步建议");
            operations.add("-- expected precheck failure: " + safe(expected));
            return task;
        }
    }

    /** Params: task 本轮内存任务。 Returns: 生产代码完整准备结果，不执行迁移或归档数据循环。 */
    private Object prepare(MigrationTask task) throws Exception {
        if (MigrationConstants.TASK_TYPE_ARCHIVE.equals(task.getRequest().getTaskType())) {
            Method method = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class, Connection.class, String.class);
            method.setAccessible(true);
            return invoke(method, new ArchiveEngine(), task, connection, DM);
        }
        Method method = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        return invoke(method, new MigrationEngine(), task, connection, connection, DM, DM);
    }

    /** Params: source/target 本轮表；archive 模式。 Returns: 不含新连接操作的测试任务。 */
    private MigrationTask task(String source, String target, boolean archive) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName("DM-default-" + target);
        request.setSource(config);
        request.setTarget(config);
        request.setBatchSize(MigrationConstants.MIN_BATCH_SIZE);
        request.setTaskType(archive ? MigrationConstants.TASK_TYPE_ARCHIVE : MigrationConstants.TASK_TYPE_MIGRATION);
        request.setSteps(Collections.emptyList());
        if (archive) {
            ArchiveTableRequest main = new ArchiveTableRequest();
            main.setName(source);
            main.setNodeId("MAIN");
            main.setSourceTable(source);
            main.setTargetTable(target);
            main.setSourceSql("SELECT * FROM " + source);
            main.setCursorColumn(ID);
            ArchiveRequest definition = new ArchiveRequest();
            definition.setConfirmSourceDelete(Boolean.TRUE);
            definition.setMainTables(Collections.singletonList(main));
            definition.setChildTables(Collections.emptyList());
            request.setArchive(definition);
        } else {
            MigrationStepRequest step = new MigrationStepRequest();
            step.setName(source);
            step.setSourceSql("SELECT * FROM " + source);
            step.setTargetTable(target);
            step.setInsertMode(MigrationConstants.INSERT_STRICT);
            request.setSteps(Collections.singletonList(step));
        }
        return new MigrationTask(prefix + "-" + target, request);
    }

    /** Params: mode 模式；kind 表用途。 Returns: 本轮预先确定的精确表名。 */
    private String table(String mode, String kind) {
        return prefix + "_" + mode + kind;
    }

    /** Params: sql 本轮生成 DDL；target 允许的唯一对象。 Returns: 无；防止测试操作其他表。 */
    private void verifyTarget(String sql, String target) {
        SqlSupport.QualifiedName name = StructureSyncScript.targetTable(sql);
        check(target.equals(name.getTable()) && (name.getSchema() == null || schema.equals(name.getSchema())),
            "生成语句只允许操作本轮精确目标表");
    }

    /** Params: table 本轮对象；definition 测试定义。 Returns: 无；只登记成功创建的对象。 */
    private void create(String table, String definition) throws Exception {
        execute(SQL_CREATE + table + " (" + definition + ")");
        owned.add(table);
    }

    /** Params: sql 本轮 DDL 或种子写入。 Returns: 无；统一由记录代理保存执行结果。 */
    private void execute(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** Params: table 本轮表。 Returns: 行数，并记录实际标量结果。 */
    private long count(String table) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            check(rows.next(), "计数查询必须有结果");
            long count = rows.getLong(1);
            operations.add("-- actual count " + table + "=" + count);
            return count;
        }
    }

    /** Params: 无。 Returns: 本轮六个精确表名的目录残留数，不扫描其他业务表。 */
    private long remaining() throws Exception {
        String sql = "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            int bind = 2;
            for (String mode : Arrays.asList("M", "A")) {
                for (String kind : Arrays.asList("S", "T", "C")) {
                    statement.setString(bind++, table(mode, kind));
                }
            }
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next(), "目录计数必须有结果");
                long result = rows.getLong(1);
                operations.add("-- actual catalog remaining=" + result);
                return result;
            }
        }
    }

    /** Params: physical 唯一连接。 Returns: 记录远程操作的代理；不允许逻辑调用关闭物理连接。 */
    private Connection trace(Connection physical) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (CLOSE.equals(name)) {
                    operations.add("-- logical close; one physical connection remains owned by test");
                    return null;
                }
                Object result = invoke(method, physical, args == null ? new Object[0] : args);
                if (result instanceof Statement) {
                    String sql = args != null && args.length > FIRST_ARGUMENT ? String.valueOf(args[FIRST_ARGUMENT]) : null;
                    return traceStatement((Statement)result, sql);
                }
                if (result instanceof DatabaseMetaData) {
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (metaProxy, metaMethod, metaArgs) -> {
                            operations.add("-- JDBC metadata " + metaMethod.getName() + " " + redact(Arrays.toString(metaArgs)));
                            return invoke(metaMethod, result, metaArgs == null ? new Object[0] : metaArgs);
                        });
                }
                if (COMMIT.equals(name) || ROLLBACK.equals(name) || AUTOCOMMIT.equals(name)) {
                    operations.add("-- JDBC " + name + " " + Arrays.toString(args));
                }
                return result;
            });
    }

    /** Params: delegate 语句；preparedSql 预编译模板。 Returns: 记录 SQL、绑定参数、执行结果和失败原因的代理。 */
    private Statement traceStatement(Statement delegate, String preparedSql) {
        Map<Integer, Object> binds = new LinkedHashMap<>();
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                String name = method.getName();
                if (GET_METADATA.equals(name)) {
                    operations.add("-- JDBC PreparedStatement.getMetaData; SQL=" + preparedSql);
                }
                if (name.startsWith(SET_PREFIX) && args != null && args.length >= BIND_ARGUMENTS
                    && args[FIRST_ARGUMENT] instanceof Integer) {
                    binds.put((Integer)args[FIRST_ARGUMENT], SET_NULL.equals(name) ? null : args[SECOND_ARGUMENT]);
                }
                boolean execute = name.startsWith(EXECUTE_PREFIX);
                if (execute) {
                    String sql = preparedSql == null ? String.valueOf(args[FIRST_ARGUMENT]) : preparedSql;
                    operations.add(sql + "; -- bindings=" + binds);
                    for (String mutation : MUTATING_SQL) {
                        if (sql.trim().toUpperCase(Locale.ROOT).startsWith(mutation)) {
                            businessMutations++;
                            break;
                        }
                    }
                }
                try {
                    Object result = invoke(method, delegate, args == null ? new Object[0] : args);
                    if (execute) {
                        operations.add("-- execution result: " + (result instanceof ResultSet ? "ResultSet returned" : result));
                    }
                    return result;
                } catch (Exception exception) {
                    operations.add("-- operation failed: " + safe(exception));
                    throw exception;
                }
            });
    }

    /** Params: method/object/args 反射目标和参数。 Returns: 解包后的原始结果，失败不重试。 */
    private Object invoke(Method method, Object object, Object... args) throws Exception {
        try {
            return method.invoke(object, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception)cause;
            }
            throw (Error)cause;
        }
    }

    /** Params: label 用例；script 实际任务建议。 Returns: 无；保存实际生成内容用于审阅。 */
    private void recordScript(String label, String script) {
        operations.add("-- generated script [" + label + "]\n" + script);
    }

    /** Params: physical 唯一物理连接；original 原异常。 Returns: 无；清理所有本轮精确创建表并保存结果。 */
    private void cleanup(Connection physical, Throwable original) throws Exception {
        Throwable failure = null;
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            }
            List<String> cleanup = new ArrayList<>(owned);
            Collections.reverse(cleanup);
            for (String table : cleanup) {
                try {
                    execute("DROP TABLE " + table);
                    owned.remove(table);
                } catch (Throwable exception) {
                    observations.add("清理失败：" + table + "；" + safe(exception));
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            long remaining = remaining();
            check(remaining == ZERO_ROWS, "本轮精确隔离表清理残留=0");
            System.out.println("[CLEANUP] exact tables remaining=" + remaining);
        } catch (Throwable exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            try {
                writeRecord();
            } finally {
                try {
                    physical.close();
                } finally {
                    config.clearPassword();
                }
            }
        }
        if (failure != null) {
            if (original != null) {
                original.addSuppressed(new IllegalStateException("清理异常：" + safe(failure)));
            } else {
                throw new IllegalStateException("达梦测试清理失败：" + safe(failure));
            }
        }
    }

    /** Params: 无。 Returns: 无；追加完整脱敏 SQL 记录，不保存密码或端点。 */
    private void writeRecord() throws Exception {
        StringBuilder text = new StringBuilder("\n# 达梦源默认值同步真实验证\n\n日期：2026-10-30；本轮前缀：`")
            .append(prefix).append("`。单次物理登录，不自动重试，只操作本轮独立对象。\n\n")
            .append("下列记录覆盖全部应用 SQL、绑定参数、元数据 API、执行结果及清理。")
            .append("驱动内部初始化和元数据内部 SQL 无法取得原文。连接端点、用户名、Schema、密码脱敏。\n\n");
        for (String item : observations) {
            text.append("- ").append(redact(item)).append('\n');
        }
        text.append("\n```sql\n");
        int number = 1;
        for (String operation : operations) {
            text.append("-- operation ").append(number++).append('\n').append(redact(operation)).append('\n');
        }
        text.append("```\n");
        Files.write(Paths.get(recordPath), text.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Params: 无。 Returns: 环境提供的测试配置；密码在唯一登录前从环境或标准输入读取。 */
    private DatabaseConfig configuration() {
        DatabaseConfig result = new DatabaseConfig();
        result.setType(DM);
        result.setHost(requiredEnv("LDM_TEST_DM_HOST"));
        result.setPort(Integer.valueOf(optionalEnv("LDM_TEST_DM_PORT", "5236")));
        result.setUsername(requiredEnv("LDM_TEST_DM_USER"));
        result.setDatabase(optionalEnv("LDM_TEST_DM_SCHEMA", ""));
        return result;
    }

    /** Params: name 必填环境变量。 Returns: 非空值，缺失时不连接数据库。 */
    private String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少环境变量：" + name);
        }
        return value.trim();
    }

    /** Params: name 环境变量；fallback 缺省。 Returns: 配置值。 */
    private String optionalEnv(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Params: value 原始默认值。 Returns: 显示空格、换行和 SQL NULL 差别的记录内容。 */
    private String visible(String value) {
        return value == null ? "<SQL NULL>" : "[" + value.replace("\r", "\\r").replace("\n", "\\n") + "]";
    }

    /** Params: exception 异常。 Returns: 脱敏单行说明。 */
    private String safe(Throwable exception) {
        return redact(SqlSupport.safeMessage(exception)).replace('\r', ' ').replace('\n', ' ');
    }

    /** Params: value 任意记录。 Returns: 移除连接秘密后的内容。 */
    private String redact(String value) {
        String result = value == null ? "" : value;
        if (config == null) {
            return result;
        }
        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            result = result.replace(config.getPassword(), "<PASSWORD>");
        }
        if (schema != null && !schema.isEmpty()) {
            result = result.replace(schema, "<SCHEMA>");
        }
        if (config.getHost() != null && !config.getHost().isEmpty()) {
            result = result.replace(config.getHost(), "<HOST>");
        }
        if (config.getUsername() != null && !config.getUsername().isEmpty()) {
            result = result.replace(config.getUsername(), "<USER>");
        }
        return result;
    }

    /** Params: condition 断言；message 可审阅说明。 Returns: 无；失败立即停止当前用例，不重试。 */
    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        observations.add("通过：" + message);
    }
}
