# CP04 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。本阶段完成真实异步投递、attempt 事务、租约及故障窗口验证，不代表全部 95 个验收用例完成。

## 环境与命令

沿用 [CP01](../CP01/REPORT.md) 的 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16 与真实 PostgreSQL 17.11。每个数据库测试使用独立随机 schema；供应商是本机真实 HTTP 服务。

```sh
CP04_TEST_SECRET=cp04-test-only-secret CP04_TEST_SECRET_VERSION=fixture-v1 \
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP04-verify-2.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP04 --scope 'Cumulative CP01-CP04'
```

上面的 Secret 与数据库密码均为隔离测试的公开假值。测试调用真实环境变量解析器，缺少变量会失败，不会跳过。CI 已配置同一假值，但尚未在 GitHub 执行。

最终构建约 12:19:22 开始，12:20:37 +08:00 完成，耗时约 75 秒，返回码 0。19 个套件累计 **204/204 通过，失败 0、错误 0、跳过 0**。门禁与归档工具均返回 0。

| 本阶段新增套件 | 执行 |
| --- | ---: |
| DeliveryRepositoryTest | 7 |
| WorkerBoundaryTest | 5 |
| WorkerCrashWindowTest | 5 |
| WorkerDeliveryTest | 7 |
| OutboundWireEncodingTest | 1 |
| CP01–CP03 回归 | 179 |

逐方法结果与原 XML 哈希见 [results.json](results.json)，实际验证源码见 [source-sha256.txt](source-sha256.txt)。前置提交为 `07a714b`；本阶段的源码、报告与 checkpoint 状态一并提交，Git 历史可定位对应版本。

## 实际验证

- 真实 API 先返回 202，启动的自动调度器随后向供应商发送 JSON 或表单快照。运行时追加 API Key，不把凭据写入任务快照。原始 TCP 捕获确认中文和 emoji Header 的 UTF-8 字节没有替换。
- T1 竞争领取仅一个 owner，零计次；T2 计数与 RESERVED 记录共同提交或回滚；T3 持久化发送意图后才调用 HTTP；T4 同事务更新任务和历史。
- 用真实 PG 触发器拒绝 attempt 插入或成功写回，证明无部分提交；供应商已成功但 T4 失败时，本 attempt 不暗中补发。
- T1 后、T2 后、T3 后、HTTP 成功后分别阻断执行；实际等待数据库租约过期，调用生产回收器，再由生产 Worker 继续。已预留记录保守转 UNKNOWN，领取未预留不增加次数。
- Mock 分别统计请求次数与业务副作用，复现成功到写回窗口中的重复，以及下游按事件标识去重后只发生一次副作用。
- 错误 token、错误轮次、终态、已过期但尚未回收的 lease 都不能写回。晚到旧 Worker 不覆盖新 owner 的成功结果。
- 独立 PG 连接锁定 attempt 行，`pg_blocking_pids` 确认 T4 已阻塞，再等待 lease 过期并释放锁。旧写回整体回滚，任务与历史均保持原值，恢复后新尝试才发送。
- 截止前开始的尝试可在有效执行预算与租约内完成；截止后不新增 attempt。供应商等待期间任务行可被另一事务加锁，证明网络不包在数据库事务内。
- 全局容量有界；零宽限关闭会取消真实在途 HTTP 并停止领取，遗留 lease 可恢复。快速任务完成不会扩增单轮领取预算，12 条任务按每轮 2 条执行完成。
- 数值 IP 也经过生产连接器地址检查，禁止目标记录 NOT_SENT 且实际 HTTP 接收数为零；完整 SSRF/TLS 矩阵仍留 CP08。

生产默认值为 lease 90 秒、attempt 30 秒、业务 HTTP 10 秒。多数故障测试使用 3/2/1 秒，个别边界使用明确记录在测试中的更短数据库 lease；这些测试参数不修改生产默认值。

## 审查与修正

首轮 203/203 通过。审查发现单次扫描可能持续复用快速任务归还的槽位，使回收与后续供应商扫描得不到机会；修为固定本轮初始槽位预算，通过合并补位事件继续吞吐。测试追加快响应与延迟领取组合，第二轮累计 204/204 通过。

同时修正了 PostgreSQL nullable 参数类型、锁等待跨过租约后的写回检查、确定在连接前拒绝的地址应记 NOT_SENT、HTTP 默认编码可能替换 Unicode Header 等边界。执行预算用数据库时间差扣单调耗时，避免依赖 Worker 与数据库墙钟一致。独立代码审查未留下 CP04 blocker。

## 阶段限制

故障窗口使用同 JVM 的测试监听器和真实 PG/HTTP；两个独立 Worker 进程及强杀重启属于 CP09，不能据此宣称已完成。完整重试/人工重投、OAuth、熔断和响应头资源限制分别在后续 checkpoint 完成。当前 HTTP 非 2xx 的处理只是 CP04 基础，最终分类策略以 CP05 为准。
