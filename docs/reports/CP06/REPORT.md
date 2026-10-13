# CP06 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。完成 OAuth 客户端凭证、进程内 Token 缓存、并发获取合并、认证冷却和持久化 401 恢复。本结果不替代后续独立进程故障与集群验证。

## 执行环境与命令

沿用 [CP01](../CP01/REPORT.md) 的 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16 与 PostgreSQL 17.11。协议测试使用真实受控 HTTP；Worker 测试使用真实应用、PG 隔离 schema、独立 Token 和业务 Mock。以下凭据全部为测试假值。

```sh
CP04_TEST_SECRET=cp04-test-only-secret CP04_TEST_SECRET_VERSION=fixture-v1 \
CP06_CLIENT_SECRET='cp06 fake:+密钥' CP06_CLIENT_SECRET_VERSION=fixture-oauth-v1 \
CP06_ROTATED_SECRET='cp06 rotated secret' CP06_ROTATED_SECRET_VERSION=fixture-oauth-v2 \
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP06-verify-2.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP06 --scope 'Cumulative CP01-CP06' --require-scenario-evidence
```

最终构建约 13:08:02 开始，13:10:03 +08:00 完成，耗时约 121 秒，返回码 0。29 个套件累计 **376/376 通过，失败 0、错误 0、跳过 0**。冻结时保存的 144 个文件哈希在构建结束后全部一致；报告门禁与归档工具返回 0。

| 本阶段新增套件 | 执行 |
| --- | ---: |
| OAuthTokenProtocolTest | 33 |
| TokenCacheTest | 10 |
| TokenCacheLifecycleTest | 5 |
| BearerChallengeTest | 21 |
| WorkerOAuthTest | 18 |
| WorkerOAuthBoundaryTest | 11 |
| CP01–CP05 回归 | 278 |

逐方法结果、原 XML 哈希、场景索引见 [results.json](results.json)，源码哈希见 [source-sha256.txt](source-sha256.txt)。前置提交为 `0c2429e`；本阶段源码和报告一并提交。所有 376 个实际 testcase 节点均已核对，不能仅依据套件汇总数字判断通过。

## 实际覆盖

- Basic 和 Post 两种客户端认证通过真实 HTTP 验证。Basic 先分别对 Client ID、Secret 做表单编码，再构造 Base64；包含中文、空格、冒号和加号。Scope 去重排序，认证材料只出现在指定位置，不使用 refresh_token。两份仓库 OAuth 样例经真实管理 API 创建、发布、绑定和投递。
- 严格 Token 响应解析覆盖 Bearer、非法/缺失有效期、显式寿命回退、非 JWT、不合法 Token、3xx 不转发凭据、确定性认证错误，以及 408/429/5xx。固定长度和 chunked 响应都受 64 KiB 限制，5 秒总超时覆盖读取与解码；取消后容量可复用。
- 两组 50 并发分别验证成功与失败，放行 Token Mock 前等待实际 WAIT 指标达到 49，证明一个 leader 和 49 个等待者共享一次获取。失败不会触发等待者串行击穿；冷却结束才重取。单个等待者超时或中断不取消共享获取。
- 缓存键按环境、租户、认证版本、凭据版本、Token URL、Client ID、认证方法及 Scope 集合隔离。单调时钟验证寿命边界，墙钟跳变不误判；有效期必须覆盖业务预算和安全余量。新 Token 过短明确终止，不无限刷新。
- 共享结果按各等待者的请求预算及拒绝指纹独立判断。旧 generation 的 401 不删除新 Token；取得新 generation 但值仍相同，恢复任务也不能再次发送。
- 缓存满载不驱逐 in-flight 条目，过期条目可清理。关闭缓存释放全部等待者并停止 leader；原 Mock 仍阻塞时，唯一传输许可可被另一个真实请求复用。429 的秒数、HTTP 日期和超大合法整数不能缩短为默认短冷却或溢出。
- 只有 OAuth 业务 401 的合法 Bearer invalid_token 挑战可恢复。普通 401、403、API Key、误导性 realm、畸形/重复参数均不盲目刷新；解析覆盖多挑战、转义、跨物理字段、合法空列表元素及 token68 的空白边界。
- 401 恢复意图与当前 attempt 结束同事务提交，下一次 attempt 消耗原轮次预算；一次轮次最多一次恢复。次数已尽或恢复后再次拒绝均停止。中间 Token 临时失败保留恢复意图；T3 使用合格新 Token 后只清 required，保留 used 与拒绝指纹。
- 同进程重建 Spring 应用、T4 提交后失效缓存前中断、两个任务的旧 401 延迟返回均有真实 PG/HTTP 证据。原任务认证版本不因新账户发布而改变，新的环境凭据引用、Client ID、Scope、Token URL 只用于绑定后的新任务。
- Token 临时失败记 NOT_SENT，业务调用为零；T4 直接持久化普通退避、Retry-After 与认证冷却的最大下限。同上下文新任务在 T2 前延期，attempt 不增长，最后结果不变。
- Token 获取后在 T3 屏障等待导致寿命不足，发送前再次检查并记 NOT_SENT；下一 attempt 才获取并发送。等待 attempt 已耗尽后共享 leader 完成，只能填充缓存，不能晚发业务请求；过期未写回的尝试仍按既有租约规则保守恢复为 UNKNOWN。

## 证据与修正记录

归档 **77 份场景 JSON**，其中 46 份为 CP06，31 份为本轮累计执行得到的前置场景。任务/attempt/业务 Mock 使用原字段白名单；认证附加记录只有任务 ID、轮次、恢复布尔状态及 Token 调用时间、方法、路径，不保存 Token 请求正文、正文摘要、实际凭据或拒绝指纹。每个文件的 SHA-256 可在 results.json 核对。

协议和缓存先分别诊断 33/33、10/10 通过；首次累计诊断 358/358 通过。随后新增边界定向执行 32 项，唯一失败来自测试误把新任务的 last_outcome 预期为 null；既有 V2 约束为 NOT NULL、默认 NOT_SENT。测试改为采样延期前的实际状态并逐字段比较保留结果，没有修改生产契约或删除断言。补齐生命周期验证后，最终累计 376/376 通过。

交叉审查修复了三类生产问题：leader 的任务级约束污染其他等待者；取得 Token 后锁等待消耗寿命却缺少发送前复核；Bearer 挑战解析的列表/物理字段/空白边界。另将认证短冷却直接纳入 T4 的 nextAttemptAt，避免一次无谓领取。详细规则同步到 [技术方案 §8](../../TECHNICAL_DESIGN.md#8-动态认证与-token-生命周期)。

## 后续验收边界

TC-AUTH-09 的业务熔断样本排除在 CP07；TC-AUTH-12 的同一逻辑 Secret 引用在独立进程重启后读取轮换环境值，以及 TC-AUTH-13 的真实进程强杀在 CP09。当前同进程屏障/应用重建不能代替这些验证。完整出站安全矩阵在 CP08，部署与性能在 CP10，最终 95 个逻辑用例矩阵在 CP11。GitHub Actions 尚未远程运行。
