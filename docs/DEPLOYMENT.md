# 部署与运行

## 2026-11-11 线上版本与访问地址

公网地址更新为 `http://121.40.149.114:8090/`，本机 `ssh camp-server` 已核验可登录。当前版本为 `release-20261110T100939Z-optimization`，`/opt/camp-agent/current` 已指向该版本。外网首页与健康接口HTTP 200。2026-11-09的部署记录保留在下文，最新验收见[优化记录](OPTIMIZATION-20261110.md)。

本手册对应 **营知 · 营地服务 Agent**。本地工程在 `D:\camp-agent`，服务器使用 `/opt/camp-agent`、Compose 项目 `camp-agent` 和容器内 `camp_agent` 库。

同机 80 端口由 **Sahara · 露营行程规划与物资管理** 使用，项目在 `D:\毕设1`，服务器目录为 `/www/wwwroot/camping`，服务为 `camping.service`，数据在宿主机的 `camp_planner` 库。2026-11-11 已从公网分别验证 80 和 8090 的页面与 API，两套应用使用独立登录密钥和文件存储。

网页映射现在仅在compose.yml定义一次：`${WEB_BIND_ADDRESS:-127.0.0.1}:8090:80`。本机默认回环；服务器私有 `/opt/camp-agent/shared/.env` 必须明确设置 `WEB_BIND_ADDRESS=0.0.0.0`，避免升级覆盖手工修改。不要在server覆盖文件重复追加端口。

发布前备份现有release、共享环境文件（权限600）、数据库与原文。在已准备并验证的release中执行以下命令。

```bash
docker compose --env-file /opt/camp-agent/shared/.env -f deploy/compose.yml -f deploy/compose.server.yml config --quiet
docker compose --env-file /opt/camp-agent/shared/.env -f deploy/compose.yml -f deploy/compose.server.yml up -d --no-deps --no-build --pull never --force-recreate backend web
docker port camp-agent-web-1
curl --fail --silent http://127.0.0.1:8090/api/health
```

需要从外网另验首页与API，不得用服务器本机curl代替。再验证登录、引用、工单和持久化；确认其他项目服务未受影响。SSH隧道仍可使用 `ssh -N -T -L 127.0.0.1:18090:127.0.0.1:8090 camp-server`，浏览器访问 `http://127.0.0.1:18090/`。

失败时回到发布前记录的完整release路径，用相同shared环境及Compose参数重建backend/web，并将current指回该版本。不要删除卷、重跑种子建库或回滚时删表；新map_quota表可以保留供旧版回滚。仅改网页绑定时可单独重建web。

新增环境参数：MAP_MINUTE_LIMIT=30、MAP_DAY_LIMIT=200、MAP_GLOBAL_DAY_LIMIT=1000。日额度按Asia/Shanghai计算。应用自动幂等创建map_quota，独立增量DDL同时位于database/02_map_quota.sql。保留该表才能保留重启前的额度。

### 账号切换验收（2026-11-11）

现有user/user2/staff/ops需分别设置不同密码并实际更新BCrypt哈希；修改DEMO_PASSWORD不会更新已有账号。通过私有输入完成一次性维护，不把密码写入命令参数、报告或聊天。同时更新shared/.env中的JWT_SECRET并重建backend，让旧令牌失效。留存轮换前私有备份，在本机受保护位置交付新凭据。

四个账号已使用不同密码，JWT已轮换。已验证四个账号的新密码、旧密码和旧令牌失效、角色授权，以及普通用户的客服和运营接口访问被拒绝。新凭据保存在本机受访问控制的私有目录，位置见 `.local/optimization-20261110/deployment-20261111.json`。评测脚本的 `--environment` 应指向同目录所记录的私有凭据目录里的 `pilot.env`。

### 备份材料与恢复要求

`scripts/backup-server.sh` 固定操作 `/opt/camp-agent` 和camp-agent容器，验证数据库及原文压缩包并保存SHA256。成功备份目录权限700、文件权限600；仅清理该脚本创建且超过七天的成功备份，历史手工备份和失败目录保留。数据库与文件是依次备份，不宣称两者为同时刻快照，完整恢复必须检查记录与原文的对应关系。

先手动执行脚本并完成恢复验证，再安装定时器：

```bash
install -m 644 deploy/camp-agent-backup.service /etc/systemd/system/camp-agent-backup.service
install -m 644 deploy/camp-agent-backup.timer /etc/systemd/system/camp-agent-backup.timer
systemctl daemon-reload
systemctl enable --now camp-agent-backup.timer
systemctl list-timers camp-agent-backup.timer
systemctl status camp-agent-backup.service
journalctl -u camp-agent-backup.service --since today
```

默认每日服务器当地时间03:15执行并随机延迟最多10分钟，安装时记录服务器时区。首次启用必须实际验证服务成功记录；失败通过systemd退出状态和备份status.txt可见。发布包的备份脚本已改为Linux换行并通过服务器语法检查。2026-11-11已执行服务器备份，数据库和原文的SHA-256校验通过，路径为 `/opt/camp-agent/backups/backup-20261111T010243Z-R4jVeG`。完整恢复和定时器安装待办。上线前检查bash、flock、gzip、tar和sha256sum等命令可用。

完整恢复需使用隔离库/卷/网络启动完整应用，验证账号、资料、引用、工单、历史并记录耗时，不覆盖正式库、不将演练端口绑定公网。本机备份不足以应对服务器整机损坏，异机目标尚未配置。

暂无域名，本轮仍使用HTTP演示；获得域名和证书后接入现有反向代理HTTPS并关闭公网8090，避免影响已有80/443上的其他项目。

---

## 本地数据

demo 使用 H2 文件库，位于 backend/.local。后端默认仅绑定 127.0.0.1:8081；Vite 使用 127.0.0.1:5174。初次启动创建 4 个演示账号、5 个虚构营地、60 份公开资料和 1 份内部客服资料。

首次启动可通过 DEMO_PASSWORD 修改种子账号密码，至少 12 字符；已建账号不随该环境变量变化自动重置。demo 的 JWT 密钥在未配置时每次启动重新生成，重启后需要重新登录。

## 容器与 cloud 模式

1. 启动 Docker Desktop / Docker Engine。
2. 将 deploy/.env.example 复制为 deploy/.env，填写互不相同的数据库密码、随机 JWT 密钥、演示账号密码及所选服务 Key。现有私有文件不要覆盖。
3. 默认示例选择 CHAT_PROVIDER=deepseek、DEEPSEEK_MODEL=deepseek-flash、RETRIEVAL_MODE=keyword，使用 DEEPSEEK_API_KEY。hybrid 检索另需 DASHSCOPE_API_KEY；百炼 MODEL_BASE_URL 不含末尾 /v1，RERANK_URL 为完整端点。DeepSeek Key 不发送到这两个百炼地址。
4. 在 deploy 执行 `docker compose --env-file .env up -d --build`。
5. 访问本机 http://127.0.0.1:8090，先登录 ops，检查原文后发布。keyword 仅启用 BM25；hybrid 必须成功写入向量索引才会发布。
6. 切换 user / staff 进行真实模型联调。缺少 Key、无权限、限流、超时和索引失败均会明确报错，不降级为 demo。

数据库 camp_agent 和 Qdrant 集合 camp_agent_chunks 与旧项目独立。MySQL 和 Qdrant 不映射到公网；网页仅映射本机 8090，公网接入应在其前配置域名和 HTTPS 反向代理。

本机已运行独立 MySQL/Qdrant 并通过集成测试，compose.local.yml 仅加入本机端口。2026-11-09 已完成下述服务器运行产物部署；原多阶段 Dockerfile 从源码构建的完整镜像、HTTPS 和公网入口仍未验收。

## 已部署服务器（2026-11-09）

服务器为 Ubuntu 22.04、4 核、8GiB，SSH 别名 `camp-server`。采用 Docker Engine 29.9.3、Compose v5.7.0，与宝塔已有服务分开。Docker 官方源连接重置时使用阿里云 Docker CE 软件源，保留签名校验。参考 [Docker Ubuntu 安装文档](https://docs.docker.com/engine/install/ubuntu/)和[阿里云镜像说明](https://developer.aliyun.com/mirror/docker-ce/)。

没有域名，网页绑定服务器 `127.0.0.1:8090`。本机已开启转发，可访问 **http://127.0.0.1:18090/**。SSH 断开后，在本机终端保持以下命令运行：

```powershell
ssh -N -T -o ExitOnForwardFailure=yes -L 127.0.0.1:18090:127.0.0.1:8090 camp-server
```

`18090` 已监听时直接访问，避免重复启动隧道。账号仍为 `user`、`user2`、`staff`、`ops`；服务器使用重新生成的演示密码，取本机私有 `D:\camp-agent\.local\server-deploy\server.env` 中的 `DEMO_PASSWORD`。本地 `deploy/.env` 的密码不用于服务器。该私有文件包含 API Key，不得整份分享或提交 Git。

### 文件与运行结构

```text
/opt/camp-agent/
├── current -> releases/release-20261109-172651
├── shared/.env                       # root:root 600，目录 700
├── releases/
│   ├── release-20261109-164126/        # 上一版，供回退
│   └── release-20261109-172651/
│       ├── deploy/
│       │   ├── compose.yml
│       │   ├── compose.server.yml     # 内存、运行镜像、健康检查及日志上限
│       │   └── nginx.conf
│       └── runtime/
│           ├── camp-agent.jar         # 经测试的构建产物，只读挂载
│           └── web/                   # Vue dist，只读挂载
└── backups/                           # MySQL 导出与上传原文备份
```

服务器无法直连 Docker Hub。本轮将本机缓存的 Linux/amd64 镜像压缩、校验 SHA-256 后传输并加载；JRE、Nginx 分别使用固定项目标签 `camp-agent-java:20261109`、`camp-agent-web-runtime:20261109`。MySQL 使用 `mysql:8.4`，Qdrant 使用 `qdrant/qdrant:v1.15.5`。该路线运行已构建的 JAR/dist，服务器不执行 Maven/npm 构建。

```bash
cd /opt/camp-agent/current
docker compose --env-file /opt/camp-agent/shared/.env -f deploy/compose.yml -f deploy/compose.server.yml config -q
docker compose --env-file /opt/camp-agent/shared/.env -f deploy/compose.yml -f deploy/compose.server.yml up -d --no-build --pull never
docker compose --env-file /opt/camp-agent/shared/.env -f deploy/compose.yml -f deploy/compose.server.yml ps
```

不要省略 `compose.server.yml`、`--no-build` 和 `--pull never`；不要输出带密钥的完整 Compose 展开内容。构建新版本时创建新的 release 目录，校验产物后启动，健康及业务检查通过后更新 `current`。本次没有数据库结构变更，上一 release 可用同一组卷回退；涉及后续迁移时需单独验证兼容性。

| 容器 | 内存上限 | 本轮验收后的单次占用 |
| --- | ---: | ---: |
| MySQL | 768MiB | 196.5MiB |
| Qdrant | 384MiB | 22.9MiB |
| Java 后端 | 1GiB，最大堆 512MiB | 263.6MiB |
| Nginx 前端 | 128MiB | 5.0MiB |

四个容器均配置 `unless-stopped` 和日志轮转。MySQL、Qdrant、原文分别使用 `camp-agent_mysql-data`、`camp-agent_qdrant-data`、`camp-agent_documents`。数据库不发布宿主机端口。服务器采用新建独立库，没有迁移本机历史会话、工单或资料。

当前配置为 DeepSeek + BM25，并显式开启高德 MCP；Qdrant 尚不承担该模式的检索。仅审核发布 3 份虚构种子规则用于验收，另有 1 份上传持久化测试资料，其他种子资料继续待审核。

本轮通过 35 项服务器 API 检查、19 项重启及静态资源检查。服务器上的浏览器交互未完成，因为浏览器权限检查拒绝打开该地址。完整结果见[服务器验收记录](SERVER-ACCEPTANCE.md)。

最新修复版为 `release-20261109-172651`，已通过 53 项后端测试、7 项前端测试、37 项服务器 API 检查和异常 PDF、代理限流专项。服务器保留 1 份安全拒绝的异常 PDF 测试记录，未发布。完整证据见[缺陷修复验收](BUGFIX-ACCEPTANCE-2026-11-09.md)。

代理限流现在返回 429 JSON 及 `Retry-After`，可运行 `./scripts/test-proxy.ps1` 验证。该脚本会短时耗尽测试入口的登录突发预算，执行后按重试提示等待。IP 限流仍为共享出口的粗粒度防护，应用另外按已验证账号控制同时生成和每日次数；尚未配置公网可信代理地址。

## 持久化与备份

Compose 使用 mysql-data、qdrant-data、documents 三个独立卷。升级前用 mysqldump 导出 camp_agent，并备份 documents；Qdrant 可通过快照备份或根据知识片段和原文件重新索引。恢复后必须验证登录、原文访问、检索引用和工单进度。

本轮已在服务器 `/opt/camp-agent/backups/` 生成 `mysql-20261109T084442Z.sql.gz` 和 `documents-20261109T084442Z.tar.gz`，文件权限 600，压缩完整性检查通过。尚未执行备份恢复演练，也未配置定时或异机备份。

后续全面检查已将上述备份恢复到隔离库并核对记录与原文哈希，未覆盖正式库、未用恢复库启动完整应用。修复版升级前另生成 `mysql-20261109T092546Z.sql.gz`、`documents-20261109T092546Z.tar.gz`。尚未配置定时或异机备份。

不要通过删除卷或重跑旧项目建库脚本升级。当前 schema.sql 仅执行 CREATE TABLE IF NOT EXISTS，后续字段调整通过 database/migrations 增量脚本处理。

## 已知实现约束

- 为可验证的首版采用 Spring Boot 4.1.1 / Spring AI 2.0.1，较规划基线下调一个补丁版本，已通过本机编译。
- RETRIEVAL_MODE=keyword 仅执行 Java BM25；hybrid 使用 Qdrant 密集向量 + 同一语料的 BM25，再执行 RRF 和百炼重排序。BM25 使用中文单字/双字及英文词，上限 5000 片段；未接入 Qdrant 原生 BM25。由 keyword 切换 hybrid 时，需重新发布当前生效资料建立索引，不能沿用无向量的发布状态。
- 普通 HTTP 返回完整回答，不支持流式输出。
- 文件最多 10MB、PDF 最多 30 页、文本最多 15 万字符。解析单线程、队列 8 项；图片/扫描 PDF OCR 需要 cloud 模式。
- 目前记录请求次数、工具次数和完整耗时；尚未记录模型 token 计费账单，不能据此宣称成本下降。


## 本机基础组件联调

运行 `./scripts/test-infrastructure.ps1 -StartServices` 启动并验证独立基础组件；测试库固定为 camp_agent_it，不能替换为应用库。真实模型接入使用私有 deploy/.env 和 scripts/start-cloud-local.ps1。详见 [基础设施验收记录](INFRASTRUCTURE-ACCEPTANCE.md)。

本机 cloud 后端使用 8081，前端使用 5174。脚本读取私有配置，校验所选模型 Key 与检索模式，使用独立 MySQL camp_agent，监听 127.0.0.1。CHAT_PROVIDER 未填写时，存在 DeepSeek Key 则选择 deepseek，否则 dashscope。RETRIEVAL_MODE 未填写时，DeepSeek 选 keyword，百炼选 hybrid。hybrid 需要独立百炼 Key。

cloud 登录使用原账号名 user/user2/staff/ops，初次密码取自私有 DEMO_PASSWORD。原 H2 库保留；修改环境变量不会重置已创建账号。当前详细结果见 [DeepSeek 联调记录](DEEPSEEK-ACCEPTANCE.md)。

## 高德真实营地查询

在私有 `deploy/.env` 填写高德 Web 服务 `AMAP_WEB_SERVICE_KEY` 和 `AMAP_MCP_ENABLED=true`，重启项目后端。容器和应用默认关闭高德，`start-cloud-local.ps1` 在开关缺省且存在 Key 时为本机联调启用。只有后端访问高德。浏览器登录后打开「找真实营地」。首次发布前核对账号配额及展示授权。[高德 MCP 接入指南](https://lbs.amap.com/api/mcp-server/gettingstarted)

2026-11-06 的真实高德及 DeepSeek 测试采用本机隔离 H2 实例，Docker 引擎 HTTP 500 时跳过 MySQL/Qdrant 回归。详见 [高德 MCP 验收记录](AMAP-MCP-ACCEPTANCE.md)。
