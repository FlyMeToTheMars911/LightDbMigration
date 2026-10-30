
# ORACLE 源默认值同步实测

日期：2026-10-30；前缀：`LDM_DEF_72377B8D28`。单次物理登录；仅本轮独立对象。驱动内部 SQL 无法取得原文；以下为全部应用 SQL、绑定参数、元数据 API、结果及清理。

- 实际数据库产品=Oracle；版本=Oracle Database 19c Enterprise Edition Release 19.0.0.0.0 - Production
Version 19.3.0.0.0
- 通过：目录残留查询返回结果
- 通过：本轮六张精确独立表预先不存在
- 通过：精确表默认值目录可读取
- 通过：默认值验证行存在
- 通过：LDM_DEF_72377B8D28_MS/1默认值应用正确
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
- 通过：LDM_DEF_72377B8D28_MT/1默认值应用正确
- 通过：默认值验证行存在
- 通过：LDM_DEF_72377B8D28_MT/2默认值应用正确
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
- 通过：LDM_DEF_72377B8D28_MC/1默认值应用正确
- 通过：M同步后完整预检通过且零写入/删除
- 通过：精确表默认值目录可读取
- 通过：精确表默认值目录可读取
- 通过：MCREATE后源目标物理默认值一致
- 通过：精确表默认值目录可读取
- 通过：默认值验证行存在
- 通过：LDM_DEF_72377B8D28_AS/1默认值应用正确
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
- 通过：LDM_DEF_72377B8D28_AT/1默认值应用正确
- 通过：默认值验证行存在
- 通过：LDM_DEF_72377B8D28_AT/2默认值应用正确
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
- 通过：LDM_DEF_72377B8D28_AC/1默认值应用正确
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
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MS, 3=LDM_DEF_72377B8D28_MT, 4=LDM_DEF_72377B8D28_MC, 5=LDM_DEF_72377B8D28_AS, 6=LDM_DEF_72377B8D28_AT, 7=LDM_DEF_72377B8D28_AC}
-- operation 4
-- execution result=ResultSet
-- operation 5
-- exact table remaining=0
-- operation 6
CREATE TABLE LDM_DEF_72377B8D28_MS (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR2(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR2(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 7
-- execution result=0
-- operation 8
CREATE TABLE LDM_DEF_72377B8D28_MT (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR2(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 9
-- execution result=0
-- operation 10
INSERT INTO LDM_DEF_72377B8D28_MS (ID) VALUES (1); -- bindings={}
-- operation 11
-- execution result=1
-- operation 12
INSERT INTO LDM_DEF_72377B8D28_MT (ID) VALUES (1); -- bindings={}
-- operation 13
-- execution result=1
-- operation 14
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MS}
-- operation 15
-- execution result=ResultSet
-- operation 16
-- actual default LDM_DEF_72377B8D28_MS.ID=<SQL NULL>
-- operation 17
-- actual default LDM_DEF_72377B8D28_MS.OWNER_NAME=['O''Brien' ]
-- operation 18
-- actual default LDM_DEF_72377B8D28_MS.VIP_LEVEL=[' ' ]
-- operation 19
-- actual default LDM_DEF_72377B8D28_MS.MANUAL_FLAG=['0' ]
-- operation 20
-- actual default LDM_DEF_72377B8D28_MS.AUTO_USED_FLAG=[0 ]
-- operation 21
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_MS WHERE ID=?; -- bindings={1=1}
-- operation 22
-- execution result=ResultSet
-- operation 23
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 24
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_72377B8D28_MS
-- operation 25
-- JDBC metadata getSearchStringEscape null
-- operation 26
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MS, %]
-- operation 27
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 28
SELECT * FROM LDM_DEF_72377B8D28_MT WHERE 1 = 0; -- bindings={}
-- operation 29
-- execution result=ResultSet
-- operation 30
-- JDBC metadata getSearchStringEscape null
-- operation 31
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MT, %]
-- operation 32
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 33
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_72377B8D28_MS) LM_STATS; -- bindings={}
-- operation 34
-- execution result=ResultSet
-- operation 35
-- expected preparation failure: 表结构校验未通过，共发现 4 个问题，已一次性输出到任务日志
-- operation 36
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_MS; -- bindings={}
-- operation 37
-- execution result=ResultSet
-- operation 38
-- count=1
-- operation 39
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_MT; -- bindings={}
-- operation 40
-- execution result=ResultSet
-- operation 41
-- count=1
-- operation 42
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：ORACLE
-- 重要：本脚本根据源查询结果及已确认物理来源元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_72377B8D28_MS）：结构不一致：字段 OWNER_NAME 目标长度 12 小于源长度 30；源查询映射=VARCHAR2(30 CHAR) NOT NULL，目标=VARCHAR2(12 CHAR) NOT NULL
-- 2. 步骤 1（LDM_DEF_72377B8D28_MS）：源查询字段 VIP_LEVEL 在目标表 LDM_DEF_72377B8D28_MT 中不存在
-- 3. 步骤 1（LDM_DEF_72377B8D28_MS）：源查询字段 MANUAL_FLAG 在目标表 LDM_DEF_72377B8D28_MT 中不存在
-- 4. 步骤 1（LDM_DEF_72377B8D28_MS）：源查询字段 AUTO_USED_FLAG 在目标表 LDM_DEF_72377B8D28_MT 中不存在

-- 建议 DDL
ALTER TABLE "LDM_DEF_72377B8D28_MT" MODIFY ("OWNER_NAME" VARCHAR2(30 CHAR) DEFAULT 'O''Brien');

ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("VIP_LEVEL" VARCHAR2(3 CHAR) DEFAULT ' ' NOT NULL);

ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("MANUAL_FLAG" CHAR(1 CHAR) DEFAULT '0' NOT NULL);

ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL);

-- operation 43
ALTER TABLE "LDM_DEF_72377B8D28_MT" MODIFY ("OWNER_NAME" VARCHAR2(30 CHAR) DEFAULT 'O''Brien'); -- bindings={}
-- operation 44
-- execution result=0
-- operation 45
ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("VIP_LEVEL" VARCHAR2(3 CHAR) DEFAULT ' ' NOT NULL); -- bindings={}
-- operation 46
-- execution result=0
-- operation 47
ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("MANUAL_FLAG" CHAR(1 CHAR) DEFAULT '0' NOT NULL); -- bindings={}
-- operation 48
-- execution result=0
-- operation 49
ALTER TABLE "LDM_DEF_72377B8D28_MT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 50
-- execution result=0
-- operation 51
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_MT WHERE ID=?; -- bindings={1=1}
-- operation 52
-- execution result=ResultSet
-- operation 53
-- actual row owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 54
INSERT INTO LDM_DEF_72377B8D28_MT (ID) VALUES (2); -- bindings={}
-- operation 55
-- execution result=1
-- operation 56
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_MT WHERE ID=?; -- bindings={1=2}
-- operation 57
-- execution result=ResultSet
-- operation 58
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 59
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_72377B8D28_MS
-- operation 60
-- JDBC metadata getSearchStringEscape null
-- operation 61
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MS, %]
-- operation 62
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 63
SELECT * FROM LDM_DEF_72377B8D28_MT WHERE 1 = 0; -- bindings={}
-- operation 64
-- execution result=ResultSet
-- operation 65
-- JDBC metadata getSearchStringEscape null
-- operation 66
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MT, %]
-- operation 67
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 68
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_72377B8D28_MS) LM_STATS; -- bindings={}
-- operation 69
-- execution result=ResultSet
-- operation 70
-- JDBC metadata getUserName null
-- operation 71
-- JDBC metadata getPrimaryKeys [null, <REDACTED>, LDM_DEF_72377B8D28_MT]
-- operation 72
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MS}
-- operation 73
-- execution result=ResultSet
-- operation 74
-- actual default LDM_DEF_72377B8D28_MS.ID=<SQL NULL>
-- operation 75
-- actual default LDM_DEF_72377B8D28_MS.OWNER_NAME=['O''Brien' ]
-- operation 76
-- actual default LDM_DEF_72377B8D28_MS.VIP_LEVEL=[' ' ]
-- operation 77
-- actual default LDM_DEF_72377B8D28_MS.MANUAL_FLAG=['0' ]
-- operation 78
-- actual default LDM_DEF_72377B8D28_MS.AUTO_USED_FLAG=[0 ]
-- operation 79
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MT}
-- operation 80
-- execution result=ResultSet
-- operation 81
-- actual default LDM_DEF_72377B8D28_MT.ID=<SQL NULL>
-- operation 82
-- actual default LDM_DEF_72377B8D28_MT.OWNER_NAME=['O''Brien']
-- operation 83
-- actual default LDM_DEF_72377B8D28_MT.VIP_LEVEL=[' ' ]
-- operation 84
-- actual default LDM_DEF_72377B8D28_MT.MANUAL_FLAG=['0' ]
-- operation 85
-- actual default LDM_DEF_72377B8D28_MT.AUTO_USED_FLAG=[0 ]
-- operation 86
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_72377B8D28_MS
-- operation 87
-- JDBC metadata getSearchStringEscape null
-- operation 88
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MS, %]
-- operation 89
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 90
SELECT * FROM LDM_DEF_72377B8D28_MC WHERE 1 = 0; -- bindings={}
-- operation 91
-- operation failed=ORA-00942: 表或视图不存在
-- operation 92
-- expected preparation failure: 表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 93
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 表结构同步建议脚本
-- 目标数据库类型：ORACLE
-- 重要：本脚本根据源查询结果及已确认物理来源元数据生成，工具不会未经确认执行；点击执行脚本并再次确认后执行。
-- 字段集合、映射后的类型/长度/精度及可空性以源查询为准；多余字段会删除。
-- 主键、索引、默认值、自增、字符集和注释不在本次字段比对范围；DDL 可能受现有数据或约束影响。
-- 生成字段 DDL 时携带已确认且可转换的源默认值；查询表达式不继承物理默认值，仅默认值不同不触发差异。

-- 本次校验问题
-- 1. 步骤 1（LDM_DEF_72377B8D28_MS）：目标表 LDM_DEF_72377B8D28_MC 无法读取字段结构：ORA-00942: 表或视图不存在

-- 建议 DDL
-- 已确认目标表确实不存在后再执行；主键、索引和注释需要另行补充。
CREATE TABLE "LDM_DEF_72377B8D28_MC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR2(30 CHAR) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR2(3 CHAR) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1 CHAR) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
);

-- operation 94
CREATE TABLE "LDM_DEF_72377B8D28_MC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR2(30 CHAR) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR2(3 CHAR) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1 CHAR) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 95
-- execution result=0
-- operation 96
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_MC; -- bindings={}
-- operation 97
-- execution result=ResultSet
-- operation 98
-- count=0
-- operation 99
INSERT INTO LDM_DEF_72377B8D28_MC (ID) VALUES (1); -- bindings={}
-- operation 100
-- execution result=1
-- operation 101
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_MC WHERE ID=?; -- bindings={1=1}
-- operation 102
-- execution result=ResultSet
-- operation 103
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 104
-- PreparedStatement.getMetaData SQL=SELECT * FROM LDM_DEF_72377B8D28_MS
-- operation 105
-- JDBC metadata getSearchStringEscape null
-- operation 106
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MS, %]
-- operation 107
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 108
SELECT * FROM LDM_DEF_72377B8D28_MC WHERE 1 = 0; -- bindings={}
-- operation 109
-- execution result=ResultSet
-- operation 110
-- JDBC metadata getSearchStringEscape null
-- operation 111
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_MC, %]
-- operation 112
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 113
SELECT COUNT(*) FROM (SELECT * FROM LDM_DEF_72377B8D28_MS) LM_STATS; -- bindings={}
-- operation 114
-- execution result=ResultSet
-- operation 115
-- JDBC metadata getUserName null
-- operation 116
-- JDBC metadata getPrimaryKeys [null, <REDACTED>, LDM_DEF_72377B8D28_MC]
-- operation 117
-- JDBC metadata getIndexInfo [null, <REDACTED>, LDM_DEF_72377B8D28_MC, true, false]
-- operation 118
-- JDBC metadata getPrimaryKeys [null, <REDACTED>, ldm_def_72377b8d28_mc]
-- operation 119
-- JDBC metadata getIndexInfo [null, <REDACTED>, ldm_def_72377b8d28_mc, true, false]
-- operation 120
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MS}
-- operation 121
-- execution result=ResultSet
-- operation 122
-- actual default LDM_DEF_72377B8D28_MS.ID=<SQL NULL>
-- operation 123
-- actual default LDM_DEF_72377B8D28_MS.OWNER_NAME=['O''Brien' ]
-- operation 124
-- actual default LDM_DEF_72377B8D28_MS.VIP_LEVEL=[' ' ]
-- operation 125
-- actual default LDM_DEF_72377B8D28_MS.MANUAL_FLAG=['0' ]
-- operation 126
-- actual default LDM_DEF_72377B8D28_MS.AUTO_USED_FLAG=[0 ]
-- operation 127
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MC}
-- operation 128
-- execution result=ResultSet
-- operation 129
-- actual default LDM_DEF_72377B8D28_MC.ID=<SQL NULL>
-- operation 130
-- actual default LDM_DEF_72377B8D28_MC.OWNER_NAME=['O''Brien' ]
-- operation 131
-- actual default LDM_DEF_72377B8D28_MC.VIP_LEVEL=[' ' ]
-- operation 132
-- actual default LDM_DEF_72377B8D28_MC.MANUAL_FLAG=['0' ]
-- operation 133
-- actual default LDM_DEF_72377B8D28_MC.AUTO_USED_FLAG=[0 ]
-- operation 134
CREATE TABLE LDM_DEF_72377B8D28_AS (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR2(30) DEFAULT 'O''Brien' NOT NULL, VIP_LEVEL VARCHAR2(3) DEFAULT ' ' NOT NULL, MANUAL_FLAG CHAR(1) DEFAULT '0' NOT NULL, AUTO_USED_FLAG NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 135
-- execution result=0
-- operation 136
CREATE TABLE LDM_DEF_72377B8D28_AT (ID NUMBER(10,0) NOT NULL PRIMARY KEY, OWNER_NAME VARCHAR2(12) DEFAULT 'old' NOT NULL); -- bindings={}
-- operation 137
-- execution result=0
-- operation 138
INSERT INTO LDM_DEF_72377B8D28_AS (ID) VALUES (1); -- bindings={}
-- operation 139
-- execution result=1
-- operation 140
INSERT INTO LDM_DEF_72377B8D28_AT (ID) VALUES (1); -- bindings={}
-- operation 141
-- execution result=1
-- operation 142
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_AS}
-- operation 143
-- execution result=ResultSet
-- operation 144
-- actual default LDM_DEF_72377B8D28_AS.ID=<SQL NULL>
-- operation 145
-- actual default LDM_DEF_72377B8D28_AS.OWNER_NAME=['O''Brien' ]
-- operation 146
-- actual default LDM_DEF_72377B8D28_AS.VIP_LEVEL=[' ' ]
-- operation 147
-- actual default LDM_DEF_72377B8D28_AS.MANUAL_FLAG=['0' ]
-- operation 148
-- actual default LDM_DEF_72377B8D28_AS.AUTO_USED_FLAG=[0 ]
-- operation 149
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_AS WHERE ID=?; -- bindings={1=1}
-- operation 150
-- execution result=ResultSet
-- operation 151
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 152
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 153
-- execution result=ResultSet
-- operation 154
-- JDBC metadata getSearchStringEscape null
-- operation 155
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AS, %]
-- operation 156
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 157
SELECT * FROM "LDM_DEF_72377B8D28_AT" WHERE 1 = 0; -- bindings={}
-- operation 158
-- execution result=ResultSet
-- operation 159
-- JDBC metadata getSearchStringEscape null
-- operation 160
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AT, %]
-- operation 161
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 162
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 163
-- execution result=ResultSet
-- operation 164
-- expected preparation failure: 归档表结构校验未通过，共发现 5 个问题，已一次性输出到任务日志
-- operation 165
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_AS; -- bindings={}
-- operation 166
-- execution result=ResultSet
-- operation 167
-- count=1
-- operation 168
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_AT; -- bindings={}
-- operation 169
-- execution result=ResultSet
-- operation 170
-- count=1
-- operation 171
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：ORACLE
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_72377B8D28_AS）源表与归档表字段数量不同：源表 5 个，归档表 2 个
-- 2. 归档节点 1（LDM_DEF_72377B8D28_AS）字段结构不同：OWNER_NAME，源表=VARCHAR2(30,0),jdbcType=12,nullable=0,default='O''Brien'，归档表=VARCHAR2(12,0),jdbcType=12,nullable=0,default='old'
-- 3. 归档节点 1（LDM_DEF_72377B8D28_AS）归档表缺少字段：VIP_LEVEL
-- 4. 归档节点 1（LDM_DEF_72377B8D28_AS）归档表缺少字段：MANUAL_FLAG
-- 5. 归档节点 1（LDM_DEF_72377B8D28_AS）归档表缺少字段：AUTO_USED_FLAG

-- 建议 DDL
ALTER TABLE "LDM_DEF_72377B8D28_AT" MODIFY ("OWNER_NAME" VARCHAR2(30) DEFAULT 'O''Brien');

ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("VIP_LEVEL" VARCHAR2(3) DEFAULT ' ' NOT NULL);

ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL);

ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL);


-- operation 172
ALTER TABLE "LDM_DEF_72377B8D28_AT" MODIFY ("OWNER_NAME" VARCHAR2(30) DEFAULT 'O''Brien'); -- bindings={}
-- operation 173
-- execution result=0
-- operation 174
ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("VIP_LEVEL" VARCHAR2(3) DEFAULT ' ' NOT NULL); -- bindings={}
-- operation 175
-- execution result=0
-- operation 176
ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL); -- bindings={}
-- operation 177
-- execution result=0
-- operation 178
ALTER TABLE "LDM_DEF_72377B8D28_AT" ADD ("AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL); -- bindings={}
-- operation 179
-- execution result=0
-- operation 180
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_AT WHERE ID=?; -- bindings={1=1}
-- operation 181
-- execution result=ResultSet
-- operation 182
-- actual row owner=[old]; vip=[ ]; manual=[0]; automatic=0
-- operation 183
INSERT INTO LDM_DEF_72377B8D28_AT (ID) VALUES (2); -- bindings={}
-- operation 184
-- execution result=1
-- operation 185
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_AT WHERE ID=?; -- bindings={1=2}
-- operation 186
-- execution result=ResultSet
-- operation 187
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 188
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 189
-- execution result=ResultSet
-- operation 190
-- JDBC metadata getSearchStringEscape null
-- operation 191
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AS, %]
-- operation 192
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 193
SELECT * FROM "LDM_DEF_72377B8D28_AT" WHERE 1 = 0; -- bindings={}
-- operation 194
-- execution result=ResultSet
-- operation 195
-- JDBC metadata getSearchStringEscape null
-- operation 196
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AT, %]
-- operation 197
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 198
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 199
-- execution result=ResultSet
-- operation 200
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 201
-- execution result=ResultSet
-- operation 202
SELECT * FROM "LDM_DEF_72377B8D28_AT" WHERE 1 = 0; -- bindings={}
-- operation 203
-- execution result=ResultSet
-- operation 204
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 205
-- execution result=ResultSet
-- operation 206
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ID"), COUNT(DISTINCT LM_ARCHIVE_STATS."ID") FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 207
-- execution result=ResultSet
-- operation 208
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_AS}
-- operation 209
-- execution result=ResultSet
-- operation 210
-- actual default LDM_DEF_72377B8D28_AS.ID=<SQL NULL>
-- operation 211
-- actual default LDM_DEF_72377B8D28_AS.OWNER_NAME=['O''Brien' ]
-- operation 212
-- actual default LDM_DEF_72377B8D28_AS.VIP_LEVEL=[' ' ]
-- operation 213
-- actual default LDM_DEF_72377B8D28_AS.MANUAL_FLAG=['0' ]
-- operation 214
-- actual default LDM_DEF_72377B8D28_AS.AUTO_USED_FLAG=[0 ]
-- operation 215
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_AT}
-- operation 216
-- execution result=ResultSet
-- operation 217
-- actual default LDM_DEF_72377B8D28_AT.ID=<SQL NULL>
-- operation 218
-- actual default LDM_DEF_72377B8D28_AT.OWNER_NAME=['O''Brien']
-- operation 219
-- actual default LDM_DEF_72377B8D28_AT.VIP_LEVEL=[' ' ]
-- operation 220
-- actual default LDM_DEF_72377B8D28_AT.MANUAL_FLAG=['0' ]
-- operation 221
-- actual default LDM_DEF_72377B8D28_AT.AUTO_USED_FLAG=[0 ]
-- operation 222
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 223
-- execution result=ResultSet
-- operation 224
-- JDBC metadata getSearchStringEscape null
-- operation 225
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AS, %]
-- operation 226
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 227
SELECT * FROM "LDM_DEF_72377B8D28_AC" WHERE 1 = 0; -- bindings={}
-- operation 228
-- operation failed=ORA-00942: 表或视图不存在
-- operation 229
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 230
-- execution result=ResultSet
-- operation 231
-- expected preparation failure: 归档表结构校验未通过，共发现 1 个问题，已一次性输出到任务日志
-- operation 232
-- actual generated script; executed statements are recorded separately
-- Light DB Migration 归档表结构同步建议脚本
-- 数据库类型：ORACLE
-- 以源物理表字段为准；工具不会未经确认执行，归档写入和删除尚未开始。
-- 字段数量、名称、类型、精度、小数位及可空性之外的属性不在本次比对范围。

-- 上述结构差异生成 DDL 时携带已确认的源默认值；仅默认值不同不会单独触发差异。

-- 本次校验问题
-- 1. 归档节点 1（LDM_DEF_72377B8D28_AS）归档表 LDM_DEF_72377B8D28_AC 无法读取字段：ORA-00942: 表或视图不存在

-- 建议 DDL
-- 先确认归档表确实不存在且账号具有查询权限；主键、索引、自增、字符集及注释须人工补齐。
CREATE TABLE "LDM_DEF_72377B8D28_AC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR2(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR2(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
);


-- operation 233
CREATE TABLE "LDM_DEF_72377B8D28_AC" (
    "ID" NUMBER(10,0) NOT NULL,
    "OWNER_NAME" VARCHAR2(30) DEFAULT 'O''Brien' NOT NULL,
    "VIP_LEVEL" VARCHAR2(3) DEFAULT ' ' NOT NULL,
    "MANUAL_FLAG" CHAR(1) DEFAULT '0' NOT NULL,
    "AUTO_USED_FLAG" NUMBER(2,0) DEFAULT 0 NOT NULL
); -- bindings={}
-- operation 234
-- execution result=0
-- operation 235
SELECT COUNT(*) FROM LDM_DEF_72377B8D28_AC; -- bindings={}
-- operation 236
-- execution result=ResultSet
-- operation 237
-- count=0
-- operation 238
INSERT INTO LDM_DEF_72377B8D28_AC (ID) VALUES (1); -- bindings={}
-- operation 239
-- execution result=1
-- operation 240
SELECT OWNER_NAME,VIP_LEVEL,MANUAL_FLAG,AUTO_USED_FLAG FROM LDM_DEF_72377B8D28_AC WHERE ID=?; -- bindings={1=1}
-- operation 241
-- execution result=ResultSet
-- operation 242
-- actual row owner=[O'Brien]; vip=[ ]; manual=[0]; automatic=0
-- operation 243
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 244
-- execution result=ResultSet
-- operation 245
-- JDBC metadata getSearchStringEscape null
-- operation 246
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AS, %]
-- operation 247
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 248
SELECT * FROM "LDM_DEF_72377B8D28_AC" WHERE 1 = 0; -- bindings={}
-- operation 249
-- execution result=ResultSet
-- operation 250
-- JDBC metadata getSearchStringEscape null
-- operation 251
-- JDBC metadata getColumns [null, HS/_CCB, LDM/_DEF/_72377B8D28/_AC, %]
-- operation 252
-- JDBC getColumns returned ResultSet; application reads actual COLUMN_DEF
-- operation 253
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 254
-- execution result=ResultSet
-- operation 255
SELECT * FROM "LDM_DEF_72377B8D28_AS" WHERE 1 = 0; -- bindings={}
-- operation 256
-- execution result=ResultSet
-- operation 257
SELECT * FROM "LDM_DEF_72377B8D28_AC" WHERE 1 = 0; -- bindings={}
-- operation 258
-- execution result=ResultSet
-- operation 259
SELECT * FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_META WHERE 1 = 0; -- bindings={}
-- operation 260
-- execution result=ResultSet
-- operation 261
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ID"), COUNT(DISTINCT LM_ARCHIVE_STATS."ID") FROM (SELECT * FROM LDM_DEF_72377B8D28_AS) LM_ARCHIVE_STATS; -- bindings={}
-- operation 262
-- execution result=ResultSet
-- operation 263
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_AS}
-- operation 264
-- execution result=ResultSet
-- operation 265
-- actual default LDM_DEF_72377B8D28_AS.ID=<SQL NULL>
-- operation 266
-- actual default LDM_DEF_72377B8D28_AS.OWNER_NAME=['O''Brien' ]
-- operation 267
-- actual default LDM_DEF_72377B8D28_AS.VIP_LEVEL=[' ' ]
-- operation 268
-- actual default LDM_DEF_72377B8D28_AS.MANUAL_FLAG=['0' ]
-- operation 269
-- actual default LDM_DEF_72377B8D28_AS.AUTO_USED_FLAG=[0 ]
-- operation 270
SELECT COLUMN_NAME,DATA_DEFAULT FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID; -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_AC}
-- operation 271
-- execution result=ResultSet
-- operation 272
-- actual default LDM_DEF_72377B8D28_AC.ID=<SQL NULL>
-- operation 273
-- actual default LDM_DEF_72377B8D28_AC.OWNER_NAME=['O''Brien' ]
-- operation 274
-- actual default LDM_DEF_72377B8D28_AC.VIP_LEVEL=[' ' ]
-- operation 275
-- actual default LDM_DEF_72377B8D28_AC.MANUAL_FLAG=['0' ]
-- operation 276
-- actual default LDM_DEF_72377B8D28_AC.AUTO_USED_FLAG=[0 ]
-- operation 277
DROP TABLE LDM_DEF_72377B8D28_AC; -- bindings={}
-- operation 278
-- execution result=0
-- operation 279
DROP TABLE LDM_DEF_72377B8D28_AT; -- bindings={}
-- operation 280
-- execution result=0
-- operation 281
DROP TABLE LDM_DEF_72377B8D28_AS; -- bindings={}
-- operation 282
-- execution result=0
-- operation 283
DROP TABLE LDM_DEF_72377B8D28_MC; -- bindings={}
-- operation 284
-- execution result=0
-- operation 285
DROP TABLE LDM_DEF_72377B8D28_MT; -- bindings={}
-- operation 286
-- execution result=0
-- operation 287
DROP TABLE LDM_DEF_72377B8D28_MS; -- bindings={}
-- operation 288
-- execution result=0
-- operation 289
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?,?,?,?,?); -- bindings={1=<REDACTED>, 2=LDM_DEF_72377B8D28_MS, 3=LDM_DEF_72377B8D28_MT, 4=LDM_DEF_72377B8D28_MC, 5=LDM_DEF_72377B8D28_AS, 6=LDM_DEF_72377B8D28_AT, 7=LDM_DEF_72377B8D28_AC}
-- operation 290
-- execution result=ResultSet
-- operation 291
-- exact table remaining=0
```

## 本轮 Oracle 回收站精确清理

以下只查询并清理上述六张本轮测试表的回收站对象；未清理其他对象。

```sql
SELECT OBJECT_NAME, ORIGINAL_NAME FROM USER_RECYCLEBIN WHERE ORIGINAL_NAME IN (?,?,?,?,?,?) AND TYPE = 'TABLE'; -- bindings=[LDM_DEF_72377B8D28_MS, LDM_DEF_72377B8D28_MT, LDM_DEF_72377B8D28_MC, LDM_DEF_72377B8D28_AS, LDM_DEF_72377B8D28_AT, LDM_DEF_72377B8D28_AC]
-- returned: original=LDM_DEF_72377B8D28_AC; object=BIN$Xwpif2bxG7LgYziQFAr0NQ==$0
-- returned: original=LDM_DEF_72377B8D28_AT; object=BIN$Xwpif2b5G7LgYziQFAr0NQ==$0
-- returned: original=LDM_DEF_72377B8D28_AS; object=BIN$Xwpif2cBG7LgYziQFAr0NQ==$0
-- returned: original=LDM_DEF_72377B8D28_MC; object=BIN$Xwpif2cHG7LgYziQFAr0NQ==$0
-- returned: original=LDM_DEF_72377B8D28_MT; object=BIN$Xwpif2cPG7LgYziQFAr0NQ==$0
-- returned: original=LDM_DEF_72377B8D28_MS; object=BIN$Xwpif2cXG7LgYziQFAr0NQ==$0
PURGE TABLE "BIN$Xwpif2bxG7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
PURGE TABLE "BIN$Xwpif2b5G7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
PURGE TABLE "BIN$Xwpif2cBG7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
PURGE TABLE "BIN$Xwpif2cHG7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
PURGE TABLE "BIN$Xwpif2cPG7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
PURGE TABLE "BIN$Xwpif2cXG7LgYziQFAr0NQ==$0"; -- bindings=[]
-- result=0
SELECT OBJECT_NAME, ORIGINAL_NAME FROM USER_RECYCLEBIN WHERE ORIGINAL_NAME IN (?,?,?,?,?,?) AND TYPE = 'TABLE'; -- bindings=[LDM_DEF_72377B8D28_MS, LDM_DEF_72377B8D28_MT, LDM_DEF_72377B8D28_MC, LDM_DEF_72377B8D28_AS, LDM_DEF_72377B8D28_AT, LDM_DEF_72377B8D28_AC]
-- exact test recyclebin objects remaining=0
```
