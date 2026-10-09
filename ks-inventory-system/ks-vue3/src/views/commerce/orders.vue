<template>
  <div class="app-container commerce-workbench">
    <header class="commerce-heading">
      <div>
        <h1>商城订单与库存</h1>
        <el-tooltip content="本地支付沙箱，不会实际收款或委托物流。" placement="right"><el-tag type="info" size="small">{{ tenantId }} · 沙箱环境</el-tag></el-tooltip>
      </div>
      <div class="commerce-shop-actions">
        <el-select v-model="shopId" aria-label="当前店铺" @change="changeShop"><el-option v-for="shop in shops" :key="shop.shopId" :label="shop.shopName" :value="shop.shopId" /></el-select>
        <el-button v-if="canCreateShop" @click="newShop">开设店铺</el-button>
        <el-button icon="Refresh" :loading="ordersLoading || inventoryLoading" @click="refreshAll">刷新</el-button>
      </div>
    </header>
    <nav class="commerce-navigation" aria-label="商城业务工作区">
      <button v-for="section in workbenchSections" :key="section.id" type="button" :aria-pressed="workbenchSection === section.id" @click="selectWorkbench(section.id)">{{ section.label }}</button>
    </nav>

    <section v-show="workbenchSection === 'orders'" class="commerce-section" aria-labelledby="commerce-orders-title">
      <div class="commerce-section-heading">
        <h2 id="commerce-orders-title">订单处理</h2>
        <el-select v-model="query.status" aria-label="订单状态" placeholder="全部状态" clearable @change="filterOrders">
          <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </div>
      <el-alert v-if="ordersError" :title="ordersError" type="error" :closable="false" show-icon class="commerce-error" />
      <el-table v-loading="ordersLoading" :data="orders" row-key="orderId" empty-text="暂无订单，可到顾客商城下单后刷新。">
        <el-table-column type="expand">
          <template #default="scope">
            <div class="commerce-order-items">
              <div v-for="item in scope.row.items" :key="item.productId" class="commerce-line-item">
                <div class="commerce-product">
                  <img :src="getProductImage(item)" :alt="`${item.productName || '货品'}缩略图`" loading="lazy" @error="handleProductImageError($event, item)" />
                  <div><strong>{{ item.productName }}</strong><span>{{ item.productCode }}<template v-if="item.spec"> / {{ item.spec }}</template></span></div>
                </div>
                <span>{{ formatNumber(item.quantity) }} 件 × {{ formatMoney(item.unitPrice) }}<small v-if="item.orderedQuantity != null" class="commerce-quantity-note">已发 {{ item.shippedQuantity }} · 待发 {{ item.unshippedQuantity }} · 已退 {{ item.returnedQuantity }} · 已取消 {{ item.cancelledQuantity }}</small></span>
                <strong>{{ formatMoney(item.amount) }}</strong>
              </div>
              <p v-if="scope.row.receiptId" class="commerce-record-note">销售出库单：{{ scope.row.receiptId }} · {{ scope.row.carrier || '未填写承运商' }}<template v-if="scope.row.trackingNo"> · {{ scope.row.trackingNo }}</template></p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="订单编号" prop="orderId" min-width="220" />
        <el-table-column label="下单时间" min-width="170"><template #default="scope">{{ formatTime(scope.row.createTime) }}</template></el-table-column>
        <el-table-column label="订单金额" align="right" width="120"><template #default="scope">{{ formatMoney(scope.row.totalAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="160"><template #default="scope"><el-tag :type="statusTone(scope.row)">{{ statusLabel(scope.row) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="scope">
            <el-button link type="primary" @click="showDetail(scope.row)">查看</el-button>
            <el-button v-if="hasCapability('FULFILMENT')" type="primary" size="small" :disabled="!canShip(scope.row) || !!shippingId" :loading="shippingId === scope.row.orderId" @click="openShipment(scope.row)">登记发货</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="loadOrders" />
    </section>

    <AfterSalesPanel v-if="workbenchSection === 'orders'" ref="afterSalesPanel" :shop-id="shopId" :capabilities="capabilities" @updated="refreshAll" />
    <ReplenishmentPanel v-if="workbenchSection === 'supply'" ref="replenishmentPanel" :shop-id="shopId" :capabilities="capabilities" :user-id="userId" :member-role="currentShop?.memberRole || ''" @updated="refreshAll" />
    <DurableAgentTaskPanel v-if="workbenchSection === 'supply'" ref="agentTaskPanel" :shop-id="shopId" :capabilities="capabilities" :user-id="userId" @updated="refreshAll" />
    <CostReconciliationPanel v-if="workbenchSection === 'finance' && hasCapability('REFUND_REVIEW')" ref="costPanel" :shop-id="shopId" :capabilities="capabilities" @updated="refreshAll" />

    <section v-show="workbenchSection === 'inventory'" class="commerce-section" aria-labelledby="commerce-inventory-title">
      <div class="commerce-section-heading"><h2 id="commerce-inventory-title">商城商品库存</h2><div><el-tag :type="reconciliation ? (reconciliation.healthy ? 'success' : 'danger') : 'info'">{{ reconciliation?.healthy === false ? '库存有差异' : reconciliation ? '账实核对一致' : '核对暂不可用' }}</el-tag><el-button v-if="hasCapability('CATALOG')" link type="primary" style="margin-left:16px" @click="openListing">上架货品</el-button></div></div>
      <el-alert v-if="reconciliation?.issues?.length" :title="'发现 ' + reconciliation.issues.length + ' 项库存差异，请核对原业务单据'" type="error" :closable="false" />
      <el-alert v-if="inventoryError" :title="inventoryError" type="error" :closable="false" show-icon class="commerce-error" />
      <el-table v-loading="inventoryLoading" :data="inventory" row-key="productId" empty-text="暂无商城商品库存。">
        <el-table-column label="商品" min-width="300">
          <template #default="scope">
            <div class="commerce-product">
              <img :src="getProductImage(scope.row)" :alt="`${scope.row.productName || scope.row.name || '货品'}缩略图`" loading="lazy" @error="handleProductImageError($event, scope.row)" />
              <div><strong>{{ scope.row.productName || scope.row.name }}</strong><span>{{ scope.row.productCode || scope.row.code }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="账面库存" align="right" min-width="115"><template #default="scope">{{ formatNumber(scope.row.bookStock) }}</template></el-table-column>
        <el-table-column label="仓分布" width="90"><template #default="scope"><el-button link type="primary" @click="openWarehouses(scope.row)">按仓查看</el-button></template></el-table-column>
        <el-table-column label="订单预留" align="right" min-width="115"><template #default="scope"><span :class="{ 'commerce-reserved': Number(scope.row.reservedStock) > 0 }">{{ formatNumber(scope.row.reservedStock) }}</span></template></el-table-column>
        <el-table-column label="活动待抢" align="right" min-width="115"><template #default="scope">{{ formatNumber(scope.row.activityStock || 0) }}</template></el-table-column>
        <el-table-column label="不可售" align="right" min-width="100"><template #default="scope"><el-button v-if="Number(scope.row.unavailableStock) > 0" link type="warning" @click="openConditions(scope.row)">{{ formatNumber(scope.row.unavailableStock) }} · 原因</el-button><span v-else>0</span></template></el-table-column>
        <el-table-column label="流水" width="85"><template #default="scope"><el-button link type="primary" @click="showLedger(scope.row)">查看</el-button></template></el-table-column>
        <el-table-column label="上架" width="85"><template #default="scope"><el-switch :model-value="!!Number(scope.row.listed)" :disabled="!hasCapability('CATALOG')" @change="value => toggleListing(scope.row,value)" /></template></el-table-column>
        <el-table-column v-if="hasCapability('CATALOG')" label="活动" width="110"><template #default="scope"><el-button link type="primary" :disabled="Number(scope.row.availableStock) <= 0 || !Number(scope.row.listed)" @click="openActivity(scope.row)">创建秒杀</el-button></template></el-table-column>
        <el-table-column label="可售库存" align="right" min-width="115"><template #header><el-tooltip content="账面库存减去订单预留、活动待抢和不可售数量；取消释放预留，发货扣减账面。" placement="top"><span tabindex="0">可售库存 ⓘ</span></el-tooltip></template><template #default="scope"><strong :class="{ 'commerce-out': Number(scope.row.availableStock) <= 0 }">{{ formatNumber(scope.row.availableStock) }}</strong></template></el-table-column>
      </el-table>
      <p v-if="lastUpdated" class="commerce-updated" aria-live="polite">最近成功读取 {{ lastUpdated }}</p>
    </section>

    <el-drawer v-model="conditionsVisible" :title="conditionProduct.productName + ' · 不可售登记'" size="min(700px, 100vw)">
      <el-alert v-if="conditionError" :title="conditionError" type="error" :closable="false" />
      <el-table :data="conditionRows" v-loading="conditionLoading" empty-text="最近登记中没有该商品的记录">
        <el-table-column label="仓库" prop="warehouseId" width="80" /><el-table-column label="调整" min-width="170"><template #default="scope">{{ conditionNames[scope.row.fromState] || scope.row.fromState }} → {{ conditionNames[scope.row.toState] || scope.row.toState }}</template></el-table-column><el-table-column label="数量" prop="quantity" width="80" /><el-table-column label="登记原因" prop="reason" min-width="200" />
      </el-table>
    </el-drawer>
    <section v-show="workbenchSection === 'orders'" class="commerce-section" aria-label="交易队列">
      <div class="commerce-section-heading"><h2>交易队列</h2><span class="commerce-sync-note">最长等待 {{ queueMetrics.oldestWaitingSeconds || 0 }} 秒</span></div>
      <div class="commerce-queue-states"><span>待处理 <strong>{{ queueMetrics.PENDING || 0 }}</strong></span><span>处理中 <strong>{{ queueMetrics.PROCESSING || 0 }}</strong></span><span>已成单 <strong>{{ queueMetrics.SUCCEEDED || 0 }}</strong></span><span>未成单 <strong>{{ queueMetrics.REJECTED || 0 }}</strong></span></div>
    </section>
    <el-drawer v-model="warehousesVisible" :title="warehouseProduct.productName + ' · 按仓库存'" size="min(720px, 100vw)">
      <el-alert v-if="warehousesError" :title="warehousesError" type="error" :closable="false" />
      <el-table :data="warehouseBalances" v-loading="warehousesLoading" empty-text="暂无仓库库存记录">
        <el-table-column label="仓库" prop="warehouseId" width="100" />
        <el-table-column label="在库" prop="onHand" align="right" />
        <el-table-column label="订单占用" prop="orderReserved" align="right" />
        <el-table-column label="质检 / 损坏" prop="unavailable" align="right" />
        <el-table-column label="未占用" prop="uncommitted" align="right" />
      </el-table>
    </el-drawer>
    <el-drawer v-model="ledgerVisible" :title="ledgerProduct.productName + ' · 库存流水'" size="min(760px, 100vw)">
      <el-radio-group v-model="ledgerTab" style="margin-bottom:20px"><el-radio-button value="sellable" label="sellable">可售库存</el-radio-button><el-radio-button value="warehouse" label="warehouse">仓库出入库</el-radio-button></el-radio-group>
      <el-alert v-if="ledgerError" :title="ledgerError" type="error" :closable="false" />
      <el-table v-if="ledgerTab==='sellable'" :data="ledgerRows" v-loading="ledgerLoading" empty-text="暂无流水">
        <el-table-column label="时间" prop="created_at" min-width="165" />
        <el-table-column label="业务" width="130"><template #default="scope">{{ ledgerNames[scope.row.event_type] || scope.row.event_type }}</template></el-table-column>
        <el-table-column label="账面变化" prop="delta_on_hand" width="90" />
        <el-table-column label="占用变化" prop="delta_reserved" width="90" />
        <el-table-column label="活动变化" prop="delta_activity" width="90" />
        <el-table-column label="关联订单 / 活动" min-width="240"><template #default="scope">{{ scope.row.order_id || scope.row.activity_id || '—' }}</template></el-table-column>
      </el-table>
      <el-table v-else :data="warehouseRows" v-loading="ledgerLoading" empty-text="暂无仓库流水">
        <el-table-column label="时间" prop="created_at" min-width="170" />
        <el-table-column label="仓库" prop="warehouse_id" width="80" />
        <el-table-column label="数量变化" prop="delta_quantity" width="100" />
        <el-table-column label="变化前" prop="before_quantity" width="90" />
        <el-table-column label="变化后" prop="after_quantity" width="90" />
        <el-table-column label="关联单据" prop="related_receipts" min-width="240" />
      </el-table>
    </el-drawer>
    <el-dialog v-model="listingVisible" title="上架货品" width="480px">
      <el-select v-model="listingProduct" filterable placeholder="选择本企业的货品" style="width:100%"><el-option v-for="product in listingCandidates" :key="product.productId" :value="product.productId" :label="product.productName + ' · ' + product.productCode" /></el-select>
      <template #footer><el-button @click="listingVisible=false">返回</el-button><el-button type="primary" :disabled="!listingProduct" @click="publishListing">上架到当前店铺</el-button></template>
    </el-dialog>
    <section v-show="workbenchSection === 'activities'" class="commerce-section" aria-label="秒杀活动">
      <div class="commerce-section-heading"><h2>秒杀活动</h2><el-link :href="tenantId === 'studio' ? 'http://127.0.0.1:6002/activities' : 'http://127.0.0.1:6001/activities'" target="_blank">顾客抢购入口 ↗</el-link></div>
      <el-table :data="activities" empty-text="选择有可售库存的商品创建活动">
        <el-table-column label="商品" min-width="230"><template #default="scope"><ProductIdentity :product="scope.row" /></template></el-table-column>
        <el-table-column label="活动" prop="title" min-width="180" />
        <el-table-column label="活动价" width="110"><template #default="scope">{{ formatMoney(scope.row.price) }}</template></el-table-column>
        <el-table-column label="剩余 / 配额" width="120"><template #default="scope">{{ scope.row.remaining }} / {{ scope.row.capacity }}</template></el-table-column>
        <el-table-column label="每人限购" prop="perOwnerLimit" width="100" />
        <el-table-column label="截止时间" prop="endsAt" min-width="180" />
      </el-table>
    </section>
    <el-dialog v-model="activityVisible" title="创建秒杀活动" width="440px" :close-on-click-modal="false">
      <ProductIdentity :product="activityProduct" />
      <el-form label-position="top" style="margin-top:20px" @submit.prevent="submitActivity">
        <el-form-item label="活动标题"><el-input v-model="activityForm.title" maxlength="80" /></el-form-item>
        <el-form-item label="活动价"><el-input-number v-model="activityForm.price" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="分配库存"><el-input-number v-model="activityForm.capacity" :min="1" :max="Math.min(1000, Number(activityProduct.availableStock || 1))" /></el-form-item>
        <el-form-item label="每人限购"><el-input-number v-model="activityForm.perOwnerLimit" :min="1" :max="10" /></el-form-item>
        <el-form-item label="持续分钟"><el-input-number v-model="activityForm.minutes" :min="1" :max="1440" /></el-form-item>
      </el-form>
      <el-alert v-if="activityError" :title="activityError" type="error" :closable="false" />
      <template #footer><el-button @click="activityVisible=false">返回</el-button><el-button type="primary" :loading="activitySaving" @click="submitActivity">分配库存并开始</el-button></template>
    </el-dialog>

    <el-dialog v-model="shipmentVisible" title="选择此次发货商品" width="680px" :close-on-click-modal="false" :before-close="closeShipment">
      <template v-if="shipmentOrder">
        <p class="commerce-dialog-order">{{ shipmentOrder.orderId }}</p>
        <el-select v-model="shipmentWarehouse" clearable aria-label="本次发货仓库" placeholder="按订单分配自动出库" :disabled="!!shippingId || shipmentLoading" @change="resetShipmentQuantities" style="width:100%">
          <el-option v-for="warehouse in shipmentWarehouses" :key="warehouse" :value="warehouse" :label="'仓库 ' + warehouse" />
        </el-select>
        <el-table :data="shipmentOrder.items || []" v-loading="shipmentLoading" style="margin-top:16px">
          <el-table-column label="商品" min-width="240"><template #default="scope"><div class="commerce-product"><img :src="getProductImage(scope.row)" :alt="scope.row.productName" @error="handleProductImageError($event, scope.row)" /><div><strong>{{ scope.row.productName }}</strong><span>本仓可发 {{ warehouseShippable(scope.row) }} · 已发 {{ scope.row.shippedQuantity || 0 }}</span></div></div></template></el-table-column>
          <el-table-column label="本次发货" width="165"><template #default="scope"><el-input-number v-model="shipmentQuantities[String(scope.row.productId)]" :min="0" :max="warehouseShippable(scope.row)" :precision="0" size="small" :disabled="!!shippingId || shipmentLoading" :aria-label="`${scope.row.productName}本次发货数量`" /></template></el-table-column>
        </el-table>
        <el-form ref="shipmentFormRef" :model="shipmentForm" :rules="shipmentRules" label-position="top" class="commerce-shipment-form">
          <el-form-item label="承运商" prop="carrier"><el-input v-model="shipmentForm.carrier" maxlength="40" placeholder="例如：演示物流" :disabled="!!shippingId" /></el-form-item>
          <el-form-item label="物流单号（可选）" prop="trackingNo"><el-input v-model="shipmentForm.trackingNo" maxlength="64" placeholder="留空时由系统生成演示单号" :disabled="!!shippingId" /></el-form-item>
        </el-form>
        <el-alert v-if="shipmentError" :title="shipmentError" type="error" :closable="false" show-icon />
      </template>
      <template #footer><el-button :disabled="!!shippingId" @click="shipmentVisible = false">返回</el-button><el-button type="primary" :loading="!!shippingId" :disabled="shipmentLoading || !shipmentReady" @click="submitShipment">确认登记发货</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="商城订单详情" width="720px">
      <div v-loading="detailLoading" class="commerce-details">
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
        <template v-else-if="detail">
          <el-steps v-if="orderStatus(detail) !== 4" :active="orderStatus(detail)" finish-status="success" align-center style="margin-bottom: 24px"><el-step title="沙箱支付" /><el-step title="发货出库" /><el-step title="确认收货" /></el-steps>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="订单编号" :span="2">{{ detail.orderId }}</el-descriptions-item>
            <el-descriptions-item label="订单状态">{{ statusLabel(detail) }}</el-descriptions-item>
            <el-descriptions-item label="订单金额">{{ formatMoney(detail.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.shippedAmount != null" label="已发货金额">{{ formatMoney(detail.shippedAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.returnedAmount != null" label="已退货金额">{{ formatMoney(detail.returnedAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.refundedAmount != null" label="已退款金额">{{ formatMoney(detail.refundedAmount) }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.shippingAddress" label="收货人">{{ detail.shippingAddress.addressee }} · {{ detail.shippingAddress.phone }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.shippingAddress" label="收货地址" :span="2">{{ detail.shippingAddress.address }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.afterSalesStatus" label="售后状态">{{ afterSalesLabels[detail.afterSalesStatus] || '状态待核对' }}</el-descriptions-item>
            <el-descriptions-item label="下单时间">{{ formatTime(detail.createTime) }}</el-descriptions-item>
            <el-descriptions-item label="沙箱支付时间">{{ formatTime(detail.paidTime) }}</el-descriptions-item>
            <el-descriptions-item label="发货时间">{{ formatTime(detail.shippedTime) }}</el-descriptions-item>
            <el-descriptions-item label="收货时间">{{ formatTime(detail.receivedTime) }}</el-descriptions-item>
            <el-descriptions-item label="支付渠道" :span="2">{{ detail.paymentProvider === 'LOCAL_SANDBOX' ? '本地支付沙箱（未实际收款）' : detail.paymentProvider || '—' }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.transactionId" label="沙箱交易编号" :span="2">{{ detail.transactionId }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.receiptId" label="销售出库单">{{ detail.receiptId }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.carrier" label="承运商">{{ detail.carrier }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.trackingNo" label="物流单号" :span="2">{{ detail.trackingNo }}</el-descriptions-item>
          </el-descriptions>
          <div class="commerce-order-items">
            <div v-for="item in detail.items" :key="item.productId" class="commerce-line-item">
              <div class="commerce-product"><img :src="getProductImage(item)" :alt="`${item.productName || '货品'}缩略图`" @error="handleProductImageError($event, item)" /><div><strong>{{ item.productName }}</strong><span>{{ item.spec }}</span></div></div>
              <span>{{ formatNumber(item.quantity) }} 件<small v-if="item.orderedQuantity != null" class="commerce-quantity-note">已发 {{ item.shippedQuantity }} · 待发 {{ item.unshippedQuantity }} · 已退 {{ item.returnedQuantity }} · 已取消 {{ item.cancelledQuantity }}</small></span><strong>{{ formatMoney(item.amount) }}</strong>
            </div>
          </div>
          <el-table v-if="detail.warehouseAllocations?.length" :data="detail.warehouseAllocations" style="margin-top:20px"><el-table-column label="商品编号" prop="productId" /><el-table-column label="分配仓库" prop="warehouseId" /><el-table-column label="待发" prop="reservedQuantity" /><el-table-column label="已发" prop="shippedQuantity" /><el-table-column label="已释放" prop="releasedQuantity" /></el-table>
          <el-table v-if="detail.shipments?.length" :data="detail.shipments" style="margin-top:20px"><el-table-column label="发货时间" prop="createdAt" min-width="165" /><el-table-column label="承运商" prop="carrier" min-width="120" /><el-table-column label="运单" prop="trackingNo" min-width="160" /><el-table-column label="出库单" prop="receiptId" width="100" /><el-table-column label="此次金额" width="110"><template #default="scope">{{ formatMoney(scope.row.amount) }}</template></el-table-column></el-table>
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<script setup name="CommerceOrders">
import { ElMessage, ElMessageBox } from 'element-plus'
import useUserStore from '@/store/modules/user'
import { listProduct } from '@/api/basedate/product'
import { setCommerceShop, listCommerceShops, createCommerceShop, listStockLedger, listWarehouseLedger, reconcileStock, getQueueMetrics, setProductListing } from '@/api/commerce/orders'
import { listCommerceOrders, getCommerceOrder, listCommerceInventory, shipCommerceOrder, getCommerceContext, listCommerceActivities, createCommerceActivity } from '@/api/commerce/orders'
import { getProductImage, handleProductImageError } from '@/utils/productMedia'
import AfterSalesPanel from './components/AfterSalesPanel.vue'
import ReplenishmentPanel from './components/ReplenishmentPanel.vue'
import CostReconciliationPanel from './components/CostReconciliationPanel.vue'
import DurableAgentTaskPanel from './components/DurableAgentTaskPanel.vue'
import { useRoute, useRouter } from 'vue-router'
import { listCommerceWarehouses } from '@/api/commerce/orders'
import { listCommerceStockConditions } from '@/api/commerce/orders'
const afterSalesPanel = ref()
const replenishmentPanel = ref()
const costPanel = ref(), agentTaskPanel = ref()
const route = useRoute(), router = useRouter()
const sectionIds = ['orders', 'inventory', 'supply', 'finance', 'activities']
const workbenchSection = ref(sectionIds.includes(route.query.section) ? route.query.section : 'orders')
const workbenchSections = computed(() => [
  { id: 'orders', label: '订单与售后' }, { id: 'inventory', label: '仓库库存' },
  { id: 'supply', label: '采购与任务' },
  ...(hasCapability('REFUND_REVIEW') ? [{ id: 'finance', label: '成本与对账' }] : []),
  { id: 'activities', label: '活动' }
])
async function selectWorkbench(section) {
  workbenchSection.value = section
  await router.replace({ query: { ...route.query, section } })
  await nextTick()
  await refreshAll()
}
watch(() => route.query.section, section => {
  if (sectionIds.includes(section) && section !== workbenchSection.value) {
    workbenchSection.value = section
    nextTick(refreshAll)
  }
})
const warehousesVisible = ref(false), warehousesLoading = ref(false), warehouseProduct = ref({}), warehouseBalances = ref([]), warehousesError = ref('')
async function openWarehouses(product) {
  const shop = shopId.value
  warehouseProduct.value = product; warehouseBalances.value = []; warehousesError.value = ''; warehousesVisible.value = true; warehousesLoading.value = true
  try {
    const response = await listCommerceWarehouses(product.productId)
    if (shop === shopId.value && warehouseProduct.value.productId === product.productId) warehouseBalances.value = response.data || []
  } catch (error) {
    if (shop === shopId.value) warehousesError.value = errorText(error, '按仓库存读取失败')
  } finally { warehousesLoading.value = false }
}
const conditionsVisible = ref(false), conditionLoading = ref(false), conditionRows = ref([]), conditionProduct = ref({}), conditionError = ref('')
const conditionNames = {SELLABLE:'可售',QUALITY_HOLD:'质检冻结',DAMAGED:'损坏'}
async function openConditions(product) {
  const shop = shopId.value
  conditionProduct.value = product; conditionRows.value = []; conditionError.value = ''; conditionsVisible.value = true; conditionLoading.value = true
  try { const result = await listCommerceStockConditions(); if (shop === shopId.value && conditionProduct.value.productId === product.productId) conditionRows.value = (result.data || []).filter(row => String(row.productId) === String(product.productId)) }
  catch (failure) { if (shop === shopId.value) conditionError.value = failure?.message || '不可售原因读取失败，请重试' }
  finally { conditionLoading.value = false }
}

const tenantId = ref('demo')
const userId = ref(null)
const shopId = ref('default')
const shops = ref([])
const currentShop = computed(() => shops.value.find(shop => shop.shopId === shopId.value))
const capabilities = computed(() => Array.isArray(currentShop.value?.capabilities) ? currentShop.value.capabilities : [])
const hasCapability = capability => capabilities.value.includes(capability)
const canCreateShop = computed(() => useUserStore().roles.some(role => ['admin', 'tenant_admin'].includes(role)))
const reconciliation = ref(null)
const queueMetrics = ref({})
const ledgerVisible = ref(false), ledgerLoading = ref(false), ledgerRows = ref([]), ledgerProduct = ref({})
const ledgerTab = ref('sellable'), warehouseRows = ref([]), ledgerError = ref('')
const listingVisible = ref(false), listingCandidates = ref([]), listingProduct = ref(null)
const ledgerNames = { BOOTSTRAP: '初始余额', RESERVE: '订单占用', RELEASE: '取消 / 超时释放', DISPATCH: '发货出库', ACTIVITY_ALLOCATE: '活动分配', ACTIVITY_EXPIRE: '活动结束', EXTERNAL_ADJUST: '进销存单据变动' }
async function loadShops() {
  shops.value = (await listCommerceShops()).data
  if (workbenchSection.value === 'finance' && !hasCapability('REFUND_REVIEW')) await selectWorkbench('orders')
}
async function newShop() {
  if (!canCreateShop.value) return
  const result = await ElMessageBox.prompt('店铺名称', '开设店铺', { inputValidator: value => !!value?.trim() || '请填写店铺名称' }).catch(() => null)
  if (!result) return
  const shop = (await createCommerceShop(result.value.trim())).data
  await loadShops(); shopId.value = shop.shopId; await changeShop()
}
async function changeShop() {
  setCommerceShop(shopId.value); query.pageNum = 1
  orders.value = []; inventory.value = []; activities.value = []; total.value = 0; reconciliation.value = null; queueMetrics.value = {}
  detailVisible.value = false; shipmentVisible.value = false; ledgerVisible.value = false
  conditionsVisible.value = false
  warehousesVisible.value = false
  if (workbenchSection.value === 'finance' && !hasCapability('REFUND_REVIEW')) workbenchSection.value = 'orders'
  await refreshAll()
}
async function showLedger(product) {
  const currentShop=shopId.value
  ledgerVisible.value = true; ledgerProduct.value = product; ledgerRows.value = []; warehouseRows.value = []; ledgerLoading.value = true; ledgerError.value = ''; ledgerTab.value = 'sellable'
  try {
    const [sellable,warehouse] = await Promise.all([listStockLedger(product.productId),listWarehouseLedger(product.productId)])
    if (currentShop !== shopId.value || ledgerProduct.value.productId !== product.productId) return
    ledgerRows.value = sellable.data; warehouseRows.value = warehouse.data
  } catch(error) { ledgerError.value = errorText(error,'库存流水读取失败') }
  finally { ledgerLoading.value = false }
}
async function toggleListing(product,value) { if (!hasCapability('CATALOG')) return; await setProductListing(product.productId,value); await refreshAll() }
async function openListing() {
  if (!hasCapability('CATALOG')) return
  listingProduct.value = null; listingVisible.value = true
  listingCandidates.value = (await listProduct({ pageNum: 1, pageSize: 100 })).rows
}
async function publishListing() { if (!hasCapability('CATALOG')) return; await setProductListing(listingProduct.value,true); listingVisible.value = false; await refreshAll() }
async function loadOperations() {
  const current = shopId.value
  const [report, metrics] = await Promise.all([reconcileStock(),getQueueMetrics()])
  if (current !== shopId.value) return
  reconciliation.value = report.data; queueMetrics.value = metrics.data
}
const activities = ref([])
const activityVisible = ref(false)
const activitySaving = ref(false)
const activityError = ref('')
const activityProduct = ref({})
const activityForm = reactive({ title: '智能家居限时秒杀', price: 99, capacity: 1, perOwnerLimit: 1, minutes: 30 })
function openActivity(product) { if (!hasCapability('CATALOG')) return; activityProduct.value = product; activityError.value = ''; activityVisible.value = true }
function localIso(date) { return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19) }
async function submitActivity() {
  if (activitySaving.value || !hasCapability('CATALOG')) return
  activitySaving.value = true; activityError.value = ''
  try {
    const now = new Date()
    await createCommerceActivity({ activityId: 'rush_' + Date.now(), productId: activityProduct.value.productId,
      title: activityForm.title, price: activityForm.price, capacity: activityForm.capacity, perOwnerLimit: activityForm.perOwnerLimit,
      startsAt: localIso(new Date(now.getTime() - 1000)), endsAt: localIso(new Date(now.getTime() + activityForm.minutes * 60000)) })
    activityVisible.value = false; ElMessage.success('活动已开始'); await refreshAll()
  } catch (error) { activityError.value = errorText(error, '活动创建失败') }
  finally { activitySaving.value = false }
}
async function loadActivities() { const current = shopId.value; const response = await listCommerceActivities(); if (current === shopId.value) activities.value = response.data }
const query = reactive({ pageNum: 1, pageSize: 20, status: undefined })
const orders = ref([])
const total = ref(0)
const inventory = ref([])
const ordersLoading = ref(false)
const inventoryLoading = ref(false)
const ordersError = ref('')
const inventoryError = ref('')
const lastUpdated = ref('')
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref(null)
const shipmentVisible = ref(false)
const shipmentOrder = ref(null)
const shipmentWarehouse = ref(null), shipmentLoading = ref(false), shipmentReady = ref(false)
const shipmentWarehouses = computed(() => [...new Set((shipmentOrder.value?.warehouseAllocations || []).filter(row => Number(row.reservedQuantity) > 0).map(row => row.warehouseId))])
function warehouseShippable(item) {
  const maximum = shippable(item, shipmentOrder.value)
  if (shipmentWarehouse.value == null) return maximum
  const assigned = (shipmentOrder.value?.warehouseAllocations || []).filter(row => String(row.productId) === String(item.productId) && String(row.warehouseId) === String(shipmentWarehouse.value)).reduce((sum, row) => sum + Number(row.reservedQuantity || 0), 0)
  return Math.min(maximum, assigned)
}
function resetShipmentQuantities() {
  for (const key of Object.keys(shipmentQuantities)) delete shipmentQuantities[key]
  for (const item of shipmentOrder.value?.items || []) shipmentQuantities[String(item.productId)] = 0
}
const shippingId = ref('')
const shipmentError = ref('')
const shipmentFormRef = ref()
const shipmentForm = reactive({ carrier: '演示物流', trackingNo: '' })
const shipmentQuantities = reactive({})
let shipmentRequestKey = '', shipmentFingerprint = ''
let syncTimer
const shipmentRules = { carrier: [{ required: true, whitespace: true, message: '请填写承运商', trigger: 'blur' }] }
const statusOptions = [
  { value: 0, label: '待支付' }, { value: 1, label: '待发货' },
  { value: 2, label: '已发货' }, { value: 3, label: '已收货' }, { value: 4, label: '已取消' }
]
const moneyFormatter = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' })
const numberFormatter = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 3 })

function formatMoney(value) {
  return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) ? moneyFormatter.format(Number(value)) : '—'
}
function formatNumber(value) {
  return value !== null && value !== undefined && value !== '' && Number.isFinite(Number(value)) ? numberFormatter.format(Number(value)) : '—'
}
function formatTime(value) { return value ? String(value).replace('T', ' ').slice(0, 19) : '—' }
function orderStatus(order) { return Number(order.orderStatus ?? order.status) }
const afterSalesLabels = {REQUESTED:'售后待审核',APPROVED:'待退款',AWAITING_RETURN:'待退货验收',RETURN_RECEIVED:'退货已验收',REFUNDED:'已退款',REJECTED:'售后申请已拒绝'}
function statusLabel(order) { if (order.fulfillmentStatus === 'PARTIALLY_SHIPPED') return '部分发货'; if (Number(order.refundedAmount)>0 && Number(order.refundedAmount)<Number(order.totalAmount)) return '部分退款'; return (order.afterSalesStatus !== 'REJECTED' ? afterSalesLabels[order.afterSalesStatus] : '') || statusOptions.find(option => option.value === orderStatus(order))?.label || '未知状态' }
function shippable(item, order) { return Math.max(0, Number(item.shippableQuantity ?? item.unshippedRefundAvailableQuantity ?? item.unshippedQuantity ?? (orderStatus(order) === 1 ? item.quantity : 0)) || 0) }
function canShip(order) { return hasCapability('FULFILMENT') && (Array.isArray(order.availableActions) ? order.availableActions.includes('SHIP') : orderStatus(order) === 1 && (order.items || []).some(item => shippable(item, order) > 0)) }
function statusTone(order) { return ({ 0: 'warning', 1: '', 2: 'success', 3: 'success', 4: 'info' })[orderStatus(order)] ?? 'info' }
function errorText(error, fallback) { return error instanceof Error ? `${fallback}：${error.message}` : fallback }

async function loadOrders() {
  if (ordersLoading.value) return
  ordersLoading.value = true
  ordersError.value = ''
  const requestedQuery = { ...query }; const currentShop = shopId.value
  try {
    const response = await listCommerceOrders(requestedQuery)
    if (!Array.isArray(response.data?.rows) || !Number.isFinite(Number(response.data?.total))) throw new Error('订单数据格式异常')
    if (currentShop !== shopId.value) return
    orders.value = response.data.rows
    total.value = Number(response.data.total)
  } catch (error) {
    ordersError.value = errorText(error, '订单未能刷新，请检查服务后重试。已有列表可能不是最新状态')
  } finally {
    ordersLoading.value = false
    if (currentShop !== shopId.value || JSON.stringify(requestedQuery) !== JSON.stringify(query)) await loadOrders()
  }
}
async function loadInventory() {
  if (inventoryLoading.value) return
  inventoryLoading.value = true
  inventoryError.value = ''; const currentShop = shopId.value
  try {
    const response = await listCommerceInventory()
    if (!Array.isArray(response.data)) throw new Error('库存数据格式异常')
    if (currentShop !== shopId.value) return
    inventory.value = response.data
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    inventoryError.value = errorText(error, '库存未能刷新，请检查服务后重试。已有数据可能不是最新库存')
  } finally { inventoryLoading.value = false; if (currentShop !== shopId.value) await loadInventory() }
}
async function refreshAll() {
  const reads = []
  if (workbenchSection.value === 'orders') reads.push(loadOrders(), afterSalesPanel.value?.refresh(), loadOperations().catch(() => { reconciliation.value = null }))
  if (workbenchSection.value === 'inventory') reads.push(loadInventory(), loadOperations().catch(() => { reconciliation.value = null }))
  if (workbenchSection.value === 'supply') reads.push(replenishmentPanel.value?.refresh(), agentTaskPanel.value?.refresh())
  if (workbenchSection.value === 'finance') reads.push(costPanel.value?.refresh())
  if (workbenchSection.value === 'activities') reads.push(loadInventory(), loadActivities().catch(() => { inventoryError.value = '活动数据读取失败，请刷新重试' }))
  await Promise.allSettled(reads)
}
function filterOrders() { query.pageNum = 1; loadOrders() }
async function showDetail(order) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  detailError.value = ''
  try { detail.value = (await getCommerceOrder(order.orderId)).data }
  catch (error) { detailError.value = errorText(error, '订单详情未能读取，请关闭后重试') }
  finally { detailLoading.value = false }
}
async function openShipment(order) {
  if (!canShip(order) || shippingId.value) return
  const shop = shopId.value
  shipmentOrder.value = order
  shipmentWarehouse.value = null
  resetShipmentQuantities()
  shipmentRequestKey = ''; shipmentFingerprint = ''
  shipmentForm.carrier = '演示物流'
  shipmentForm.trackingNo = ''
  shipmentError.value = ''
  shipmentVisible.value = true
  shipmentLoading.value = true; shipmentReady.value = false
  try {
    const response = await getCommerceOrder(order.orderId)
    if (shop === shopId.value && shipmentOrder.value?.orderId === order.orderId && shipmentVisible.value) {
      shipmentOrder.value = response.data
      shipmentReady.value = true
      resetShipmentQuantities()
    }
  } catch (error) { if (shop === shopId.value) shipmentError.value = errorText(error, '订单分配读取失败，请重新打开') }
  finally { shipmentLoading.value = false }
  nextTick(() => shipmentFormRef.value?.clearValidate())
}
function closeShipment(done) { if (!shippingId.value) done() }
async function submitShipment() {
  if (shippingId.value || shipmentLoading.value || !shipmentReady.value || !shipmentOrder.value || !canShip(shipmentOrder.value)) return
  const valid = await shipmentFormRef.value?.validate().catch(() => false)
  if (!valid) return
  const items = (shipmentOrder.value.items || []).map(item => ({ productId: String(item.productId), quantity: Math.min(warehouseShippable(item), Math.max(0, Number(shipmentQuantities[String(item.productId)]) || 0)) })).filter(item => item.quantity > 0)
  if (!items.length) { shipmentError.value = '请选择此次发货的商品数量'; return }
  const body = { items, warehouseId: shipmentWarehouse.value ?? undefined, carrier: shipmentForm.carrier.trim(), trackingNo: shipmentForm.trackingNo.trim() || undefined }
  const fingerprint = JSON.stringify(body)
  if (fingerprint !== shipmentFingerprint) { shipmentFingerprint = fingerprint; shipmentRequestKey = crypto.randomUUID() }
  shippingId.value = shipmentOrder.value.orderId
  shipmentError.value = ''
  try {
    await shipCommerceOrder(shippingId.value, { ...body, requestKey: shipmentRequestKey })
    shipmentVisible.value = false
    ElMessage.success('发货登记成功，库存与销售出库单已更新')
    await refreshAll()
  } catch (error) {
    shipmentError.value = errorText(error, '发货未能确认，请刷新订单后核实状态')
    await refreshAll()
  } finally { shippingId.value = '' }
}

function startSync() {
  if (syncTimer) return
  syncTimer = setInterval(() => { if (document.visibilityState === 'visible' && ['orders', 'inventory', 'activities'].includes(workbenchSection.value)) refreshAll() }, 30000)
}
function stopSync() { clearInterval(syncTimer); syncTimer = undefined }
onMounted(() => { setCommerceShop(shopId.value); loadShops().catch(() => {}); getCommerceContext().then(response => { tenantId.value = response.data.tenantId; userId.value = response.data.userId }).catch(() => {}); refreshAll(); startSync() })
onActivated(() => { refreshAll(); startSync() })
onDeactivated(stopSync)
onUnmounted(stopSync)
</script>

<style scoped>
.commerce-workbench { color: var(--sc-text); }
.commerce-heading, .commerce-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.commerce-shop-actions, .commerce-queue-states { display: flex; align-items: center; gap: 16px; }
.commerce-shop-actions :deep(.el-select) { width: 200px; }
.commerce-queue-states { flex-wrap: wrap; gap: 32px; color: var(--sc-muted); }
.commerce-queue-states strong { margin-left: 12px; color: var(--sc-text); font-size: 24px; }
.commerce-heading { margin-bottom: 22px; }
.commerce-navigation { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 22px; border-bottom: 1px solid var(--sc-border); }
.commerce-navigation button { padding: 12px 16px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--sc-muted); font: inherit; cursor: pointer; }
.commerce-navigation button[aria-pressed="true"] { color: var(--sc-text); font-weight: 600; border-bottom-color: var(--el-color-primary); }
.commerce-navigation button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: -2px; }
.commerce-heading h1 { margin: 0 0 8px; font-size: 25px; font-weight: 650; }
.commerce-heading p, .commerce-section-heading p { margin: 0; color: var(--sc-muted); line-height: 1.7; }
.commerce-sync-note { display: block; margin-top: 5px; color: var(--sc-muted); font-size: 12px; }
.commerce-sandbox { margin-bottom: 22px; }
.commerce-section { padding: 22px; margin-bottom: 22px; background: var(--sc-surface); border: 1px solid var(--sc-border); border-radius: 10px; }
.commerce-section-heading { margin-bottom: 18px; }
.commerce-section-heading h2 { margin: 0 0 7px; font-size: 18px; font-weight: 600; }
.commerce-section-heading :deep(.el-select) { width: 200px; }
.commerce-error { margin-bottom: 14px; }
.commerce-product { display: flex; align-items: center; gap: 12px; min-width: 0; }
.commerce-product img { width: 52px; height: 52px; object-fit: contain; flex: 0 0 52px; background: var(--sc-surface-soft); border: 1px solid var(--sc-border); border-radius: 6px; }
.commerce-product strong { display: block; font-size: 14px; font-weight: 550; line-height: 1.6; }
.commerce-product span { display: block; font-size: 12px; color: var(--sc-muted); }
.commerce-quantity-note { display:block; margin-top:5px; color:var(--sc-muted); font-size:12px; line-height:1.6; }
.commerce-order-items { padding: 6px 24px; }
.commerce-line-item { display: grid; grid-template-columns: minmax(240px, 1fr) auto 100px; gap: 22px; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--sc-border); }
.commerce-line-item:last-child { border-bottom: 0; }
.commerce-line-item > strong { text-align: right; font-variant-numeric: tabular-nums; }
.commerce-reserved { color: var(--sc-warning); font-weight: 600; }
.commerce-out { color: #c23d42; }
.commerce-updated, .commerce-record-note { color: var(--sc-muted); font-size: 12px; line-height: 1.7; }
.commerce-updated { margin: 14px 0 0; }
.commerce-shipment-form { margin-top: 20px; }
.commerce-dialog-order { margin-top: 0; overflow-wrap: anywhere; }
.commerce-details { min-height: 120px; }
:deep(.el-button.is-disabled) { color: var(--sc-muted); background: var(--sc-surface-soft); border-color: var(--sc-border); }
:deep(.el-descriptions__content) { overflow-wrap: anywhere; }
@media (max-width: 767px) {
  .commerce-heading, .commerce-section-heading { align-items: flex-start; flex-direction: column; gap: 12px; }
  .commerce-section { padding: 14px; }
  .commerce-line-item { grid-template-columns: minmax(0, 1fr) auto; gap: 10px; }
  .commerce-line-item .commerce-product { grid-column: 1 / -1; }
  .commerce-order-items { padding: 6px 10px; }
}
</style>
