package com.hundsun.lightdbmigration.model;

public class ArchiveRelationRequest {

    private String childColumn;
    private String mainColumn;

    public String getChildColumn() {
        return childColumn;
    }

    public void setChildColumn(String childColumn) {
        this.childColumn = childColumn;
    }

    public String getMainColumn() {
        return mainColumn;
    }

    public void setMainColumn(String mainColumn) {
        this.mainColumn = mainColumn;
    }
}
