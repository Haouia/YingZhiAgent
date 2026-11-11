# 基础设施与 RAG 联调记录

日期：2026-11-05。

本文为切换 DeepSeek 之前的基础设施验收快照，保留当时的 25 项结果和 H2 运行状态。最新后端已切换 DeepSeek + MySQL + keyword，回归总计 33 项，另有真实模型用例与单图提取记录，见 [DeepSeek 联调记录](DEEPSEEK-ACCEPTANCE.md)。

## 验证边界

本轮使用真实 MySQL 8.4 容器和 Qdrant v1.15.5 容器；Embedding、Rerank、对话和 OCR 使用测试进程内的本地 HTTP 协议模拟服务，未使用百炼 Key、未发生真实模型调用。

因此，本报告证明的是数据库/检索服务接入、协议处理和业务正确性，不能证明模型语义效果、OCR 准确率、费用或线上性能。模拟向量固定用于检验写入与查询，不用于准确率评估。

## 隔离资源

| 资源 | 本轮配置 |
| --- | --- |
| Compose 项目 | camp-agent |
| MySQL 容器 | camp-agent-mysql-1 |
| MySQL 本地端口 | 127.0.0.1:13316 |
| 应用数据库 | camp_agent，未迁移本地 H2 演示数据 |
| 联调数据库 | camp_agent_it |
| Qdrant 容器 | camp-agent-qdrant-1 |
| Qdrant 本地端口 | 127.0.0.1:16333 |
| 联调集合 | 每次运行生成 camp_agent_it_ 前缀的随机集合，结束时仅清理该集合 |
| 私有凭证 | deploy/.env，已被 Git 忽略，未写入本文 |
| 模型请求 | 仅 127.0.0.1 上的临时协议服务 |

未操作其他项目容器、业务数据库、网络或数据卷。本轮容器保留运行，后续可继续使用。测试只允许连接本机 camp_agent_it 库，不清空应用库，不使用原 H2 文件。联调库保留自动化夹具，不作为人工业务数据。

## 测试结果

Maven infrastructure profile：

| 测试套件 | 数量 | 失败 | 错误 | 跳过 |
| --- | --- | --- | --- | --- |
| ApplicationTest | 9 | 0 | 0 | 0 |
| CloudProtocolTest | 8 | 0 | 0 | 0 |
| InfrastructureIT | 8 | 0 | 0 | 0 |
| 合计 | 25 | 0 | 0 | 0 |

运行结果为 BUILD SUCCESS。报告原件位于 backend/target/surefire-reports；控制台日志位于 .local/infrastructure-test.log，均不提交 Git。

### 真实基础设施覆盖

- MySQL 驱动连接和 MEDIUMTEXT 大于 64KB 的中文文本存储。
- 向 Qdrant 写入 1024 维协议夹具向量、按允许片段 ID 查询。
- BM25 + 密集召回 + RRF + 重排序协议 + 有效版本复核。
- 旧版本仍在索引时，新回答只使用当前发布版本；下架后不返回该资料。
- 向量化失败时发布事务回滚，旧版本继续有效，新版本仍为待审核。
- 内部片段不会进入普通用户的向量候选及重排序输入，按引用 ID 读取也被拒绝。
- Agent 经 Spring AI 工具调用真实检索链，返回来自当前有效片段的引用。
- 8 个并发确认请求最终只创建同一张工单，验证 MySQL 行锁与唯一约束。
- 实际 DOCX 段落/表格顺序与普通 PDF 页码标记、文本提取。
- 扫描 PDF 的转图/OCR 请求协议（OCR 为模拟结果）、损坏 PDF 拒绝发布。
- 重复解析任务不改写已发布版本，片段 ID 保持不变。

### 本轮复现并修复

1. 非数值向量原先抛出 ClassCastException；无效维度被归为用户输入错误。现校验形状、维度与有限数值，并返回明确上游错误。
2. 重排序小数索引原先会被强制截为整数；空结果没有作为异常处理。现拒绝非整数、越界、重复和无效结果。
3. 重复排队的解析任务原先会将已发布版本改为 REVIEW 并重建片段。现仅处理 PROCESSING 版本；保存提取内容时加事务锁和状态检查，失败回写也仅限处理中版本。

## 复现方式

在项目根目录执行：

```powershell
./scripts/test-infrastructure.ps1 -StartServices
```

普通开发仍使用：

```powershell
cd backend
mvn test
```

普通测试为 17 项，不需要 Docker，也不调用付费模型；infrastructure profile 另加入 8 项真实基础组件联调。可选 MavenRepository 参数用于本机存在不同 Maven 缓存目录的情况。

本地端口映射仅由 compose.local.yml 加入，部署基础文件没有公开 MySQL/Qdrant 端口。Compose 允许在未配置模型 Key 时单独启动基础组件；模型调用仍明确返回 503，不会降级为演示结果。

## 真实模型的下一步

1. 在私有 deploy/.env 填写 DASHSCOPE_API_KEY，端点应与百炼账号地域和业务空间匹配。不要将 Key 发到聊天或提交 Git。
2. 确认基础组件已启动。停止当前 8081 上的 demo 后端后，执行 scripts/start-cloud-local.ps1。该脚本显式使用 MySQL camp_agent 库，保持本机监听，不将 H2 的演示发布状态误用到 cloud。
3. 继续使用原前端，重新登录；cloud 初始账号密码取自私有文件中的 DEMO_PASSWORD。
4. 先核对并发布少量资料，验证真实 Embedding、检索、Rerank 和对话，再扩展语料。真实 OCR、质量评测及性能费用单独记录。

当前仍未验证真实模型/OCR效果、200 个候选案例的人工审核与独立评测、公网 HTTPS 和备份恢复。


## 运行版本更新

已将修复打包并更新本机后端。重启后 /api/health 为 UP、mode 为 demo，前端 HTTP 200；原有带【界面验收】标记的处理中工单仍保留。当前页面继续使用原本地演示库，未自动切换到真实模型或 MySQL 应用库。
