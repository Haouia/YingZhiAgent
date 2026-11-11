# 营知 · 营地咨询与服务 Agent

独立 Java / Vue 应用，完成营地查询、知识资料问答、确认后提交工单、客服处理和运营资料发布。

## 项目位置与边界

本工程独立存放于 `D:\camp-agent`，不读取“毕设1”的数据库、代码或账号。默认后端端口 8081、前端端口 5174，数据保存在本工程 `backend/.local`。

正式本机模式采用 MySQL、DeepSeek 实际工具调用和 BM25 关键词检索。营地查询、多轮规则引用、工单闭环和一张图片文字提取已通过小规模联调。混合向量检索需独立的向量化和重排序凭证。详见 [DeepSeek 联调记录](docs/DEEPSEEK-ACCEPTANCE.md)。

## 快速运行：本地业务演示

要求 Java 21、Maven 3.9+、Node 24。首次运行需要下载依赖。

终端一：

```powershell
cd D:\camp-agent\backend
mvn spring-boot:run
```

终端二：

```powershell
cd D:\camp-agent\frontend
npm ci
npm run dev
```

浏览器打开 [本地开发页面](http://127.0.0.1:5174)。H2 demo 账号 `user`、`user2`、`staff`、`ops` 的初始密码为 `CampDemo2026!`，依次为普通用户、另一个普通用户、客服和运营。cloud 模式的账号初始密码取自私有 `deploy/.env` 中的 `DEMO_PASSWORD`。

也可以直接启动已经构建的后端：

```powershell
cd D:\camp-agent\backend
java -jar target/camp-agent-0.1.0.jar
```

本地模式采用持久化 H2 数据库、关键词路由及 BM25 原文检索，页面和回答明确标记“未调用大模型”。这用于无 Key 时验证业务流程，不模拟真实向量或重排序结果。图片和扫描 PDF 的 OCR 在此模式会明确提示需要云服务，不伪造提取结果。

## 切换真实云模型

私有 deploy/.env 已配置 DeepSeek；不要把 Key 发到聊天或提交 Git。当前设置为 CHAT_PROVIDER=deepseek、DEEPSEEK_MODEL=deepseek-flash、RETRIEVAL_MODE=keyword。完整变量与容器配置见 [部署说明](docs/DEPLOYMENT.md)。

```powershell
cd D:\camp-agent
docker compose --env-file deploy/.env -f deploy/compose.yml -f deploy/compose.local.yml up -d mysql qdrant
# 确保 8081 上现有后端已停止后启动；前端可保留。
./scripts/start-cloud-local.ps1
```

脚本连接独立 MySQL camp_agent 库，不迁移 H2 数据。cloud 不自动降级为 demo；keyword 是明确配置的检索方式。运营须核对资料再发布。切换 hybrid 需配置独立 DASHSCOPE_API_KEY 并重新发布有效资料以建立向量索引；DeepSeek Key 只用于 DeepSeek。

Spring AI 负责 `@Tool` 元数据及受控工具执行；应用显式实现最多 4 轮的模型/工具循环。工具只查询营地、检索资料、准备草稿和查询本人工单；正式工单始终由确认接口创建。

## 查找真实营地

登录后可在「营地助手」输入「帮我找杭州的真实露营地」，再问「第二个营地的地址」「它能带宠物吗」。也可打开「找真实营地」直接输入城市和关键词。两处均展示最多 5 个高德地图候选、来源和查询时间。当前真实地点的审核规则资料待补齐，规则追问会提示向营地核实。

在私有 `deploy/.env` 中配置 `AMAP_WEB_SERVICE_KEY` 并设置 `AMAP_MCP_ENABLED=true`，然后按上节启动。高德查询仅在后端调用。[高德 MCP 验收记录](docs/AMAP-MCP-ACCEPTANCE.md)

## 目录入口

| 路径 | 内容 |
| --- | --- |
| [PLAN.md](PLAN.md) | 原始目标及实际实现差异 |
| backend/src/main/java/com/campagent | auth、chat、knowledge、campsite、ticket、config、common |
| frontend/src | Vue 界面、API 客户端、登录状态与样式 |
| database | 建库结构 |
| data/knowledge | 61 份公开/内部虚构资料及清单 |
| data/evaluation | 200 个待人工审核的候选案例，不是验收结果 |
| [docs/API.md](docs/API.md) | 主要接口和权限 |
| [docs/DEMO.md](docs/DEMO.md) | 用户、客服、运营演示步骤 |
| [docs/EVALUATION.md](docs/EVALUATION.md) | 当前验证证据和未验证项 |
| [docs/DEEPSEEK-ACCEPTANCE.md](docs/DEEPSEEK-ACCEPTANCE.md) | 当前模型配置、数据流和真实联调结果 |
| deploy | Dockerfile、Compose、Nginx、环境变量模板 |

## 验证命令

```powershell
cd D:\camp-agent\backend
mvn test
mvn package
cd ..\frontend
npm run build
```

默认 mvn test 使用内存数据库和本地模型协议模拟服务；infrastructure profile 另外使用隔离的真实 MySQL/Qdrant。两种方式均不消耗真实云 API 额度。用户资料、正式业务数据库和旧项目不参与测试。


## 后续联调进度

首版 MySQL 8.4 与 Qdrant v1.15.5 联调通过，使用本机 13316 / 16333。首版 33 项自动化测试包含 25 项普通测试和 8 项基础组件联调。另行完成了 5 个真实 DeepSeek 对话用例、工单 REST 流程和 1 张图片文字提取。

- 完整证据：[基础设施验收记录](docs/INFRASTRUCTURE-ACCEPTANCE.md)。
- 可复现命令：在项目根目录运行 `./scripts/test-infrastructure.ps1 -StartServices`。
- 私有配置：`deploy/.env` 使用随机本地凭证并被 Git 忽略，已配置 DeepSeek，百炼 Key 未配置。
- 2026-11-09 完成第 2 阶段本机验收，44 项自动化测试通过，其中包括 8 项真实 MySQL/Qdrant 联调。浏览器已验证聊天查询、详情、规则追问、引用与历史卡片，五个专项真实接口请求均返回 200。地点上下文保留 10 分钟。下一阶段为六城试点和检索优化。
- 2026-11-09 已部署到独立 Ubuntu 服务器，35 项服务器 API、19 项重启与静态资源检查通过；同时修复草稿提示覆盖问题，37 项默认测试通过。暂无域名，使用 SSH 隧道访问；服务器浏览器交互、HTTPS 和并发验收待完成。见[服务器验收记录](docs/SERVER-ACCEPTANCE.md)及[连接方式](docs/DEPLOYMENT.md)。
- 2026-11-09 全面检查发现的七项缺陷已完成对应修复，最新服务器版本 `release-20261109-172651`。53 项后端测试、7 项前端测试、37 项服务器 API 及异常 PDF/代理限流专项通过。前端回归可运行 `cd frontend; npm test`。详见[修复验收](docs/BUGFIX-ACCEPTANCE-2026-11-09.md)。

