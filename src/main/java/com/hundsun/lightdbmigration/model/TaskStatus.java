package com.hundsun.lightdbmigration.model;

public enum TaskStatus {
    CREATED,
    QUEUED,
    VALIDATING,
    RUNNING,
    PAUSING,
    PAUSED,
    STOPPING,
    TERMINATED,
    SUCCEEDED,
    FAILED
}
