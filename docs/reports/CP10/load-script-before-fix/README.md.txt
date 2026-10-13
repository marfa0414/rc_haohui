# CP10 性能与恢复实测

这些工具只针对仓库的隔离 Compose 环境。Python 3.10+ 标准库即可；数据库查询通过 `docker compose exec -T postgres psql`，不要求宿主安装驱动。脚本不会启动、删除或强杀容器。运行与配置变更由操作者执行。

所有运行使用唯一 `--run-id`，数据输出 `.cache/perf/<run-id>/`；目录存在即拒绝覆盖。`run.py` 负责采集，`analyze.py` 必须另外执行且返回 0 才表示该次门禁通过。分析失败须保留原目录，查明原因后使用新 run-id 重跑，不能删样本或改低目标。

## 凭据与部署

先按 `compose.yaml` 启动已完成迁移的环境。业务和管理调用凭据只读环境变量，不能写入命令参数、证据或 Git：

```bash
export PERF_BUSINESS_TOKEN=rc-demo-business-token
export PERF_ADMIN_TOKEN=rc-demo-admin-token
```

以上均为已公开的本地假值。真实环境不得复用。应用与 Mock 的 `DEMO_VENDOR_SECRET` 必须相同；默认演示值由 Compose 提供。脚本通过真实管理 API 为 tenant-01..10 创建版本、发布、切换 `notify` 路由，引用 `env:DEMO_VENDOR_SECRET`，每租户独立 OAuth profile。供应商 client id 默认为 `demo-client`，可用 `PERF_OAUTH_CLIENT_ID` 同时设置 Mock/脚本。

默认 API `127.0.0.1:18080`、Worker `18081`、Mock 控制面 `19090`；应用连接 `http://mock:9090`。单 `all` 进程必须传 `--nodes http://127.0.0.1:18080`；API + 单 Worker 用默认 nodes；API + 双 Worker 显式指定三个地址。不要在基线同时启用两个 Worker。

DB 默认库/模式 `notifications`，只通过迁移账号查询。可用 `PERF_DB_SCHEMA` 及 `PERF_DB_COMMAND_JSON`（JSON 参数数组，不经 shell）覆盖隔离环境。每次开始要求整个环境没有活动任务；不会删除历史任务。

## 固定运行

基线使用单 Worker（或单 `all`），global=10/provider=3、接入 100rps/burst200/backlog10000，默认 30s attempt/90s lease 等预算。Mock 为 100ms/200。独立预热 20rps×5s=100 条并排空，然后正式 **20rps×600s=12000 条**，10 租户、实际业务 Body 恰好 1024 UTF-8 字节。真实 OAuth Token TTL 为 60s，因此测量期会发生刷新等待。预热和主测量分别保存，不混入 P95。

```bash
python3 scripts/perf/run.py baseline --run-id baseline-001 --nodes http://127.0.0.1:18080
python3 scripts/perf/analyze.py .cache/perf/baseline-001
```

压力同单 Worker，但重建应用使 `ADMISSION_MAX_BACKLOG=500` 生效；rate100/burst200，其余与基线一致。固定阶梯 20/40/80/160rps，各 30s，共 **9000 planned**。不通过阻塞注入器降低实际输入。429 必须是 `ADMISSION_RATE_LIMITED`，503 必须是 `BACKLOG_CAPACITY_EXCEEDED`；数据库失败不能冒充容量保护。

```bash
python3 scripts/perf/run.py pressure --run-id pressure-001 --nodes http://127.0.0.1:18080
python3 scripts/perf/analyze.py .cache/perf/pressure-001
```

突发测试重建单 Worker/all，显式配置：

```text
WORKER_GLOBAL_CONCURRENCY=10
WORKER_PROVIDER_CONCURRENCY=3
WORKER_POLL_INTERVAL=100ms
WORKER_BUSINESS_TIMEOUT=2s
WORKER_ATTEMPT_TIMEOUT=10s
WORKER_LEASE_DURATION=15s
RETRY_BASE_DELAY=100ms
RETRY_MAX_DELAY=1s
PROTECTION_OPEN_DURATION=1s
DELIVERY_MAX_ATTEMPTS=12
DELIVERY_RUN_DURATION=120s
ADMISSION_REQUESTS_PER_SECOND=100
ADMISSION_BURST_CAPACITY=200
ADMISSION_MAX_BACKLOG=10000
```

A/B 各 100，交错在 100ms 输入窗口内一次提交，此后停止输入，观察到 60s。A 从测量开始的前 15s 每次等 3s（超过 2s 业务超时），随后 200/100ms；B 始终 200/100ms。以固定输入起点计算 B 全部≤10s、A 全部≤60s。

```bash
python3 scripts/perf/run.py burst --run-id burst-001 --nodes http://127.0.0.1:18080
python3 scripts/perf/analyze.py .cache/perf/burst-001
```

Compose 双 Worker 恢复使用 `distributed` profile 启动 API、worker-1、worker-2。Mock 延迟 1s，10rps×20s=200 条，持续制造在途；默认允许排空 240s，覆盖 90s 租约恢复。操作者在另一终端观察对应 Worker active>0，依次实际强杀、重启 worker-1 和 worker-2；不停止 API/PG/Mock。不要把没有在途的容器重启当故障窗口证据。

```bash
python3 scripts/perf/run.py recovery --run-id recovery-001 --nodes http://127.0.0.1:18080,http://127.0.0.1:18081,http://127.0.0.1:18082
# 完成下面要求的 lifecycle.jsonl 后再分析
python3 scripts/perf/analyze.py .cache/perf/recovery-001
```

操作者把实际执行记录追加到该目录 `lifecycle.jsonl`，每行形如（不能照抄示例填充）：

```json
{"action":"kill","service":"worker-1","containerId":"实际ID","observedAt":0,"activeBefore":3}
{"action":"restart","service":"worker-1","containerId":"实际ID","observedAt":0}
```

`observedAt` 是执行的 Unix 秒，kill 必须记录真实 activeBefore>0；两 Worker 都要有 kill/restart。命令输出、退出码、镜像/JDK/宿主硬件及 Docker VM 配额也应由操作者保存在本次报告，并只保存安全字段，不能原样导出含 Secret 的 `docker inspect` 或 `docker compose config`。

## 证据与判定

- `requests.jsonl`：每个 planned 事件的绝对单调计划映射墙钟、实际开始/结束、漂移、响应状态与 notificationId。固定 128 个注入工作线程，无排队无限增长；满额记明确失败，不偷偷减速。最大漂移>1s 为负载机未维持本次计划，实测保留但不能签合格。
- `tasks.jsonl` / `attempts.jsonl`：真实 PostgreSQL 投影，不读取 body/headers/secret/digest。`reconciliation.json` 逐任务关联。拒绝事件必须不存在；每个 202 必须对应唯一数据库任务、attempt、Mock 事件和最终成功；未知受理、未发、未终态均失败。
- `journal.jsonl`：Mock 实际调用时刻、业务事件/租户、1024 字节长度和业务 Body SHA；Token 只记录时刻/路径/状态，连 Token 请求 Body SHA 也不保存。副作用由真实去重 ledger 计数，与 HTTP 次数分别记录。
- `resources.jsonl`：每秒尝试采样真实 PG 积压、Mock、ADMIN Actuator/JVM/Hikari/Worker/HTTP 池。每条记录采样时长；各指标通过独立请求读取，不假称原子瞬时快照。按 client 标签分业务/Token；报告是**采样峰值**。健康进程采样缺失或 DB 样本过期不能当 0。恢复测试显式保留被杀节点不可达。
- `docker-resources.jsonl`：每约 5s 采集本 Compose 正在运行容器的 CPU、内存、PIDs、硬限额、镜像 ID 与安全预算参数。`docker inspect` 仅内存解析并按白名单投影，绝不落完整环境变量。
- `clock-before/after.json`：父进程对 PG、Mock、各 JVM 做 5 次校准，取最小 RTT 样本记录偏移和不确定度；偏移绝对值加 RTT 半宽>250ms 则不能据此签 3s 指标。PG `created_at` 是受理事务时间；首次 `http_send_started_at` 是 Worker 进入真实业务传输前墙钟，包含 Token 等待；`started_at` 仅是 T2 预留。分别计算，不以预留时间替代真实 HTTP。
- 基线 P95 使用 nearest-rank，接入≤300ms；accepted→首次业务发送≤3s。另外报告计划到响应延迟，漂移不会被客户端响应延迟隐藏。未发送事件保留在总集合，并令门禁失败，不从报告默默剔除。
- burst/recovery 安装 `perf_observation` 旁路 AFTER trigger，在 T1 原事务记录原 nextAt、新 lease、PG clock，只 INSERT 观察表，不修改任务或结果。回滚没有记录。将每个 attempt 的 lease 与观察行关联，精确检查 DB claim≥due；实际 HTTP 再结合时钟误差核对。此工具带少量写放大，报告明确，基线/压力会卸载该 trigger。
- `backlog-explain.txt` 是真实 `EXPLAIN (ANALYZE,BUFFERS)`，不关闭顺序扫描或伪造索引效果。小数据量可能合理选择顺序扫描，应据实际计划解释。
- `analysis.json` 含原始证据 SHA、目标值、实际值、失败码、分阶输入/受理/拒绝、资源和积压峰值。失败仍保留原始证据；脚本不会预写“通过”。

Mock 容量固定 128 个请求线程、128 accept backlog、最多 250000 条有界 journal；满额 503、overflow 及鉴权失败都有显式计数并导致门禁失败。它仅供本地测试，控制面不能部署到真实生产。

Mock `POST /control/reset` 接受 `runId`、`mode`、`delaySeconds`、`tokenDelaySeconds`、`faultSeconds`、`timeoutSeconds`；`preserveTokens=true` 在暖机后保留已签发 Token；可选 `responseBytes`≤16MiB 用固定 8KiB 分块响应。`POST /control/start` 在发流前1秒用共同的计划墙钟起点校准故障15秒窗口，不能在已有业务调用后修改。实际测试前后不得在在途期间重置（409）。`GET /control/stats` 和按 `after/limit` 分页的 `/control/journal` 只返回脱敏值。正常应用下游不可调用控制面。

这里的四种 Compose 运行不替代累计测试套件里的 30A+10B/20s 隔离场景、大响应/无限流/慢读边界及精确指标增量测试；最终报告分别引用对应真实证据。
