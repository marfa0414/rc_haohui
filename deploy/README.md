# 本地容器部署

这是隔离的演示环境，所有默认凭据都是公开假值。单机 Compose 不提供生产高可用保证。先执行仓库测试门禁并生成 `target/notification-service-0.1.0-SNAPSHOT.jar`，再构建镜像；Dockerfile 仅封装已经验证的 jar，不代替测试。

应用部署配置与 PostgreSQL 初始化脚本复制进各自镜像，不依赖宿主目录 bind mount。PostgreSQL 派生镜像只增加角色初始化脚本，保留固定摘要的官方基础镜像及其启动行为。更改这两份部署文件后须重新构建；环境变量覆盖仍在容器启动时生效，数据库里的供应商 Header/Body 模板仍通过配置发布更新，无需重建镜像。

```sh
cp .env.example .env
docker compose --profile all build
docker compose --profile all up -d
curl --fail http://127.0.0.1:18080/health/ready
```

`all` profile 使用一个同时承担 API 和 Worker 的进程。另一个 profile 使用独立 API 和两个 Worker；切换时先停止旧应用，避免同时绑定 API 端口：

```sh
docker compose --profile all stop all
docker compose --profile distributed up -d
curl --fail http://127.0.0.1:18081/health/ready
curl --fail http://127.0.0.1:18082/health/ready
```

API 在 `127.0.0.1:18080`，Worker 健康端口在 `18081/18082`，Mock 在 `19090`，演示 PostgreSQL 在 `55433`。业务地址为容器内 `http://mock:9090/business/{provider}`，Token 地址为 `http://mock:9090/oauth/token`。业务身份 `rc-demo-business-token` 仅可为 `tenant-01` 至 `tenant-10` 提交 `notify` 操作；管理身份 `rc-demo-admin-token` 用于配置与指标。参考 [性能脚本](../scripts/perf/README.md) 安装配置和执行实测。

迁移服务使用 `notification_migrator`，运行同一个 jar 的 `--migrate-only` 入口，成功后退出。API/Worker 只收到 `notification_app` 的密码，默认启动模式 `validate` 检查已完成的 Flyway 迁移与 schema 版本。应用账号不拥有对象，不具有 DDL、DELETE、TRUNCATE 权限；审计仅允许 INSERT/SELECT，Flyway 历史仅允许 SELECT。迁移账号具有独立 schema DDL 权限。初始化角色脚本只在全新 PostgreSQL 数据卷执行。

`notification-data` 保存任务、历史、幂等和配置；普通停止／重启保留数据。不要在需要保留记录时执行 `down -v`。更改 `.env` 中已有数据库角色的密码不会自动修改数据库；已有环境的轮换须由部署管理员实际执行。重复执行迁移不会重跑已完成版本：

```sh
docker compose run --rm migrate
```

开发出站例外只批准 `mock:9090` 与固定 `172.30.86.10/32`；其余私网地址仍拒绝。网络使用 `172.30.86.0/24`，若本机冲突，须同时修改网络、Mock 固定地址和精确 `/32` 配置，再重建环境。设置生产环境时必须移除开发例外，不能将演示配置直接作为生产出口策略。

每个应用容器限制 768 MiB、2 CPU、256 PID；Java 堆上限 384 MiB、直接内存 64 MiB、Metaspace 192 MiB，数据库连接池 10，业务并发 10／供应商 3。多 Worker 的进程内配额与池上限会叠加。容器等待 45 秒停机，应用优雅关闭上限 30 秒；超时或强杀依靠数据库租约恢复，允许重复投递。

基线默认扫描 100 ms，Token 最多 5 秒，业务最多 10 秒，attempt 最多 30 秒，租约 90 秒。固定突发场景用专门的环境覆盖，不修改基线规则：

```sh
WORKER_BUSINESS_TIMEOUT=2s WORKER_ATTEMPT_TIMEOUT=10s WORKER_LEASE_DURATION=15s \
RETRY_BASE_DELAY=100ms RETRY_MAX_DELAY=1s PROTECTION_OPEN_DURATION=1s \
DELIVERY_RUN_DURATION=120s DELIVERY_MAX_ATTEMPTS=12 \
docker compose --profile all up -d --force-recreate all
```

切回基线时移除命令行覆盖后重建 `all`。压力场景可覆盖 `ADMISSION_MAX_BACKLOG`、`ADMISSION_REQUESTS_PER_SECOND` 和 `ADMISSION_BURST_CAPACITY`；报告应保留实际取值和拒绝数量。

`test` profile 的 `test-postgres` 是独立的测试数据库，使用另一个数据卷和默认 `55432` 端口；其测试角色允许创建隔离 schema，不与演示应用共用。若端口已被使用，设置 `TEST_DATABASE_PORT` 并同步测试连接地址。此 profile 不需要启动演示应用：

```sh
docker compose --profile test up -d test-postgres
```

镜像均固定摘要：Java 来源 Temurin 17 JDK Jammy，Python 基础镜像实际版本由环境证据记录，PostgreSQL 17.11。具体镜像版本、实测命令、吞吐与资源曲线见 CP10 报告；未执行结果不视为达标。
