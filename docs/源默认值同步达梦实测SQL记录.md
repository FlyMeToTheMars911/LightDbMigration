
# 达梦源默认值同步真实验证

日期：2026-10-30；本轮前缀：`LDM_DEF_4C88356102`。单次物理登录，不自动重试，只操作本轮独立对象。

下列记录覆盖全部应用 SQL、绑定参数、元数据 API、执行结果及清理。驱动内部初始化和元数据内部 SQL 无法取得原文。连接端点、用户名、Schema、密码脱敏。

- 通过：目录计数必须有结果
- 通过：本轮六张精确隔离表必须预先不存在
- 通过：原 SQL 预编译描述可读取
- 通过：原 SQL 预编译描述可读取
- 通过：原 SQL 预编译描述可读取
- 通过：驱动必须提供元数据模式转义符
- 通过：驱动必须提供元数据模式转义符
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_MS/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：结构预检失败同时输出同步建议
- 通过：M：失败预检零数据写入/删除
- 通过：计数查询必须有结果
- 通过：计数查询必须有结果
- 通过：M：预检失败保留两侧原有数据
- 通过：M：三项 ADD 和一项 MODIFY 均生成可执行建议
- 通过：M：空格默认值原样保留
- 通过：M：数字零默认值保留
- 通过：M：引号默认值保留
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_MT/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_MT/2 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：M：执行同步后完整预检通过
- 通过：M：重检零数据写入/删除
- 通过：执行后 ALL_TAB_COLUMNS 默认值与源表一致
- 通过：结构预检失败同时输出同步建议
- 通过：M：缺表预检零数据写入/删除
- 通过：M：缺表生成一项 CREATE 建议
- 通过：M：CREATE 包含全部可确认源默认值
- 通过：生成语句只允许操作本轮精确目标表
- 通过：计数查询必须有结果
- 通过：M：CREATE 不复制业务数据
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_MC/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：M：CREATE 后完整预检通过
- 通过：M：CREATE 后重检零数据写入/删除
- 通过：执行后 ALL_TAB_COLUMNS 默认值与源表一致
- 通过：原 SQL 预编译描述可读取
- 通过：原 SQL 预编译描述可读取
- 通过：原 SQL 预编译描述可读取
- 通过：驱动必须提供元数据模式转义符
- 通过：驱动必须提供元数据模式转义符
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_AS/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：结构预检失败同时输出同步建议
- 通过：A：失败预检零数据写入/删除
- 通过：计数查询必须有结果
- 通过：计数查询必须有结果
- 通过：A：预检失败保留两侧原有数据
- 通过：A：三项 ADD 和一项 MODIFY 均生成可执行建议
- 通过：A：空格默认值原样保留
- 通过：A：数字零默认值保留
- 通过：A：引号默认值保留
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：只执行本轮预期 ALTER 建议
- 通过：生成语句只允许操作本轮精确目标表
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_AT/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_AT/2 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：A：执行同步后完整预检通过
- 通过：A：重检零数据写入/删除
- 通过：执行后 ALL_TAB_COLUMNS 默认值与源表一致
- 通过：结构预检失败同时输出同步建议
- 通过：A：缺表预检零数据写入/删除
- 通过：A：缺表生成一项 CREATE 建议
- 通过：A：CREATE 包含全部可确认源默认值
- 通过：生成语句只允许操作本轮精确目标表
- 通过：计数查询必须有结果
- 通过：A：CREATE 不复制业务数据
- 通过：默认值验证行必须存在
- 通过：行 LDM_DEF_4C88356102_AC/1 的文本、空格及数字默认值符合预期
- 通过：精确主键不得返回多行
- 通过：A：CREATE 后完整预检通过
- 通过：A：CREATE 后重检零数据写入/删除
- 通过：执行后 ALL_TAB_COLUMNS 默认值与源表一致
- 迁移和归档均验证了 ADD、MODIFY、CREATE 继承源默认值；未启动任何业务数据任务。
- 通过：目录计数必须有结果
- 通过：本轮精确隔离表清理残留=0

```sql
-- operation 1
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MS, 3=LDM_DEF_4C88356102_MT, 4=LDM_DEF_4C88356102_MC, 5=LDM_DEF_4C88356102_AS, 6=LDM_DEF_4C88356102_AT, 7=LDM_DEF_4C88356102_AC}
-- operation 2
-- execution result: ResultSet returned
-- operation 3
-- actual catalog remaining=0
-- operation 4
CREATE TABLE LDM_DEF_4C88356102_MS (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 5
-- execution result: 0
-- operation 6
CREATE TABLE LDM_DEF_4C88356102_MT (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 7
-- execution result: 0
-- operation 8
INSERT INTO LDM_DEF_4C88356102_MS (ID) VALUES (1); -- bindings={}
-- operation 9
-- execution result: 1
-- operation 10
INSERT INTO LDM_DEF_4C88356102_MT (ID) VALUES (1); -- bindings={}
-- operation 11
-- execution result: 1
-- operation 12
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_MS
-- operation 13
-- source origin: label=ID; column=ID; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 14
-- source origin: label=OWNER_NAME; column=OWNER_NAME; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 15
-- source origin: label=VIP_LEVEL; column=VIP_LEVEL; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 16
-- source origin: label=MANUAL_FLAG; column=MANUAL_FLAG; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 17
-- source origin: label=AUTO_USED_FLAG; column=AUTO_USED_FLAG; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 18
-- JDBC PreparedStatement.getMetaData; SQL=SELECT ID,OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_MS
-- operation 19
-- source origin: label=ID; column=ID; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 20
-- source origin: label=OWNER_NAME; column=OWNER_NAME; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 21
-- source origin: label=VIP_LEVEL; column=VIP_LEVEL; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 22
-- source origin: label=MANUAL_FLAG; column=MANUAL_FLAG; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 23
-- source origin: label=AUTO_USED_FLAG; column=AUTO_USED_FLAG; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 24
-- JDBC PreparedStatement.getMetaData; SQL=SELECT ID AS ID_ALIAS,OWNER_NAME AS OWNER_ALIAS FROM LDM_DEF_4C88356102_MS
-- operation 25
-- source origin: label=ID_ALIAS; column=ID; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 26
-- source origin: label=OWNER_ALIAS; column=OWNER_NAME; table=LDM_DEF_4C88356102_MS; schema=<SCHEMA>; catalog=
-- operation 27
-- JDBC metadata getSearchStringEscape null
-- operation 28
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MS, %]
-- operation 29
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_MS; column=ID; type=NUMBER; nullable=0; COLUMN_DEF=<SQL NULL>
-- operation 30
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_MS; column=OWNER_NAME; type=VARCHAR; nullable=0; COLUMN_DEF=['O''Brien']
-- operation 31
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_MS; column=VIP_LEVEL; type=VARCHAR; nullable=0; COLUMN_DEF=[' ']
-- operation 32
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_MS; column=MANUAL_FLAG; type=CHAR; nullable=0; COLUMN_DEF=['0']
-- operation 33
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_MS; column=AUTO_USED_FLAG; type=NUMBER; nullable=0; COLUMN_DEF=[0]
-- operation 34
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MS}
-- operation 35
-- execution result: ResultSet returned
-- operation 36
-- actual catalog: LDM_DEF_4C88356102_MS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 37
-- actual catalog: LDM_DEF_4C88356102_MS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 38
-- actual catalog: LDM_DEF_4C88356102_MS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 39
-- actual catalog: LDM_DEF_4C88356102_MS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 40
-- actual catalog: LDM_DEF_4C88356102_MS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 41
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_MS WHERE ID=?; -- bindings={1=1}
-- operation 42
-- execution result: ResultSet returned
-- operation 43
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 44
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_MS
-- operation 45
-- JDBC metadata getSearchStringEscape null
-- operation 46
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MS, %]
-- operation 47
SELECT * FROM LDM_DEF_4C88356102_MT WHERE 1 = 0; -- bindings={}
-- operation 48
-- execution result: ResultSet returned
-- operation 49
-- JDBC metadata getSearchStringEscape null
-- operation 50
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MT, %]
-- operation 51
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_4C88356102_MS) LM_STATS; -- bindings={}
-- operation 52
-- execution result: ResultSet returned
-- operation 53
-- expected precheck failure: 表结构校验未通过，共发现 4 个问题，已一次性输出到任务日志
-- operation 54
SELECT COUNT(*) FROM LDM_DEF_4C88356102_MS; -- bindings={}
-- operation 55
-- execution result: ResultSet returned
-- operation 56
-- actual count LDM_DEF_4C88356102_MS=1
-- operation 57
SELECT COUNT(*) FROM LDM_DEF_4C88356102_MT; -- bindings={}
-- operation 58
-- execution result: ResultSet returned
-- operation 59
-- actual count LDM_DEF_4C88356102_MT=1
-- operation 60
-- generated script [M ADD/MODIFY]
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：DM
-- 重要：本脚本仅根据源查询结果元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_4C88356102_MS）：结构不一致：字段 OWNER_NAME 目标长度 12 小于源长度 30；源查询映射=VARCHAR(30) NOT NULL，目标=VARCHAR(12) NOT NULL
-- 2. 步骤 1（LDM_DEF_4C88356102_MS）：源查询字段 VIP_LEVEL 在目标表 LDM_DEF_4C88356102_MT 中不存在
-- 3. 步骤 1（LDM_DEF_4C88356102_MS）：源查询字段 MANUAL_FLAG 在目标表 LDM_DEF_4C88356102_MT 中不存在
-- 4. 步骤 1（LDM_DEF_4C88356102_MS）：源查询字段 AUTO_USED_FLAG 在目标表 LDM_DEF_4C88356102_MT 中不存在

-- 建议 DDL
ALTER TABLE "LDM_DEF_4C88356102_MT" MODIFY ("OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien');

ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL);

ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL);

ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("AUTO_USED_FLAG" DECIMAL(2,0) DEFAULT 0 NOT NULL);

-- operation 61
ALTER TABLE "LDM_DEF_4C88356102_MT" MODIFY ("OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien'); -- bindings={}
-- operation 62
-- execution result: 0
-- operation 63
ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL); -- bindings={}
-- operation 64
-- execution result: 0
-- operation 65
ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL); -- bindings={}
-- operation 66
-- execution result: 0
-- operation 67
ALTER TABLE "LDM_DEF_4C88356102_MT" ADD ("AUTO_USED_FLAG" DECIMAL(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 68
-- execution result: 0
-- operation 69
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_MT WHERE ID=?; -- bindings={1=1}
-- operation 70
-- execution result: ResultSet returned
-- operation 71
-- actual row: owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 72
INSERT INTO LDM_DEF_4C88356102_MT (ID) VALUES (2); -- bindings={}
-- operation 73
-- execution result: 1
-- operation 74
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_MT WHERE ID=?; -- bindings={1=2}
-- operation 75
-- execution result: ResultSet returned
-- operation 76
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 77
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_MS
-- operation 78
-- JDBC metadata getSearchStringEscape null
-- operation 79
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MS, %]
-- operation 80
SELECT * FROM LDM_DEF_4C88356102_MT WHERE 1 = 0; -- bindings={}
-- operation 81
-- execution result: ResultSet returned
-- operation 82
-- JDBC metadata getSearchStringEscape null
-- operation 83
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MT, %]
-- operation 84
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_4C88356102_MS) LM_STATS; -- bindings={}
-- operation 85
-- execution result: ResultSet returned
-- operation 86
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DEF_4C88356102_MT]
-- operation 87
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MS}
-- operation 88
-- execution result: ResultSet returned
-- operation 89
-- actual catalog: LDM_DEF_4C88356102_MS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 90
-- actual catalog: LDM_DEF_4C88356102_MS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 91
-- actual catalog: LDM_DEF_4C88356102_MS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 92
-- actual catalog: LDM_DEF_4C88356102_MS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 93
-- actual catalog: LDM_DEF_4C88356102_MS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 94
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MT}
-- operation 95
-- execution result: ResultSet returned
-- operation 96
-- actual catalog: LDM_DEF_4C88356102_MT.ID; DATA_DEFAULT=<SQL NULL>
-- operation 97
-- actual catalog: LDM_DEF_4C88356102_MT.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 98
-- actual catalog: LDM_DEF_4C88356102_MT.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 99
-- actual catalog: LDM_DEF_4C88356102_MT.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 100
-- actual catalog: LDM_DEF_4C88356102_MT.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 101
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_MS
-- operation 102
-- JDBC metadata getSearchStringEscape null
-- operation 103
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MS, %]
-- operation 104
-- expected precheck failure: 表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 105
-- generated script [M CREATE]
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：DM
-- 重要：本脚本仅根据源查询结果元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_4C88356102_MS）：目标表 LDM_DEF_4C88356102_MC 无法读取字段结构：第1 行附近出现错误: 无效的表或视图名[LDM_DEF_4C88356102_MC]

-- 建议 DDL
-- 已确认目标表确实不存在后再执行；主键、索引和注释需要另行补充。
CREATE TABLE "LDM_DEF_4C88356102_MC" (
    "ID" DECIMAL(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" DECIMAL(2,0) DEFAULT 0 NOT NULL
);

-- operation 106
CREATE TABLE "LDM_DEF_4C88356102_MC" (
    "ID" DECIMAL(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" DECIMAL(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 107
-- execution result: 0
-- operation 108
SELECT COUNT(*) FROM LDM_DEF_4C88356102_MC; -- bindings={}
-- operation 109
-- execution result: ResultSet returned
-- operation 110
-- actual count LDM_DEF_4C88356102_MC=0
-- operation 111
INSERT INTO LDM_DEF_4C88356102_MC (ID) VALUES (1); -- bindings={}
-- operation 112
-- execution result: 1
-- operation 113
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_MC WHERE ID=?; -- bindings={1=1}
-- operation 114
-- execution result: ResultSet returned
-- operation 115
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 116
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_MS
-- operation 117
-- JDBC metadata getSearchStringEscape null
-- operation 118
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MS, %]
-- operation 119
SELECT * FROM LDM_DEF_4C88356102_MC WHERE 1 = 0; -- bindings={}
-- operation 120
-- execution result: ResultSet returned
-- operation 121
-- JDBC metadata getSearchStringEscape null
-- operation 122
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_MC, %]
-- operation 123
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_4C88356102_MS) LM_STATS; -- bindings={}
-- operation 124
-- execution result: ResultSet returned
-- operation 125
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DEF_4C88356102_MC]
-- operation 126
-- JDBC metadata getIndexInfo [null, <SCHEMA>, LDM_DEF_4C88356102_MC, true, false]
-- operation 127
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, ldm_def_4c88356102_mc]
-- operation 128
-- JDBC metadata getIndexInfo [null, <SCHEMA>, ldm_def_4c88356102_mc, true, false]
-- operation 129
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MS}
-- operation 130
-- execution result: ResultSet returned
-- operation 131
-- actual catalog: LDM_DEF_4C88356102_MS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 132
-- actual catalog: LDM_DEF_4C88356102_MS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 133
-- actual catalog: LDM_DEF_4C88356102_MS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 134
-- actual catalog: LDM_DEF_4C88356102_MS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 135
-- actual catalog: LDM_DEF_4C88356102_MS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 136
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MC}
-- operation 137
-- execution result: ResultSet returned
-- operation 138
-- actual catalog: LDM_DEF_4C88356102_MC.ID; DATA_DEFAULT=<SQL NULL>
-- operation 139
-- actual catalog: LDM_DEF_4C88356102_MC.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 140
-- actual catalog: LDM_DEF_4C88356102_MC.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 141
-- actual catalog: LDM_DEF_4C88356102_MC.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 142
-- actual catalog: LDM_DEF_4C88356102_MC.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 143
CREATE TABLE LDM_DEF_4C88356102_AS (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 144
-- execution result: 0
-- operation 145
CREATE TABLE LDM_DEF_4C88356102_AT (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 146
-- execution result: 0
-- operation 147
INSERT INTO LDM_DEF_4C88356102_AS (ID) VALUES (1); -- bindings={}
-- operation 148
-- execution result: 1
-- operation 149
INSERT INTO LDM_DEF_4C88356102_AT (ID) VALUES (1); -- bindings={}
-- operation 150
-- execution result: 1
-- operation 151
-- JDBC PreparedStatement.getMetaData; SQL=SELECT * FROM LDM_DEF_4C88356102_AS
-- operation 152
-- source origin: label=ID; column=ID; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 153
-- source origin: label=OWNER_NAME; column=OWNER_NAME; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 154
-- source origin: label=VIP_LEVEL; column=VIP_LEVEL; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 155
-- source origin: label=MANUAL_FLAG; column=MANUAL_FLAG; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 156
-- source origin: label=AUTO_USED_FLAG; column=AUTO_USED_FLAG; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 157
-- JDBC PreparedStatement.getMetaData; SQL=SELECT ID,OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_AS
-- operation 158
-- source origin: label=ID; column=ID; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 159
-- source origin: label=OWNER_NAME; column=OWNER_NAME; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 160
-- source origin: label=VIP_LEVEL; column=VIP_LEVEL; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 161
-- source origin: label=MANUAL_FLAG; column=MANUAL_FLAG; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 162
-- source origin: label=AUTO_USED_FLAG; column=AUTO_USED_FLAG; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 163
-- JDBC PreparedStatement.getMetaData; SQL=SELECT ID AS ID_ALIAS,OWNER_NAME AS OWNER_ALIAS FROM LDM_DEF_4C88356102_AS
-- operation 164
-- source origin: label=ID_ALIAS; column=ID; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 165
-- source origin: label=OWNER_ALIAS; column=OWNER_NAME; table=LDM_DEF_4C88356102_AS; schema=<SCHEMA>; catalog=
-- operation 166
-- JDBC metadata getSearchStringEscape null
-- operation 167
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AS, %]
-- operation 168
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_AS; column=ID; type=NUMBER; nullable=0; COLUMN_DEF=<SQL NULL>
-- operation 169
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_AS; column=OWNER_NAME; type=VARCHAR; nullable=0; COLUMN_DEF=['O''Brien']
-- operation 170
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_AS; column=VIP_LEVEL; type=VARCHAR; nullable=0; COLUMN_DEF=[' ']
-- operation 171
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_AS; column=MANUAL_FLAG; type=CHAR; nullable=0; COLUMN_DEF=['0']
-- operation 172
-- JDBC actual column: schema=<SCHEMA>; table=LDM_DEF_4C88356102_AS; column=AUTO_USED_FLAG; type=NUMBER; nullable=0; COLUMN_DEF=[0]
-- operation 173
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_AS}
-- operation 174
-- execution result: ResultSet returned
-- operation 175
-- actual catalog: LDM_DEF_4C88356102_AS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 176
-- actual catalog: LDM_DEF_4C88356102_AS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 177
-- actual catalog: LDM_DEF_4C88356102_AS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 178
-- actual catalog: LDM_DEF_4C88356102_AS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 179
-- actual catalog: LDM_DEF_4C88356102_AS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 180
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_AS WHERE ID=?; -- bindings={1=1}
-- operation 181
-- execution result: ResultSet returned
-- operation 182
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 183
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 184
-- execution result: ResultSet returned
-- operation 185
-- JDBC metadata getSearchStringEscape null
-- operation 186
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AS, %]
-- operation 187
SELECT * FROM "LDM_DEF_4C88356102_AT" WHERE 1 = 0; -- bindings={}
-- operation 188
-- execution result: ResultSet returned
-- operation 189
-- JDBC metadata getSearchStringEscape null
-- operation 190
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AT, %]
-- operation 191
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 192
-- execution result: ResultSet returned
-- operation 193
-- expected precheck failure: 归档表结构校验未通过，共发现 5 个问题，已一次性输出到任务日志
-- operation 194
SELECT COUNT(*) FROM LDM_DEF_4C88356102_AS; -- bindings={}
-- operation 195
-- execution result: ResultSet returned
-- operation 196
-- actual count LDM_DEF_4C88356102_AS=1
-- operation 197
SELECT COUNT(*) FROM LDM_DEF_4C88356102_AT; -- bindings={}
-- operation 198
-- execution result: ResultSet returned
-- operation 199
-- actual count LDM_DEF_4C88356102_AT=1
-- operation 200
-- generated script [A ADD/MODIFY]
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：DM
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_4C88356102_AS）源表与归档表字段数量不同：源表 5 个，归档表 2 个
-- 2. 归档节点 1（LDM_DEF_4C88356102_AS）字段结构不同：OWNER_NAME，源表=VARCHAR(30,0),jdbcType=12,nullable=0,default='O''Brien'，归档表=VARCHAR(12,0),jdbcType=12,nullable=0,default='old'
-- 3. 归档节点 1（LDM_DEF_4C88356102_AS）归档表缺少字段：VIP_LEVEL
-- 4. 归档节点 1（LDM_DEF_4C88356102_AS）归档表缺少字段：MANUAL_FLAG
-- 5. 归档节点 1（LDM_DEF_4C88356102_AS）归档表缺少字段：AUTO_USED_FLAG

-- 建议 DDL
ALTER TABLE "LDM_DEF_4C88356102_AT" MODIFY ("OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien');

ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL);

ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL);

ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL);


-- operation 201
ALTER TABLE "LDM_DEF_4C88356102_AT" MODIFY ("OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien'); -- bindings={}
-- operation 202
-- execution result: 0
-- operation 203
ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL); -- bindings={}
-- operation 204
-- execution result: 0
-- operation 205
ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL); -- bindings={}
-- operation 206
-- execution result: 0
-- operation 207
ALTER TABLE "LDM_DEF_4C88356102_AT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 208
-- execution result: 0
-- operation 209
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_AT WHERE ID=?; -- bindings={1=1}
-- operation 210
-- execution result: ResultSet returned
-- operation 211
-- actual row: owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 212
INSERT INTO LDM_DEF_4C88356102_AT (ID) VALUES (2); -- bindings={}
-- operation 213
-- execution result: 1
-- operation 214
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_AT WHERE ID=?; -- bindings={1=2}
-- operation 215
-- execution result: ResultSet returned
-- operation 216
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 217
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 218
-- execution result: ResultSet returned
-- operation 219
-- JDBC metadata getSearchStringEscape null
-- operation 220
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AS, %]
-- operation 221
SELECT * FROM "LDM_DEF_4C88356102_AT" WHERE 1 = 0; -- bindings={}
-- operation 222
-- execution result: ResultSet returned
-- operation 223
-- JDBC metadata getSearchStringEscape null
-- operation 224
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AT, %]
-- operation 225
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 226
-- execution result: ResultSet returned
-- operation 227
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 228
-- execution result: ResultSet returned
-- operation 229
SELECT * FROM "LDM_DEF_4C88356102_AT" WHERE 1 = 0; -- bindings={}
-- operation 230
-- execution result: ResultSet returned
-- operation 231
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 232
-- execution result: ResultSet returned
-- operation 233
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ID"), COUNT(DISTINCT LM_ARCHIVE_STATS."ID") FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 234
-- execution result: ResultSet returned
-- operation 235
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_AS}
-- operation 236
-- execution result: ResultSet returned
-- operation 237
-- actual catalog: LDM_DEF_4C88356102_AS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 238
-- actual catalog: LDM_DEF_4C88356102_AS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 239
-- actual catalog: LDM_DEF_4C88356102_AS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 240
-- actual catalog: LDM_DEF_4C88356102_AS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 241
-- actual catalog: LDM_DEF_4C88356102_AS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 242
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_AT}
-- operation 243
-- execution result: ResultSet returned
-- operation 244
-- actual catalog: LDM_DEF_4C88356102_AT.ID; DATA_DEFAULT=<SQL NULL>
-- operation 245
-- actual catalog: LDM_DEF_4C88356102_AT.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 246
-- actual catalog: LDM_DEF_4C88356102_AT.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 247
-- actual catalog: LDM_DEF_4C88356102_AT.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 248
-- actual catalog: LDM_DEF_4C88356102_AT.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 249
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 250
-- execution result: ResultSet returned
-- operation 251
-- JDBC metadata getSearchStringEscape null
-- operation 252
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AS, %]
-- operation 253
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 254
-- execution result: ResultSet returned
-- operation 255
-- expected precheck failure: 归档表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 256
-- generated script [A CREATE]
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：DM
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_4C88356102_AS）归档表 LDM_DEF_4C88356102_AC 无法读取字段：第1 行附近出现错误: 无效的表或视图名[LDM_DEF_4C88356102_AC]

-- 建议 DDL
-- 先确认归档表确实不存在且账号具有查询权限；主键、索引、自增、字符集及注释须人工补齐。
CREATE TABLE "LDM_DEF_4C88356102_AC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
);


-- operation 257
CREATE TABLE "LDM_DEF_4C88356102_AC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 258
-- execution result: 0
-- operation 259
SELECT COUNT(*) FROM LDM_DEF_4C88356102_AC; -- bindings={}
-- operation 260
-- execution result: ResultSet returned
-- operation 261
-- actual count LDM_DEF_4C88356102_AC=0
-- operation 262
INSERT INTO LDM_DEF_4C88356102_AC (ID) VALUES (1); -- bindings={}
-- operation 263
-- execution result: 1
-- operation 264
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_4C88356102_AC WHERE ID=?; -- bindings={1=1}
-- operation 265
-- execution result: ResultSet returned
-- operation 266
-- actual row: owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 267
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 268
-- execution result: ResultSet returned
-- operation 269
-- JDBC metadata getSearchStringEscape null
-- operation 270
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AS, %]
-- operation 271
SELECT * FROM "LDM_DEF_4C88356102_AC" WHERE 1 = 0; -- bindings={}
-- operation 272
-- execution result: ResultSet returned
-- operation 273
-- JDBC metadata getSearchStringEscape null
-- operation 274
-- JDBC metadata getColumns [null, HS!_AIA!_DEV, LDM!_DEF!_4C88356102!_AC, %]
-- operation 275
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 276
-- execution result: ResultSet returned
-- operation 277
SELECT * FROM "LDM_DEF_4C88356102_AS" WHERE 1 = 0; -- bindings={}
-- operation 278
-- execution result: ResultSet returned
-- operation 279
SELECT * FROM "LDM_DEF_4C88356102_AC" WHERE 1 = 0; -- bindings={}
-- operation 280
-- execution result: ResultSet returned
-- operation 281
SELECT * FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 282
-- execution result: ResultSet returned
-- operation 283
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ID"), COUNT(DISTINCT LM_ARCHIVE_STATS."ID") FROM (SELECT * FROM LDM_DEF_4C88356102_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 284
-- execution result: ResultSet returned
-- operation 285
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_AS}
-- operation 286
-- execution result: ResultSet returned
-- operation 287
-- actual catalog: LDM_DEF_4C88356102_AS.ID; DATA_DEFAULT=<SQL NULL>
-- operation 288
-- actual catalog: LDM_DEF_4C88356102_AS.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 289
-- actual catalog: LDM_DEF_4C88356102_AS.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 290
-- actual catalog: LDM_DEF_4C88356102_AS.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 291
-- actual catalog: LDM_DEF_4C88356102_AS.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 292
SELECT COLUMN_NAME, DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_AC}
-- operation 293
-- execution result: ResultSet returned
-- operation 294
-- actual catalog: LDM_DEF_4C88356102_AC.ID; DATA_DEFAULT=<SQL NULL>
-- operation 295
-- actual catalog: LDM_DEF_4C88356102_AC.OWNER_NAME; DATA_DEFAULT=['O''Brien']
-- operation 296
-- actual catalog: LDM_DEF_4C88356102_AC.VIP_LEVEL; DATA_DEFAULT=[' ']
-- operation 297
-- actual catalog: LDM_DEF_4C88356102_AC.MANUAL_FLAG; DATA_DEFAULT=['0']
-- operation 298
-- actual catalog: LDM_DEF_4C88356102_AC.AUTO_USED_FLAG; DATA_DEFAULT=[0]
-- operation 299
DROP TABLE LDM_DEF_4C88356102_AC; -- bindings={}
-- operation 300
-- execution result: 0
-- operation 301
DROP TABLE LDM_DEF_4C88356102_AT; -- bindings={}
-- operation 302
-- execution result: 0
-- operation 303
DROP TABLE LDM_DEF_4C88356102_AS; -- bindings={}
-- operation 304
-- execution result: 0
-- operation 305
DROP TABLE LDM_DEF_4C88356102_MC; -- bindings={}
-- operation 306
-- execution result: 0
-- operation 307
DROP TABLE LDM_DEF_4C88356102_MT; -- bindings={}
-- operation 308
-- execution result: 0
-- operation 309
DROP TABLE LDM_DEF_4C88356102_MS; -- bindings={}
-- operation 310
-- execution result: 0
-- operation 311
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<SCHEMA>, 2=LDM_DEF_4C88356102_MS, 3=LDM_DEF_4C88356102_MT, 4=LDM_DEF_4C88356102_MC, 5=LDM_DEF_4C88356102_AS, 6=LDM_DEF_4C88356102_AT, 7=LDM_DEF_4C88356102_AC}
-- operation 312
-- execution result: ResultSet returned
-- operation 313
-- actual catalog remaining=0
```
