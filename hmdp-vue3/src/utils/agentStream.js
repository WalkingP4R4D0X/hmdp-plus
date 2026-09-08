import JSONbig from 'json-bigint'

const json = JSONbig({
  storeAsString: true,
  protoAction: 'ignore',
  constructorAction: 'ignore'
})

export function agentStreamOptions(data, signal, token) {
  return {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: token } : {})
    },
    body: JSON.stringify(data),
    signal
  }
}

// Keep incomplete lines between chunks, including a CR split from its LF.
// data remains raw: Spring sends String payloads without JSON quoting.
export function createSSEParser(onEvent) {
  let buffer = ''
  let event = ''
  let id = ''
  let data = []
  function line(value) {
    if (value === '') {
      if (data.length)
        onEvent({ event: event || 'message', id, data: data.join('\n') })
      event = ''
      id = ''
      data = []
      return
    }
    if (value.startsWith(':')) return
    const colon = value.indexOf(':')
    const field = colon < 0 ? value : value.slice(0, colon)
    const raw = colon < 0 ? '' : value.slice(colon + 1)
    const content = raw.startsWith(' ') ? raw.slice(1) : raw
    if (field === 'event') event = content
    if (field === 'id' && !content.includes('\0')) id = content
    if (field === 'data') data.push(content)
  }
  return {
    push(chunk) {
      buffer += chunk
      let end
      while ((end = buffer.search(/[\r\n]/)) >= 0) {
        if (buffer[end] === '\r' && end === buffer.length - 1) break
        const length = buffer[end] === '\r' && buffer[end + 1] === '\n' ? 2 : 1
        line(buffer.slice(0, end))
        buffer = buffer.slice(end + length)
      }
    },
    finish() {
      // Dispatch only frames terminated by a blank line, never truncated data.
      if (buffer.endsWith('\r')) this.push('\n')
    }
  }
}

export function createAssistant() {
  return {
    role: 'assistant',
    content: '',
    cards: [],
    filters: {},
    phase: 'loading',
    fallback: false,
    errorCode: null,
    memorySaved: null,
    noResult: false,
    conversationId: null,
    traceId: null
  }
}

export function errorMessage(code) {
  return (
    {
      AGENT_NO_LOCATION:
        '未获取到定位，请允许定位，或补充所在区域、按关键词搜索。',
      AGENT_GEO_INDEX_EMPTY: '附近商户索引正在准备中，请稍后重试。',
      AGENT_GEO_UNAVAILABLE:
        '附近检索暂时不可用，请稍后重试或按区域、关键词搜索。',
      AGENT_RATE_LIMITED: '请求过于频繁，请稍后再试。',
      AGENT_UNAUTHORIZED: '登录状态或会话已失效，请重新登录或开始新会话。',
      AGENT_REQUEST_INVALID:
        '请输入 1–500 字的查店需求，包含品类、区域或预算。',
      AGENT_INPUT_UNCLEAR: '请补充具体的品类、区域、预算或距离。',
      AGENT_LLM_EMPTY_INTENT: '暂未识别出查店条件，请补充品类、区域或预算。',
      AGENT_TOOL_TIMEOUT: '查询超时，请稍后重试。',
      AGENT_REQUEST_IN_PROGRESS: '该请求仍在处理中，请稍后重试。',
      AGENT_REQUEST_CANCELLED: '已停止本次推荐请求。',
      AGENT_STREAM_INCOMPLETE: '连接提前中断，结果可能不完整，请重试。',
      AGENT_STREAM_INVALID: '收到的推荐数据格式异常，请重试。',
      AGENT_NETWORK_ERROR: '暂时无法连接智能导购，请稍后重试。'
    }[code] || '查询失败，请稍后重试。'
  )
}

function record(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

// One reducer per request: SSE id is the increasing seq, not a data envelope.
export function createAgentReducer(message) {
  let lastSeq = -1n
  let ended = false
  return (frame) => {
    if (ended || message.phase === 'stopped') return false
    if (/^\d+$/.test(frame.id)) {
      const seq = BigInt(frame.id)
      if (seq <= lastSeq) return false
      lastSeq = seq
    }
    if (message.phase === 'error' && frame.event !== 'done') return false
    const structured = ['filter_update', 'shop_card', 'done'].includes(
      frame.event
    )
    const data = structured ? json.parse(frame.data) : frame.data
    if (frame.event === 'status') message.phase = 'loading'
    if (frame.event === 'filter_update') {
      if (!record(data)) throw new Error('AGENT_STREAM_INVALID')
      message.filters = data
    }
    if (frame.event === 'shop_card') {
      if (!record(data)) throw new Error('AGENT_STREAM_INVALID')
      message.cards.push(data)
    }
    if (frame.event === 'text_delta') message.content += data
    if (frame.event === 'fallback') message.fallback = true
    if (frame.event === 'error') {
      message.errorCode = data || 'AGENT_NETWORK_ERROR'
      message.phase = 'error'
    }
    if (frame.event === 'done') {
      // Older error paths may terminate with null after sending an error code.
      if (!record(data) && !(data === null && message.errorCode)) {
        throw new Error('AGENT_STREAM_INVALID')
      }
      if (data) {
        if (typeof data.answer === 'string') message.content = data.answer
        if (Array.isArray(data.cards)) message.cards = data.cards
        if (record(data.filters)) message.filters = data.filters
        message.fallback = data.fallback === true || message.fallback
        message.memorySaved =
          typeof data.memorySaved === 'boolean' ? data.memorySaved : null
        message.conversationId = data.conversationId || null
        message.traceId = data.traceId || null
        message.errorCode = data.errorCode || message.errorCode
      }
      const nonFatal = ['AGENT_NO_RESULT', 'AGENT_FALLBACK'].includes(
        message.errorCode
      )
      message.phase = message.errorCode && !nonFatal ? 'error' : 'done'
      message.noResult =
        message.phase === 'done' &&
        message.cards.length === 0 &&
        (message.errorCode === 'AGENT_NO_RESULT' ||
          Object.values(message.filters).some((v) => v != null && v !== ''))
      ended = true
    }
    return true
  }
}

export async function consumeAgentStream(
  response,
  { signal, active, message }
) {
  if (!active()) return
  if (!response.ok) {
    throw new Error(
      {
        401: 'AGENT_UNAUTHORIZED',
        403: 'AGENT_UNAUTHORIZED',
        429: 'AGENT_RATE_LIMITED',
        400: 'AGENT_REQUEST_INVALID'
      }[response.status] || 'AGENT_NETWORK_ERROR'
    )
  }
  if (
    !response.body ||
    !response.headers.get('content-type')?.includes('text/event-stream')
  ) {
    throw new Error('AGENT_STREAM_INVALID')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  const reduce = createAgentReducer(message)
  let finished = false
  const parser = createSSEParser((frame) => {
    if (!active() || signal.aborted || finished) return
    let accepted
    try {
      accepted = reduce(frame)
    } catch {
      throw new Error('AGENT_STREAM_INVALID')
    }
    if (accepted && frame.event === 'done') finished = true
  })
  const cancelReader = () => {
    void reader.cancel().catch(() => {})
  }
  signal.addEventListener('abort', cancelReader, { once: true })
  try {
    while (active() && !signal.aborted && !finished) {
      const { value, done } = await reader.read()
      if (!active() || signal.aborted) return
      if (done) {
        parser.push(decoder.decode())
        parser.finish()
        if (!finished && message.phase !== 'error')
          throw new Error('AGENT_STREAM_INCOMPLETE')
        break
      }
      parser.push(decoder.decode(value, { stream: true }))
    }
  } finally {
    signal.removeEventListener('abort', cancelReader)
    cancelReader()
    reader.releaseLock()
  }
}

// Own request identity outside Vue. Every async continuation checks ownership;
// stopping releases the slot immediately, so an old finally cannot clear it.
export function createAgentRunner({ stream, stop, onLoading, onConversation }) {
  let current = null
  return {
    async run(message, payload) {
      if (current) return
      const request = {
        id: payload.clientRequestId,
        controller: new AbortController(),
        message
      }
      current = request
      onLoading(true)
      const active = () => current === request
      try {
        const response = await stream(payload, request.controller.signal)
        if (!active()) {
          void response.body?.cancel().catch(() => {})
          return
        }
        await consumeAgentStream(response, {
          signal: request.controller.signal,
          active,
          message
        })
        if (active() && message.memorySaved === false) {
          onConversation(null)
        } else if (active() && message.phase === 'done') {
          onConversation(
            message.memorySaved === false ? null : message.conversationId
          )
        } else if (active() && message.errorCode === 'AGENT_UNAUTHORIZED') {
          onConversation(null)
        }
      } catch (error) {
        if (active() && !request.controller.signal.aborted) {
          message.errorCode = error.message?.startsWith('AGENT_')
            ? error.message
            : 'AGENT_NETWORK_ERROR'
          message.phase = 'error'
          if (message.errorCode === 'AGENT_UNAUTHORIZED') onConversation(null)
        }
      } finally {
        if (active()) {
          current = null
          onLoading(false)
        }
      }
    },
    cancel() {
      if (!current) return
      const request = current
      current = null
      request.message.phase = 'stopped'
      request.controller.abort()
      onLoading(false)
      if (request.id)
        Promise.resolve()
          .then(() => stop(request.id))
          .catch(() => {})
    }
  }
}

export function locateAgent(geolocation) {
  return new Promise((resolve) => {
    if (!geolocation) return resolve(null)
    try {
      geolocation.getCurrentPosition(
        ({ coords }) => {
          const { latitude, longitude } = coords
          resolve(
            Number.isFinite(latitude) &&
              Math.abs(latitude) <= 90 &&
              Number.isFinite(longitude) &&
              Math.abs(longitude) <= 180
              ? { latitude, longitude }
              : null
          )
        },
        () => resolve(null),
        { enableHighAccuracy: false, timeout: 5000, maximumAge: 300000 }
      )
    } catch {
      resolve(null)
    }
  })
}

export function filterLabels(filters = {}) {
  const labels = {
    keyword: ['关键词', ''],
    location: ['区域', ''],
    radiusMeter: ['距离以内', '米'],
    budgetMax: ['人均上限', '元'],
    minScore: ['最低评分', '分'],
    openAt: ['营业时间', ''],
    scene: ['场景', ''],
    needVoucher: ['优惠券', '']
  }
  return Object.entries(labels).flatMap(([key, [name, unit]]) => {
    const value = filters[key]
    if (value == null || value === '') return []
    return [
      `${name}：${key === 'needVoucher' ? (value ? '需要' : '不限') : `${value}${unit}`}`
    ]
  })
}

export function fieldText(value, suffix = '') {
  return value == null || value === '' ? '信息缺失' : `${value}${suffix}`
}

export function businessStatus(value) {
  return value === true
    ? '营业中'
    : value === false
      ? '未营业'
      : '营业状态：信息缺失'
}

export function voucherMoney(value) {
  return value == null || value === '' || !Number.isFinite(Number(value))
    ? '信息缺失'
    : `${(Number(value) / 100).toFixed(2)} 元`
}
