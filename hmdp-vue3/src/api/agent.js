import request from '@/utils/request'
import { useUserStore } from '@/stores'
import { agentStreamOptions } from '@/utils/agentStream'

export const chatAgent = (data) => request.post('/agent/chat', data)
export const streamAgent = (data, signal) =>
  fetch('/api/agent/chat/stream', agentStreamOptions(data, signal, useUserStore().token))
export const stopAgent = (clientRequestId) =>
  request.post('/agent/chat/stop', null, { params: { clientRequestId } })
export const getAgentMessages = (id) =>
  request.get(`/agent/conversations/${id}/messages`)
export const deleteAgentConversation = (id) =>
  request.delete(`/agent/conversations/${id}`)
