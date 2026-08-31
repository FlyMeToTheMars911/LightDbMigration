package com.hundsun.lightdbmigration.model;

import java.util.List;

public class ArchiveRequest {

    private Boolean confirmSourceDelete;
    private ArchiveTableRequest mainTable;
    private List<ArchiveTableRequest> childTables;

    public Boolean getConfirmSourceDelete() {
        return confirmSourceDelete;
    }

    public void setConfirmSourceDelete(Boolean confirmSourceDelete) {
        this.confirmSourceDelete = confirmSourceDelete;
    }

    public ArchiveTableRequest getMainTable() {
        return mainTable;
    }

    public void setMainTable(ArchiveTableRequest mainTable) {
        this.mainTable = mainTable;
    }

    public List<ArchiveTableRequest> getChildTables() {
        return childTables;
    }

    public void setChildTables(List<ArchiveTableRequest> childTables) {
        this.childTables = childTables;
    }
}
