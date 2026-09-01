package com.hundsun.lightdbmigration.support;

public final class MigrationConstants {

    public static final String DB_MYSQL = "MYSQL";
    public static final String DB_ORACLE = "ORACLE";
    public static final String ORACLE_SERVICE = "SERVICE";
    public static final String ORACLE_SID = "SID";
    public static final String INSERT_IGNORE = "IGNORE_DUPLICATE";
    public static final String INSERT_STRICT = "STRICT_INSERT";
    public static final String TASK_TYPE_MIGRATION = "MIGRATION";
    public static final String TASK_TYPE_ARCHIVE = "ARCHIVE";
    public static final String ARCHIVE_MAIN_NODE_ID = "MAIN";
    public static final String ARCHIVE_MAIN_NODE_PREFIX = "MAIN_";
    public static final String ARCHIVE_CHILD_NODE_PREFIX = "CHILD_";
    public static final int DEFAULT_MYSQL_PORT = 3306;
    public static final int DEFAULT_ORACLE_PORT = 1521;
    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 10000;
    public static final int MAX_ARCHIVE_BATCH_SIZE = 500;
    public static final int MAX_ARCHIVE_RELATIONS = 5;
    public static final int MIN_ARCHIVE_MAIN_TABLES = 1;
    public static final int MAX_STEPS = 50;
    public static final int MAX_TASKS = 100;
    public static final int MAX_TASK_LOGS = 5000;
    public static final int CONNECTION_TIMEOUT_SECONDS = 15;
    public static final int EXECUTOR_SIZE = 4;

    private MigrationConstants() {
    }
}
