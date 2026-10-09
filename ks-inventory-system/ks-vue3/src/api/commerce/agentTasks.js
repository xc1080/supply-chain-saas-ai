import axios from 'axios'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'

const taskRequest = (shopId, config) => request({ ...config, headers: { 'X-Shop-ID': shopId } })
export const listAgentTasks = shopId => taskRequest(shopId, { url: '/commerce/agent-tasks', method: 'get' })
export const getAgentTask = (shopId, taskId) => taskRequest(shopId, { url: `/commerce/agent-tasks/${encodeURIComponent(taskId)}`, method: 'get' })
export const refreshAgentTask = (shopId, taskId, data) => taskRequest(shopId, { url: `/commerce/agent-tasks/${encodeURIComponent(taskId)}/refresh`, method: 'post', data })
export const reviewAgentTask = (shopId, taskId, data) => taskRequest(shopId, { url: `/commerce/agent-tasks/${encodeURIComponent(taskId)}/review`, method: 'post', data })
export const executeAgentTask = (shopId, taskId, data) => taskRequest(shopId, { url: `/commerce/agent-tasks/${encodeURIComponent(taskId)}/execute`, method: 'post', data })
export const cancelAgentTask = (shopId, taskId, data) => taskRequest(shopId, { url: `/commerce/agent-tasks/${encodeURIComponent(taskId)}/cancel`, method: 'post', data })
export async function planProcurementTask(shopId, data) {
  const response = await axios.post('/ai-api/business/tasks/procurement', { ...data, shopId }, {
    timeout: 110000, headers: { Authorization: 'Bearer ' + getToken(), 'Content-Type': 'application/json' }
  })
  return { data: response.data }
}
