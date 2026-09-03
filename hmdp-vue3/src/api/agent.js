import request from '@/utils/request'

export const chatAgent = (data) => request.post('/agent/chat', data)
export const streamAgent = (data, signal) =>
  fetch('/api/agent/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    signal
  })
export const getAgentMessages = (id) =>
  request.get(`/agent/conversations/${id}/messages`)
export const deleteAgentConversation = (id) =>
  request.delete(`/agent/conversations/${id}`)
