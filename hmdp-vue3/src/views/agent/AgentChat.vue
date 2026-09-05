<script setup>
import { ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { streamAgent, stopAgent } from '@/api/agent'

const router = useRouter()
const input = ref('')
const messages = ref([
  {
    role: 'assistant',
    content: '你好，我是黑马点评智能导购。告诉我你想找什么店？',
    cards: []
  }
])
const loading = ref(false)
const controller = ref(null)
const conversationId = ref(null)
const activeRequestId = ref(null)
const quick = [
  '附近有什么好吃的',
  '拱墅区人均100以内适合约会的餐厅',
  '找3公里内晚上9点还营业的火锅店'
]

async function send(text = input.value) {
  if (!text?.trim() || loading.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const assistant = {
    role: 'assistant',
    content: '',
    cards: [],
    fallback: false
  }
  messages.value.push(assistant)
  loading.value = true
  controller.value = new AbortController()
  try {
    activeRequestId.value = crypto.randomUUID()
    const response = await streamAgent(
      {
        conversationId: conversationId.value,
        message: text,
        stream: true,
        clientRequestId: activeRequestId.value
      },
      controller.value.signal
    )
    if (!response.ok) throw new Error('request failed')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const chunks = buffer.split('\n\n')
      buffer = chunks.pop() || ''
      for (const chunk of chunks) {
        const event = chunk.match(/^event: (.+)$/m)?.[1]
        const data = chunk.match(/^data: (.+)$/m)?.[1]
        if (!data) continue
        let parsed
        try {
          parsed = JSON.parse(data)
        } catch {
          parsed = data
        }
        if (event === 'shop_card') assistant.cards.push(parsed)
        if (event === 'text_delta') assistant.content += parsed
        if (event === 'fallback') assistant.fallback = true
        if (event === 'done' && parsed?.conversationId)
          conversationId.value = parsed.conversationId
        await nextTick()
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError')
      assistant.content = '暂时无法连接智能导购，请稍后重试。'
  } finally {
    loading.value = false
    controller.value = null
  }
}
function stop() {
  controller.value?.abort()
  if (activeRequestId.value) stopAgent(activeRequestId.value).catch(() => {})
  loading.value = false
}
function openShop(card) {
  router.push(`/shopDetail/${card.shopId}`)
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
      <div class="messages">
        <article
          v-for="(message, index) in messages"
          :key="index"
          :class="['message', message.role]"
        >
          <div class="bubble">
            {{
              message.content ||
              (loading && index === messages.length - 1
                ? '正在查询真实商户…'
                : '')
            }}
          </div>
          <div v-if="message.fallback" class="fallback">
            智能推荐暂时不可用，已切换到普通搜索
          </div>
          <div v-if="message.cards?.length" class="cards">
            <div
              v-for="card in message.cards"
              :key="card.shopId"
              class="card"
              @click="openShop(card)"
            >
              <div class="card-top">
                <strong>{{ card.name }}</strong
                ><span>{{ card.score ? `${card.score} 分` : '暂无评分' }}</span>
              </div>
              <p>{{ card.area || card.address || '商户详情' }}</p>
              <p class="meta">
                {{
                  card.averagePrice
                    ? `人均 ${card.averagePrice} 元`
                    : '价格待更新'
                }}
                ·
                {{
                  card.distanceMeter
                    ? `${Math.round(card.distanceMeter)} 米`
                    : '距离待定'
                }}
                · {{ card.openNow ? '营业中' : '营业状态待确认' }}
              </p>
              <small>{{ card.reason }}</small>
            </div>
          </div>
        </article>
      </div>
      <div class="quick">
        <button v-for="item in quick" :key="item" @click="send(item)">
          {{ item }}
        </button>
      </div>
      <div class="composer">
        <textarea
          v-model="input"
          placeholder="例如：西湖区适合约会、人均150以内的日料"
          @keydown.enter.exact.prevent="send()"
        /><button v-if="loading" class="stop" @click="stop">停止</button
        ><button v-else class="send" @click="send">发送</button>
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
