package com.hundsun.lightdbmigration.service;

import com.hundsun.lightdbmigration.support.MigrationConstants;
import com.hundsun.lightdbmigration.support.SqlSupport;
import com.hundsun.lightdbmigration.support.SqlSupport.QualifiedName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * 在达梦实际写入目标表之前启用当前会话的自增列显式赋值。
 * Author: Codex
 * date: 2026-10-22
 */
final class DamengIdentitySupport {

    private DamengIdentitySupport() {
    }

    /**
     * 每次实际写入前设置目标表，支持同一事务中归档父子表的来回切换。
     * Params: task 当前任务；connection 当前事务连接；table 目标表；required 是否包含显式写入的达梦自增列。
     * Returns: 无；执行失败时抛出异常，由调用方回滚当前批次。
     */
    static void enable(MigrationTask task, Connection connection, QualifiedName table, boolean required)
        throws SQLException {
        if (!required) {
            return;
        }
        String quotedTable = SqlSupport.quoteIdentifier(table.getTable().toUpperCase(Locale.ROOT),
            MigrationConstants.DB_DM);
        String qualifiedName = table.getSchema() == null ? quotedTable
            : SqlSupport.quoteIdentifier(table.getSchema().toUpperCase(Locale.ROOT), MigrationConstants.DB_DM)
                + "." + quotedTable;
        String sql = "SET IDENTITY_INSERT " + qualifiedName + " ON";
        task.backendSql("SQL执行[达梦保留原自增列值]：" + sql);
        try (Statement statement = connection.createStatement()) {
            task.setCurrentStatement(statement);
            try {
                statement.execute(sql);
            } finally {
                task.clearCurrentStatement(statement);
            }
        }
    }
}
