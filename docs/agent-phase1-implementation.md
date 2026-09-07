# 黑马点评智能导购 Agent：校招 MVP 实施清单

## 1. 交付目标

在现有 `hmdp-core-service` 和 `hmdp-vue3` 中完成一个可运行的智能导购页面：用户可用自然语言找店、连续修改条件、查看真实商户卡片，并在模型异常时获得普通搜索结果。

本清单以已有 `org.javaup.agent` 包为基础，不创建 `hmdp-agent` Maven 模块，也不拆分 Agent 微服务。

## 2. 最小功能闭环

```text
用户输入
  -> IntentParser 解析条件
  -> IntentNormalizer 合并会话条件
  -> ShopSearchTool / NearbyShopTool 查询
  -> ShopRankingService 过滤、排序
  -> LLM 生成说明
  -> 返回商户卡片和 SSE 文本
```

模型调用失败时：

```text
模型超时/非法输出
  -> RuleBasedIntentParser 提取关键词、预算、距离
  -> 普通商户搜索
  -> 返回 fallback=true 的结果
```

## 3. 实施任务

### M1：后端基础链路（必须）

- [ ] 定义并校验 `ChatRequest`、`ChatResponse`、`Intent`、商户卡片和错误响应。
- [ ] 完成 `/agent/chat`，校验消息长度、会话 ID 和登录上下文。
- [ ] 完成 `IntentParser`、`RuleBasedIntentParser` 与 `IntentNormalizer`。
- [ ] 完成 `ShopSearchTool`、`NearbyShopTool` 和 `ShopRankingService`。
- [ ] 价格、距离、评分、营业状态和优惠券状态均从业务数据读取，不能由模型填充。
- [ ] 附近查询使用真实定位；无定位时返回可追问信息，不使用模型虚构坐标。

### M2：会话与体验（必须）

- [ ] 用 `ConversationMemory` 保存最近 6–10 轮消息、filters 和 7 天 TTL。
- [ ] 让“预算改成 100 元以内”等追问只更新对应字段。
- [ ] 完成 Vue `AgentChat` 页面：输入、消息列表、商户卡片与详情跳转。
- [ ] 展示加载、无结果、错误和降级状态。

### M3：流式与增强（建议）

- [ ] 完成 `/agent/chat/stream`，发送 `status`、`shop_card`、`text_delta` 和 `done`。
- [ ] 使用 `clientRequestId` 和递增 `seq` 防止重连重复展示。
- [ ] 支持停止生成。
- [ ] 接入 `VoucherTool` 与 `ShopContentTool`，只增强推荐理由，不覆盖结构化字段。

### M4：可靠性与安全（建议）

- [ ] 给模型请求增加超时，失败时走规则解析 + 普通搜索降级。
- [ ] 限制输入长度、工具调用次数和每次返回商户数。
- [ ] 将用户 ID 从认证上下文获取，限制私有数据查询。
- [ ] 日志输出 `traceId`、耗时、工具名和降级状态，并对敏感内容脱敏。

## 4. 接口与数据约定

```text
POST   /agent/chat
POST   /agent/chat/stream
GET    /agent/conversations/{conversationId}/messages
DELETE /agent/conversations/{conversationId}
```

请求：

```json
{
  "conversationId": "c_10001",
  "message": "拱墅区人均 100 元以内适合约会的餐厅",
  "clientRequestId": "r_001"
}
```

响应至少包含：`conversationId`、`answer`、`cards`、`filters`、`fallback`、`traceId`。每张商户卡片包含商户 ID、名称、距离、评分、人均价格、营业状态、优惠券摘要和推荐理由。

Redis Key：

```text
agent:conversation:{conversationId}
agent:conversation:user:{userId}
```

## 5. 测试清单

- [ ] 预算、距离、评分、营业时间和优惠券的硬过滤测试。
- [ ] `IntentNormalizer` 的历史条件合并与越界值测试。
- [ ] `NearbyShopTool` 的米单位转换、无定位、无结果和 Redis 异常测试。
- [ ] 模型成功、超时和非法 JSON 的客户端契约测试。
- [ ] `/agent/chat` 与 SSE 事件顺序、停止生成、重复请求测试。
- [ ] 会话 TTL、删除和越权访问测试。
- [ ] 前端至少验证正常推荐、无结果与降级三种状态。

不要求为了本项目接入 Testcontainers、Kafka、Prometheus、OpenTelemetry 或完整线上压测；已有测试应优先覆盖最能证明 Agent 工程能力的规则和降级路径。

## 6. Demo 验收

完成后，按以下顺序演示：

1. 输入“附近 3 公里、人均 100 元以内的火锅店”，展示真实商户卡片。
2. 继续输入“晚上 9 点还营业”，说明会话条件被正确覆盖。
3. 输入无法满足的组合条件，展示无结果与放宽建议。
4. 模拟模型超时，展示普通搜索降级提示。
5. 展示单元测试结果，并说明“模型理解、代码校验”的职责划分。

## 7. 非本阶段内容

向量检索、RAG、自动偏好学习、复杂推荐算法、多 Agent、独立微服务、灰度发布、全链路可观测、领券/下单等均不属于校招 MVP。若面试被问到，可说明它们是建立在当前工具边界、结构化卡片和降级机制之上的后续扩展。
