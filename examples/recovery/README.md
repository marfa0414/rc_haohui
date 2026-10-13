# 查询与人工重投

此目录说明 CP05 的恢复接口。完整运行步骤和实际验证结果以根目录 README 与 checkpoint 报告为准。先使用已配置的内部身份获取失败任务 ID；下面变量中的凭证应从本机环境传入，不写进仓库。

```sh
export SERVICE_URL=http://127.0.0.1:8080
export NOTIFICATION_ID='<实际失败任务 UUID>'
export OPERATOR_TOKEN='<已配置且有该租户/操作权限的内部凭证>'

curl --fail-with-body \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  "$SERVICE_URL/v1/notifications/$NOTIFICATION_ID"

curl --fail-with-body \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  "$SERVICE_URL/v1/notifications/$NOTIFICATION_ID/attempts?limit=20"

curl --fail-with-body \
  -H "Authorization: Bearer $OPERATOR_TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/recovery/replay.json \
  "$SERVICE_URL/admin/notifications/$NOTIFICATION_ID/replay"
```

执行前将 `expectedRunNumber` 改为查询所得的失败轮次，并为本次运维操作指定 `replayRequestId` 和原因。若响应丢失，使用完全相同的文件重试；不要生成新键。相同键和参数返回已保存的操作结果，即使任务后来已经成功。相同键改变原因或预期轮次返回 409。

仅 `failed` 任务可重投。重投增加轮次、更新本轮预算，保留任务 ID、事件标识、原请求字节、累计次数和历史。它不会采用后来发布的新模板，也不保证下游业务副作用不重复；需要供应商按稳定事件标识去重。

尝试历史按轮次和尝试号倒序分页。后续页把响应中的 `nextCursor` 作为 `cursor` 参数（使用 URL 编码），权限仍按本次请求校验。查询不返回 Body、认证 Header、Secret 或 Token。

`deferReason` 表示调度或终止原因，`lastOutcome`、`lastHttpStatus` 表示最近已知的投递结果。因截止时间终止或人工重投，不会把原来的 UNKNOWN 擦除成“未发送”。
