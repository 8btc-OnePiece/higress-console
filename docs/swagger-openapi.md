# Higress Console Swagger / OpenAPI

## 基线与范围

本改动从本地 `wujieai-main` 的 `25355d2` 开始（包含 `d3ed38c` 的 key-group 基线，以及已有插件执行顺序提交），不修改 Jenkins 流水线、数据库、提供者或业务路由。

Spring Boot 2.7.18 / Springdoc 1.8.0 / Swagger UI 5.11.10 已在仓库中。本改动开启现有组件，为 Springdoc Controller 和静态资源复用 `SessionService.validateSession`；原业务接口仍由 `ApiStandardizationAspect` 鉴权。未经登录访问文档返回 401，不重定向至匿名文档。

## 访问与关闭

部署后的入口以该控制台根 URL 为前缀：

| 内容 | 路径 |
| --- | --- |
| Swagger UI | `/swagger-ui.html` → `/swagger-ui/index.html` |
| 本仓库分组 OpenAPI JSON（自动化使用此入口） | `/v3/api-docs/Higress` |
| 分组 OpenAPI YAML | `/v3/api-docs.yaml/Higress` |
| Swagger UI 配置 | `/v3/api-docs/swagger-config` |
| 默认未分组文档 | `/v3/api-docs` |

先在同源控制台登录，再打开 Swagger UI，浏览器沿用 HttpOnly 会话 Cookie（默认 `_hi_sess`）。自动化可以调用 `/session/login` 保存 Cookie，或使用现有 HTTP Basic 管理员认证。Swagger 的 Authorize 支持 Basic；Cookie 由浏览器登录维护，不能在 Swagger 中手工写入 HttpOnly Cookie。分组文档标注二者为可选认证方式；匿名登录接口不标注认证要求。

`HIGRESS_CONSOLE_API_DOCS_ENABLED=false` 可同时关闭 UI 和 API 文档；默认 `true`。Spring 的 `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` 仍可由部署配置分别覆盖。保持同源访问，不新增 CORS 放行。

测试根 URL 为 `https://test-higress-ui.wujieai.com`，来自 OPE-11244 的测试构建 #9（运行基线 `25355d2`）。本任务的新提交尚未部署。实测该站 `/v3/api-docs/Higress` 返回 HTTP 200 **HTML**，不是 OpenAPI JSON；AI 路由和 key-group 匿名查询均返回 401。不能把这一 HTML 回退当作文档已开放。

## 与官方文档对照

参考 `https://higress.cn/swagger/` 及其 `/swagger/openapi.json`（2026-11-10 读取）：OpenAPI 3.0.1，共 45 个路径。核心服务来源、提供者、AI 路由、路由插件实例路径一致；官方文档没有本仓库的 `/v1/api-keys/group/list` 等 key-group 扩展。自动化应以目标环境生成的 `Higress` 分组文档为准，不能把官网静态文档等同于运行版本。`info.version` 来自构建版本；精确 Git SHA 需配合流水线/镜像证据核对。

## 接口交接清单

| 功能 | 方法与路径 | 使用边界 |
| --- | --- | --- |
| 登录/退出 | `POST /session/login`, `GET /session/logout` | 登录请求为 `username`, `password`, 可选 `autoLogin`；本版本登录成功实际为 HTTP 201 |
| 服务来源查询 | `GET /v1/service-sources`, `GET /v1/service-sources/{name}` | 列表支持 `pageNum`、`pageSize` |
| 服务来源变更 | `POST /v1/service-sources`, `PUT /v1/service-sources/{name}` | 按运行文档中的 `ServiceSource` 校验 |
| 提供者查询 | `GET /v1/ai/providers`, `GET /v1/ai/providers/{name}` | 仅查询；新提供者由人工创建 |
| AI 路由 | `GET/POST /v1/ai/routes`, `GET/PUT/DELETE /v1/ai/routes/{name}` | `AiRoute` schema 为准，变更前保存原值 |
| 既有路由 | `GET /v1/routes`, `GET /v1/routes/{name}` | 只读回归 |
| 插件目录 | `GET /v1/wasm-plugins/user-balance-check` | 验证运行环境是否安装插件及其配置 schema |
| 路由插件 | `GET/PUT/DELETE /v1/routes/{routeName}/plugin-instances/{name}` | `name=user-balance-check`；PUT 使用 `WasmPluginInstance` |
| key-group 查询 | `GET /v1/api-keys/group/list`, `GET /v1/api-keys/groupInfo?customerName=…` | 下游真实数据需测试环境验证 |
| key-group 变更 | `POST /v1/api-keys/group/upinsert`, `/group/remove`, `/group/mapping/add`, `/group/mapping/del` | 后三项均延续 `/v1/api-keys` 前缀；本次不执行写操作 |

注意：插件实例 GET 在实例不存在时会返回 `enabled=false` 的占位对象，HTTP 200 **不能证明插件已启用**。先检查插件目录，再检查指定路由实例的 `enabled` 与配置。新提供者有已知创建问题，禁止自动调用提供者 POST/PUT 创建新提供者。

## 自动验收入口

在受保护的临时 JSON 文件中提供管理员 `username`、`password`，不要提交凭据文件。以下命令须在本任务提交发布后执行，凭据文件和既有测试路由仍需由操作者提供：

```sh
python3 scripts/verify-openapi.py \
  --base-url https://test-higress-ui.wujieai.com \
  --auth-file /secure/console-auth.json \
  --route EXISTING_TEST_ROUTE \
  --output openapi-smoke-result.json
```

脚本除登录/退出外只做 GET；不创建提供者、路由或插件。检查匿名 401、登录 Cookie、UI/资源、生成文档、核心接口和插件目录，报告不保存业务响应体或凭据。`--route` 可选，缺省时明确记录实例检查未执行；`balance_plugin_enabled=false` 表示实例未启用，不代表查询失败。脚本不证明模型调用/扣费成功，也不替代 UI 人工回归。

安全的单条查询示例（已有 Cookie 文件由操作者维护）：

```sh
curl --fail --cookie /secure/console.cookies \
  https://test-higress-ui.wujieai.com/v3/api-docs/Higress
curl --fail --cookie /secure/console.cookies \
  https://test-higress-ui.wujieai.com/v1/ai/routes/EXISTING_TEST_ROUTE
```

仓库原有 `-Papi-docs` 改为从环境变量 `HIGRESS_CONSOLE_DOCS_AUTHORIZATION` 读取认证头，导出失败会令构建失败。现有 OSS 文档工作流仅在专用 `kind-higress` 临时集群生成随机管理员 Secret，并将掩码认证头传给导出步骤；不涉及真实集群。该 GitHub Actions 工作流需在 CI 另行验收，本地未执行 OSS 上传。不要为静态导出而关闭业务登录校验。

## 验证层次与回滚

`SwaggerAccessTests` 使用真实 Springdoc、MVC、AOP、SessionService 和加密 Cookie，mock Kubernetes 与业务数据；包含嵌入式 HTTP 服务请求。`SwaggerDisabledTests` 验证总开关及关闭后业务鉴权。它们不连接真实集群，不能替代部署验收。可额外传入 `-Dhigress.openapi.smoke=true`，调用 Python 3 验收脚本检查嵌入式 HTTP 服务；此选项不启用时该脚本集成用例会跳过。

```sh
cd backend
./mvnw -B -ntp test -Dskip.installnodenpm -Dskip.npm -Dskip.npx \
  -Dmaven.gitcommitid.nativegit=true -Dcheckstyle.skip=true -Dpmd.skip=true
```

回滚：优先设置 `HIGRESS_CONSOLE_API_DOCS_ENABLED=false` 并重新部署；代码回滚可 revert 本任务提交，恢复原镜像。没有数据库、业务配置或新提供者变更需要回滚。

## 本次本地验证记录（2026-11-10）

- SDK 240 项、控制台 26 项测试通过，失败/错误/跳过均为 0；控制台包含自定义文档路径、关闭开关、Cookie/Basic、生成 schema、登录/退出、路由/key-group 查询及脚本集成测试。
- 只读脚本对嵌入式 HTTP 服务完成 22 项检查。外部业务数据是 mock；插件实例 `enabled=false` 是测试占位场景，不代表真实环境已启用。
- 前端使用 Node 22.14.0 构建成功；Java 17.0.8.1、Maven Wrapper 3.8.7 打包成功。最终 JAR 包含前端、Swagger UI 5.11.10 与鉴权配置。
- PMD、Checkstyle、`git diff --check` 通过；PMD 仍有仓库既有非阻断警告。
- 完整构建最初遇到 worktree Git 插件限制（使用原生 Git 参数解决）、npm 镜像超时（重试解决）、静态检查错误（修正注解缩进及 7 处基线导入/分号/注释问题）。这些修正不改变 key-group 业务行为。
- 完整前端加入后，关闭文档的测试捕获 SPA 回退 HTML 200；已修复为文档路径返回 404，并回归通过。
- GitHub Actions YAML/内嵌 Python 及随机凭据引导逻辑通过本地检查（kubectl 为 mock）；未执行 GitHub Actions、OSS 上传、Docker 构建或测试/生产集群发布。

本地前端构建使用 `-Dnodejs.version=22.14.0 -Dskip.npx=true`，不更新 Browserslist 数据库。最终打包复用了本轮已经成功构建的前端资源：

```sh
cd backend
./mvnw -B -ntp package \
  -Dmaven.gitcommitid.nativegit=true -Dnodejs.version=22.14.0 \
  -Dskip.installnodenpm=true -Dskip.npm=true -Dskip.npx=true \
  -Dhigress.openapi.smoke=true -Dpmd.language=en
```

首次构建新工作区时去掉 `skip.installnodenpm`、`skip.npm`，让 Maven 安装依赖并构建前端。Node 16 下完整构建未验收；Jenkins 需采用与本轮验证一致的工具版本或补做 Node 16 验证。

## 发布交接依赖

OPE-11244 已建立测试任务 `https://jenkins.8btc-ops.com/job/APP/job/higress-console-test/`，测试构建 #9 使用 `ACTION=deploy BRANCH=wujieai-key-group` 发布 `25355d2`；默认分支仍为 `wujieai-main`。本任务未触发新构建或改变现有测试发布。

当前阻塞已缩小为 GitHub 写入认证：本机实际推送 SSH 返回 `Permission denied (publickey)`，HTTPS 无可用登录凭据；OPE-11244 也报告 Jenkins Git 凭据 `390b8ea4-cb7d-4ad2-a2ca-fabac16c4eed` 没有写权限，远端 `wujieai-main` 尚不存在。需补齐该凭据的仓库写权限或提供可写 credential ID，再把本补丁合入 `wujieai-main`，由该测试流水线发布新提交，并运行验收脚本。Jenkins API auth 已在上游正常使用，不是本次剩余阻塞。

真实环境的 Swagger UI/JSON、已登录关键查询、浏览器控制台登录/路由/key-group 和 `user-balance-check` 配置回归仍未验收；生产未发布。新提供者仍人工添加，不执行数据库写入。
