
# 达梦查询元数据真实验证

日期：2026-10-28；前缀：`LDM_DMQ_9D7148F990`。仅一次物理登录；公开执行服务只复用该连接的逻辑代理。

驱动元数据真实读取，不伪造 precision/scale。以下记录全部应用 SQL、绑定参数、JDBC 元数据 API、执行结果及清理顺序；驱动内部初始化和元数据内部 SQL 无法获取原文。Schema/账号/地址/密码以环境占位符脱敏，Schema 绑定使用 <SCHEMA>。

- 候选 1：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S`
- 候选 2：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S`
- 候选 3：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S`
- 候选 4：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S`
- 已复现并选用实际 precision=0/scale=-127 查询元数据；没有兼容任何已知但不相等的精度。
- 最终选用查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S`
- 选中查询与源物理表的 ACT_ID、日期数字、金额和 LABEL 值完全相同，共 1 行。
- initial-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- repaired-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- ACT_ID 精度、PHONE_SERVICE_VALUE 精度/小数位、LABEL 长度/可空性三项真实物理差异严格报错，产生三条 ALTER 并经公开确认执行成功；源1行/目标0行未变；修复后两阶段预检通过，无查询类型误报。
- 完整归档两阶段预检、物理差异严格失败、公开结构建议执行及重新预检全部通过。
- 两张隔离表全部清理，精确表名目录查询残留数量=0。

```sql
-- operation 1
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_9D7148F990_S, 3=LDM_DMQ_9D7148F990_T}
-- operation 2
-- execution result: ResultSet returned
-- operation 3
CREATE TABLE LDM_DMQ_9D7148F990_S (ACT_ID NUMBER(20,0) NOT NULL PRIMARY KEY, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 4
-- execution result: 0
-- operation 5
CREATE TABLE LDM_DMQ_9D7148F990_T (ACT_ID NUMBER(20,0) NOT NULL, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 6
-- execution result: 0
-- operation 7
INSERT INTO LDM_DMQ_9D7148F990_S VALUES (12345678901234567890,20261028,20261028103045,12.3456,'dm-query-test');
-- operation 8
-- execution result: 1
-- operation 9
-- JDBC commit null
-- operation 10
-- seed result: source=1 row, target=0 rows
-- operation 11
SELECT * FROM LDM_DMQ_9D7148F990_S WHERE 1=0;
-- operation 12
-- execution result: ResultSet returned
-- operation 13
-- actual metadata [physical source] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0
-- operation 14
-- actual metadata [physical source] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1
-- operation 15
-- actual metadata [physical source] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0
-- operation 16
-- actual metadata [physical source] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1
-- operation 17
-- actual metadata [physical source] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 18
SELECT * FROM LDM_DMQ_9D7148F990_T WHERE 1=0;
-- operation 19
-- execution result: ResultSet returned
-- operation 20
-- actual metadata [physical target] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0
-- operation 21
-- actual metadata [physical target] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1
-- operation 22
-- actual metadata [physical target] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0
-- operation 23
-- actual metadata [physical target] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1
-- operation 24
-- actual metadata [physical target] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 25
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 26
-- execution result: ResultSet returned
-- operation 27
-- actual metadata [candidate 1] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 28
-- actual metadata [candidate 1] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 29
-- actual metadata [candidate 1] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 30
-- actual metadata [candidate 1] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 31
-- actual metadata [candidate 1] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 32
SELECT * FROM (SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 33
-- execution result: ResultSet returned
-- operation 34
-- actual metadata [candidate 2] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 35
-- actual metadata [candidate 2] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 36
-- actual metadata [candidate 2] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 37
-- actual metadata [candidate 2] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 38
-- actual metadata [candidate 2] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 39
SELECT * FROM (SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 40
-- execution result: ResultSet returned
-- operation 41
-- actual metadata [candidate 3] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 42
-- actual metadata [candidate 3] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 43
-- actual metadata [candidate 3] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 44
-- actual metadata [candidate 3] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 45
-- actual metadata [candidate 3] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 46
SELECT * FROM (SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 47
-- execution result: ResultSet returned
-- operation 48
-- actual metadata [candidate 4] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 49
-- actual metadata [candidate 4] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 50
-- actual metadata [candidate 4] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 51
-- actual metadata [candidate 4] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1
-- operation 52
-- actual metadata [candidate 4] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 53
SELECT * FROM LDM_DMQ_9D7148F990_S;
-- operation 54
-- execution result: ResultSet returned
-- operation 55
SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S;
-- operation 56
-- execution result: ResultSet returned
-- operation 57
SELECT * FROM "LDM_DMQ_9D7148F990_S" WHERE 1 = 0;
-- operation 58
-- execution result: ResultSet returned
-- operation 59
SELECT * FROM "LDM_DMQ_9D7148F990_T" WHERE 1 = 0;
-- operation 60
-- execution result: ResultSet returned
-- operation 61
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 62
-- execution result: ResultSet returned
-- operation 63
SELECT * FROM "LDM_DMQ_9D7148F990_S" WHERE 1 = 0;
-- operation 64
-- execution result: ResultSet returned
-- operation 65
SELECT * FROM "LDM_DMQ_9D7148F990_T" WHERE 1 = 0;
-- operation 66
-- execution result: ResultSet returned
-- operation 67
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 68
-- execution result: ResultSet returned
-- operation 69
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_9D7148F990_S]
-- operation 70
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_STATS;
-- operation 71
-- execution result: ResultSet returned
-- operation 72
ALTER TABLE LDM_DMQ_9D7148F990_T MODIFY (ACT_ID NUMBER(10,0));
-- operation 73
-- execution result: 0
-- operation 74
ALTER TABLE LDM_DMQ_9D7148F990_T MODIFY (PHONE_SERVICE_VALUE NUMBER(8,2));
-- operation 75
-- execution result: 0
-- operation 76
ALTER TABLE LDM_DMQ_9D7148F990_T MODIFY (LABEL VARCHAR(10) NOT NULL);
-- operation 77
-- execution result: 0
-- operation 78
SELECT * FROM LDM_DMQ_9D7148F990_T WHERE 1=0;
-- operation 79
-- execution result: ResultSet returned
-- operation 80
-- actual metadata [physical target after deliberate changes] ACT_ID=NUMBER(10,0),jdbcType=2,nullable=0
-- operation 81
-- actual metadata [physical target after deliberate changes] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1
-- operation 82
-- actual metadata [physical target after deliberate changes] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0
-- operation 83
-- actual metadata [physical target after deliberate changes] PHONE_SERVICE_VALUE=NUMBER(8,2),jdbcType=2,nullable=1
-- operation 84
-- actual metadata [physical target after deliberate changes] LABEL=VARCHAR(10,0),jdbcType=12,nullable=0
-- operation 85
SELECT * FROM "LDM_DMQ_9D7148F990_S" WHERE 1 = 0;
-- operation 86
-- execution result: ResultSet returned
-- operation 87
SELECT * FROM "LDM_DMQ_9D7148F990_T" WHERE 1 = 0;
-- operation 88
-- execution result: ResultSet returned
-- operation 89
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 90
-- execution result: ResultSet returned
-- operation 91
SELECT COUNT(*) FROM LDM_DMQ_9D7148F990_S;
-- operation 92
-- execution result: ResultSet returned
-- operation 93
SELECT COUNT(*) FROM LDM_DMQ_9D7148F990_T;
-- operation 94
-- execution result: ResultSet returned
-- operation 95
-- StructureSyncService requested connection; reused sole physical DM connection
-- operation 96
-- JDBC setAutoCommit [true]
-- operation 97
ALTER TABLE "LDM_DMQ_9D7148F990_T" MODIFY ("ACT_ID" NUMBER(20,0));
-- operation 98
-- execution result: false
-- operation 99
ALTER TABLE "LDM_DMQ_9D7148F990_T" MODIFY ("PHONE_SERVICE_VALUE" NUMBER(12,4));
-- operation 100
-- execution result: false
-- operation 101
ALTER TABLE "LDM_DMQ_9D7148F990_T" MODIFY ("LABEL" VARCHAR(20) NULL);
-- operation 102
-- execution result: false
-- operation 103
-- JDBC logical close; sole physical connection retained by fixture
-- operation 104
SELECT * FROM LDM_DMQ_9D7148F990_S WHERE 1=0;
-- operation 105
-- execution result: ResultSet returned
-- operation 106
-- actual metadata [source after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0
-- operation 107
-- actual metadata [source after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1
-- operation 108
-- actual metadata [source after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0
-- operation 109
-- actual metadata [source after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1
-- operation 110
-- actual metadata [source after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 111
SELECT * FROM LDM_DMQ_9D7148F990_T WHERE 1=0;
-- operation 112
-- execution result: ResultSet returned
-- operation 113
-- actual metadata [target after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0
-- operation 114
-- actual metadata [target after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1
-- operation 115
-- actual metadata [target after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0
-- operation 116
-- actual metadata [target after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1
-- operation 117
-- actual metadata [target after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1
-- operation 118
SELECT * FROM "LDM_DMQ_9D7148F990_S" WHERE 1 = 0;
-- operation 119
-- execution result: ResultSet returned
-- operation 120
SELECT * FROM "LDM_DMQ_9D7148F990_T" WHERE 1 = 0;
-- operation 121
-- execution result: ResultSet returned
-- operation 122
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 123
-- execution result: ResultSet returned
-- operation 124
SELECT * FROM "LDM_DMQ_9D7148F990_S" WHERE 1 = 0;
-- operation 125
-- execution result: ResultSet returned
-- operation 126
SELECT * FROM "LDM_DMQ_9D7148F990_T" WHERE 1 = 0;
-- operation 127
-- execution result: ResultSet returned
-- operation 128
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 129
-- execution result: ResultSet returned
-- operation 130
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_9D7148F990_S]
-- operation 131
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_9D7148F990_S) LM_ARCHIVE_STATS;
-- operation 132
-- execution result: ResultSet returned
-- operation 133
SELECT COUNT(*) FROM LDM_DMQ_9D7148F990_S;
-- operation 134
-- execution result: ResultSet returned
-- operation 135
SELECT COUNT(*) FROM LDM_DMQ_9D7148F990_T;
-- operation 136
-- execution result: ResultSet returned
-- operation 137
-- JDBC rollback skipped: autoCommit=true
-- operation 138
DROP TABLE LDM_DMQ_9D7148F990_T;
-- operation 139
-- execution result: 0
-- operation 140
DROP TABLE LDM_DMQ_9D7148F990_S;
-- operation 141
-- execution result: 0
-- operation 142
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_9D7148F990_S, 3=LDM_DMQ_9D7148F990_T}
-- operation 143
-- execution result: ResultSet returned
-- operation 144
-- cleanup catalog result: 0
```

# 达梦查询元数据真实验证

日期：2026-10-28；前缀：`LDM_DMQ_5F8674D791`。仅一次物理登录；公开执行服务只复用该连接的逻辑代理。

驱动元数据真实读取，不伪造 precision/scale。以下记录全部应用 SQL、绑定参数、JDBC 元数据 API、执行结果及清理顺序；驱动内部初始化和元数据内部 SQL 无法获取原文。Schema/账号/地址/密码以环境占位符脱敏，Schema 绑定使用 <SCHEMA>。

- 候选 1：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S`
- 候选 2：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S`
- 候选 3：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S`
- 候选 4：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S`
- 已复现并选用实际 precision=0/scale=-127 查询元数据；没有兼容任何已知但不相等的精度。
- 最终选用查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S`
- 选中查询与源物理表的 ACT_ID、日期数字、金额和 LABEL 值完全相同，共 1 行。
- initial-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- 本轮失败：表结构校验未通过，共发现 4 个问题，已一次性输出到任务日志
- 两张隔离表全部清理，精确表名目录查询残留数量=0。

```sql
-- operation 1
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_5F8674D791_S, 3=LDM_DMQ_5F8674D791_T}
-- operation 2
-- execution result: ResultSet returned
-- operation 3
CREATE TABLE LDM_DMQ_5F8674D791_S (ACT_ID NUMBER(20,0) NOT NULL PRIMARY KEY, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 4
-- execution result: 0
-- operation 5
CREATE TABLE LDM_DMQ_5F8674D791_T (ACT_ID NUMBER(20,0) NOT NULL, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 6
-- execution result: 0
-- operation 7
INSERT INTO LDM_DMQ_5F8674D791_S VALUES (12345678901234567890,20261028,20261028103045,12.3456,'dm-query-test');
-- operation 8
-- execution result: 1
-- operation 9
-- JDBC commit null
-- operation 10
-- seed result: source=1 row, target=0 rows
-- operation 11
SELECT * FROM LDM_DMQ_5F8674D791_S WHERE 1=0;
-- operation 12
-- execution result: ResultSet returned
-- operation 13
-- actual metadata [physical source] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_S,column=ACT_ID
-- operation 14
-- actual metadata [physical source] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_S,column=VISIT_BEGIN_DATE
-- operation 15
-- actual metadata [physical source] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_S,column=CREATE_DATE_TIME
-- operation 16
-- actual metadata [physical source] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_S,column=PHONE_SERVICE_VALUE
-- operation 17
-- actual metadata [physical source] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_S,column=LABEL
-- operation 18
SELECT * FROM LDM_DMQ_5F8674D791_T WHERE 1=0;
-- operation 19
-- execution result: ResultSet returned
-- operation 20
-- actual metadata [physical target] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_T,column=ACT_ID
-- operation 21
-- actual metadata [physical target] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_T,column=VISIT_BEGIN_DATE
-- operation 22
-- actual metadata [physical target] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_T,column=CREATE_DATE_TIME
-- operation 23
-- actual metadata [physical target] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_T,column=PHONE_SERVICE_VALUE
-- operation 24
-- actual metadata [physical target] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_5F8674D791_T,column=LABEL
-- operation 25
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 26
-- execution result: ResultSet returned
-- operation 27
-- actual metadata [candidate 1] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 28
-- actual metadata [candidate 1] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 29
-- actual metadata [candidate 1] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 30
-- actual metadata [candidate 1] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 31
-- actual metadata [candidate 1] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 32
SELECT * FROM (SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 33
-- execution result: ResultSet returned
-- operation 34
-- actual metadata [candidate 2] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 35
-- actual metadata [candidate 2] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 36
-- actual metadata [candidate 2] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 37
-- actual metadata [candidate 2] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 38
-- actual metadata [candidate 2] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 39
SELECT * FROM (SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 40
-- execution result: ResultSet returned
-- operation 41
-- actual metadata [candidate 3] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 42
-- actual metadata [candidate 3] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 43
-- actual metadata [candidate 3] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 44
-- actual metadata [candidate 3] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 45
-- actual metadata [candidate 3] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 46
SELECT * FROM (SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 47
-- execution result: ResultSet returned
-- operation 48
-- actual metadata [candidate 4] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 49
-- actual metadata [candidate 4] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 50
-- actual metadata [candidate 4] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 51
-- actual metadata [candidate 4] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 52
-- actual metadata [candidate 4] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 53
SELECT * FROM LDM_DMQ_5F8674D791_S;
-- operation 54
-- execution result: ResultSet returned
-- operation 55
SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S;
-- operation 56
-- execution result: ResultSet returned
-- operation 57
SELECT * FROM "LDM_DMQ_5F8674D791_S" WHERE 1 = 0;
-- operation 58
-- execution result: ResultSet returned
-- operation 59
SELECT * FROM "LDM_DMQ_5F8674D791_T" WHERE 1 = 0;
-- operation 60
-- execution result: ResultSet returned
-- operation 61
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 62
-- execution result: ResultSet returned
-- operation 63
SELECT * FROM "LDM_DMQ_5F8674D791_S" WHERE 1 = 0;
-- operation 64
-- execution result: ResultSet returned
-- operation 65
SELECT * FROM "LDM_DMQ_5F8674D791_T" WHERE 1 = 0;
-- operation 66
-- execution result: ResultSet returned
-- operation 67
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 68
-- execution result: ResultSet returned
-- operation 69
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_5F8674D791_S]
-- operation 70
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_ARCHIVE_STATS;
-- operation 71
-- execution result: ResultSet returned
-- operation 72
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_SOURCE WHERE 1 = 0;
-- operation 73
-- execution result: ResultSet returned
-- operation 74
SELECT * FROM LDM_DMQ_5F8674D791_T WHERE 1 = 0;
-- operation 75
-- execution result: ResultSet returned
-- operation 76
SELECT COUNT(*) FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_5F8674D791_S) LM_STATS;
-- operation 77
-- execution result: ResultSet returned
-- operation 78
-- JDBC rollback skipped: autoCommit=true
-- operation 79
DROP TABLE LDM_DMQ_5F8674D791_T;
-- operation 80
-- execution result: 0
-- operation 81
DROP TABLE LDM_DMQ_5F8674D791_S;
-- operation 82
-- execution result: 0
-- operation 83
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_5F8674D791_S, 3=LDM_DMQ_5F8674D791_T}
-- operation 84
-- execution result: ResultSet returned
-- operation 85
-- cleanup catalog result: 0
```

# 达梦查询元数据真实验证

日期：2026-10-28；前缀：`LDM_DMQ_E6CC304556`。仅一次物理登录；公开执行服务只复用该连接的逻辑代理。

驱动元数据真实读取，不伪造 precision/scale。以下记录全部应用 SQL、绑定参数、JDBC 元数据 API、执行结果及清理顺序；驱动内部初始化和元数据内部 SQL 无法获取原文。Schema/账号/地址/密码以环境占位符脱敏，Schema 绑定使用 <SCHEMA>。

- 候选 1：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S`
- 候选 2：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S`
- 候选 3：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S`
- 候选 4：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S`
- 已复现并选用实际 precision=0/scale=-127 查询元数据；没有兼容任何已知但不相等的精度。
- 最终选用查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S`
- 选中查询与源物理表的 ACT_ID、日期数字、金额和 LABEL 值完全相同，共 1 行。
- initial-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- initial-migration-compatible：源/目标物理字段相同，迁移查询未知精度不误报，零数据写入。
- repaired-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- repaired-migration-compatible：源/目标物理字段相同，迁移查询未知精度不误报，零数据写入。
- ACT_ID 精度、PHONE_SERVICE_VALUE 精度/小数位、LABEL 长度/可空性三项真实物理差异严格报错，产生三条 ALTER 并经公开确认执行成功；源1行/目标0行未变；修复后两阶段预检通过，无查询类型误报。
- 完整归档两阶段预检、物理差异严格失败、公开结构建议执行及重新预检全部通过。
- 两张隔离表全部清理，精确表名目录查询残留数量=0。

```sql
-- operation 1
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_E6CC304556_S, 3=LDM_DMQ_E6CC304556_T}
-- operation 2
-- execution result: ResultSet returned
-- operation 3
CREATE TABLE LDM_DMQ_E6CC304556_S (ACT_ID NUMBER(20,0) NOT NULL PRIMARY KEY, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 4
-- execution result: 0
-- operation 5
CREATE TABLE LDM_DMQ_E6CC304556_T (ACT_ID NUMBER(20,0) NOT NULL, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 6
-- execution result: 0
-- operation 7
INSERT INTO LDM_DMQ_E6CC304556_S VALUES (12345678901234567890,20261028,20261028103045,12.3456,'dm-query-test');
-- operation 8
-- execution result: 1
-- operation 9
-- JDBC commit null
-- operation 10
-- seed result: source=1 row, target=0 rows
-- operation 11
SELECT * FROM LDM_DMQ_E6CC304556_S WHERE 1=0;
-- operation 12
-- execution result: ResultSet returned
-- operation 13
-- actual metadata [physical source] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=ACT_ID
-- operation 14
-- actual metadata [physical source] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=VISIT_BEGIN_DATE
-- operation 15
-- actual metadata [physical source] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=CREATE_DATE_TIME
-- operation 16
-- actual metadata [physical source] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=PHONE_SERVICE_VALUE
-- operation 17
-- actual metadata [physical source] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=LABEL
-- operation 18
SELECT * FROM LDM_DMQ_E6CC304556_T WHERE 1=0;
-- operation 19
-- execution result: ResultSet returned
-- operation 20
-- actual metadata [physical target] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=ACT_ID
-- operation 21
-- actual metadata [physical target] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=VISIT_BEGIN_DATE
-- operation 22
-- actual metadata [physical target] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=CREATE_DATE_TIME
-- operation 23
-- actual metadata [physical target] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=PHONE_SERVICE_VALUE
-- operation 24
-- actual metadata [physical target] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=LABEL
-- operation 25
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 26
-- execution result: ResultSet returned
-- operation 27
-- actual metadata [candidate 1] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 28
-- actual metadata [candidate 1] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 29
-- actual metadata [candidate 1] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 30
-- actual metadata [candidate 1] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 31
-- actual metadata [candidate 1] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 32
SELECT * FROM (SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 33
-- execution result: ResultSet returned
-- operation 34
-- actual metadata [candidate 2] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 35
-- actual metadata [candidate 2] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 36
-- actual metadata [candidate 2] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 37
-- actual metadata [candidate 2] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 38
-- actual metadata [candidate 2] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 39
SELECT * FROM (SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 40
-- execution result: ResultSet returned
-- operation 41
-- actual metadata [candidate 3] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 42
-- actual metadata [candidate 3] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 43
-- actual metadata [candidate 3] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 44
-- actual metadata [candidate 3] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 45
-- actual metadata [candidate 3] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 46
SELECT * FROM (SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 47
-- execution result: ResultSet returned
-- operation 48
-- actual metadata [candidate 4] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 49
-- actual metadata [candidate 4] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 50
-- actual metadata [candidate 4] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 51
-- actual metadata [candidate 4] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 52
-- actual metadata [candidate 4] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 53
SELECT * FROM LDM_DMQ_E6CC304556_S;
-- operation 54
-- execution result: ResultSet returned
-- operation 55
SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S;
-- operation 56
-- execution result: ResultSet returned
-- operation 57
SELECT * FROM "LDM_DMQ_E6CC304556_S" WHERE 1 = 0;
-- operation 58
-- execution result: ResultSet returned
-- operation 59
SELECT * FROM "LDM_DMQ_E6CC304556_T" WHERE 1 = 0;
-- operation 60
-- execution result: ResultSet returned
-- operation 61
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 62
-- execution result: ResultSet returned
-- operation 63
SELECT * FROM "LDM_DMQ_E6CC304556_S" WHERE 1 = 0;
-- operation 64
-- execution result: ResultSet returned
-- operation 65
SELECT * FROM "LDM_DMQ_E6CC304556_T" WHERE 1 = 0;
-- operation 66
-- execution result: ResultSet returned
-- operation 67
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 68
-- execution result: ResultSet returned
-- operation 69
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_E6CC304556_S]
-- operation 70
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_STATS;
-- operation 71
-- execution result: ResultSet returned
-- operation 72
-- JDBC PreparedStatement.getMetaData; sql=SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S
-- operation 73
SELECT * FROM LDM_DMQ_E6CC304556_T WHERE 1 = 0;
-- operation 74
-- execution result: ResultSet returned
-- operation 75
SELECT COUNT(*) FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_STATS;
-- operation 76
-- execution result: ResultSet returned
-- operation 77
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_E6CC304556_T]
-- operation 78
-- JDBC metadata getIndexInfo [null, <SCHEMA>, LDM_DMQ_E6CC304556_T, true, false]
-- operation 79
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, ldm_dmq_e6cc304556_t]
-- operation 80
-- JDBC metadata getIndexInfo [null, <SCHEMA>, ldm_dmq_e6cc304556_t, true, false]
-- operation 81
ALTER TABLE LDM_DMQ_E6CC304556_T MODIFY (ACT_ID NUMBER(10,0));
-- operation 82
-- execution result: 0
-- operation 83
ALTER TABLE LDM_DMQ_E6CC304556_T MODIFY (PHONE_SERVICE_VALUE NUMBER(8,2));
-- operation 84
-- execution result: 0
-- operation 85
ALTER TABLE LDM_DMQ_E6CC304556_T MODIFY (LABEL VARCHAR(10) NOT NULL);
-- operation 86
-- execution result: 0
-- operation 87
SELECT * FROM LDM_DMQ_E6CC304556_T WHERE 1=0;
-- operation 88
-- execution result: ResultSet returned
-- operation 89
-- actual metadata [physical target after deliberate changes] ACT_ID=NUMBER(10,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=ACT_ID
-- operation 90
-- actual metadata [physical target after deliberate changes] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=VISIT_BEGIN_DATE
-- operation 91
-- actual metadata [physical target after deliberate changes] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=CREATE_DATE_TIME
-- operation 92
-- actual metadata [physical target after deliberate changes] PHONE_SERVICE_VALUE=NUMBER(8,2),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=PHONE_SERVICE_VALUE
-- operation 93
-- actual metadata [physical target after deliberate changes] LABEL=VARCHAR(10,0),jdbcType=12,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=LABEL
-- operation 94
SELECT * FROM "LDM_DMQ_E6CC304556_S" WHERE 1 = 0;
-- operation 95
-- execution result: ResultSet returned
-- operation 96
SELECT * FROM "LDM_DMQ_E6CC304556_T" WHERE 1 = 0;
-- operation 97
-- execution result: ResultSet returned
-- operation 98
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 99
-- execution result: ResultSet returned
-- operation 100
SELECT COUNT(*) FROM LDM_DMQ_E6CC304556_S;
-- operation 101
-- execution result: ResultSet returned
-- operation 102
SELECT COUNT(*) FROM LDM_DMQ_E6CC304556_T;
-- operation 103
-- execution result: ResultSet returned
-- operation 104
-- StructureSyncService requested connection; reused sole physical DM connection
-- operation 105
-- JDBC setAutoCommit [true]
-- operation 106
ALTER TABLE "LDM_DMQ_E6CC304556_T" MODIFY ("ACT_ID" NUMBER(20,0));
-- operation 107
-- execution result: false
-- operation 108
ALTER TABLE "LDM_DMQ_E6CC304556_T" MODIFY ("PHONE_SERVICE_VALUE" NUMBER(12,4));
-- operation 109
-- execution result: false
-- operation 110
ALTER TABLE "LDM_DMQ_E6CC304556_T" MODIFY ("LABEL" VARCHAR(20) NULL);
-- operation 111
-- execution result: false
-- operation 112
-- JDBC logical close; sole physical connection retained by fixture
-- operation 113
SELECT * FROM LDM_DMQ_E6CC304556_S WHERE 1=0;
-- operation 114
-- execution result: ResultSet returned
-- operation 115
-- actual metadata [source after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=ACT_ID
-- operation 116
-- actual metadata [source after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=VISIT_BEGIN_DATE
-- operation 117
-- actual metadata [source after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=CREATE_DATE_TIME
-- operation 118
-- actual metadata [source after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=PHONE_SERVICE_VALUE
-- operation 119
-- actual metadata [source after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_S,column=LABEL
-- operation 120
SELECT * FROM LDM_DMQ_E6CC304556_T WHERE 1=0;
-- operation 121
-- execution result: ResultSet returned
-- operation 122
-- actual metadata [target after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=ACT_ID
-- operation 123
-- actual metadata [target after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=VISIT_BEGIN_DATE
-- operation 124
-- actual metadata [target after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=CREATE_DATE_TIME
-- operation 125
-- actual metadata [target after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=PHONE_SERVICE_VALUE
-- operation 126
-- actual metadata [target after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_E6CC304556_T,column=LABEL
-- operation 127
SELECT * FROM "LDM_DMQ_E6CC304556_S" WHERE 1 = 0;
-- operation 128
-- execution result: ResultSet returned
-- operation 129
SELECT * FROM "LDM_DMQ_E6CC304556_T" WHERE 1 = 0;
-- operation 130
-- execution result: ResultSet returned
-- operation 131
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 132
-- execution result: ResultSet returned
-- operation 133
SELECT * FROM "LDM_DMQ_E6CC304556_S" WHERE 1 = 0;
-- operation 134
-- execution result: ResultSet returned
-- operation 135
SELECT * FROM "LDM_DMQ_E6CC304556_T" WHERE 1 = 0;
-- operation 136
-- execution result: ResultSet returned
-- operation 137
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 138
-- execution result: ResultSet returned
-- operation 139
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_E6CC304556_S]
-- operation 140
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_ARCHIVE_STATS;
-- operation 141
-- execution result: ResultSet returned
-- operation 142
-- JDBC PreparedStatement.getMetaData; sql=SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S
-- operation 143
SELECT * FROM LDM_DMQ_E6CC304556_T WHERE 1 = 0;
-- operation 144
-- execution result: ResultSet returned
-- operation 145
SELECT COUNT(*) FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_E6CC304556_S) LM_STATS;
-- operation 146
-- execution result: ResultSet returned
-- operation 147
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_E6CC304556_T]
-- operation 148
-- JDBC metadata getIndexInfo [null, <SCHEMA>, LDM_DMQ_E6CC304556_T, true, false]
-- operation 149
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, ldm_dmq_e6cc304556_t]
-- operation 150
-- JDBC metadata getIndexInfo [null, <SCHEMA>, ldm_dmq_e6cc304556_t, true, false]
-- operation 151
SELECT COUNT(*) FROM LDM_DMQ_E6CC304556_S;
-- operation 152
-- execution result: ResultSet returned
-- operation 153
SELECT COUNT(*) FROM LDM_DMQ_E6CC304556_T;
-- operation 154
-- execution result: ResultSet returned
-- operation 155
-- JDBC rollback skipped: autoCommit=true
-- operation 156
DROP TABLE LDM_DMQ_E6CC304556_T;
-- operation 157
-- execution result: 0
-- operation 158
DROP TABLE LDM_DMQ_E6CC304556_S;
-- operation 159
-- execution result: 0
-- operation 160
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_E6CC304556_S, 3=LDM_DMQ_E6CC304556_T}
-- operation 161
-- execution result: ResultSet returned
-- operation 162
-- cleanup catalog result: 0
```

# 达梦查询元数据真实验证

日期：2026-10-28；前缀：`LDM_DMQ_F991690DB4`。仅一次物理登录；公开执行服务只复用该连接的逻辑代理。

驱动元数据真实读取，不伪造 precision/scale。以下记录全部应用 SQL、绑定参数、JDBC 元数据 API、执行结果及清理顺序；驱动内部初始化和元数据内部 SQL 无法获取原文。Schema/账号/地址/密码以环境占位符脱敏，Schema 绑定使用 <SCHEMA>。

- 候选 1：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S`
- 候选 2：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S`
- 候选 3：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S`
- 候选 4：实际未知数值标记字段数=4，其他已知字段定义匹配=true。查询：`SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S`
- 已复现并选用实际 precision=0/scale=-127 查询元数据；没有兼容任何已知但不相等的精度。
- 最终选用查询：`SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S`
- 选中查询与源物理表的 ACT_ID、日期数字、金额和 LABEL 值完全相同，共 1 行。
- initial-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- initial-migration-compatible：源/目标物理字段相同，迁移查询未知精度不误报，零数据写入。
- repaired-compatible：完整 ArchiveEngine.prepare 两阶段通过，零数据写入/删除。
- repaired-migration-compatible：源/目标物理字段相同，迁移查询未知精度不误报，零数据写入。
- ACT_ID 精度、PHONE_SERVICE_VALUE 精度/小数位、LABEL 长度/可空性三项真实物理差异严格报错，产生三条 ALTER 并经公开确认执行成功；源1行/目标0行未变；修复后两阶段预检通过，无查询类型误报。
- 完整归档两阶段预检、物理差异严格失败、公开结构建议执行及重新预检全部通过。
- 两张隔离表全部清理，精确表名目录查询残留数量=0。

```sql
-- operation 1
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_F991690DB4_S, 3=LDM_DMQ_F991690DB4_T}
-- operation 2
-- execution result: ResultSet returned
-- operation 3
CREATE TABLE LDM_DMQ_F991690DB4_S (ACT_ID NUMBER(20,0) NOT NULL PRIMARY KEY, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 4
-- execution result: 0
-- operation 5
CREATE TABLE LDM_DMQ_F991690DB4_T (ACT_ID NUMBER(20,0) NOT NULL, VISIT_BEGIN_DATE NUMBER(8,0) NULL, CREATE_DATE_TIME NUMBER(14,0) NOT NULL, PHONE_SERVICE_VALUE NUMBER(12,4) NULL, LABEL VARCHAR(20) NULL);
-- operation 6
-- execution result: 0
-- operation 7
INSERT INTO LDM_DMQ_F991690DB4_S VALUES (12345678901234567890,20261028,20261028103045,12.3456,'dm-query-test');
-- operation 8
-- execution result: 1
-- operation 9
-- JDBC commit null
-- operation 10
-- seed result: source=1 row, target=0 rows
-- operation 11
SELECT * FROM LDM_DMQ_F991690DB4_S WHERE 1=0;
-- operation 12
-- execution result: ResultSet returned
-- operation 13
-- actual metadata [physical source] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=ACT_ID
-- operation 14
-- actual metadata [physical source] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=VISIT_BEGIN_DATE
-- operation 15
-- actual metadata [physical source] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=CREATE_DATE_TIME
-- operation 16
-- actual metadata [physical source] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=PHONE_SERVICE_VALUE
-- operation 17
-- actual metadata [physical source] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=LABEL
-- operation 18
SELECT * FROM LDM_DMQ_F991690DB4_T WHERE 1=0;
-- operation 19
-- execution result: ResultSet returned
-- operation 20
-- actual metadata [physical target] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=ACT_ID
-- operation 21
-- actual metadata [physical target] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=VISIT_BEGIN_DATE
-- operation 22
-- actual metadata [physical target] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=CREATE_DATE_TIME
-- operation 23
-- actual metadata [physical target] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=PHONE_SERVICE_VALUE
-- operation 24
-- actual metadata [physical target] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=LABEL
-- operation 25
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 26
-- execution result: ResultSet returned
-- operation 27
-- actual metadata [candidate 1] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 28
-- actual metadata [candidate 1] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 29
-- actual metadata [candidate 1] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 30
-- actual metadata [candidate 1] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 31
-- actual metadata [candidate 1] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 32
SELECT * FROM (SELECT NVL(ACT_ID,ACT_ID) AS ACT_ID,NVL(VISIT_BEGIN_DATE,VISIT_BEGIN_DATE) AS VISIT_BEGIN_DATE,NVL(CREATE_DATE_TIME,CREATE_DATE_TIME) AS CREATE_DATE_TIME,NVL(PHONE_SERVICE_VALUE,PHONE_SERVICE_VALUE) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 33
-- execution result: ResultSet returned
-- operation 34
-- actual metadata [candidate 2] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 35
-- actual metadata [candidate 2] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 36
-- actual metadata [candidate 2] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 37
-- actual metadata [candidate 2] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 38
-- actual metadata [candidate 2] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 39
SELECT * FROM (SELECT CAST(ACT_ID AS NUMBER) AS ACT_ID,CAST(VISIT_BEGIN_DATE AS NUMBER) AS VISIT_BEGIN_DATE,CAST(CREATE_DATE_TIME AS NUMBER) AS CREATE_DATE_TIME,CAST(PHONE_SERVICE_VALUE AS NUMBER) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 40
-- execution result: ResultSet returned
-- operation 41
-- actual metadata [candidate 3] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 42
-- actual metadata [candidate 3] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 43
-- actual metadata [candidate 3] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 44
-- actual metadata [candidate 3] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 45
-- actual metadata [candidate 3] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 46
SELECT * FROM (SELECT (ACT_ID+0) AS ACT_ID,(VISIT_BEGIN_DATE+0) AS VISIT_BEGIN_DATE,(CREATE_DATE_TIME+0) AS CREATE_DATE_TIME,(PHONE_SERVICE_VALUE+0) AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1=0;
-- operation 47
-- execution result: ResultSet returned
-- operation 48
-- actual metadata [candidate 4] ACT_ID=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=ACT_ID
-- operation 49
-- actual metadata [candidate 4] VISIT_BEGIN_DATE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=VISIT_BEGIN_DATE
-- operation 50
-- actual metadata [candidate 4] CREATE_DATE_TIME=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=CREATE_DATE_TIME
-- operation 51
-- actual metadata [candidate 4] PHONE_SERVICE_VALUE=NUMBER(0,-127),jdbcType=2,nullable=1；origin=catalog=,schema=,table=,column=PHONE_SERVICE_VALUE
-- operation 52
-- actual metadata [candidate 4] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=,table=,column=LABEL
-- operation 53
SELECT * FROM LDM_DMQ_F991690DB4_S;
-- operation 54
-- execution result: ResultSet returned
-- operation 55
SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S;
-- operation 56
-- execution result: ResultSet returned
-- operation 57
SELECT * FROM "LDM_DMQ_F991690DB4_S" WHERE 1 = 0;
-- operation 58
-- execution result: ResultSet returned
-- operation 59
SELECT * FROM "LDM_DMQ_F991690DB4_T" WHERE 1 = 0;
-- operation 60
-- execution result: ResultSet returned
-- operation 61
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 62
-- execution result: ResultSet returned
-- operation 63
SELECT * FROM "LDM_DMQ_F991690DB4_S" WHERE 1 = 0;
-- operation 64
-- execution result: ResultSet returned
-- operation 65
SELECT * FROM "LDM_DMQ_F991690DB4_T" WHERE 1 = 0;
-- operation 66
-- execution result: ResultSet returned
-- operation 67
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 68
-- execution result: ResultSet returned
-- operation 69
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_F991690DB4_S]
-- operation 70
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_STATS;
-- operation 71
-- execution result: ResultSet returned
-- operation 72
-- JDBC PreparedStatement.getMetaData; sql=SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S
-- operation 73
SELECT * FROM LDM_DMQ_F991690DB4_T WHERE 1 = 0;
-- operation 74
-- execution result: ResultSet returned
-- operation 75
SELECT COUNT(*) FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_STATS;
-- operation 76
-- execution result: ResultSet returned
-- operation 77
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_F991690DB4_T]
-- operation 78
-- JDBC metadata getIndexInfo [null, <SCHEMA>, LDM_DMQ_F991690DB4_T, true, false]
-- operation 79
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, ldm_dmq_f991690db4_t]
-- operation 80
-- JDBC metadata getIndexInfo [null, <SCHEMA>, ldm_dmq_f991690db4_t, true, false]
-- operation 81
ALTER TABLE LDM_DMQ_F991690DB4_T MODIFY (ACT_ID NUMBER(10,0));
-- operation 82
-- execution result: 0
-- operation 83
ALTER TABLE LDM_DMQ_F991690DB4_T MODIFY (PHONE_SERVICE_VALUE NUMBER(8,2));
-- operation 84
-- execution result: 0
-- operation 85
ALTER TABLE LDM_DMQ_F991690DB4_T MODIFY (LABEL VARCHAR(10) NOT NULL);
-- operation 86
-- execution result: 0
-- operation 87
SELECT * FROM LDM_DMQ_F991690DB4_T WHERE 1=0;
-- operation 88
-- execution result: ResultSet returned
-- operation 89
-- actual metadata [physical target after deliberate changes] ACT_ID=NUMBER(10,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=ACT_ID
-- operation 90
-- actual metadata [physical target after deliberate changes] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=VISIT_BEGIN_DATE
-- operation 91
-- actual metadata [physical target after deliberate changes] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=CREATE_DATE_TIME
-- operation 92
-- actual metadata [physical target after deliberate changes] PHONE_SERVICE_VALUE=NUMBER(8,2),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=PHONE_SERVICE_VALUE
-- operation 93
-- actual metadata [physical target after deliberate changes] LABEL=VARCHAR(10,0),jdbcType=12,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=LABEL
-- operation 94
SELECT * FROM "LDM_DMQ_F991690DB4_S" WHERE 1 = 0;
-- operation 95
-- execution result: ResultSet returned
-- operation 96
SELECT * FROM "LDM_DMQ_F991690DB4_T" WHERE 1 = 0;
-- operation 97
-- execution result: ResultSet returned
-- operation 98
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 99
-- execution result: ResultSet returned
-- operation 100
SELECT COUNT(*) FROM LDM_DMQ_F991690DB4_S;
-- operation 101
-- execution result: ResultSet returned
-- operation 102
SELECT COUNT(*) FROM LDM_DMQ_F991690DB4_T;
-- operation 103
-- execution result: ResultSet returned
-- operation 104
-- StructureSyncService requested connection; reused sole physical DM connection
-- operation 105
-- JDBC setAutoCommit [true]
-- operation 106
ALTER TABLE "LDM_DMQ_F991690DB4_T" MODIFY ("ACT_ID" NUMBER(20,0));
-- operation 107
-- execution result: false
-- operation 108
ALTER TABLE "LDM_DMQ_F991690DB4_T" MODIFY ("PHONE_SERVICE_VALUE" NUMBER(12,4));
-- operation 109
-- execution result: false
-- operation 110
ALTER TABLE "LDM_DMQ_F991690DB4_T" MODIFY ("LABEL" VARCHAR(20) NULL);
-- operation 111
-- execution result: false
-- operation 112
-- JDBC logical close; sole physical connection retained by fixture
-- operation 113
SELECT * FROM LDM_DMQ_F991690DB4_S WHERE 1=0;
-- operation 114
-- execution result: ResultSet returned
-- operation 115
-- actual metadata [source after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=ACT_ID
-- operation 116
-- actual metadata [source after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=VISIT_BEGIN_DATE
-- operation 117
-- actual metadata [source after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=CREATE_DATE_TIME
-- operation 118
-- actual metadata [source after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=PHONE_SERVICE_VALUE
-- operation 119
-- actual metadata [source after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_S,column=LABEL
-- operation 120
SELECT * FROM LDM_DMQ_F991690DB4_T WHERE 1=0;
-- operation 121
-- execution result: ResultSet returned
-- operation 122
-- actual metadata [target after repair] ACT_ID=NUMBER(20,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=ACT_ID
-- operation 123
-- actual metadata [target after repair] VISIT_BEGIN_DATE=NUMBER(8,0),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=VISIT_BEGIN_DATE
-- operation 124
-- actual metadata [target after repair] CREATE_DATE_TIME=NUMBER(14,0),jdbcType=2,nullable=0；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=CREATE_DATE_TIME
-- operation 125
-- actual metadata [target after repair] PHONE_SERVICE_VALUE=NUMBER(12,4),jdbcType=2,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=PHONE_SERVICE_VALUE
-- operation 126
-- actual metadata [target after repair] LABEL=VARCHAR(20,0),jdbcType=12,nullable=1；origin=catalog=,schema=<SCHEMA>,table=LDM_DMQ_F991690DB4_T,column=LABEL
-- operation 127
SELECT * FROM "LDM_DMQ_F991690DB4_S" WHERE 1 = 0;
-- operation 128
-- execution result: ResultSet returned
-- operation 129
SELECT * FROM "LDM_DMQ_F991690DB4_T" WHERE 1 = 0;
-- operation 130
-- execution result: ResultSet returned
-- operation 131
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 132
-- execution result: ResultSet returned
-- operation 133
SELECT * FROM "LDM_DMQ_F991690DB4_S" WHERE 1 = 0;
-- operation 134
-- execution result: ResultSet returned
-- operation 135
SELECT * FROM "LDM_DMQ_F991690DB4_T" WHERE 1 = 0;
-- operation 136
-- execution result: ResultSet returned
-- operation 137
SELECT * FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_META WHERE 1 = 0;
-- operation 138
-- execution result: ResultSet returned
-- operation 139
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_F991690DB4_S]
-- operation 140
SELECT COUNT(*), COUNT(LM_ARCHIVE_STATS."ACT_ID") FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_ARCHIVE_STATS;
-- operation 141
-- execution result: ResultSet returned
-- operation 142
-- JDBC PreparedStatement.getMetaData; sql=SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S
-- operation 143
SELECT * FROM LDM_DMQ_F991690DB4_T WHERE 1 = 0;
-- operation 144
-- execution result: ResultSet returned
-- operation 145
SELECT COUNT(*) FROM (SELECT ACT_ID AS ACT_ID,VISIT_BEGIN_DATE AS VISIT_BEGIN_DATE,CREATE_DATE_TIME AS CREATE_DATE_TIME,PHONE_SERVICE_VALUE AS PHONE_SERVICE_VALUE,LABEL FROM LDM_DMQ_F991690DB4_S) LM_STATS;
-- operation 146
-- execution result: ResultSet returned
-- operation 147
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, LDM_DMQ_F991690DB4_T]
-- operation 148
-- JDBC metadata getIndexInfo [null, <SCHEMA>, LDM_DMQ_F991690DB4_T, true, false]
-- operation 149
-- JDBC metadata getPrimaryKeys [null, <SCHEMA>, ldm_dmq_f991690db4_t]
-- operation 150
-- JDBC metadata getIndexInfo [null, <SCHEMA>, ldm_dmq_f991690db4_t, true, false]
-- operation 151
SELECT COUNT(*) FROM LDM_DMQ_F991690DB4_S;
-- operation 152
-- execution result: ResultSet returned
-- operation 153
SELECT COUNT(*) FROM LDM_DMQ_F991690DB4_T;
-- operation 154
-- execution result: ResultSet returned
-- operation 155
-- JDBC rollback skipped: autoCommit=true
-- operation 156
DROP TABLE LDM_DMQ_F991690DB4_T;
-- operation 157
-- execution result: 0
-- operation 158
DROP TABLE LDM_DMQ_F991690DB4_S;
-- operation 159
-- execution result: 0
-- operation 160
SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME IN (?,?); -- bind={1=<SCHEMA>, 2=LDM_DMQ_F991690DB4_S, 3=LDM_DMQ_F991690DB4_T}
-- operation 161
-- execution result: ResultSet returned
-- operation 162
-- cleanup catalog result: 0
```
