# 黑马点评智能导购 Agent 技术方案

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 文档名称 | 黑马点评智能导购 Agent 技术方案 |
| 关联产品文档 | `docs/agent-product-design.md` |
| 适用范围 | Phase 1-4 的总体技术设计 |
| 当前重点 | Phase 1 只读导购 MVP |
| 状态 | 技术方案 |

## 2. 设计目标与约束

### 2.1 目标

系统为商户、优惠券、博客和用户体系提供自然语言入口，完成“理解需求 -> 查询真实数据 -> 过滤排序 -> 生成解释 -> 返回可操作卡片”的闭环，并支持连续追问、SSE 流式响应和模型异常降级。

### 2.2 约束

- 模型只负责意图理解、工具编排和语言生成，不能直接访问数据库、Redis 或 Spring Bean。
- 价格、库存、营业时间、距离、优惠券有效性由业务代码最终校验。
- Phase 1 只注册只读工具，不执行领取、秒杀、订阅、下单等副作用操作。
- Agent 故障不能影响现有商户、博客、优惠券和订单链路。
- 优先复用当前 Java 17、Spring Boot 3.5.x、MyBatis-Plus、Redis、Kafka 和 Vue3 工程。

## 3. 技术选型

| 层次 | 选型 | 说明 |
| --- | --- | --- |
| 前端 | Vue 3、Vite、Pinia、Element Plus | 与 `hmdp-vue3` 现有体系一致 |
| 前后端流式通信 | Spring MVC `SseEmitter` + `fetch-event-source` 或 `fetch` 流读取 | 对话接口使用 POST，可携带认证信息和请求体 |
| 运行时 | Java 17、Spring Boot 3.5.x | 复用当前根 POM 版本 |
| LLM 接入 | Spring AI + OpenAI 兼容协议 | 支持云模型、国内模型和自部署模型切换 |
| Agent 编排 | 自研 `AgentOrchestrator`、`ToolRegistry`、策略组件 | MVP 流程受控，避免引入复杂自主规划框架 |
| 结构化解析 | Java DTO、JSON Schema、Jakarta Bean Validation | 将预算、距离、时间和场景转换为明确字段 |
| 业务数据 | MySQL 8、MyBatis-Plus | 商户、优惠券、博客和用户偏好查询 |
| 地理检索 | Redis GEO / `GEOSEARCH` | 附近商户和半径筛选 |
| 会话记忆 | Redis、Redisson | 消息、筛选条件、摘要、TTL 和并发控制 |
| 异步事件 | Kafka | 行为事件、异步指标和后续提醒能力 |
| 稳定性 | 项目已有限流、锁、重试能力；新增 Resilience4j 时统一封装 | 工具超时、有限重试、熔断和隔离 |
| 可观测性 | Micrometer、Actuator、Prometheus、OpenTelemetry | 指标、日志和 Trace 关联 |
| 测试 | JUnit 5、Spring Boot Test、Testcontainers、WireMock/MockWebServer | 覆盖模型、工具、Redis、MySQL、Kafka 和 SSE |
| 部署 | 现有服务部署方式；本地 Docker Compose | MVP 不新增独立运行时服务 |

模型配置只通过环境变量或外部配置注入，例如 `AGENT_LLM_BASE_URL`、`AGENT_LLM_API_KEY`、`AGENT_LLM_MODEL`。密钥不得提交到仓库或写入前端。

## 4. 总体架构

```text
Vue3 AgentChat
       |
       | HTTP POST /agent/chat 或 /agent/chat/stream
       v
AgentChatController
       |
       v
AgentOrchestrator
  |        |         |
  |        |         +--> ConversationMemory (Redis)
  |        +------------> ToolRegistry -> 业务 Port/Adapter
  +---------------------> LlmClient -> OpenAI 兼容模型
                              |
                              v
             MySQL / Redis GEO / 现有 Shop、Voucher、Blog、User Service
```

### 4.1 部署形态

Phase 1 将 `hmdp-agent` 作为根 Maven 工程的子模块，并与 `hmdp-core-service` 在同一个 Spring Boot 进程中运行。这样可以复用现有认证、Redis、数据库、Kafka、限流和监控配置，降低网络调用和部署复杂度。

后续若模型调用、内容检索或发布节奏需要独立扩容，再将 Agent 模块拆为独立服务；对外 API 和工具 Port 保持不变。

### 4.2 模块依赖方向

推荐依赖关系：

```text
hmdp-agent-api        # 请求/响应 DTO、业务 Port、事件模型
       ^
       |
hmdp-agent            # Agent 编排、工具、记忆、排序、策略
       ^
       |
hmdp-core-service     # 现有业务实现和可执行应用
```

如果暂不新增 `hmdp-agent-api`，则在 `hmdp-agent` 内定义 Port，并由核心服务通过适配器提供实现。禁止 `hmdp-agent` 依赖核心服务的启动类或形成反向循环依赖。

## 5. 核心执行流程

```text
1. 校验请求长度、频率、登录上下文和 conversationId
2. 从 Redis 读取最近消息、筛选条件和摘要
3. LLM 输出结构化 Intent DTO；不确定字段保持 null
4. Java 归一化默认规则（附近半径、预算边界、时间和场景标签）
5. AgentOrchestrator 按白名单选择并调用只读工具
6. RecommendationService 执行硬约束过滤和加权排序
7. 按需查询 Top N 商户的博客/评论作为解释依据
8. LLM 仅根据工具结果生成推荐文字和比较说明
9. 对最终卡片做字段、权限和商户 ID 二次校验
10. 写入会话、摘要和指标；模型失败时进入普通搜索降级
```

模型调用建议拆为两个阶段：意图解析阶段使用低温度和结构化输出；解释生成阶段只传入已校验的结果，限制最大输出长度。

## 6. Agent 模块设计

```text
hmdp-agent/src/main/java/org/javaup/agent
├── controller/AgentChatController
├── application/AgentOrchestrator
├── application/AgentContextService
├── application/RecommendationService
├── model/LlmClient
├── model/ChatRequest、ChatResponse、AgentEvent、Intent
├── tool/AgentTool、ToolRegistry
├── tool/ShopSearchTool、NearbyShopTool、ShopDetailTool
├── tool/VoucherTool、ShopContentTool、UserPreferenceTool
├── memory/ConversationMemory、ConversationSummaryService
├── ranking/ShopRankingService
├── policy/ToolPermissionPolicy、ConfirmationPolicy
└── observability/AgentMetrics、AgentTraceService
```

工具接口：

```java
public interface AgentTool<I, O> {
    String name();
    String description();
    Class<I> inputType();
    O execute(I input, AgentContext context);
}
```

`ToolRegistry` 只注册显式允许的工具，并在执行前统一完成参数校验、登录权限、超时、调用计数、审计摘要和错误码映射。

## 7. 工具与业务 Port

Phase 1 工具：

| 工具 | 主要输入 | 数据来源 | 未登录 |
| --- | --- | --- | --- |
| `searchShops` | 关键词、类型、预算、评分、营业时间、是否有券 | MySQL/业务 Service | 是 |
| `searchNearbyShops` | 经纬度、半径、其他过滤条件 | Redis GEO + MySQL | 是，需定位数据 |
| `getShopDetail` | `shopId` | MySQL/业务 Service | 是 |
| `listShopVouchers` | `shopId`、当前用户上下文 | MySQL/优惠券 Service | 基础信息是，私有资格需登录 |
| `getShopContent` | `shopId`、关键词、条数 | Blog/评论 Service | 是 |
| `getUserPreference` | 当前登录用户 | MySQL/Redis | 否 |

工具不得接受模型传入的可信 `userId`，用户身份必须从当前请求的认证上下文获取。所有输出使用内部 DTO，禁止将 Entity 直接暴露给模型或前端。

## 8. 数据模型与缓存

### 8.1 会话 Key

```text
agent:conversation:{conversationId}
agent:conversation:user:{userId}
agent:conversation:lock:{conversationId}
```

会话值建议使用 JSON 文档，包含最近 6-10 轮消息、当前筛选条件、摘要、更新时间和版本号。默认 TTL 7 天；每次访问续期。单条消息限制长度，工具调用只保存摘要和参数摘要。

### 8.2 消息模型

```json
{
  "messageId": "m_001",
  "conversationId": "c_10001",
  "role": "user",
  "content": "拱墅区人均100以内适合约会的餐厅",
  "filters": {"location": "拱墅区", "budgetMax": 100, "scene": "约会"},
  "toolCalls": [{"name": "searchShops", "latencyMs": 82, "success": true}],
  "createTime": "2026-09-03T18:20:00+08:00"
}
```

### 8.3 用户偏好

正式偏好数据使用 `user_preference` 表，保留 `confidence`、`source` 和更新时间。只有用户明确表达或多次高置信行为才更新偏好；一次对话不直接永久修改画像。

## 9. API 与事件协议

### 9.1 HTTP API

```text
POST   /agent/chat
POST   /agent/chat/stream
GET    /agent/conversations
GET    /agent/conversations/{conversationId}/messages
DELETE /agent/conversations/{conversationId}
```

请求：

```json
{
  "conversationId": "c_10001",
  "message": "拱墅区人均100以内适合约会的餐厅",
  "stream": true,
  "clientRequestId": "r_001"
}
```

响应统一包含 `traceId`、`conversationId`、`answer`、`cards`、`filters`、`fallback` 和 `pendingAction`。金额、距离、评分和营业状态必须来自业务查询结果。

### 9.2 SSE 事件

```text
event: status       # 理解需求、查询商户、生成回答
event: text_delta   # 增量文本
event: filter_update
event: shop_card
event: voucher_card
event: no_result
event: fallback
event: done
event: error
```

每个事件包含 `traceId`、`conversationId`、递增 `seq` 和事件数据。客户端断线重连时使用请求级幂等标识，服务端不得重复写入相同消息。

## 10. 排序与真实性校验

排序由代码完成，模型只解释：

```text
推荐分 = 距离匹配分 * 0.25
       + 价格匹配分 * 0.20
       + 评分分 * 0.20
       + 营业时间匹配分 * 0.15
       + 优惠券分 * 0.10
       + 内容相关性分 * 0.10
```

先执行硬约束：距离、预算、最低评分、指定营业时间和优惠券有效性。缺少价格、评分或营业时间时标记为“信息缺失”，不得由模型补造，并降低排序分。

生成卡片前再次检查：`shopId` 存在、字段来自本次工具结果、用户可见、优惠券仍有效。进入未来购买链路时必须再次实时校验库存和资格。

## 11. 降级、超时与限流

- 请求总超时建议 8 秒；普通查询首屏目标 2 秒，SSE 首 Token 目标 3 秒。
- 单次请求最多 5 次工具调用，最多返回 10 个商户。
- LLM 超时、限流或格式错误时，使用关键词/数字约束提取器调用普通商户搜索，并返回 `AGENT_FALLBACK`。
- 工具调用只做有限重试；读操作可重试，业务副作用操作禁止自动重试。
- 对用户、IP、会话设置 Agent 访问频率限制；熔断只隔离 Agent，不影响核心接口。
- Redis 会话不可用时，允许无上下文单轮查询，并在响应中标识上下文未保存。

错误码：`AGENT_REQUEST_INVALID`、`AGENT_RATE_LIMITED`、`AGENT_MODEL_TIMEOUT`、`AGENT_TOOL_TIMEOUT`、`AGENT_NO_RESULT`、`AGENT_FALLBACK`、`AGENT_UNAUTHORIZED`。

## 12. 安全与合规

- System Prompt、工具描述和用户内容分层传递；博客、评论等内容标记为不可信数据。
- 模型不能改变工具白名单、权限、调用次数和系统规则。
- 使用 Jakarta Validation 限制半径、价格、时间、条数和文本长度。
- 日志脱敏手机号、Token、Cookie 和用户私有字段；模型日志只保存摘要或哈希。
- 所有私有数据查询使用当前登录身份二次校验。
- Phase 1 不注册购买、领取、秒杀、订阅和订单变更工具。

## 13. 可观测性

每个请求建立 `traceId`，并关联 `userId`、`conversationId`、`intent`、`modelName`、工具名、工具耗时、Token 用量、首 Token 延迟、总耗时、降级状态和错误码。

指标：

```text
agent_request_total
agent_request_latency
agent_first_token_latency
agent_tool_call_total
agent_tool_latency
agent_tool_error_total
agent_model_token_usage
agent_fallback_total
agent_no_result_total
```

日志采用结构化 JSON，禁止打印完整 Prompt、完整评论内容和认证信息。通过 Micrometer 暴露 Prometheus 指标，OpenTelemetry 负责跨 Controller、编排器、工具和 Redis/MySQL 的 Trace。

## 14. 测试策略

- 单元测试：Intent 归一化、硬约束过滤、排序、权限策略、错误映射和会话摘要。
- 集成测试：Testcontainers 启动 MySQL、Redis、Kafka；验证 GEO、TTL、消息和业务 Port。
- 模型契约测试：WireMock/MockWebServer 模拟结构化输出、工具调用、超时、限流和非法 JSON。
- 接口测试：验证普通 JSON、SSE 事件顺序、停止生成、断线重连和幂等。
- 安全测试：越权用户、提示词注入、超长输入、恶意工具参数和敏感信息脱敏。
- 评测测试：至少 50 条真实问题，统计意图准确率、工具参数正确率、硬约束满足率、可追溯率和幻觉率。

## 15. 分阶段演进

### Phase 1：只读导购 MVP

实现本文第 4-14 节中的最小闭环：商户/附近/详情/优惠券/内容只读工具、Redis 会话、基础排序、Vue3 对话页、SSE、日志指标和降级。

### Phase 2：检索与推荐质量增强

增加场景标签、博客/评论关键词检索、内容相关性排序、筛选条件可视化、明确偏好记录和无结果改写。数据规模达到瓶颈后再引入 OpenSearch/Elasticsearch 做 BM25 + 向量混合召回。

### Phase 3：低风险动作

增加优惠券订阅和提醒。所有副作用操作必须经过确认弹窗、幂等操作号、权限校验、审计日志，并复用现有限流、令牌和消息链路。

### Phase 4：评测和优化

建立离线评测集和线上指标闭环，根据点击、收藏、详情访问和优惠券转化调整排序权重，持续监控幻觉和越权风险。

## 16. 关键决策结论

1. 采用模块化单体，不在 MVP 阶段拆独立 Agent 微服务。
2. 采用 Spring AI + OpenAI 兼容协议，保留模型供应商切换能力。
3. 采用自研受控编排器，不引入复杂 ReAct/LangGraph 运行时。
4. 先用 MySQL + Redis GEO + 关键词/标签，不提前引入向量数据库。
5. 采用 SSE 事件协议返回文本和结构化卡片。
6. 所有硬约束、排序、权限和真实性校验由 Java 代码完成。
