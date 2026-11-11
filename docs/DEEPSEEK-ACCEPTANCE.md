# DeepSeek 本机联调记录

日期：2026-11-05。工程：D:\camp-agent，与 D:\毕设1 的代码、账号和数据库分开。

## 当前实际架构

```text
Vue 5174 → /api 代理 → Spring Boot 8081 → DeepSeek 工具调用循环（最多4轮）
                           ├─ searchCampsites / getCampsite → MySQL 营地表
                           ├─ searchKnowledge → 已发布且有访问权限的片段 → Java BM25
                           │                                          → 模型回答 + 引用校验
                           ├─ prepareTicket → 仅生成草稿
                           │    用户确认接口 → 幂等创建正式工单 → 客服处理
                           └─ queryMyTickets → 当前用户的 MySQL 工单
运营上传图片 → DeepSeek 文字提取 → REVIEW → 人工核对后才能发布
```

当前 RETRIEVAL_MODE=keyword，不调用向量化、Qdrant 或专用重排序。BM25 的中文单字/双字匹配可能召回含共同字词的无关片段；未证明其语义召回效果。Qdrant 保留运行，独立基础组件测试仍验证 hybrid 协议链路。

## 配置和入口

| 项目 | 当前值 |
| --- | --- |
| 前端 | http://127.0.0.1:5174 |
| 后端 | http://127.0.0.1:8081/api |
| AGENT_MODE | cloud |
| CHAT_PROVIDER | deepseek |
| DEEPSEEK_MODEL | deepseek-flash |
| 对话端点 | https://api.deepseek.com/chat/completions |
| 非推理参数 | thinking: {type: disabled} |
| RETRIEVAL_MODE | keyword |
| MySQL 应用库 | camp_agent，127.0.0.1:13316 |
| 自动化联调库 | camp_agent_it，与应用库分开 |
| Key 与初始账号密码 | 仅在私有 deploy/.env，不写入本文 |

已实际鉴权查询 /models，当前账号返回 deepseek-flash、deepseek-v4-pro；本轮选用 flash。仅“重命名 Key”不会更换服务，本轮同时适配了端点、请求参数、配置注入与启动脚本。百炼凭证与 DeepSeek 凭证分别传递，自动测试验证不混用。

账号仍为 user、user2、staff、ops；cloud 初始密码使用私有 DEMO_PASSWORD。切换后重新登录。未迁移或删除 H2 demo 数据；原演示工单在原库中。

| 文件 | 职责 |
| --- | --- |
| backend/.../knowledge/Cloud.java | 分别调用 DeepSeek 对话/图像提取与百炼向量化/重排序 |
| backend/.../knowledge/Retrieval.java | 显式选择 keyword/hybrid；访问范围与有效版本过滤 |
| backend/.../knowledge/Documents.java | keyword 直接发布有效片段；hybrid 先成功索引 |
| backend/.../chat/ChatService.java | 有界工具循环、引用校验及回复边界 |
| backend/.../auth/AuthController.java | health 返回配置模式，便于界面标识 |
| backend/src/main/resources/application.yml | 环境变量映射 |
| scripts/start-cloud-local.ps1 | 读取私有配置、检查凭证、连接独立 MySQL |
| frontend/src/App.vue | 显示 DeepSeek 与 BM25 关键词检索 |
| deploy/.env.example、compose.yml | 两家凭证及检索模式分别配置 |

## 自动化回归

| 套件 | 用例数 | 结果 |
| --- | --- | --- |
| ApplicationTest | 9 | 通过 |
| CloudProtocolTest | 8 | 通过 |
| DeepSeekProtocolTest | 6 | 通过 |
| DeepSeekKeywordTest | 2 | 通过 |
| InfrastructureIT | 8 | 通过 |
| 合计 | 33 | 无失败、错误或跳过 |

新增测试覆盖正确端点与非推理参数、两家凭证隔离、缺 Key、非法端点、图像响应截断，以及没有向量服务时 cloud 发布/问答/引用、内部资料过滤和下架。自动化全部使用本机模型 HTTP 夹具；InfrastructureIT 使用真实 MySQL/Qdrant，不默认消耗云 API。

复现：项目根目录执行 ./scripts/test-infrastructure.ps1。报告位于 backend/target/surefire-reports，本轮日志为 .local/deepseek-regression.log。前端 npm run build 通过；仍有既有资源体积大于 500KB 的警告，本轮未拆分打包。

## 独立执行的真实模型用例

仅使用虚构资料和测试账号。先核对并发布 c01-08、c02-08 两份宠物规定及 c01-05、c02-05 两份预订流程；其余种子资料未批量发布。

| 用例 | 实际结果 | 单次端到端耗时 |
| --- | --- | --- |
| 杭州 + 洗手间查询 | searchCampsites 返回 c01/c02 | 约 2.65 秒 |
| “第一个营地”的宠物规则追问 | searchKnowledge 使用 c01，引用接口验证当前版本及营地归属 | 约 2.08 秒 |
| 停车标识投诉草稿 | 模型准备草稿；确认前正式工单数量不变 | 约 6.22 秒 |
| 本人工单处理进度 | queryMyTickets 返回实际“已解决”状态 | 约 2.01 秒 |
| 实时余位/报价边界回归 | 明确工具无法查询，需转交客服；宠物规则仍有有效引用 | 约 6.21 秒 |
| 中英文 PNG 文字提取 | 两种语言测试文字核对通过，版本停留 REVIEW，未发布 | 约 1.66 秒 |

另经 REST 验证：重复确认返回同一正式工单；user2 读取 user 工单为 404；staff 按待处理→处理中→已解决流转，user 能读取结果。保留一张【DeepSeek本机联调】虚构工单。

原始非敏感结果：.local/deepseek-smoke.json、.local/deepseek-ocr-smoke.json、.local/deepseek-boundary-smoke.json。它们不含 Key 或登录令牌，且作为运行产物被 Git 忽略。耗时仅为本机单次观测，投诉用例已超过 3 秒，不能宣称所有咨询均在 3 秒内完成。

首轮模型回复曾承诺“帮忙核对报价和余位”，并将草稿编号称为工单号；随后收紧系统提示词，明确没有实时库存/报价工具、草稿没有正式工单状态。修正后再次实际提问，模型明确表示无法查询实时报价和余位、需客服核对，并保留规则引用。自然语言仍需质量评测，后端对正式工单写入的确认约束不依赖模型自觉。

## 如何继续完整 RAG

1. 保持 CHAT_PROVIDER=deepseek，配置单独的 DASHSCOPE_API_KEY，端点匹配百炼账号；不把 DeepSeek Key 当作百炼 Key。
2. 改 RETRIEVAL_MODE=hybrid 并重启。脚本在缺少独立百炼 Key 时明确拒绝启动。
3. 逐份重新发布当前有效资料以建立向量索引，再核对 hybrid 问答；keyword 时的发布状态不会自动生成向量。
4. 完成人工审核与证据标注后，对比关键词、向量、混合及重排序的检索与回答质量。

## 尚未验收

真实向量化/专用重排序、全量资料和 200 个候选案例的人工评测、复杂扫描件 OCR 准确率、P50/P95、并发负载、token 账单、生产镜像、公网 HTTPS 和备份恢复。本次小样本不能证明客服工作量下降 60% 或满意度 94%。

接口依据：[DeepSeek API](https://api-docs.deepseek.com/)、[工具调用](https://api-docs.deepseek.com/guides/tool_calls/)、[视觉输入](https://api-docs.deepseek.com/guides/vision/)。具体可用模型以本账号的实际接口验证为准。

