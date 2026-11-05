# 独立 npm 发布

包：`@jiahao6635/skill-center`。命令：`skill-center`。发布不再依赖上游仓库、上游 npm 组织或 GitHub Actions。

1. 安装 Node.js 22+、Bun 1.3.13，在 `cli/` 执行 `bun install --frozen-lockfile`。
2. 使用有包发布权限的 npm 账号登录：`npm login`。CI 使用 npm 自身支持的凭据配置；不要把 token 写入仓库。
3. 首次版本为 `1.0.0`。后续在 `cli/` 运行 `npm version patch --no-git-tag-version`（也可 minor/major），把版本变更纳入提交。
4. 仓库根目录执行 `make pack-cli`，完成 lint、类型检查、测试、构建和 Node 冒烟检查，生成 `cli/dist/jiahao6635-skill-center-<version>.tgz`。
5. 检查包内容：`tar -tzf cli/dist/jiahao6635-skill-center-<version>.tgz`。
6. 发布：`make publish-cli`。需要 npm 登录与 scope 权限；npm 若要求双因素验证，按其提示完成。也可发布第 4 步已检查的归档：`npm publish cli/dist/jiahao6635-skill-center-<version>.tgz --access public`。
7. 验证：`npx @jiahao6635/skill-center@latest version`，然后对自己的服务执行登录、搜索、临时目录安装，以及测试空间内的发布/更新/移动。

默认 npm 源遵循本机 npm 配置；发布脚本可用 `NPM_REGISTRY=https://registry.npmjs.org make publish-cli` 覆盖。这个变量不是 Skill Center 服务地址。

发布脚本不提交、不推送、不创建 PR、不打 Git 标签。失败时保留工作区，修复后重试；已经发布的版本不可重复使用，请按 npm 错误提示处理。

## 从上游 CLI 迁移

先发布新 npm 包，再部署网页安装命令和内置 skill-center 技能的新版本，避免用户看到尚未存在的 npm 包。

```bash
npm install -g @jiahao6635/skill-center
skill-center login --browser --registry https://skill-center.sigmob.com
skill-center list
```

上游 `skillhub` 可并存，新命令不会覆盖它。凭据、清单和每个已安装技能中的 `.skillhub/metadata.json` 保持兼容。

主要命令变化：

| 旧入口 | 新入口 |
| --- | --- |
| `npx @astron-team/skillhub ...` | `npx @jiahao6635/skill-center ...` |
| `skillhub update`（升级 CLI） | `skill-center self-update` |
| 无独立上传更新命令 | `skill-center update <skill-id> <path>` |
| 无技能移动命令 | `skill-center move <skill-id> <namespace>` |

业务自定义接口使用当前仓库的服务端实现。管理端统计、审核管理和独立调用采集接口不作为本轮 CLI 命令；它们的鉴权和业务职责不同。
