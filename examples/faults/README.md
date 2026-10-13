# 真实进程故障验证

这些用例启动独立 Java 进程、共享真实 PostgreSQL，并通过真实 HTTP 访问可控供应商。数据库事务、任务状态机和 Worker 使用应用实现。

仅使用专门的测试数据库，账号需要 CREATE SCHEMA 权限；测试为每个场景创建随机 schema，结束时只删除该 schema。请勿指向生产数据库。启动数据库的例子见最终部署说明；已有测试实例也可使用。

```sh
export TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:55432/rc_test'
export TEST_DATABASE_USER='rc_test'
export TEST_DATABASE_PASSWORD='rc_test_local_only'
./scripts/verify-faults.sh
```

脚本设置的是固定测试假 Secret。凭据轮换场景在新子进程的环境中覆盖相同逻辑引用，应用仍通过真实 SecretResolver 获取凭据。缺少数据库或无法启动子进程会失败，不会跳过。该命令只执行 `Process*Test`，用于复现进程故障；完整验收须运行全部 `clean verify`，不能把定向结果当成累计通过率。

故障控制代码只放在 `src/test`，不进入发布 JAR。父测试通过私有控制文件在已提交的生命周期阶段设置屏障，再对自己启动的进程执行强杀或优雅退出；生产 HTTP 接口没有故障开关。数据库中断由仅属于本测试的 TCP 代理切断现有连接和拒绝新连接，不停止共享 PostgreSQL 容器。

重点观察以下持久状态：

- 领取后、预留前宕机：attempt 仍为 0；租约过期后重新领取。
- 预留后宕机：已计次数不能退回；不完整结果按 UNKNOWN 保留。
- 供应商成功、写回前宕机：允许再次 HTTP 投递；有下游幂等键时业务副作用可去重，没有时可能重复。
- 旧 Worker 迟到：任务、轮次和 lease token 条件阻止其覆盖当前状态。
- 数据库断连：API 不能虚假返回受理成功；写回失败保留待租约恢复状态。
- 明确的 401 恢复意图：重启后仍占该轮唯一的认证恢复机会。
- 时钟偏差：子进程的 `java.time` 墙钟注入 ±1 小时，真实数据库仍决定租约与截止。测试不会修改操作系统、PG 或单调时钟；记录的节点发送时间须先校准才能用于跨时钟延迟计算。

执行后的原始 JUnit 报告位于 `target/surefire-reports`，脱敏场景证据位于 `target/evidence`。证据保留 PID、故障时间、任务状态、attempt 和请求次数，不归档原始 Secret、Token 或业务请求内容。阶段结果以实际报告为准。
