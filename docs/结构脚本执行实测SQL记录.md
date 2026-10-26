# 结构脚本确认执行 MySQL SQL 记录

日期：2026-10-26。单次真实登录，关闭自动重连；前缀：`LDM_SE_1A177D3DC1C`。服务的连接开器注入唯一真实连接的记录代理，公开校验/执行路径不替换。

记录全部应用侧 SQL 和元数据 API；驱动内部登录初始化与元数据内部 SQL 无法获得原文。本轮预检和DDL不使用绑定参数，不记录连接凭据。

- 迁移：未确认零连接；确认执行两条 DDL 成功；扩容、增列及非空约束正确；重复请求拒绝；未执行数据任务。
- 归档：未确认零连接；确认执行两条 DDL 成功；扩容、增列及非空约束正确；重复请求拒绝；未执行数据任务。
- 部分失败：人为在预检后补入第二个字段，执行第1条成功、第2条冲突失败；记录1/2，保留已生效DDL，重复请求零连接零执行。
- 迁移、归档确认执行及部分失败后的重复拒绝全部通过；源业务数据未自动迁移或删除。
- 隔离表残留数量为 0。

```sql
-- operation 1
SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'LDM_SE_1A177D3DC1C_%';
-- operation 2
CREATE TABLE LDM_SE_1A177D3DC1C_MS (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(40) NOT NULL, AMOUNT DECIMAL(12,2) NULL) ENGINE=InnoDB;
-- operation 3
CREATE TABLE LDM_SE_1A177D3DC1C_MT (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(5) NOT NULL) ENGINE=InnoDB;
-- operation 4
INSERT INTO LDM_SE_1A177D3DC1C_MS VALUES (1,'structure-check',12.50);
-- operation 5
SELECT * FROM (SELECT * FROM LDM_SE_1A177D3DC1C_MS) LM_SOURCE WHERE 1 = 0;
-- operation 6
SELECT * FROM LDM_SE_1A177D3DC1C_MT WHERE 1 = 0;
-- operation 7
-- JDBC metadata getColumns [hs_aio_blank, null, LDM_SE_1A177D3DC1C_MT, null]
-- operation 8
SELECT COUNT(*) FROM (SELECT * FROM LDM_SE_1A177D3DC1C_MS) LM_STATS;
-- operation 9
-- StructureSyncService connection claim 1 (reused physical test connection)
-- operation 10
-- JDBC setAutoCommit [true]
-- operation 11
ALTER TABLE `LDM_SE_1A177D3DC1C_MT` MODIFY COLUMN `NAME` VARCHAR(40) NOT NULL;
-- operation 12
ALTER TABLE `LDM_SE_1A177D3DC1C_MT` ADD COLUMN `AMOUNT` DECIMAL(12,2) NULL;
-- operation 13
-- JDBC logical close; physical connection remains owned by fixture
-- operation 14
SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='LDM_SE_1A177D3DC1C_MT' AND COLUMN_NAME='AMOUNT';
-- operation 15
SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='LDM_SE_1A177D3DC1C_MT' AND COLUMN_NAME='NAME' AND CHARACTER_MAXIMUM_LENGTH=40 AND IS_NULLABLE='NO';
-- operation 16
SELECT COUNT(*) FROM LDM_SE_1A177D3DC1C_MS;
-- operation 17
SELECT COUNT(*) FROM LDM_SE_1A177D3DC1C_MT;
-- operation 18
CREATE TABLE LDM_SE_1A177D3DC1C_AS (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(40) NOT NULL, AMOUNT DECIMAL(12,2) NULL) ENGINE=InnoDB;
-- operation 19
CREATE TABLE LDM_SE_1A177D3DC1C_AT (ID INT NOT NULL PRIMARY KEY, NAME VARCHAR(5) NOT NULL) ENGINE=InnoDB;
-- operation 20
INSERT INTO LDM_SE_1A177D3DC1C_AS VALUES (1,'structure-check',12.50);
-- operation 21
SELECT * FROM `LDM_SE_1A177D3DC1C_AS` WHERE 1 = 0;
-- operation 22
SELECT * FROM `LDM_SE_1A177D3DC1C_AT` WHERE 1 = 0;
-- operation 23
SELECT * FROM (SELECT * FROM LDM_SE_1A177D3DC1C_AS) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 24
-- StructureSyncService connection claim 2 (reused physical test connection)
-- operation 25
-- JDBC setAutoCommit [true]
-- operation 26
ALTER TABLE `LDM_SE_1A177D3DC1C_AT` MODIFY COLUMN `NAME` VARCHAR(40) NOT NULL;
-- operation 27
ALTER TABLE `LDM_SE_1A177D3DC1C_AT` ADD COLUMN `AMOUNT` DECIMAL(12,2) NULL;
-- operation 28
-- JDBC logical close; physical connection remains owned by fixture
-- operation 29
SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='LDM_SE_1A177D3DC1C_AT' AND COLUMN_NAME='AMOUNT';
-- operation 30
SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='LDM_SE_1A177D3DC1C_AT' AND COLUMN_NAME='NAME' AND CHARACTER_MAXIMUM_LENGTH=40 AND IS_NULLABLE='NO';
-- operation 31
SELECT COUNT(*) FROM LDM_SE_1A177D3DC1C_AS;
-- operation 32
SELECT COUNT(*) FROM LDM_SE_1A177D3DC1C_AT;
-- operation 33
CREATE TABLE LDM_SE_1A177D3DC1C_PS (ID INT NOT NULL PRIMARY KEY, A VARCHAR(20) NULL, B VARCHAR(30) NULL) ENGINE=InnoDB;
-- operation 34
CREATE TABLE LDM_SE_1A177D3DC1C_PT (ID INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
-- operation 35
INSERT INTO LDM_SE_1A177D3DC1C_PS VALUES (1,'a','b');
-- operation 36
SELECT * FROM (SELECT * FROM LDM_SE_1A177D3DC1C_PS) LM_SOURCE WHERE 1 = 0;
-- operation 37
SELECT * FROM LDM_SE_1A177D3DC1C_PT WHERE 1 = 0;
-- operation 38
-- JDBC metadata getColumns [hs_aio_blank, null, LDM_SE_1A177D3DC1C_PT, null]
-- operation 39
SELECT COUNT(*) FROM (SELECT * FROM LDM_SE_1A177D3DC1C_PS) LM_STATS;
-- operation 40
ALTER TABLE LDM_SE_1A177D3DC1C_PT ADD COLUMN B VARCHAR(30) NULL;
-- operation 41
-- StructureSyncService connection claim 3 (reused physical test connection)
-- operation 42
-- JDBC setAutoCommit [true]
-- operation 43
ALTER TABLE `LDM_SE_1A177D3DC1C_PT` ADD COLUMN `A` VARCHAR(20) NULL;
-- operation 44
ALTER TABLE `LDM_SE_1A177D3DC1C_PT` ADD COLUMN `B` VARCHAR(30) NULL;
-- operation 45
-- JDBC logical close; physical connection remains owned by fixture
-- operation 46
SELECT COUNT(*) FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='LDM_SE_1A177D3DC1C_PT' AND COLUMN_NAME='A';
-- operation 47
SELECT COUNT(*) FROM LDM_SE_1A177D3DC1C_PS;
-- operation 48
DROP TABLE LDM_SE_1A177D3DC1C_PT;
-- operation 49
DROP TABLE LDM_SE_1A177D3DC1C_PS;
-- operation 50
DROP TABLE LDM_SE_1A177D3DC1C_AT;
-- operation 51
DROP TABLE LDM_SE_1A177D3DC1C_AS;
-- operation 52
DROP TABLE LDM_SE_1A177D3DC1C_MT;
-- operation 53
DROP TABLE LDM_SE_1A177D3DC1C_MS;
-- operation 54
SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME LIKE 'LDM_SE_1A177D3DC1C_%';
```
