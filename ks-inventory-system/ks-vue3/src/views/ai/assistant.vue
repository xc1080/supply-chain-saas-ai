<template>
  <div class="assistant-page">
    <header class="assistant-heading">
      <div>
        <h1>智能选品助手</h1>
      </div>
      <div class="query-scope" aria-label="支持查询商品、库存和知识库">
        <span>商品</span><span>库存</span><span>知识库</span>
      </div>
    </header>

    <div class="assistant-workspace">
      <section class="chat-panel" aria-labelledby="chat-heading">
        <div class="chat-toolbar">
          <div class="chat-title">
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5h-8L5 21v-3.5H4.5A1.5 1.5 0 0 1 3 16V6a1.5 1.5 0 0 1 1.5-1.5H5Z" stroke="currentColor" stroke-width="1.6"/><path d="M7 9h10M7 13h6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
            <h2 id="chat-heading">选品对话</h2>
          </div>
          <button class="new-conversation" type="button" :disabled="loading || !messages.length" @click="clearConversation">
            <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="M10 4v12M4 10h12" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/></svg>
            新对话
          </button>
        </div>

        <div ref="conversationEl" class="conversation" role="log" aria-label="选品对话记录" aria-live="polite" aria-relevant="additions text" :aria-busy="loading">
          <div v-if="!messages.length" class="welcome">
            <div class="welcome-mark" aria-hidden="true">
              <svg viewBox="0 0 32 32" fill="none"><path d="m16 4 11 6.5v12L16 29 5 22.5v-12L16 4Z" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/><path d="m5 10.5 11 6 11-6M16 16.5V29M10.5 7.2l11 6.3v5" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"/></svg>
            </div>
            <h3>从客户需求开始选品</h3>
            <p>描述要用在哪里、预算多少、有哪些设备，或者直接问库存与业务规则。</p>

          </div>

          <article v-for="message in messages" :key="message.id" :class="['message', message.role]">
            <div class="message-label">
              <span v-if="message.role === 'assistant'" class="assistant-mark" aria-hidden="true">
                <svg viewBox="0 0 20 20" fill="none"><path d="m10 2 2.3 5.7L18 10l-5.7 2.3L10 18l-2.3-5.7L2 10l5.7-2.3L10 2Z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round"/></svg>
              </span>
              <span>{{ message.role === 'user' ? '你' : '选品助手' }}</span>
            </div>
            <div class="message-content" v-text="message.role === 'assistant' ? readableAnswer(message.content) : message.content" />

            <template v-if="message.role === 'assistant'">
              <div v-if="message.mode && !isLlmEnabled(message.mode.llm)" class="mode-tags">
                <span :class="['mode-label', { 'model-answer': isLlmEnabled(message.mode.llm) }]">
                  {{ isLlmEnabled(message.mode.llm) ? '大模型回答' : '本地检索回答' }}
                </span>
                <span v-if="message.mode.retrieval" class="mode-label">{{ retrievalLabel(message.mode.retrieval) }}</span>
              </div>

              <div v-if="message.products.length" class="product-grid" aria-label="推荐商品">
                <article v-for="product in message.products" :key="product.id" class="product-card">
                  <img class="product-image" :src="getProductImage(product)" :alt="product.name + ' 商品示意图'" width="320" height="200" loading="lazy" @error="handleProductImageError($event, product)">
                  <span class="product-image-caption">商品示意</span>
                  <div class="product-topline">
                    <span class="product-category">{{ product.category || '智能家居' }}</span>
                    <span v-if="product.code" class="product-code" v-text="product.code" />
                  </div>
                  <h3 v-text="product.name" />
                  <div class="product-spec"><span class="detail-label">规格</span><span v-text="product.spec || '暂无规格信息'" /></div>
                  <p v-if="product.remark" class="product-remark" v-text="product.remark" />
                  <div class="product-bottomline">
                    <div class="price-block"><span class="detail-label">参考售价</span><strong class="price">{{ formatPrice(product.price) }}</strong></div>
                    <div :class="['stock-block', { 'stock-positive': Number(product.stock) > 0, 'stock-empty': product.stock !== null && product.stock !== undefined && product.stock !== '' && Number(product.stock) <= 0 }]">
                      <el-tooltip content="可售数量已扣除订单预留和活动待抢配额；下单时再次核对。"><span class="detail-label" tabindex="0">可售库存 ⓘ</span></el-tooltip>
                      <strong>{{ formatStock(product.stock) }}</strong>
                    </div>
                  </div>
                </article>
              </div>

              <el-collapse v-if="message.sources.length || message.trace.length" class="evidence-collapse">
                <el-collapse-item v-if="visibleSources(message).length" :title="'回答依据（' + visibleSources(message).length + ' 条）'" name="sources">
                  <div v-for="(source, index) in visibleSources(message)" :key="source.id || index" class="source-item">
                    <h4 class="source-title" v-text="source.title" />
                    <div class="source-content" v-text="source.content" />
                  </div>
                </el-collapse-item>
                <el-collapse-item v-if="message.trace.length" title="查看处理步骤" name="trace">
                  <ol class="trace-list">
                    <li v-for="(step, index) in message.trace" :key="index" class="trace-item">
                      <span class="trace-number" aria-hidden="true">{{ index + 1 }}</span>
                      <div><strong v-text="traceLabel(step.step)" /><p v-text="step.detail" /></div>
                    </li>
                  </ol>
                </el-collapse-item>
              </el-collapse>
            </template>
          </article>

          <div v-if="loading" class="pending-message"><span class="loading-dot" aria-hidden="true" />正在检索商品与业务资料…</div>
        </div>

        <form class="composer" @submit.prevent="sendMessage()">
          <div v-if="errorMessage" id="assistant-error" class="error-alert" role="alert" v-text="errorMessage" />
          <label for="assistant-question" class="composer-label">描述你的选品需求</label>
          <el-input
            id="assistant-question"
            v-model="draft"
            name="question"
            autocomplete="off"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="1000"
            show-word-limit
            :disabled="loading"
            placeholder="例如：卧室用，预算 300 元以内，已有 Zigbee 网关…"
            aria-label="输入选品或库存问题"
            :aria-invalid="Boolean(errorMessage)"
            :aria-describedby="errorMessage ? 'assistant-error' : undefined"
            @keydown.ctrl.enter.prevent="sendMessage()"
            @keydown.meta.enter.prevent="sendMessage()"
          />
          <div class="composer-footer">
            <span>Ctrl / ⌘ + Enter 发送</span>
            <el-button type="primary" native-type="submit" :loading="loading" :disabled="!draft.trim() || loading">{{ loading ? '查询中…' : '发送问题' }}</el-button>
          </div>
        </form>
      </section>

      <aside class="selection-context" aria-labelledby="selection-heading">
        <section class="selection-guide">
          <h2 id="selection-heading">快捷提问</h2>
          <div class="suggested-prompts">
            <button v-for="(question, index) in questions" :key="question" type="button" :disabled="loading" @click="sendMessage(question)">
              <span class="prompt-topic">{{ questionTopics[index] }}</span><span class="prompt-question">{{ question }}</span>
            </button>
          </div>
        </section>
        <details class="query-note">
          <summary>选品帮助</summary>
          <p>提供用途、预算和已有网关，便于核对设备兼容性。</p>
          <p>出库与售后请通过业务单据处理。新对话会清空当前页面的上下文。</p>
        </details>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { nextTick, ref } from 'vue'
import { chatWithAssistant } from '@/api/ai/assistant'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'

const questions = ['卧室 300 元以内的智能灯怎么选？', '我有 Zigbee 网关，适合选哪些传感器？', '缺货能直接出库吗？']
const questionTopics = ['按预算选品', '查设备兼容', '问业务规则']
const priceFormatter = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 })
const draft = ref('')
const loading = ref(false)
const errorMessage = ref('')
const messages = ref([])
const conversationEl = ref(null)
let messageId = 0

function isLlmEnabled(value) {
  return value === true || ['enabled', 'online', 'configured', 'llm', 'openai'].includes(String(value).toLowerCase())
}

function retrievalLabel(value) {
  const name = String(value).toLowerCase()
  if (['hybrid', 'keyword+vector', 'keyword_vector', 'keyword-vector'].includes(name)) return '关键词 + 向量检索'
  if (['local', 'local_tfidf', 'tfidf', 'local-hybrid'].includes(name)) return '本地检索'
  if (['keyword', 'lexical'].includes(name)) return '关键词检索'
  return '检索方式：' + value
}

function traceLabel(value) {
  const labels = {
    search_products: '搜索候选商品', check_stock: '实时核对可售库存', check_budget: '核对预算', check_compatibility: '核对设备协议', read_orders: '读取当前访客订单',
    context: '理解后续问题',
    query_context: '理解后续问题',
    fetch_data: '读取业务数据',
    business_data: '读取业务数据',
    read_business_data: '读取业务数据',
    fetch_products: '读取业务数据',
    inventory: '读取业务数据',
    embedding: '向量召回',
    vector_recall: '向量召回',
    vector_search: '向量召回',
    retrieval: '向量召回',
    retrieve: '筛选与融合',
    filter_and_merge: '筛选与融合',
    filter_merge: '筛选与融合',
    fusion: '筛选与融合',
    rrf: '筛选与融合',
    generate_answer: '生成回答',
    generation: '生成回答',
    answer: '生成回答'
  }
  return labels[value] || value
}

function formatPrice(value) {
  if (value === null || value === undefined || value === '') return '售价待核对'
  const amount = Number(value)
  return Number.isFinite(amount) ? priceFormatter.format(amount) : '售价待核对'
}

function formatStock(value) {
  if (value === null || value === undefined || value === '') return '待核对'
  const amount = Number(value)
  if (!Number.isFinite(amount)) return '待核对'
  return amount > 0 ? numberFormatter.format(amount) : '暂无库存'
}

function readableAnswer(value) {
  // Compatibility for responses from an old agent during local restarts.
  return String(value || '').replace(/\[(?:DEMO-[A-Z-]+|P-[\w-]+|O-[\w-]+)\]/g, '').trim()
}

function visibleSources(message) {
  return Array.isArray(message.citations) ? message.sources.filter(source => message.citations.includes(source.id)) : message.sources
}

async function scrollToLatest(showAnswer = false) {
  await nextTick()
  const element = conversationEl.value
  if (!element) return
  const latest = showAnswer ? element.querySelector('.message:last-of-type') : null
  element.scrollTop = latest ? Math.max(0, latest.offsetTop - 20) : element.scrollHeight
}

function clearConversation() {
  if (loading.value) return
  messages.value = []
  errorMessage.value = ''
  draft.value = ''
}

async function sendMessage(suggestedQuestion) {
  const content = typeof suggestedQuestion === 'string' ? suggestedQuestion.trim() : draft.value.trim()
  if (!content || loading.value) return
  const history = messages.value.slice(-6).map(message => ({ role: message.role, content: message.content.slice(0, 1000) }))
  loading.value = true
  errorMessage.value = ''
  draft.value = ''
  messages.value.push({ id: ++messageId, role: 'user', content })
  scrollToLatest()

  try {
    const result = await chatWithAssistant(content, history)
    messages.value.push({
      id: ++messageId,
      role: 'assistant',
      content: result.answer || '暂时没有找到可用的回答，请补充使用场景或商品关键词。',
      products: Array.isArray(result.products) ? result.products : [],
      sources: Array.isArray(result.sources) ? result.sources : [],
      citations: Array.isArray(result.citations) ? result.citations : null,
      trace: Array.isArray(result.trace) ? result.trace : [],
      mode: result.mode || null
    })
  } catch (error) {
    messages.value.pop()
    draft.value = content
    const status = error.response?.status
    const detail = error.response?.data?.detail
    if (status === 401 || status === 403) {
      errorMessage.value = '登录已失效或没有查询权限，请重新登录后再试。'
    } else if (error.code === 'ECONNABORTED') {
      errorMessage.value = '回答等待超时，请稍后重试。'
    } else {
      errorMessage.value = typeof detail === 'string' ? detail : '助手暂时无法连接，请检查服务后重试。'
    }
  } finally {
    loading.value = false
    scrollToLatest(!errorMessage.value)
  }
}
</script>

<style scoped>
.assistant-page { max-width: 1480px; margin: 0 auto; padding: 28px 30px 32px; color: var(--sc-text); font-family: 'PingFang SC', 'Microsoft YaHei UI', 'Microsoft YaHei', sans-serif; }
.assistant-heading { display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 25px; }
.assistant-heading h1 { margin: 0 0 8px; color: var(--sc-text); font-size: 26px; line-height: 1.4; font-weight: 650; letter-spacing: -.5px; text-wrap: balance; }
.assistant-heading p { margin: 0; color: var(--sc-muted); font-size: 14px; line-height: 1.7; }
.query-scope { display: flex; flex-shrink: 0; gap: 0; color: var(--sc-muted); font-size: 12px; }
.query-scope span { padding: 2px 14px; border-left: 1px solid var(--sc-border); }
.query-scope span:first-child { border-left: 0; }
.assistant-workspace { display: grid; grid-template-columns: minmax(0, 1fr) 278px; align-items: start; gap: 24px; }
.chat-panel { min-width: 0; overflow: hidden; border: 1px solid var(--sc-border); border-radius: 14px; background: var(--sc-surface); box-shadow: var(--sc-shadow); }
.chat-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 64px; padding: 12px 24px; border-bottom: 1px solid var(--sc-border); }
.chat-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.chat-title > svg { width: 21px; height: 21px; flex-shrink: 0; color: var(--sc-primary); }
.chat-title h2 { margin: 0; font-size: 15px; font-weight: 600; line-height: 1.5; }
.new-conversation { display: inline-flex; align-items: center; gap: 5px; min-height: 36px; padding: 7px 10px; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--sc-muted); font: inherit; font-size: 12px; cursor: pointer; }
.new-conversation svg { width: 16px; height: 16px; }
.new-conversation:hover:not(:disabled) { color: var(--sc-primary); border-color: var(--sc-border); background: var(--sc-primary-soft); }
.new-conversation:disabled { opacity: .5; cursor: not-allowed; }
.conversation { position: relative; height: clamp(360px, calc(100dvh - 390px), 660px); overflow-y: auto; overscroll-behavior: contain; padding: 26px 28px; scrollbar-width: thin; scrollbar-color: var(--sc-border) transparent; }
.welcome { max-width: 540px; margin: 0 auto; padding: 20px 2px 12px; }
.welcome-mark { display: grid; place-items: center; width: 46px; height: 46px; color: var(--sc-primary); background: var(--sc-primary-soft); border-radius: 12px; margin-bottom: 20px; }
.welcome-mark svg { width: 29px; height: 29px; }
.welcome h3 { margin: 0 0 9px; font-size: 21px; font-weight: 600; letter-spacing: -.3px; line-height: 1.5; text-wrap: balance; }
.welcome > p { margin: 0 0 24px; color: var(--sc-muted); font-size: 14px; line-height: 1.8; max-width: 440px; }
.suggested-prompts { display: grid; gap: 10px; }
.suggested-prompts button { display: grid; grid-template-columns: 80px minmax(0, 1fr) 18px; align-items: center; gap: 12px; width: 100%; padding: 14px 15px; border: 1px solid var(--sc-border); border-radius: 9px; background: var(--sc-surface); color: var(--sc-text); font: inherit; text-align: left; cursor: pointer; transition: border-color .16s, background-color .16s, color .16s; }
.suggested-prompts button:hover:not(:disabled) { border-color: var(--sc-primary); background: var(--sc-primary-soft); }
.suggested-prompts button:disabled { cursor: wait; opacity: .6; }
.prompt-topic { font-size: 12px; color: var(--sc-muted); }
.prompt-question { min-width: 0; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.suggested-prompts svg { width: 18px; height: 18px; color: var(--sc-primary); }
.message { max-width: 820px; margin: 0 auto 30px; min-width: 0; }
.message-label { display: flex; align-items: center; gap: 7px; margin-bottom: 12px; color: var(--sc-muted); font-size: 12px; }
.assistant-mark { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 7px; color: var(--sc-primary); background: var(--sc-primary-soft); }
.assistant-mark svg { width: 17px; height: 17px; }
.message-content { color: var(--sc-text); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.9; }
.message.user { text-align: right; }
.message.user .message-label { justify-content: flex-end; margin-bottom: 8px; }
.message.user .message-content { display: inline-block; max-width: 86%; padding: 11px 16px; border-radius: 12px 12px 3px 12px; text-align: left; background: var(--sc-primary-soft); }
.mode-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 13px; }
.mode-label { padding: 3px 7px; border-radius: 4px; color: var(--sc-muted); background: var(--sc-surface-soft); font-size: 11px; line-height: 1.5; }
.mode-label.model-answer { color: var(--sc-primary); background: var(--sc-primary-soft); }
.product-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; margin-top: 19px; }
.product-card { position: relative; display: flex; flex-direction: column; min-width: 0; padding: 18px; border: 1px solid var(--sc-border); border-radius: 10px; background: var(--sc-surface); }
.product-image { width: calc(100% + 36px); height: 160px; margin: -18px -18px 16px; object-fit: contain; background: #eef2f6; border-radius: 10px 10px 0 0; }
.product-image-caption { position: absolute; top: 10px; right: 10px; padding: 3px 7px; border-radius: 4px; background: #fff; color: #64748b; font-size: 10px; line-height: 1.5; }
.product-fact-note { margin: 12px 0 0; color: var(--sc-muted); font-size: 11px; line-height: 1.8; }
.product-topline { display: flex; justify-content: space-between; align-items: baseline; flex-wrap: wrap; gap: 7px; }
.product-category { min-width: 0; font-size: 11px; line-height: 1.5; color: var(--sc-primary); overflow-wrap: anywhere; }
.product-code { color: var(--sc-muted); font-size: 11px; overflow-wrap: anywhere; font-variant-numeric: tabular-nums; }
.product-card h3 { margin: 11px 0 10px; font-size: 15px; line-height: 1.6; font-weight: 600; overflow-wrap: anywhere; }
.product-spec { display: flex; align-items: baseline; gap: 9px; color: var(--sc-text); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.detail-label { flex-shrink: 0; color: var(--sc-muted); font-size: 11px; font-weight: 400; line-height: 1.6; }
.product-remark { margin: 9px 0 0; color: var(--sc-muted); font-size: 12px; line-height: 1.8; overflow-wrap: anywhere; }
.product-bottomline { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: flex-end; gap: 14px; margin-top: auto; padding-top: 20px; }
.price-block, .stock-block { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.price { color: var(--sc-primary); font-size: 23px; line-height: 1.3; font-weight: 650; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.stock-block { text-align: right; }
.stock-block strong { color: var(--sc-muted); font-size: 15px; line-height: 1.6; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.stock-block.stock-positive strong { color: var(--sc-success); }
.stock-block.stock-empty strong { color: var(--sc-warning); }
.evidence-collapse { margin-top: 18px; border: none; }
.evidence-collapse :deep(.el-collapse-item__header) { height: auto; min-height: 38px; padding: 5px 0; color: var(--sc-muted); font-family: inherit; font-size: 12px; line-height: 1.6; background: transparent; border-color: var(--sc-border); }
.evidence-collapse :deep(.el-collapse-item__wrap) { background: transparent; border-color: var(--sc-border); }
.evidence-collapse :deep(.el-collapse-item__content) { padding: 12px 0 16px; }
.evidence-collapse :deep(.el-collapse-item__header:hover) { color: var(--sc-primary); }
.source-item + .source-item { margin-top: 16px; }
.source-title { margin: 0 0 5px; color: var(--sc-text); font-size: 12px; font-weight: 600; line-height: 1.7; overflow-wrap: anywhere; }
.source-content { color: var(--sc-muted); white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; line-height: 1.8; }
.trace-list { margin: 0; padding: 0; list-style: none; }
.trace-item { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 12px; color: var(--sc-text); font-size: 12px; }
.trace-item:last-child { margin-bottom: 0; }
.trace-number { flex: 0 0 21px; height: 21px; text-align: center; line-height: 21px; border-radius: 50%; color: var(--sc-muted); background: var(--sc-surface-soft); font-variant-numeric: tabular-nums; }
.trace-item strong { font-weight: 500; }
.trace-item p { margin: 4px 0 0; color: var(--sc-muted); line-height: 1.7; overflow-wrap: anywhere; }
.pending-message { display: flex; align-items: center; gap: 9px; color: var(--sc-muted); font-size: 13px; line-height: 1.7; }
.loading-dot { width: 7px; height: 7px; flex-shrink: 0; border-radius: 50%; background: var(--sc-primary); animation: pulse 1.2s infinite; }
@keyframes pulse { 50% { opacity: .3; } }
.composer { padding: 19px 24px 20px; border-top: 1px solid var(--sc-border); background: var(--sc-surface); }
.composer-label { display: inline-block; margin-bottom: 10px; color: var(--sc-text); font-size: 12px; font-weight: 500; line-height: 1.5; }
.composer :deep(.el-textarea__inner) { padding: 12px 14px 24px; border-radius: 9px; background: var(--sc-surface-soft); color: var(--sc-text); font-family: inherit; font-size: 13px; line-height: 1.7; box-shadow: 0 0 0 1px var(--sc-border) inset; }
.composer :deep(.el-textarea__inner::placeholder) { color: var(--sc-muted); }
.composer :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px var(--sc-primary) inset; }
.composer :deep(.el-input__count) { background: transparent; color: var(--sc-muted); font-size: 10px; }
.composer-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; }
.composer-footer > span { color: var(--sc-muted); font-size: 11px; line-height: 1.5; }
.composer-footer :deep(.el-button) { min-height: 38px; padding: 8px 19px; border-radius: 8px; font-family: inherit; }
.error-alert { margin-bottom: 14px; padding: 10px 12px; border-left: 3px solid var(--sc-warning); border-radius: 4px; color: var(--sc-text); background: var(--sc-surface-soft); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.selection-context { min-width: 0; padding: 0; margin: 0; background: transparent; border-radius: 0; font: inherit; line-height: normal; }
.selection-guide { padding: 22px 0 0; }
.selection-guide h2, .query-note h2 { margin: 0; color: var(--sc-text); font-size: 14px; font-weight: 600; line-height: 1.6; }
.guide-intro { margin: 7px 0 0; color: var(--sc-muted); font-size: 12px; line-height: 1.8; }
.selection-list { margin: 20px 0 0; }
.selection-list > div { padding: 15px 0; border-top: 1px solid var(--sc-border); }
.selection-list dt { margin-bottom: 5px; font-size: 12px; font-weight: 500; color: var(--sc-text); }
.selection-list dd { margin: 0; color: var(--sc-muted); font-size: 12px; line-height: 1.8; }
.query-note { margin-top: 13px; padding: 17px 18px; border-left: 2px solid var(--sc-primary); border-radius: 0 8px 8px 0; background: var(--sc-primary-soft); }
.query-note h2 { font-size: 12px; }
.query-note p { margin: 6px 0 0; color: var(--sc-muted); font-size: 12px; line-height: 1.9; }
.session-note { margin: 19px 1px 0; color: var(--sc-muted); font-size: 11px; line-height: 1.8; }
.assistant-page button { touch-action: manipulation; -webkit-tap-highlight-color: transparent; }
.assistant-page button:focus-visible, .assistant-page :deep(.el-collapse-item__header:focus-visible), .assistant-page :deep(.el-textarea__inner:focus-visible) { outline: 2px solid var(--sc-primary); outline-offset: 3px; }
@media (min-width: 1600px) { .conversation { padding-right: 38px; padding-left: 38px; } }
@media (max-width: 1100px) { .assistant-workspace { grid-template-columns: minmax(0, 1fr) 230px; gap: 20px; } .assistant-page { padding-right: 22px; padding-left: 22px; } .suggested-prompts button { grid-template-columns: minmax(0, 1fr) 18px; } .prompt-topic { grid-column: 1; font-size: 11px; } .prompt-question { grid-column: 1; } .suggested-prompts svg { grid-column: 2; grid-row: 1 / span 2; } }
@media (max-width: 900px) { .assistant-workspace { grid-template-columns: minmax(0, 1fr); } .selection-context { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 24px; } .selection-guide { padding-top: 6px; } .selection-list > div { padding: 12px 0; } .query-note { align-self: start; margin-top: 6px; } .session-note { grid-column: 1 / -1; margin-top: 12px; } .prompt-topic { grid-column: auto; } .prompt-question { grid-column: auto; } .suggested-prompts button { grid-template-columns: 80px minmax(0, 1fr) 18px; } .suggested-prompts svg { grid-column: auto; grid-row: auto; } }
@media (max-width: 600px) {
  .assistant-page { padding: 20px 14px 25px; }
  .assistant-heading { align-items: flex-start; flex-direction: column; gap: 12px; margin-bottom: 18px; }
  .assistant-heading h1 { font-size: 23px; }
  .assistant-heading p { font-size: 12px; }
  .query-scope { font-size: 11px; }
  .query-scope span { padding: 0 12px; }
  .query-scope span:first-child { padding-left: 0; }
  .chat-toolbar { min-height: 54px; padding: 9px 16px; }
  .chat-title h2 { font-size: 14px; }
  .conversation { height: clamp(235px, 40dvh, 400px); padding: 19px 16px; }
  .welcome { padding: 3px 0 7px; }
  .welcome-mark { width: 38px; height: 38px; margin-bottom: 14px; border-radius: 10px; }
  .welcome-mark svg { width: 24px; height: 24px; }
  .welcome h3 { font-size: 18px; }
  .welcome > p { margin-bottom: 17px; font-size: 12px; }
  .suggested-prompts button { grid-template-columns: minmax(0, 1fr) 16px; gap: 4px 10px; padding: 11px 12px; }
  .prompt-topic, .prompt-question { grid-column: 1; }
  .prompt-question { font-size: 12px; }
  .suggested-prompts svg { grid-column: 2; grid-row: 1 / span 2; width: 16px; height: 16px; }
  .message-content { font-size: 13px; }
  .message.user .message-content { max-width: 94%; padding: 10px 13px; }
  .product-grid { grid-template-columns: minmax(0, 1fr); }
  .product-card { padding: 16px; }
  .product-image { width: calc(100% + 32px); margin: -16px -16px 14px; }
  .composer { padding: 14px 16px 16px; }
  .composer-label { margin-bottom: 8px; }
  .composer-footer > span { max-width: 120px; font-size: 10px; }
  .composer-footer :deep(.el-button) { padding: 8px 14px; }
  .selection-context { grid-template-columns: minmax(0, 1fr); }
  .selection-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 20px; margin-top: 12px; }
  .selection-list dd { font-size: 11px; }
  .query-note { margin-top: 12px; }
}
@media (prefers-reduced-motion: reduce) { .loading-dot { animation: none; } .suggested-prompts button { transition: none; } }
</style>
