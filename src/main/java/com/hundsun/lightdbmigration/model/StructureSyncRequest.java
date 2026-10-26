package com.hundsun.lightdbmigration.model;

/**
 * 用户确认后的结构建议执行请求，不接受客户端 SQL 文本。
 * Author: Codex
 * date: 2026-10-26
 */
public class StructureSyncRequest {

    private Boolean confirmed;
    private String scriptDigest;
    private DatabaseConfig database;

    /** Params: 无。 Returns: 用户是否完成确认。 */
    public Boolean getConfirmed() {
        return confirmed;
    }

    /** Params: confirmed 用户确认标志。 Returns: 无。 */
    public void setConfirmed(Boolean confirmed) {
        this.confirmed = confirmed;
    }

    /** Params: 无。 Returns: 页面显示的脚本摘要。 */
    public String getScriptDigest() {
        return scriptDigest;
    }

    /** Params: scriptDigest 页面显示的脚本摘要。 Returns: 无。 */
    public void setScriptDigest(String scriptDigest) {
        this.scriptDigest = scriptDigest;
    }

    /** Params: 无。 Returns: 本次操作临时使用的目标连接配置。 */
    public DatabaseConfig getDatabase() {
        return database;
    }

    /** Params: database 临时连接配置。 Returns: 无。 */
    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }
}
