# Skill 对话触发记录

插件 1.2.0 / 日志 schema 1.4.0 新增链路：

发布操作与线上对账步骤见日志插件仓库的 `docs/skill-release-checklist.md`，部署前可运行 `tools/check-skill-deployment.py`。

```text
Qoder / Qoder IDE / QoderWork
  → SKILL_TRIGGERED（本地 requests_*.jsonl）
  → /api/logs 或 /api/logs/batch
  → 审计 spool + 独立 .skill-outbox
  → skill-center POST /api/internal/v1/skill-invocations/batch
  → PostgreSQL skill_invocation_event
```

## 统计口径与兼容

- 记录每次技能触发，不代表整个任务成功；同一调用的多个通道和重传使用相同 `event_id`。
- 自动触发：`Skill` 工具的 `input.skill`。支持 hook 的对象/JSON 字符串入参和 transcript 的 `assistant.message.content[].tool_use`。
- 手动触发：transcript 的 `session_meta`，仅接受 `data.meta_type=slash_command` 且 `data.content.type=skill`，名称取 `data.content.name`。普通命令、插件安装指令、技能推荐、权限询问、文件读取不计数。
- IDE 自动/手动、Work 自动的结构已在本机真实 transcript 中观察到；已据此编写脱敏场景测试。独立 Qoder 桌面、Work 手动选择以及各端子任务需继续通过实际客户端操作验收，不能把合成测试等同于三端人工验收。
- 产品单独写入 `client_product`：`qoder` / `qoder_ide` / `qoderwork` / `unknown`；原始产品和版本保留。`.qoder/` 单独不足以区分 IDE 与桌面；已有 `origin_product` 和 OSS 分区保持兼容。
- `occurred_at` 为源时间；不可得则使用采集时间，并写 `time_source=observed`。后到的源时间/更明确的 transcript 可以补充同一事件，不新增次数。`observed_at` 和中心的 `received_at` 分别表示首次采集和首次入库时间。
- 每台安装的随机标识保存在日志状态目录 `skill-client-id`；事件 ID 由安装标识、标准化邮箱、会话和稳定调用引用生成 SHA-256。不要复制该身份文件到其他机器。无调用 ID 时用 transcript UUID/块位置，或文件位置与记录摘要；无 ID 的 hook 不独立计数，并记录覆盖诊断。
- 初次接触一个 transcript 时建立当前位置边界，不回灌旧内容；升级后启动新会话。已有旧版读取 offset 会继续使用。
- Node 模式提供完整采集；jq 降级只保留原有日志并输出覆盖不足诊断。没有可用邮箱时不生成中心事件。
- 终止事件只在还有未上传 Skill 时绕过上传间隔，仍遵守退避和硬超时。保留日文件全部参与补传，不再限于最近三天。无常驻计时器，因此退出、休眠或离线时不保证十分钟内送达。

## 中心接口

### 写入

`POST /api/internal/v1/skill-invocations/batch`，`Content-Type: application/json`，请求体为 1–500 个事件的数组，最大 2 MiB。必须提供独立的 `X-Skill-Usage-Key`，普通会话和 API Token 不替代该凭据。

```json
[
  {
    "source": "qoder-request-logger",
    "event_id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "email": "employee@example.com",
    "name": "示例用户",
    "session_id": "session-123",
    "tool_call_id": "call-456",
    "skill_name": "my-plugin:report",
    "skill_plugin": "my-plugin",
    "client_product": "qoder_ide",
    "trigger_mode": "automatic",
    "evidence": "transcript_tool_use",
    "time_source": "source",
    "occurred_at": "2026-09-14T01:02:03Z",
    "observed_at": "2026-09-14T01:02:04Z"
  }
]
```

其他可选字段：`uid`（外部用户 ID）、`prompt_id`、`agent_id`、`skill_coordinate`（明确的 `@namespace/slug`）、`skill_version`、`product`、`product_version`。不提供时不猜测。`plugin:skill` 不是中心命名空间坐标。

元数据字符串最多 256 字符，`skill_name` 最多 512；日期要求 ISO-8601 时区，范围为 2000 年以后至服务器当前时间加一天。邮箱去空白并忽略大小写匹配；多个账号同邮箱时不任意选择。用户与技能无法关联时保留原始信息，中心 ID 为空。

正常返回标准 ApiResponse，`data.results` 与输入顺序一一对应：

```json
{"code":0,"data":{"results":[{"event_id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"accepted","error":null}]}}
```

`status` 为 `accepted` / `duplicate` / `rejected`。无效单条返回 `INVALID_EVENT`；同事件 ID 的邮箱、会话或技能不同返回 `EVENT_ID_CONFLICT`。数据库故障返回非 2xx，已提交的前序条目可安全重传。只有数据库提交后才返回 accepted。未知输入字段会被丢弃，不存储原始请求体。

### 查询

`GET /api/v1/admin/skill-invocations`，沿用中心会话鉴权，仅 `SUPER_ADMIN` / `AUDITOR`。

参数：`email`、`userId`、`skillName`、`skillId`、`product`、`sessionId`、`from`（包含）、`to`（不包含）、`page`（从 0 开始）、`size`（默认 50，范围 1–200）。名称精确匹配；按 `occurred_at DESC, id DESC` 排序。

响应 `data` 为 `{items,total,page,size}`，每项含 `id`、`centerUserId`、`centerSkillId`、`receivedAt` 和 `event`。只提供接口，不修改下载次数、下载排行或管理页面。

## 部署顺序与配置

1. 先部署 skill-center，执行 Flyway `V49__skill_invocations.sql`。默认不删除使用记录。配置 `SKILLHUB_SKILL_INVOCATIONS_KEY_SHA256` 为独立服务密钥的 SHA-256 十六进制哈希；留空时写入入口关闭（401）。密钥与已有插件上传 Key 必须分开。
2. 再部署日志服务，配置下表。未开启转发时仍把新事件保存到 outbox；开启后补送。队列应位于持久化卷，默认在 spool 下，受既有磁盘背压保护。自定义目录必须放在同一受监测文件系统。
3. 最后分发插件 v1.2.0。交付 ZIP 沿用 v1.1.10 的日志上传地址 `https://qoder-log.sigmob.com/api/logs` 和原有插件上传 Key。源码模板仍保持空地址和空 Key；自行构建时使用现有 `gen-hooks.py` 注入分发配置。不要把中心服务密钥放进插件。

| 日志服务环境变量 | 默认/含义 |
| --- | --- |
| `SKILL_CENTER_ENABLED` | `false`，仅控制转发，不控制排队 |
| `SKILL_CENTER_URL` | 默认 `https://skill-center.sigmob.com`，自动追加内部接口路径 |
| `SKILL_CENTER_API_KEY` | 独立服务密钥明文，只部署于日志服务 |
| `SKILL_CENTER_OUTBOX_DIR` | `${audit.spool-dir}/.skill-outbox` |

队列每 15 秒尝试一次；每批最多 500 条且约 1.5 MB。连接和请求超时均为 5 秒，不跟随重定向。超时、429、5xx 等退避为 15 秒起指数增长、最长一小时；Retry-After 可以延长等待。401/403 持久化暂停，轮换密钥后重启可恢复；如权限已修复但密钥未变，停止日志服务后把队列 `state.json` 的 `paused` 改为 false 再启动。

永久无效条目及 HTTP 400/413/422 批次保留到 `dead/`，不会删除原始记录。修正原因后可重放：

```sh
python3 tools/replay-skill-outbox.py /path/to/spool/.skill-outbox
python3 tools/replay-skill-outbox.py /path/to/spool/.skill-outbox --execute
```

观察指标：`skill_forward_pending`、`skill_forward_paused`、`skill_forward_dead`、`skill_forward_records{status}`、`skill_forward_failures{reason}`。通过查询中的中心 ID 空值检查未关联情况。原始事件继续进入 OSS，中心接收的仅是白名单元数据，避免携带提示词和工具参数。

## 验证

```sh
node tools/verify-skill-events.js
sh tools/verify-collector.sh
mvn -f server/pom.xml test
```

skill-center 执行 `make test-backend-app`。可选真实 PostgreSQL/跨服务测试需要在隔离测试库运行：

```sh
export SKILL_INVOCATION_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55439/postgres?user=invocation_test'
export QODER_LOGGER_REPO=/absolute/path/to/qoder-request-logger
# 先构建日志服务 jar，随后在 skill-center/server 执行：
./mvnw -pl skillhub-app -am -Dtest='SkillInvocation*' -Dsurefire.failIfNoSpecifiedTests=false test
```

测试在指定测试库创建并清理独立 schema，不应指向生产库。跨服务测试覆盖插件→日志服务→中心 HTTP→PostgreSQL，包含提交后响应失败、重启和补送。三端客户端真实操作仍需独立验收。
