# CP07 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。完成供应商并发隔离、熔断、配额冷却与入口容量保护。

## 最终门禁与证据

沿用 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16、PostgreSQL 17.11；真实应用、PG 隔离 schema、HTTP Mock。新增 Resilience4j 2.4.0 只使用 core/circuitbreaker，不启用 AOP 或额外自动重试；两份依赖基础 class 版本均为 Java 17 的 61，见 [依赖兼容性记录](dependency-compatibility.json)。

```sh
CP04_TEST_SECRET=cp04-test-only-secret CP04_TEST_SECRET_VERSION=fixture-v1 \
CP06_CLIENT_SECRET='cp06 fake:+密钥' CP06_CLIENT_SECRET_VERSION=fixture-oauth-v1 \
CP06_ROTATED_SECRET='cp06 rotated secret' CP06_ROTATED_SECRET_VERSION=fixture-oauth-v2 \
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP07-verify-5.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP07 --scope 'Cumulative CP01-CP07' --require-scenario-evidence
```

以上凭据均为测试假值。最终构建约 13:36:04 开始，13:38:39 +08:00 完成，耗时约 155 秒，返回码 0。**35 个套件累计 428/428 通过，失败 0、错误 0、跳过 0**；428 个实际 testcase 节点均核对。157 个源码/构建/样例文件与执行前冻结哈希完全一致。

| 本阶段新增套件 | 执行 |
| --- | ---: |
| ProtectionGateTest | 30 |
| ProtectionCapacityTest | 4 |
| AdmissionGateTest | 2 |
| IngressProtectionTest | 2 |
| WorkerProtectionTest | 13 |
| WorkerIsolationTest | 1 |
| CP01–CP06 回归 | 376 |

逐方法结果与原 XML 哈希见 [results.json](results.json)，源码标识见 [source-sha256.txt](source-sha256.txt)。前置提交 `e782c98`；本阶段源码与报告共同提交。保存 105 份场景 JSON：77 份前置场景重新执行，28 份本阶段状态/认证附加/隔离时间线；不含 Secret、Token、原始 Header/Body 或拒绝指纹。

实际覆盖 GUARD01–08 本阶段范围、API08 接入保护、AUTH09 业务熔断排除，以及 RETRY03/06/07 的冷却组合。真实 9/10 样本验证默认阈值；3 个真实任务竞争同一半开探测，200/503 分别恢复/重开，其他两任务 attempt 保持 0。Token 503/429/超时和取消都释放探测资格；旧代次完成及单独 close 均不污染新代次。默认冷却、0/1 秒/过去日期/多值/超大值、跨上下文及共享组、时间跳变和状态容量边界均有断言。

固定隔离负载一次提交慢供应商 30 条、健康供应商 10 条，停止输入后观察 **20103ms**。慢/健康并发峰值各 **3**，全局峰值 **6**（上限 10），内存队列峰值 **0**，数据库积压峰值 **40**；健康任务首次 HTTP 在 **268–632ms** 内开始，**840ms** 内全部成功。40 条已受理任务逐条对账；慢组在观察结束时可保留符合预算的 pending/processing，不伪称已经全成功。详情见 [隔离时间线](scenarios/cp07-supplier-isolation.json)。

## 实现与边界

- Worker 默认全局 10 个执行槽位、每供应商 3 个；供应商许可先于全局许可，非阻塞领取并按供应商轮转。Token 准备占所属供应商槽位。执行队列有界，关闭、空领取、异常和取消归还许可。
- Resilience4j 2.4.0 默认最近 20 个样本、至少 10 个、失败率 50%、打开 30 秒、半开单探测。仅业务网络/超时/5xx 计失败，2xx 计成功；认证故障、其他状态和未发送路径不计样本。许可一次性终结，旧代次晚到结果与单独关闭不会影响新探测。
- 429 冷却按环境/租户/认证配置隔离，支持显式共享组。有效 Retry-After 作为下限，缺失/非法时默认 30 秒；多个值取最大，合法 0/过去日期不强加默认冷却。HTTP 日期使用数据库时间，已建立冷却与熔断等待使用单调时钟。
- T2 前保护拒绝不计 attempt。Token 等待或 T3 后才建立的新冷却会在发送前复核，记 NOT_SENT 并保留已预留次数。T4 合并普通退避、Retry-After、配额及认证冷却；已经进入传输的请求不能撤回。
- 本地提交速率默认 100/s、突发 200；新增任务活动积压阈值 10000。429/503 有固定错误码及 Retry-After。幂等命中仍经过基础速率保护，随后优先解析，不因积压已满而遗失原任务。V6 为 pending/processing 增加部分索引，执行计划留 CP10 实测。
- 熔断与配额状态各最多 1000 条，容量不足受控延期。活动许可不被驱逐；无活动、超过闲置期限的熔断域可回收，OPEN 还必须经过打开等待。长期闲置后再次出现流量会重建样本窗口。

以上速率、并发、缓存、熔断和冷却均为进程内机制；多进程上限会叠加，重启清空本地保护但不删除数据库任务。积压检查允许并发短时超过阈值，不承诺集群精确配额。

## 验证与修正记录

最初主代码编译通过。首次完整门禁止于测试编译：SimpleMeterRegistry 不实现 AutoCloseable；测试改为 finally 显式 close，未更改业务行为或断言。第二次完整构建 35 个套件 425/425 通过；审查补齐旧许可单独 close、独立墙钟跳变和显式数据库时间偏差三个确定性边界后，重新冻结运行最终门禁。

第三次完整门禁实际执行 428 项，其中唯一失败是新时钟测试的 HTTP 日期夹具：Java 的 RFC_1123 格式器输出单数字日期，按冻结的 HTTP-date 语法属于非法值，因而触发 30 秒回退。测试统一使用双位日、英文星期/月名和 GMT，仍保留 120 秒数据库时钟断言及此前真实到达下限；生产解析器未修改。修正后定向 ProtectionGateTest 30/30 通过，再执行第四次完整门禁。

第四次完整门禁在一个半开用例的应用初始化阶段收到非本应用契约的 401。定位发现 Tomcat 绑定 IPv6 通配地址的随机端口，而测试客户端请求 IPv4 回环；本机另一服务已监听同端口的 IPv4 回环，应用日志也未出现该请求的 DispatcherServlet 初始化。测试基础配置随后显式绑定 127.0.0.1，与请求地址一致，由操作系统在该接口选择空闲端口。未对 401 放宽断言、增加成功重试或修改生产鉴权。

前置 CP05 的两个局部夹具提高熔断最小样本量，以保留原目标：12 次重试耗尽仍须真实发送 12 次；105 次失败历史分页仍须真实发送 105 次。其他默认保护不变，CP07 另用默认 20/10/50 阈值严格验证。Retry-After 原测试保持其原配置与实际到达下限断言。

本轮对 GUARD-06 的文字歧义按既有 T2 预算契约明确：准入拒绝不计次，已经预留后遇到新冷却保留计次；同时增加真实 Token 等待与 T3 后两种时序测试，不倒扣次数或删除失败路径。

测试 agent 独立签核：35 份原 XML 哈希、428 个唯一 testcase、157 个文件哈希全部匹配；105 份归档与执行产物逐字节一致。场景由 75 份投递状态、29 份认证附加和 1 份隔离时间线组成，字段白名单及测试敏感值扫描通过。

## 后续验收边界

TC-GUARD-07 的独立进程重启、API-08 的独立数据库中断在 CP09；GUARD-08、OBS-02 的持续压力、指标对账和资源排空在 CP10。CP07 的固定隔离负载不替代 CP10 的十分钟性能基线。完整 SSRF/TLS 安全矩阵在 CP08。GitHub Actions 尚未远程运行。
