# 接口说明

后端默认 `http://127.0.0.1:8081/api`。除 health 和 login 外，请求携带 `Authorization: Bearer <token>`，凭证有效 1 小时。成功响应直接返回对象/数组，失败返回 HTTP 状态和 `message`。

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| POST /auth/login | 公开 | JSON username、password，返回 token 和 user |
| GET /auth/me | 登录 | 当前身份 |
| GET /health | 公开 | status、mode、fictionalData、chatProvider、chatModel、retrievalMode |
| GET /campsites | 登录 | city、facility、keyword 可选筛选 |
| GET /campsites/{id} | 登录 | 当前本地营地事实 |
| GET /discovery/campsites | 登录 | 高德营地候选。`city` 必填，`keyword` 默认「露营地」 |
| GET /discovery/campsites/{poiId} | 当前搜索所属用户 | `searchId` 必填，从本次候选获取 `poiId` |
| POST /discovery/follow-up | 当前搜索所属用户 | JSON `searchId`、`question` 必填，`placeId` 可选 |
| POST /chat | 登录 | message 必填，conversationId 可选；最多 2000 字 |
| GET /conversations | 登录 | 本人会话 |
| GET /conversations/{id}/messages | 所有者 | 本人消息、引用与草稿快照 |
| POST /ticket-drafts/{id}/confirm | 草稿所有者 | 幂等确认；旧草稿 409 |
| GET /tickets | 登录 | 本人工单 |
| GET /tickets/{id} | 所有者 | 工单与公开处理记录 |
| GET /staff/tickets | STAFF | 客服队列 |
| GET /staff/tickets/{id} | STAFF | 工单详情 |
| PATCH /staff/tickets/{id} | STAFF | JSON status、reply、revision；版本冲突 409 |
| GET /ops/campsites | OPS | 营地列表 |
| POST /ops/campsites | OPS | 新增营地 |
| PUT /ops/campsites/{id} | OPS | 编辑营地 |
| GET /ops/documents | OPS | 文档与版本列表 |
| POST /ops/documents | OPS | multipart file、campsiteId、title、category、visibility，documentId 可选 |
| GET /ops/documents/{id} | OPS | 版本、提取内容与失败原因 |
| POST /ops/documents/{id}/publish | OPS | JSON versionId；核对后发布 |
| POST /ops/documents/{id}/unpublish | OPS | 下架，新的检索立即排除 |
| GET /knowledge/citations/{id} | 资料权限 | 原文，historical 表示历史版本 |
| GET /ops/metrics | OPS | 请求/耗时、工单、文档状态汇总 |

营地编辑字段：name、city、terrain、facilities、feeDesc、description。设施为逗号分隔文字。

聊天响应：requestId、conversationId、answer、citations、toolSummary、pendingTicketDraft、campsites、mode、chatProvider、retrievalMode。`pendingTicketDraft` 仅代表草稿，不能作为工单已提交的依据。引用格式为 `[[片段UUID]]`，来源原文通过引用接口校验权限。mode=cloud 表示真实模型模式；retrievalMode=keyword 为 BM25 关键词检索，hybrid 为向量融合及重排序。health 反映配置与进程存活，不代替实际模型请求。

### 高德地点查询

搜索返回 `searchId`、`city`、`source`、`queriedAt` 和最多 5 条 `places`。每条含 `id`、`name`、`address`。详情含 `id`、`name`、`city`、`address`、`location`、`category`、`source`、`queriedAt`。`queriedAt` 记录请求时刻。独立查询页按用户保留最近一次搜索，聊天按用户和会话保留各自的结果。上下文在 10 分钟后失效。

规则追问用「第二个」一类序号或 `placeId` 指定营地。只有一个结果时可省略。响应含 `placeId`、`answer`，当前对真实地点统一说明缺少审核资料。地点查询和追问是只读接口。

无候选返回 200 和空数组。400 为城市、关键词或序号输入无效。404 为候选不可访问、过期或详情未通过身份核对。429 为地图限流，502 为上游错误，503 为地图关闭或缺 Key，504 为超时。

### 资料与工单

资料版本状态：PROCESSING → REVIEW → PUBLISHED；失败为 FAILED。逻辑文档 current_version 指向实际生效版本，下架后为空。历史版本可追溯，当前检索只读取有效发布版本。重启中断的解析任务标记 FAILED，可重新上传。

工单状态：待处理 → 处理中 → 已解决/已关闭；每次客服操作必须填写 reply，并携带当前 revision。用户只查自己的工单；模型没有客服改状态和正式提交工具。

已生成工单草稿且没有引用的回答，统一提示核对草稿并点击确认；知识检索没有命中不会覆盖这条草稿提示。草稿仍不会自动提交。

模型工具准备草稿时仅校验并暂存内容；回答通过引用和发布状态校验后，才在同一短事务内替换草稿并保存用户、助手消息。模型失败或消息写入失败均保留旧草稿。正式确认接口及其幂等规则不变。

错误：400 输入无效；401 未登录/凭证失效；403 角色不足；404 资源不存在或不属于本人；409 状态冲突；413 文件过大；429 请求额度/并发限制；502 云端或检索服务失败；503 未配置 Key。

### 聊天中的真实地点

`POST /chat` 的请求格式沿用原有 `message` 和可选 `conversationId`。首次查找需要明确城市。响应增加可选的 `publicLookup`、`publicSearch`、`publicDetail`、`publicAnswer`，分别标识真实查询及本次返回的候选、详情和规则提示。字段结构与独立查询接口相同。

「第二个」「它」等明确指代的地址与规则追问使用当前会话的选择范围，错误序号为 400，结果失效或缺少上下文为 404，选择已变更为 409。地图限流等上游状态保留原有含义。

聊天历史只保留 `publicLookup` 标识，地点卡片由当前响应展示。浏览器重新加载后可重新查询卡片。当前版本的选中地点保存在进程内，后端重启后需重新搜索。

### 本地身份与引用修正

具名本地规则问题由后端先检索，再提供本轮工具证据给模型。没有正文引用时，规则回答只能使用本轮检索原文或明确的资料缺口；营地事实、工单查询和草稿只按实际工具结果生成提示。没有工具依据的自由回答统一转为补充条件提示。合法片段 ID 仍不等于已完成语义正确性评测。

当前消息点名完整的本地营地名称且未明确要求真实或高德查询时，本轮模型可用工具限定为本地业务工具。明确要求地图查询时，可使用公开地点工具。同名地点按 POI ID 和本次结果顺序选择。

模型返回本轮证据清单以外的引用时，若已有检索片段且剩余轮数允许，后端最多请求一次回答修正。该请求提供本轮有效片段 ID，并关闭工具调用。修正结果重新经过引用、资料版本和权限校验。持续无效仍返回 502，失败回答不保存为成功消息。一次修正会增加一次模型请求。
