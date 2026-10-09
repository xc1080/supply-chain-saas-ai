import axios from 'axios'
import { getToken } from '@/utils/auth'

const assistantClient = axios.create({
  baseURL: '/ai-api',
  timeout: 90000,
  headers: { 'Content-Type': 'application/json' }
})

assistantClient.interceptors.request.use(config => {
  const token = getToken()
  if (token) config.headers.Authorization = 'Bearer ' + token
  return config
})

export async function chatWithAssistant(message, history, shopId) {
  if (!shopId) throw new Error('请先选择当前店铺')
  const response = await assistantClient.post('/chat', { message, history, shopId })
  return response.data
}
