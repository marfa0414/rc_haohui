# 部署侧安全配置

[application-example.yml](application-example.yml) 对应仓库的租户 A 供应商配置示例。域名是占位值；替换为经过批准的供应商业务域名和 Token 域名，内部调用凭证及供应商 Secret 均由环境或部署系统提供。可通过 Spring Boot 的 `spring.config.additional-location` 加载这份部署配置，不把真实值提交到仓库。

`app.secrets.tenant-references` 只保存授权关系和逻辑引用，不保存 Secret。默认空表示拒绝；共享同一个引用必须在每个获准租户下明确列出。配置管理员不能通过管理 API 扩大这个部署白名单。`env:EXAMPLE_CRM_CLIENT_SECRET` 对应外部提供的 `EXAMPLE_CRM_CLIENT_SECRET` 及 `EXAMPLE_CRM_CLIENT_SECRET_VERSION`；其他 API Key 同样需要配套 `_VERSION`。同一账号轮换时更新值与版本并重启，引用授权不变；换账号则发布新认证配置。

生产环境只接受允许列表中的 HTTPS 目标及端口，实际 DNS 地址还必须通过公网地址检查。允许列表使用精确名称；URL 使用 ASCII 域名或 punycode，不接受通配符、尾点、userinfo 或混淆 IP 写法。环境为 `production` 时，任何开发 HTTP/CIDR 例外都会导致启动失败。

本地 Mock 仅在显式 `development` 或 `test` 环境下配置 `development-http-hosts`、`development-http-ports` 和 `development-allowed-cidrs`。例如只批准 `127.0.0.1/32`，不要为方便而扩大为整个内网。测试 HTTPS 的名称/端口仍使用常规 HTTPS 允许列表，CIDR 仅允许建立连接，证书链、有效期、原主机名与 SNI 校验照常执行。

本服务对固定的特殊地址范围采取保守拒绝策略，包括私网、回环、链路本地、云元数据等；应用无法识别部署环境自行将公网地址路由到私有服务的所有情况。生产部署应同时配置出口网络边界。日志、预览、查询和审计不返回认证材料；数据库任务保留必要的业务请求快照，需按内部敏感数据管理权限与备份。
