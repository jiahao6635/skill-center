# Skill 使用看板

入口：最高管理员用户菜单 → **Skill 使用统计**，页面 `/admin/skill-usage`。

## 使用方式

- 默认查看最近 30 个北京时间自然日（包含今天）的调用统计；可选今天、7 天、本月、自定义，最长 366 天。
- 搜索姓名或邮箱并选择具体用户，查看其使用的 Skill 和调用次数。同名用户通过邮箱区分，未注册中心账号但已有调用记录的用户也可查询。
- Skill 排行、用户排行均按调用次数降序。Skill 名称搜索仅筛选 Skill 表，不改变概览指标。
- 用户排行的「查看使用情况」切换为该用户的 Skill 排行；「查看明细」打开调用记录面板，可复制会话 ID。筛选与标签页保存在 URL。
- 累计下载、当前收藏为明确关联的中心 Skill 的全站当前计数，不随日期或用户筛选变化。未关联、已删除或同名记录关联多个中心 Skill 时显示 `—`，不把缺失数据解释为零。
- 页面无定时刷新，使用「刷新」获取最新已入库记录；「查询时间」仅是查询执行时间，不表示该时间之前的客户端日志已全部送达。

## 统计口径

数据来自 `skill_invocation_event`，由既有 `(source,event_id)` 唯一约束去重。自动／手动触发均计数；调用不代表任务成功。不采集或展示对话正文。

- 用户按标准化邮箱归组，展示名称优先使用已关联中心账号的当前姓名，其次为全部历史中最近非空的源姓名。不会凭相同邮箱关联多个中心账号。
- Skill 按原始完整 `skill_name` 归组，保留插件前缀，不拆分版本；同名且无法区分来源的记录会合并。中心关联按去除 `plugin:` 或 `@namespace/` 前缀后的名称与 `skill.slug` 全局精确匹配，不考虑命名空间或展示标题。唯一匹配时关联，多项同名或无匹配项时不关联。`unlinked` 表示该分组全部记录均未关联中心，调用次数仍正常统计。
- 使用人数按邮箱去重；活跃会话按 `(email,session_id)` 去重。
- 区间按 `occurred_at`：`from` 包含，`to` 不包含。界面结束日期转换为北京时间次日零点；数据库保持 UTC 时间。
- 排行页和总数使用只读可重复读事务，避免并发入库导致同一次分页查询总数与条目不一致。多个独立接口间不承诺同一快照。
- 下载和收藏只在分组的名称关联能明确解析到唯一中心 Skill ID 时读取，不求和调用记录上的重复关联。

## API

所有以下 GET 接口均要求中心登录会话且角色为 `SUPER_ADMIN`。普通用户、`AUDITOR`、`SKILL_ADMIN`、`USER_ADMIN` 均不可访问；内部上报密钥不能读取。此权限约束也应用于原有调用明细接口。

基础路径 `/api/v1/admin/skill-invocations`：

| 路径 | 参数及响应 data |
| --- | --- |
| `/summary` | 必填 `from,to`，可选 `email,product`；返回 `invocationCount,userCount,skillCount,sessionCount,queriedAt` |
| `/skills` | 同上，加可选 `search,page,size`；分页条目含 `skillName,invocationCount,userCount,lastUsedAt,unlinked,rank,peakCount,downloadCount,starCount` |
| `/users` | 同上，加 `page,size`；分页条目含 `email,name,invocationCount,skillCount,lastUsedAt,rank` |
| `/user-options` | 可选 `search`，按姓名／邮箱字面子串、不区分大小写搜索；返回至多 20 个 `{email,name}`，不受日期过滤 |
| 基础路径 | 复用原有调用明细查询，见对接说明 |

汇总时间区间必须有效且不超过 366 天。`product` 为 `qoder/qoder_ide/qoderwork/unknown` 或省略；邮箱精确匹配，忽略前后空白与大小写。排行默认 `page=0,size=20`，size 范围 1–200；外层遵循标准 ApiResponse，分页为 `{items,total,page,size}`。相同调用次数按原始名称或邮箱稳定排序。`peakCount` 为 Skill 名称搜索前同一日期／用户／客户端筛选下的第一名次数，供横条比较。

## 发布与验证

依赖 V49 账本、V50 名称关联迁移和原有采集转发链路。V50 自动为历史账本记录重新计算名称关联，不新增调用，不回灌客户端历史日志，也不改变调用时间。按名称匹配的本次变更只需更新后端并执行迁移，已有看板可直接显示关联后的下载和收藏；无新增汇总表或定时任务。后续新注册或新增同名技能可在事件重传时刷新对应记录的关联。

前端接口类型通过后端 OpenAPI 生成；也支持利用 `SkillInvocationControllerTest` 的 `SKILL_INVOCATION_OPENAPI_OUTPUT` 导出完整 OpenAPI，在无运行中后端时用本地 `openapi-typescript` 生成。

后端：`SkillUsageStatsControllerTest` 验证权限与参数；`SkillUsageStatsPostgresTest` 在隔离 schema 中验证统计、日期、姓名、同名关联、下载收藏、去重和排序，需要设置 `SKILL_INVOCATION_TEST_JDBC_URL` 指向专用测试 PostgreSQL。

前端：日期和 URL 状态测试、页面交互测试，以及 `web/e2e/admin-skill-usage.spec.ts` 浏览器测试。浏览器测试使用合成接口数据，不代表生产链路验证；可用 `SKILL_USAGE_BROWSER_EXECUTABLE` 指定已安装的测试浏览器。真实验收需将某个已入库用户的排行次数与明细及数据库对账。

## 本次验证记录（2026-09-14）

| 检查 | 结果 |
| --- | --- |
| 看板聚合、权限和原有明细接口专项回归 | 14 项通过，含隔离 PostgreSQL 真实查询及 10 万条合成数据检查 |
| 全量前端单元测试 | 194 个文件，714 项通过 |
| TypeScript、前端生产构建、变更文件 lint | 通过 |
| 生产构建浏览器验收 | 3 项通过：双排行、用户搜索、URL 恢复与返回、面板焦点和 Escape、权限失效清屏、手机和深色模式；使用合成 API 数据 |
| 全量后端回归 | 共 1329 项，4 项既有失败、1 项条件跳过，不能视为全绿 |
| 全量 lint | 原有 `web/src/pages/search.tsx:234` 存在 `searchNavigation` Hook 依赖警告；`--max-warnings 0` 阻挡通过 |
| staging | 后端 JAR 构建完成，Docker daemon 未启动，容器构建及 smoke 未执行完成 |

4 项既有后端失败：`SkillPublishServiceTest` 的三个私有命名空间场景（Mockito 桩不匹配），以及 `LocalDevDataInitializerTest.shouldSeedLocalUsersGlobalMembershipAndSuperAdminRole`（缺少内置 private namespace）。标准 `make test-backend-app` 在 domain 模块失败后停止；为收集后续模块结果，另用 `maven.test.failure.ignore=true` 执行全链路测试，上表按实际失败记录计数，不以该收集命令退出码判定成功。

本机合成数据规模：100,000 条新增事件、500 个邮箱、100 个 Skill、30 天。`EXPLAIN (ANALYZE, BUFFERS)` 显示概览约 140 ms，单用户概览约 0.4 ms，后者命中 `idx_invocation_email_time`；实际 Skill 排行约 87 ms、用户排行约 71 ms、用户候选查询约 60 ms。仅作为本机可行性证据，不作为生产延迟承诺。可设置 `SKILL_USAGE_EXPLAIN=1` 运行可选规模检查，每次创建并清理独立 schema。

GitNexus 已刷新并完成依赖核查；最终已跟踪工作区变更被标为 HIGH（14 个文件、34 个符号、6 条流程，包含工作区原有 AGENTS 改动），涉及共享布局和原有明细读取流程。新增统计仓储的单符号影响分析为 LOW，依赖限定在新控制器／服务／查询仓储。对共享布局和深色模式补充了全量前端及生产构建浏览器回归。未跟踪新文件不包含在该 git diff 汇总中；框架动态注入也无法完全靠图索引判断，因此还结合源码和权限测试核验，不能将索引统计理解为完整风险保证。

本次没有部署生产。发布时同时更新后端与前端，并按 V49 入库前置条件完成真实数据对账。
