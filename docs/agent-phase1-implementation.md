# 黑马点评智能导购 Agent Phase 1 实施方案

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 对应总体方案 | `docs/agent-technical-design.md` |
| 实施阶段 | Phase 1：只读导购 MVP |
| 目标 | 自然语言找店、连续追问、结构化卡片、SSE 和异常降级 |
| 不包含 | 领取/秒杀优惠券、订阅、下单、用户画像自动学习、向量检索 |
| 完成标准 | 满足第 12 节验收条件并通过测试 |

## 2. 交付范围

### 2.1 用户能力

- 输入自然语言查找商户。
- 支持位置、品类、预算、半径、评分、营业时间、场景和优惠券条件。
- 支持至少 3 轮连续追问和条件修改。
- 展示文本、商户卡片、优惠券卡片、无结果建议和降级提示。
- 点击卡片跳转现有商户详情、博客或优惠券页面。
- 登录用户可继续历史会话；未登录用户支持基础商户查询。

### 2.2 工程能力

- 新增 `hmdp-agent` Maven 模块，并接入现有 Spring Boot 应用。
- 接入一个 OpenAI 兼容模型，使用结构化意图解析和工具调用。
- Redis 保存会话和筛选条件，支持 TTL、删除和清空上下文。
- 提供同步 JSON 接口和 POST SSE 流式接口。
- 模型、Redis 或工具异常时降级到普通商户搜索。
- 记录 Trace、工具耗时、Token、首 Token 延迟和降级指标。

## 3. 代码与模块变更

### 3.1 Maven

根 `pom.xml` 增加：

```xml
<module>hmdp-agent-api</module>
<module>hmdp-agent</module>
```

如果项目希望减少模块数量，可第一版只增加 `hmdp-agent`；但必须使用 Port/Adapter 隔离核心业务依赖。

`hmdp-agent` 至少引入：

- Spring Boot Web、Validation、Actuator。
- Spring AI 对应 starter 和 BOM。
- Redis/Redisson、MyBatis-Plus 共享依赖。
- Micrometer Prometheus。
- 测试依赖和 SSE 测试工具。

### 3.2 后端目录

```text
hmdp-agent/src/main/java/org/javaup/agent
├── controller
│   └── AgentChatController.java
├── application
│   ├── AgentOrchestrator.java
│   ├── AgentContextService.java
│   └── RecommendationService.java
├── model
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── AgentEvent.java
│   ├── Intent.java
│   └── ToolCallResult.java
├── tool
│   ├── AgentTool.java
│   ├── ToolRegistry.java
│   ├── ShopSearchTool.java
│   ├── NearbyShopTool.java
│   ├── ShopDetailTool.java
│   ├── VoucherTool.java
│   ├── ShopContentTool.java
│   └── UserPreferenceTool.java
├── memory
│   ├── ConversationMemory.java
│   └── ConversationSummaryService.java
├── ranking
│   └── ShopRankingService.java
├── policy
│   └── ToolPermissionPolicy.java
└── observability
    ├── AgentMetrics.java
    └── AgentTraceService.java
```

### 3.3 前端目录

```text
hmdp-vue3/src/views/agent
├── AgentChat.vue
├── AgentMessage.vue
├── AgentInput.vue
├── ShopRecommendationCard.vue
├── VoucherCard.vue
└── ActionConfirmDialog.vue
```

Phase 1 的 `ActionConfirmDialog.vue` 只保留组件骨架或用于非副作用的二次确认提示，不接入领取、订阅和订单操作。

## 4. 接口实现

### 4.1 Controller

```text
POST   /agent/chat
POST   /agent/chat/stream
GET    /agent/conversations
GET    /agent/conversations/{conversationId}/messages
DELETE /agent/conversations/{conversationId}
```

请求 DTO：

```json
{
  "conversationId": "c_10001",
  "message": "拱墅区人均100以内适合约会的餐厅",
  "stream": true,
  "clientRequestId": "r_001"
}
```

校验规则：

- `message` 非空，长度 1-500 字符。
- `conversationId` 可选；存在时校验归属和格式。
- `clientRequestId` 可选但建议前端每次生成，用于停止、重连和幂等。
- 单用户/IP/会话限流，超限返回 `AGENT_RATE_LIMITED`。

### 4.2 同步响应

```json
{
  "conversationId": "c_10001",
  "answer": "我为你找到 3 家比较合适的餐厅。",
  "cards": [
    {
      "type": "shop",
      "shopId": 1,
      "name": "103茶餐厅",
      "distanceMeter": 850,
      "score": 4.5,
      "averagePrice": 80,
      "openNow": true,
      "reason": "价格符合预算，晚间营业，距离较近"
    }
  ],
  "filters": {"location": "拱墅区", "budgetMax": 100, "scene": "约会"},
  "pendingAction": null,
  "fallback": false,
  "traceId": "trace-xxx"
}
```

### 4.3 SSE

`/agent/chat/stream` 使用 `text/event-stream`，事件顺序如下：

```text
status -> filter_update -> status -> shop_card/voucher_card
       -> text_delta (one or more) -> done
```

异常时发送 `error` 或 `fallback` 后发送 `done`。前端必须按 `seq` 去重，停止按钮使用 `AbortController` 中止请求。

## 5. 编排器实现

采用两阶段调用，不实现无限循环：

### 5.1 阶段 A：意图解析

输入最近上下文和用户新消息，模型输出 `Intent`：

```json
{
  "intent": "SHOP_RECOMMENDATION",
  "keyword": "日料",
  "location": "拱墅区",
  "latitude": null,
  "longitude": null,
  "radiusMeter": 3000,
  "budgetMax": 100,
  "minScore": null,
  "openAt": "21:00",
  "scene": "约会",
  "needVoucher": false
}
```

解析结果由 Java 归一化：模糊词映射默认值、合并历史筛选条件、清理越界数值。不能确定的字段保留 `null`，必要时向用户追问，而不是猜测。

### 5.2 阶段 B：查询和解释

编排器根据 Intent 调用工具，先做硬过滤，再排序。只有存在候选结果时才调用内容工具；Top N 建议为 3-5 家。最终将已校验 DTO 传给模型生成理由，模型不能修改卡片结构化字段。

伪代码：

```java
Intent intent = llmClient.parseIntent(context, request.message());
Intent normalized = contextService.normalize(intent, context);
List<ShopCandidate> candidates = toolRegistry.search(normalized, context);
List<ShopCard> cards = recommendationService.rankAndValidate(candidates, normalized);
List<ContentEvidence> evidence = contentService.enrich(cards, normalized);
String answer = llmClient.explain(normalized, cards, evidence);
return responseFactory.build(answer, cards, normalized, trace);
```

## 6. 工具实现任务

### 6.1 `searchShops`

- 复用 `IShopService` 或抽取 `ShopQueryPort`。
- 支持关键词、类型、价格、评分、营业时间和是否有有效券。
- 返回最多 10 条标准化商户 DTO。
- 业务查询失败映射为 `AGENT_TOOL_TIMEOUT` 或内部错误，不把异常堆栈返回模型。

### 6.2 `searchNearbyShops`

- 从请求上下文或用户定位获得经纬度；不接受模型伪造的用户位置。
- Redis GEO 查询候选 ID，再批量从 MySQL 查询详情。
- 计算距离并在 Java 层再次过滤半径。
- 无定位时返回可追问状态或降级为区域/关键词搜索。

### 6.3 `getShopDetail`

- 校验 `shopId` 为正整数并通过权限策略。
- 只返回用户可见字段，避免暴露内部库存或运营字段。

### 6.4 `listShopVouchers`

- 查询有效期、售价、面值、规则、库存状态和秒杀标识。
- 不执行领取或库存扣减。
- 当前用户领取资格属于私有字段，必须登录后通过认证上下文查询。

### 6.5 `getShopContent`

- 查询博客、评论和摘要，限制条数和内容长度。
- 输出标识为“不可信内容证据”，不能改变系统指令和工具权限。
- 内容只用于解释和相关性，不覆盖实时价格、库存和营业状态。

### 6.6 `getUserPreference`

- 未登录直接返回空偏好。
- 只读 `user_preference` 中达到最低置信度的记录。
- Phase 1 不因一次对话自动写入偏好。

## 7. 会话与 Redis 实现

使用 `Redisson` 或 Spring Data Redis 封装 `ConversationMemory`：

- 新会话生成 `c_` 前缀的 ID，并建立用户索引。
- 保存最近 6-10 轮消息、当前 filters、摘要和版本号。
- 默认 TTL 7 天，访问时续期。
- 删除会话时同时删除消息 Key、用户索引和锁 Key。
- 使用会话锁或版本号避免同一会话并发请求覆盖上下文。
- 超过 Token 预算时先摘要旧消息，再保留最近消息和当前筛选条件。

## 8. 排序与降级实现

排序分采用总体方案中的 0.25/0.20/0.20/0.15/0.10/0.10 权重。实现顺序：

1. 过滤已打烊、超预算、超半径和无有效券的硬约束结果。
2. 对距离、价格、评分、营业、优惠券和内容相关性分别归一化。
3. 缺失字段标记为 `missing`，不得补造，降低分数并在卡片提示。
4. 取前 10 条返回，内容增强只处理前 3-5 条。

模型或工具异常降级链路：

```text
LLM 超时/限流/非法输出
  -> KeywordFallbackParser 提取关键词、预算、半径和时间
  -> 调用普通 Shop 查询接口
  -> 返回标准商户卡片
  -> fallback=true，错误码 AGENT_FALLBACK
```

降级响应不得伪装成智能推荐成功；前端显示“智能推荐暂时不可用，已为你切换到普通搜索”。

## 9. 前端实现任务

- 新增 `/agent` 路由和 Agent 页面入口。
- 使用 Pinia 保存当前会话 ID、消息列表、filters、连接状态和错误状态。
- `AgentInput` 支持发送、停止、重新生成和快捷问题。
- 通过 `fetch-event-source` 或 `fetch` 处理 SSE 事件，并按事件类型增量更新消息。
- `AgentMessage` 区分普通文本、工具状态、无结果、降级和错误消息。
- `ShopRecommendationCard` 展示名称、类型、地址、距离、评分、人均、营业状态、优惠券摘要和推荐理由。
- 所有跳转复用现有商户详情、博客和优惠券页面，不复制业务详情逻辑。
- 移动端适配：消息区可滚动，卡片宽度不超过视口，输入区固定在底部但不遮挡键盘。

## 10. 配置与运行

建议新增配置前缀 `agent`：

```yaml
agent:
  enabled: true
  llm:
    base-url: ${AGENT_LLM_BASE_URL}
    api-key: ${AGENT_LLM_API_KEY}
    model: ${AGENT_LLM_MODEL}
    connect-timeout: 2s
    read-timeout: 8s
  conversation:
    ttl: 7d
    max-rounds: 10
  limits:
    max-tool-calls: 5
    max-shops: 10
    max-input-length: 500
    rate-limit-window: 60s
    rate-limit-per-window: 20
```

`agent.enabled=false` 时不注册 Agent HTTP Controller；接口按客户端 IP、登录用户和会话 ID 分别限流。Redis 限流不可用时采用 fail-open，避免影响现有核心业务链路。

开发环境通过 `.env` 或本地未提交配置提供模型密钥；测试环境使用 WireMock，不调用真实模型。生产环境通过密钥管理服务或容器 Secret 注入。

## 11. 开发任务拆分

### M1：骨架和契约

- [ ] 创建 `hmdp-agent`（可选 `hmdp-agent-api`）模块并接入根 POM。
- [ ] 定义 Request、Response、Intent、Card、AgentEvent 和错误码。
- [ ] 定义业务 Port、`AgentTool` 和 `ToolRegistry`。
- [ ] 增加配置绑定、认证上下文和统一异常处理。

### M2：会话和同步链路

- [ ] 实现 Redis 会话读写、TTL、删除、清空和摘要。
- [ ] 实现结构化意图解析和字段归一化。
- [ ] 实现 `searchShops`、`searchNearbyShops`、`getShopDetail`。
- [ ] 实现排序、卡片校验和 `/agent/chat`。

### M3：优惠券、内容和 SSE

- [ ] 实现 `listShopVouchers`、`getShopContent`、`getUserPreference`。
- [ ] 实现 `/agent/chat/stream` 和事件协议。
- [x] 增加停止生成、断线重连和幂等处理（请求级取消、完成结果缓存；断线重连使用相同 `clientRequestId` 重放结果）。
- [ ] 完成 Vue3 对话页和卡片组件。

### M4：降级、观测和安全

- [ ] 实现关键词降级解析器和 `AGENT_FALLBACK`。
- [ ] 接入限流、超时、有限重试和熔断。
- [x] 接入基础 Trace、Micrometer 请求/工具/降级/无结果指标和脱敏日志约束。
- [ ] 完成提示词注入、越权和超长输入防护。

## 12. 测试与验收

### 12.1 自动化测试

- [ ] Intent 解析结果的 JSON Schema 和 Bean Validation 测试。
- [ ] 预算、距离、营业时间、优惠券和硬约束过滤测试。
- [ ] 排序权重、缺失字段和推荐理由证据测试。
- [ ] Redis 会话 TTL、摘要、删除和并发覆盖测试。
- [ ] Testcontainers 验证 MySQL、Redis GEO 和 Kafka 配置。
- [ ] WireMock 模拟模型成功、超时、限流、非法 JSON 和工具调用。
- [x] 增加 SSE 停止/幂等协议、Intent 归一化和模型 HTTP 合约测试；真实 Redis/GEO 集成测试需在允许 loopback 且服务依赖已启动的环境执行。
- [ ] 越权访问、评论注入、敏感字段脱敏和请求限流测试。

### 12.2 MVP 验收标准

1. 用户可以用自然语言搜索商户，并返回真实商户 ID。
2. 预算、位置、品类、距离和时间至少能正确转换为结构化条件。
3. 返回结果满足硬约束；价格、距离、营业状态和优惠券状态来自业务数据。
4. 支持至少 3 轮连续追问和条件修改。
5. 同时返回文本和结构化商户卡片。
6. 模型超时、Redis 异常或工具失败时能返回普通搜索结果或明确错误。
7. 未登录用户不能读取其他用户私有资格或偏好。
8. 每次请求有 `traceId`、工具调用日志和关键延迟指标。
9. 未经确认不执行领取、秒杀、订阅、购买和订单操作。
10. 页面在桌面和移动端可用，停止生成后不会继续追加内容。

## 13. 上线、灰度与回滚

### 13.1 上线前

- 使用 WireMock 和测试模型完成契约测试。
- 准备至少 50 条评测问题并记录基线结果。
- 配置模型超时、限流、Token 上限、Redis TTL 和 Prometheus 告警。
- 通过 feature flag 控制 Agent 页面和接口开关。

### 13.2 灰度

- 先对内部账号或小比例登录用户开放。
- 重点观察成功率、首 Token 延迟、降级率、无结果率、卡片点击率和 Token 成本。
- 发现模型异常、越权或核心服务受影响时立即关闭 `agent.enabled`，普通商户接口保持可用。

### 13.3 回滚

- 应用层：关闭 feature flag 或回滚 Agent 模块版本。
- 数据层：删除会话 Key 不影响核心业务；偏好表本阶段不自动写入，避免回滚数据污染。
- 模型层：切换到备用 OpenAI 兼容模型或强制普通搜索降级。

## 14. Phase 1 完成后的后续入口

- Phase 2 在本方案基础上增加场景标签、关键词检索和内容相关性排序，不改变基础工具契约。
- Phase 3 在独立的确认、幂等、审计和权限框架上增加订阅提醒，不能直接复用只读工具改写为写操作。
- Phase 4 将本阶段 Trace、卡片点击和评测集接入质量分析，调整排序权重并监控幻觉率。
