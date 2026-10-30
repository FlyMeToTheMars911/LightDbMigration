
# MYSQL 源默认值同步实测

日期：2026-10-30；前缀：`LDM_DEF_E08FF2C8A9`。单次物理登录；仅本轮独立对象。驱动内部 SQL 无法取得原文；以下为全部应用 SQL、绑定参数、元数据 API、结果及清理。

- 通过：目录残留查询返回结果
- 通过：本轮六张精确独立表预先不存在
- 通过：精确表默认值目录可读取
- 通过：默认值验证行存在
- 通过：LDM_DEF_E08FF2C8A9_MS/1默认值应用正确
- 通过：失败预检同步返回建议脚本
- 通过：行数查询返回结果
- 通过：行数查询返回结果
- 通过：M失败预检零写入/删除
- 通过：实际建议条数符合预期
- 独立对象验证失败：实际建议保留空格、引号及数值默认值
- 通过：目录残留查询返回结果
- 通过：本轮精确独立表清理残留=0

```sql
-- operation 1
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_E08FF2C8A9_MS, 3=LDM_DEF_E08FF2C8A9_MT, 4=LDM_DEF_E08FF2C8A9_MC, 5=LDM_DEF_E08FF2C8A9_AS, 6=LDM_DEF_E08FF2C8A9_AT, 7=LDM_DEF_E08FF2C8A9_AC}
-- operation 2
-- execution result=ResultSet
-- operation 3
-- exact table remaining=0
-- operation 4
CREATE TABLE LDM_DEF_E08FF2C8A9_MS (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG DECIMAL(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 5
-- execution result=0
-- operation 6
CREATE TABLE LDM_DEF_E08FF2C8A9_MT (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 7
-- execution result=0
-- operation 8
INSERT INTO LDM_DEF_E08FF2C8A9_MS (ID) VALUES (1); -- bindings={}
-- operation 9
-- execution result=1
-- operation 10
INSERT INTO LDM_DEF_E08FF2C8A9_MT (ID) VALUES (1); -- bindings={}
-- operation 11
-- execution result=1
-- operation 12
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_E08FF2C8A9_MS}
-- operation 13
-- execution result=ResultSet
-- operation 14
-- actual default LDM_DEF_E08FF2C8A9_MS.ID=<SQL NULL>
-- operation 15
-- actual default LDM_DEF_E08FF2C8A9_MS.OWNER_NAME=['O''Brien']
-- operation 16
-- actual default LDM_DEF_E08FF2C8A9_MS.VIP_LEVEL=[' ']
-- operation 17
-- actual default LDM_DEF_E08FF2C8A9_MS.MANUAL_FLAG=['0']
-- operation 18
-- actual default LDM_DEF_E08FF2C8A9_MS.AUTO_USED_FLAG=[0]
-- operation 19
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_E08FF2C8A9_MS WHERE ID=?; -- bindings={1=1}
-- operation 20
-- execution result=ResultSet
-- operation 21
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 22
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_E08FF2C8A9_MS
-- operation 23
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=ldm_def_e08ff2c8a9_ms}
-- operation 24
-- execution result=ResultSet
-- operation 25
SELECT * FROM LDM_DEF_E08FF2C8A9_MT WHERE 1 = 0; -- bindings={}
-- operation 26
-- execution result=ResultSet
-- operation 27
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_E08FF2C8A9_MT}
-- operation 28
-- execution result=ResultSet
-- operation 29
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_E08FF2C8A9_MS) LM_STATS; -- bindings={}
-- operation 30
-- execution result=ResultSet
-- operation 31
-- expected preparation failure: 表结构校验未通过，共发现 4 个问题，已一次性输出到任务日志
-- operation 32
SELECT COUNT(*) FROM LDM_DEF_E08FF2C8A9_MS; -- bindings={}
-- operation 33
-- execution result=ResultSet
-- operation 34
-- count=1
-- operation 35
SELECT COUNT(*) FROM LDM_DEF_E08FF2C8A9_MT; -- bindings={}
-- operation 36
-- execution result=ResultSet
-- operation 37
-- count=1
-- operation 38
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：MYSQL
-- 重要：本脚本根据源查询结果及已确认物理来源元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_E08FF2C8A9_MS）：结构不一致：字段 OWNER_NAME 目标长度 12 小于源长度 30；源查询映射=VARCHAR(30) NOT NULL，目标=VARCHAR(12) NOT NULL
-- 2. 步骤 1（LDM_DEF_E08FF2C8A9_MS）：源查询字段 VIP_LEVEL 在目标表 LDM_DEF_E08FF2C8A9_MT 中不存在
-- 3. 步骤 1（LDM_DEF_E08FF2C8A9_MS）：源查询字段 MANUAL_FLAG 在目标表 LDM_DEF_E08FF2C8A9_MT 中不存在
-- 4. 步骤 1（LDM_DEF_E08FF2C8A9_MS）：源查询字段 AUTO_USED_FLAG 在目标表 LDM_DEF_E08FF2C8A9_MT 中不存在

-- 建议 DDL
ALTER TABLE `LDM_DEF_E08FF2C8A9_MT` MODIFY COLUMN `OWNER_NAME` VARCHAR(30) DEFAULT '''O''''Brien''' NOT NULL;

ALTER TABLE `LDM_DEF_E08FF2C8A9_MT` ADD COLUMN `VIP_LEVEL` VARCHAR(3) DEFAULT ''' ''' NOT NULL;

ALTER TABLE `LDM_DEF_E08FF2C8A9_MT` ADD COLUMN `MANUAL_FLAG` CHAR(1) DEFAULT '''0''' NOT NULL;

ALTER TABLE `LDM_DEF_E08FF2C8A9_MT` ADD COLUMN `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL;

-- operation 39
DROP TABLE LDM_DEF_E08FF2C8A9_MT; -- bindings={}
-- operation 40
-- execution result=0
-- operation 41
DROP TABLE LDM_DEF_E08FF2C8A9_MS; -- bindings={}
-- operation 42
-- execution result=0
-- operation 43
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_E08FF2C8A9_MS, 3=LDM_DEF_E08FF2C8A9_MT, 4=LDM_DEF_E08FF2C8A9_MC, 5=LDM_DEF_E08FF2C8A9_AS, 6=LDM_DEF_E08FF2C8A9_AT, 7=LDM_DEF_E08FF2C8A9_AC}
-- operation 44
-- execution result=ResultSet
-- operation 45
-- exact table remaining=0
```

# MYSQL 源默认值同步实测

日期：2026-10-30；前缀：`LDM_DEF_0B69D2A4B4`。单次物理登录；仅本轮独立对象。驱动内部 SQL 无法取得原文；以下为全部应用 SQL、绑定参数、元数据 API、结果及清理。

- 实际数据库产品=MySQL；版本=5.5.5-10.9.5-MariaDB
- 通过：目录残留查询返回结果
- 通过：本轮六张精确独立表预先不存在
- 通过：精确表默认值目录可读取
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_MS/1默认值应用正确
- 通过：失败预检同步返回建议脚本
- 通过：行数查询返回结果
- 通过：行数查询返回结果
- 通过：M失败预检零写入/删除
- 通过：实际建议条数符合预期
- 通过：实际建议保留空格、引号及数值默认值
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_MT/1默认值应用正确
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_MT/2默认值应用正确
- 通过：M同步后完整预检通过且零写入/删除
- 通过：精确表默认值目录可读取
- 通过：精确表默认值目录可读取
- 通过：MALTER后源目标物理默认值一致
- 通过：失败预检同步返回建议脚本
- 通过：M缺表预检零写入/删除
- 通过：实际建议条数符合预期
- 通过：实际建议保留空格、引号及数值默认值
- 通过：执行对象限定为本轮精确目标
- 通过：行数查询返回结果
- 通过：CREATE 不复制任何业务行
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_MC/1默认值应用正确
- 通过：M同步后完整预检通过且零写入/删除
- 通过：精确表默认值目录可读取
- 通过：精确表默认值目录可读取
- 通过：MCREATE后源目标物理默认值一致
- 通过：精确表默认值目录可读取
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_AS/1默认值应用正确
- 通过：失败预检同步返回建议脚本
- 通过：行数查询返回结果
- 通过：行数查询返回结果
- 通过：A失败预检零写入/删除
- 通过：实际建议条数符合预期
- 通过：实际建议保留空格、引号及数值默认值
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：执行对象限定为本轮精确目标
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_AT/1默认值应用正确
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_AT/2默认值应用正确
- 通过：A同步后完整预检通过且零写入/删除
- 通过：精确表默认值目录可读取
- 通过：精确表默认值目录可读取
- 通过：AALTER后源目标物理默认值一致
- 通过：失败预检同步返回建议脚本
- 通过：A缺表预检零写入/删除
- 通过：实际建议条数符合预期
- 通过：实际建议保留空格、引号及数值默认值
- 通过：执行对象限定为本轮精确目标
- 通过：行数查询返回结果
- 通过：CREATE 不复制任何业务行
- 通过：默认值验证行存在
- 通过：LDM_DEF_0B69D2A4B4_AC/1默认值应用正确
- 通过：A同步后完整预检通过且零写入/删除
- 通过：精确表默认值目录可读取
- 通过：精确表默认值目录可读取
- 通过：ACREATE后源目标物理默认值一致
- 两种生产 prepare 路径全部通过；未启动业务迁移或归档数据循环。
- 通过：目录残留查询返回结果
- 通过：本轮精确独立表清理残留=0

```sql
-- operation 1
-- JDBC metadata getDatabaseProductName null
-- operation 2
-- JDBC metadata getDatabaseProductVersion null
-- operation 3
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MS, 3=LDM_DEF_0B69D2A4B4_MT, 4=LDM_DEF_0B69D2A4B4_MC, 5=LDM_DEF_0B69D2A4B4_AS, 6=LDM_DEF_0B69D2A4B4_AT, 7=LDM_DEF_0B69D2A4B4_AC}
-- operation 4
-- execution result=ResultSet
-- operation 5
-- exact table remaining=0
-- operation 6
CREATE TABLE LDM_DEF_0B69D2A4B4_MS (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG DECIMAL(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 7
-- execution result=0
-- operation 8
CREATE TABLE LDM_DEF_0B69D2A4B4_MT (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 9
-- execution result=0
-- operation 10
INSERT INTO LDM_DEF_0B69D2A4B4_MS (ID) VALUES (1); -- bindings={}
-- operation 11
-- execution result=1
-- operation 12
INSERT INTO LDM_DEF_0B69D2A4B4_MT (ID) VALUES (1); -- bindings={}
-- operation 13
-- execution result=1
-- operation 14
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MS}
-- operation 15
-- execution result=ResultSet
-- operation 16
-- actual default LDM_DEF_0B69D2A4B4_MS.ID=<SQL NULL>
-- operation 17
-- actual default LDM_DEF_0B69D2A4B4_MS.OWNER_NAME=['O''Brien']
-- operation 18
-- actual default LDM_DEF_0B69D2A4B4_MS.VIP_LEVEL=[' ']
-- operation 19
-- actual default LDM_DEF_0B69D2A4B4_MS.MANUAL_FLAG=['0']
-- operation 20
-- actual default LDM_DEF_0B69D2A4B4_MS.AUTO_USED_FLAG=[0]
-- operation 21
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_MS WHERE ID=?; -- bindings={1=1}
-- operation 22
-- execution result=ResultSet
-- operation 23
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 24
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_0B69D2A4B4_MS
-- operation 25
-- JDBC metadata getDatabaseProductVersion null
-- operation 26
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=ldm_def_0b69d2a4b4_ms}
-- operation 27
-- execution result=ResultSet
-- operation 28
SELECT * FROM LDM_DEF_0B69D2A4B4_MT WHERE 1 = 0; -- bindings={}
-- operation 29
-- execution result=ResultSet
-- operation 30
-- JDBC metadata getDatabaseProductVersion null
-- operation 31
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MT}
-- operation 32
-- execution result=ResultSet
-- operation 33
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_MS) LM_STATS; -- bindings={}
-- operation 34
-- execution result=ResultSet
-- operation 35
-- expected preparation failure: 表结构校验未通过，共发现 4 个问题，已一次性输出到任务日志
-- operation 36
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_MS; -- bindings={}
-- operation 37
-- execution result=ResultSet
-- operation 38
-- count=1
-- operation 39
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_MT; -- bindings={}
-- operation 40
-- execution result=ResultSet
-- operation 41
-- count=1
-- operation 42
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：MYSQL
-- 重要：本脚本根据源查询结果及已确认物理来源元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_0B69D2A4B4_MS）：结构不一致：字段 OWNER_NAME 目标长度 12 小于源长度 30；源查询映射=VARCHAR(30) NOT NULL，目标=VARCHAR(12) NOT NULL
-- 2. 步骤 1（LDM_DEF_0B69D2A4B4_MS）：源查询字段 VIP_LEVEL 在目标表 LDM_DEF_0B69D2A4B4_MT 中不存在
-- 3. 步骤 1（LDM_DEF_0B69D2A4B4_MS）：源查询字段 MANUAL_FLAG 在目标表 LDM_DEF_0B69D2A4B4_MT 中不存在
-- 4. 步骤 1（LDM_DEF_0B69D2A4B4_MS）：源查询字段 AUTO_USED_FLAG 在目标表 LDM_DEF_0B69D2A4B4_MT 中不存在

-- 建议 DDL
ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` MODIFY COLUMN `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL;

-- operation 43
ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` MODIFY COLUMN `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL; -- bindings={}
-- operation 44
-- execution result=0
-- operation 45
ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL; -- bindings={}
-- operation 46
-- execution result=0
-- operation 47
ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL; -- bindings={}
-- operation 48
-- execution result=0
-- operation 49
ALTER TABLE `LDM_DEF_0B69D2A4B4_MT` ADD COLUMN `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL; -- bindings={}
-- operation 50
-- execution result=0
-- operation 51
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_MT WHERE ID=?; -- bindings={1=1}
-- operation 52
-- execution result=ResultSet
-- operation 53
-- actual row owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 54
INSERT INTO LDM_DEF_0B69D2A4B4_MT (ID) VALUES (2); -- bindings={}
-- operation 55
-- execution result=1
-- operation 56
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_MT WHERE ID=?; -- bindings={1=2}
-- operation 57
-- execution result=ResultSet
-- operation 58
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 59
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_0B69D2A4B4_MS
-- operation 60
-- JDBC metadata getDatabaseProductVersion null
-- operation 61
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=ldm_def_0b69d2a4b4_ms}
-- operation 62
-- execution result=ResultSet
-- operation 63
SELECT * FROM LDM_DEF_0B69D2A4B4_MT WHERE 1 = 0; -- bindings={}
-- operation 64
-- execution result=ResultSet
-- operation 65
-- JDBC metadata getDatabaseProductVersion null
-- operation 66
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MT}
-- operation 67
-- execution result=ResultSet
-- operation 68
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_MS) LM_STATS; -- bindings={}
-- operation 69
-- execution result=ResultSet
-- operation 70
-- JDBC metadata getPrimaryKeys [<REDACTED>, null, LDM_DEF_0B69D2A4B4_MT]
-- operation 71
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MS}
-- operation 72
-- execution result=ResultSet
-- operation 73
-- actual default LDM_DEF_0B69D2A4B4_MS.ID=<SQL NULL>
-- operation 74
-- actual default LDM_DEF_0B69D2A4B4_MS.OWNER_NAME=['O''Brien']
-- operation 75
-- actual default LDM_DEF_0B69D2A4B4_MS.VIP_LEVEL=[' ']
-- operation 76
-- actual default LDM_DEF_0B69D2A4B4_MS.MANUAL_FLAG=['0']
-- operation 77
-- actual default LDM_DEF_0B69D2A4B4_MS.AUTO_USED_FLAG=[0]
-- operation 78
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MT}
-- operation 79
-- execution result=ResultSet
-- operation 80
-- actual default LDM_DEF_0B69D2A4B4_MT.ID=<SQL NULL>
-- operation 81
-- actual default LDM_DEF_0B69D2A4B4_MT.OWNER_NAME=['O''Brien']
-- operation 82
-- actual default LDM_DEF_0B69D2A4B4_MT.VIP_LEVEL=[' ']
-- operation 83
-- actual default LDM_DEF_0B69D2A4B4_MT.MANUAL_FLAG=['0']
-- operation 84
-- actual default LDM_DEF_0B69D2A4B4_MT.AUTO_USED_FLAG=[0]
-- operation 85
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_0B69D2A4B4_MS
-- operation 86
-- JDBC metadata getDatabaseProductVersion null
-- operation 87
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=ldm_def_0b69d2a4b4_ms}
-- operation 88
-- execution result=ResultSet
-- operation 89
SELECT * FROM LDM_DEF_0B69D2A4B4_MC WHERE 1 = 0; -- bindings={}
-- operation 90
-- operation failed=Table '<REDACTED>.ldm_def_0b69d2a4b4_mc' doesn't exist
-- operation 91
-- expected preparation failure: 表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 92
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：MYSQL
-- 重要：本脚本根据源查询结果及已确认物理来源元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_0B69D2A4B4_MS）：目标表 LDM_DEF_0B69D2A4B4_MC 无法读取字段结构：Table '<REDACTED>.ldm_def_0b69d2a4b4_mc' doesn't exist

-- 建议 DDL
-- 已确认目标表确实不存在后再执行；主键、索引和注释需要另行补充。
CREATE TABLE `LDM_DEF_0B69D2A4B4_MC` (
    `ID` INT NOT NULL,
    `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL,
    `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL,
    `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL
);

-- operation 93
CREATE TABLE `LDM_DEF_0B69D2A4B4_MC` (
    `ID` INT NOT NULL,
    `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL,
    `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL,
    `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 94
-- execution result=0
-- operation 95
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_MC; -- bindings={}
-- operation 96
-- execution result=ResultSet
-- operation 97
-- count=0
-- operation 98
INSERT INTO LDM_DEF_0B69D2A4B4_MC (ID) VALUES (1); -- bindings={}
-- operation 99
-- execution result=1
-- operation 100
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_MC WHERE ID=?; -- bindings={1=1}
-- operation 101
-- execution result=ResultSet
-- operation 102
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 103
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_0B69D2A4B4_MS
-- operation 104
-- JDBC metadata getDatabaseProductVersion null
-- operation 105
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=ldm_def_0b69d2a4b4_ms}
-- operation 106
-- execution result=ResultSet
-- operation 107
SELECT * FROM LDM_DEF_0B69D2A4B4_MC WHERE 1 = 0; -- bindings={}
-- operation 108
-- execution result=ResultSet
-- operation 109
-- JDBC metadata getDatabaseProductVersion null
-- operation 110
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MC}
-- operation 111
-- execution result=ResultSet
-- operation 112
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_MS) LM_STATS; -- bindings={}
-- operation 113
-- execution result=ResultSet
-- operation 114
-- JDBC metadata getPrimaryKeys [<REDACTED>, null, LDM_DEF_0B69D2A4B4_MC]
-- operation 115
-- JDBC metadata getIndexInfo [<REDACTED>, null, LDM_DEF_0B69D2A4B4_MC, true, false]
-- operation 116
-- JDBC metadata getPrimaryKeys [<REDACTED>, null, ldm_def_0b69d2a4b4_mc]
-- operation 117
-- JDBC metadata getIndexInfo [<REDACTED>, null, ldm_def_0b69d2a4b4_mc, true, false]
-- operation 118
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MS}
-- operation 119
-- execution result=ResultSet
-- operation 120
-- actual default LDM_DEF_0B69D2A4B4_MS.ID=<SQL NULL>
-- operation 121
-- actual default LDM_DEF_0B69D2A4B4_MS.OWNER_NAME=['O''Brien']
-- operation 122
-- actual default LDM_DEF_0B69D2A4B4_MS.VIP_LEVEL=[' ']
-- operation 123
-- actual default LDM_DEF_0B69D2A4B4_MS.MANUAL_FLAG=['0']
-- operation 124
-- actual default LDM_DEF_0B69D2A4B4_MS.AUTO_USED_FLAG=[0]
-- operation 125
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MC}
-- operation 126
-- execution result=ResultSet
-- operation 127
-- actual default LDM_DEF_0B69D2A4B4_MC.ID=<SQL NULL>
-- operation 128
-- actual default LDM_DEF_0B69D2A4B4_MC.OWNER_NAME=['O''Brien']
-- operation 129
-- actual default LDM_DEF_0B69D2A4B4_MC.VIP_LEVEL=[' ']
-- operation 130
-- actual default LDM_DEF_0B69D2A4B4_MC.MANUAL_FLAG=['0']
-- operation 131
-- actual default LDM_DEF_0B69D2A4B4_MC.AUTO_USED_FLAG=[0]
-- operation 132
CREATE TABLE LDM_DEF_0B69D2A4B4_AS (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG DECIMAL(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 133
-- execution result=0
-- operation 134
CREATE TABLE LDM_DEF_0B69D2A4B4_AT (ID INT NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 135
-- execution result=0
-- operation 136
INSERT INTO LDM_DEF_0B69D2A4B4_AS (ID) VALUES (1); -- bindings={}
-- operation 137
-- execution result=1
-- operation 138
INSERT INTO LDM_DEF_0B69D2A4B4_AT (ID) VALUES (1); -- bindings={}
-- operation 139
-- execution result=1
-- operation 140
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 141
-- execution result=ResultSet
-- operation 142
-- actual default LDM_DEF_0B69D2A4B4_AS.ID=<SQL NULL>
-- operation 143
-- actual default LDM_DEF_0B69D2A4B4_AS.OWNER_NAME=['O''Brien']
-- operation 144
-- actual default LDM_DEF_0B69D2A4B4_AS.VIP_LEVEL=[' ']
-- operation 145
-- actual default LDM_DEF_0B69D2A4B4_AS.MANUAL_FLAG=['0']
-- operation 146
-- actual default LDM_DEF_0B69D2A4B4_AS.AUTO_USED_FLAG=[0]
-- operation 147
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_AS WHERE ID=?; -- bindings={1=1}
-- operation 148
-- execution result=ResultSet
-- operation 149
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 150
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 151
-- execution result=ResultSet
-- operation 152
-- JDBC metadata getDatabaseProductVersion null
-- operation 153
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 154
-- execution result=ResultSet
-- operation 155
SELECT * FROM `LDM_DEF_0B69D2A4B4_AT` WHERE 1 = 0; -- bindings={}
-- operation 156
-- execution result=ResultSet
-- operation 157
-- JDBC metadata getDatabaseProductVersion null
-- operation 158
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AT}
-- operation 159
-- execution result=ResultSet
-- operation 160
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 161
-- execution result=ResultSet
-- operation 162
-- expected preparation failure: 归档表结构校验未通过，共发现 5 个问题，已一次性输出到任务日志
-- operation 163
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_AS; -- bindings={}
-- operation 164
-- execution result=ResultSet
-- operation 165
-- count=1
-- operation 166
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_AT; -- bindings={}
-- operation 167
-- execution result=ResultSet
-- operation 168
-- count=1
-- operation 169
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：MYSQL
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）源表与归档表字段数量不同：源表 5 个，归档表 2 个
-- 2. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）字段结构不同：OWNER_NAME，源表=VARCHAR(30,0),jdbcType=12,nullable=0,nativeType=varchar(30),default='O''Brien'，归档表=VARCHAR(12,0),jdbcType=12,nullable=0,nativeType=varchar(12),default='old'
-- 3. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）归档表缺少字段：VIP_LEVEL
-- 4. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）归档表缺少字段：MANUAL_FLAG
-- 5. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）归档表缺少字段：AUTO_USED_FLAG

-- 建议 DDL
ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` MODIFY COLUMN `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL;

ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL;


-- operation 170
ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` MODIFY COLUMN `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL; -- bindings={}
-- operation 171
-- execution result=0
-- operation 172
ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL; -- bindings={}
-- operation 173
-- execution result=0
-- operation 174
ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL; -- bindings={}
-- operation 175
-- execution result=0
-- operation 176
ALTER TABLE `LDM_DEF_0B69D2A4B4_AT` ADD COLUMN `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL; -- bindings={}
-- operation 177
-- execution result=0
-- operation 178
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_AT WHERE ID=?; -- bindings={1=1}
-- operation 179
-- execution result=ResultSet
-- operation 180
-- actual row owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 181
INSERT INTO LDM_DEF_0B69D2A4B4_AT (ID) VALUES (2); -- bindings={}
-- operation 182
-- execution result=1
-- operation 183
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_AT WHERE ID=?; -- bindings={1=2}
-- operation 184
-- execution result=ResultSet
-- operation 185
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 186
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 187
-- execution result=ResultSet
-- operation 188
-- JDBC metadata getDatabaseProductVersion null
-- operation 189
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 190
-- execution result=ResultSet
-- operation 191
SELECT * FROM `LDM_DEF_0B69D2A4B4_AT` WHERE 1 = 0; -- bindings={}
-- operation 192
-- execution result=ResultSet
-- operation 193
-- JDBC metadata getDatabaseProductVersion null
-- operation 194
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AT}
-- operation 195
-- execution result=ResultSet
-- operation 196
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 197
-- execution result=ResultSet
-- operation 198
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 199
-- execution result=ResultSet
-- operation 200
SELECT * FROM `LDM_DEF_0B69D2A4B4_AT` WHERE 1 = 0; -- bindings={}
-- operation 201
-- execution result=ResultSet
-- operation 202
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 203
-- execution result=ResultSet
-- operation 204
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS.`ID`), COUNT(DISTINCT LM_ARCHIVE_STATS.`ID`) FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 205
-- execution result=ResultSet
-- operation 206
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 207
-- execution result=ResultSet
-- operation 208
-- actual default LDM_DEF_0B69D2A4B4_AS.ID=<SQL NULL>
-- operation 209
-- actual default LDM_DEF_0B69D2A4B4_AS.OWNER_NAME=['O''Brien']
-- operation 210
-- actual default LDM_DEF_0B69D2A4B4_AS.VIP_LEVEL=[' ']
-- operation 211
-- actual default LDM_DEF_0B69D2A4B4_AS.MANUAL_FLAG=['0']
-- operation 212
-- actual default LDM_DEF_0B69D2A4B4_AS.AUTO_USED_FLAG=[0]
-- operation 213
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AT}
-- operation 214
-- execution result=ResultSet
-- operation 215
-- actual default LDM_DEF_0B69D2A4B4_AT.ID=<SQL NULL>
-- operation 216
-- actual default LDM_DEF_0B69D2A4B4_AT.OWNER_NAME=['O''Brien']
-- operation 217
-- actual default LDM_DEF_0B69D2A4B4_AT.VIP_LEVEL=[' ']
-- operation 218
-- actual default LDM_DEF_0B69D2A4B4_AT.MANUAL_FLAG=['0']
-- operation 219
-- actual default LDM_DEF_0B69D2A4B4_AT.AUTO_USED_FLAG=[0]
-- operation 220
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 221
-- execution result=ResultSet
-- operation 222
-- JDBC metadata getDatabaseProductVersion null
-- operation 223
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 224
-- execution result=ResultSet
-- operation 225
SELECT * FROM `LDM_DEF_0B69D2A4B4_AC` WHERE 1 = 0; -- bindings={}
-- operation 226
-- operation failed=Table '<REDACTED>.ldm_def_0b69d2a4b4_ac' doesn't exist
-- operation 227
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 228
-- execution result=ResultSet
-- operation 229
-- expected preparation failure: 归档表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 230
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：MYSQL
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_0B69D2A4B4_AS）归档表 LDM_DEF_0B69D2A4B4_AC 无法读取字段：Table '<REDACTED>.ldm_def_0b69d2a4b4_ac' doesn't exist

-- 建议 DDL
-- 先确认归档表确实不存在且账号具有查询权限；主键、索引、自增、字符集及注释须人工补齐。
CREATE TABLE `LDM_DEF_0B69D2A4B4_AC` (
    `ID` INT(11) NOT NULL,
    `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL,
    `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL,
    `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL
);


-- operation 231
CREATE TABLE `LDM_DEF_0B69D2A4B4_AC` (
    `ID` INT(11) NOT NULL,
    `OWNER_NAME` VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    `VIP_LEVEL` VARCHAR(3) DEFAULT ' ' NOT NULL,
    `MANUAL_FLAG` CHAR(1) DEFAULT '0' NOT NULL,
    `AUTO_USED_FLAG` DECIMAL(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 232
-- execution result=0
-- operation 233
SELECT COUNT(*) FROM LDM_DEF_0B69D2A4B4_AC; -- bindings={}
-- operation 234
-- execution result=ResultSet
-- operation 235
-- count=0
-- operation 236
INSERT INTO LDM_DEF_0B69D2A4B4_AC (ID) VALUES (1); -- bindings={}
-- operation 237
-- execution result=1
-- operation 238
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_0B69D2A4B4_AC WHERE ID=?; -- bindings={1=1}
-- operation 239
-- execution result=ResultSet
-- operation 240
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 241
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 242
-- execution result=ResultSet
-- operation 243
-- JDBC metadata getDatabaseProductVersion null
-- operation 244
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 245
-- execution result=ResultSet
-- operation 246
SELECT * FROM `LDM_DEF_0B69D2A4B4_AC` WHERE 1 = 0; -- bindings={}
-- operation 247
-- execution result=ResultSet
-- operation 248
-- JDBC metadata getDatabaseProductVersion null
-- operation 249
SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_DEFAULT, EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AC}
-- operation 250
-- execution result=ResultSet
-- operation 251
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 252
-- execution result=ResultSet
-- operation 253
SELECT * FROM `LDM_DEF_0B69D2A4B4_AS` WHERE 1 = 0; -- bindings={}
-- operation 254
-- execution result=ResultSet
-- operation 255
SELECT * FROM `LDM_DEF_0B69D2A4B4_AC` WHERE 1 = 0; -- bindings={}
-- operation 256
-- execution result=ResultSet
-- operation 257
SELECT * FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 258
-- execution result=ResultSet
-- operation 259
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS.`ID`), COUNT(DISTINCT LM_ARCHIVE_STATS.`ID`) FROM (SELECT * FROM LDM_DEF_0B69D2A4B4_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 260
-- execution result=ResultSet
-- operation 261
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AS}
-- operation 262
-- execution result=ResultSet
-- operation 263
-- actual default LDM_DEF_0B69D2A4B4_AS.ID=<SQL NULL>
-- operation 264
-- actual default LDM_DEF_0B69D2A4B4_AS.OWNER_NAME=['O''Brien']
-- operation 265
-- actual default LDM_DEF_0B69D2A4B4_AS.VIP_LEVEL=[' ']
-- operation 266
-- actual default LDM_DEF_0B69D2A4B4_AS.MANUAL_FLAG=['0']
-- operation 267
-- actual default LDM_DEF_0B69D2A4B4_AS.AUTO_USED_FLAG=[0]
-- operation 268
SELECT COLUMN_NAME,COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION; -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_AC}
-- operation 269
-- execution result=ResultSet
-- operation 270
-- actual default LDM_DEF_0B69D2A4B4_AC.ID=<SQL NULL>
-- operation 271
-- actual default LDM_DEF_0B69D2A4B4_AC.OWNER_NAME=['O''Brien']
-- operation 272
-- actual default LDM_DEF_0B69D2A4B4_AC.VIP_LEVEL=[' ']
-- operation 273
-- actual default LDM_DEF_0B69D2A4B4_AC.MANUAL_FLAG=['0']
-- operation 274
-- actual default LDM_DEF_0B69D2A4B4_AC.AUTO_USED_FLAG=[0]
-- operation 275
DROP TABLE LDM_DEF_0B69D2A4B4_AC; -- bindings={}
-- operation 276
-- execution result=0
-- operation 277
DROP TABLE LDM_DEF_0B69D2A4B4_AT; -- bindings={}
-- operation 278
-- execution result=0
-- operation 279
DROP TABLE LDM_DEF_0B69D2A4B4_AS; -- bindings={}
-- operation 280
-- execution result=0
-- operation 281
DROP TABLE LDM_DEF_0B69D2A4B4_MC; -- bindings={}
-- operation 282
-- execution result=0
-- operation 283
DROP TABLE LDM_DEF_0B69D2A4B4_MT; -- bindings={}
-- operation 284
-- execution result=0
-- operation 285
DROP TABLE LDM_DEF_0B69D2A4B4_MS; -- bindings={}
-- operation 286
-- execution result=0
-- operation 287
SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_0B69D2A4B4_MS, 3=LDM_DEF_0B69D2A4B4_MT, 4=LDM_DEF_0B69D2A4B4_MC, 5=LDM_DEF_0B69D2A4B4_AS, 6=LDM_DEF_0B69D2A4B4_AT, 7=LDM_DEF_0B69D2A4B4_AC}
-- operation 288
-- execution result=ResultSet
-- operation 289
-- exact table remaining=0
```
