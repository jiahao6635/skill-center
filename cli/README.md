# Skill Center CLI

私有 Skill Center 的独立 CLI，npm 包为 `@jiahao6635/skill-center`，命令为 `skill-center`。需要 Node.js 22 或更新版本。

```bash
npm install -g @jiahao6635/skill-center
# 或不做全局安装
npx @jiahao6635/skill-center@latest help
```

默认连接 `https://skill-center.sigmob.com`。业务服务地址与 npm 包仓库是两个独立配置。

## 登录与查找

```bash
skill-center login --browser
# 自动化环境建议使用 SKILL_CENTER_TOKEN，避免令牌进入 shell 历史
skill-center whoami
skill-center search pdf --limit 20
skill-center inspect @global/pdf-parser
skill-center namespaces --page 0 --size 20
```

服务地址优先级：`--registry` → `SKILL_CENTER_REGISTRY` → 旧 `SKILLHUB_REGISTRY` → 已保存配置 → 默认地址。
令牌优先级：`--token` → `SKILL_CENTER_TOKEN` → 旧 `SKILLHUB_TOKEN` → 当前服务的已保存令牌。

`login --token <token>` 可保存令牌；`logout` 只删除当前服务的令牌。兼容旧版 `~/.skillhub/config.json`、`credentials.json` 和 `inventory.json`，无需重新安装已有技能。若旧配置指向上游，请先执行：

```bash
skill-center login --browser --registry https://skill-center.sigmob.com
```

## 安装与本地管理

```bash
skill-center install @my-team/pdf-parser --agent codex --scope user
skill-center install my-team--pdf-parser --dir ./skills --version 1.2.0
skill-center list --json
skill-center remove pdf-parser --agent codex
skill-center doctor
```

支持 `@namespace/slug`、`namespace/slug`、旧 `namespace--slug`，仅提供 slug 时默认 `global`。`--namespace` 可覆盖空间。
保留旧版 Agent 目录识别；`--dir` 可使用任意 Agent 的技能目录，不能与 `--agent` / `--scope` 同用。
安装已有技能的新版本使用 `install ... --force`。`update` 用于上传服务端版本。

## 发布与更新

```bash
skill-center publish ./my-skill --namespace my-team --visibility namespace-only --dry-run
skill-center publish ./my-skill --namespace my-team --visibility namespace-only
skill-center publish ./personal-skill --namespace private --visibility private
skill-center inspect @my-team/my-skill
skill-center update 42 ./my-skill
```

目录自动打包，也可传 ZIP。发布、更新返回 Skill ID 和真实状态，例如 `SCANNING`、`PENDING_REVIEW`、`PUBLISHED`；上传成功不等于已发布。
更新按稳定 Skill ID 定位，继承现有空间和可见范围，不能用更新参数移动技能。包有告警时先阅读错误信息，确认后添加 `--confirm-warnings`。
`publish --dry-run` 是现有服务端包校验，不会上传发布或完成安全扫描；定向更新没有服务端 dry-run 接口，CLI 不提供假的更新预检查。

## 移动

```bash
skill-center move-status 42
skill-center move 42 target-team --dry-run
skill-center move 42 target-team --idempotency-key move-42-20260916
skill-center move-status 42
skill-center withdraw 42 123
```

目标可使用 `move-status` 返回的空间 slug 或 ID。CLI 先预检查，出现告警时需要 `--confirm-warnings`。服务端选择最新可用版本，固定为目标空间成员可见；扫描、审核期间原技能保持可用。
提交返回申请状态和幂等键。网络失败时先查询 `move-status`；确需重试时复用同一幂等键，避免新建申请。`inspect 42` 可在移动后按稳定 ID 查询新坐标。

## 删除与升级 CLI

```bash
skill-center remove @my-team/my-skill --remote --hard
skill-center self-update --check
skill-center self-update
```

远端删除需要权限和显式确认；`--hard` 是旧 CLI 兼容的确认开关，不用于绕过服务端权限。
自更新只查询本包 `@jiahao6635/skill-center`，使用 npm 原生配置与 `.npmrc` 鉴权。识别为 npm 全局安装时自动更新；npx、Bun 或未知安装方式会给出安装命令。
`--npm-registry` / `SKILL_CENTER_NPM_REGISTRY` 可显式指定 npm 源，不影响业务服务地址。

所有命令支持 `--json`。结果写 stdout，错误与浏览器登录进度写 stderr；自动化应同时检查退出码。退出码：1 一般错误，2 鉴权，3 网络，4 文件，5 用法，6 校验失败。

## 开发与发布

开发构建使用 Bun 1.3.13；发布后的 CLI 使用 Node.js，不要求用户安装 Bun。

```bash
bun install --frozen-lockfile
bun run check
# 仓库根目录，执行检查并生成本地 npm 包（不发布）
make pack-cli
```

发布与迁移步骤见 [RELEASE.md](RELEASE.md)。本版本复用原 SkillHub 的安装、目录识别与本地清单实现，重写命令入口、HTTP 请求层和发布路径，并直接适配当前服务端更新/移动接口。保留原有来源与版权说明。

上游来源：[iFLYTEK SkillHub](https://github.com/iflytek/skillhub)。原 SkillHub CLI 部分版权：Copyright 2026 iFlytek Co., Ltd.
