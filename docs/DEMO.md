# 从受理到配置升级、回滚与重投

先按根目录 [README](../README.md#快速开始) 完成真实 PostgreSQL 全量验证、镜像构建和 `all` 启动。本演示只使用真实管理/业务 HTTP API 和 Mock 观测端点，不使用 SQL 修改任务、不替换 Worker、不清空 Mock 或数据库。需要 Python 3.11+，仅使用标准库。

在隔离的演示环境执行，期间不要并发运行性能脚本或其他业务：脚本会把 `tenant-01 / notify` 路由切到本次配置，并核对配置阶段没有 Token/业务网络调用。默认内部身份为公开假值 `rc-demo-admin-token`、`rc-demo-business-token`；第三方 Secret 由 Compose 环境配置，脚本只提交 `env:DEMO_VENDOR_SECRET` 引用，不读取实际 Secret。

```sh
# 必须与启动环境使用同一 Compose 项目；脚本通过回环 HTTP 访问它。
export COMPOSE_PROJECT_NAME=rc-haohui-fresh
python3 scripts/demo.py --run-id demo-001
```

每次使用新的 run-id（1–40 个 ASCII 字母/数字/下划线/连字符，首位为字母或数字）。脚本拒绝覆盖 `.cache/demo/<run-id>`；配置、任务和历史全部保留。若修改过内部演示凭据，在运行前设置与部署一致的 `DEMO_ADMIN_TOKEN`、`DEMO_BUSINESS_TOKEN`，不要将真实值放到命令行。脚本不自动读取 `.env`。可用 `--api http://127.0.0.1:18080 --mock http://127.0.0.1:19090` 指定回环端口，容器内目标固定为 `mock:9090`。

## 脚本执行的真实步骤

1. 检查 API 与 Mock readiness，记录 Mock 计数起点；创建并发布本次唯一的 OAuth 配置，采用 client_credentials / client_secret_post。
2. 创建模板 V1，调用 validate 和脱敏 preview，随后发布；确认这一阶段没有 Token 或业务调用，再绑定 `tenant-01 / notify`。
3. 提交一个新事件，必须 `202`；等待真实 OAuth 和业务 HTTP 后查询到 `succeeded`。相同事件和数据再次提交为 `200 / succeeded / run 1`，不同数据为 `409`，任务 ID 不变；观察 300 ms 后完整 TaskView、截止、计数和全部 attempt 历史均不变。
4. 创建并发布模板 V2，通过 `expectedRevision` 切换路由。新事件使用 V2；旧事件重复提交仍指向 V1。再把路由回滚至 V1，新事件使用 V1。
5. 发布指向 Mock 不存在路径的独立模板，提交事件，观察真实 HTTP `404` 后 `failed`。把当前路由切回健康 V1，再对这个失败任务执行人工重投。
6. 重投生成 run 2，**仍访问原来的不存在路径并 404 失败**。断言累计两个 attempt、run 1/2 历史保留、旧模板 ID 不变。同一个重投键和参数再次调用返回当时固定的 `pending / run 2` 响应，即使当前任务已失败；更改原因但复用键返回 `409`。再普通 POST 原失败事件，必须 `200`、同 ID、当前 `failed / run 2`；观察 300 ms 后完整任务与历史均不变，不能重置截止或产生新 attempt。
7. 再提交一个新事件，使用健康 V1 成功；与 Mock 按事件、实际调用及 Body SHA-256 对账，确认重复接入没有额外业务 HTTP。期望 Body 由固定的演示输入/常量独立重建，字段顺序取已发布模板（避免假定 JSONB 保留创建请求的键顺序），编码为紧凑 UTF-8 JSON；实际长度和 SHA-256 必须逐条精确相等，证据同时记录期望与实际摘要。

**第 6 步的失败是预期行为。** 路由修正只影响新的业务事件；人工重投保留旧请求快照，不能借重投偷偷改写原事件。修复目标内容需要新的配置与新业务事件。系统也支持供应商恢复后的成功重投，其自动化证据见 [CP05](reports/CP05/REPORT.md) 和 [CP09](reports/CP09/REPORT.md)；本演示不将预期 404 说成恢复成功。

成功退出码为 `0`，最终对账为 **5 个新任务，4 个成功，1 个预期失败**，失败任务有两个轮次；实际正常业务端点调用 4 次，另外两次真实 404 由 attempt 历史证明。任何断言、网络、鉴权或超时问题均非零退出，不能按“部分 PASS”验收。每个任务等待终态最长约 40 秒，单次 HTTP 有 5 秒超时，读取响应有 1 MiB 上限；脚本不会自动重试写操作。

结果保存在 `.cache/demo/<run-id>/result.json`，包含脚本 SHA-256、配置 ID、路由修订、公开 TaskView/AttemptView、脱敏 Mock 观测和固定错误分类。不写入凭据、Token、Header、业务 Body 或响应正文。脚本在故意坏路由阶段失败时会尝试切回本次健康 V1，并记录恢复结果；不会删除已经写入的配置或任务。

## 手动查看接口

下面的固定 Token 仅对应默认本地演示身份。把示例事件 ID 换成新的值，避免与既有记录冲突。运行脚本后当前 `tenant-01 / notify` 已绑定健康 V1。

```sh
curl --fail-with-body -i http://127.0.0.1:18080/v1/notifications \
  -H 'Authorization: Bearer rc-demo-business-token' \
  -H 'Content-Type: application/json' \
  --data '{"tenantId":"tenant-01","operation":"notify","eventId":"manual-001","data":{"payload":"example"}}'
```

从响应复制 `notificationId`，再执行：

```sh
NOTIFICATION_ID='替换为响应中的 UUID'
curl --fail "http://127.0.0.1:18080/v1/notifications/$NOTIFICATION_ID" \
  -H 'Authorization: Bearer rc-demo-business-token'
curl --fail "http://127.0.0.1:18080/v1/notifications/$NOTIFICATION_ID/attempts?limit=20" \
  -H 'Authorization: Bearer rc-demo-business-token'
curl --fail 'http://127.0.0.1:18080/v1/notifications?tenantId=tenant-01&status=failed&limit=20' \
  -H 'Authorization: Bearer rc-demo-business-token'
curl --fail 'http://127.0.0.1:18080/actuator/metrics' \
  -H 'Authorization: Bearer rc-demo-admin-token'
```

`202` 只证明受理事务已提交；以查询结果和 attempt 历史判断投递。分页结果含 `items` 和 `nextCursor`，后续请求继续带原租户和过滤条件。响应不包含请求 Body、认证 Header 或供应商正文。

## 切换为 API 与两个 Worker

使用同一镜像和数据库；先停止占用 API 端口的 `all`，不删除数据卷：

```sh
docker compose --profile all stop all
docker compose --profile distributed up -d --wait
curl --fail http://127.0.0.1:18080/health/ready
curl --fail http://127.0.0.1:18081/health/ready
curl --fail http://127.0.0.1:18082/health/ready
python3 scripts/demo.py --run-id demo-distributed-001
```

Worker 端口只提供健康与受限指标，不暴露通知接入和配置 API。多实例的 Token、熔断和供应商额度为本地状态，可能各自获取 Token。真实在途强杀、重启、重复投递的复现与证据见 [故障说明](../examples/faults/README.md)、[CP10 恢复报告](reports/CP10/REPORT.md)；不能把普通 `restart` 或空闲时停止当成强杀恢复证明。

演示结束可执行 `docker compose --profile all --profile distributed stop` 保留容器和卷，或 `docker compose --profile all --profile distributed down` 删除容器/网络但保留数据卷。测试服务如果也要停止，使用相同项目名执行 `docker compose --profile test stop test-postgres`。重用有数据的卷会保留事件幂等记录、配置版本和审计；修改 `.env` 不会自动重置数据库角色密码。
