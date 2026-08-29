package com.hundsun.lightdbmigration.model;

public class MigrationStepRequest {

    private String name;
    private String sourceSql;
    private String targetTable;
    private String cursorColumn;
    private String insertMode;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
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

    public String getInsertMode() {
        return insertMode;
    }

    public void setInsertMode(String insertMode) {
        this.insertMode = insertMode;
    }
}
