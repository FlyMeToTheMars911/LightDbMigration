package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.model.MigrationStepRequest;
import com.hundsun.lightdbmigration.model.StructureSyncRequest;
import com.hundsun.lightdbmigration.support.DatabaseSupport;
import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.StructureSyncScript;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
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
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单次真实达梦登录验证查询元数据未知精度兼容、物理差异严格校验及确认执行闭环。
 * Author: Codex
 * date: 2026-10-28
 */
public final class DamengQueryMetadataIntegrationCheck {

    private static final String DRIVER = "dm.jdbc.driver.DmDriver";
    private static final String ID = "ACT_ID";
    private static final String VISIT = "VISIT_BEGIN_DATE";
    private static final String CREATED = "CREATE_DATE_TIME";
    private static final String AMOUNT = "PHONE_SERVICE_VALUE";
    private static final String LABEL = "LABEL";
    private static final String QUERY_MISMATCH = "归档查询字段类型与源表不同";
    private static final String EXECUTE_PREFIX = "execute";
    private static final String GET_METADATA = "getMetaData";
    private static final String SET_PREFIX = "set";
    private static final String CLOSE = "close";
    private static final String COMMIT = "commit";
    private static final String ROLLBACK = "rollback";
    private static final String AUTO_COMMIT = "setAutoCommit";
    private static final String SET_SCHEMA = "setSchema";
    private static final String ADD_BATCH = "addBatch";
    private static final String CLEAR_BATCH = "clearBatch";
    private static final String EXECUTE_BATCH = "executeBatch";
    private static final String SET_NULL = "setNull";
    private static final String SUCCESS = "SUCCEEDED";
    private static final String CONNECTION_STATE_PREFIX = "08";
    private static final String SQL_ALTER = "ALTER TABLE ";
    private static final int TOKEN_LENGTH = 10;
    private static final int UNKNOWN_PRECISION = 0;
    private static final int UNKNOWN_SCALE = -127;
    private static final int FIRST_ARGUMENT = 0;
    private static final int SECOND_ARGUMENT = 1;
    private static final int BIND_ARGUMENTS = 2;
    private static final int EXPECTED_DIFFERENCES = 3;
    private static final long ONE_ROW = 1L;
    private static final long ZERO_ROWS = 0L;
    private static final Set<String> EXACT_NUMERIC_TYPES = new LinkedHashSet<>(
        Arrays.asList("NUMBER", "DECIMAL", "NUMERIC", "DEC"));
    private static final List<String> MUTATING_SQL = Arrays.asList("INSERT", "UPDATE", "DELETE", "MERGE");
    private final String prefix = "LDM_DMQ_" + UUID.randomUUID().toString().replace("-", "")
        .substring(0, TOKEN_LENGTH).toUpperCase(Locale.ROOT);
    private final String source = prefix + "_S";
    private final String target = prefix + "_T";
    private final List<String> owned = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final List<String> observations = new ArrayList<>();
    private DatabaseConfig config;
    private String schema;
    private Connection connection;
    private int businessMutations;

    /** Params: args 未使用；密码仅从标准输入读取。 Returns: 无；不执行第二次物理登录。 */
    public static void main(String[] args) throws Exception {
        new DamengQueryMetadataIntegrationCheck().run();
    }

    /** Params: 无。 Returns: 无；登录失败立即停止，不执行后续 SQL 或重试。 */
    private void run() throws Exception {
        config = configuration();
        schema = SqlSupport.normalizeColumnName(config.getDatabase().isEmpty() ? config.getUsername()
            : config.getDatabase(), "测试 Schema").toUpperCase(Locale.ROOT);
        config.setPassword(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine());
        Class.forName(DRIVER);
        DriverManager.setLoginTimeout(MigrationConstants.CONNECTION_TIMEOUT_SECONDS);
        Connection physical;
        try {
            physical = DriverManager.getConnection(DatabaseSupport.buildJdbcUrl(config), config.getUsername(),
                config.getPassword());
        } catch (Exception exception) {
            String message = safe(exception);
            config.clearPassword();
            System.out.println("[LOGIN_FAILED] single attempt; no retry");
            throw new IllegalStateException("达梦单次登录失败：" + message);
        }
        System.out.println("[LOGIN_SUCCEEDED] single physical DM connection");
        connection = trace(physical);
        Throwable original = null;
        try {
            check(remaining() == ZERO_ROWS, "本轮隔离对象必须预先不存在");
            createFixtures();
            String query = selectQuery();
            verifyQueryValues(query);
            verifyPreparePasses(query, "initial-compatible");
            verifyMigrationMetadata(query, "initial-migration-compatible");
            verifyPhysicalDifferencesAndRepair(query);
            observations.add("完整归档两阶段预检、物理差异严格失败、公开结构建议执行及重新预检全部通过。");
        } catch (Throwable exception) {
            observations.add("本轮失败：" + safe(exception));
            IllegalStateException reported = new IllegalStateException("达梦隔离验证失败：" + safe(exception));
            original = reported;
            throw reported;
        } finally {
            try {
                cleanup(physical, original);
            } finally {
                config.clearPassword();
            }
        }
        System.out.println("[RESULT] DAMENG_QUERY_METADATA_INTEGRATION_CHECK_PASSED");
    }

    /** Params: 无。 Returns: 无；只登记本次成功创建的两张独立表。 */
    private void createFixtures() throws Exception {
        String definition = "ACT_ID NUMBER(20,0) NOT NULL PRIMARY KEY, VISIT_BEGIN_DATE NUMBER(8,0) NULL,"
            + " CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL,"
            + " LABEL VARCHAR(20) NULL";
        for (String table : Arrays.asList(source, target)) {
            String fields = table.equals(source) ? definition : definition.replace(" PRIMARY KEY", "");
            update("CREATE TABLE " + table + " (" + fields + ")");
            owned.add(table);
        }
        update("INSERT INTO " + source + " VALUES (12345678901234567890,20261028,20261028103045,12.3456,'dm-query-test')");
        connection.commit();
        operations.add("-- seed result: source=1 row, target=0 rows");
    }

    /** Params: 无。 Returns: 优先选择实际出现未知精度且其他已知定义严格相符的查询。 */
    private String selectQuery() throws Exception {
        Map<String, Column> physical = columns("physical source", "SELECT * FROM " + source + " WHERE 1=0");
        columns("physical target", "SELECT * FROM " + target + " WHERE 1=0");
        List<String> candidates = Arrays.asList(query(""), query("NVL"), query("CAST"), query("ARITHMETIC"));
        String fallback = null;
        String selected = null;
        boolean observedUnknown = false;
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                Map<String, Column> actual = columns("candidate " + (i + 1),
                    "SELECT * FROM (" + candidate + ") LM_ARCHIVE_META WHERE 1=0");
                int unknown = unknownCount(actual);
                observedUnknown |= unknown > UNKNOWN_PRECISION;
                boolean compatible = compatibleQuery(physical, actual);
                observations.add("候选 " + (i + 1) + "：实际未知数值标记字段数=" + unknown
                    + "，其他已知字段定义匹配=" + compatible + "。查询：`" + candidate + "`");
                if (compatible && fallback == null) {
                    fallback = candidate;
                }
                if (compatible && unknown > UNKNOWN_PRECISION && selected == null) {
                    selected = candidate;
                }
            } catch (Exception exception) {
                rejectConnectionFailure(exception);
                observations.add("候选 " + (i + 1) + " 只读查询失败：" + safe(exception));
            }
        }
        if (selected == null) {
            selected = fallback;
            if (observedUnknown) {
                observations.add("候选实际出现 precision=0/scale=-127，但同一查询还存在其他已知定义差异，"
                    + "不能将该候选判为兼容；改用严格匹配查询验证闭环。");
                System.out.println("[OBSERVATION] UNKNOWN_NUMBER_MARKER_OBSERVED_BUT_NOT_SELECTED");
            } else {
                observations.add("本库候选未复现 precision=0/scale=-127；如实以已知定义匹配查询验证闭环，不伪造元数据。");
                System.out.println("[OBSERVATION] UNKNOWN_NUMBER_MARKER_NOT_REPRODUCED");
            }
        } else {
            observations.add("已复现并选用实际 precision=0/scale=-127 查询元数据；没有兼容任何已知但不相等的精度。");
            System.out.println("[OBSERVATION] UNKNOWN_NUMBER_MARKER_REPRODUCED");
        }
        check(selected != null, "所有候选均有已知类型/精度差异或无法执行，不能宣称预检兼容");
        observations.add("最终选用查询：`" + selected + "`");
        return selected;
    }

    /** Params: expression 数值表达式候选。 Returns: 保持本轮数字值语义的五字段查询。 */
    private String query(String expression) {
        List<String> fields = new ArrayList<>();
        for (String field : Arrays.asList(ID, VISIT, CREATED, AMOUNT)) {
            String value = field;
            switch (expression) {
                case "NVL": value = "NVL(" + field + "," + field + ")"; break;
                case "CAST": value = "CAST(" + field + " AS NUMBER)"; break;
                case "ARITHMETIC": value = "(" + field + "+0)"; break;
                default: break;
            }
            fields.add(value + " AS " + field);
        }
        fields.add(LABEL);
        return "SELECT " + String.join(",", fields) + " FROM " + source;
    }

    /** Params: sql 查询。 Returns: 无；源物理行与选中查询的所有数值和文本必须一致。 */
    private void verifyQueryValues(String sql) throws Exception {
        List<String> physical = rowValues("SELECT * FROM " + source);
        List<String> query = rowValues(sql);
        check(physical.equals(query), "选中查询改变本轮测试数据值");
        observations.add("选中查询与源物理表的 ACT_ID、日期数字、金额和 LABEL 值完全相同，共 1 行。");
    }

    /** Params: sql 五字段查询。 Returns: 单条测试记录的规范化值。 */
    private List<String> rowValues(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            check(rows.next(), "测试数据必须存在");
            List<String> result = new ArrayList<>();
            for (String field : Arrays.asList(ID, VISIT, CREATED, AMOUNT)) {
                BigDecimal number = rows.getBigDecimal(field);
                result.add(number == null ? "<NULL>" : number.stripTrailingZeros().toPlainString());
            }
            result.add(rows.getString(LABEL));
            check(!rows.next(), "本轮仅允许一条隔离测试记录");
            return result;
        }
    }

    /** Params: query 最终选中的真实查询。 Returns: 无；真实物理差异必须失败并能通过公开建议执行消除。 */
    private void verifyPhysicalDifferencesAndRepair(String query) throws Exception {
        update(SQL_ALTER + target + " MODIFY (ACT_ID NUMBER(10,0))");
        update(SQL_ALTER + target + " MODIFY (PHONE_SERVICE_VALUE NUMBER(8,2))");
        update(SQL_ALTER + target + " MODIFY (LABEL VARCHAR(10) NOT NULL)");
        columns("physical target after deliberate changes", "SELECT * FROM " + target + " WHERE 1=0");
        MigrationTask task = task(query, "physical-difference");
        int before = businessMutations;
        boolean failed = false;
        try {
            prepare(task);
        } catch (IllegalArgumentException expected) {
            failed = true;
            task.markFailed("本轮物理差异预检按预期失败：" + expected.getMessage());
        }
        check(failed && task.getStructureSyncScript() != null, "真实物理差异未严格失败并生成脚本");
        check(businessMutations == before, "物理差异预检失败前执行了数据写入或删除");
        check(count("SELECT COUNT(*) FROM " + source) == ONE_ROW
            && count("SELECT COUNT(*) FROM " + target) == ZERO_ROWS, "差异预检失败后源/目标数据发生改变");
        check(!taskLogs(task).contains(QUERY_MISMATCH), "兼容查询仍误报字段类型与源表不同");
        List<String> suggestions = StructureSyncScript.parseStatements(task.getStructureSyncScript());
        check(suggestions.size() == EXPECTED_DIFFERENCES, "三项物理差异应生成三条独立 ALTER");
        for (String field : Arrays.asList(ID, AMOUNT, LABEL)) {
            check(suggestions.stream().anyMatch(sql -> sql.startsWith(SQL_ALTER) && sql.contains("\"" + field + "\"")),
                "物理差异缺少对应 ALTER：" + field);
        }
        task.clearPasswords();
        StructureSyncRequest confirmed = new StructureSyncRequest();
        confirmed.setConfirmed(Boolean.TRUE);
        confirmed.setScriptDigest(task.getStructureSyncDigest());
        confirmed.setDatabase(copyConfig());
        StructureSyncService service = new StructureSyncService(database -> {
            operations.add("-- StructureSyncService requested connection; reused sole physical DM connection");
            return connection;
        });
        service.execute(task, confirmed);
        Map<String, Object> result = task.structureSyncExecutionSnapshot();
        check(SUCCESS.equals(result.get("status")), "公开结构建议执行失败：" + result.get("message"));
        check(((Number)result.get("executedStatements")).intValue() == EXPECTED_DIFFERENCES,
            "三条物理字段同步语句未全部执行");
        check(confirmed.getDatabase().getPassword() == null, "执行请求的临时密码未清理");
        check(businessMutations == before, "结构同步错误执行了归档数据写入或源删除");
        Map<String, Column> left = columns("source after repair", "SELECT * FROM " + source + " WHERE 1=0");
        Map<String, Column> right = columns("target after repair", "SELECT * FROM " + target + " WHERE 1=0");
        check(physicalDefinitions(left).equals(physicalDefinitions(right)), "执行建议后物理表字段定义仍有差异");
        verifyPreparePasses(query, "repaired-compatible");
        verifyMigrationMetadata(query, "repaired-migration-compatible");
        check(count("SELECT COUNT(*) FROM " + source) == ONE_ROW
            && count("SELECT COUNT(*) FROM " + target) == ZERO_ROWS, "结构修复误启动归档业务流程");
        observations.add("ACT_ID 精度、PHONE_SERVICE_VALUE 精度/小数位、LABEL 长度/可空性三项真实物理差异严格报错，"
            + "产生三条 ALTER 并经公开确认执行成功；源1行/目标0行未变；修复后两阶段预检通过，无查询类型误报。");
        System.out.println("[CHECK] DM_PHYSICAL_DIFFERENCES_AND_CONFIRMED_REPAIR_PASSED");
    }

    /** Params: query 查询；label 阶段名。 Returns: 无；必须通过完整归档 prepare 的两个阶段。 */
    private void verifyPreparePasses(String query, String label) throws Exception {
        MigrationTask task = task(query, label);
        int before = businessMutations;
        check(prepare(task) != null, "两阶段 prepare 未返回有效准备结果");
        check(task.getStructureSyncScript() == null, "应兼容查询产生了结构建议");
        check(!taskLogs(task).contains(QUERY_MISMATCH), "完整准备仍出现查询字段类型误报");
        check(businessMutations == before, "准备阶段发生数据变更");
        observations.add(label + "：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。");
        System.out.println("[CHECK] " + label.toUpperCase(Locale.ROOT) + "_TWO_PHASE_PREPARE_PASSED");
    }

    /** Params: task 当前隔离任务。 Returns: 真实生产准备结果，原异常解包上抛。 */
    private Object prepare(MigrationTask task) throws Exception {
        Method method = ArchiveEngine.class.getDeclaredMethod("prepare", MigrationTask.class,
            Connection.class, String.class);
        method.setAccessible(true);
        return invoke(method, new ArchiveEngine(), task, connection, MigrationConstants.DB_DM);
    }

    /** Params: query 真实查询；label 阶段名。 Returns: 无；迁移不能将同一未知精度当作真实字段变更。 */
    private void verifyMigrationMetadata(String query, String label) throws Exception {
        MigrationStepRequest step = new MigrationStepRequest();
        step.setName(label);
        step.setSourceSql(query);
        step.setTargetTable(target);
        step.setInsertMode(MigrationConstants.INSERT_STRICT);
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName(label);
        request.setTaskType(MigrationConstants.TASK_TYPE_MIGRATION);
        request.setSource(copyConfig());
        request.setTarget(copyConfig());
        request.setBatchSize(MigrationConstants.MIN_BATCH_SIZE);
        request.setSteps(Collections.singletonList(step));
        MigrationTask task = new MigrationTask(prefix + "-" + label, request);
        int before = businessMutations;
        Method method = MigrationEngine.class.getDeclaredMethod("prepareAllSteps", MigrationTask.class,
            Connection.class, Connection.class, String.class, String.class);
        method.setAccessible(true);
        check(invoke(method, new MigrationEngine(), task, connection, connection,
            MigrationConstants.DB_DM, MigrationConstants.DB_DM) != null, "达梦迁移准备必须通过");
        check(task.getStructureSyncScript() == null, "相同物理字段不应因查询未知精度生成无意义ALTER");
        check(businessMutations == before, "迁移准备不得写入数据");
        observations.add(label + "：源/目标物理字段相同，迁移查询未知精度不误报，零数据写入。");
        System.out.println("[CHECK] " + label.toUpperCase(Locale.ROOT) + "_PASSED");
    }

    /** Params: query 主表查询；label 任务标签。 Returns: 仅本轮源/目标表的归档任务。 */
    private MigrationTask task(String query, String label) {
        ArchiveTableRequest main = new ArchiveTableRequest();
        main.setName("达梦查询元数据隔离表");
        main.setNodeId("MAIN_1");
        main.setSourceTable(source);
        main.setTargetTable(target);
        main.setSourceSql(query);
        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        archive.setMainTables(Collections.singletonList(main));
        archive.setChildTables(Collections.emptyList());
        CreateTaskRequest request = new CreateTaskRequest();
        request.setName(label);
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setSource(copyConfig());
        request.setTarget(request.getSource());
        request.setBatchSize(MigrationConstants.MIN_BATCH_SIZE);
        request.setSteps(Collections.emptyList());
        request.setArchive(archive);
        return new MigrationTask(prefix + "-" + label, request);
    }

    /** Params: task 当前任务。 Returns: 不含连接配置的任务日志文本。 */
    private String taskLogs(MigrationTask task) {
        return String.valueOf(task.snapshot(true, 0L).get("logs"));
    }

    /** Params: label 元数据标签；sql 零行 SQL。 Returns: 实际驱动返回的字段，不伪造或改写。 */
    private Map<String, Column> columns(String label, String sql) throws Exception {
        Map<String, Column> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData metadata = rows.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                Column column = new Column(metadata.getColumnLabel(i), metadata.getColumnType(i),
                    metadata.getColumnTypeName(i), metadata.getPrecision(i), metadata.getScale(i),
                    metadata.isNullable(i));
                result.put(column.name.toUpperCase(Locale.ROOT), column);
                operations.add("-- actual metadata [" + label + "] " + column.describe()
                    + "；origin=" + origin(metadata, i));
            }
        }
        return result;
    }

    /** Params: metadata 实际查询字段；index 字段序号。 Returns: 驱动报告的物理来源，不可读时明确说明。 */
    private String origin(ResultSetMetaData metadata, int index) {
        try {
            return "catalog=" + metadata.getCatalogName(index) + ",schema=" + metadata.getSchemaName(index)
                + ",table=" + metadata.getTableName(index) + ",column=" + metadata.getColumnName(index);
        } catch (Exception exception) {
            return "<unavailable:" + safe(exception) + ">";
        }
    }

    /** Params: physical/query 两侧实际元数据。 Returns: 是否仅含允许的未知查询标记，其余定义严格相符。 */
    private boolean compatibleQuery(Map<String, Column> physical, Map<String, Column> query) {
        if (!physical.keySet().equals(query.keySet())) {
            return false;
        }
        for (String name : physical.keySet()) {
            Column left = physical.get(name);
            Column right = query.get(name);
            boolean exactFamily = numeric(left) && numeric(right);
            boolean sameType = exactFamily || (left.jdbc == right.jdbc && left.type.equalsIgnoreCase(right.type));
            boolean unknown = exactFamily && right.precision == UNKNOWN_PRECISION && right.scale == UNKNOWN_SCALE;
            if (!sameType || (!unknown && (left.precision != right.precision || left.scale != right.scale))) {
                return false;
            }
        }
        return true;
    }

    /** Params: columns 驱动元数据。 Returns: 实际使用未知数值精度标记的字段数量。 */
    private int unknownCount(Map<String, Column> columns) {
        int count = 0;
        for (Column column : columns.values()) {
            if (numeric(column) && column.precision == UNKNOWN_PRECISION && column.scale == UNKNOWN_SCALE) {
                count++;
            }
        }
        return count;
    }

    /** Params: exception 候选查询异常。 Returns: 无；连接故障不能作为语法候选失败继续探测。 */
    private void rejectConnectionFailure(Exception exception) throws Exception {
        Throwable current = exception;
        Set<Throwable> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (current != null && seen.add(current)) {
            if (current instanceof SQLNonTransientConnectionException || current instanceof SQLRecoverableException
                || current instanceof SQLTransientConnectionException) {
                throw exception;
            }
            if (current instanceof SQLException) {
                SQLException sql = (SQLException)current;
                if (sql.getSQLState() != null && sql.getSQLState().startsWith(CONNECTION_STATE_PREFIX)) {
                    throw exception;
                }
            }
            current = current.getCause();
        }
    }

    /** Params: column 实际字段。 Returns: 是否为达梦有限精确数值族。 */
    private boolean numeric(Column column) {
        return (column.jdbc == Types.NUMERIC || column.jdbc == Types.DECIMAL)
            && EXACT_NUMERIC_TYPES.contains(column.type.toUpperCase(Locale.ROOT));
    }

    /** Params: columns 物理字段。 Returns: 包含类型、精度、小数位和可空性的严格定义映射。 */
    private Map<String, String> physicalDefinitions(Map<String, Column> columns) {
        Map<String, String> definitions = new LinkedHashMap<>();
        for (Column column : columns.values()) {
            definitions.put(column.name, column.describe());
        }
        return definitions;
    }

    /** Params: 无。 Returns: 只统计指定 Schema 下本轮两个精确表名。 */
    private long remaining() throws Exception {
        String sql = "SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, source);
            statement.setString(3, target);
            try (ResultSet rows = statement.executeQuery()) {
                check(rows.next(), "隔离表计数必须返回结果");
                return rows.getLong(1);
            }
        }
    }

    /** Params: sql 本轮 DDL/DML。 Returns: 无。 */
    private void update(String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** Params: sql 本轮计数查询。 Returns: 标量计数。 */
    private long count(String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            check(rows.next(), "统计必须返回结果");
            return rows.getLong(1);
        }
    }

    /** Params: physical 唯一真实连接。 Returns: 记录所有应用调用、保留物理连接所有权的代理。 */
    private Connection trace(Connection physical) {
        return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (CLOSE.equals(name)) {
                    operations.add("-- JDBC logical close; sole physical connection retained by fixture");
                    return null;
                }
                Object result = invoke(method, physical, args == null ? new Object[0] : args);
                if (result instanceof Statement) {
                    String sql = args != null && args.length > FIRST_ARGUMENT ? (String)args[FIRST_ARGUMENT] : null;
                    return traceStatement((Statement)result, sql);
                }
                if (result instanceof DatabaseMetaData) {
                    DatabaseMetaData metadata = (DatabaseMetaData)result;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                        (metaProxy, metaMethod, metaArgs) -> {
                            operations.add("-- JDBC metadata " + metaMethod.getName() + " " + redact(Arrays.toString(metaArgs)));
                            return invoke(metaMethod, metadata, metaArgs == null ? new Object[0] : metaArgs);
                        });
                }
                if (COMMIT.equals(name) || ROLLBACK.equals(name) || AUTO_COMMIT.equals(name) || SET_SCHEMA.equals(name)) {
                    operations.add("-- JDBC " + name + " " + redact(Arrays.toString(args)));
                }
                return result;
            });
    }

    /** Params: delegate 真实语句；preparedSql 预编译模板。 Returns: 带完整绑定、批次及执行结果记录的语句。 */
    private Statement traceStatement(Statement delegate, String preparedSql) {
        Map<Integer, Object> binds = new LinkedHashMap<>();
        List<Map<Integer, Object>> batches = new ArrayList<>();
        Class<?> type = delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement)Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                String name = method.getName();
                if (GET_METADATA.equals(name)) {
                    operations.add("-- JDBC PreparedStatement.getMetaData; sql=" + preparedSql);
                }
                if (name.startsWith(SET_PREFIX) && args != null && args.length >= BIND_ARGUMENTS
                    && args[FIRST_ARGUMENT] instanceof Integer) {
                    binds.put((Integer)args[FIRST_ARGUMENT], SET_NULL.equals(name) ? null : args[SECOND_ARGUMENT]);
                }
                if (ADD_BATCH.equals(name)) {
                    batches.add(new LinkedHashMap<>(binds));
                }
                boolean execute = name.startsWith(EXECUTE_PREFIX);
                if (execute) {
                    String sql = preparedSql != null ? preparedSql : (String)args[FIRST_ARGUMENT];
                    operations.add(sql + ";" + (binds.isEmpty() ? "" : " -- bind=" + redact(binds.toString()))
                        + (batches.isEmpty() ? "" : " -- batches=" + redact(batches.toString())));
                    if (MUTATING_SQL.stream().anyMatch(sql.trim().toUpperCase(Locale.ROOT)::startsWith)) {
                        businessMutations++;
                    }
                }
                Object result;
                try {
                    result = invoke(method, delegate, args == null ? new Object[0] : args);
                    if (execute) {
                        operations.add("-- execution result: " + (result instanceof ResultSet ? "ResultSet returned"
                            : String.valueOf(result)));
                    }
                } catch (Exception exception) {
                    if (execute) {
                        operations.add("-- execution failed: " + safe(exception));
                    }
                    throw exception;
                }
                if (CLEAR_BATCH.equals(name) || EXECUTE_BATCH.equals(name)) {
                    batches.clear();
                }
                return result;
            });
    }

    /** Params: method/target/args 反射调用参数。 Returns: 解包后的真实方法结果。 */
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

    /** Params: physical 管理连接；original 主流程异常。 Returns: 无；逐表尽力清理并保存完整记录。 */
    private void cleanup(Connection physical, Throwable original) throws Exception {
        Throwable failure = null;
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
            } else {
                operations.add("-- JDBC rollback skipped: autoCommit=true");
            }
        } catch (Throwable exception) {
            failure = exception;
        }
        List<String> cleanup = new ArrayList<>(owned);
        Collections.reverse(cleanup);
        for (String table : cleanup) {
            try {
                update("DROP TABLE " + table);
                owned.remove(table);
            } catch (Throwable exception) {
                failure = accumulate(failure, exception);
                observations.add("隔离表清理失败：" + table + "，原因=" + safe(exception));
            }
        }
        try {
            long remaining = remaining();
            operations.add("-- cleanup catalog result: " + remaining);
            check(remaining == ZERO_ROWS, "本轮隔离表仍有残留");
            observations.add("两张隔离表全部清理，精确表名目录查询残留数量=0。");
            System.out.println("[CLEANUP] exact owned tables remaining=0");
        } catch (Throwable exception) {
            failure = accumulate(failure, exception);
        }
        try {
            writeRecord();
        } catch (Throwable exception) {
            failure = accumulate(failure, exception);
        } finally {
            try {
                physical.close();
            } catch (Throwable exception) {
                failure = accumulate(failure, exception);
            }
        }
        if (failure != null) {
            if (original != null) {
                original.addSuppressed(new IllegalStateException("清理/记录异常：" + safe(failure)));
            } else {
                throw new IllegalStateException("达梦隔离测试清理或记录失败：" + safe(failure));
            }
        }
    }

    /** Params: 无。 Returns: 无；只追加本轮证据，不覆盖已有记录，不保存私有端点或凭据。 */
    private void writeRecord() throws Exception {
        StringBuilder text = new StringBuilder("\n# 达梦查询元数据真实验证\n\n日期：2026-10-28；前缀：`")
            .append(prefix).append("`。仅一次物理登录；公开执行服务只复用该连接的逻辑代理。\n\n")
            .append("驱动元数据真实读取，不伪造 precision/scale。以下记录全部应用 SQL、绑定参数、JDBC 元数据 API、")
            .append("执行结果及清理顺序；驱动内部初始化和元数据内部 SQL 无法获取原文。")
            .append("Schema/账号/地址/密码以环境占位符脱敏，Schema 绑定使用 <SCHEMA>。\n\n");
        for (String observation : observations) {
            text.append("- ").append(redact(observation)).append('\n');
        }
        text.append("\n```sql\n");
        for (int i = 0; i < operations.size(); i++) {
            text.append("-- operation ").append(i + 1).append('\n').append(redact(operations.get(i))).append('\n');
        }
        text.append("```\n");
        Files.write(Paths.get(requiredEnv("LDM_TEST_SQL_RECORD")), text.toString().getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Params: 无。 Returns: 环境配置，密码不在环境或源码中保存。 */
    private DatabaseConfig configuration() {
        DatabaseConfig database = new DatabaseConfig();
        database.setType(MigrationConstants.DB_DM);
        database.setHost(requiredEnv("LDM_TEST_DM_HOST"));
        database.setPort(Integer.valueOf(requiredEnv("LDM_TEST_DM_PORT")));
        database.setUsername(requiredEnv("LDM_TEST_DM_USER"));
        database.setDatabase(optionalEnv("LDM_TEST_DM_SCHEMA", ""));
        return database;
    }

    /** Params: 无。 Returns: 本次确认请求独立使用的连接配置副本。 */
    private DatabaseConfig copyConfig() {
        DatabaseConfig database = configuration();
        database.setPassword(config.getPassword());
        return database;
    }

    /** Params: exception 原始异常。 Returns: 脱敏后的单行错误说明。 */
    private String safe(Throwable exception) {
        return redact(SqlSupport.safeMessage(exception)).replace('\r', ' ').replace('\n', ' ');
    }

    /** Params: value 记录内容。 Returns: 不含真实端点、账号、Schema 或密码的内容。 */
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

    /** Params: previous/next 异常。 Returns: 首个异常并保留后续抑制异常。 */
    private Throwable accumulate(Throwable previous, Throwable next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }

    /** Params: key 必填环境变量名。 Returns: 非空变量值。 */
    private String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable: " + key);
        }
        return value.trim();
    }

    /** Params: key 可选变量名；fallback 缺省值。 Returns: 变量或缺省值。 */
    private String optionalEnv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** Params: condition 条件；message 失败说明。 Returns: 无。 */
    private void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Author: Codex. date: 2026-10-28. */
    private static final class Column {
        private final String name;
        private final int jdbc;
        private final String type;
        private final int precision;
        private final int scale;
        private final int nullable;

        /** Params: name/jdbc/type/precision/scale/nullable 驱动实际字段定义。 Returns: 元数据记录。 */
        private Column(String name, int jdbc, String type, int precision, int scale, int nullable) {
            this.name = name;
            this.jdbc = jdbc;
            this.type = type;
            this.precision = precision;
            this.scale = scale;
            this.nullable = nullable;
        }

        /** Params: 无。 Returns: 可审阅的完整字段定义。 */
        private String describe() {
            return name + "=" + type + "(" + precision + "," + scale + "),jdbcType=" + jdbc + ",nullable=" + nullable;
        }
    }
}
