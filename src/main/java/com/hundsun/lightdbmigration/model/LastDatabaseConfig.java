package com.hundsun.lightdbmigration.model;

public class LastDatabaseConfig {

    private DatabaseConfig source;
    private DatabaseConfig target;

    public LastDatabaseConfig() {
    }

    public LastDatabaseConfig(DatabaseConfig source, DatabaseConfig target) {
        this.source = source;
        this.target = target;
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
}
