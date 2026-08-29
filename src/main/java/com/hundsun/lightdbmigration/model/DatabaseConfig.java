package com.hundsun.lightdbmigration.model;

public class DatabaseConfig {

    private String type;
    private String host;
    private Integer port;
    private String database;
    private String oracleMode;
    private String username;
    private String password;
    private String jdbcUrl;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getOracleMode() {
        return oracleMode;
    }

    public void setOracleMode(String oracleMode) {
        this.oracleMode = oracleMode;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public void clearPassword() {
        this.password = null;
    }
}
