import test from 'node:test'
import assert from 'node:assert/strict'
import { reactive, watchEffect, nextTick } from 'vue'
import {
  createSSEParser,
  createAssistant,
  createAgentReducer,
  consumeAgentStream,
  createAgentRunner,
  locateAgent,
  agentStreamOptions,
  filterLabels,
  fieldText,
  businessStatus,
  voucherMoney,
  errorMessage
} from './agentStream.js'

const encode = new TextEncoder()
const frame = (event, id, data) =>
  `event:${event}\r\nid:${id}\r\ndata:${typeof data === 'string' ? data : JSON.stringify(data)}\r\n\r\n`
const response = (chunks) =>
  new Response(
    new ReadableStream({
      start(controller) {
        for (const chunk of chunks)
          controller.enqueue(
            typeof chunk === 'string' ? encode.encode(chunk) : chunk
          )
        controller.close()
      }
    }),
    { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } }
  )
const complete = (overrides) => ({
  answer: '找到一家商户',
  cards: [{ shopId: '1', name: '真实商户' }],
  filters: { budgetMax: 100 },
  conversationId: 'c1',
  memorySaved: true,
  fallback: false,
  traceId: 'trace1',
  ...overrides
})
async function consume(res, message = createAssistant()) {
  await consumeAgentStream(res, {
    signal: new AbortController().signal,
    active: () => true,
    message
  })
  return message
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function runnerHarness(stream) {
  const busy = [],
    conversations = [],
    stopped = []
  const runner = createAgentRunner({
    stream,
    stop: (id) => {
      stopped.push(id)
    },
    onLoading: (value) => busy.push(value),
    onConversation: (value) => conversations.push(value)
  })
  return { runner, busy, conversations, stopped }
}

test('SSE retains CRLF across every split, comments, repeated data, and leading spaces', () => {
  const source =
    ': heartbeat\r\nevent: text_delta\r\nid: 12\r\ndata:  有空格\r\ndata: 第二行\r\n\r\n'
  for (let split = 0; split <= source.length; split++) {
    const events = []
    const parser = createSSEParser((event) => events.push(event))
    parser.push(source.slice(0, split))
    parser.push(source.slice(split))
    parser.finish()
    assert.deepEqual(events, [
      { event: 'text_delta', id: '12', data: ' 有空格\n第二行' }
    ])
  }
})

test('SSE accepts LF, CR, empty data and ignores unfinished frames at EOF', () => {
  const events = []
  const parser = createSSEParser((event) => events.push(event))
  parser.push(
    'event:text_delta\nid:1\ndata:\n\nevent:text_delta\rid:2\rdata:ok\r\revent:done\ndata:truncated'
  )
  parser.finish()
  assert.deepEqual(
    events.map((e) => e.data),
    ['', 'ok']
  )
})

test('SSE decoding supports Chinese UTF-8 split at every byte and preserves large IDs', async () => {
  const bytes = encode.encode(
    frame('shop_card', 1, '{"shopId":9223372036854775806,"name":"火锅"}') +
      frame(
        'done',
        2,
        '{"answer":"你好","cards":[{"shopId":9223372036854775806}],"memorySaved":true}'
      )
  )
  const message = await consume(
    response(Array.from(bytes, (byte) => Uint8Array.of(byte)))
  )
  assert.equal(message.cards[0].shopId, '9223372036854775806')
  assert.equal(message.content, '你好')
  assert.equal(message.phase, 'done')
})

test('raw string payloads are not JSON-decoded; seq deduplicates cards/text and older events', () => {
  const message = createAssistant()
  const reduce = createAgentReducer(message)
  const send = (event, id, data) => reduce({ event, id: String(id), data })
  send('status', 1, 'working')
  send('shop_card', 2, '{"shopId":1}')
  send('shop_card', 2, '{"shopId":1}')
  send('text_delta', 3, '123')
  send('text_delta', 3, '重复')
  send('text_delta', 2, '过期')
  send('text_delta', 4, '"原文"')
  send('text_delta', 5, ' true null')
  assert.equal(message.cards.length, 1)
  assert.equal(message.content, '123"原文" true null')
})

test('deduplication is per request, including integer seq above JS safe range', () => {
  for (let i = 0; i < 2; i++) {
    const message = createAssistant()
    const reduce = createAgentReducer(message)
    reduce({ event: 'text_delta', id: '9007199254740992', data: 'a' })
    reduce({ event: 'text_delta', id: '9007199254740993', data: 'b' })
    reduce({ event: 'text_delta', id: '9007199254740992', data: 'a' })
    assert.equal(message.content, 'ab')
  }
})

test('done is authoritative ChatResponse, never appends duplicate cards or answer', async () => {
  const message = await consume(
    response([
      frame('status', 1, 'processing'),
      frame('filter_update', 2, { radiusMeter: 3000 }),
      frame('shop_card', 3, { shopId: '1' }),
      frame('text_delta', 4, '找到'),
      frame('done', 5, complete()),
      frame('text_delta', 6, '不应追加')
    ])
  )
  assert.equal(message.content, '找到一家商户')
  assert.equal(message.cards.length, 1)
  assert.deepEqual({ ...message.filters }, { budgetMax: 100 })
  assert.equal(message.memorySaved, true)
  assert.equal(message.traceId, 'trace1')
  assert.equal(message.noResult, false)
})

test('reactive assistant triggers updates for actual streamed deltas and cards', async () => {
  const message = reactive(createAssistant())
  const snapshots = []
  const stop = watchEffect(() =>
    snapshots.push(`${message.content}/${message.cards.length}`)
  )
  const reduce = createAgentReducer(message)
  reduce({ event: 'text_delta', id: '1', data: '实时内容' })
  reduce({ event: 'shop_card', id: '2', data: '{"shopId":1}' })
  await nextTick()
  stop()
  assert.deepEqual(snapshots, ['/0', '实时内容/1'])
})

test('no results retains filters and fallback remains a successful degraded result', async () => {
  const message = await consume(
    response([
      frame('filter_update', 1, { budgetMax: 0 }),
      frame('fallback', 2, 'AGENT_FALLBACK'),
      frame(
        'done',
        3,
        complete({
          cards: [],
          filters: { budgetMax: 0 },
          fallback: true,
          errorCode: 'AGENT_FALLBACK'
        })
      )
    ])
  )
  assert.equal(message.phase, 'done')
  assert.equal(message.fallback, true)
  assert.equal(message.noResult, true)
  assert.deepEqual(filterLabels(message.filters), ['人均上限：0元'])
  const greeting = await consume(
    response([
      frame('done', 1, complete({ cards: [], filters: {}, answer: '你好' }))
    ])
  )
  assert.equal(greeting.noResult, false)
})

test('nearby no-result and infrastructure/location errors remain distinct', async () => {
  for (const code of [
    'AGENT_NO_RESULT',
    'AGENT_NO_LOCATION',
    'AGENT_GEO_INDEX_EMPTY',
    'AGENT_GEO_UNAVAILABLE'
  ]) {
    const message = await consume(
      response([frame('done', 1, complete({ cards: [], errorCode: code }))])
    )
    assert.equal(message.phase, code === 'AGENT_NO_RESULT' ? 'done' : 'error')
    assert.equal(message.noResult, code === 'AGENT_NO_RESULT')
  }
})

test('error is raw errorCode, preserved through null done and not overwritten by deltas', async () => {
  const message = await consume(
    response([
      frame('error', 1, 'AGENT_RATE_LIMITED'),
      frame('text_delta', 2, '不应追加'),
      frame('done', 3, null)
    ])
  )
  assert.equal(message.errorCode, 'AGENT_RATE_LIMITED')
  assert.equal(message.phase, 'error')
  assert.equal(message.content, '')
  assert.match(errorMessage(message.errorCode), /频繁/)
})

test('stream ending after explicit error preserves it without requiring done', async () => {
  const message = await consume(
    response([frame('error', 1, 'AGENT_REQUEST_IN_PROGRESS')])
  )
  assert.equal(message.errorCode, 'AGENT_REQUEST_IN_PROGRESS')
})

test('invalid JSON, non-SSE HTTP success, truncated stream and HTTP errors are explicit failures', async () => {
  await assert.rejects(
    consume(response([frame('shop_card', 1, '{bad')])),
    /AGENT_STREAM_INVALID/
  )
  await assert.rejects(consume(new Response('{}')), /AGENT_STREAM_INVALID/)
  await assert.rejects(
    consume(response([frame('text_delta', 1, '部分结果')])),
    /AGENT_STREAM_INCOMPLETE/
  )
  await assert.rejects(
    consume(response(['event:done\nid:1\ndata:{}'])),
    /AGENT_STREAM_INCOMPLETE/
  )
  await assert.rejects(
    consume(new Response(null, { status: 401 })),
    /AGENT_UNAUTHORIZED/
  )
  await assert.rejects(
    consume(new Response(null, { status: 429 })),
    /AGENT_RATE_LIMITED/
  )
})

test('stop while fetch is pending blocks stale data and old finally cannot clear new loading', async () => {
  const first = deferred(),
    second = deferred()
  const signals = []
  const { runner, busy, conversations, stopped } = runnerHarness(
    (payload, signal) => {
      signals.push(signal)
      return payload.clientRequestId === 'r1' ? first.promise : second.promise
    }
  )
  const oldMessage = createAssistant(),
    newMessage = createAssistant()
  const oldRun = runner.run(oldMessage, { clientRequestId: 'r1' })
  runner.cancel()
  const newRun = runner.run(newMessage, { clientRequestId: 'r2' })
  first.resolve(
    response([
      frame('text_delta', 1, '过期'),
      frame('done', 2, complete({ conversationId: 'old' }))
    ])
  )
  await oldRun
  assert.equal(oldMessage.phase, 'stopped')
  assert.equal(oldMessage.content, '')
  assert.deepEqual(busy, [true, false, true])
  assert.deepEqual(conversations, [])
  assert.equal(signals[0].aborted, true)
  assert.equal(signals[1].aborted, false)
  assert.deepEqual(stopped, ['r1'])
  second.resolve(
    response([frame('done', 1, complete({ conversationId: 'new' }))])
  )
  await newRun
  assert.deepEqual(conversations, ['new'])
  assert.deepEqual(busy, [true, false, true, false])
})

test('stop cancels pending reader and discards queued frames after the current delta', async () => {
  let cancelled = false
  const seen = deferred()
  const { runner, conversations } = runnerHarness(() =>
    Promise.resolve(
      new Response(
        new ReadableStream({
          start(controller) {
            controller.enqueue(encode.encode(frame('text_delta', 1, '已收到')))
          },
          cancel() {
            cancelled = true
          }
        }),
        { headers: { 'Content-Type': 'text/event-stream' } }
      )
    )
  )
  const message = reactive(createAssistant())
  const unwatch = watchEffect(() => {
    if (message.content) seen.resolve()
  })
  const running = runner.run(message, { clientRequestId: 'r1' })
  await seen.promise
  runner.cancel()
  await running
  unwatch()
  assert.equal(cancelled, true)
  assert.equal(message.content, '已收到')
  assert.equal(message.phase, 'stopped')
  assert.deepEqual(conversations, [])
})

test('cancel during a frame callback prevents later frames in the same chunk', async () => {
  const message = reactive(createAssistant())
  const { runner } = runnerHarness(() =>
    Promise.resolve(
      response([
        frame('text_delta', 1, '保留') +
          frame('text_delta', 2, '丢弃') +
          frame('done', 3, complete())
      ])
    )
  )
  const unwatch = watchEffect(
    () => {
      if (message.content) runner.cancel()
    },
    { flush: 'sync' }
  )
  await runner.run(message, { clientRequestId: 'r1' })
  unwatch()
  assert.equal(message.content, '保留')
  assert.equal(message.phase, 'stopped')
})

test('old rejected fetch cannot overwrite a stopped message or release a new request', async () => {
  const first = deferred(),
    second = deferred()
  const { runner, busy } = runnerHarness((payload) =>
    payload.clientRequestId === 'old' ? first.promise : second.promise
  )
  const message = createAssistant()
  const oldRun = runner.run(message, { clientRequestId: 'old' })
  runner.cancel()
  const newRun = runner.run(createAssistant(), { clientRequestId: 'new' })
  first.reject(new Error('network error'))
  await oldRun
  assert.equal(message.phase, 'stopped')
  assert.equal(message.errorCode, null)
  assert.equal(busy.at(-1), true)
  second.resolve(response([frame('done', 1, complete())]))
  await newRun
})

test('memorySaved=false warns and clears conversation; saved responses retain it', async () => {
  for (const saved of [false, true]) {
    const { runner, conversations } = runnerHarness(() =>
      Promise.resolve(
        response([frame('done', 1, complete({ memorySaved: saved }))])
      )
    )
    const message = createAssistant()
    await runner.run(message, { clientRequestId: 'r' })
    assert.equal(message.memorySaved, saved)
    assert.deepEqual(conversations, [saved ? 'c1' : null])
  }
})

test('partial text survives transport failure and authentication resets stale conversation', async () => {
  const { runner } = runnerHarness(() =>
    Promise.resolve(response([frame('text_delta', 1, '部分结果')]))
  )
  const message = createAssistant()
  await runner.run(message, { clientRequestId: 'r1' })
  assert.equal(message.content, '部分结果')
  assert.equal(message.errorCode, 'AGENT_STREAM_INCOMPLETE')
  const auth = runnerHarness(() =>
    Promise.resolve(new Response(null, { status: 401 }))
  )
  await auth.runner.run(createAssistant(), { clientRequestId: 'r2' })
  assert.deepEqual(auth.conversations, [null])
})

test('fetch options carry current user token verbatim and cancellation signal', () => {
  const signal = new AbortController().signal
  const options = agentStreamOptions({ message: '火锅' }, signal, 'user-token')
  assert.equal(options.headers.Authorization, 'user-token')
  assert.equal(options.headers.Accept, 'text/event-stream')
  assert.equal(options.signal, signal)
  assert.deepEqual(JSON.parse(options.body), { message: '火锅' })
  assert.equal(
    'Authorization' in agentStreamOptions({}, signal, '').headers,
    false
  )
})

test('geolocation uses browser coordinates, retains zero, rejects invalid values and never supplies defaults', async () => {
  assert.equal(await locateAgent(null), null)
  assert.equal(
    await locateAgent({
      getCurrentPosition(ok, fail) {
        fail({ code: 1 })
      }
    }),
    null
  )
  assert.equal(
    await locateAgent({
      getCurrentPosition() {
        throw new Error('unavailable')
      }
    }),
    null
  )
  for (const coords of [
    { latitude: 0, longitude: 0 },
    { latitude: 31.23, longitude: 121.47 }
  ]) {
    assert.deepEqual(
      await locateAgent({
        getCurrentPosition(ok) {
          ok({ coords })
        }
      }),
      coords
    )
  }
  for (const coords of [
    { latitude: 91, longitude: 0 },
    { latitude: 0, longitude: NaN },
    { latitude: null, longitude: 0 }
  ]) {
    assert.equal(
      await locateAgent({
        getCurrentPosition(ok) {
          ok({ coords })
        }
      }),
      null
    )
  }
  const payload = { message: '附近', ...((await locateAgent(null)) || {}) }
  assert.equal('latitude' in payload, false)
  assert.equal('longitude' in payload, false)
})

test('display helpers retain zero/false, explain missing fields, and convert voucher cents', () => {
  assert.equal(fieldText(0, ' 米'), '0 米')
  assert.equal(fieldText(null), '信息缺失')
  assert.equal(fieldText(undefined), '信息缺失')
  assert.equal(businessStatus(false), '未营业')
  assert.equal(businessStatus(null), '营业状态：信息缺失')
  assert.equal(voucherMoney(9900), '99.00 元')
  assert.equal(voucherMoney(0), '0.00 元')
  assert.equal(voucherMoney(null), '信息缺失')
  assert.deepEqual(
    filterLabels({ needVoucher: false, minScore: 0, keyword: null }),
    ['最低评分：0分', '优惠券：不限']
  )
})
