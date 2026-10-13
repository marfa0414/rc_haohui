# CP11 最终交付验收

状态：已完成。全新环境实际运行与独立审计全部通过：531/531 个自动化实例、95/95 个逻辑用例、28/28 项 AT，失败／错误／跳过／未验收均为 0。远程 CI 单独以 GitHub 运行记录为准。

## 验收范围

本阶段核对 README、实际代码、PRD、技术方案、95 个逻辑用例与 28 项 AT，并从全新源目录、全新 PostgreSQL 测试卷和演示卷执行构建与操作演示。Maven 依赖和已校验基础镜像允许复用缓存；这验证干净源码和数据库可复现性，不宣称在无缓存、任意网络条件下已验证下载。

生产实现基于 CP10 提交 `41d08c62b3b4aa59751ff2d42d89235d49aaed1c`。本阶段仅补充交付文档、演示工具和忽略运行缓存；若发现实现缺陷，将记录变更及受影响的重新验证。

## 实际执行

| 验收 | 结果与证据 |
| --- | --- |
| 全新构建 | 2026-10-13 15:57:21–16:03:57（Asia/Shanghai），6 分 35 秒；51 套件、531 个实际实例，0 失败／错误／跳过；[fresh-verification.json](fresh-verification.json) |
| 自动化归档 | [results.json](results.json) 的全部 testcase 身份与新目录原始 XML 对应，330 份 [场景](scenarios/) 逐字节归档；测试 agent 独立核对 |
| all 演示 | 16:05:57–16:05:59，5 个新任务：4 成功、1 预期 404 失败；OAuth 实际交换 1 次；[demo-all.json](demo-all.json) |
| API + 双 Worker 演示 | 16:07:58–16:08:01，同样 5 个新任务，OAuth 实际交换 2 次，展示缓存按进程维护；[demo-distributed.json](demo-distributed.json) |
| 配置与幂等 | validate／preview／publish 无外部 HTTP；V1→V2→回滚 V1；成功与失败终态重复提交均返回当前原任务，完整状态、预算、历史保持不变；相同事件不同数据 409 |
| 重投语义 | 当前路由修好后，原 404 任务重投仍按旧快照 404，run 1/2 和两个 attempt 保留；重复 replayRequestId 固定响应、改参数 409。预期失败不等于演示失败 |
| 实际编码 | 两次演示共 8 个成功请求，按固定输入和发布配置的字段顺序独立重建 UTF-8 Body，实际字节长度与 SHA-256 全部匹配 |
| 部署与迁移 | [all](deployment-all.json)、[distributed](deployment-distributed.json) 的实际 JAR 与新构建一致；API 无 Worker 指标，Worker 启用调度；6 个迁移重复执行无变更；运行角色无 DDL／DELETE／TRUNCATE，迁移和运行账号分离 |
| 全新强杀恢复 | 新 run `cp11-recovery-001`：200 次计划输入均 202 并最终成功；204 个 attempt／真实 HTTP，Mock 去重副作用 200；两次在途 SIGKILL 后 PID 改变、恢复健康，等待租约后回收 |

独立 [恢复审计](recovery-audit.json) 确认 4 个 UNKNOWN 分别对应强杀时的 1 + 3 个在途请求，原租约实际达到 90.014–90.053 秒后回收；204 次领取均未早于 nextAt。4 次指标缺失只落在对应进程 kill→ready 窗口，其余样本正常，最终在途与连接均归零；本次资源采样包含保留的独立测试数据库，未将它误认为业务路径新增实例。[Worker 端口探测](role-probes.json) 两次返回 404、数据库新增任务为 0。

两次演示都不清空 Mock、不执行 SQL 修改状态、不删数据；每次各 6 个实际 attempt（4 个 200 + 2 个 404）。未知路径的 404 由真实投递历史核对，普通业务端点由 Mock journal 与独立 Body 摘要核对。脚本以断言决定退出码，源码见 [demo.py](../../../scripts/demo.py)，本次执行版本 SHA-256 为 `b3866c9916d906fa01e0ebb5160e6851838681753853462f25b104196268c0c2`。

## 来源与复现边界

- [全新源目录](fresh-source.json)：由已提交源码导出，没有复用 target 或数据库。测试端口 55434，Compose 项目 `rc-haohui-fresh`。
- [CP10 实测](../CP10/REPORT.md)：累计 531 项、基线／压力／突发／双 Worker 恢复；本阶段不将这些历史执行冒充新运行。
- [构建开始输入](build-inputs.json)、[归档时源码](source-sha256.txt)、[最终交付源码](final-source-sha256.txt)。构建期间仅同步文档、`.gitignore` 和独立演示脚本，175 个 Java／资源／Maven 输入保持不变。全量回归之后，演示脚本补充终态重复提交及独立 Body 摘要断言，再单独实际执行；未用 Java 回归冒充脚本测试。
- [执行日志摘要](execution-manifest.json)、[演示文件输入](delivery-inputs.json)。首次只读部署采集误用了 `/app/app.jar`，按 Dockerfile 修正为 `/app/notification-service.jar` 后完成核对，没有修改生产实现。
- 全新 JAR SHA-256：`b6ddb7de9174ccae56c17e9d1a36b0898dd386e733504a965498a1d25bb7a3f9`。构建时间等打包元数据使其与 CP10 JAR 摘要不同；Java 源码未变，实际运行容器逐个检查为本次 JAR。
- [恢复原始数据归档](recovery-archive.json)、[强杀执行脚本](recovery-driver.py.txt)、[部署核对脚本](deployment-capture.py.txt)。恢复时被杀进程的指标缺失必须按实际生命周期解释，不当成零；首个可用的持久化发送时间也不能冒充丢失写回前的物理首次发送。
- [95 项逻辑用例与 28 项 AT 矩阵](ACCEPTANCE_MATRIX.md)、[结构化逐项证据](acceptance-matrix.json)；执行实例数和逻辑用例数分别计数。
- [96 个文档相对链接检查](document-link-check.json)，缺失为 0；真实凭据不进入仓库，公开 demo 假值保留明确标注。生产 JAR 没有测试进程、Mock 或时钟注入类。

负载与演示仍是同一台 Apple M5 上的容器环境，基础镜像使用 CP10 已核验官方摘要的本地缓存标签；新建了源目录、`rc-haohui-fresh_test-data` 和 `rc-haohui-fresh_notification-data` 卷，没有复用旧 target 或业务数据。并非在冷缓存、任意公网连接下验证依赖下载。重复迁移、强杀与恢复不依赖更改宿主时间或直接改写任务。

## GitHub 交付

仓库已创建为私有：[marfa0414/rc_haohui](https://github.com/marfa0414/rc_haohui)。本轮 API 曾间歇连接超时，用户恢复网络后创建和首次推送成功。最终文档和报告随 CP11 提交；[Actions 实际运行列表](https://github.com/marfa0414/rc_haohui/actions) 展示远程执行状态，不能仅以本地通过推导远程成功。

README、MVP 源码、设计与边界、可运行示例、真实测试与负载证据、基于历史协作的 AI 使用说明均纳入仓库。已知限制与演进条件保持 README 所列范围，不因测试通过宣称 exactly-once、严格集群配额、数据库高可用或公网生产 SLA。
