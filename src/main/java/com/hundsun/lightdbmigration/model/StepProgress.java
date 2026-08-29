package com.hundsun.lightdbmigration.model;

import java.util.LinkedHashMap;
import java.util.Map;

public class StepProgress {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";

    private final int index;
    private final String name;
    private final String targetTable;
    private final String cursorColumn;
    private final String insertMode;
    private volatile String status = STATUS_PENDING;
    private volatile long totalRows;
    private volatile long processedRows;
    private volatile long insertedRows;
    private volatile long ignoredRows;
    private volatile long batches;

    public StepProgress(int index, MigrationStepRequest request) {
        this.index = index;
        this.name = request.getName();
        this.targetTable = request.getTargetTable();
        this.cursorColumn = request.getCursorColumn();
        this.insertMode = request.getInsertMode();
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public synchronized void addBatch(long processed, long inserted, long ignored) {
        this.processedRows += processed;
        this.insertedRows += inserted;
        this.ignoredRows += ignored;
        this.batches++;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("index", index);
        result.put("name", name);
        result.put("targetTable", targetTable);
        result.put("cursorColumn", cursorColumn);
        result.put("insertMode", insertMode);
        result.put("status", status);
        result.put("totalRows", totalRows);
        result.put("processedRows", processedRows);
        result.put("insertedRows", insertedRows);
        result.put("ignoredRows", ignoredRows);
        result.put("batches", batches);
        long percent = totalRows <= 0L ? (STATUS_SUCCEEDED.equals(status) ? 100L : 0L)
            : Math.min(100L, processedRows * 100L / totalRows);
        result.put("percent", percent);
        return result;
    }
}
