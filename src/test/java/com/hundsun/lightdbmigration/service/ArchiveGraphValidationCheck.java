package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.model.ArchiveRelationRequest;
import com.hundsun.lightdbmigration.model.ArchiveRequest;
import com.hundsun.lightdbmigration.model.ArchiveTableRequest;
import com.hundsun.lightdbmigration.model.CreateTaskRequest;
import com.hundsun.lightdbmigration.model.DatabaseConfig;
import com.hundsun.lightdbmigration.support.MigrationConstants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ArchiveGraphValidationCheck {

    private ArchiveGraphValidationCheck() {
    }

    public static void main(String[] args) throws Exception {
        TaskManager taskManager = new TaskManager(new MigrationEngine(), new ArchiveEngine(), null);
        try {
            Method validateRequest = TaskManager.class.getDeclaredMethod("validateRequest", CreateTaskRequest.class);
            validateRequest.setAccessible(true);
            verifyBlankCursorAccepted(taskManager, validateRequest);
            verifyMultipleMainTablesAccepted(taskManager, validateRequest);
            verifyBackendSqlLogBoundary(taskManager, validateRequest);
            verifyTopologicalSort(taskManager, validateRequest);
            verifyCycleRejected(taskManager, validateRequest);
            verifyMissingParentRejected(taskManager, validateRequest);
            System.out.println("ARCHIVE_GRAPH_VALIDATION_CHECK_PASSED");
        } finally {
            taskManager.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyBackendSqlLogBoundary(TaskManager taskManager, Method validateRequest)
        throws Exception {
        CreateTaskRequest request = baseRequest();
        invoke(taskManager, validateRequest, request);
        MigrationTask task = new MigrationTask("backend-sql-boundary", request);
        task.backendSql("SQL执行[边界验证]：SELECT 1；参数=[]");
        List<java.util.Map<String, Object>> logs = (List<java.util.Map<String, Object>>)task.snapshot(true, 0L)
            .get("logs");
        for (java.util.Map<String, Object> log : logs) {
            if (String.valueOf(log.get("message")).contains("SQL执行[")) {
                throw new AssertionError("后台SQL被错误加入前端任务日志");
            }
        }
        System.out.println("ARCHIVE_SQL_BACKEND_ONLY_BOUNDARY_PASSED");
    }

    private static void verifyMultipleMainTablesAccepted(TaskManager taskManager, Method validateRequest)
        throws Exception {
        CreateTaskRequest request = baseRequest();
        ArchiveTableRequest firstMain = request.getArchive().getMainTable();
        firstMain.setNodeId("ROOT_A");
        ArchiveTableRequest secondMain = main("ROOT_B", "GRAPH_MAIN_B", "GRAPH_MAIN_B_H");
        request.getArchive().setMainTables(Arrays.asList(firstMain, secondMain));
        ArchiveTableRequest child = child("C1", "ROOT_B", "GRAPH_CHILD_B", "GRAPH_CHILD_B_H",
            "MAIN_ID", "ID");
        request.getArchive().setChildTables(Collections.singletonList(child));
        invoke(taskManager, validateRequest, request);
        List<ArchiveTableRequest> mainTables = request.getArchive().getMainTables();
        if (mainTables.size() != 2 || !"ROOT_A".equals(mainTables.get(0).getNodeId())
            || !"ROOT_B".equals(mainTables.get(1).getNodeId()) || !"ROOT_B".equals(child.getParentNodeId())) {
            throw new AssertionError("多主业务表节点或从表归属关系未保留");
        }
        System.out.println("ARCHIVE_MULTIPLE_MAIN_TABLES_VALIDATION_PASSED");
    }

    private static void verifyBlankCursorAccepted(TaskManager taskManager, Method validateRequest) throws Exception {
        CreateTaskRequest request = baseRequest();
        ArchiveTableRequest child = child("C1", MigrationConstants.ARCHIVE_MAIN_NODE_ID,
            "BLANK_CURSOR_CHILD", "BLANK_CURSOR_CHILD_H", "MAIN_ID", "ID");
        request.getArchive().setChildTables(Collections.singletonList(child));
        invoke(taskManager, validateRequest, request);
        if (request.getArchive().getMainTable().getCursorColumn() != null || child.getCursorColumn() != null) {
            throw new AssertionError("归档任务创建阶段不应强制填写唯一游标字段");
        }
        System.out.println("ARCHIVE_BLANK_CURSOR_REQUEST_PASSED");
    }

    private static void verifyTopologicalSort(TaskManager taskManager, Method validateRequest) throws Exception {
        CreateTaskRequest request = baseRequest();
        ArchiveTableRequest firstLevel = child("C1", MigrationConstants.ARCHIVE_MAIN_NODE_ID,
            "GRAPH_CHILD_ONE", "GRAPH_CHILD_ONE_H", "MAIN_ID", "ID");
        ArchiveTableRequest secondLevel = child("C2", "C1", "GRAPH_CHILD_TWO", "GRAPH_CHILD_TWO_H",
            "CHILD1_ID", "ID");
        request.getArchive().setChildTables(Arrays.asList(secondLevel, firstLevel));
        invoke(taskManager, validateRequest, request);
        List<ArchiveTableRequest> sorted = request.getArchive().getChildTables();
        if (sorted.size() != 2 || !"C1".equals(sorted.get(0).getNodeId())
            || !"C2".equals(sorted.get(1).getNodeId())) {
            throw new AssertionError("从表节点没有按父节点优先拓扑排序");
        }
        System.out.println("ARCHIVE_GRAPH_TOPOLOGICAL_SORT_PASSED");
    }

    private static void verifyCycleRejected(TaskManager taskManager, Method validateRequest) throws Exception {
        CreateTaskRequest request = baseRequest();
        request.getArchive().setChildTables(Arrays.asList(
            child("C1", "C2", "CYCLE_ONE", "CYCLE_ONE_H", "PARENT_ID", "ID"),
            child("C2", "C1", "CYCLE_TWO", "CYCLE_TWO_H", "PARENT_ID", "ID")));
        expectRejected(taskManager, validateRequest, request, "循环");
        System.out.println("ARCHIVE_GRAPH_CYCLE_REJECTION_PASSED");
    }

    private static void verifyMissingParentRejected(TaskManager taskManager, Method validateRequest) throws Exception {
        CreateTaskRequest request = baseRequest();
        request.getArchive().setChildTables(Collections.singletonList(
            child("C1", "UNKNOWN", "MISSING_PARENT", "MISSING_PARENT_H", "PARENT_ID", "ID")));
        expectRejected(taskManager, validateRequest, request, "上级节点不存在");
        System.out.println("ARCHIVE_GRAPH_MISSING_PARENT_REJECTION_PASSED");
    }

    private static CreateTaskRequest baseRequest() {
        DatabaseConfig database = new DatabaseConfig();
        database.setType(MigrationConstants.DB_MYSQL);
        database.setHost("127.0.0.1");
        database.setPort(MigrationConstants.DEFAULT_MYSQL_PORT);
        database.setDatabase("graph_validation");
        database.setUsername("validation_user");
        database.setPassword("validation_only");

        ArchiveTableRequest mainTable = main(null, "GRAPH_MAIN", "GRAPH_MAIN_H");

        ArchiveRequest archive = new ArchiveRequest();
        archive.setConfirmSourceDelete(Boolean.TRUE);
        archive.setMainTable(mainTable);
        archive.setChildTables(new ArrayList<ArchiveTableRequest>());

        CreateTaskRequest request = new CreateTaskRequest();
        request.setTaskType(MigrationConstants.TASK_TYPE_ARCHIVE);
        request.setName("归档图校验");
        request.setBatchSize(10);
        request.setSource(database);
        request.setTarget(database);
        request.setArchive(archive);
        return request;
    }

    private static ArchiveTableRequest main(String nodeId, String sourceTable, String targetTable) {
        ArchiveTableRequest mainTable = new ArchiveTableRequest();
        mainTable.setName(nodeId == null ? "主业务表" : nodeId);
        mainTable.setNodeId(nodeId);
        mainTable.setSourceTable(sourceTable);
        mainTable.setTargetTable(targetTable);
        mainTable.setCursorColumn(null);
        mainTable.setSourceSql("SELECT ID FROM " + sourceTable);
        return mainTable;
    }

    private static ArchiveTableRequest child(String nodeId, String parentNodeId, String sourceTable,
        String targetTable, String childColumn, String parentColumn) {
        ArchiveRelationRequest relation = new ArchiveRelationRequest();
        relation.setChildColumn(childColumn);
        relation.setMainColumn(parentColumn);
        ArchiveTableRequest child = new ArchiveTableRequest();
        child.setName(nodeId);
        child.setNodeId(nodeId);
        child.setParentNodeId(parentNodeId);
        child.setSourceTable(sourceTable);
        child.setTargetTable(targetTable);
        child.setCursorColumn(null);
        child.setRelations(Collections.singletonList(relation));
        return child;
    }

    private static void expectRejected(TaskManager taskManager, Method validateRequest, CreateTaskRequest request,
        String expectedMessage) throws Exception {
        try {
            invoke(taskManager, validateRequest, request);
            throw new AssertionError("预期归档图配置被拒绝：" + expectedMessage);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() == null || !exception.getMessage().contains(expectedMessage)) {
                throw new AssertionError("归档图拒绝原因不正确：" + exception.getMessage());
            }
        }
    }

    private static void invoke(TaskManager taskManager, Method validateRequest, CreateTaskRequest request)
        throws Exception {
        try {
            validateRequest.invoke(taskManager, request);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalArgumentException) {
                throw (IllegalArgumentException)cause;
            }
            throw exception;
        }
    }
}
