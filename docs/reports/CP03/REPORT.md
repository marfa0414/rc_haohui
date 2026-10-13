# CP03 验证记录

状态：通过。执行日期：2026-10-13（Asia/Shanghai）。本记录覆盖配置管理、有限映射、静态认证准备和此前回归；实际 Worker 投递从 CP04 开始，本报告不代表全部 95 个验收用例完成。

## 环境与命令

环境沿用 [CP01](../CP01/REPORT.md)：JDK 17.0.20、Maven Wrapper 3.9.16、Spring Boot 3.5.16、真实 PostgreSQL 17.11，每个数据库测试使用独立随机 schema。

```sh
MAVEN_USER_HOME="$PWD/.cache/maven-user" ./mvnw \
  -Dmaven.repo.local="$PWD/.cache/m2" \
  -Dtest.database.url=jdbc:postgresql://127.0.0.1:55432/rc_test \
  -Dtest.database.user=rc_test -Dtest.database.password=rc_test_local_only \
  --batch-mode --no-transfer-progress clean verify \
  --log-file .cache/logs/CP03-verify-3.log
python3 scripts/verify_test_reports.py target/surefire-reports
python3 scripts/archive_test_evidence.py CP03 --scope 'Cumulative CP01-CP03'
```

最终构建约 11:56:18 开始，11:56:53 +08:00 完成，耗时 34.922 秒，返回码 0。门禁与归档工具返回码 0。14 个套件累计 **179/179 通过，失败 0、错误 0、跳过 0**。

| 新增套件 | 执行 |
| --- | ---: |
| ConfigurationLifecycleTest | 7 |
| ConfigurationVersionTest | 3 |
| ConfigurationAccessTest | 4 |
| ConfigurationValidationTest | 39 |
| MappingIntegrationTest | 12 |
| ApiKeyAuthStrategyTest | 13 |
| CP01–CP02 回归 | 101 |

逐方法结果与原 XML 哈希见 [results.json](results.json)，实际验证的源码见 [source-sha256.txt](source-sha256.txt)。原始 XML 中的环境属性、完整日志不复制到交付报告。

## 实际覆盖

- 真实管理 HTTP API 创建、更新、校验、预览、发布、绑定、回滚；没有替换生产 Repository 或映射器。
- 乐观修订号、过时修订发布、并发绑定、模板键跨租户首次竞争；20 次路由切换与 50 次接入并发时，每个快照来自完整同一组版本。
- API 与 PG 共同保护已发布配置、任务快照和审计。直接修改/删除的断言检查 SQLState `23514`，防止仅因外键约束失败而误判不可变触发器有效。
- JSON 嵌套/类型、对象数组直传、表单特殊字符、缺失/null/false/0、字段路径、Header 和 Body 字节上限、控制字符以及大小写头冲突。
- 预览对真实可观测端点零连接，不读取 Secret，不获取 Token；输出值脱敏。
- [两个供应商的实际样例](../../../examples/configuration/README.md) 由测试直接加载，通过真实管理流程受理，不新增供应商 Java 类。
- 旧事件在新模板增加必填字段后仍返回旧任务，切换与回滚均不改写已有请求字节。
- 管理资源的租户权限、稳定分页、最长租户/操作审计标识、静态 API Key 准备及密钥版本引用。
- `1e10000` 常量经历真实 JSONB 保存、预览及任务受理；配置指数展开超限与 NUL 被拒绝，失败事务无记录或审计残留；合法业务 JSON 的转义 NUL 仍支持。

## 发现与修正

首轮 166 次执行中 2 项失败：样例测试用 revision=0 覆盖了夹具已有路由，以及越权路由详情返回 403 而不是资源查询契约中的 404。前者改为真实查询现有 revision 后绑定，后者修复生产权限响应。第二轮 166/166 通过。

审查另外修复了完整草稿 PUT 中 JSON 数字节点类型导致的错误冲突、API Key 传输控制头缺项、最长路由审计字段长度、跨租户模板命名空间竞争及 JSONB 数字展开读取边界。测试增加控制字符、展开大小和不可变触发器的独立断言后，第三轮累计 179/179 通过。

管理 API 引用不存在的配置采用 404，草稿或非法组合采用 400，跨租户凭据绑定采用 403；该细化已记录到技术方案，业务接入配置不可用的 422 保持不变。没有通过删除失败测试或条件跳过来取得通过。

## 限制

AT02 当前证明两种配置正确受理并保存快照，真实出站字节与静态认证 Header 留 CP04。运行时 DNS/IP/TLS 完整防护留 CP08。命名空间测试为真实双请求并发，并未强制两个请求同时越过“归属查询”窗口；源码中的事务锁同时经过审查。GitHub Actions 尚未远程执行。
