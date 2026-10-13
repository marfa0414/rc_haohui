# CP01 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。此记录只覆盖构建、配置表迁移、角色/健康检查与受控 HTTP 基础，不代表全部 95 个验收用例通过。

## 实际环境

- macOS 26.3.1 / ARM64，Homebrew OpenJDK 17.0.20。
- Maven 3.9.16；仓库 Wrapper 3.3.4，分发包 SHA-256 已固定并实际验证启动。
- Spring Boot 3.5.16；实际依赖版本见本目录的依赖记录。
- Docker Desktop 4.88.1，Engine 29.7.2。
- 真实 PostgreSQL 17.11，项目独立容器 `rc-haohui-test-postgres`，仅本机 `55432` 端口；独立测试卷，每项数据库测试使用随机 schema。
- 镜像：`postgres:17.11@sha256:2d2b8998d31037bf721cfdf764d76ba74171b4fab3431b7f72c27c56ddbdf9e3`。

## 命令与结果

实际执行使用下载并解压的 Maven 3.9.16，参数如下。所有凭据均为独立本地测试假值。

```sh
/private/tmp/apache-maven-3.9.16/bin/mvn \
  -Dmaven.repo.local=/Users/Admin/Desktop/rc_haohui/.cache/m2 \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test \
  -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify
python3 scripts/verify_test_reports.py target/surefire-reports
```

构建返回码 0，2026-10-13 11:17:20 +08:00 完成，Maven 总耗时 2 分 1 秒（包含首次依赖下载）。报告门禁返回码 0。

| 测试套件 | 实际执行 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ApplicationFoundationTest | 8 | 0 | 0 | 0 |
| PostgresMigrationTest | 3 | 0 | 0 | 0 |
| ControlledHttpClientTest | 17 | 0 | 0 | 0 |
| 合计 | 28 | 0 | 0 | 0 |

逐方法结果及原始 XML 摘要见 [results.json](results.json)。测试 agent 独立核对了实际 testcase 节点与汇总计数一致，无禁用或条件跳过。独立代码审查未发现 CP01 范围内的明确阻塞问题；静态审查不代替测试。

## 覆盖与限制

- 真实 PG 空库、重复和并发迁移、校验和不一致：TC-OPS-01 的基础部分。
- API/Worker/All 角色、schema 不匹配与健康检查：TC-OBS-03、TC-OPS-01 的基础部分。
- 真实 HTTP 字节、受控 DNS、跳转/透明重试/认证挑战禁止、取消、不可中断 DNS 资源有界：CP01 传输可行性门禁。
- HTTP、TLS、完整 SSRF、业务权限、Worker、OAuth 及双进程业务恢复仍按后续 checkpoint 实现并验收。
- GitHub Actions 配置已编写，本记录不声称已在 GitHub 执行 CI。

源文件校验清单见 [source-sha256.txt](source-sha256.txt)。该清单固定本次实际验证的代码版本，后续 checkpoint 变更不改写本记录。
