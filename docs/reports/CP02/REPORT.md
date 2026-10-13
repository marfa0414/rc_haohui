# CP02 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。本记录覆盖 CP01–CP02 累计回归，不代表全部 95 个验收用例通过。

环境沿用 [CP01](../CP01/REPORT.md)：JDK 17.0.20、Maven Wrapper 3.9.16、Spring Boot 3.5.16、真实 PostgreSQL 17.11。数据库按测试使用隔离随机 schema。

## 命令与结果

```sh
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP02-verify-1.log
python3 scripts/verify_test_reports.py target/surefire-reports
```

构建与门禁返回码均为 0。11:32:30 开始，11:32:50 +08:00 结束，Maven 耗时 19.685 秒。8 个套件累计 **101/101 通过，失败 0、错误 0、跳过 0**。测试 agent 独立核对了全部实际 testcase 节点。

| 套件 | 执行 |
| --- | ---: |
| NotificationInputTest | 41 |
| NotificationIdempotencyTest | 21 |
| NotificationAccessAndQueryTest | 6 |
| NotificationAcceptanceFailureTest | 3 |
| SubmissionParserTest | 2 |
| CP01 原有三个套件 | 28 |

逐方法结果见 [results.json](results.json)，已测试源码校验清单见 [source-sha256.txt](source-sha256.txt)。报告不保存测试进程的全部环境属性及原始请求日志。

## 覆盖与缺陷修复

- 两组 50 并发请求在真实 PostgreSQL INSERT 屏障后竞争，验证同事件同数据复用及不同数据冲突。
- 无损数字、对象键顺序、数组顺序、空值/缺失、严格 JSON、大小/深度/标识符边界。
- 真实 AFTER INSERT 事务回滚、JDBC TCP 断连恢复、数据库提交后响应丢失；均未返回虚假受理或创建重复事件。
- 租户/操作授权与同时间记录稳定分页。失败列表使用直接 SQL 创建状态夹具，仅验证查询，不冒充 Worker 执行结果。
- 审查发现孤立 Unicode surrogate 在 UTF-8 编码时可能被替换为问号，造成摘要碰撞。解析器及摘要防御均拒绝非法 surrogate；合法 emoji 回归通过。

## 范围限制

本阶段仅受理并持久化 pending 任务，使用已发布数据库配置的常量 JSON 子集。管理接口/完整映射留 CP03，实际异步投递及终态历史留 CP04–CP05。双 API/Worker 进程、强杀窗口和完整故障链路留 CP09。以上范围未执行部分不计入本阶段通过数。GitHub Actions 尚未远程执行。
