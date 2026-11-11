# 营知 · 营地服务 Agent

营知（YingZhiAgent）是一套可独立运行的营地咨询与服务应用。用户可以在对话中查找营地、询问规则和准备工单。客服通过工作台处理工单，运营人员负责维护营地信息和发布知识资料。

## 功能

- **营地查询**　按城市、设施或关键词查找本地营地。启用高德地图 MCP 后，可以查询真实地点，查看地址和详情，并用「第二个营地」等指代继续提问。
- **资料问答**　按营地、资料发布状态和用户权限检索规则，回答可附原文引用。运营人员可以上传、审核、发布和下架资料版本。
- **工单处理**　对话先准备草稿，用户确认后才创建正式工单。重复确认返回同一工单，客服填写处理说明，用户可查看本人工单的进度。
- **角色与权限**　普通用户、客服和运营人员各有对应界面。后端校验会话、工单和资料引用的访问权限。

仓库内置虚构营地及演示资料。高德返回地点候选和地图信息，真实营地的营业规则、价格和余位需向营地核实。工单用于咨询和服务处理。

## 本地快速开始

准备 JDK 21、Maven 3.9+ 和 Node.js 24。本地 `demo` 模式使用 H2 和 BM25，无需 Docker 或模型密钥。

```bash
git clone https://github.com/Haouia/YingZhiAgent.git
cd YingZhiAgent
```

从仓库根目录打开两个终端。在第一个终端启动后端。

```bash
cd backend
mvn spring-boot:run
```

在第二个终端启动前端。

```bash
cd frontend
npm ci
npm run dev
```

打开 [本地应用](http://127.0.0.1:5174/)。前端把 `/api` 请求代理到 `127.0.0.1:8081`，本地演示数据保存在 `backend/.local/`。

| 本地演示账号 | 用途 |
| --- | --- |
| `user` | 查询营地、查看引用和提交工单 |
| `user2` | 验证普通用户之间的数据隔离 |
| `staff` | 处理工单 |
| `ops` | 管理营地和发布资料 |

全新本地 `demo` 库的初始密码为 `CampDemo2026!`。可以用 `user` 登录并提问「青岚竹溪营地可以带宠物吗？」，然后打开回答里的资料引用。完整流程见 [演示指南](docs/DEMO.md)。

## 云模型与容器运行

安装 Docker 和 Docker Compose，从仓库根目录复制环境变量模板。

```bash
cp deploy/.env.example deploy/.env
```

在本机的 `deploy/.env` 中配置 `DATABASE_PASSWORD`（应用数据库密码）、`MYSQL_ROOT_PASSWORD`（MySQL root 密码）、`JWT_SECRET`（JWT 签名密钥）、`DEMO_PASSWORD`（初始演示账号密码）和 `DEEPSEEK_API_KEY`。模板默认选择 DeepSeek 对话和 BM25 检索，实际密钥保存在本地环境文件中。

```bash
docker compose --env-file deploy/.env -f deploy/compose.yml up -d --build
```

打开 [容器应用](http://127.0.0.1:8090/)。首次启动时，使用 `DEMO_PASSWORD` 初始化演示账号。首次体验云模型问答时，先用 `ops` 和 `DEMO_PASSWORD` 登录，核对并发布所需种子资料。再切换到 `user` 进行提问。

| 可选配置 | 用途 |
| --- | --- |
| `AMAP_MCP_ENABLED=true` 和 `AMAP_WEB_SERVICE_KEY` | 启用真实营地查询。聊天工具和地点查询接口共用后端额度控制。 |
| `RETRIEVAL_MODE=hybrid` 和 `DASHSCOPE_API_KEY` | 使用 Qdrant 向量检索、BM25 融合和百炼重排序。切换后需重新发布用于检索的资料，建立向量索引。 |

容器部署使用独立的 `camp-agent` Compose 项目、`camp_agent` MySQL 数据库和持久化卷。Nginx 提供页面及同源 `/api` 代理，后端、MySQL 和 Qdrant 通过容器网络连接。服务器配置、备份和维护步骤见 [部署说明](docs/DEPLOYMENT.md)。

## 技术与目录

前端使用 Vue 3、Vite、Pinia 和 Element Plus。后端使用 Spring Boot、Spring Security 与 Spring AI，负责身份校验、资料检索、受控工具调用和工单状态流转。

| 目录 | 内容 |
| --- | --- |
| `backend/` | Java 后端、数据库初始化和后端测试 |
| `frontend/` | Vue 界面、路由、登录状态和前端测试 |
| `database/` | 数据库结构和增量脚本 |
| `data/knowledge/` | 虚构营地知识资料及清单 |
| `data/evaluation/` | 检索与地图查询评测样本 |
| `deploy/` | Dockerfile、Compose、Nginx 和环境变量模板 |
| `scripts/`、`tests/` | 联调、评测和备份验证 |

## 测试与文档

在仓库根目录执行以下命令。前端测试和构建需要先运行 `npm ci` 安装依赖。

```bash
mvn -f backend/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
python -m unittest discover -s tests -p "test_*.py"
```

后端默认测试使用内存数据库与本地模拟服务。MySQL 和 Qdrant 集成测试的准备方式见 [基础设施验收记录](docs/INFRASTRUCTURE-ACCEPTANCE.md)。

- [接口与权限](docs/API.md)
- [DeepSeek 联调记录](docs/DEEPSEEK-ACCEPTANCE.md)
- [高德查询验收记录](docs/AMAP-MCP-ACCEPTANCE.md)
- [评测方法与结果](docs/EVALUATION.md)
- [优化与线上验收记录](docs/OPTIMIZATION-20261110.md)

真实营地覆盖和口语检索仍按评测门槛继续验证，当前结果以验收记录中的样本和统计口径为准。
