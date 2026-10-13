# 两个供应商的配置样例

CRM 使用嵌套 JSON，广告供应商使用 URL 编码表单；两者共用同一套 Java 实现。这里的 `example.com` 地址仅演示配置结构，测试/演示时应替换为受控 Mock 地址。配置文件只保存密钥环境变量引用。

| 供应商 | 模板 | 认证 | 上游事件 |
| --- | --- | --- | --- |
| CRM | [crm-template.json](crm-template.json) | [crm-auth.json](crm-auth.json) | [crm-event.json](crm-event.json) |
| 广告 | [ads-template.json](ads-template.json) | [ads-auth.json](ads-auth.json) | [ads-event.json](ads-event.json) |

使用配置管理员身份完成以下流程。业务调用者需另有 `tenant-a` 和目标操作的提交权限。`ADMIN_TOKEN` 是本服务内部凭证，不是供应商密钥。

实际发送时，Worker 环境还需提供 `EXAMPLE_CRM_API_KEY` 与 `EXAMPLE_CRM_API_KEY_VERSION`、`EXAMPLE_ADS_API_KEY` 与 `EXAMPLE_ADS_API_KEY_VERSION`。版本号使用非敏感标识，例如 `v1`；轮换密钥时同步更换版本并重启 Worker。创建、预览、发布配置时无需读取这些实际值。

```sh
export API_URL=http://127.0.0.1:8080
export ADMIN_TOKEN='<本服务的配置管理员凭证>'
curl --fail-with-body -sS "$API_URL/admin/template-versions" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data-binary @examples/configuration/crm-template.json
curl --fail-with-body -sS "$API_URL/admin/auth-profiles" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  --data-binary @examples/configuration/crm-auth.json
```

记录两次返回的 `id` 和 `revision`，分别对以下路径发送 `POST {"expectedRevision":1}`（若已编辑则使用当前修订号）：

- `/admin/template-versions/{templateId}/publish`
- `/admin/auth-profiles/{authProfileId}/publish`

发布前可调用 `POST /admin/template-versions/{templateId}/validate`，或用 `{"eventId":"preview-001","data":{"contactId":"contact-123","amount":29.99}}` 调用 `POST /admin/template-versions/{templateId}/preview`。预览只离线生成脱敏结果，不解析密钥、不请求 Token、不连接供应商。

首次绑定对 `/admin/routes/tenant-a/subscription.paid` 发送 `PUT`：

```json
{
  "expectedRevision": 0,
  "templateVersionId": "<模板返回的 UUID>",
  "authProfileId": "<认证返回的 UUID>"
}
```

广告供应商重复上述流程，改用 `ads-*.json` 文件，并绑定 `/admin/routes/tenant-a/user.registered`。上游随后对 `/v1/notifications` 提交对应 `*-event.json`。

生产地址需列入 `app.outbound.allowed-hosts`；本地 HTTP Mock 还需 `app.environment=development` 和明确的 `development-http-hosts`、`development-http-ports`。不得将示例放行配置视作生产默认策略。

配置变更时创建 `version:2`，校验、预览并发布，再携带路由当前 `revision` 切换绑定。回滚同样是切换到旧版 UUID。旧任务继续使用受理时保存的请求字节；同一个业务事件重复提交不受新模板影响。新增编码格式、签名算法或任意业务计算仍需开发代码策略。

## OAuth 客户端凭证

[crm-oauth-basic-auth.json](crm-oauth-basic-auth.json) 演示 `client_secret_basic`，[crm-oauth-post-auth.json](crm-oauth-post-auth.json) 演示供应商要求的 `client_secret_post`。选与供应商契约一致的一种，按上面的认证创建、发布和绑定流程替换 `authProfileId`，CRM 的 Body 模板无需改 Java 代码。

Worker 环境提供 `EXAMPLE_CRM_CLIENT_SECRET` 和 `EXAMPLE_CRM_CLIENT_SECRET_VERSION`。内部调用凭证、供应商 Client Secret、供应商 Access Token 是三种不同材料；上游提交业务事件时无需携带后两者。Token URL 与业务 URL 都必须满足出站地址规则。

Post 样例中的 `tokenLifetimeSeconds:3600` 只演示供应商明确约定寿命时的配置写法，不代表可猜测任何供应商的有效期。正常使用 Token 响应中的合法 `expires_in`；只有供应商确认省略该字段并明确寿命时才配置回退值。Basic 样例故意未配置回退值。

同一账户轮换 Secret 时同时更新环境中的值和版本，再重启 Worker；历史任务仍引用原认证配置，但会读取新的凭据版本。Client ID、Token URL、Scope 或账户身份变化应发布新的认证配置并切换路由。缓存为进程本地状态，多 Worker 可分别获取 Token；若供应商不允许 Token 共存或严格限制申请量，需要另行设计共享缓存与刷新协调。
