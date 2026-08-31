package com.hundsun.lightdbmigration.model;

import java.util.List;

public class ArchiveTableRequest {

    private String name;
    private String nodeId;
    private String parentNodeId;
    private String sourceTable;
    private String targetTable;
    private String cursorColumn;
    private String sourceSql;
    private List<ArchiveRelationRequest> relations;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getParentNodeId() {
        return parentNodeId;
    }

    public void setParentNodeId(String parentNodeId) {
        this.parentNodeId = parentNodeId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public String getCursorColumn() {
        return cursorColumn;
    }

    public void setCursorColumn(String cursorColumn) {
        this.cursorColumn = cursorColumn;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
    }

    public List<ArchiveRelationRequest> getRelations() {
        return relations;
    }

    public void setRelations(List<ArchiveRelationRequest> relations) {
        this.relations = relations;
    }
}
