# CP11 最终验收追踪矩阵

最终为 **95/95 逻辑用例 PASS、28/28 AT PASS，无待验收项**。结论来自逐项参数变体、531个实际JUnit身份及独立部署/负载/故障证据核对，未以测试总数替代逻辑验收。

已独立核对CP11全新目录/数据库卷产生的531条实际JUnit身份及0失败/错误/跳过，CP10全部531身份原样保留；51个原始XML及330份场景逐SHA/逐字节与归档一致。完整类名、方法名、参数化实例编号、源码SHA、XML SHA及场景路径见 [acceptance-matrix.json](acceptance-matrix.json)。负载使用独立真实运行证据，不冒充JUnit实例。

表中“变体”列列明冻结目录要求的主要分支；同一方法内可包含多个断言。场景链接按测试类收集，具体方法/参数执行以JSON内actualExecutionInstances为准。

| 用例 | 状态 | 已审阅变体及边界 | 实际方法／证据 |
| --- | --- | --- | --- |
| TC-API-01 | PASS | 首次202/独立PG提交可见；API暂停无HTTP；真实异步scheduler与手动Worker均发送快照字节并成功。 | `NotificationAccessAndQueryTest#acceptsOnly`<br>`WorkerDeliveryTest#acceptedTask`<br>`WorkerDeliveryTest#allMode`<br>3 个已执行实例 |
| TC-API-02 | PASS | INSERT前断库、INSERT后事务回滚、已commit响应TCP丢失三分支；原键查回同ID；日志requestId关联。 | `NotificationAcceptanceFailureTest`<br>`ProcessDatabaseRecoveryTest#acknowledgedHttp`<br>`SensitiveDataExposureTest`<br>6 个已执行实例 |
| TC-API-03 | PASS | 缺4字段/未知字段/非对象/空及超长标识/非法字符/大小写；顶层及嵌套重复键/双根JSON；深度64与65、数字1000与1001、指数±10000与±10001；孤立代理对拒绝；UTF8有界读取。 | `NotificationInputTest`<br>`SubmissionParserTest`<br>`InboundExactSizeTest`<br>45 个已执行实例 |
| TC-API-04 | PASS | 缺凭证/错误凭证401；租户/操作伪造403；无新增任务；出站只供应商认证，不转内部Authorization。 | `NotificationAccessAndQueryTest#rejects`<br>`SecurityAuthorizationTest`<br>`WorkerDeliveryTest#acceptedTask`<br>9 个已执行实例 |
| TC-API-05 | PASS | pending/processing/succeeded/failed的run、累计及本轮次数、deadline/next/最后结果/配置引用；查询与历史无body及凭据。 | `NotificationAccessAndQueryTest#acceptsOnly`<br>`AttemptHistoryTest`<br>`WorkerRetryTest`<br>`ReplayTest#replayPreserves`<br>31 个已执行实例 |
| TC-API-06 | PASS | 同createdAt二元分页，默认20/上限100、非法limit/cursor；tenant/status/op交集过滤；越权与不存在404同形。 | `NotificationAccessAndQueryTest#paginates`<br>`NotificationAccessAndQueryTest#unknownAnd`<br>2 个已执行实例 |
| TC-API-07 | PASS | 缺路由/未发布版本/不可用配置/动态编码超限均无任务；422 ROUTE_UNAVAILABLE；草稿绑定拒绝。 | `NotificationAccessAndQueryTest#missingDraft`<br>`ConfigurationLifecycleTest#draftsMust`<br>`MappingIntegrationTest#outputBody`<br>`MappingIntegrationTest#ordinaryHeader`<br>4 个已执行实例 |
| TC-API-08 | PASS | 入站429/积压503/断库503与Retry-After；拒绝不落任务、既有幂等优先；真实恢复；压力5395拒绝均无持久任务。 | `IngressProtectionTest`<br>`NotificationAcceptanceFailureTest#disconnected`<br>`ProcessDatabaseRecoveryTest`<br>`OperationalMetricsTest#acceptedCounters`<br>`OperationalMetricsTest#backlogRejection`<br>`AdmissionGateTest`<br>12 个已执行实例<br>[pressure-audit.json](../CP10/pressure-audit.json) |
| TC-IDEM-01 | PASS | 单API真实PG屏障50竞争；双独立API各分担50请求；恰1次202其余200同ID；正常双Worker一次发送。 | `NotificationIdempotencyTest#fifty`<br>`ProcessConcurrencyTest#twoApi`<br>2 个已执行实例 |
| TC-IDEM-02 | PASS | 同键两种data在真实PG屏障并发；一方胜出、另一409；全部请求无500且赢家快照/摘要一致。 | `NotificationIdempotencyTest#competing`<br>1 个已执行实例 |
| TC-IDEM-03 | PASS | 17组：对象/嵌套键序、1/1.0/1e0、负0、精确大整数/小数、数组调序、null/缺失、大小写、emoji等价；另U+10000/BMP码点黄金向量。 | `NotificationIdempotencyTest#hashes`<br>`SubmissionParserTest#canonical`<br>18 个已执行实例 |
| TC-IDEM-04 | PASS | 完整tenant+operation+event唯一；跨tenant/op独立；鉴权和授权先于幂等查重。 | `NotificationIdempotencyTest#tenantAnd`<br>`NotificationIdempotencyTest#existingEvent`<br>`SecurityAuthorizationTest#operationScope`<br>3 个已执行实例 |
| TC-IDEM-05 | PASS | v2新增required字段；原event原data200同ID，新event缺字段400，新完整data用v2。 | `ConfigurationVersionTest#oldEvent`<br>1 个已执行实例 |
| TC-IDEM-06 | PASS | 两次真实演示(all及api+双Worker)都在succeeded/run1与failed/run2后重新POST原事件：200同ID/当前status+run，300ms观察完整任务/截止/计数/history不变；最终4正常HTTP无重复、404总attempt仍2。 | `WorkerRetryTest#exhaustedAttemptBudget`<br>`ReplayTest#replayRejects`<br>`ProcessDatabaseRecoveryTest#terminalTasks`<br>4 个已执行实例 <br>[all演示](demo-all.json)、[双Worker演示](demo-distributed.json) |
| TC-IDEM-07 | PASS | 改路由并重启独立API/Worker后原key仍同ID原快照；幂等数据无自动清理，不依赖进程cache。 | `ProcessConcurrencyTest#concurrentPublication`<br>`ProcessDatabaseRecoveryTest#terminalTasks`<br>2 个已执行实例 |
| TC-CFG-01 | PASS | create/validate/preview/publish/bind/submit全生命周期；草稿不可绑定；审计actor/revision前后值真实断言。 | `ConfigurationLifecycleTest#draftsMust`<br>`ConfigurationLifecycleTest#previewAnd`<br>`SensitiveDataExposureTest`<br>4 个已执行实例 |
| TC-CFG-02 | PASS | OAuth草稿预览与发布只离线校验；业务和Token均0请求；仅脱敏叶值/长度/类型，无Secret。 | `ConfigurationLifecycleTest#previewAnd`<br>`SensitiveDataExposureTest`<br>3 个已执行实例 |
| TC-CFG-03 | PASS | 发布后API与DB修改/删除拒绝（SQLState23514区分FK）；复制v2可改，v1字节不变；重复publish409。 | `ConfigurationLifecycleTest#draftsMust`<br>`ConfigurationLifecycleTest#copying`<br>2 个已执行实例 |
| TC-CFG-04 | PASS | 模板/认证expectedRevision旧值publish409；并发draft编辑与routeCAS各一成一败，无模板/认证半更新。 | `ConfigurationLifecycleTest#staleDraft`<br>`ConfigurationVersionTest#routeCompare`<br>2 个已执行实例 |
| TC-CFG-05 | PASS | 50提交对20次路由切换；独立JVM并发发布/在途发送：URL/Header/Body/authVersion整组v1或v2。 | `ConfigurationVersionTest#submissionsDuring`<br>`ProcessConcurrencyTest#concurrentPublication`<br>2 个已执行实例 |
| TC-CFG-06 | PASS | 独立JVM让old-in-flight v1先返回503并停在AFTER_HTTP；随后切v2、并发切换、回滚v1、重启API，再放行真实重试；旧任务两次URL/Header/Body/凭据均v1，新任务明确v2/回滚v1且全量对账。 | `ConfigurationVersionTest#oldEvent`<br>`ProcessConcurrencyTest#concurrentPublication`<br>`ReplayTest#replayPreserves`<br>3 个已执行实例 |
| TC-CFG-07 | PASS | 共享模板不授权共享认证；A绑定B凭据被拒；只切A不改B版本；租户Secret ref部署ACL不可借用。 | `ConfigurationAccessTest#sharedTemplates`<br>`SecurityAuthorizationTest#tenantConfig`<br>`SecurityAuthorizationTest#roleAndTenant`<br>3 个已执行实例 |
| TC-CFG-08 | PASS | 37模板变体覆盖未知format/策略、非法路径、数组索引、脚本、方法、URL、头及空值；JSONB指数膨胀有界；无任意执行入口。 | `ConfigurationValidationTest`<br>`ConfigurationLifecycleTest#configurationJsonb`<br>`SecurityAuthorizationTest#productionApi`<br>41 个已执行实例 |
| TC-CFG-09 | PASS | 模板/认证/路由跨租户详情和键集分页；不存在引用404/不合法引用400；FK与事务拒悬空；已发布未绑定合法且不可删除。 | `ConfigurationAccessTest`<br>`ConfigurationLifecycleTest#draftsMust`<br>`SecurityAuthorizationTest#operationScope`<br>6 个已执行实例 |
| TC-MAP-01 | PASS | 嵌套对象/数组直传、精确number/boolean/string；引号/中文/换行/NUL Body；保存bytes与物理HTTP相同、Header实际UTF8。 | `MappingIntegrationTest#jsonSupports`<br>`MappingIntegrationTest#jsonBusiness`<br>`WorkerDeliveryTest#acceptedTask`<br>`OutboundWireEncodingTest`<br>`ConfigurationLifecycleTest#largeButAllowed`<br>`ControlledHttpClientTest#sendsOne`<br>6 个已执行实例 |
| TC-MAP-02 | PASS | 直接加载交付crm/ads6样例配置，无Java供应商分支；中文/空格/+&=引号换行form一次编码、Content-Type正确，真实发送。 | `MappingIntegrationTest#secondSupplier`<br>`ConfigurationLifecycleTest#checkedIn`<br>`WorkerDeliveryTest#configurationOnlyForm`<br>3 个已执行实例 |
| TC-MAP-03 | PASS | required缺失/null/5类错类型拒绝无任务；optional缺失/null省略；false/0保留。 | `MappingIntegrationTest#optionalNull`<br>`MappingIntegrationTest#missingNull`<br>6 个已执行实例 |
| TC-MAP-04 | PASS | from与constant互斥/两者缺失、越界路径、数组索引、函数/循环/环境访问表达式均静态拒绝。 | `ConfigurationValidationTest#invalidTemplates`<br>37 个已执行实例 |
| TC-MAP-05 | PASS | form非空string/number/boolean精确编码；空串/null/对象/数组/嵌套fields常量或来源拒绝。 | `MappingIntegrationTest#formRejects`<br>`MappingIntegrationTest#secondSupplier`<br>`ConfigurationValidationTest#invalidTemplates`<br>39 个已执行实例 |
| TC-MAP-06 | PASS | 非string、CR/LF/NUL/C0/DEL、大小写保留头、APIKey冲突；HTAB允许；编码器唯一Content-Type、策略唯一认证。 | `ConfigurationValidationTest`<br>`MappingIntegrationTest#ordinaryHeader`<br>`ApiKeyAuthStrategyTest`<br>`OutboundWireEncodingTest`<br>55 个已执行实例 |
| TC-MAP-07 | PASS | 接入262144/262145字节；渲染Body262144/262145；普通头16384/16385；中文多字节、受理前拒绝且无网络。 | `InboundExactSizeTest`<br>`SubmissionParserTest#oversized`<br>`MappingIntegrationTest#outputBody`<br>`MappingIntegrationTest#ordinaryHeader`<br>5 个已执行实例 |
| TC-MAP-08 | PASS | 仅description变化不改执行字节；实际POST以及独立PUT/PATCH各一次发送；不支持方法/动态URL拒绝。 | `ConfigurationLifecycleTest#copying`<br>`IndependentBusinessMethodTest`<br>`ConfigurationValidationTest#invalidTemplates`<br>`WorkerDeliveryTest#acceptedTask`<br>41 个已执行实例 |
| TC-AUTH-01 | PASS | APIKey头/前缀正确，运行时Secret解析；缺ref/撤销ACL无HTTP，AUTH_CONFIGURATION_ERROR；快照日志无真实值。 | `ApiKeyAuthStrategyTest`<br>`WorkerDeliveryTest#acceptedTask`<br>`WorkerDeliveryTest#aMissing`<br>`SensitiveDataExposureTest`<br>18 个已执行实例 |
| TC-AUTH-02 | PASS | Basic先formencode再Base64与Post精确位置；client_credentials、clientId/secret/scope去重排序；实际环境Secret→Bearer，不依赖refresh_token。 | `OAuthTokenProtocolTest#usesCorrect`<br>`WorkerOAuthTest#environmentBacked`<br>4 个已执行实例 |
| TC-AUTH-03 | PASS | 50线程真实TokenHTTP，明确49WAIT+1leader屏障，1次获取/1generation；两个operation真实Worker共享缓存。 | `TokenCacheTest#fiftyConcurrent`<br>`WorkerOAuthTest#environmentBacked`<br>3 个已执行实例 |
| TC-AUTH-04 | PASS | 8个key维度分别隔离、等价scope集合命中；真实两tenant不同profile/clientId/scope/credentialVersion双Token屏障并发，逐eventBearer不串用，重复事件各自命中。 | `TokenCacheTest#allAuthorization`<br>`TenantOAuthIsolationTest`<br>2 个已执行实例 |
| TC-AUTH-05 | PASS | 3600s有效期在3539/3540s安全边界；短寿命、planned耗时+margin、发送前真实暂停导致不足；不发已知过期token、不原地刷新循环。 | `TokenCacheTest#safetyBoundary`<br>`TokenCacheTest#plannedBusiness`<br>`WorkerOAuthBoundaryTest#tokenLosing`<br>`WorkerOAuthBoundaryTest#aNewlyIssued`<br>4 个已执行实例 |
| TC-AUTH-06 | PASS | 缺expires_in需明确fallback；非法/负/0/小数/字符串/超范围TTL不被fallback掩盖；opaque非JWT可用；过短明确失败不循环。 | `OAuthTokenProtocolTest#malformedProtocol`<br>`OAuthTokenProtocolTest#missingExpiry`<br>`WorkerOAuthBoundaryTest#aNewlyIssued`<br>15 个已执行实例 |
| TC-AUTH-07 | PASS | 合法Bearer invalid_token才恢复；T4used/required/digest持久，下一attempt新token；每attempt1业务HTTP，总2；每run最多1恢复。 | `WorkerOAuthTest#invalidTokenRecovery`<br>`WorkerOAuthTest#budgetExhaustion`<br>`BearerChallengeTest`<br>25 个已执行实例 |
| TC-AUTH-08 | PASS | 旧generation401不能删新缓存，新token直接复用；相同值新generation仍拒；leader私有约束不污染正常waiter。 | `TokenCacheTest#delayedOld401`<br>`TokenCacheTest#leadersPrivate`<br>`WorkerOAuthTest#delayedOld401`<br>`WorkerOAuthTest#aNewGeneration`<br>5 个已执行实例 |
| TC-AUTH-09 | PASS | 真实Token429/500/503/timeout：当次计次NOT_SENT、业务0、退避+RA/cooldown；恢复后成功且业务熔断0样本；deadline耗尽走保守UNKNOWN回收另验。 | `WorkerOAuthBoundaryTest#temporaryToken`<br>`WorkerOAuthBoundaryTest#actualTokenTimeout`<br>`WorkerOAuthBoundaryTest#failurePersists`<br>`WorkerProtectionTest#aHalfOpen`<br>`OAuthTokenProtocolTest#oauthErrorResponses`<br>17 个已执行实例 |
| TC-AUTH-10 | PASS | invalid_client/unauthorized_client/invalid_scope终止；业务普通401/403/APIKey401不恢复；第1/12预算与二次invalid_token无额外恢复。 | `WorkerOAuthTest#definitiveCredential`<br>`WorkerOAuthTest#ordinaryUnauthorized`<br>`WorkerOAuthTest#budgetExhaustion`<br>`WorkerOAuthTest#malformedOr`<br>`WorkerOAuthBoundaryTest#anApiKey`<br>12 个已执行实例 |
| TC-AUTH-11 | PASS | 50失败等待者49WAIT屏障、仅1Token网络；短cooldown不串行击穿，冷却后可用；waiter超时/interrupt不取消leader；close取消资源；容量/inflight不驱逐。 | `TokenCacheTest#fiftyFailed`<br>`TokenCacheTest#anIndividual`<br>`TokenCacheTest#fullCache`<br>`TokenCacheLifecycleTest`<br>`TokenCacheTest#leadersPrivate`<br>10 个已执行实例 |
| TC-AUTH-12 | PASS | 同Secret ref新独立JVM实际envversion轮换；旧profile/clientId/scope/URL不改；新profile路由只影响新任务，不复用旧cache。 | `ProcessAuthRecoveryTest#rotating`<br>`WorkerOAuthBoundaryTest#aNewAccount`<br>`TokenCacheTest#allAuthorization`<br>3 个已执行实例 |
| TC-AUTH-13 | PASS | 401恢复T4commit后真实kill，成功及再次拒绝两个分支；used/required不丢；tokenwait超时之后共享leader完成只入cache，不给旧attempt补发。 | `ProcessAuthRecoveryTest#committedInvalid`<br>`WorkerOAuthBoundaryTest#crashAfter`<br>`WorkerOAuthBoundaryTest#lateShared`<br>`WorkerOAuthTest#transientToken`<br>5 个已执行实例 |
| TC-WORK-01 | PASS | 双native调度Worker唯一租约；真实HTTP期间独立PG锁可得；固定tick初始空槽预算，完成返槽不无界claim。 | `ProcessConcurrencyTest#twoApi`<br>`DeliveryRepositoryTest#competingClaims`<br>`WorkerDeliveryTest#supplierWait`<br>`WorkerDeliveryTest#aManualTick`<br>4 个已执行实例 |
| TC-WORK-02 | PASS | AFTER_CLAIM真实kill/同进程interrupt；attempt0历史空；真实租约回收后首次reserve1并成功。 | `ProcessWorkerRecoveryTest#aRealKill`<br>`WorkerCrashWindowTest#interruptionBefore`<br>8 个已执行实例 |
| TC-WORK-03 | PASS | AFTER_RESERVE及AFTER_REQUEST_STARTED各真实kill；T2任务次数+attempt原子，已预留不退；UNKNOWN后重发；事务失败无部分计次。 | `ProcessWorkerRecoveryTest#aRealKill`<br>`WorkerCrashWindowTest#interruptionBefore`<br>`DeliveryRepositoryTest#reservationRollback`<br>9 个已执行实例 |
| TC-WORK-04 | PASS | AFTER_HTTP/T4前kill；去重和非去重两变体，稳定event重复HTTP；分别副作用1和2，明确至少一次。 | `ProcessWorkerRecoveryTest#aRealKill`<br>`WorkerCrashWindowTest#supplierAcknowledgement`<br>7 个已执行实例 |
| TC-WORK-05 | PASS | 旧owner成功/503/新run四独立JVM变体；旧defer在expired-unrecovered/replacement/replayed三变体0变化；T4等待锁跨lease全回滚。 | `ProcessWorkerRecoveryTest#aPausedOld`<br>`WorkerBoundaryTest#aLateWorker`<br>`RepositoryOwnershipRaceTest#staleDeferral`<br>`DeliveryRepositoryTest#aCompletion`<br>9 个已执行实例 |
| TC-WORK-06 | PASS | 错误token/run/status，过期未接管token仍相同，过期claim晚reserve，以及operator新run；旧T2/T3/T4/defer均拒绝无发送。 | `DeliveryRepositoryTest#wrongToken`<br>`DeliveryRepositoryTest#anExpiredLease`<br>`ProcessWorkerRecoveryTest#anExpiredClaim`<br>`ReplayTest#oldWorker`<br>`RepositoryOwnershipRaceTest#staleDeferral`<br>7 个已执行实例 |
| TC-WORK-07 | PASS | deadline前已reserve且有效lease内响应可成功；deadline后不新reserve；远未来nextAttemptAt仍独立expire，不改最后结果。 | `WorkerBoundaryTest#anAttemptStarted`<br>`WorkerBoundaryTest#deadlineAfter`<br>`WorkerRetryTest#independentExpiry`<br>3 个已执行实例 |
| TC-WORK-08 | PASS | 剩余预算/次数耗尽/期限耗尽；回收两次幂等；真实PG屏障回收与claim竞争仅1替代lease；不虚增attempt。 | `DeliveryRepositoryTest#recoveryUses`<br>`RepositoryOwnershipRaceTest#competingRecoverers`<br>`WorkerRetryTest#recoveringAClaim`<br>3 个已执行实例 |
| TC-WORK-09 | PASS | HTTP成功后真实DB网络断开，task/attempt写回整体失败无假成功；独立Worker按lease恢复并允许重复；记录UNKNOWN。 | `ProcessDatabaseRecoveryTest#acknowledgedHttp`<br>`WorkerBoundaryTest#aSuccessful`<br>`DeliveryRepositoryTest#completionRolls`<br>3 个已执行实例 |
| TC-WORK-10 | PASS | SIGTERM宽限有界停止/真实强杀；JVM±3600s JavaClock偏移不动PG/nano/宿主；PG Asia/Tokyo与JVM其它时区实际观测，API UTC。 | `ProcessWorkerRecoveryTest#sigterm`<br>`ProcessClockSkewTest`<br>`WorkerBoundaryTest#shutdown`<br>`ProcessDatabaseRecoveryTest#terminalTasks`<br>`ProtectionGateTest#explicitDatabase`<br>`ProtectionGateTest#wallClock`<br>7 个已执行实例 |
| TC-RETRY-01 | PASS | 真实500→500→200三attempt、两次退避、同快照、单attempt单HTTP。 | `WorkerRetryTest#twoServer`<br>`ControlledHttpClientTest#neverRetries`<br>`ControlledHttpClientTest#retryAfterDoes`<br>4 个已执行实例 |
| TC-RETRY-02 | PASS | max3及生产max12两变体，额外扫描不第4/13次；HTTP_REJECTED+budget原因保留、failed不删除。 | `WorkerRetryTest#exhaustedAttemptBudget`<br>2 个已执行实例 |
| TC-RETRY-03 | PASS | 下次退避≥deadline直接终止；pending远未来next、长期Open与quota都独立到期，无无限pending。 | `WorkerRetryTest#retryAfterExactly`<br>`WorkerRetryTest#independentExpiry`<br>`WorkerProtectionTest#actualNine`<br>`WorkerProtectionTest#longQuota`<br>4 个已执行实例 |
| TC-RETRY-04 | PASS | 副作用后断连/超时两变体UNKNOWN；相同event重试；传输DNS/TLS/header/body总预算/取消释放实证。 | `WorkerRetryTest#aLostResponse`<br>`ControlledHttpClientTest#totalTimeout`<br>`OutboundResourceLimitsTest`<br>29 个已执行实例 |
| TC-RETRY-05 | PASS | 固定random0/1与范围、指数/大attempt饱和、默认30s上限1h，无负数/溢出；允许FullJitter0。 | `RetryPolicyTest#productionDefaults`<br>`RetryPolicyTest#networkUncertainty`<br>2 个已执行实例 |
| TC-RETRY-06 | PASS | delta秒/三HTTP-date格式/过去/0/负/小数/非法/超大及重复头；有效RA可越本地cap但不早发，≥deadline终止；双位年50年/闰年边界。 | `RetryPolicyTest#parsesValid`<br>`RetryPolicyTest#invalidRetry`<br>`RetryPolicyTest#duplicateHeaders`<br>`RetryPolicyTest#obsoleteDates`<br>`WorkerRetryTest#retryAfter`<br>`WorkerRetryTest#anOverflowing`<br>`ProtectionGateTest#defaultThirty`<br>26 个已执行实例 |
| TC-RETRY-07 | PASS | 20策略状态+17物理HTTP：200/204/其他2xx、3xx、400/401/403/404/409、408/429、各5xx；2xx业务失败Body仍ACK，408/429不计熔断。 | `RetryPolicyTest#follows`<br>`WorkerRetryTest#physicalHttp`<br>`ProtectionGateTest#excluded`<br>`ControlledHttpClientTest#neverFollows`<br>51 个已执行实例 |
| TC-RETRY-08 | PASS | 部署预算12/24h改3/2h并重启：旧round仍12/原deadline，新task和replay轮新预算；累计历史保留。 | `ReplayTest#replayPreserves`<br>1 个已执行实例 |
| TC-GUARD-01 | PASS | 默认窗口20/min10/rate50；9样本不开，第10个5/10失败开，低于阈值不开；一次计样、域隔离。 | `ProtectionGateTest#defaultsAnd`<br>`ProtectionGateTest#eligible`<br>`WorkerProtectionTest#actualNine`<br>3 个已执行实例 |
| TC-GUARD-02 | PASS | Open前等待窗口0业务/0预留、明确defer；有限扫描与deadline终止；live状态不驱逐。 | `WorkerProtectionTest#actualNine`<br>`ProtectionCapacityTest`<br>5 个已执行实例 |
| TC-GUARD-03 | PASS | 纯Gate50并发仅1probe及真实多task同时半开；200关闭/503重开两变体；未选任务0attempt。 | `ProtectionGateTest#fiftyConcurrentHalf`<br>`WorkerProtectionTest#realClaimed`<br>`ProtectionGateTest#aLateClosed`<br>6 个已执行实例 |
| TC-GUARD-04 | PASS | HalfOpen token429/5xx/timeout及取消/未发准备失败5变体；释放probe且不计成功或失败样本，下一任务可探测。 | `ProtectionGateTest#abandonedHalf`<br>`WorkerProtectionTest#aHalfOpen`<br>`WorkerOAuthBoundaryTest#lateShared`<br>7 个已执行实例 |
| TC-GUARD-05 | PASS | 严格原夹具单Worker10/3、A30超时+B10固定100ms，真实观察20s；B首发≤5s/全成≤10s；队列/在途界限与40ID对账。 | `WorkerIsolationTest`<br>1 个已执行实例 |
| TC-GUARD-06 | PASS | RA秒/日期，两auth域A冷却B继续；T2前0计次；T2后屏障及真实OAuth等待中新429保留1次NOT_SENT且0业务；已发送不撤回。 | `WorkerProtectionTest#quotaCooling`<br>`WorkerProtectionTest#aNewQuota`<br>`WorkerProtectionTest#aQuotaRestriction`<br>`ProtectionGateTest#quotaLower`<br>`ProtectionGateTest#enormousLegal`<br>`ProtectionGateTest#aLaterZero`<br>8 个已执行实例 |
| TC-GUARD-07 | PASS | 10排除结果不计成功或失败；endpointGroup与authquota独立；重启只清本地保护，原10failed/延迟任务仍在。 | `ProtectionGateTest#excluded`<br>`ProtectionGateTest#quotaLower`<br>`ProcessAuthRecoveryTest#restartingAWorker`<br>13 个已执行实例 |
| TC-GUARD-08 | PASS | 小池并发超时/interrupt重复循环后全资源归还；CP10持续压力曲线与排空；本地每provider3/双进程可合6，非集群限流。 | `OutboundResourceLimitsTest`<br>`WorkerIsolationTest`<br>`TokenCacheLifecycleTest`<br>`OperationalMetricsTest#hikariWaiting`<br>`OperationalMetricsTest#realToken`<br>`ControlledHttpClientTest#dnsThat`<br>`ControlledHttpClientTest#callerInterruption`<br>`ControlledHttpClientTest#rejectsExcess`<br>37 个已执行实例<br>[pressure-audit.json](../CP10/pressure-audit.json)、[burst-audit.json](../CP10/burst-audit.json)、[recovery-audit.json](../CP10/recovery-audit.json) |
| TC-REPLAY-01 | PASS | failed新run pending/本轮0/清auth恢复/新deadline；累计/attempt/原快照/event固定；原lastOutcome保留；审计前后run+reason。 | `ReplayTest#replayPreserves`<br>`SensitiveDataExposureTest`<br>3 个已执行实例 |
| TC-REPLAY-02 | PASS | 50同key同params真实锁屏障只有1新run1审计；完成后重复key仍返回固定原响应不清零。 | `ReplayTest#fiftyConcurrent`<br>`ReplayTest#replayPreserves`<br>3 个已执行实例 |
| TC-REPLAY-03 | PASS | 50不同key同expectedRun仅1胜者其余409；事务审计失败回滚task与replay幂等记录。 | `ReplayTest#fiftyConcurrent`<br>`ReplayTest#replayAudit`<br>3 个已执行实例 |
| TC-REPLAY-04 | PASS | pending/processing/succeeded拒；staleRun409；BUSINESS无权限403，跨tenant/op或未知ID404；不变状态不新调用。 | `ReplayTest#replayRejects`<br>`SecurityAuthorizationTest#operationScope`<br>`SecurityAuthorizationTest#roleAndTenant`<br>3 个已执行实例 |
| TC-REPLAY-05 | PASS | 重投commit后真实响应丢失，原key重试唯一result/审计；改reason/expectedRun409；业务eventKey不能替代replayRequestId。 | `ReplayTest#lostReplay`<br>`ReplayTest#replayInput`<br>`ProcessWorkerRecoveryTest#aPausedOld`<br>6 个已执行实例 |
| TC-SEC-01 | PASS | 业务/Token双目标：协议/http/userinfo/未授权域/回环/私网/linklocal/metadata/Azure168.63.129.16/IPv6/mapped/混淆数字；实际禁止0连接；URLuserinfo不记日志。 | `OutboundSecurityTest#productionRejects`<br>`OutboundSecurityTest#authorityNormalization`<br>`OutboundSecurityTest#numericLiteral`<br>`SecurityAuthorizationTest#productionPublication`<br>`SensitiveDataExposureTest`<br>`WorkerDeliveryTest#anAcceptedHttps`<br>7 个已执行实例 |
| TC-SEC-02 | PASS | 先允许后禁止DNS与多A/AAAA混合拒；数字IP走真实predicate；只用已验证地址、不二次解析，TLS SNI/Host保留原域名。 | `OutboundSecurityTest#publicPreflight`<br>`OutboundSecurityTest#checkedDns`<br>`OutboundSecurityTest#oneForbidden`<br>`OutboundTlsTest`<br>`ControlledHttpClientTest#aDns`<br>`ControlledHttpClientTest#validatedResolver`<br>15 个已执行实例 |
| TC-SEC-03 | PASS | 301/302/307/308业务+Token共8真实变体；受保护及允许跳转目标均0到达，无Body/凭据转发。 | `OutboundSecurityTest#redirects`<br>`OAuthTokenProtocolTest#tokenRedirects`<br>`OutboundSecurityTest#inheritedProxies`<br>`ControlledHttpClientTest#doesNotRetain`<br>`ControlledHttpClientTest#returnsAuthentication`<br>12 个已执行实例 |
| TC-SEC-04 | PASS | 合法/过期/错hostname/不可信证书×业务/Token=8真实TLS，CA链/hostname/SNI验证，不trust-all。 | `OutboundTlsTest`<br>8 个已执行实例 |
| TC-SEC-05 | PASS | 两客户端认证形式下成功/业务拒/Token错误/预览/发布/查询/重投；fakeSecret及Basic/Form变体零日志/API/审计泄露；task只必要快照，attempt无响应体。 | `SensitiveDataExposureTest`<br>`SecurityAuthorizationTest#tenantConfig`<br>`SecurityAuthorizationTest#revokedOAuth`<br>4 个已执行实例 |
| TC-SEC-06 | PASS | 业务200/503×huge/endless/slow6变体只读headers即关闭；Token64KiB/+1/chunked、body/decoder≤5s；header行/数量/总量、压缩不解压、DNS/TLS/header取消全部资源可复用。 | `OutboundResourceLimitsTest`<br>`OAuthTokenProtocolTest#tokenJson`<br>`OAuthTokenProtocolTest#chunked`<br>`OAuthTokenProtocolTest#slowResponse`<br>`OAuthTokenProtocolTest#responseDecoder`<br>`OAuthTokenProtocolTest#aLongRequested`<br>`ControlledHttpClientTest#finalResponse`<br>33 个已执行实例<br>[pressure-audit.json](../CP10/pressure-audit.json) |
| TC-SEC-07 | PASS | 全业务/配置/route/replay/history/list角色+tenant+operation交集；伪造字段不授权；Secret引用租户ACL发布及旧任务运行再核，指标ADMIN专用。 | `SecurityAuthorizationTest`<br>`ConfigurationAccessTest`<br>`NotificationAccessAndQueryTest`<br>`AttemptHistoryTest`<br>`OperationalMetricsTest#globalMetrics`<br>19 个已执行实例 |
| TC-SEC-08 | PASS | 真实production无故障HTTP/脚本入口，production拒devCIDR；独立jar条目扫描无testsupport/Mock/clockagent，213源文件四类secret模式0命中；demo仅明显假值。 | `SecurityAuthorizationTest#productionApi`<br>`OutboundSecurityTest#authorityNormalization`<br>`ApplicationFoundationTest#rejectsInvalid`<br>3 个已执行实例 |
| TC-OBS-01 | PASS | requestId→notificationId与WorkerexecutionId分开；成功/拒绝/NOT_SENT/UNKNOWN阶段可追溯；纯延期/无attempt回收/expire/replay保留最后结果，历史唯一键。 | `SensitiveDataExposureTest`<br>`AttemptHistoryTest`<br>`WorkerRetryTest#recoveringAClaim`<br>`WorkerRetryTest#independentExpiry`<br>`WorkerOAuthBoundaryTest#failurePersists`<br>`ReplayTest#replayPreserves`<br>7 个已执行实例 |
| TC-OBS-02 | PASS | 8实例精确验证commit才计受理、安全/冲突/rollback/rate/backlog、成功/重试/失败/恢复、Token实取/缓存；PGdue最老时间/stale显式；Hikari等待/超时与独立HTTP池可见。 | `OperationalMetricsTest`<br>8 个已执行实例 |
| TC-OBS-03 | PASS | api/worker/all三独立JVM，断DB/缺schema/notready与live200、单供应商503不拖APIready；恢复自动ready。 | `ApplicationFoundationTest`<br>`ProcessDatabaseRecoveryTest#eachRealRole`<br>11 个已执行实例 |
| TC-OBS-04 | PASS | 发布/切route/rollback/replay actor和对象、before/after revision/run与原因；失败事务0伪审计，同key1条，未授权读取拒绝。 | `SensitiveDataExposureTest`<br>`ConfigurationLifecycleTest#staleDraft`<br>`ConfigurationVersionTest#routeCompare`<br>`ReplayTest#replayAudit`<br>`ReplayTest#fiftyConcurrent`<br>`SecurityAuthorizationTest#roleAndTenant`<br>8 个已执行实例 |
| TC-OBS-05 | PASS | 成功与failed终态/历史跨重启扫描仍在；输出UTC；文档明确failed可包含已产生副作用的UNKNOWN，不是未执行证明。 | `ProcessDatabaseRecoveryTest#terminalTasks`<br>`ProcessWorkerRecoveryTest#sigterm`<br>2 个已执行实例 |
| TC-OPS-01 | PASS | 全新目录/独立PG数据卷完整531回归；实际all与API+双Worker部署、最小角色权限、重复迁移无变更；同一fresh JAR两轮演示及200受理在途交换SIGKILL，204实际HTTP/200副作用，四任务等待真实90s租约后恢复。 | `ApplicationFoundationTest`<br>`PostgresMigrationTest`<br>`DeploymentMigrationTest`<br>`ProcessConcurrencyTest#twoApi`<br>`ProcessDatabaseRecoveryTest#eachRealRole`<br>18 个已执行实例<br>[fresh构建](fresh-verification.json)、[部署](deployment-distributed.json)、[恢复审计](recovery-audit.json) |
| TC-OPS-02 | PASS | 双Worker混合14任务22attempt/20HTTP真实swapkill；CP10容器200受理、204HTTP、200副作用、4租约90s真实回收，2次SIGKILL exit137重启PID改变。 | `ProcessWorkerRecoveryTest#mixedAccepted`<br>`ProcessConcurrencyTest#twoApi`<br>`ProcessAuthRecoveryTest`<br>6 个已执行实例<br>[recovery-audit.json](../CP10/recovery-audit.json) |
| TC-OPS-03 | PASS | 真实10租户×1200、1024字节、20rps600s=12000；预热100独列；API P95 6.606542ms≤300、first业务send0.097579956s≤3、reserve0.094468117s；全集合对账无未发/未终态。 | <br>0 个已执行实例<br>[baseline-audit.json](../CP10/baseline-audit.json) |
| TC-OPS-04 | PASS | 20/40/80/160rps各30s=9000实际offered；3605成功、1601入站429、3794积压503且拒绝无任务；峰值500，停流18.430155s排空；可持续物理吞吐约28rps，非40rps入站即40rps持续出站。 | <br>0 个已执行实例<br>[pressure-audit.json](../CP10/pressure-audit.json) |
| TC-OPS-05 | PASS | 固定200/100ms、A/B各100、A前15s超时、B100ms；正式burst002200受理，B4.070785s/A19.353264s，753claim均不早due、214HTTP/200效应；burst001128发流槽丢8明确失败并保留。 | <br>0 个已执行实例<br>[burst-audit.json](../CP10/burst-audit.json) |
| TC-OPS-06 | PASS | 95逻辑目录和28AT逐项核对参数变体，映射531个实际执行身份及330场景；全新环境README命令、两模式演示/失败原快照重投/终态幂等、真实双Worker强杀已复现；问题理解/架构/取舍/边界及AI历史归因人工审阅通过。 | 人工逐项审阅与真实环境演示；不冒充JUnit实例<br>[all](demo-all.json)、[双Worker](demo-distributed.json)、[恢复](recovery-audit.json) |

## 28 项 PRD 验收条件

| AT | 状态 | 对应逻辑用例 |
| --- | --- | --- |
| AT01 供应商正常返回 200 | PASS | TC-API-01、TC-MAP-01、TC-AUTH-01 |
| AT02 JSON 和表单两个供应商 | PASS | TC-MAP-02、TC-MAP-05、TC-MAP-08 |
| AT03 相同事件并发提交 | PASS | TC-IDEM-01、TC-IDEM-02、TC-IDEM-03、TC-IDEM-04 |
| AT04 任务入库后受理响应丢失 | PASS | TC-API-02、TC-IDEM-07 |
| AT05 模板缺少必填数据或类型错误 | PASS | TC-API-03、TC-API-07、TC-MAP-03、TC-MAP-04 |
| AT06 草稿、预览、发布和回滚 | PASS | TC-CFG-01、TC-CFG-02、TC-CFG-03、TC-CFG-04、TC-CFG-07、TC-CFG-08、TC-CFG-09、TC-OBS-04 |
| AT07 发布与任务受理并发 | PASS | TC-CFG-03、TC-CFG-05、TC-CFG-06 |
| AT08 同事件在配置升级后再次提交 | PASS | TC-IDEM-05、TC-IDEM-06、TC-IDEM-07 |
| AT09 供应商先 500 后 200 | PASS | TC-RETRY-01、TC-RETRY-05、TC-RETRY-07 |
| AT10 供应商始终失败 | PASS | TC-RETRY-02、TC-RETRY-03、TC-RETRY-08、TC-OBS-01、TC-OBS-02、TC-OBS-05 |
| AT11 请求超时 | PASS | TC-RETRY-04 |
| AT12 领取后、预留 attempt 前宕机 | PASS | TC-WORK-02、TC-WORK-08 |
| AT13 预留后、发送前宕机 | PASS | TC-WORK-03、TC-WORK-08 |
| AT14 供应商成功但本地未记录便宕机 | PASS | TC-WORK-04、TC-WORK-09 |
| AT15 旧 Worker 在租约接管后写回 | PASS | TC-WORK-05、TC-WORK-06 |
| AT16 双 Worker 同时运行并结束其中一个 | PASS | TC-WORK-01、TC-WORK-10、TC-OPS-01、TC-OPS-02 |
| AT17 并发 Token 缓存未命中 | PASS | TC-AUTH-02、TC-AUTH-03、TC-AUTH-04、TC-AUTH-11、TC-AUTH-12 |
| AT18 Token 到期与旧 401 并发 | PASS | TC-AUTH-05、TC-AUTH-07、TC-AUTH-08、TC-AUTH-10、TC-AUTH-12、TC-AUTH-13 |
| AT19 Token 接口暂时故障与 invalid_client | PASS | TC-AUTH-01、TC-AUTH-06、TC-AUTH-09、TC-AUTH-10、TC-AUTH-11、TC-AUTH-13、TC-GUARD-04、TC-SEC-05 |
| AT20 熔断打开和半开 | PASS | TC-GUARD-01、TC-GUARD-02、TC-GUARD-03、TC-GUARD-04、TC-GUARD-07 |
| AT21 一个供应商持续超时 | PASS | TC-GUARD-05、TC-GUARD-07、TC-OPS-05 |
| AT22 429 和 Retry-After | PASS | TC-RETRY-06、TC-GUARD-06 |
| AT23 同键和不同键并发人工重投 | PASS | TC-REPLAY-01、TC-REPLAY-02、TC-REPLAY-03、TC-REPLAY-04、TC-REPLAY-05、TC-RETRY-08、TC-OBS-04 |
| AT24 越权查询、修改及重投 | PASS | TC-API-04、TC-API-05、TC-API-06、TC-CFG-07、TC-CFG-09、TC-SEC-05、TC-SEC-07 |
| AT25 非法目标、重定向和超大请求 | PASS | TC-MAP-06、TC-MAP-07、TC-SEC-01、TC-SEC-02、TC-SEC-03、TC-SEC-04、TC-SEC-06、TC-SEC-08、TC-RETRY-07 |
| AT26 数据库不可用 | PASS | TC-API-02、TC-API-08、TC-WORK-09、TC-OBS-02、TC-OBS-03 |
| AT27 长时间熔断或冷却 | PASS | TC-WORK-07、TC-WORK-08、TC-RETRY-03、TC-RETRY-06、TC-GUARD-02、TC-OPS-05 |
| AT28 基线和压力测试 | PASS | TC-OPS-03、TC-OPS-04、TC-OPS-05、TC-GUARD-08 |

## 人工审阅结论

- SEC08：当前生产jar无测试支持类、Mock、进程控制或时钟注入agent；213个跟踪的非报告文本文件在4类常见真实密钥模式下0命中。`.env.example`仅明确隔离演示假值。模式扫描不能证明所有可能秘密绝对不存在。
- OBS05：成功/失败终态及尝试历史独立JVM重启后保留，UTC输出已实际测试；failed/UNKNOWN不等于对方从未处理。
- AI使用说明：已复核修订，保留可见用户决策来源和AI代码/测试贡献，删除无法独立确认的早期建议精确时序；Redis仍正确归因于用户提出的候选方案。
- OPS01/OPS06：全新数据库531回归、all及API+双Worker两次README演示和新环境200任务在途强杀恢复均已独立核验。终态幂等、真实Body摘要、重复迁移、最小权限、原快照重投与最终95/28追踪均通过。

## 原始负载数据的三分位复算

采用 nearest-rank `ceil(N×p)`，每个202均计入，失败/未发送不会静默删样本。压力单列所有输入响应（含429/503）与202受理响应，完整P50/P95/P99及原始SHA见JSON。以下顺序均为P50 / P95 / P99。

| 运行 | 202接入 ms | accepted→首个可用持久HTTP发送 s | accepted→首次预留 s |
| --- | --- | --- | --- |
| baseline-001 | 2.979250 / 6.606542 / 8.402542 | 0.052031 / 0.097580 / 0.106862 | 0.049283 / 0.094468 / 0.102124 |
| pressure-001 | 2.209291 / 4.720792 / 7.732000 | 13.222868 / 17.964835 / 18.083430 | 13.220936 / 17.962640 / 18.081166 |
| burst-002 | 128.327917 / 205.822708 / 242.432917 | 3.475190 / 18.717763 / 19.053148 | 3.472066 / 18.714265 / 19.051627 |
| recovery-001 | 4.462667 / 10.767292 / 16.362625 | 8.507458 / 14.686990 / 91.281959 | 8.267790 / 14.473667 / 15.108980 |

recovery的4个首attempt已实际到达Mock，但强杀前未完成T4，故首次`http_send_started_at`没有持久化；该列对这4项保守采用后续可用值，不能称其原始首发时刻。另保留Mock首次到达分位，二者语义分开。故障与过载运行不套用健康基线3秒目标。

全部531条实际JUnit身份至少映射一项逻辑用例；另外用真实部署、负载和人工文档审阅闭合OPS01/06等非JUnit验收，不把没有JUnit实例的项目虚构为自动化测试。

## 全新环境自动化独立核验

`41d08c6`归档到新目录、使用新PG数据卷，完整构建exit0；51套件531个实际节点无失败/错误/跳过。330份场景与fresh target逐字节相同、归档哈希一致；常见敏感字段及已知假凭据编码扫描0命中。当前Java/Maven相关175个输入与本次测试开始manifest一致；生产jar无测试控制设施。fresh目录仅`.gitignore`有非构建差异；后续README/demo已按各自脚本SHA与实际执行单独验收，未声称文档演示由531回归自动覆盖。

## 文档演示独立核验

[all模式](demo-all.json)与[API+双Worker](demo-distributed.json)均与fresh目录实际结果逐字节一致，scriptSHA `b3866c99…`。每次5个唯一任务、4成功、1预期404失败；6个唯一attempt中4次200、2次404。V1→V2→V1及失败原快照重投语义正确；成功/失败终态业务幂等完整验证。8个正常请求Body SHA已独立按固定输入/常量重建匹配，未输出原Body或凭据。两次演示不把预期404称为恢复成功。

## 全新环境在途强杀恢复独立核验

[recovery-audit.json](recovery-audit.json) 逐任务核对200次202、200条succeeded任务、204次attempt/真实HTTP及200次去重副作用。两Worker均在有实际任务执行时收到SIGKILL（exit137），PID变更后健康恢复；4个受影响attempt保留UNKNOWN/LEASE_EXPIRED，实际等待90.014–90.053秒才关闭旧租约，随后自动重新领取，无任务状态修补。204条领取审计均未早于各自due。

四个首发时刻未在强杀前完成持久化：供应商Mock已收到并记录副作用，后续可用持久send时刻属于重试；报告分别保留物理首次到达与首个可用持久发送分位，避免混为一谈。97次资源采样中的4个空窗逐一落在对应Worker强杀至ready窗口，保留缺失，不按0值解释；正常样本新鲜，排空后Worker/Token/HTTP活动、排队与许可均回零。14次Docker采样覆盖5个部署服务及独立的全量门禁test-postgres，全部在配置上限内、无OOM。24份原始文件与归档逐字节一致，脱敏字段及已知假凭据编码检查无泄露。
