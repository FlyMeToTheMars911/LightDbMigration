package com.hundsun.lightdbmigration.model;

import java.util.List;

public class CreateTaskRequest {

    private String name;
    private Integer batchSize;
    private DatabaseConfig source;
    private DatabaseConfig target;
    private List<MigrationStepRequest> steps;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
}
