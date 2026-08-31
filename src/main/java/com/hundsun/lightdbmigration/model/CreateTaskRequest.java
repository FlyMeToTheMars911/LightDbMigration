package com.hundsun.lightdbmigration.model;

import java.util.List;

public class CreateTaskRequest {

    private String name;
    private String taskType;
    private Integer batchSize;
    private DatabaseConfig source;
    private DatabaseConfig target;
    private List<MigrationStepRequest> steps;
    private ArchiveRequest archive;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public DatabaseConfig getSource() {
        return source;
    }

    public void setSource(DatabaseConfig source) {
        this.source = source;
    }

    public DatabaseConfig getTarget() {
        return target;
    }

    public void setTarget(DatabaseConfig target) {
        this.target = target;
    }

    public List<MigrationStepRequest> getSteps() {
        return steps;
    }

    public void setSteps(List<MigrationStepRequest> steps) {
        this.steps = steps;
    }

    public ArchiveRequest getArchive() {
        return archive;
    }

    public void setArchive(ArchiveRequest archive) {
        this.archive = archive;
    }
}
