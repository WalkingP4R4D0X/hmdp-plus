<script setup>
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { streamAgent, stopAgent } from '@/api/agent'
import {
  createAssistant, createAgentRunner, locateAgent, filterLabels,
  errorMessage, fieldText, businessStatus, voucherMoney
} from '@/utils/agentStream'

const router = useRouter()
const input = ref('')
const messages = ref([
  { role: 'assistant', content: '你好，我是黑马点评智能导购。告诉我你想找什么店？', cards: [] }
])
const loading = ref(false)
const conversationId = ref(null)
const location = ref(null)
const locating = ref(false)
const inputError = ref('')
const quick = ['附近有什么好吃的', '拱墅区人均100以内适合约会的餐厅', '找3公里内晚上9点还营业的火锅店']
const runner = createAgentRunner({
  stream: streamAgent,
  stop: stopAgent,
  onLoading: value => { loading.value = value },
  onConversation: value => { conversationId.value = value }
})

async function send(text = input.value) {
  if (loading.value || !text?.trim()) return
  text = text.trim()
  if (text.length > 500) {
    inputError.value = '请输入 1–500 字的查店需求。'
    return
  }
  inputError.value = ''
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  // Mutate the proxy itself so incoming deltas and cards trigger Vue updates.
  const assistant = reactive(createAssistant())
  messages.value.push(assistant)
  await runner.run(assistant, {
    conversationId: conversationId.value,
    message: text,
    clientRequestId: crypto.randomUUID(),
    ...(location.value || {})
  })
}
async function locate() {
  if (locating.value) return
  locating.value = true
  location.value = await locateAgent(navigator.geolocation)
  locating.value = false
}
onMounted(locate)
onBeforeUnmount(() => runner.cancel())
function stop() {
  runner.cancel()
}
function openShop(card) {
  if (card.shopId != null) router.push(`/shopDetail/${card.shopId}`)
}
</script>

<template>
  <main class="agent-page">
    <header>
      <div>
        <span class="eyebrow">HMDP / SMART GUIDE</span>
        <h1>智能导购</h1>
        <p>把你的用餐计划说给我听。</p>
      </div>
      <button class="ghost" @click="router.push('/index')">返回首页</button>
    </header>
    <section class="chat-shell">
      <div class="location-status" role="status">
        <span>{{ locating ? '正在获取定位…' : location ? '已获取真实定位，可查询附近商户。' : '未获取定位：请允许定位，或输入所在区域、按关键词搜索。' }}</span>
        <button class="ghost" :disabled="locating" @click="locate">重新定位</button>
      </div>
      <div class="messages" aria-live="polite" aria-relevant="additions text">
        <article
          v-for="(message, index) in messages"
          :key="index"
          :class="['message', message.role]"
        >
          <div v-if="message.content || message.phase === 'loading'" class="bubble">
            {{
              message.content ||
              (message.phase === 'loading'
                ? '正在查询真实商户…'
                : '')
            }}
          </div>
          <div v-if="message.filters && filterLabels(message.filters).length" class="filters">
            <strong>已生效筛选条件</strong>
            <span v-for="label in filterLabels(message.filters)" :key="label">{{ label }}</span>
          </div>
          <div v-if="message.fallback" class="fallback">
            智能推荐暂时不可用，已切换到普通搜索
          </div>
          <p v-if="message.memorySaved === false" class="notice">上下文未保存，下次提问请补充完整筛选条件。</p>
          <p v-if="message.phase === 'error'" class="error" role="alert">{{ errorMessage(message.errorCode) }}</p>
          <p v-if="message.phase === 'stopped'" class="notice">已停止生成，以上为停止前收到的内容。</p>
          <p v-if="message.noResult" class="notice">没有符合条件的商户。可以扩大距离、提高预算或放宽营业时间后重试。</p>
          <div v-if="message.cards?.length" class="cards">
            <div
              v-for="card in message.cards"
              :key="card.shopId ?? card.name"
              class="card"
              :role="card.shopId != null ? 'link' : undefined"
              :tabindex="card.shopId != null ? 0 : undefined"
              @click="openShop(card)"
              @keydown.enter.prevent="openShop(card)"
            >
              <div class="card-top">
                <strong>{{ fieldText(card.name) }}</strong
                ><span>评分：{{ fieldText(card.score, ' 分') }}</span>
              </div>
              <p>地址：{{ fieldText(card.address) }}<template v-if="card.area"> · {{ card.area }}</template></p>
              <p class="meta">
                人均：{{ fieldText(card.averagePrice, ' 元') }} ·
                距离：{{ fieldText(card.distanceMeter == null ? null : Math.round(card.distanceMeter), ' 米') }} ·
                {{ businessStatus(card.openNow) }}
              </p>
              <p class="meta">营业时间：{{ fieldText(card.openHours) }}</p>
              <p v-if="card.missingData || card.shopId == null" class="notice">部分商户信息缺失，请在详情页确认。</p>
              <div class="vouchers">
                <strong>优惠券</strong>
                <p v-if="!Array.isArray(card.vouchers)">优惠券信息缺失</p>
                <p v-else-if="!card.vouchers.length">本次查询未返回优惠券</p>
                <div v-for="(voucher, voucherIndex) in card.vouchers" :key="voucher.voucherId ?? voucherIndex" class="voucher">
                  <p>{{ fieldText(voucher.title) }} · 付 {{ voucherMoney(voucher.payValue) }} 抵 {{ voucherMoney(voucher.actualValue) }}</p>
                  <p class="meta">{{ voucher.valid === true ? '有效' : voucher.valid === false ? '无效' : '有效状态：信息缺失' }} · {{ voucher.needSeckill === true ? '秒杀券' : voucher.needSeckill === false ? '普通券' : '券类型：信息缺失' }}</p>
                  <p class="meta">使用规则：{{ fieldText(voucher.rules) }}</p>
                  <p class="meta">有效期：{{ fieldText(voucher.beginTime) }} 至 {{ fieldText(voucher.endTime) }}</p>
                </div>
              </div>
              <small>推荐理由：{{ fieldText(card.reason) }}</small>
            </div>
          </div>
        </article>
      </div>
      <div class="quick">
        <button v-for="item in quick" :key="item" :disabled="loading" @click="send(item)">
          {{ item }}
        </button>
      </div>
      <p v-if="inputError" class="input-error error" role="alert">{{ inputError }}</p>
      <div class="composer">
        <textarea
          v-model="input"
          aria-label="查店需求"
          placeholder="例如：西湖区适合约会、人均150以内的日料"
          @keydown.enter.exact="event => { if (!event.isComposing) { event.preventDefault(); send() } }"
        /><button v-if="loading" class="stop" @click="stop">停止</button
        ><button v-else class="send" @click="send()">发送</button>
      </div>
    </section>
  </main>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;700&family=Space+Grotesk:wght@500;700&display=swap');
:global(body) {
  margin: 0;
  background: #f4f0e8;
  color: #20231f;
  font-family: 'DM Sans', sans-serif;
}
.agent-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 10% 0, #fbd37c55, transparent 35%),
    linear-gradient(135deg, #f7f3ed, #e8eee9);
  padding: 44px clamp(18px, 6vw, 90px);
  box-sizing: border-box;
}
header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  max-width: 980px;
  margin: auto;
}
.eyebrow {
  letter-spacing: 0.18em;
  font-size: 11px;
  color: #c06435;
}
h1 {
  font: 700 clamp(40px, 7vw, 76px) / 0.95 'Space Grotesk';
  margin: 12px 0;
}
header p {
  font-size: 18px;
  color: #5f665e;
}
.ghost {
  border: 1px solid #c9c7bd;
  background: #fff8;
  border-radius: 99px;
  padding: 11px 18px;
}
.chat-shell {
  max-width: 980px;
  margin: 40px auto 0;
  background: #ffffffe6;
  border: 1px solid #dedbd2;
  border-radius: 28px;
  box-shadow: 0 20px 60px #7a67401c;
  overflow: hidden;
}
.messages {
  height: min(58vh, 620px);
  overflow: auto;
  padding: 30px;
}
.message {
  margin: 0 0 22px;
  max-width: 82%;
}
.message.user {
  margin-left: auto;
  text-align: right;
}
.bubble {
  display: inline-block;
  padding: 14px 18px;
  border-radius: 18px 18px 18px 5px;
  background: #eef0e9;
  white-space: pre-wrap;
}
.user .bubble {
  background: #20231f;
  color: white;
  border-radius: 18px 18px 5px 18px;
}
.cards {
  display: grid;
  gap: 12px;
  margin-top: 12px;
}
.card {
  background: #fff;
  border: 1px solid #ddd9cf;
  border-radius: 16px;
  padding: 16px;
  cursor: pointer;
  transition: 0.2s;
}
.card:hover {
  transform: translateY(-2px);
  border-color: #c06435;
}
.card-top {
  display: flex;
  justify-content: space-between;
}
.card-top span {
  color: #c06435;
}
.card p {
  margin: 8px 0 0;
  color: #687067;
}
.meta {
  font-size: 13px;
}
.card small {
  display: block;
  margin-top: 12px;
  color: #9a5b3f;
}
.fallback {
  font-size: 12px;
  color: #b35e3b;
  margin-top: 8px;
}
.location-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 22px;
  border-bottom: 1px solid #e3dfd5;
  font-size: 13px;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
  font-size: 12px;
}
.filters span {
  background: #eef0e9;
  border-radius: 8px;
  padding: 3px 7px;
}
.notice, .error {
  font-size: 13px;
  line-height: 1.6;
}
.notice { color: #687067; }
.error { color: #a43826; }
.input-error { padding: 0 22px; }
.vouchers {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed #ddd9cf;
  font-size: 13px;
}
.voucher + .voucher { margin-top: 10px; }
button:disabled { opacity: 0.6; cursor: not-allowed; }
.quick {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 0 30px 18px;
}
.quick button {
  border: 1px solid #d9d5c9;
  background: #faf8f2;
  border-radius: 99px;
  padding: 8px 12px;
  color: #555b52;
}
.composer {
  display: flex;
  gap: 12px;
  padding: 18px 22px;
  border-top: 1px solid #e3dfd5;
}
.composer textarea {
  flex: 1;
  resize: none;
  border: 0;
  background: #f5f2eb;
  border-radius: 16px;
  padding: 14px;
  font: inherit;
  min-height: 24px;
  outline: none;
}
.send,
.stop {
  border: 0;
  border-radius: 14px;
  padding: 0 22px;
  color: white;
  background: #c06435;
  font-weight: 700;
}
.stop {
  background: #555b52;
}
@media (max-width: 600px) {
  .agent-page {
    padding: 28px 12px;
  }
  header {
    align-items: center;
  }
  .messages {
    padding: 18px;
    height: 60vh;
  }
  .message {
    max-width: 94%;
  }
  .chat-shell {
    margin-top: 24px;
  }
  .quick {
    padding: 0 18px 14px;
  }
  .composer {
    padding: 14px;
  }
  .composer textarea {
    min-width: 0;
  }
}
</style>
