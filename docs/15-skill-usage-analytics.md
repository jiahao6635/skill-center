# Skill 使用次数与操作审计设计

**状态**：可实施设计（评审修订稿）  
**范围**：SkillHub 平台上**每个 Skill 包**的使用与操作统计（下载 / 搜索 / 打开详情 / 上传 / 修改），以及「谁做了这些事」。  
**非范围**：把本仓库当成外部 agent 的「一个 skill」去统计被调用次数。

---

## 1. Context / Problem

产品需要回答三类问题：

1. **这个 Skill 被用了多少次？** 列表/详情已有 `downloadCount`，但只有裸计数，无法解释「谁、何时、从哪个客户端」。
2. **谁在用？** 作者要看到近期下载者；管理员要按用户 / 客户端 / 时间排查。
3. **发生过哪些操作？** 下载、搜索、打开详情、上传、改版本。治理动作（yank / hide / archive / 审核）已有 `audit_log`，v1 **不**做作者侧治理时间线联合查询。

当前实现把「热路径计数」和「安全审计」拆开了，中间没有「使用明细」层：

- `skill.download_count` / `skill_version_stats.download_count` 是同步 `+1`，**不记人**。
- `audit_log` 记治理与安全相关写操作，**不记下载/搜索/打开详情**，且 `actor_user_id` 外键指向 `user_account`，**无法记匿名用户**。
- `SkillDownloadedEvent(skillId, versionId)` 已发出，但**没有 listener**，也没有 actor / client。`SkillDownloadService` **没有** `@Transactional`；`incrementDownloadCount` 各自短事务提交后再 `publishEvent`。因此 **禁止** 用 `@TransactionalEventListener(AFTER_COMMIT)` 记 **DOWNLOAD**（无活跃事务时事件会被丢掉）。UPLOAD / PUBLISH 则相反：它们在 `@Transactional` 发布方法内 `publishEvent`，必须等外层事务提交后再插 `skill_usage_event`（见 §5.7）。
- 搜索入口不止 Web / compat：CLI 是 `CliSkillController` → `searchInstallableLatest`；compat registry 另有 `ClawHubRegistryFacade.search`。均为纯读，零打点。
- 详情 `GET /{namespace}/{slug}` 是纯读；`ClawHubRegistryFacade.getSkill` 也调 `getSkillDetail`，**不得**当成 portal VIEW。
- 上传走 `SkillPublishService`，**不写** `audit_log`（仅 ClawHub compat 写 `COMPAT_PUBLISH`）。
- 匿名下载限流已有 `AnonymousDownloadIdentityService` + first-party cookie `skillhub_anon_dl`（`SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET`）。用量匿名身份必须复用它，禁止再发明第二套 cookie / secret。

如果把搜索/下载明细塞进 `audit_log`，会污染管理员审计台、撑爆带 FK 的表、并在搜索热路径上同步写库。

---

## 2. 假设（请按此实现；歧义见 Open Questions）

1. 统计对象是 **SkillHub 里的 Skill 包**（`skill.id`），不是本仓库作为外部 skill 的调用次数。
2. **「使用次数」（公开指标）= 已发布版本的成功下载/安装次数**。与现网 `downloadCount` 语义对齐，继续用于搜索 popularity 排序。
3. **搜索不计入「使用次数」**。搜索是 query-level 发现行为；打开详情是 skill-level 兴趣；下载才是使用。
4. **上传 / 修改** 记入使用明细。治理动作（hide / yank / archive / 审核）**只走 `audit_log`**。v1 **不做**作者侧 usage ∪ audit 联合时间线（yank 的 `target_id` 是 `versionId`，无法廉价解析到 skill）。作者用量页只展示 usage 动作。
5. Web、CLI（`/api/cli/v1`，含 `GET /api/cli/v1/skills/search`）、ClawHub compat 搜索/下载、API Token、匿名访问 **全部打点**。compat `GET /api/v1/download` 的 302 **本身不计** DOWNLOAD。
6. 未登录访问 **记录**。匿名 `actor_key` 优先复用现有下载 cookie 的 `cookieHash`；无 cookie（CLI / compat）才 HMAC(IP+UA)。作者侧只看到「匿名用户」，**看不到 IP / UA**。
7. 短时间重复下载/刷新：**明细与独立用户数去重**；公开 `downloadCount` **不去重**（保持现网每次成功下载 +1，含 deep-link 每 token 一次）。
8. **独立用户数是 lifetime**（`skill_usage_actor` 首次出现表），不随 180 天事件删除而回退。事件删除默认关闭。
9. Web 发布 **不**补 `audit_log`（OQ1 关闭：won't-do）。不新增 `sh_aid` cookie（OQ2 关闭）。

---

## 3. Goals and Non-goals

### Goals

- 为每个 Skill 提供可查询的：**下载次数（公开）**、**独立下载者（lifetime）**、**详情打开次数**、**独立浏览者（lifetime）**、**上传/改版本次数**。管理员另可查 query-level 搜索明细。
- 记录操作者：`userId`、展示名（读时 join）、客户端（`WEB` / `CLI` / `COMPAT` / `API_TOKEN`）。
- 管理员可按 skill / 用户 / 动作 / 时间分页查全量明细。
- 作者 / 命名空间 OWNER·ADMIN / `SKILL_ADMIN` / `SUPER_ADMIN` 可看有权管理的 Skill 的汇总与「谁用过」（无 IP/UA）。`AUDITOR` **只走** `/admin/usage`，不走作者 API。
- 搜索与列表热路径 **不在请求线程同步写 PostgreSQL**。
- 明细 append-only；日聚合与 lifetime unique **可从各自权威表重建**（unique 的权威是 `skill_usage_actor`，不是事件）。公开下载计数保持现有单写者。
- ClawHub / CLI 与 Web 同一套语义。

### Non-goals

- 不做实时用户行为分析产品（漏斗、会话回放、热力图）。
- 不把搜索结果里每一个 hit 都记成 skill 级「使用」。
- 不在本设计替换 `audit_log`，也不把 `security_audit` 与使用统计合并。
- 不引入 Kafka / ClickHouse。
- 不统计安装后在用户本机 / agent 内的执行次数。
- 不改搜索相关性算法。
- v1 不做作者治理时间线、不做 outbox 表、不把 `DEVICE_FLOW` 从 API token 中拆出来。

---

## 4. Current state（仓库现状）

### 4.1 `audit_log`：安全/治理审计，不是用量账本

表（`V1__init_schema.sql`，时间戳后续迁到 timestamptz）：

| 列 | 类型 | 说明 |
|---|---|---|
| `id` | BIGSERIAL | PK |
| `actor_user_id` | VARCHAR(128) **FK → user_account** | 匿名无法插入 |
| `action` | VARCHAR(64) | 如 `HIDE_SKILL`、`COMPAT_PUBLISH` |
| `target_type` / `target_id` | VARCHAR / BIGINT | 无 `skill_id` 专用列 |
| `request_id` / `client_ip` / `user_agent` | | 请求上下文 |
| `detail_json` | JSONB | |
| `created_at` | timestamptz | |

写入点（均为**低频写操作**）：

| Action | 写入位置 |
|---|---|
| `HIDE_SKILL` / `UNHIDE_SKILL` / `ARCHIVE_SKILL` / `UNARCHIVE_SKILL` / `YANK_SKILL_VERSION` / `DELETE_SKILL_VERSION` | `SkillGovernanceService` |
| `REVIEW_APPROVE` / `REVIEW_REJECT` / `REVIEW_SUBMIT` | `ReviewPortalAppService` |
| `REVIEW_WITHDRAW` / `SUBMIT_REVIEW` / `CONFIRM_PUBLISH` / `RERELEASE_SKILL_VERSION` | `SkillLifecycleAppService` |
| `PROMOTION_*` | `PromotionPortalAppService` |
| `REPORT_SKILL` / `RESOLVE_*` / `DISMISS_*` | `SkillReportService` |
| `COMPAT_PUBLISH` | `ClawHubCompatAppService` |
| `REBUILD_SEARCH_INDEX` | `AdminSearchController` |
| 标签 / 命名空间治理 / 资料审核 | 各对应服务 |

查询：`GET /api/v1/admin/audit-logs`，`AUDITOR` + `SUPER_ADMIN`。前端 `web/src/pages/admin/audit-log.tsx`。治理工作台 `GovernanceWorkbenchAppService.listActivity` 复用同一套 action 过滤（含 `REVIEW_*`、`PROMOTION_*`、`REPORT_*`、`HIDE/UNHIDE/ARCHIVE/UNARCHIVE`；**不含** `YANK_SKILL_VERSION`）。

**结论**：`audit_log` 适合「谁改了系统状态」，不适合「谁下载/搜了/打开了」。

### 4.2 下载计数：有次数、无人

- `skill.download_count BIGINT`（`V2`），搜索 popularity 排序：`ORDER BY s.download_count DESC`（`PostgresFullTextQueryService`、`V29`）。
- `skill_version_stats(skill_version_id, skill_id, download_count)`（`V14`），`ON CONFLICT ... download_count + 1`。
- `SkillDownloadService.recordDownloadById`：**无类级/方法级 `@Transactional`**。两次 `incrementDownloadCount`（各自 `@Modifying` 短事务）完成后才 `publishEvent(SkillDownloadedEvent)`。
- **仅 `PUBLISHED` 且可安装的版本**计入；审核包 `downloadReviewVersion`、draft 深链 **不计**。
- 匿名可下 **PUBLIC** skill。匿名限流身份：`AnonymousDownloadIdentityService`（cookie `skillhub_anon_dl`，secret 启动校验 ≥32 且禁 placeholder）。
- Deep-link：`DownloadLinkStore.markCountedIfAbsent`（Redis SETNX，10 分钟）保证 **每 token 计一次**。`recordDownloadById` 的调用方只有 `downloadVersion` 与 `SkillDownloadLinkService.resolveForRedirect`。
- Compat `/api/v1/download` **只 302 到** `/api/v1/skills/{ns}/{slug}/download`，真正计数发生在 `SkillDownloadService`。
- 前端详情页展示 `downloadCount`；点击下载时 `incrementSkillDownloadCount` **乐观更新缓存**（`web/src/shared/lib/skill-download-cache.ts`），不是独立上报。

**缺口**：`SkillDownloadedEvent` 只有 `(skillId, versionId)`，无 production listener，无用户。

### 4.3 搜索

| 入口 | 调用 |
|---|---|
| Web `GET /api/web/skills` | `SkillSearchController` → `SkillSearchAppService.search` |
| Compat `GET /api/v1/search` | `ClawHubCompatAppService.search` → `SkillSearchAppService.search` |
| CLI `GET /api/cli/v1/skills/search` | `CliSkillAppService.search` → `SkillSearchAppService.searchInstallableLatest` |
| Registry `ClawHubRegistryFacade.search` | 同样进 `SkillSearchAppService` |

限流：`search` 认证 60 / 匿名 20。**无任何 usage 写入**。

### 4.4 上传 / 修改

- `POST /{namespace}/publish` → `SkillPublishService.publishFromEntries`。
- 成功后发 `SkillPublishedEvent(skillId, versionId, publisherId)` 或 `ReviewSubmittedEvent`。
- **领域层不写 audit**；Web 发布无 `audit_log`。
- 版本再发布：`RERELEASE_SKILL_VERSION` 已在 `SkillLifecycleAppService` 写 audit。
- 元数据「修改」没有独立 PATCH；新版本上传即修改。
- **今天会发出 `SkillPublishedEvent` 的三个生产点**（均为三参 `new SkillPublishedEvent(skillId, versionId, publisherId)`，且都在未提交的 `@Transactional` 方法内）：
  1. `SkillPublishService`（超管/直发进入 `PUBLISHED`）
  2. `SkillPublicationService.publishVersion`（**仅** `ReviewService` 审核通过、`SecurityScanService` 扫描 SAFE 自动发布。**不含** `confirmPublish`）
  3. `PromotionService`（提升通过时新建目标 skill 并直接 `PUBLISHED`）
- **第四条生产发布路径（今天不发事件）**：`SkillReviewSubmitService.confirmPublish`（`SkillLifecycleController` → `GovernanceWorkflowAppService` → `SkillLifecycleAppService.confirmPublish`）。它自己把 PRIVATE 版本从 `UPLOADED`/`DRAFT` 置为 `PUBLISHED` 并更新 `latestVersionId`，**不**调用 `publishVersion`，**不** `publishEvent`。`audit_log` 另写 `CONFIRM_PUBLISH`。若 usage 只监听现有三个 `SkillPublishedEvent` 发射点，**PRIVATE 确认发布会静默漏记 `PUBLISH`**。

### 4.5 详情打开

- Portal：`SkillController.getSkillDetail`（`/api/v1/skills/{ns}/{slug}` 与 `/api/web/skills/{ns}/{slug}`）。
- Registry：`ClawHubRegistryFacade.getSkill` → `getSkillDetail`（inspect，v1 **不计 VIEW**）。
- Review：`ReviewController` 走审核专用 DTO，不计作者可见 VIEW。
- 文件 / 版本列表 / resolve GET：**不是 VIEW**。
- Health **不**打这些路由。

### 4.6 Metrics 与异步池

`SkillHubMetrics` 仅有 publish / download.delivery / bundle_missing_fallback。

唯一业务线程池：`AsyncConfig.skillhubEventExecutor`（core 2 / max 4 / queue 100 / **`CallerRunsPolicy`**）。搜索索引、飞书、通知都用它。用量 **禁止** 复用该池。

### 4.7 前端

- 详情 / 搜索卡 / 我的技能：只展示 `downloadCount`。
- 详情 DTO 权限字段是 **`canManageLifecycle`**（不是 `canManage`）。
- 管理后台：审计日志，无「谁用过」页。

### 4.8 Flyway / 任务

- 下一版本号：**V46**（当前最新 `V45__add_auto_publish_on_scan_pass_to_skill_version.sql`）。
- 惯例：`BIGSERIAL` PK、`VARCHAR(128)` 用户 ID、`timestamptz`、显式索引、`COMMENT ON TABLE`。
- `IdempotencyCleanupTask` 约 02:00；保留任务避开该窗口。

---

## 5. Proposed design

### 5.1 与 `AuditLog` 的关系（结论）

**新建 `skill_usage_event` + `skill_usage_actor` + 可重建日/滚动表，不扩展 `audit_log`。**

| | `audit_log` | `skill_usage_event` |
|---|---|---|
| 目的 | 合规 / 安全 / 治理写操作 | 用量与「谁用过」 |
| 体量 | 低 | 中高（搜索/打开详情） |
| 匿名 | FK 禁止 | 允许 `actor_user_id` NULL |
| 热路径 | 同步、同事务 | 搜索/详情：专用 `usageTaskExecutor`；下载：increment 之后同步新事务写明细；上传/发布：外层事务 **AFTER_COMMIT** 后再写 |
| 读者 | AUDITOR / SUPER_ADMIN | 公开计数 + 作者汇总 + 管理员明细 |
| 保留 | 长期（合规） | PII 30 天擦除；行删除默认关闭 |

治理动作继续只写 `audit_log`。**禁止**把下载/搜索写入 `audit_log`，也禁止把 hide/yank 再写一份 usage。

作者治理时间线（usage ∪ audit）**推迟到后续独立 PR**，见 §5.8.3。

### 5.2 「使用」定义

| 指标 | 定义 | 是否公开 | 权威数据 |
|---|---|---|---|
| **Downloads（使用次数）** | 已发布版本每次成功交付（stream / 302 presign / deep-link 首次兑现 / tag 下载） | 是，`skill.downloadCount` | `SkillDownloadService` 单写者 |
| **Unique downloaders** | 该 skill 上曾出现过 `DOWNLOAD` 的 distinct `actor_key`（lifetime） | 作者 / 管理员 | **`skill_usage_actor`** |
| **Views** | Portal 详情 GET 成功（仅 `SkillController.getSkillDetail`） | 作者 / 管理员 | 事件 + rollup |
| **Unique viewers** | lifetime distinct `VIEW` `actor_key` | 作者 / 管理员 | **`skill_usage_actor`** |
| **Searches** | 一次 `SkillSearchAppService` 成功返回（query-level，`skill_id` 空） | 仅管理员 | 事件 |
| **Uploads / Updates** | 成功创建 skill 首版本 / 追加新版本 | 作者 / 管理员 | 事件 |
| **Published** | 版本进入 `PUBLISHED` | 作者 / 管理员 | 事件；审核通过仍以 `audit_log.REVIEW_APPROVE` 为准 |

**搜索不计入公开使用次数。**

### 5.3 动作与客户端枚举

```
SkillUsageAction:
  DOWNLOAD | VIEW | SEARCH | UPLOAD | UPDATE | PUBLISH
```

```
SkillUsageClient:
  WEB       // /api/web/** 或带 session 的 /api/v1/skills/** 非 compat
  CLI       // /api/cli/** （deep-link 兑现除外，见 5.7.2）
  COMPAT    // ClawHub /api/v1/search、/api/v1/publish 等；不含最终落到 SkillDownloadService 的包下载（那次按落地路径记）
  API_TOKEN // /api/v1/skills/** 且仅 Bearer API token、无 session
  UNKNOWN   // 无 HTTP 的 PUBLISH/UPLOAD（审核扫描自动发、三参 publishVersion）；列宽仍 VARCHAR(16)
```

```
SkillUsageAuthMethod:
  SESSION | API_TOKEN | ANONYMOUS | UNKNOWN
```

v1 **不**设 `DEVICE_FLOW`。Device grant 换到的仍是普通 `ApiToken`，记 `API_TOKEN`。  
v1 **不**设 `INTERNAL` 客户端。判定规则：没有组装好的 `SkillUsageRequestContext` → **不写 SEARCH/VIEW**；DOWNLOAD 只在 `SkillDownloadService` 被用户请求路径调用时记录（见 5.7.2 排除表）。

```
SkillUsageActorKind:
  USER | ANONYMOUS
```

### 5.4 数据模型

#### 5.4.1 Flyway `V46__skill_usage_events.sql`

```sql
CREATE TABLE skill_usage_event (
    id               BIGSERIAL PRIMARY KEY,
    occurred_at      TIMESTAMPTZ NOT NULL,
    action           VARCHAR(32)  NOT NULL,
    skill_id         BIGINT REFERENCES skill(id) ON DELETE SET NULL,
    skill_version_id BIGINT,
    namespace_id     BIGINT,
    actor_user_id    VARCHAR(128),
    actor_key        VARCHAR(160) NOT NULL,
    actor_kind       VARCHAR(16)  NOT NULL,
    client           VARCHAR(16)  NOT NULL,
    auth_method      VARCHAR(16)  NOT NULL,
    request_id       VARCHAR(64),
    client_ip        VARCHAR(64),
    user_agent       VARCHAR(512),
    dedup_key        VARCHAR(512),
    payload_json     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE skill_usage_event IS
  'Append-only skill usage ledger. Public download_count remains on skill. Unique counts live on skill_usage_actor.';
COMMENT ON COLUMN skill_usage_event.actor_key IS
  'user:{userId} (userId up to 128 chars) or anon:{hex}. Column is VARCHAR(160).';
COMMENT ON COLUMN skill_usage_event.actor_kind IS
  'USER | ANONYMOUS';
COMMENT ON COLUMN skill_usage_event.client IS
  'WEB | CLI | COMPAT | API_TOKEN | UNKNOWN';
COMMENT ON COLUMN skill_usage_event.auth_method IS
  'SESSION | API_TOKEN | ANONYMOUS | UNKNOWN';
COMMENT ON COLUMN skill_usage_event.dedup_key IS
  'Optional unique key inside the dedup window; UNIQUE where not null. VARCHAR(512): must fit action + subject + VARCHAR(160) actor_key + bucket.';

CREATE UNIQUE INDEX uq_skill_usage_event_dedup
    ON skill_usage_event (dedup_key)
    WHERE dedup_key IS NOT NULL;

CREATE INDEX idx_skill_usage_event_skill_time
    ON skill_usage_event (skill_id, occurred_at DESC)
    WHERE skill_id IS NOT NULL;

CREATE INDEX idx_skill_usage_event_action_time
    ON skill_usage_event (action, occurred_at DESC);

CREATE INDEX idx_skill_usage_event_actor_time
    ON skill_usage_event (actor_user_id, occurred_at DESC)
    WHERE actor_user_id IS NOT NULL;

CREATE INDEX idx_skill_usage_event_skill_action_actor_time
    ON skill_usage_event (skill_id, action, actor_key, occurred_at DESC);

CREATE INDEX idx_skill_usage_event_occurred_at
    ON skill_usage_event (occurred_at);

-- Lifetime first-seen / last-seen. Authority for unique_* rollup. Survives event DELETE.
CREATE TABLE skill_usage_actor (
    skill_id   BIGINT      NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    action     VARCHAR(32) NOT NULL,
    actor_key  VARCHAR(160) NOT NULL,
    first_at   TIMESTAMPTZ NOT NULL,
    last_at    TIMESTAMPTZ NOT NULL,
    last_client VARCHAR(16) NOT NULL,
    PRIMARY KEY (skill_id, action, actor_key)
);

CREATE INDEX idx_skill_usage_actor_skill_action_last
    ON skill_usage_actor (skill_id, action, last_at DESC, actor_key);

COMMENT ON TABLE skill_usage_actor IS
  'Lifetime unique authority for DOWNLOAD and VIEW only. SEARCH is query-level (skill_id NULL) and never inserts here. UPLOAD/UPDATE/PUBLISH write events only.';

CREATE TABLE skill_usage_daily (
    skill_id      BIGINT      NOT NULL REFERENCES skill(id) ON DELETE CASCADE,
    day           DATE        NOT NULL,
    action        VARCHAR(32) NOT NULL,
    event_count   BIGINT      NOT NULL DEFAULT 0,
    unique_actors BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (skill_id, day, action)
);

CREATE INDEX idx_skill_usage_daily_day ON skill_usage_daily (day);

CREATE TABLE skill_usage_rollup (
    skill_id             BIGINT PRIMARY KEY REFERENCES skill(id) ON DELETE CASCADE,
    view_count           BIGINT NOT NULL DEFAULT 0,
    unique_downloaders   BIGINT NOT NULL DEFAULT 0,
    unique_viewers       BIGINT NOT NULL DEFAULT 0,
    upload_count         BIGINT NOT NULL DEFAULT 0,
    update_count         BIGINT NOT NULL DEFAULT 0,
    last_downloaded_at   TIMESTAMPTZ,
    last_viewed_at       TIMESTAMPTZ,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE skill_usage_job_state (
    job_name     VARCHAR(64) PRIMARY KEY,
    watermark    TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    locked_by    VARCHAR(128),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO skill_usage_job_state (job_name, updated_at) VALUES ('rollup', CURRENT_TIMESTAMP);
```

`payload_json`：

```json
// DOWNLOAD — delivery is domain/storage outcome, NOT HTTP 302 vs stream (see §5.7.2)
{ "version": "1.2.0", "delivery": "presigned|bundle|deeplink", "namespaceSlug": "global", "skillSlug": "demo" }

// VIEW
{ "namespaceSlug": "global", "skillSlug": "demo" }

// SEARCH（仅管理员 API 返回）
{ "q": "truncated<=200", "namespace": "global|null", "sort": "newest", "page": 0, "size": 20, "resultCount": 12, "labelCount": 1 }

// UPLOAD / UPDATE / PUBLISH
{ "version": "1.2.0", "visibility": "PUBLIC", "status": "PENDING_REVIEW|PUBLISHED" }
```

作者 events API **不允许** `action=SEARCH`。作者可见 payload allow-list：

| action | 字段 |
|---|---|
| DOWNLOAD | `version`, `delivery`（`presigned` / `bundle` / `deeplink`） |
| VIEW | （空对象） |
| UPLOAD / UPDATE / PUBLISH | `version`, `status` |

`actor_user_id` **不加 FK**。

#### 5.4.2 领域模型放置

| 构件 | 模块 | 包 |
|---|---|---|
| 枚举、`SkillUsageEvent`、`SkillUsageActor`、`SkillUsageRequestContext`（纯 record，无 servlet） | domain | `com.iflytek.skillhub.domain.usage` |
| `SkillUsageRecorder.record(UsageCommand)` | domain | 同上。**始终** insert `skill_usage_event`。**仅当** `skillId != null && action ∈ {DOWNLOAD, VIEW}` 时 upsert `skill_usage_actor`。SEARCH（`skill_id` 恒 null）与 UPLOAD/UPDATE/PUBLISH **禁止**写 actor 行。**不依赖 Redis / HTTP / auth / storage** |
| Repo 接口 | domain | 同上 |
| JPA | infra | `com.iflytek.skillhub.infra.jpa` |
| `UsageContextFactory`（从 `HttpServletRequest` 填 `SkillUsageRequestContext` + actor_key） | app | `com.iflytek.skillhub.usage` |
| `UsageDedupService`（Redis SETNX） | app | 同上 |
| `SkillUsageRecordingListener` | app | `com.iflytek.skillhub.listener` |
| App / query / controllers / tasks | app | 见 §12 |

`UsageCommand`（domain，PR 1 即冻结）：

```java
public record UsageCommand(
    Instant occurredAt,
    SkillUsageAction action,
    Long skillId,
    Long skillVersionId,
    Long namespaceId,
    String actorUserId,
    String actorKey,
    SkillUsageActorKind actorKind,
    SkillUsageRequestContext requestContext,
    String dedupKey,
    String payloadJson
) {}

public record SkillUsageRequestContext(
    SkillUsageClient client,
    SkillUsageAuthMethod authMethod,
    String requestId,
    String clientIp,
    String userAgent
) {}

/** HTTP 边组装、传入 domain 下载方法。禁止 ThreadLocal。 */
public record UsageAttribution(
    String actorUserId,
    String actorKey,
    SkillUsageActorKind actorKind,
    SkillUsageRequestContext requestContext
) {
    public boolean isRecordable() {
        return actorKey != null && !actorKey.isBlank() && requestContext != null;
    }
}
```

后续 PR **只调用** `SkillUsageRecorder.record` / 发已定义事件，禁止再开第二套写入。

**`SkillUsageRecorder.record` 冻结实现（PR 1，禁止「无脑 upsert actor」）：**

```java
@Transactional
public void record(UsageCommand cmd) {
    insertEvent(cmd); // always
    if (cmd.skillId() != null
            && (cmd.action() == SkillUsageAction.DOWNLOAD
                || cmd.action() == SkillUsageAction.VIEW)) {
        upsertActor(cmd);
    }
}
```

```sql
INSERT INTO skill_usage_actor AS a
    (skill_id, action, actor_key, first_at, last_at, last_client)
VALUES
    (:skillId, :action, :actorKey, :occurredAt, :occurredAt, :client)
ON CONFLICT (skill_id, action, actor_key) DO UPDATE SET
    last_at     = EXCLUDED.last_at,
    last_client = EXCLUDED.last_client,
    first_at    = LEAST(a.first_at, EXCLUDED.first_at);
```

| action | insert event | upsert `skill_usage_actor` |
|---|---|---|
| `DOWNLOAD` | 是（`skill_id` 非空） | **是** |
| `VIEW` | 是（`skill_id` 非空） | **是** |
| `SEARCH` | 是（**`skill_id` 恒为 NULL**） | **否**（PK/FK 都不允许 null `skill_id`） |
| `UPLOAD` / `UPDATE` / `PUBLISH` | 是 | **否**（不得进入 `unique_downloaders` / `unique_viewers`） |

`uq_skill_usage_event_dedup` 冲突仍只吞该 UNIQUE；actor upsert 的其它完整性错误上抛。`SkillUsageRecorderTest` 必须：一条 `SEARCH` + `skillId=null` **成功 insert event 且零次 actor insert**；`DOWNLOAD` 触发上述 `ON CONFLICT`。

### 5.5 身份、匿名、客户端

**`actor_key`**

- 已登录：`user:{userId}`。`userId` 最长 128 → 列必须 `VARCHAR(160)`。Recorder 单测必须覆盖 128 字符 id。
- 匿名且请求带有效 `skillhub_anon_dl`：`anon:{cookieHash}`。`cookieHash` = 现有 **private** `AnonymousDownloadIdentityService.hash(cookieId)`（SHA-256 **hex，64 字符**，不是 32 字符 HMAC fallback）。**禁止**第二颗 cookie、禁止 `USAGE_ANONYMOUS_SECRET`。
- 匿名且无 cookie（CLI / compat / 未走过下载限流的 Web）：`anon:{fallbackHex}`，其中  
  `fallbackHex = hex( HMAC-SHA256(SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET, clientIp + "\n" + normalizedUserAgent)[0:16] )`  
  → **32 个 hex 字符**。`normalizedUserAgent` = trim 后截断 512。
- Web 的 SEARCH/VIEW：**只读**已有 cookie，**禁止**调用 `resolve(request, response)`。现网 `resolve()` **在 cookie 无效时一律 `response.addHeader(Set-Cookie)`**；`extractValidCookieId` / `hash` 都是 private。`RateLimitInterceptor` **只**在 `category=download` 时调 `resolve`，搜索/详情今天不会有 cookie。因此必须新增只读 API（PR 2 与 factory 一起落地，PR 3 SEARCH/VIEW 才能用）：

```java
/**
 * Verify skillhub_anon_dl with current then previous secret.
 * Never writes Set-Cookie. Empty when the cookie is missing or invalid.
 * Returned hash is SHA-256 hex of the cookie id (64 chars), same as resolve().cookieHash.
 */
public Optional<String> peekCookieHash(HttpServletRequest request) {
    String cookieId = extractValidCookieId(request); // already tries current then previous after PR 2 secret work
    return cookieId == null ? Optional.empty() : Optional.of(hash(cookieId));
}
```

`UsageContextFactory` 规则：

| 路径 | 匿名 actor_key |
|---|---|
| 下载（portal / CLI / 已过 `RateLimitInterceptor` download） | 已登录 → `user:{id}`；否则按下面「同请求 mint」顺序 |
| SEARCH / VIEW | 已登录 → `user:{id}`；否则 **只** `peekCookieHash`；empty → HMAC fallback。**永不** `resolve()` |

**同请求 mint：** `resolve()` 把 `Set-Cookie` 写在 **response** 上，**不会**把新 cookie 塞回 `request.getCookies()`。第一次匿名 Web 下载若只 `peekCookieHash(request)` 会 empty，HMAC fallback 与下一请求的 `anon:{cookieHash}` 会裂成两个 unique。因此：

1. `RateLimitInterceptor` 在 `category=download` 调用 `resolve` 之后：`request.setAttribute("anonymousDownloadIdentity", identity)`（`AnonymousDownloadIdentity`，含已有的 `cookieHash`）。
2. Factory 下载路径顺序：`peekCookieHash(request)` → 若 empty 读 attribute `anonymousDownloadIdentity.cookieHash()` → 再 empty 才 HMAC。
3. SEARCH/VIEW **不**读该 attribute（它们从不 mint），只 peek。

- `resolve()` **仅**留给下载限流（`RateLimitInterceptor` `category=download`）。单测：`peekCookieHash` 在无 cookie / 坏签名时 empty 且 response 无 `Set-Cookie`；有效 cookie 返回 64 hex；`resolve` 行为不变（无 cookie 仍 mint）。
- Secret 策略与下载 cookie **同一套**：启动时已由 `AnonymousDownloadIdentityService.validateAnonymousCookieSecret` fail-fast。轮转：在 **现有** `@ConfigurationProperties(prefix = "skillhub.ratelimit.download")` 的 `DownloadRateLimitProperties` 上增加可选字段 `anonymousCookiePreviousSecret`（YAML：`skillhub.ratelimit.download.anonymous-cookie-previous-secret`）。**禁止**再发明 `skillhub.download-rate-limit.*` 前缀（仓库里没有这个 namespace）。`AnonymousDownloadIdentityService.parseAndVerify` 与 fallback HMAC **先试当前 secret，失败再试 previous**。`peekCookieHash` 走同一条 `extractValidCookieId` → `parseAndVerify`。持久化的 `actor_key` **永远用当前 secret** 算出的值（`hash(cookieId)` 与 secret 无关，轮转只影响验签）。轮转窗口内同一匿名访客可能短暂显示为两个 unique，可接受。

**作者 API 展示**

- 已登录：`userId` + `displayName` + 可选 `avatarUrl`。
- 匿名：`displayName` = i18n `usage.actor.anonymous`，不返回 `userId`，可返回 `actorKey` 前 8 位。
- **永不**返回 `clientIp` / `userAgent`。

**管理员 API**

- `clientIp` / 截断 `userAgent` / `requestId`：**仅** `AUDITOR`、`SUPER_ADMIN`。
- `SKILL_ADMIN` 可按 `skillId`/`namespaceId` 看用量行，但这两列必须为 `null`（与审计台 PII 面一致，见 Key Decision 11）。

**客户端判定（请求线程，写入事件字段，禁止 listener 再解析 request）**

1. 事件来自 **token** `resolveForRedirect` → **不要**用 fetcher path；用 token 上的 `issuerClient`（见 5.7.2）。Issue **fallback**（无 token）随后打到 `/api/cli/v1/skills/.../download`，按第 2 条记 `CLI`，**不要**假装 issuer
2. path 以 `/api/cli/` 开头 → `CLI`
3. ClawHub compat 映射（`/api/v1/search`、`/api/v1/publish`、`/api/v1/download` 等 `ClawHubCompatController`）→ `COMPAT`（搜索/发布）；下载 302 **不**发 DOWNLOAD
4. path 以 `/api/web/` 开头 → `WEB`
5. 其余 `/api/v1/skills/**`：有 session → `WEB`；仅 API token → `API_TOKEN`
6. `auth_method`：无 principal → `ANONYMOUS`；API token → `API_TOKEN`；session → `SESSION`

### 5.6 去重

Redis key：`skillhub:usage-dedup:v1:{action}:{subject}:{actor_key}`

| Action | subject | TTL | 窗口内重复 |
|---|---|---|---|
| `DOWNLOAD` | `{skillId}:{versionId}` | **15m** | 不写事件、不碰 `skill_usage_actor`；**公开 download_count 仍 +1** |
| `VIEW` | `{skillId}` | **30m** | 丢弃 |
| `SEARCH` | 见下方唯一公式 | **5m** | 丢弃 |
| `UPLOAD` / `UPDATE` / `PUBLISH` | `{skillVersionId}:{action}` | 24h | UNIQUE `dedup_key` |

**SEARCH subject 唯一公式**（不再有「表里不含 page、正文含 page」的分叉）：

```
sha256( normalizedQ + "|" + nullToEmpty(ns) + "|" + sort + "|" + page + "|" + size )
```

`normalizedQ` = trim + 连续空白压单空格 + 小写 + 截断 200。换 filter / 翻页 / 改 page size = 新 subject。空 `q` 的「逛」按上式记；v1 **不采样**。管理员发现图读的是「去重后的查询次数」不是 UV。

`dedup_key`（PG UNIQUE，列宽 **VARCHAR(512)**）：明文拼接

```
{action}:{subject}:{actor_key}:{bucket}
```

`bucket` = `occurred_at` 按 TTL 向下取整（ISO-8601 对齐后的 epoch 桶号即可）。

长度上界（必须能放下，禁止再截断）：

| 段 | 最大 |
|---|---|
| `action` | 16（`DOWNLOAD` 等） |
| `subject` | DOWNLOAD `skillId:versionId` ≤ 40；SEARCH = 64 hex sha256；UPLOAD/UPDATE/PUBLISH `versionId:ACTION` ≤ 40 |
| `actor_key` | **160**（`user:` + 128 字符 userId） |
| `bucket` | ≤ 20 |
| 分隔冒号 | 3 |

最坏约 16+40+160+20+3 = **239**，远小于 512。Recorder 单测必须用 **128 字符 userId + DOWNLOAD subject** 插入并断言 `dedup_key.length() > 128` 且写入成功。

**不要**改成 sha256 摘要，除非将来 subject 再膨胀；明文便于排 UNIQUE 冲突。Redis 丢失后 UNIQUE 冲突视为成功去重，**仅**吞「违反 `uq_skill_usage_event_dedup`」的 `DataIntegrityViolationException`（不得把 `actor_key` 超长、FK 失败等其它完整性错误当成去重）。

公开「下载次数」与「独立下载用户」口径分裂，必须在 UI 写清（§5.10 文案）。

### 5.7 写路径

按 **「发布事件时有没有尚未提交的、会插入 `skill` / `skill_version` 的外层事务」** 拆开发送模型。**禁止**一条全局规则套所有 action。

| Action | 事件何时 `publishEvent` | 外层事务？ | Usage 投递 | 为何 |
|---|---|---|---|---|
| `DOWNLOAD` | `SkillDownloadService.recordDownloadById`，两次 increment **短事务已提交之后** | **无** | 普通 `@EventListener`（同步）→ `@Transactional(REQUIRES_NEW)` `record()` | 无活跃事务时 `@TransactionalEventListener(AFTER_COMMIT)` **会被丢掉**（无 `fallbackExecution` 也一样） |
| `UPLOAD` / `UPDATE` | `SkillPackageAcceptedEvent`，在 `SkillPublishService` / `PromotionService` 的 `@Transactional` **内部** | **有**（新 `skill` 行可能尚未对其他事务可见） | `@TransactionalEventListener(phase = AFTER_COMMIT)`，**不要** `@Async`，**不要** `REQUIRES_NEW` 抢跑；listener 里 `try/catch` 吞 recorder 异常 | 同步 `@EventListener` + `REQUIRES_NEW` **看不见未提交的 `skill.id`**，首次 `UPLOAD` / 提升新建目标 skill 会 **FK 失败**；未捕获的 listener 异常还会回滚发布事务 |
| `PUBLISH` | `SkillPublishedEvent`，现有三个 `@Transactional` 发射点 **加上** `confirmPublish` 新增的第四次 `publishEvent`（仍在该方法自己的 `@Transactional` 内） | **有** | 与 UPLOAD 相同：`AFTER_COMMIT` + 吞异常 | 同上 |
| `SEARCH` / `VIEW` | 读路径，成功返回后 | 无写事务 | `usageTaskExecutor.execute`（事件已自带全部字段） | 禁止反压搜索/详情 |

**禁止：**

- 对 **DOWNLOAD** 使用 `@TransactionalEventListener`（含 `AFTER_COMMIT` / `fallbackExecution`）。
- 对 **UPLOAD / UPDATE / PUBLISH** 使用同步 `@EventListener` + `REQUIRES_NEW`（会 FK-fail / 可能回滚发布）。
- Listener / `@Async` 方法读取 ThreadLocal、`RequestContextHolder`、request-scoped bean。
- `@Async("skillhubEventExecutor")` 写 usage（`CallerRunsPolicy` 会把 insert 打回搜索线程）。
- 给 UPLOAD/PUBLISH 的 AFTER_COMMIT listener 再加 `@Async("skillhubEventExecutor")`（现有 `SearchIndexEventListener` 那样做是为了索引，用量不要跟它抢池，也不要异步到 skill 行可见性变得难测）。

**允许：**

- 请求线程组装 `SkillUsageRequestContext` + `actor_key`，放进事件 record（DOWNLOAD / SEARCH / VIEW / 有 HTTP 的 publish）。
- DOWNLOAD：普通 `@EventListener` → 新事务 `record`。increment 已提交；用量失败只打 `skillhub.usage.record.failure`，**不回滚下载**。
- UPLOAD / UPDATE / PUBLISH：`AFTER_COMMIT` 后同一请求线程跑 listener；此时 `skill` / `skill_version` 已对其他事务可见，`record()` 用默认事务即可。失败只打点，**禁止**把异常抛回（`AFTER_COMMIT` 抛异常也不会回滚已提交的发布，但会吓到日志/监控；必须 catch）。
- SEARCH / VIEW：`usageTaskExecutor` + Discard。

v1 **不做 outbox 表**。下载成功但用量 insert 失败 = 公开次数正确、明细少一条。企业体量可接受；用 metric 观察。

```
  用户请求线程
       │
       ├─ DOWNLOAD: incrementDownloadCount (各自短事务, 已提交)
       │            publish SkillDownloadedEvent(…, requestContext, actorKey, …)
       │            @EventListener 同步 → REQUIRES_NEW record()
       │
       ├─ UPLOAD/UPDATE/PUBLISH:
       │            @Transactional 发布方法内 publishEvent
       │            事务 COMMIT
       │            @TransactionalEventListener(AFTER_COMMIT) → record()
       │            catch 所有异常 → metric，绝不向上抛
       │
       ├─ SEARCH:   SkillSearchAppService 返回后 submit usageTaskExecutor
       │            队列满 DISCARD + metric，响应不受影响
       │
       └─ VIEW:     仅 SkillController.getSkillDetail 成功后 submit usageTaskExecutor
```

#### 5.7.1 避免双写不一致

| 数据 | 一致性 | 重建 |
|---|---|---|
| `skill.download_count` | 与现网相同 | **禁止**从事件重建（去重会使数字偏小） |
| `skill_usage_event` | 尽力而为；窗口内至多一行 | Redis + UNIQUE |
| `skill_usage_actor` | 与成功写入的事件同事务 upsert | 事件仍在时可用 `MIN/MAX(occurred_at)` 重建；**事件删除后以本表为准** |
| `skill_usage_daily` / 非 unique 的 rollup 计数 | 最终一致（60s） | 从事件重算尚在保留期内的天；unique_* **只**从 `skill_usage_actor` 计 |

#### 5.7.2 下载

**`delivery` 不是 HTTP 状态。** 现网 `SkillController.buildDownloadResponse` / CLI `buildDownloadResponse` 在 **increment 已经发生之后** 才用 `shouldRedirectToPresignedUrl`（HTTPS 请求 + 非 HTTPS presign → 改 stream）决定 302 还是 stream。Domain **不可能**在 `recordPublishedDownload` 里知道最终 HTTP 形态。因此 payload / 事件字段：

| `delivery` 值 | 何时写入 | 含义 |
|---|---|---|
| `presigned` | `DownloadResult.presignedUrl() != null && !fallbackBundle` | 存储给出了 presigned URL（HTTP 仍可能因 insecure URL 改 stream） |
| `bundle` | `fallbackBundle == true` 或 `presignedUrl == null` | 现场打 zip / 无 presign |
| `deeplink` | **仅** `SkillDownloadLinkService.resolveForRedirect` 调带 attribution 的 `recordDownloadById` | token 兑现。**不含** issue 的 CLI fallback 路径 |

**禁止** `redirect|stream`。**禁止**在 `buildDownloadResponse` 之后二次 UPDATE payload。作者 API 的 `delivery` 展示文案用 `presigned` / `bundle` / `deeplink`，不要写成「浏览器 302」。

**`delivery` 在 increment 之前就算好。** `private downloadVersion` 顺序必须是：

```
DownloadResult result = buildDownloadResult(skill, version);   // 已有 presignedUrl / fallbackBundle
if (version.status == PUBLISHED) {
    String delivery = result.fallbackBundle() || result.presignedUrl() == null
            ? "bundle" : "presigned";
    recordPublishedDownload(skill, version, attribution, delivery);
}
return result;
```

`attribution` 来自三条 public 方法的最后一参，**在进入 increment 前已经在栈上**，不是事后从 ThreadLocal / Holder 补。

---

**现网生产调用图（改完后每一条必须带着 attribution 或显式 null）**

`SkillQueryService` **没有**下载方法。`ReviewController` 走 `downloadReviewVersion`，**不计**。

```
A. Portal 包下载（计 DOWNLOAD）
   SkillController.downloadLatest | downloadVersion | downloadByTag
        UsageAttribution a = usageContextFactory.fromRequest(request, userId);
        skillDownloadService.download*(..., userId, roles, a)
        → private downloadVersion(..., a)
        → recordPublishedDownload(..., a, delivery=presigned|bundle)
        → recordDownloadById(skillId, versionId, version, ns, slug, delivery, a)
        → incrementBoth + SkillDownloadedEvent(完整字段)
        → 返回 DownloadResult
        → SkillController.buildDownloadResponse   // 302 vs stream；不再写 usage

B. CLI 包下载（计 DOWNLOAD）
   CliSkillController → CliSkillAppService.downloadLatest | downloadVersion
        a = usageContextFactory.fromRequest(request, request.userId)
        skillDownloadService.download*(..., a)     // 同上，client 由 factory 判 CLI
        → CliSkillAppService.buildDownloadResponse // 现网：presignedUrl!=null 即 302，无 HTTPS 回退

C. Compat 302（本跳 不计 DOWNLOAD）
   ClawHubCompatController GET /api/v1/download | /download/{slug}
        → ClawHubCompatAppService.downloadLocation*
        → 302 Location=/api/v1/skills/{ns}/{slug}/download
        → 浏览器/CLI 跟随后走路径 A（client 按落地 URL：/api/v1/skills + session/token）

D. Deep-link 签发（不计 DOWNLOAD；必须捕获 issuer）
   DownloadLinkController.createDownloadLink          // 已有 HttpServletRequest + principal
        a = usageContextFactory.fromRequest(request, principal.userId())
        skillDownloadLinkService.issueDownloadLink(ns, slug, version, userId, roles, a)
        → skillDownloadService.presignDownload(...)   // 现网：不 increment、不发事件
        → 若 presignedUrl 非空：
              DownloadLinkData 写入 skill/version + 全部 issuer 字段（见下表）
              返回 token
        → 若 presignedUrl 空（local/dev）：
              不写 token、不写 issuer
              返回 fallbackPath=/api/cli/v1/skills/{ns}/{slug}/download
              QoderWork 随后走路径 B（fetcher 归因，delivery=presigned|bundle，不是 deeplink）

E. Deep-link 兑现（计 DOWNLOAD，issuer 覆盖 fetcher 身份）
   CliDownloadLinkController GET /api/cli/v1/download-link/{token}
        → SkillDownloadLinkService.resolveForRedirect(token)
        → markCountedIfAbsent
        → issuer UsageAttribution = from DownloadLinkData（旧 token 见下）
        → recordDownloadById(..., delivery="deeplink", issuerAttribution)
        → 302 到存储 presigned URL

F. 两参 recordDownloadById（不计「谁」）
   仅测试 / 内部拉包。Listener 因 !isRecordable() 跳过。
   生产 A–E 禁止走这个 overload。
```

**生产调用方对照（禁止漏改）：**

| # | 类.方法 | 今日签名 | PR 2 必须传入 |
|---|---|---|---|
| 1 | `SkillController.downloadLatest` | `downloadLatest(ns, slug, userId, roles)` | `fromRequest(request, userId)` |
| 2 | `SkillController.downloadVersion` | 同上 + version | 同上 |
| 3 | `SkillController.downloadByTag` | 同上 + tag | 同上 |
| 4 | `CliSkillAppService.downloadLatest` | `downloadLatest(ns, slug, userId, roles)` | `fromRequest(request, userId)` |
| 5 | `CliSkillAppService.downloadVersion` | 同上 + version | 同上 |
| 6 | `CliSkillController` 两个 download | 只调 AppService | **不改签名** |
| 7 | `DownloadLinkController.createDownloadLink` | `issueDownloadLink(ns, slug, version, userId, roles)` | **`issueDownloadLink(..., attribution)`**；factory 用已有 `request` + `principal.userId()` |
| 8 | `SkillDownloadLinkService.issueDownloadLink` | 5 参，无 context | 追加 `UsageAttribution`；presign 成功则把 issuer 字段写入 `DownloadLinkData` |
| 9 | `SkillDownloadLinkService.resolveForRedirect` | 两参 `recordDownloadById` | 带 attribution overload，`delivery=deeplink`，attribution = **issuer** |
| 10 | `ClawHubCompatAppService.downloadLocation*` | 只拼 Location | **不**调 `recordDownloadById` |
| 11 | `ReviewController` review download | `downloadReviewVersion` | **不**传 attribution、不计 |
| 12 | `SkillDownloadService.presignDownload` | 无 increment | **不**发 DOWNLOAD；issue 只拿 URL |
| 13 | `BuiltinSkillRemotePackageDownloader` / 测试两参 | `recordDownloadById(id,id)` | 保持两参 |

`POST /api/web/skills/{ns}/{slug}/download-link` **没有** `@RateLimit(category=download)`，interceptor **不会** mint `skillhub_anon_dl`。该端点 **要求登录**（`principal.userId()`），issuer `actor_key` = `user:{id}`，不依赖 cookie。Factory 仍走 `fromRequest`，以便写入 `client=WEB`、`authMethod=SESSION`。

**禁止 ThreadLocal。** attribution 是方法参数，从 HTTP 边传到 increment。

---

**1. Domain 签名（PR 2 一次改完，含全部现有 `SkillDownloadServiceTest` 调用点）**

```java
public DownloadResult downloadLatest(..., String currentUserId,
        Map<Long, NamespaceRole> userNsRoles, UsageAttribution attribution);
public DownloadResult downloadVersion(..., String versionStr, String currentUserId,
        Map<Long, NamespaceRole> userNsRoles, UsageAttribution attribution);
public DownloadResult downloadByTag(..., String tagName, String currentUserId,
        Map<Long, NamespaceRole> userNsRoles, UsageAttribution attribution);

private DownloadResult downloadVersion(Skill skill, SkillVersion version,
        String currentUserId, Map<Long, NamespaceRole> userNsRoles,
        UsageAttribution attribution) {
    assertPublishedAccessible(skill);
    assertDownloadableVersion(...);
    DownloadResult result = buildDownloadResult(skill, version);
    if (version.getStatus() == SkillVersionStatus.PUBLISHED) {
        String delivery = (result.fallbackBundle() || result.presignedUrl() == null)
                ? "bundle" : "presigned";
        recordPublishedDownload(skill, version, attribution, delivery);
    }
    return result;
}

private void recordPublishedDownload(Skill skill, SkillVersion version,
        UsageAttribution attribution, String delivery) {
    recordDownloadById(skill.getId(), version.getId(), version.getVersion(),
            namespaceSlugInScope, skill.getSlug(), delivery, attribution);
}

/** 仅测试、内部拉包。三条 public 下载方法与 recordPublishedDownload 禁止调用。 */
public void recordDownloadById(Long skillId, Long versionId) {
    incrementBoth(skillId, versionId);
    eventPublisher.publishEvent(new SkillDownloadedEvent(skillId, versionId));
}

/** 生产唯一 overload。attribution 已在调用方算好；delivery 已在 buildDownloadResult 之后、本方法之前算好。 */
public void recordDownloadById(Long skillId, Long versionId, String version,
        String namespaceSlug, String skillSlug, String delivery,
        UsageAttribution attribution) {
    incrementBoth(skillId, versionId);
    eventPublisher.publishEvent(new SkillDownloadedEvent(
            skillId, versionId, version,
            attribution == null ? null : attribution.actorUserId(),
            attribution == null ? null : attribution.actorKey(),
            attribution == null ? null : attribution.actorKind(),
            namespaceSlug, skillSlug, delivery,
            attribution == null ? null : attribution.requestContext()));
}
```

`downloadLatest` / `downloadVersion` / `downloadByTag` **必须**走 `recordPublishedDownload` → **带 attribution 的** `recordDownloadById`，即使 `attribution == null`（测试）也走这个 overload，这样 `delivery`/`version`/`slug` 仍在事件里；listener 只因 `!isRecordable()` 跳过写库。

---

**2. HTTP 边与 issue 签名（PR 2 文件清单必含，缺一不可合并）**

```java
// SkillDownloadLinkService
public IssueResult issueDownloadLink(String namespaceSlug, String skillSlug, String version,
        String userId, Map<Long, NamespaceRole> userNsRoles, UsageAttribution attribution) {
    PresignedDownload presigned = skillDownloadService.presignDownload(...); // 不计次
    if (presigned.presignedUrl() != null && !presigned.presignedUrl().isBlank()) {
        downloadLinkStore.save(token, new DownloadLinkData(
                presigned.presignedUrl(), presigned.skillId(), presigned.versionId(),
                presigned.filename(), presigned.published(),
                attribution == null ? null : attribution.actorUserId(),
                attribution == null ? null : attribution.actorKey(),
                attribution == null ? null : attribution.actorKind(),
                attribution == null || attribution.requestContext() == null
                        ? null : attribution.requestContext().client(),
                attribution == null || attribution.requestContext() == null
                        ? null : attribution.requestContext().authMethod()));
        return IssueResult.redirect(token, expiresAt);
    }
    return IssueResult.fallback("/api/cli/v1/skills/" + namespaceSlug + "/" + skillSlug + "/download", expiresAt);
}

public String resolveForRedirect(String token) {
    DownloadLinkData data = downloadLinkStore.get(token);
    ...
    if (data.isPublished() && downloadLinkStore.markCountedIfAbsent(token)) {
        UsageAttribution issuer = attributionFromIssuer(data); // 见下
        skillDownloadService.recordDownloadById(
                data.getSkillId(), data.getVersionId(), /* version 可空 */
                /* ns/slug 可空 */, "deeplink", issuer);
    }
    return data.getPresignedUrl();
}
```

`DownloadLinkController.createDownloadLink`（已有 `HttpServletRequest request`）：

```java
UsageAttribution a = usageContextFactory.fromRequest(request, principal.userId());
skillDownloadLinkService.issueDownloadLink(namespace, slug, version, principal.userId(),
        userNsRoles != null ? userNsRoles : Map.of(), a);
```

`DownloadLinkData` 增加（无参构造 + 缺字段 null-safe，兼容 10 分钟内旧 Redis JSON）：

| 字段 | 来源 |
|---|---|
| `issuerUserId` | `attribution.actorUserId()` |
| `issuerActorKey` | `attribution.actorKey()` |
| `issuerActorKind` | `attribution.actorKind()` |
| `issuerClient` | `attribution.requestContext().client()` |
| `issuerAuthMethod` | `attribution.requestContext().authMethod()` |

`resolveForRedirect` **禁止**用 fetcher 的 IP/UA/path 当作者可见身份：

- `actorUserId` / `actorKey` / `actorKind` / `client` / `authMethod` = **issuer**。
- issuer 全空（旧 token）→ `client=WEB`、`authMethod=ANONYMOUS`、`actorKey=anon:{fetcher HMAC fallback}`。**不要** `client=UNKNOWN`。
- `delivery=deeplink`。
- `clientIp` / `userAgent` 仍记 **fetcher**（管理员排障），作者 API 不返回。
- 15 分钟 DOWNLOAD dedup 的 `actor_key` = issuer key。

**Fallback 不是 deeplink（Issue 36）。** `presignedUrl == null` 时 **不写 token**。QoderWork 访问 `/api/cli/v1/skills/{ns}/{slug}/download` = 普通路径 B：`client=CLI`，`authMethod` 按当时 fetch 请求（多数匿名），`delivery=presigned|bundle`。v1 **不**把 issuer 塞进 query string。本地/dev「Open in QoderWork」的 unique 可能与点按钮的 Web session 裂成两个 actor，公开 `download_count` 仍只 +1。单测：`issueDownloadLink` 无 presign 时 **零次** `downloadLinkStore.save`，且 **零次** `recordDownloadById`。

`SkillDownloadService` **禁止**依赖 `UsageContextFactory`。

现有测试改 verify：`SkillControllerDownloadTest` / `CliSkillAppServiceTest` / `DownloadLinkControllerTest` / `DownloadRateLimitControllerTest` 的 `downloadVersion(...)` / `issueDownloadLink(...)` 必须带 `any(UsageAttribution.class)`（或 captor）。`SkillDownloadLinkServiceTest`：issue 把 attribution 写入 data；resolve 调 **7 参** `recordDownloadById` 且 `delivery=deeplink`。

---

**3. 事件与 listener**

```java
public record SkillDownloadedEvent(
    Long skillId,
    Long versionId,
    String version,
    String actorUserId,
    String actorKey,
    SkillUsageActorKind actorKind,
    String namespaceSlug,
    String skillSlug,
    String delivery,                 // presigned | bundle | deeplink  — 不是 redirect|stream
    SkillUsageRequestContext requestContext
) {
    public SkillDownloadedEvent(Long skillId, Long versionId) {
        this(skillId, versionId, null, null, null, null, null, null, null, null);
    }
}
```

普通 `@EventListener`（同步，**不是** AFTER_COMMIT）`SkillUsageRecordingListener.onDownloaded`：

1. 若 `requestContext == null` **或** `actorKey` 空白 → **return**（两参 overload / 内部拉包 / 测试未传 attribution）。portal/CLI 三条下载与 **issue 成功后的 resolve** 必须传入 `isRecordable()` 的 attribution。
2. Redis DOWNLOAD dedup；hit 则 return。
3. `@Transactional(REQUIRES_NEW)` `recorder.record(...)`；异常 catch 后打 `skillhub.usage.record.failure`，不向上抛。

`SkillDownloadServiceTest` **必须**增加：`downloadLatest(..., attribution)` 在 PUBLISHED 路径 captor 里 `actorKey` / `requestContext` 非空，且 `delivery` ∈ {`presigned`,`bundle`}（由 `DownloadResult` 决定，**不要**断言 `redirect`/`stream`）；`downloadLatest(..., null)` 仍 increment，事件 `actorKey` 为空。

**排除（不发 DOWNLOAD / 不计公开次数）**

| 调用 | 处理 |
|---|---|
| `downloadReviewVersion` | 不 increment、不发事件 |
| draft / 非 PUBLISHED | 现网已跳过 increment |
| `presignDownload` / `issueDownloadLink` | 不 increment |
| issue **fallback** 本身 | 不 increment；随后 CLI GET 按路径 B 计一次 |
| 两参 `recordDownloadById` | increment 但不写「谁」 |
| Compat `GET /api/v1/download` 302 | **不**发 DOWNLOAD |
| Tag 下载 | 路径 A 一次 |
| HTTP 302 与 stream | 都在 `private downloadVersion` 里各一次 increment；`delivery` 仍是 `presigned`/`bundle` |

#### 5.7.3 搜索（高频）

**只在一处打点**：`SkillSearchAppService.searchVisibleSkills`（`search` 与 `searchInstallableLatest` 的共同出口）在 **成功返回 `SearchResponse` 之后**（仍在请求线程）构造 `SkillSearchedEvent`（自带 `SkillUsageRequestContext`、`actorKey`、q/ns/sort/page/size/resultCount），然后：

```java
usageTaskExecutor.execute(() -> recordSearch(event));
```

`SkillSearchAppService` 增加可选 `SkillUsageRequestContext` + `actorKey` 参数（或一个 `UsageAttribution` 参数对象）。Controller / `CliSkillAppService` / `ClawHubCompatAppService` / `ClawHubRegistryFacade.search` 在调用前用 `UsageContextFactory` 填好。Factory 拿不到 request（测试直接调 service）→ context 空 → **不提交任务**。

**不要**在各个 Controller 再发一遍事件。

#### 5.7.4 打开详情（VIEW）

**只**在 `SkillController.getSkillDetail` 成功组装 DTO 后 `usageTaskExecutor.execute`。

**不计 VIEW：**

- `ClawHubRegistryFacade.getSkill`
- Review 详情
- `GET .../versions`、files、resolve、download
- 任何内部 / 无 context 调用

同一 `actor_key` + skill 30 分钟去重。无 `source=review` 字段（review 根本不打点）。

#### 5.7.5 上传 / 修改 / 发布

**`SkillPublishedEvent` 必须保持三参构造可编译。** 现网所有生产与测试都是 `new SkillPublishedEvent(skillId, versionId, publisherId)`。改成：

```java
public record SkillPublishedEvent(
    Long skillId,
    Long versionId,
    String publisherId,
    SkillUsageRequestContext requestContext  // nullable
) {
    public SkillPublishedEvent(Long skillId, Long versionId, String publisherId) {
        this(skillId, versionId, publisherId, null);
    }
}
```

`SearchIndexEventListener` / `NotificationEventListener` **只读前三字段**，不必改逻辑。测试里继续用三参即可。

**`PUBLISH` 挂钩（PR 3 必须改到的发射点，缺一不可）。共四条路径，不要把 confirm 误写成 `publishVersion` 的调用方：**

| # | 文件 | 方法 | 典型调用方 | `publisherId` | HTTP context？ |
|---|---|---|---|---|---|
| 1 | `SkillPublishService` | 直发进入 `PUBLISHED` 的分支（约 L568） | `SkillPublishController`、CLI/compat publish、`SkillLifecycleAppService.rerelease` 下游 | 上传者 | 有则传入 |
| 2 | `SkillPublicationService.publishVersion` | L85 `publishEvent` | **仅** `ReviewService`（审核通过）、`SecurityScanService`（扫描 SAFE 自动发） | `actorId`（审核员 / `version.createdBy`） | 审核有 HTTP（经 Review 调用链传入）；扫描回调通常无 |
| 3 | `PromotionService` | 约 L258 `publishEvent`（提升通过、**新建目标 skill**） | `PromotionPortalAppService` | `reviewerId` | 有 HTTP |
| 4 | `SkillReviewSubmitService.confirmPublish` | 在现有 `skillRepository.save(skill)` **之后**新增 `publishEvent`（约 L145–153 指针更新之后） | `SkillLifecycleController` → `GovernanceWorkflowAppService` → `SkillLifecycleAppService.confirmPublish` | `actorUserId`（确认人） | 有 HTTP；必须从 controller 传到 domain |

**不要**把 `confirmPublish` 改道 `SkillPublicationService.publishVersion`。`publishVersion` 会跑 slug 重名守卫、应用 `requestedVisibility` / parsed metadata、清除 `autoPublishOnScanPass`；confirm 今天**故意不做**这些。改道是生命周期语义变更，不在本用量项目范围内。

**`confirmPublish` 必须成为第四个 `SkillPublishedEvent` 发射点（方案 a，最小行为变化）：**

```java
// SkillReviewSubmitService
@Transactional
public void confirmPublish(Long skillId, Long versionId, String actorUserId,
                           Map<Long, NamespaceRole> userNamespaceRoles,
                           SkillUsageRequestContext requestContext) {  // 新增可空 trailing
    // ... 现有校验、set PUBLISHED、latestVersionId、save 不变 ...
    eventPublisher.publishEvent(
            new SkillPublishedEvent(skill.getId(), version.getId(), actorUserId, requestContext));
}

// 保留现有 4 参 overload：调 requestContext=null，兼容 SkillReviewSubmitServiceTest
public void confirmPublish(Long skillId, Long versionId, String actorUserId,
                           Map<Long, NamespaceRole> userNamespaceRoles) {
    confirmPublish(skillId, versionId, actorUserId, userNamespaceRoles, null);
}
```

调用链（PR 3 缺一不可）：

| 层 | 今日签名 | PR 3 |
|---|---|---|
| `SkillLifecycleController.confirmPublish` | 已有 `HttpServletRequest` | `usageContextFactory.fromRequest(httpRequest, userId).requestContext()` 传入 AppService |
| `GovernanceWorkflowAppService.confirmPublish` | 转发 `AuditRequestContext` | 增加可空 `SkillUsageRequestContext` 并原样下传 |
| `SkillLifecycleAppService.confirmPublish` | 调 4 参 domain + 写 `audit_log.CONFIRM_PUBLISH` | 把 context 传入 5 参 `confirmPublish`；**audit 仍写**，与 usage `PUBLISH` 不互相替代 |
| `SkillReviewSubmitService.confirmPublish` | 无 `publishEvent` | 指针更新后发四字段 `SkillPublishedEvent` |

未传 context 的测试 / 内部调用仍记 `PUBLISH`（`client=UNKNOWN`），与 Key Decision 13 一致。`SearchIndexEventListener` / `NotificationEventListener` 会开始收到这条事件——这是 **有意对齐**：PRIVATE 确认发布今天也不重建搜索索引、不发「已发布」通知；补上事件后与审核通过/直发一致。若测试断言「confirm 零 `SkillPublishedEvent`」，改为断言发出一次。

**不要**在 `ReviewPortalAppService` / `NotificationEventListener` / `SearchIndexEventListener` 再记一份 `PUBLISH`（会双计）。审核通过的 usage 以 `SkillPublicationService` 发出的那一次 `SkillPublishedEvent` 为准；confirm 的 usage 以 `confirmPublish` 自己发出的那一次为准；`audit_log.REVIEW_APPROVE` / `CONFIRM_PUBLISH` 仍由现网服务写。

**Context 传递（可选，缺省不得跳过 PUBLISH）：**

- `SkillPublicationService.publishVersion(Skill, SkillVersion, String actorId)` **保留**；新增 overload  
  `publishVersion(skill, version, actorId, SkillUsageRequestContext ctx)`，三参 overload 调 `ctx=null`。
- `ReviewService` 审核通过：若该 HTTP 路径拿得到 factory context 则走四参 `publishVersion`；拿不到则保持三参（仍记 PUBLISH，`UNKNOWN`）。
- `PromotionService` 的 approve 方法同样增加可空 `SkillUsageRequestContext`；`PromotionPortalAppService` 从 `UsageContextFactory` 传入。
- Web / CLI / Compat 调 `publishFromEntries` 时传入 context（`SkillPackageAcceptedEvent` 与直发的 `SkillPublishedEvent` 共用）。**禁止只加领域 overload 不改 HTTP 调用方**（否则交互上传全是 `client=UNKNOWN`，管理员 `?client=WEB` 看不到主路径）。
- 现网 5/6 参 overload **保留**，新增 trailing 可空 `SkillUsageRequestContext`。`BuiltinSkillInitializer` 继续走无 context overload（记 UPLOAD/PUBLISH，`client=UNKNOWN`）。
- `SecurityScanService` 扫描自动发布：保持三参 `publishVersion`，`requestContext=null`。
- **`confirmPublish` 不调用 `publishVersion`。** 只在本方法末尾 `publishEvent`。

**`publishFromEntries` HTTP 调用方（PR 3 文件清单必含）：**

| 调用方 | 今日调用 | PR 3 |
|---|---|---|
| `SkillPublishController.publish` | `publishFromEntries(ns, entries, principal.userId(), visibility, roles, confirmWarnings)` | 追加 `usageContextFactory.fromRequest(request, principal.userId()).requestContext()`（需给方法加 `HttpServletRequest`，与 download 相同） |
| `CliSkillAppService.publish` | 6 参 `..., confirmWarnings=false` | 追加从 `HttpServletRequest` 组装的 context；`CliSkillController` 发布入口把 request 传进 AppService（若今日已有 request 则只改 AppService） |
| `ClawHubCompatAppService.publish` / `publishSkill` | `publishFromEntries(...)` 两处（约 L297、L316） | 追加 factory context，`client=COMPAT` |
| `SkillLifecycleAppService.rereleaseVersion` | `rereleasePublishedVersion(...)` | `rerelease` 同样增加可空 context，从 `AuditRequestContext` 所在请求的 factory 传入 |
| `BuiltinSkillInitializer` | `publishFromEntries` 无 HTTP | **保持现 overload，context=null** |

领域：

```java
// 保留全部现有 overload；新 overload 只多最后一个可空参数
public PublishResult publishFromEntries(..., boolean confirmWarnings,
        SkillUsageRequestContext requestContext);

private PublishResult publishFromEntriesInternal(..., SkillUsageRequestContext requestContext);
```

`requestContext` 写入 `SkillPackageAcceptedEvent` 与直发 `SkillPublishedEvent`。测试未传则走旧 overload → `null`。

**`PUBLISH` listener 规则（与 DOWNLOAD 相反）：**

```
onPublished(@TransactionalEventListener(AFTER_COMMIT) SkillPublishedEvent e):
  actorUserId = e.publisherId()          // 即使 context == null
  actorKey    = "user:" + publisherId    // publisherId 空白则 actorKey = "user:unknown"，仍记一行
  client / authMethod = context != null ? context : (UNKNOWN, UNKNOWN)
  不要因为 context == null 而 return      // 审核通过 / 扫描自动发 / 提升 都是真人业务，不是 bootstrap
  try { recorder.record(PUBLISH, ...) } catch (Exception ex) { metric; }
```

`BuiltinSkillInitializer` 等启动种子若也会走到 `SkillPublishedEvent`：v1 **仍然记 `PUBLISH`**（`publisherId` 多为系统账号）。量可忽略。若以后要排除，用 `UserAccount.systemAccount` 显式过滤，**禁止**用「context==null」当 bootstrap 信号。

**`SkillPackageAcceptedEvent`（UPLOAD / UPDATE）** 只从「接受了一个包 / 复制出了一个新 version 行」的路径发：

```java
public record SkillPackageAcceptedEvent(
    Long skillId, Long versionId, Long namespaceId, String publisherId,
    boolean createdNewSkill, String version, String status,
    SkillUsageRequestContext requestContext  // nullable
) {}
```

| 路径 | createdNewSkill | action |
|---|---|---|
| `SkillPublishService.publishFromEntries` 新建 slug | true | `UPLOAD` |
| 同一 slug 追加 version / rerelease 新 version 行 | false | `UPDATE` |
| `PromotionService` 新建目标 skill + version | true | `UPLOAD`（目标 skill；另有 `PUBLISH`） |

`SkillPackageAcceptedEvent` 的 listener **同样** `AFTER_COMMIT`，`context==null` 时仍用 `publisherId` 记 UPLOAD/UPDATE。

yank / hide / archive / 删版本：**不写 usage**。  
Compat 发布只走领域一次，避免 `COMPAT_PUBLISH` audit 之外再走第二套 usage 入口。  
Web 发布 **不**补 `audit_log`。

#### 5.7.6 ClawHub / CLI 对照

| 入口 | 行为 |
|---|---|
| `SkillSearchAppService`（含 CLI `searchInstallableLatest`、compat、registry search） | 一条 `SEARCH`，`client` 来自调用方 factory |
| `GET /api/v1/download` 302 | 不计 DOWNLOAD |
| 落地 `SkillDownloadService`（含 tag、cli、web） | 一次 DOWNLOAD，`delivery=presigned\|bundle` |
| Deep-link **issue + token resolve** | resolve 记 DOWNLOAD + `delivery=deeplink` + **issuer** 身份 |
| Deep-link **issue fallback**（无 token） | **不是** deeplink；随后 `GET /api/cli/v1/skills/.../download` 按普通 CLI 计，fetcher 归因 |
| Compat / Web / CLI publish | `SkillPackageAcceptedEvent` 一次；HTTP 调用方必须传 `SkillUsageRequestContext` |

#### 5.7.7 `usageTaskExecutor`

在 `AsyncConfig` **新增** bean，PR 3 落地：

```java
@Bean(name = "usageTaskExecutor")
public Executor usageTaskExecutor(SkillHubMetrics metrics) {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(2);
    ex.setMaxPoolSize(8);
    ex.setQueueCapacity(1000);
    ex.setThreadNamePrefix("skillhub-usage-");
    ex.setRejectedExecutionHandler((r, e) -> metrics.incrementUsageRejected());
    // AbortPolicy 会抛到请求线程；必须 Discard + metric
    // RejectedExecutionHandler 内只 increment，丢弃任务
    return ex;
}
```

使用 `DiscardPolicy` 语义：拒绝时 **不**跑任务、**不**抛到搜索线程。实现为自定义 handler：`skillhub.usage.executor.rejected` + discard。

禁止 SEARCH/VIEW 使用 `skillhubEventExecutor`。单测：`SkillUsageRecordingListener` / search 打点方法的 `@Async` value ≠ `skillhubEventExecutor`。

### 5.8 读路径与聚合

#### 5.8.1 公开读

现有 `downloadCount` **不变**，继续读 `skill.download_count`。搜索 SQL **不** join usage 表。

#### 5.8.2 Rollup 任务（可实施规格）

`SkillUsageRollupTask`：`fixedDelay = 60_000`（**上一轮结束后**再等 60s）。多实例用 `skill_usage_job_state` 抢锁，**不**引入 ShedLock 依赖。

**锁 TTL 必须长于一次 job 预算，且必须在 `finally` 释放。** 50s lease + 从不 `locked_until = NULL` 会在慢全表 rollup（>50s）时让第二实例（或同实例下一轮若第一轮还在另一节点跑）并发跑同一条 `INSERT … ON CONFLICT`。数据不会坏，但是浪费，且锁并未「罩住整次 job」。

```sql
-- 抢锁（独立短事务立刻提交，避免包住整次 rollup）
UPDATE skill_usage_job_state
SET locked_until = NOW() + INTERVAL '5 minutes',
    locked_by    = :instanceId,
    updated_at   = NOW()
WHERE job_name = 'rollup'
  AND (locked_until IS NULL OR locked_until < NOW());
-- 若 updated row count = 0：直接 return
```

- Lease = **5 minutes**（≥ 一次全表 daily+rollup 的超时预算）。不要用 50s。
- Job 主体（daily INSERT + rollup INSERT）在锁事务**之外**跑。
- `finally`（成功、失败、超时都要）：

```sql
UPDATE skill_usage_job_state
SET locked_until = NULL,
    locked_by    = NULL,
    updated_at   = NOW()
WHERE job_name = 'rollup'
  AND locked_by = :instanceId;
```

- 进程被 kill、来不及 `finally`：最多占锁 5 分钟，之后 `locked_until < NOW()` 可再抢。
- `instanceId` = `hostname + '-' + management.server.port`（或 Spring `ContextId`），避免两节点误释放对方的锁。
- 不假设单实例。条件 UPDATE + 长 TTL + owner `finally` 释放即可。
- 单测：抢锁 SQL 含 `5 minutes`（或配置 `skillhub.usage.rollup-lock-ttl: 5m`）；`finally` 在 recorder 抛异常后仍执行 unlock；他实例 `locked_by` 不同则 unlock 影响 0 行。

UTC 日历：

- `today = (NOW() AT TIME ZONE 'UTC')::date`
- 重算闭区间：`from_day = today - 2`（含）到 `today`（含，今日为部分桶，允许被下次覆盖）
- **禁止**用「`occurred_at >= now()-2d` 然后整行替换当天」——那会把昨天写成部分桶。按 **UTC 日**过滤：

```sql
INSERT INTO skill_usage_daily AS d (skill_id, day, action, event_count, unique_actors, updated_at)
SELECT e.skill_id,
       (e.occurred_at AT TIME ZONE 'UTC')::date AS day,
       e.action,
       COUNT(*)::bigint,
       COUNT(DISTINCT e.actor_key)::bigint,
       NOW()
FROM skill_usage_event e
WHERE e.skill_id IS NOT NULL
  AND e.action IN ('DOWNLOAD', 'VIEW', 'UPLOAD', 'UPDATE', 'PUBLISH')
  AND (e.occurred_at AT TIME ZONE 'UTC')::date BETWEEN :from_day AND :today
GROUP BY 1, 2, 3
ON CONFLICT (skill_id, day, action) DO UPDATE SET
  event_count   = EXCLUDED.event_count,
  unique_actors = EXCLUDED.unique_actors,
  updated_at    = NOW();
```

`watermark` 更新为本次 `from_day` 的 UTC 零点（仅作观测，重算窗口写死「今天往回 2 个 UTC 日」，避免脏水位）。

**rollup 精确 SQL**（unique 不走 daily、不走事件）。**禁止** `FROM skill s`：那会全表扫无用量 skill。v1 驱动集必须是 actor ∪ daily：

```sql
INSERT INTO skill_usage_rollup AS r (
    skill_id, view_count, unique_downloaders, unique_viewers,
    upload_count, update_count, last_downloaded_at, last_viewed_at, updated_at
)
SELECT
    s.id,
    COALESCE(v.event_count, 0),
    COALESCE(ud.cnt, 0),
    COALESCE(uv.cnt, 0),
    COALESCE(up.event_count, 0),
    COALESCE(upd.event_count, 0),
    dl.last_at,
    vw.last_at,
    NOW()
FROM (
    SELECT skill_id AS id FROM skill_usage_actor
    UNION
    SELECT skill_id FROM skill_usage_daily
) s
LEFT JOIN (
    SELECT skill_id, SUM(event_count) AS event_count
    FROM skill_usage_daily WHERE action = 'VIEW' GROUP BY skill_id
) v ON v.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, SUM(event_count) AS event_count
    FROM skill_usage_daily WHERE action = 'UPLOAD' GROUP BY skill_id
) up ON up.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, SUM(event_count) AS event_count
    FROM skill_usage_daily WHERE action = 'UPDATE' GROUP BY skill_id
) upd ON upd.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, COUNT(*) AS cnt FROM skill_usage_actor
    WHERE action = 'DOWNLOAD' GROUP BY skill_id
) ud ON ud.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, COUNT(*) AS cnt FROM skill_usage_actor
    WHERE action = 'VIEW' GROUP BY skill_id
) uv ON uv.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, MAX(last_at) AS last_at FROM skill_usage_actor
    WHERE action = 'DOWNLOAD' GROUP BY skill_id
) dl ON dl.skill_id = s.id
LEFT JOIN (
    SELECT skill_id, MAX(last_at) AS last_at FROM skill_usage_actor
    WHERE action = 'VIEW' GROUP BY skill_id
) vw ON vw.skill_id = s.id
ON CONFLICT (skill_id) DO UPDATE SET
    view_count = EXCLUDED.view_count,
    unique_downloaders = EXCLUDED.unique_downloaders,
    unique_viewers = EXCLUDED.unique_viewers,
    upload_count = EXCLUDED.upload_count,
    update_count = EXCLUDED.update_count,
    last_downloaded_at = EXCLUDED.last_downloaded_at,
    last_viewed_at = EXCLUDED.last_viewed_at,
    updated_at = NOW();
```

v1 **只**用上面这条 union 驱动 SQL。不要再写一份 `FROM skill s` 变体。

`POST /api/v1/admin/usage-events/rebuild-rollup?skillId=`（可空=全量）：`SUPER_ADMIN`，5 分钟一次。`skillId` 非空时在 daily/actor/rollup SQL 加 `AND skill_id = :id`。unique 重建：**禁止** `DELETE skill_usage_actor` 后再从已 purge 的事件回填。rebuild 只重跑 daily + 从 **现存** actor 汇总 rollup。提供可选 `rebuildActors=true` 仅当运维确认事件完整时：

```sql
INSERT INTO skill_usage_actor (skill_id, action, actor_key, first_at, last_at, last_client)
SELECT skill_id, action, actor_key, MIN(occurred_at), MAX(occurred_at),
       (ARRAY_AGG(client ORDER BY occurred_at DESC))[1]
FROM skill_usage_event
WHERE skill_id IS NOT NULL AND action IN ('DOWNLOAD','VIEW')
GROUP BY 1,2,3
ON CONFLICT (skill_id, action, actor_key) DO UPDATE SET
  first_at = LEAST(skill_usage_actor.first_at, EXCLUDED.first_at),
  last_at = GREATEST(skill_usage_actor.last_at, EXCLUDED.last_at),
  last_client = EXCLUDED.last_client;
```

#### 5.8.3 Query Repository

`SkillUsageQueryRepository`：

- `loadRollup(skillId)` + `loadDaily(skillId, fromDay, toDay)`
- `pageActors`：见 5.9.2 SQL
- `pageEvents`：作者字段投影
- `pageAdminEvents`：动态 SQL，类注释对齐 `AdminAuditLogAppService`

**作者治理时间线：v1 明确不做。** 不写 UNION。后续若做，必须：

- `audit_log.target_type = 'SKILL' AND target_id = :skillId`
- `target_type = 'SKILL_VERSION' AND target_id IN (SELECT id FROM skill_version WHERE skill_id = :skillId)`；版本已硬删则 **丢弃** 该 audit 行（或仅当 `detail_json` 含 skillId 时保留——v1 的 json 没有这个字段，故丢弃）
- action 白名单 = `GovernanceWorkbenchAppService.ACTIVITY_ACTIONS` **加上** `YANK_SKILL_VERSION`、`DELETE_SKILL_VERSION`
- `UNION ALL` + `ORDER BY ts DESC LIMIT 50`

### 5.9 API 草案

统一 `ApiResponse<T>` / `PageResponse<T>`。改完 `make generate-api`（需 **正在运行的后端** `:8080/v3/api-docs`）。

#### 5.9.1 公开

`downloadCount` 契约不改。不公开浏览量。

#### 5.9.2 作者 / 命名空间管理员

鉴权：登录。授权（后端，按顺序）：

1. `skill.ownerId == userId`，或
2. 该 namespace `OWNER` / `ADMIN`，或
3. 平台 `SKILL_ADMIN` / `SUPER_ADMIN`

**不含 `AUDITOR`。** 审计员用 5.9.3。

前端入口：详情 `canManageLifecycle == true`（与 1+2 对齐）。`SKILL_ADMIN` / `SUPER_ADMIN` 即使 `canManageLifecycle` 为 false，也可直接打开 URL（后端放行）；导航上 **不** 为他们单独加作者页入口，他们用 `/admin/usage`。

```
GET /api/web/skills/{namespace}/{slug}/usage
```

```json
{
  "skillId": 1,
  "downloadCount": 1280,
  "uniqueDownloaders": 340,
  "viewCount": 5120,
  "uniqueViewers": 890,
  "uploadCount": 1,
  "updateCount": 6,
  "lastDownloadedAt": "2026-08-18T10:00:00Z",
  "lastViewedAt": "2026-08-18T11:00:00Z",
  "uniqueWindow": "LIFETIME",
  "daily": [
    { "day": "2026-08-01", "downloads": 10, "views": 40, "uniqueDownloaders": 8 }
  ]
}
```

- `downloadCount` ← `skill` 行。
- unique_* ← rollup ← `skill_usage_actor`（lifetime）。**不要**返回误导性的 `windowDays: 180`。
- `daily`：最近 **30 个 UTC 日**（含今天），缺日补 0。`daily[].uniqueDownloaders` 是 **当日** distinct（来自 `skill_usage_daily`），与 lifetime unique 不同。

```
GET /api/web/skills/{namespace}/{slug}/usage/actors
  ?page=0&size=20
  &action=DOWNLOAD
```

`action` ∈ {`DOWNLOAD`,`VIEW`}，缺省 `DOWNLOAD`。其它值（含 `SEARCH`）→ `400` + `error.usage.action.unsupported`。无数据 → 空 `items`、`total=0`。

`client` = 该 actor **最后一次**该 action 的 client（`skill_usage_actor.last_client`，由 recorder 在 upsert 时写入）。

```sql
SELECT a.actor_key,
       a.last_client AS client,
       CASE WHEN a.actor_key LIKE 'user:%' THEN SUBSTRING(a.actor_key FROM 6) END AS user_id,
       ua.display_name,
       a.first_at,
       a.last_at,
       COALESCE(ev.event_count, 0) AS event_count
FROM skill_usage_actor a
LEFT JOIN user_account ua
  ON ua.id = CASE WHEN a.actor_key LIKE 'user:%' THEN SUBSTRING(a.actor_key FROM 6) END
LEFT JOIN (
    SELECT actor_key, COUNT(*) AS event_count
    FROM skill_usage_event
    WHERE skill_id = :skillId AND action = :action
    GROUP BY actor_key
) ev ON ev.actor_key = a.actor_key
WHERE a.skill_id = :skillId AND a.action = :action
ORDER BY a.last_at DESC, a.actor_key ASC
LIMIT :limit OFFSET :offset
```

`event_count` 在事件 purge 后会偏小；UI 以 `firstAt`/`lastAt` 为主，次数旁注「保留期内」。分页稳定序：`last_at DESC, actor_key ASC`。

```
GET /api/web/skills/{namespace}/{slug}/usage/events
  ?page=0&size=20&action=&from=&to=
```

作者可见：无 IP/UA；payload 按 §5.4.1 allow-list。`action=SEARCH` → 400。

#### 5.9.3 管理员

```
GET /api/v1/admin/usage-events
  ?page=0&size=20&userId=&action=&skillId=&namespaceId=&client=&requestId=&ipAddress=&startTime=&endTime=
```

`@PreAuthorize("hasAnyRole('AUDITOR','SUPER_ADMIN','SKILL_ADMIN')")`

- `SKILL_ADMIN`：必须带 `skillId` 或 `namespaceId`，否则 `400` `error.usage.filter.scopeRequired`。响应中 **去掉** `clientIp`、`userAgent`。
- `AUDITOR` / `SUPER_ADMIN`：允许全表分页；含 IP/UA。

```
POST /api/v1/admin/usage-events/rebuild-rollup
```

仅 `SUPER_ADMIN`。与列表同一 PR 出 OpenAPI。

v1 无 CSV。

作者 usage 路径是 **浏览器 session 专用**。现有 `ApiTokenPolicy.allow(HttpMethod.GET, "/api/web/skills/**")`（`requiredScope == null` → **直接 allow**）会 first-match 到 `/api/web/skills/{ns}/{slug}/usage`。仓库里 **没有** `ApiTokenPolicy.unsupported(...)`；`require(..., "nobody-has")` 得到的是 `missingScope`，不是测试要断言的 `unsupported` / `cannot access`。

**必须新增 deny 工厂**（`RouteSecurityPolicyRegistry` 内部 `ApiTokenPolicy` record）：

```java
private record ApiTokenPolicy(HttpMethod method, String pattern, String requiredScope, boolean deny) {
    static ApiTokenPolicy allow(HttpMethod method, String pattern) {
        return new ApiTokenPolicy(method, pattern, null, false);
    }
    static ApiTokenPolicy require(HttpMethod method, String pattern, String requiredScope) {
        return new ApiTokenPolicy(method, pattern, requiredScope, false);
    }
    /** First-match deny → ApiTokenAuthorizationDecision.unsupported(path). */
    static ApiTokenPolicy deny(HttpMethod method, String pattern) {
        return new ApiTokenPolicy(method, pattern, null, true);
    }
}
```

`authorizeApiToken` 循环里，命中后：

1. `policy.deny() == true` → **立即** `return ApiTokenAuthorizationDecision.unsupported(path)`（message 已含 `"API token cannot access endpoint: "`）。
2. 否则沿用现逻辑：`requiredScope == null` 或 scope 命中 → allow；否则 `missingScope`。

**`API_TOKEN_POLICIES` 插入位置：必须写在 `allow(GET, "/api/web/skills/**")` 之前**（first-match）：

```java
ApiTokenPolicy.deny(HttpMethod.GET, "/api/web/skills/*/*/usage"),
ApiTokenPolicy.deny(HttpMethod.GET, "/api/web/skills/*/*/usage/**"),
ApiTokenPolicy.allow(HttpMethod.GET, "/api/web/skills"),
ApiTokenPolicy.allow(HttpMethod.GET, "/api/web/skills/**"),
```

Session 侧（`AUTHORIZATION_POLICIES`，与 token 列表独立）增加：

```java
RouteAuthorizationPolicy.authenticated(HttpMethod.GET, "/api/web/skills/*/*/usage"),
RouteAuthorizationPolicy.authenticated(HttpMethod.GET, "/api/web/skills/*/*/usage/**"),
```

管理员 `GET|POST /api/v1/admin/usage-events/**` 已被 `authenticated(..., "/api/v1/admin/**")` 覆盖；token 列表没有 `/api/v1/admin/**` allow，未匹配则已是 `unsupported`。不必再加 deny。

`RouteSecurityPolicyRegistryTest` **必须**断言：

```java
var decision = registry.authorizeApiToken("GET", "/api/web/skills/global/demo/usage", Set.of());
assertThat(decision.allowed()).isFalse();
assertThat(decision.message()).contains("cannot access");

decision = registry.authorizeApiToken("GET", "/api/web/skills/global/demo/usage/actors", Set.of("skill:publish"));
assertThat(decision.allowed()).isFalse();
assertThat(decision.message()).contains("cannot access");
```

以及 session 策略：上述两 path 的 `AccessLevel.AUTHENTICATED`。

### 5.10 前端

`web/src/features/usage/`。

- 详情：全员仍只看 `downloadCount`。`canManageLifecycle` 为 true 时显示独立下载 / 浏览，链到 `/skills/$namespace/$slug/usage`。
- 作者页：汇总 + `daily` 30 日 + 「谁用过」。
- 管理：`/admin/usage`，**不要**混进 `audit-log` 过滤器。AUDITOR / SUPER_ADMIN / SKILL_ADMIN 可进。

**必写文案（中英）：**

- `usage.hint.downloadVsUnique`（zh）：「下载次数统计每一次成功下载；同一用户短时间重复下载会计多次。独立下载用户按去重后的使用者计算，与下载次数不同。」
- `usage.hint.downloadVsUnique`（en）：`Download count increases on every successful download, including repeats from the same user. Unique downloaders are de-duplicated and will be lower.`
- `usage.actor.anonymous`：匿名用户
- `usage.hint.uniqueLifetime`：独立用户为上线以来累计，不随明细过期减少。

隐私页：匿名用量身份 **复用现有下载限流 cookie**（`skillhub_anon_dl`），不是「HMAC(IP+UA)」作为主方案。无 cookie 的 CLI 请求使用 IP+UA 的 HMAC 作为后备指纹。

Query keys：`['skills', ns, slug, 'usage']` 等。

### 5.11 Metrics

`SkillHubMetrics` 增加（禁止 `userId`/`skillId` 当 tag）：

- `skillhub.usage.recorded{action,client}`
- `skillhub.usage.dedup_hit{action}`
- `skillhub.usage.record.failure{action}`
- `skillhub.usage.executor.rejected`
- `skillhub.usage.retention.pii_cleared` / `skillhub.usage.retention.deleted`

### 5.12 配置

```yaml
skillhub:
  usage:
    enabled: true
    view-dedup-ttl: 30m
    download-dedup-ttl: 15m
    search-dedup-ttl: 5m
    pii-retention-days: 30
    event-retention-enabled: false          # 默认只擦 PII，不删行
    event-retention-days: 180               # 仅当 enabled=true
    rollup-lock-ttl: 5m                     # 必须 > 一次 job 预算；finally 释放
    executor:
      core-size: 2
      max-size: 8
      queue-capacity: 1000
```

匿名 cookie 绑定在 **已有** `DownloadRateLimitProperties`（`prefix = "skillhub.ratelimit.download"`），与 `application.yml` 里现网键一致。**不要**写 `skillhub.download-rate-limit.*`。

```yaml
skillhub:
  ratelimit:
    download:
      anonymous-cookie-name: ${SKILLHUB_DOWNLOAD_ANON_COOKIE_NAME:skillhub_anon_dl}
      anonymous-cookie-max-age: ${SKILLHUB_DOWNLOAD_ANON_COOKIE_MAX_AGE:P30D}
      anonymous-cookie-secret: ${SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET:}
      anonymous-cookie-previous-secret: ${SKILLHUB_DOWNLOAD_ANON_COOKIE_PREVIOUS_SECRET:}
```

`DownloadRateLimitProperties` 新增 `anonymousCookiePreviousSecret`（可空）。`AnonymousDownloadIdentityService.parseAndVerify`：先 `sign(id, currentSecret)`，失败再用 `previousSecret`（非空时）。fallback HMAC（无 cookie 的 CLI）同样 current 然后 previous。`previous` 空则不双算。`secret.yaml.example` / `application-local.yml` 只加这一条可选键。

同文件必须新增 **`peekCookieHash(HttpServletRequest)`**（见 §5.5）：只验签、不 mint。`UsageContextFactory` 的 SEARCH/VIEW/下载归因走 peek，**禁止**为了拿 `cookieHash` 去调 `resolve()`。

---

## 6. Consistency, privacy, retention, volume

### 一致性

- 公开下载数：同步、与现网一致；与 unique **刻意**不同。
- 明细：DOWNLOAD 同步新事务；SEARCH/VIEW 异步可丢。
- unique：与成功 `record()` 同事务写入 `skill_usage_actor`，立即准确（作者 actors 列表可读 actor 表，不必等 60s）。rollup 的 unique_* 最多晚 60s。
- 事件删除（若开启）**不得**减少 unique_*。

### 隐私

- 作者不可见 IP/UA/搜索词。
- 匿名主身份 = 已有下载 cookie；CLI 后备 HMAC 不可逆。
- `SKILL_ADMIN` 看不到 IP/UA。
- 账号合并不回写历史 `actor_key`。
- 用户注销不级联删 usage。

### 保留

| 数据 | 默认 |
|---|---|
| 事件 IP/UA | 30 天 UPDATE NULL |
| 事件行 | **默认不删**（`event-retention-enabled=false`）。开启后 180 天 DELETE |
| `skill_usage_actor` / daily / rollup | 随 skill |
| `audit_log` / `download_count` | 不变 |

`SkillUsageRetentionTask` 每天 03:15。分批 1000 行。开启硬删前 **不必**重算 unique。

### 体量

约 1.5 万行/天 × 若开启 180 天 ≈ 270 万行。SEARCH/VIEW 队列 1000，满则丢。DOWNLOAD 在请求线程写明细，失败只打点。

---

## 7. Alternatives considered

| 方案 | 为何拒绝 |
|---|---|
| 扩展 `audit_log` | FK 挡匿名；审计台被淹没；权限/保留不同 |
| 只加计数器 | 无法回答「谁用过」 |
| 去掉 `skill.download_count` 改从事件加 | 搜索排序与前端契约；去重后与现网不一致 |
| 把「谁」堆在 `skill_version_stats` | 该表是 per-version 计数，加用户会变成大宽表或爆炸行数；跨版本 unique 仍要另一张表 |
| 复用 `AnonymousDownloadIdentityService` 只限流、用量另搞 secret | **不采用**。v1 **就是**复用该 cookie；另搞 secret 会让 unique 与限流身份分裂 |
| increment 之后 App 层同步 `recorder.record`，完全不用事件 | DOWNLOAD 可行且与本设计的同步 listener 等价。仍保留领域事件，便于测试与 deep-link 同一入口。SEARCH 不能同步写 |
| 复用 `skillhubEventExecutor` | `CallerRunsPolicy` 会反压搜索；与索引重建抢队列 |
| Kafka / ClickHouse | 超出当前 PG+Redis+MinIO 部署模型 |
| 搜索 hit IMPRESSION | 刷榜与体量 |
| 真 outbox 表 | v1 企业体量不值当；假 outbox（失败才写）救不了 listener 前 crash |
| Micrometer 当账本 | 无「谁」、标签爆炸 |

---

## 8. Key Decisions

1. **新建 `skill_usage_event`，不扩展 `audit_log`。** 审计与用量的体量、匿名、权限、保留都不同。

2. **公开 `download_count` 仍由 `SkillDownloadService` 单写。** unique 的权威是 **`skill_usage_actor`（V46 必上）**，不是事件、也不能把 daily unique 相加当 lifetime。事件可删，unique 不回退。

3. **搜索 query-level，不计入 Skill 使用次数；portal 详情才是 VIEW；下载才是使用。**

4. **按动作拆投递，禁止全局禁用 / 全局使用 AFTER_COMMIT。** DOWNLOAD：无外层事务 → 普通 `@EventListener` + `REQUIRES_NEW`。UPLOAD / UPDATE / PUBLISH：在未提交的发布事务内发事件 → `@TransactionalEventListener(AFTER_COMMIT)`，禁止 `REQUIRES_NEW` 抢跑（否则新 skill FK 失败、listener 异常可能回滚发布）。SEARCH/VIEW：专用 `usageTaskExecutor` + Discard，禁止 `skillhubEventExecutor`。

5. **`UsageAttribution` 必须在 increment 之前就在调用栈上。** HTTP 边传入 `downloadLatest` / `downloadVersion` / `downloadByTag`；`private downloadVersion` 先 `buildDownloadResult` 再算 `delivery=presigned|bundle` 再 increment。禁止 ThreadLocal，禁止在 `buildDownloadResponse` 之后补写 payload。`delivery` **不是** HTTP 302 vs stream。两参 `recordDownloadById` 只给测试/内部拉包。`SkillQueryService` 不下载。`issueDownloadLink` 必须收 `UsageAttribution` 并写入 `DownloadLinkData` issuer 字段；fallback 路径不写 token、按后续 CLI GET 归因。

6. **去重只作用于明细与 unique；公开下载次数不去重。** UI 必须解释。

7. **治理动作不抄 usage。v1 不做作者侧 audit UNION。**

8. **Compat 302 不计下载。** Token 兑现的 deep-link 记签发者（`delivery=deeplink`）。**Issue fallback 是普通 CLI 下载**，记 fetcher，不是 issuer，也不是 `deeplink`。

9. **Domain 只 `SkillUsageRecorder`；Redis / cookie / servlet 留在 app。**

10. **匿名身份复用 `AnonymousDownloadIdentityService` cookie；不新增 `sh_aid` / `USAGE_ANONYMOUS_SECRET`。** SEARCH/VIEW 只调 **`peekCookieHash`（只读，永不 Set-Cookie）**；`resolve()` 仅下载限流 mint。`hash()` 仍是 64 hex SHA-256，与 HMAC fallback 的 32 hex 不同。

11. **IP/UA 仅 `AUDITOR` + `SUPER_ADMIN`。** `SKILL_ADMIN` 看用量但不看 PII。作者 API 不含 `AUDITOR`。

12. **v1 无 outbox、无 `DEVICE_FLOW`。** 用量失败打点即可。

13. **`PUBLISH` 跟全部四条进入 `PUBLISHED` 的生产路径，不是「三个现有 `SkillPublishedEvent` 发射点」。** 现网三处已发事件（`SkillPublishService` 直发、`SkillPublicationService.publishVersion`、`PromotionService`）之外，**必须**让 `SkillReviewSubmitService.confirmPublish` 在指针更新后自己 `publishEvent(new SkillPublishedEvent(..., ctx))`。禁止把 confirm 改道 `publishVersion`（会引入 slug 冲突守卫、`requestedVisibility`、清 `autoPublishOnScanPass`，改变 PRIVATE 确认语义）。`requestContext == null` 仍记（actor=`publisherId`，client=`UNKNOWN`）。禁止把「无 HTTP context」当成 bootstrap。交互上传必须由 `SkillPublishController` / `CliSkillAppService` / `ClawHubCompatAppService` 传入 context；confirm 必须由 `SkillLifecycleController` → AppService 传入 context。

14. **作者 `/usage` 对 API token 用新的 `ApiTokenPolicy.deny`，插在 `GET /api/web/skills/**` allow 之前。** 不存在的 `unsupported()` 工厂不能靠 `require(fake-scope)` 冒充。

15. **`dedup_key` 必须 `VARCHAR(512)`**，以容纳 `user:` + 128 字符 userId；禁止 128。

16. **`SkillUsageRecorder` 只在 `skillId != null && action ∈ {DOWNLOAD, VIEW}` 时 upsert `skill_usage_actor`。** SEARCH 只写 event；UPLOAD/UPDATE/PUBLISH 只写 event。禁止无脑 upsert 导致 SEARCH FK/NOT NULL 把整笔 record 回滚。

---

## 9. Open Questions

已关闭：

- **OQ1 Web `audit_log` 发布**：won't-do。用量走 `UPLOAD`/`UPDATE`；审计台暂不出现 Web 上传。
- **OQ2 匿名 cookie**：复用 `skillhub_anon_dl`，不新增 `sh_aid`。
- **OQ5 等保 vs 删事件**：默认 **只擦 PII、不删行**。硬删是显式运维开关，不是法律默认。

仍开放（需产品拍板，不阻塞 v1 实现）：

1. **作者是否允许看「搜过相关关键字的人」？** 默认否。若要做必须另做 impression + 隐私评审。
2. **公开 `downloadCount` 是否在未来改成独立用户？** 本设计不改；改则需版本公告与排序变更。
3. **审核员打开详情是否计入作者可见浏览量？** v1 审核页不打 VIEW。若要「内部关注度」再加开关。

---

## 10. Risks and mitigations

| 风险 | 缓解 |
|---|---|
| SEARCH/VIEW 队列满 | Discard + `skillhub.usage.executor.rejected`；永不 CallerRuns |
| Redis 宕机去重失效 | PG UNIQUE；非 UNIQUE 的完整性错误不得吞 |
| cookie secret 轮转 | optional previous-secret，仅 lookup 双算；persist 当前 key |
| 作者投诉两个下载数字 | 固定 i18n hint |
| 爬虫刷详情 | 已有匿名限流 + VIEW 30m 去重 + 队列丢弃 |
| 旧 deep-link Redis JSON 缺 issuer 字段 | 无参反序列化 + null-safe；缺省 ANONYMOUS/WEB |
| 本地 fallback 与 Web session 裂成两个 unique | 接受；v1 不把 issuer 放进 CLI URL；文档/测试写明 fallback ≠ deeplink |
| 多实例 rollup 互踩 / 慢任务锁过期 | 条件 UPDATE 锁 **5 分钟** + `finally` 按 `locked_by` 释放 |
| 开启事件删除后 actors.`event_count` 变小 | UI 以 first/last 为主并注明保留期 |

---

## 11. Testing strategy

### 后端

- `SkillUsageRecorderTest`：128 字符 `userId` 的 `actor_key`；**同一 userId 的 DOWNLOAD `dedup_key.length() > 128` 且 insert 成功**；cookieHash / fallback 32 hex；UNIQUE 冲突成功；其它 `DataIntegrityViolation` 失败上抛；**`SEARCH` + `skillId=null` insert event 且 verify actor repo 零次 save/upsert**；`UPLOAD` 同样零次 actor upsert。
- `SkillDownloadServiceTest`：`downloadLatest/downloadVersion/downloadByTag(..., attribution)` 在 PUBLISHED 路径 increment **一次**且事件 `actorKey`/`requestContext` 非空，`delivery` ∈ {`presigned`,`bundle`}（由 `DownloadResult.fallbackBundle` / `presignedUrl` 决定，**禁止**断言 `redirect`/`stream`）；`attribution==null` 仍 increment、事件不可 recordable；draft/review 不计；**禁止**断言三条 public 方法调用了两参 `recordDownloadById`；increment **之前** attribution 已传入（captor 在 `recordPublishedDownload` / 事件上，不是事后补丁）。
- `SkillControllerDownloadTest` / `CliSkillAppServiceTest` / `DownloadRateLimitControllerTest`：download 调用带 `UsageAttribution`。
- `AnonymousDownloadIdentityServiceTest`：`peekCookieHash` 无 cookie / 坏签名 → empty 且 **零** `Set-Cookie`；有效 cookie → 64 hex；`resolve` 无 cookie 仍 mint。
- **禁止**用 `@TransactionalEventListener` 测 DOWNLOAD 主路径。
- **必须**用 `@TransactionalEventListener(AFTER_COMMIT)` 测 UPLOAD/PUBLISH：外层事务未提交时 `REQUIRES_NEW` insert 应 FK 失败（可用集成测试或注释+单测证明 listener 注解是 AFTER_COMMIT）。
- `SkillDownloadLinkServiceTest`：`issueDownloadLink(..., attribution)` 在有 presign 时把 **全部** issuer 字段写入 `DownloadLinkData`；无 presign 时 **零** `save`、**零** `recordDownloadById`，返回 CLI fallback path；旧 payload 无 `issuerUserId` 不 NPE；resolve 调 7 参 `recordDownloadById` 且 `delivery=deeplink`、attribution=issuer。
- `DownloadLinkControllerTest`：`issueDownloadLink` verify 带 `any(UsageAttribution.class)`，不是旧 5 参。
- `SkillUsageRecordingListenerTest`：`onDownloaded` 为同步 `@EventListener`，无 context 则跳过；`onPublished` / `onPackageAccepted` 为 AFTER_COMMIT，**无 context 仍 `record`**；异常不抛出。
- `SkillSearchAppService`：`search` 与 `searchInstallableLatest` 各触发一次异步提交；**service 事务内无 insert**。CLI / compat / registry 不再单独 insert。
- `SkillController` VIEW 一次；`ClawHubRegistryFacade.getSkill` **零** VIEW。
- Query / Admin：作者无 IP；SKILL_ADMIN 无 scope → 400、有 scope 无 IP；AUDITOR 有 IP。
- `RouteSecurityPolicyRegistryTest`：usage 路径 session AUTHENTICATED；API token unsupported。
- `AsyncConfigTest`：存在 `usageTaskExecutor`；拒绝处理器不是 CallerRuns。
- Retention：默认不 DELETE；PII null。
- Compat：302 不 insert；**跟随 Location** 后 `download_count+1` 且 usage 一行。Tag 下载、presign 与 stream 各一行。

### 前端

- Vitest：文案、匿名、`canManageLifecycle` 入口。
- Playwright：作者用量页；无权限 403；`/admin/usage` 过滤。

### Staging smoke（随 API PR）

1. 匿名下载 PUBLIC → count+1，作者 actors 出现匿名行（cookie 稳定则再下一刀仍同一 actor）。
2. 同一登录用户 1 分钟下两次 → 公开 +2，unique +1。
3. `GET /api/v1/download?...` 再 GET Location → 总共 +1 次 count、1 行 usage。
4. CLI search 与 Web search 两条 `client` 不同。

---

## 12. 模块与文件清单

**新增**

- `V46__skill_usage_events.sql`
- `domain/usage/*`（含 `package-info.java`、`SkillUsageRecorder`、`UsageCommand`）
- infra JPA（event / actor / daily / rollup / job_state）
- `usage/UsageContextFactory.java`、`UsageDedupService.java`
- `listener/SkillUsageRecordingListener.java`
- `service/SkillUsageAppService.java`、`AdminUsageAppService.java`
- `repository/SkillUsageQueryRepository.java`
- `controller/portal/SkillUsageController.java`
- `controller/admin/AdminUsageController.java`
- `task/SkillUsageRollupTask.java`、`SkillUsageRetentionTask.java`
- 事件：扩展 `SkillDownloadedEvent`；`SkillPublishedEvent` **保留三参构造** + 可空 context；新增 `SkillPackageAcceptedEvent`、`SkillSearchedEvent`、`SkillViewedEvent`
- `web/src/features/usage/*`、`pages/skill-usage.tsx`、`pages/admin/usage.tsx`

**修改**

- `SkillDownloadService`（三条 public + private `downloadVersion`：先 `buildDownloadResult` 再 increment；`recordPublishedDownload` 传 `UsageAttribution` + `presigned|bundle`）、`SkillController` 三个 download、`CliSkillAppService` 两个 download、`DownloadLinkController.createDownloadLink`、`SkillDownloadLinkService.issueDownloadLink(..., UsageAttribution)` / `resolveForRedirect`、`DownloadLinkStore` / `DownloadLinkData` issuer 字段
- `AnonymousDownloadIdentityService.peekCookieHash` + `parseAndVerify` 双 secret；`AnonymousDownloadIdentityServiceTest`
- `SkillPublishService`（`SkillPackageAcceptedEvent` + 直发 `SkillPublishedEvent` 可空 context；`publishFromEntries` trailing context）
- HTTP 发布调用方：`SkillPublishController`、`CliSkillAppService.publish`、`ClawHubCompatAppService.publish`/`publishSkill`、`SkillLifecycleAppService.rereleaseVersion`；`BuiltinSkillInitializer` 保持 null context
- `SkillPublicationService.publishVersion` 四参加载；`ReviewService`（尽量传 context）/ `SecurityScanService`（保持三参）。**不要**让 `confirmPublish` 调用 `publishVersion`
- `SkillReviewSubmitService.confirmPublish`：5 参 + 4 参 overload；指针更新后发 `SkillPublishedEvent`；`SkillLifecycleController` / `GovernanceWorkflowAppService` / `SkillLifecycleAppService` 下传 factory context；`SkillReviewSubmitServiceTest` 改为断言发出事件
- `PromotionService` + `PromotionPortalAppService`（提升新建目标 skill 的 UPLOAD+PUBLISH）
- `SkillSearchAppService`（唯一 SEARCH 挂钩）
- `SkillController.getSkillDetail`（唯一 VIEW 挂钩）
- `CliSkillAppService` / `ClawHubCompatAppService` / `ClawHubRegistryFacade`：传入 attribution，**不**各自写库
- `AsyncConfig`、`SkillHubMetrics`、`application.yml`（`skillhub.ratelimit.download.anonymous-cookie-previous-secret`）、`DownloadRateLimitProperties`、`AnonymousDownloadIdentityService.parseAndVerify` 双 secret、`secret.yaml.example`
- `RouteSecurityPolicyRegistry`（`ApiTokenPolicy.deny` + usage 两条）+ `RouteSecurityPolicyRegistryTest`
- `web` 详情 / router / i18n / `privacy.tsx`

---

## PR Plan

写路径必须在 **不依赖 UI / 不依赖 generate-api** 的情况下可合并、可验证。PR 1 冻结 `SkillUsageRecorder.record(UsageCommand)`；之后禁止第二套 insert。

### PR 1 — `feat(usage): add usage ledger schema and recorder`

- **Files**: `V46__skill_usage_events.sql`（event / actor / daily / rollup / job_state）；`domain/usage/*`；infra JPA；`SkillUsageRecorder` 单测（含 128 字符 userId）
- **Depends on**: 无
- **Description**: 只加表与领域写入。无 HTTP、无 listener。运行行为为零。

### PR 2 — `feat(usage): record published downloads with request context`

- **Files**:
  - `UsageAttribution` + `SkillDownloadedEvent`（完整字段 + 两参兼容 ctor）
  - `SkillDownloadService`：`downloadLatest` / `downloadVersion` / `downloadByTag` / private `downloadVersion` / `recordPublishedDownload` 均接收 `UsageAttribution`；带字段的 `recordDownloadById`；两参 overload **仅**测试/内部
  - `SkillDownloadServiceTest`（attribution 非空则事件可 recordable）
  - `SkillController` 三个 download 方法 + `SkillControllerDownloadTest`
  - `CliSkillAppService` 两个 download 方法 + `CliSkillAppServiceTest`（`CliSkillController` 签名不用改）
  - `DownloadLinkData` issuer 全字段；`SkillDownloadLinkService.issueDownloadLink(..., UsageAttribution)` + `resolveForRedirect` 走 7 参 `recordDownloadById(..., "deeplink", issuer)`
  - `DownloadLinkController.createDownloadLink`：`fromRequest(request, principal.userId())` 传入 issue（**缺此文件不可合并**）
  - `DownloadLinkControllerTest` / `SkillDownloadLinkServiceTest`（含 fallback 不写 token）
  - `UsageContextFactory`；`AnonymousDownloadIdentityService.peekCookieHash`（及 previous-secret 验签，若本 PR 已加 previous 字段）+ Test；`RateLimitInterceptor` 在 download `resolve` 后 `setAttribute("anonymousDownloadIdentity", identity)`
  - `UsageDedupService`；**同步** `SkillUsageRecordingListener.onDownloaded`；`SkillHubMetrics` 基础计数
  - **不含** `usageTaskExecutor`（下载不走池）
- **Depends on**: PR 1
- **Description**: HTTP 边 factory → 三条下载方法 → `buildDownloadResult` → `delivery=presigned|bundle` → increment + 带 attribution 的事件。Listener 只跳过两参/无 attribution。Issue 必须写入 issuer；token resolve 用 issuer + `deeplink`。Issue fallback **不是** deeplink。compat 302 不计。**禁止**只改 `recordDownloadById` 或只改 issue 不改 `DownloadLinkController` 就合并。**无 outbox。**

### PR 3 — `feat(usage): async search/view and package accepted events`

- **Files**: `AsyncConfig.usageTaskExecutor` + Discard handler + `AsyncConfigTest`；`SkillSearchAppService` 唯一 SEARCH 挂钩；`SkillController` 唯一 VIEW；`SkillPackageAcceptedEvent` + `SkillPublishService`（`publishFromEntries` trailing `SkillUsageRequestContext`）；**HTTP 发布调用方（缺一不可合并）**：`SkillPublishController`、`CliSkillAppService.publish`、`ClawHubCompatAppService.publish`/`publishSkill`、`SkillLifecycleAppService.rereleaseVersion`；`BuiltinSkillInitializer` **保持**无 context overload；`SkillPublishedEvent` 四字段 + **保留三参构造**；`SkillPublicationService.publishVersion` overload；`ReviewService`（四参，有 context 则传）/ `SecurityScanService`（**仍三参**）；**第四发射点（缺则 PRIVATE confirm 无 PUBLISH）**：`SkillReviewSubmitService.confirmPublish` 5 参 + 4 参兼容 overload，**不**调用 `publishVersion`，save 之后 `publishEvent`；`SkillLifecycleController.confirmPublish` / `GovernanceWorkflowAppService.confirmPublish` / `SkillLifecycleAppService.confirmPublish` 下传 factory context；`SkillReviewSubmitServiceTest` 断言一次 `SkillPublishedEvent`；`PromotionService` + `PromotionPortalAppService`；listener：`onDownloaded` 保持普通 `@EventListener`；**`onPackageAccepted` / `onPublished` 必须 `@TransactionalEventListener(AFTER_COMMIT)` 且 catch 全异常**；`onSearched`/`onViewed` 走 `usageTaskExecutor`；CLI/compat/registry **只传 attribution**；`SkillUsageRecorder` SEARCH 路径零 actor upsert（若 PR 1 测试已覆盖则本 PR 加 listener 集成断言）
- **Depends on**: PR 1；建议叠在 PR 2 的 `UsageContextFactory` 上（否则本 PR 重复 factory）
- **Description**: 搜索/详情不反压。UPLOAD/UPDATE/PUBLISH 等外层事务提交后再写 usage。`PUBLISH` 覆盖直发、审核通过、**confirmPublish 自建事件**、扫描自动发、提升。**禁止**把 confirm 接到 `publishVersion`。交互发布必须带 WEB/CLI/COMPAT context；confirm 必须从 `SkillLifecycleController` 传入 context；**禁止只改领域 overload 就合并。** `context==null` 仍记 PUBLISH。禁止 `@Async("skillhubEventExecutor")`。

### PR 4 — `feat(usage): author and admin usage APIs`

- **Files**: Query repo；两个 AppService；`SkillUsageController`；`AdminUsageController`（含 rebuild）；rollup + retention tasks；DTO；`ApiTokenPolicy.deny` + `API_TOKEN_POLICIES` 里 usage 两条（**先于** `GET /api/web/skills/**`）+ `RouteSecurityPolicyRegistryTest`（`cannot access`）；`application.yml` 的 `skillhub.ratelimit.download.anonymous-cookie-previous-secret`；`DownloadRateLimitProperties` / `AnonymousDownloadIdentityService` 双 secret；**在已启动的 dev 后端上** `make generate-api` 并提交 `schema.d.ts`；`scripts/smoke-test.sh` 或新 `scripts/usage-smoke-test.sh`（下载 + Location 跟随）
- **Depends on**: PR 2（至少 DOWNLOAD 可测）；PR 3 可并行但 generate-api 一次做完更干净
- **Description**: 作者汇总/actors SQL/明细；管理员过滤与 PII 分级；任务与 rebuild。

### PR 5 — `feat(usage): skill usage UI for authors and admins`

- **Files**: `web/src/features/usage/*`；`skill-usage.tsx`；`admin/usage.tsx`；`skill-detail.tsx`（`canManageLifecycle`）；`router.tsx`；i18n（含 `usage.hint.downloadVsUnique` 全文）；`privacy.tsx`（下载 cookie）；Vitest + Playwright
- **Depends on**: PR 4（OpenAPI 类型）
- **Description**: 作者用量页与 `/admin/usage`。不把 usage 混进 audit-log。
