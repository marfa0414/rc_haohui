# CP08 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。本阶段补齐出站目标与 TLS 验证、传输资源边界、租户凭据授权、权限及脱敏验收。

## 最终门禁与证据

环境沿用 JDK 17.0.20、Maven 3.9.16、Spring Boot 3.5.16、PostgreSQL 17.11、HttpClient 5.5.2 / HttpCore 5.3.6；真实应用、PG 隔离 schema、HTTP/TLS Mock。没有引入新的中间件或数据库迁移。

```sh
CP04_TEST_SECRET=cp04-test-only-secret CP04_TEST_SECRET_VERSION=fixture-v1 \
CP06_CLIENT_SECRET='cp06 fake:+密钥' CP06_CLIENT_SECRET_VERSION=fixture-oauth-v1 \
CP06_ROTATED_SECRET='cp06 rotated secret' CP06_ROTATED_SECRET_VERSION=fixture-oauth-v2 \
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP08-verify-2.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP08 --scope 'Cumulative CP01-CP08' --require-scenario-evidence
```

以上凭据均为测试假值。最终构建约 14:00:19 开始，14:03:04 +08:00 完成，耗时约 165 秒，返回码 0。**40 个套件累计 486/486 通过，失败 0、错误 0、跳过 0**，所有实际 testcase 节点均已核对。169 个源码/构建/样例文件与执行前冻结哈希完全一致。

逐方法结果与原 XML 哈希见 [results.json](results.json)，源码标识见 [source-sha256.txt](source-sha256.txt)。前置提交 `fb7a426`；本阶段源码与报告共同提交。保存 166 份脱敏场景 JSON，包含前置场景重新执行及本阶段安全、TLS、资源和审计观察。

| 新增执行范围 | 实例数 |
| --- | ---: |
| OutboundTlsTest | 8 |
| OutboundResourceLimitsTest | 26 |
| OutboundSecurityTest | 14 |
| SecurityAuthorizationTest | 7 |
| SensitiveDataExposureTest | 2 |
| ApiKeyAuthStrategyTest 新增默认拒绝授权 | 1 |
| CP01–CP07 回归 | 428 |

## 实现与实际边界

- 业务与 Token 共用出站策略：允许域名/端口精确匹配，URL 使用 ASCII/punycode；拒绝 userinfo、fragment、尾点、异常数字主机、私网/链路本地/多播及特殊地址范围。IP literal 在连接器入口检查，域名的全部 DNS 候选在建立连接前检查；直接使用已验证的地址，不通过第二次解析取连接地址。
- 开发/test 必须同时满足明确的目标 authority 和 CIDR；默认没有隐式回环授权，生产启动拒绝配置开发例外。云平台固定地址补充拒绝 Azure WireServer；应用无法仅凭公开 IP 注册表识别部署网络的全部特殊路由，仍需部署网络出口约束。
- TLS 使用正常 CA 验证及固定主机名校验。测试生成临时私有 CA 与有效、过期、错误主机、不可信证书，两个接口路径都真实握手；非法证书没有业务 HTTP，并以同一容量为 1 的客户端随后完成合法请求验证释放。未使用 trust-all 或关闭 hostname verification。
- 响应头单行至多 16 KiB、最多 100 条；解析后另限制字段总计 64 KiB。解析阶段仍可能分配多于 64 KiB，不能将接受预算表述为峰值内存。Token 头/Body 超限是固定认证协议错误，不保存或回显原文。
- 业务取得有效最终响应头即确认状态，不主动读取或排空 Body；网络缓冲已经收到部分字节是允许的。Token 完整解析至多 64 KiB，禁自动解压。显式禁用连接复用，所有路径关闭物理连接；代价是新增 TCP/TLS 开销，性能影响留 CP10 实测。
- 慢 TLS/响应头受总时间限制；不可中断 DNS 在实际返回前继续占有有界槽位，超时后不会晚到建连。重复并发取消后所有槽位可复用；不创建无限线程补偿阻塞。禁止系统代理继承、重定向、透明认证挑战、Cookie 复用和隐式重试。
- 部署方用 `app.secrets.tenant-references` 明确授予租户 Secret 引用，默认拒绝，无通配符。数据库配置管理员不能引用部署方未授权给本租户的 Secret；同一引用可明确授予多个租户；管理员角色也不能越过此部署授权。发布、绑定和每次发送重新检查，撤销后旧快照记 NOT_SENT/AUTH_CONFIGURATION_ERROR，Token/业务调用均为零。同引用内容轮换仍支持，新引用须先由部署方授权。
- 接入日志将真实 requestId 关联 notificationId，Worker 使用 executionId（本次 lease token）关联轮次/attempt；不把执行 ID 冒充原 HTTP requestId。仅记录固定错误码和必要元数据，业务 Body、普通 Header、Token、Secret、内部凭据及完整供应商响应不进入诊断接口、日志或审计。必要业务请求快照仍存在受权限保护的任务数据库中。

## 覆盖映射

SEC01/02 使用地址与 URL 矩阵、真实 DNS/连接探针和 0 次禁止连接断言；SEC03 覆盖 301/302/307/308 的两个路径，禁止凭据随跳转外发；SEC04 为 8 个真实 TLS 变体。SEC05 捕获实际应用日志并扫描原始与编码后的假敏感值，同时核对 API、预览、task、attempt 与审计。SEC06 覆盖超大/无限/慢 Body、总/行/数量响应头边界、慢 DNS/TLS、并发取消与容量恢复；持续内存/吞吐压力仍在 CP10。

SEC07 遍历管理操作的角色/租户权限，覆盖业务与 attempt/replay 查询、operation 范围、列表隔离和 Secret 授权撤销。SEC08 验证生产拒绝调试入口、请求字段不能开启故障开关、配置中无任意脚本；主线程另外扫描了 166 个源代码/构建/样例文件的私钥、GitHub/Slack Token 和 AWS Access Key 常见模式，零命中，并确认环境样例只含占位值；这不能证明任意形式的秘密绝不存在，CP11 还会检查最终交付内容。OBS01/04 与 REPLAY04 结合既有状态机回归，增加真实 ID 关联和发布 draft→published、绑定 revision 1→2→3、重投 run 1→2 的精确审计断言。

## 发现与修正

首次传输定向测试 34 项中 31 通过：HttpCore 在 `size >= maxHeaderCount` 时拒绝，设置 100 实际只能接受 99；改为库阈值 101 并保留最终字段数 >100 拒绝。另一个失败揭示 Token 读完 EOF 后连接已提前归池，finally cancel 无法再关闭；明确禁用连接复用修复。第二次同组 34/34 通过。响应头解析行/数量异常也统一为 TOKEN_PROTOCOL_ERROR，不错误地当成可重试网络故障。

第一次完整回归实际执行 486 项，483 通过、3 失败、错误及跳过均为零。两项日志测试先挂捕获器再启动 Spring，被日志系统重置而未捕获事件；实际 console 关联日志正确。测试改为启动后、全部敏感操作之前挂载，并断言捕获器仍附着且非空，避免空采集导致假脱敏成功。另一项旧数字地址夹具默认认为测试环境仅允许 HTTP 回环；CP08 的显式 CIDR 同样授权指定 HTTPS 目标。测试改为受理后撤销 CIDR，验证旧快照重新检查，保留 NOT_SENT、HTTP_TARGET_REJECTED、一次 attempt 和业务零调用。新的连接器 literal 测试另以真实 0 TCP 证明不可绕过。

审查同时发现旧审计测试只核数量，补齐前后状态/版本/引用/原因的精确断言。修正后的三个套件定向 16/16 通过。没有删除用例、放宽最终业务结果、允许信任全部证书或用跳过代替通过。

测试 agent 独立签核：40 份 XML 哈希及 486 个唯一 testcase 与归档一致；169 个文件与冻结清单一致；166 份场景逐字节匹配，包含 81 份投递、33 份认证、1 份隔离和 51 份安全/传输记录，字段白名单及原始/编码假敏感值扫描通过。正常/Token 失败场景实际捕获 12/4 条日志，扫描 11/8 份公开响应；敏感 URL 场景实际捕获 3 条日志、20 次发布拒绝、0 次连接。

## 后续验收

独立 JVM 双 Worker、真实强杀、数据库中断与同引用环境 Secret 轮换留 CP09；持续负载、资源峰值与 Compose 部署留 CP10。GitHub Actions 尚未远程运行。本阶段结果不等于全部 95 个逻辑用例最终验收完成。
