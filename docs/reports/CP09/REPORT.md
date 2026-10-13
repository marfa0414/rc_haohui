# CP09 验证记录

状态：通过。日期：2026-10-13（Asia/Shanghai）。本阶段使用独立 JVM 验证持久化受理、任务竞争、真实进程故障及恢复；没有修改生产实现或引入新的生产依赖。

## 环境与复现

环境沿用 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16、PostgreSQL 17.11。每个场景使用独立 PG schema、真实 API、Worker 和外部 HTTP Mock。API 与 Worker 使用测试类路径启动独立 JVM；生产 JAR 不包含进程屏障、控制协议或时钟注入工具。

故障定向入口见 [examples/faults](../../../examples/faults/README.md) 与 `scripts/verify-faults.sh`。完整门禁为带真实数据库和固定假 Secret 环境变量的 `./mvnw --batch-mode --no-transfer-progress clean verify`；具体环境设置沿用 [CP08 执行命令](../CP08/REPORT.md)。完整回归日志保存在 `.cache/logs/CP09-verify-*.log`。原始 JVM 日志、控制文件及包含运行环境的完整 XML 不提交到仓库。

## 最终累计门禁

第三轮完整构建约 14:41:47 开始，14:47:57 +08:00 结束，耗时约 370 秒，退出码 0。**49 个套件、520/520 个实际 testcase 全部通过，失败 0、错误 0、跳过 0**。185 个源码/构建/样例文件与执行前冻结哈希一致。前置提交为 `6ce2bd2`，本阶段源码和报告一同提交。

逐方法结果及原 XML SHA-256 见 [results.json](results.json)，源码清单见 [source-sha256.txt](source-sha256.txt)。本轮保存 309 份脱敏场景 JSON。原始 Maven 输出留本机 `.cache/logs/CP09-verify-3.log`；报告检查及场景归档均在成功退出后执行。

最终归档命令：

```sh
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP09 --scope 'Cumulative CP01-CP09' --require-scenario-evidence
```

[构建后实际检查](runtime-cleanup.json)确认没有遗留 ProcessApplication Java 子进程或私有 IPC 目录，生产 JAR 不含测试控制、时钟注入类或 Byte Buddy agent。代码/脚本/样例的 180 个文件做了常见私钥与访问 Token 模式扫描，零命中；扫描边界见 [repository-scan.json](repository-scan.json)。

## 新增执行范围

| 套件 | 实例数 | 核心观察 |
| --- | ---: | --- |
| ProcessWorkerRecoveryTest | 12 | T1/T2/T3/HTTP 后真实强杀、旧 T2/T4 迟到、重投隔离、互换终止 Worker、SIGTERM |
| ProcessConcurrencyTest | 2 | 双 API 的 50 并发同键、双原生 Worker 调度、配置切换及 API 重启 |
| ProcessAuthRecoveryTest | 4 | 同引用 Secret 轮换、401 恢复意图强杀后保留、本地熔断重启边界 |
| ProcessDatabaseRecoveryTest | 5 | HTTP 成功后的数据库写回故障、api/worker/all 健康、实际数据库会话时区 |
| ProcessClockSkewTest | 2 | API/Worker 的 java.time 墙钟 ±1 小时，数据库租约及截止时间不受影响 |
| InboundExactSizeTest | 2 | 完整 UTF-8 入站 JSON 恰好 256 KiB 受理，多一个字节拒绝且不落任务 |
| TenantOAuthIsolationTest | 1 | 两租户同一 Token URL 并发获取、独立认证上下文与缓存命中 |
| RepositoryOwnershipRaceTest | 4 | 三种旧租约延期拒绝、双回收/领取与真实 PostgreSQL 锁竞争 |
| IndependentBusinessMethodTest | 2 | 业务 PUT/PATCH 的配置快照、真实 HTTP 方法与字节 |
| 前置回归 | 486 | CP01–CP08 累计功能、安全及资源边界 |

25 项定向测试均首轮通过：认证 4 项于 14:15:21 完成，数据库/时钟/并发 9 项于 14:22:34 完成，Worker 12 项耗时约 112.5 秒。定向结果用于排查；本阶段完成以随后一次完整 clean verify 的实际节点及冻结源码为准。

## 故障与并发结论

- 领取后强杀不耗 attempt，预留后强杀保留 attempt；发送标记及 HTTP 后故障保守记 UNKNOWN。HTTP 成功到数据库确认之间强杀会产生两次 HTTP。Mock 分别记录 HTTP 次数与副作用，外部按稳定事件标识去重时副作用一次，不去重时两次。
- 旧 Worker 在租约过期、另一实例接管或人工重投新轮次后恢复，T2/T4 条件更新均拒绝旧所有者。比对完整任务与 attempt 历史，确认迟到的 200/503 不能覆盖后续状态；HTTP 已发生的事实不会因拒绝写回而被抹去。
- 双 API 同时提交 50 个同键请求，只有一次 202，其余 49 次为同 ID 的 200。两个原生调度 Worker 处理 12 个唯一事件，全部完成且每个一次 attempt/HTTP。慢 HTTP 期间可取得相关任务行锁，证明网络等待没有占用任务事务。
- 并发发布切换、接入和 API 重启的 15 个任务全部完成，16 次 HTTP 包含旧版本重试。URL、普通 Header、Body 和认证版本成套固定，不出现新旧配置混合；原事件重试仍返回原任务。
- 两个 Worker 交替强杀并重启时，14 个受理任务、22 条 attempt 与 20 次 HTTP 全部对账；其中两个 T3 后强杀窗口未到实际 HTTP。SIGTERM 另验证停止领取、有界取消和另一实例恢复。
- 同环境 Secret 引用的内容/版本轮换后，旧任务保留旧 clientId、scope、Token URL，发送时解析新的 Secret；新任务使用新认证配置。401 恢复意图在数据库提交后、缓存失效前强杀仍保留，每轮只有一次认证恢复，持续 401 不产生第三次请求。
- 数据库代理真实切断当前连接并拒绝新连接，不停止共享测试 PG。HTTP 200 后写回失败保留可恢复状态；断库期间 ready 503、live 200、提交 503，没有虚假 202。恢复数据库并启动新 Worker 后允许重复 HTTP，外部去重副作用一次。供应商 503 不使 API ready 失败。
- api/worker/all 三种角色分别验证断库、缺表和迁移校验不匹配。Worker 角色不开放业务提交。JVM 时区从 Tokyo 切换至 Honolulu，数据库会话实际为 America/New_York，终态任务及历史完整保留、公开时间保持 UTC。
- 两项 ±1 小时测试使用已有测试依赖在独立子进程注入 `java.time.Clock.currentInstant()`，同时测量 `Instant.now()` 和 `Clock.systemUTC()` 的真实偏移。这是 Java 墙钟注入，不声称修改 OS 时钟；`System.nanoTime()`、操作系统和 PG 未修改。数据库仍控制领取、租约过期和截止，过期旧 Worker 写回失败，未开始任务到期以零 attempt 结束。`http_send_started_at` 属于节点时钟诊断，跨节点测量必须先确认时钟偏差，不能直接与数据库时间相减。
- 进程内 Token、熔断与并发保护在重启时重建；数据库任务和已持久化的延期时间保留。一项熔断重启场景有 12 个已受理任务、11 次 HTTP 与一个有明确未来调度时间的 pending，这个有意保留的任务不冒充终态成功。

新增所有权测试还在真实 PostgreSQL 回收 UPDATE 上设置仅测试 schema 的事务屏障，观察 `pg_blocking_pids` 证明确实到达，再让其他领取者竞争。只回收一次、生成一个替代租约，旧历史结束时间不因重复回收改变，下一预留只增加一次。此类仓储测试有意在 processing/预留状态结束，不声称已经完成投递；端到端受理集合由独立进程场景对账。跨租户 OAuth 使用同一 Token URL、不同认证上下文和独立屏障，检查实际出站 Bearer 后验证两边分别命中缓存，不仅测试手工拼装的缓存键。

## 测试设施与证据约束

每个子进程记录真实 PID、启动/就绪/强杀或 SIGTERM/退出事件及 UTC 时间。控制协议使用权限受限的本地目录与原子文件，启动参数仅传目录，配置加载后删除；阶段屏障有超时。父进程只终止自己创建的子进程，控制入口只存在于测试类路径。

任务/attempt 保存数据库观察，HTTP journal 只保存必要元数据和 Body SHA-256。只有使用实际副作用 ledger 的场景才输出副作用计数，没有 ledger 的场景省略该字段。源码审查纠正了若干没有 ledger 却写死副作用数量的测试证据字段；业务断言及 HTTP 次数未降低。

第一轮完整回归于 14:32:52 完成，耗时 6 分 6 秒，45 个套件 511/511 通过，181 个文件哈希与执行前冻结一致。审查发现共享故障夹具的 Token 超时仍为默认 5 秒，大于缩短后的 4 秒 attempt；虽然运行时会按剩余预算截断，仍不符合测试设计的严格配置关系。将测试 Token 超时明确设为 1 秒，全部故障 attempt 最短 2 秒、租约严格更长。另补齐认证预热任务、熔断前 10 个失败任务及 Auth/DB 场景的全部受理 UUID 关联，方便从证据直接对账。这些是夹具/证据修正，没有修改生产行为或降低断言。修正后重新冻结并运行第二轮完整回归。随后逐项核对 95 个逻辑用例时发现五类既定覆盖缺口：完整入站 UTF-8 JSON 的 262144/262145 字节边界、真实 OAuth 认证组装链路上的跨租户并发隔离、旧租约延期写回、回收器与领取器的直接并发，以及业务 PUT/PATCH 的真实投递。原有测试只覆盖渲染后 Body 边界、超大输入和手工 TokenKey 维度，不能代替这些明确要求的执行变体。补齐独立测试后再执行最终完整回归；不把此前全绿误写为所有参数变体已覆盖。补测修正后 9/9 通过，随后第三轮完整门禁 520/520 通过。第二轮中间回归于 14:40:10 完成，耗时 6 分钟，原有 511 项全部通过；四个新增测试文件是在该轮编译完成后加入，不计入这次执行。新增 9 项首次定向执行有 7 项通过，2 项在夹具断言处失败：事件字符串实际 19 个字符，测试误写成 18。修正该笔误，不改变完整请求 262144/262145 字节、202/413 或落库断言。

Compose、持续负载和资源峰值留 CP10；GitHub Actions 尚未远程运行。

## 独立签核

测试 agent 独立核验 49 份 XML 与 520 个实际 testcase，原 CP08 的 486 个案例身份全部保留；185 个源文件与最终冻结清单、归档和当前文件一致。309 份场景与 target 逐字节及 SHA-256 一致，字段白名单和原文/JSON/Form/Base64 假敏感值扫描通过。25 份带受理集合的汇总包含 80 个唯一任务，均能关联任务及 attempt 证据；Auth/DB 集合完整，刻意保留的 pending、failed 和仓储 processing 均有明确解释。生产 JAR 与清理记录复核通过。签核只覆盖 CP09，持续负载及最终交付仍待 CP10/CP11。
