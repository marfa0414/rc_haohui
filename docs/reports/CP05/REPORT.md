# CP05 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。本阶段完成分类重试、截止清理、尝试历史和幂等人工重投；OAuth 与保护机制仍按后续 checkpoint 实施。

## 执行环境与命令

沿用 [CP01](../CP01/REPORT.md) 的 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16 与 PostgreSQL 17.11。使用真实应用 HTTP、PG 隔离 schema 和本机供应商 Mock。

```sh
CP04_TEST_SECRET=cp04-test-only-secret CP04_TEST_SECRET_VERSION=fixture-v1 \
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP05-verify-2.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP05 --scope 'Cumulative CP01-CP05' --require-scenario-evidence
```

最终构建约 12:35:30 开始，12:37:11 +08:00 完成，耗时约 101 秒，返回码 0。23 个套件累计 **278/278 通过，失败 0、错误 0、跳过 0**。报告门禁与归档工具均返回 0。

| 本阶段新增套件 | 执行 |
| --- | ---: |
| RetryPolicyTest | 37 |
| WorkerRetryTest | 28 |
| ReplayTest | 8 |
| AttemptHistoryTest | 1 |
| CP01–CP04 回归 | 204 |

逐方法结果、原 XML 哈希及场景文件索引见 [results.json](results.json)，验证源码见 [source-sha256.txt](source-sha256.txt)。前置提交为 `d641b37`；本阶段源码和报告一并提交。

## 实际覆盖

- 表驱动覆盖 2xx、3xx、普通 4xx、408、429、5xx，以及网络不确定、超时和禁止目标。真实 Worker 对 500→500→200 执行独立 attempt，没有客户端透明重试。
- 默认 full jitter 基数 30 秒、上限 1 小时，纯计算验证 0/中间/上界样本及极大 attempt 饱和。真实时间测试使用 5–10 ms 退避或明确的 Retry-After，不修改生产默认值。
- Retry-After 秒数、三种 HTTP 日期、无效/过去值、重复字段、超大合法整数不会导致提前重试；真实接收时刻与计划时间比对。超出本轮截止的有效等待值直接 failed；下一时间等于截止也终止。
- 分别验证 3 次和第 12 次封顶。超时/断连记录 UNKNOWN，重试保持事件标识与请求快照，Mock 记录请求与业务副作用。
- nextAttemptAt 位于未来 5 天的 pending 任务，仍由独立截止扫描终止。最后 outcome/status/error 保留；无 attempt 的回收也不伪造新投递结果。有效租约中的在途尝试不被截止扫描抢占。
- 部署默认预算保存到任务；重启修改配置不改变旧轮次，人工重投和新任务使用新部署预算。重投保持快照、事件标识、累计次数和全部历史。
- 两组各 50 个真实 HTTP 请求，在 PG 任务行锁前形成可观测竞争。同键全部返回相同固定结果；不同键仅一个成功、其余 49 个 409；均只新增一个轮次和一条审计。
- 重投响应丢失后按独立键重试，复用原操作；任务随后成功也不改变保存的响应。同键更改原因或预期轮次冲突；非 failed、越权、旧轮次和非法输入被拒绝。
- 重投审计失败使任务、幂等记录、审计一起回滚；旧 Worker 无法覆盖已开启的新轮次。[实际重投 JSON 样例](../../../examples/recovery/replay.json) 由测试加载执行。
- 真实生成 105 次尝试并跨新轮次分页，证明倒序 keyset 无重复/遗漏、默认与最大页长有效、权限正确、响应不含敏感正文和凭据。
- 提交后的 SUCCESS/RETRY/FAIL 指标反映实际状态，次数耗尽计 FAIL；T4 回滚不增加成功计数。异常 pending 已耗尽预算时，提交终止后记录独立不变量告警。

## 可核对的故障证据

归档新增 **31 份场景 JSON**，路径与 SHA-256 在 results.json 的 `scenarioEvidence` 中。记录真实任务状态、轮次/计次、lease、attempt 阶段和时刻、Mock 接收时刻与 Body 摘要，以及适用场景的副作用计数。使用字段白名单，不归档完整 Body、Header、URL、Secret 或 Token；敏感字段检查零命中。

本次累计回归也补存了 CP04 领取/预留/发送意图/供应商成功窗口在恢复前后的实际状态。此前 CP04 报告中的这些状态只有测试断言证据；本阶段的场景文件是新增执行得到的记录，不冒充此前运行的原始采样。独立进程强杀仍留 CP09。

## 发现与修正

首次编译修复了 ReplayService 对无状态摘要实例的错误静态调用。首轮诊断执行 274 项，3 个失败、1 个错误：历史测试把既有资源隐藏契约的 404 写成 403；截止夹具回拨时间早于 runStartedAt，触发合法数据库约束；两项并发夹具在持锁事务中读 PG 缓存的统计快照，未观察到等待者。

测试修正为既有 404 契约、合法未来短 deadline 后等待数据库时钟、独立 autocommit 连接观察行锁等待。没有降低 50 并发门禁或修改数据库约束。补齐新轮次旧写回、部署预算、输入和边界场景后，冻结源第二轮 278/278 通过。代码审查补充了 HTTP 日期世纪/闰日边界、超大 Retry-After 和提交后指标；没有通过隐藏 skip 取得通过。

## 限制

OAuth 401 恢复在 CP06；供应商熔断、共享配额域冷却和接入保护在 CP07；完整安全矩阵、独立进程故障与负载实测在 CP08–CP10。当前结果不能等同全部 95 个逻辑验收用例完成。GitHub Actions 尚未远程运行。
