# 黑马点评智能导购 Agent 技术方案

## 1. 目标与边界

Phase 1 的目标是在现有黑马点评系统中完成“自然语言找店”的最小闭环：解析用户条件、查询真实业务数据、代码过滤排序、生成推荐说明，并支持多轮会话和异常降级。

项目采用**模块化单体**。Agent 代码位于现有 `hmdp-core-service` 的 `org.javaup.agent` 包中，与既有认证、MySQL、Redis 和商户服务同进程运行；不新增 Maven 模块或独立服务。

## 2. 技术选型

| 能力 | 方案 | 原因 |
| --- | --- | --- |
| 后端 | Java 17、Spring Boot、MyBatis-Plus | 复用现有工程 |
| 前端 | Vue 3、Vite、Pinia、Element Plus | 复用 `hmdp-vue3` |
| 模型接入 | OpenAI 兼容 HTTP 客户端 | 可切换模型供应商，密钥由环境变量注入 |
| 会话与附近检索 | Redis、Redis GEO | 保存有限上下文，支持附近商户查询 |
| 流式响应 | Spring MVC `SseEmitter` + 浏览器 `fetch` | 实现简单，便于展示生成过程 |
| 测试 | JUnit 5、Spring Boot Test、MockWebServer/WireMock | 覆盖规则、接口和模型异常 |

不在校招 MVP 中引入 Kafka、向量数据库、复杂 Agent 框架、分布式追踪或独立微服务。它们可在面试中作为后续演进方向说明。

## 3. 架构与执行流程

```text
Vue AgentChat
  -> AgentChatController
  -> AgentOrchestrator
       -> IntentParser / IntentNormalizer
       -> AgentTool（商户、附近、优惠券、内容）
       -> ShopRankingService
       -> ConversationMemory（Redis）
       -> DeepSeekClient / OpenAI-compatible LLM
  -> MySQL / Redis GEO / existing services
```

一次请求按以下顺序执行：

1. 校验消息长度、会话归属和访问频率。
2. 读取 Redis 中的最近消息与筛选条件。
3. 调用模型或规则解析器，得到结构化 `Intent`。
4. `IntentNormalizer` 合并历史条件、使用默认值并清理越界参数。
5. 按白名单调用只读业务工具。
6. `ShopRankingService` 先执行硬过滤，再进行排序。
7. 将已经校验的卡片数据交给模型生成简短说明。
8. 保存会话并返回 JSON 或 SSE 事件。
9. 模型失败时使用关键词解析器执行普通搜索降级。

模型不直接访问数据库、Redis 或 Spring Bean，也不决定商户卡片里的价格、距离、营业状态与优惠券字段。

## 4. 核心组件

| 组件 | 职责 |
| --- | --- |
| `AgentChatController` | 接收同步和 SSE 请求，处理取消与错误响应 |
| `AgentOrchestrator` | 编排解析、工具查询、排序、解释和降级流程 |
| `IntentParser` / `IntentNormalizer` | 解析自然语言，合并上下文、校验范围 |
| `AgentTool` | 定义显式允许的只读查询能力 |
| `ShopSearchTool` / `NearbyShopTool` | 查询普通商户和附近商户 |
| `VoucherTool` / `ShopContentTool` | 查询有效券与内容摘要，作为补充信息 |
| `ShopRankingService` | 执行硬过滤和可解释排序 |
| `ConversationMemory` | 保存最近消息、筛选条件与 TTL |
| `DeepSeekClient` | 调用 OpenAI 兼容模型接口 |

工具输入需要做 Bean Validation，最大条数、半径、预算和文本长度受限。工具只返回面向 Agent 的 DTO，不直接暴露数据库 Entity。

## 5. 数据真实性与排序

业务代码处理硬约束：超预算、超半径、低于最低评分、指定时间未营业、没有有效优惠券的商户不能进入结果。其余候选按距离、价格、评分和优惠券等简单分数排序。

模型只根据已校验结果写推荐理由，例如“距离约 850 米，人均约 80 元”。数据缺失时卡片标记为“信息缺失”，不能补造。

附近查询从请求上下文读取经纬度，不能相信模型生成的位置。Redis GEO 的距离统一转换为米；要区分无定位、索引为空、Redis 异常和真实无结果。

## 6. 会话、接口与 SSE

Redis Key：

```text
agent:conversation:{conversationId}
agent:conversation:user:{userId}
```

会话保存最近 6–10 轮消息、当前筛选条件和更新时间，默认 TTL 为 7 天。后续提问只覆盖用户明确修改的条件。

```text
POST   /agent/chat
POST   /agent/chat/stream
GET    /agent/conversations/{conversationId}/messages
DELETE /agent/conversations/{conversationId}
```

SSE 使用 `status`、`filter_update`、`shop_card`、`text_delta`、`fallback`、`error`、`done` 事件。事件带递增 `seq`；`clientRequestId` 用于避免重连导致重复写入。

## 7. 容错与基础安全

- 单次请求限制工具调用次数和输入长度，并为模型请求设置超时。
- 模型超时、限流或结构化输出非法时，改用关键词、预算和距离的规则提取后调用普通搜索；响应标记 `fallback=true`。
- Redis 会话不可用时，允许单轮查询，并提示上下文未保存。
- 用户身份从认证上下文读取；未登录用户不能读取偏好或私有资格。
- 博客、评论和用户输入均是不可信文本，只能作为内容证据，不能影响工具白名单与权限。
- 日志只记录 `traceId`、工具名称、耗时、是否降级和错误摘要；不得输出密钥、Cookie 或完整敏感内容。

## 8. 测试与验收

至少覆盖：

- 意图归一化、预算/距离/营业时间等硬过滤与排序。
- 多轮条件合并、会话 TTL 和会话归属校验。
- 附近查询的距离单位，以及无定位、无结果和 Redis 异常。
- 模型正常、超时、非法 JSON 与关键词降级。
- 同步响应与 SSE 事件顺序、停止生成和重复请求。
- 越权查询、超长输入和敏感日志脱敏。

面试演示准备 10–15 条覆盖预算、位置、连续追问、无结果和模型降级的问题即可；记录每个问题的预期筛选条件与关键断言。正式线上评测、灰度和监控告警属于后续优化，不作为本阶段交付承诺。

## 9. 后续可选演进

当 MVP 稳定后，再按需要增加场景标签、关键词检索、用户明确偏好和更丰富的评测样本。只有数据规模和检索质量确实成为瓶颈时，再考虑向量检索或搜索引擎；涉及领券、订阅或下单时，必须另行设计确认、幂等和权限校验。
