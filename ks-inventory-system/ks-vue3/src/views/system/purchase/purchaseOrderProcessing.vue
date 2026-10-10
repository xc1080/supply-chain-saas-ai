<template>
  <!-- 采购订单制作 -->
  <div class="app-container" v-loading="detailLoading">
    <el-row :gutter="10">
      <el-col :span="24" :xs="24">
        <el-form
          :inline="true"
          label-width="100px"
          :model="form"
          ref="orderRef"
          :rules="rules"
          :disabled="isApprovedOrder || orderSubmitting || detailLoading"
        >
          <el-form-item label="系统单号" prop="systematicOrderForm" ref="ref1">
            <el-input
              class="form-item"
              v-model="form.systematicOrderForm"
              placeholder="系统单号"
              disabled="disabled"
            ></el-input>
          </el-form-item>
          <el-form-item label="原始单号" prop="originalOrderForm" ref="ref2">
            <el-input
              v-model="form.originalOrderForm"
              class="form-item"
              clearable
            />
          </el-form-item>
          <el-form-item label="订单类型" prop="orderFormType" ref="ref3">
            <el-radio-group class="form-item" v-model="form.orderFormType">
              <el-radio-button label="1" :value="1">采购订单</el-radio-button>
              <el-radio-button
                label="2"
                :value="2"
                @click="salesOrder"
                v-hasPermi="['sales:salesOrderProcessing:orderFormType']"
                >销售订单</el-radio-button
              >
            </el-radio-group>
          </el-form-item>
          <el-form-item label="订单状态" prop="orderFormStatus" ref="ref4">
            <el-radio-group class="form-item" v-model="form.orderFormStatus">
              <el-radio-button label="1" :value="1">待审核</el-radio-button>
              <el-radio-button label="2" :value="2" :disabled="takeEffect"
                >已审核</el-radio-button
              >
            </el-radio-group>
          </el-form-item>
          <el-form-item label="订货日期" prop="orderDate" ref="ref24">
            <el-date-picker
              style="width: 200px"
              v-model="form.orderDate"
              type="date"
              placeholder="请选择订货日期"
              format="YYYY/MM/DD"
              value-format="YYYY-MM-DD"
            ></el-date-picker>
          </el-form-item>
          <el-form-item label="交货日期" prop="deliveryDate" ref="ref5">
            <el-date-picker
              style="width: 200px"
              v-model="form.deliveryDate"
              type="date"
              placeholder="请选择交货日期"
              format="YYYY/MM/DD"
              value-format="YYYY-MM-DD"
            ></el-date-picker>
          </el-form-item>
          <el-form-item label="仓库" prop="warehousingIds" ref="ref6">
            <el-select
              class="form-item"
              v-model="form.warehousingIds"
              placeholder="请输入仓库"
              filterable
              clearable
              remote
              :remote-method="remoteWarehouse"
              :loading="loading"
              remote-show-suffix
            >
              <el-option
                v-for="item in warehouseOptions"
                :key="item.warehouseId"
                :label="item.warehouseName"
                :value="item.warehouseId"
              ></el-option>
            </el-select>
          </el-form-item>
          <el-form-item
            label="经手人"
            prop="userIds"
            v-hasPermi="['purchase:purchaseOrderProcessing:selectUser']"
            ref="ref7"
          >
            <el-select
              class="form-item"
              v-model="form.userIds"
              placeholder="请选择"
              filterable
              clearable
            >
              <el-option
                v-for="item in userOptions"
                :key="item.userId"
                :label="item.userName"
                :value="item.userId"
              ></el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="供应商" prop="supplierIds" ref="ref8">
            <el-select
              class="form-item"
              v-model="form.supplierIds"
              placeholder="请输入供应商"
              filterable
              clearable
              remote
              :remote-method="remoteSupplier"
              :loading="loading"
              remote-show-suffix
            >
              <el-option
                v-for="item in supplierOptions"
                :key="item.supplierId"
                :label="item.supplierName"
                :value="item.supplierId"
              ></el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="备注" prop="orderFormNotes" ref="ref9">
            <el-input
              v-model="form.orderFormNotes"
              class="form-item"
              type="textarea"
            />
          </el-form-item>
          <el-form-item label="定金" prop="deposit" ref="ref10">
            <el-input
              v-model="form.deposit"
              class="form-item"
              clearable
              oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2.$3')"
            />
          </el-form-item>
          <el-form-item label="总金额" prop="orderFormAmount" ref="ref25">
            <el-input
              v-model="form.orderFormAmount"
              class="form-item"
              disabled="disabled"
              :formatter="
                (value) => `￥ ${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
              "
              :parser="(value) => value.replace(/\￥\s?|(,*)/g, '')"
            />
          </el-form-item>
          <el-form-item
            label="总金额(大写)"
            prop="orderCapitalizeAmount"
            ref="ref11"
          >
            <el-input
              v-model="form.orderCapitalizeAmount"
              class="form-item"
              disabled="disabled"
            />
          </el-form-item>
          <div v-if="finding">
            <el-divider />
            <el-form-item
              label="审核结果"
              prop="findingOfAudit"
              :disabled="['purchase:purchaseOrderProcessing:takeEffect']"
              ref="ref13"
            >
              <el-select
                v-model="form.findingOfAudit"
                filterable
                clearable
                class="form-item"
              >
                <el-option
                  v-for="dict in finding_of_audit"
                  :key="dict.value"
                  :label="dict.label"
                  :value="dict.value"
                />
              </el-select>
            </el-form-item>
            <el-form-item
              label="审核意见"
              prop="reviewComments"
              :disabled="['purchase:purchaseOrderProcessing:takeEffect']"
              ref="ref14"
            >
              <el-input
                v-model="form.reviewComments"
                clearable
                style="width: 530px"
              />
            </el-form-item>
          </div>
        </el-form>
        <section v-if="Number(form.orderFormStatus) === 2" class="purchase-progress">
          <div class="purchase-progress-header">
            <span>收货进度</span>
            <div>
              <el-button :loading="progressLoading" @click="loadProgress">刷新</el-button>
              <el-button type="primary" :disabled="!canReceive || progressLoading" @click="openProcurementDialog('receipt')" v-hasPermi="['purchase:purchaseReceiptProcessing:save']">分批收货</el-button>
              <el-button @click="openProcurementDialog('return')" v-hasPermi="['purchase:purchaseReceiptProcessing:save']">退给供应商</el-button>
            </div>
          </div>
          <el-table :data="progressLines" v-loading="progressLoading" border row-key="purchaseLineId">
            <el-table-column label="货品" min-width="240"><template #default="{ row }"><ProductIdentity :product="progressProduct(row)" /></template></el-table-column>
            <el-table-column label="订购" prop="quantity" width="85" align="right" />
            <el-table-column label="收货草稿" prop="draftReceived" width="100" align="right" />
            <el-table-column label="已收货" prop="receivedQuantity" width="95" align="right" />
            <el-table-column label="退供草稿" prop="draftReturned" width="100" align="right" />
            <el-table-column label="已退供" prop="returnedQuantity" width="95" align="right" />
            <el-table-column label="净收货" prop="netReceived" width="95" align="right" />
            <el-table-column label="还可收货" prop="remainingToReceive" width="110" align="right" />
          </el-table>
        </section>
        <div>
          <div>
            <el-row :gutter="10">
              <el-col :span="1.5">
                <div>货品明细</div>
              </el-col>
            </el-row>
          </div>
          <div>
            <el-table
              border
              :data="form.details"
              show-summary
              :summary-method="getSummaries"
              ref="ref18"
            >
              <el-table-column
                label="序号"
                align="center"
                type="index"
                width="60"
              />
              <el-table-column
                label="货品编号"
                align="center"
                prop="productCode"
                width="180"
              >
                <template #default="scope">
                  <el-select
                    v-model="scope.row.productCode"
                    :disabled="isApprovedOrder || orderSubmitting"
                    placeholder="请输入货品编号"
                    @change="changeProduct(scope.$index, scope.row)"
                    filterable
                    remote
                    :remote-method="remoteProductCode"
                    :loading="loading"
                    remote-show-suffix
                  >
                    <el-option
                      v-for="item in productOptions"
                      :key="item.productId"
                      :label="item.productCode"
                      :value="item.productId"
                    ></el-option>
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column
                label="货品名称"
                align="center"
                prop="productName"
                width="300"
              >
                <template #default="scope">
                  <ProductIdentity :product="scope.row">
                  <el-select
                    v-model="scope.row.productName"
                    :disabled="isApprovedOrder || orderSubmitting"
                    placeholder="请输入货品名称"
                    @change="changeProduct(scope.$index, scope.row)"
                    filterable
                    remote
                    :remote-method="remoteProductName"
                    :loading="loading"
                    remote-show-suffix
                  >
                    <el-option
                      v-for="item in productOptions"
                      :key="item.productId"
                      :label="item.productName"
                      :value="item.productId"
                    ></el-option>
                  </el-select>
                </ProductIdentity>
                </template>
              </el-table-column>
              <el-table-column
                label="类型"
                align="center"
                prop="productTypeName"
              />
              <el-table-column
                label="规格"
                align="center"
                prop="productSpecifications"
                width="100"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.productSpecifications"
                    :disabled="isApprovedOrder || orderSubmitting"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column label="单位" align="center" prop="measureUnit">
                <template #default="scope">
                  <el-input v-model="scope.row.measureUnit" :disabled="isApprovedOrder || orderSubmitting"></el-input>
                </template>
              </el-table-column>
              <el-table-column label="产地" align="center" prop="producer" />
              <el-table-column
                label="库存数量"
                align="center"
                prop="inventoryQty"
              />
              <el-table-column
                label="数量"
                align="center"
                prop="planQuantity"
                width="100"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.planQuantity"
                    :disabled="isApprovedOrder || orderSubmitting"
                    @change="calculateDetails(scope.row)"
                    oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2')"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column
                label="单价"
                align="center"
                prop="univalence"
                width="150"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.univalence"
                    :disabled="isApprovedOrder || orderSubmitting"
                    @change="calculateDetails(scope.row)"
                    oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2.$3')"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column
                label="折扣"
                align="center"
                prop="discount"
                width="80"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.discount"
                    :disabled="isApprovedOrder || orderSubmitting"
                    @change="calculateDetails(scope.row)"
                    oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2.$3')"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column
                label="金额"
                align="center"
                prop="money"
                width="150"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.money"
                    :disabled="isApprovedOrder || orderSubmitting"
                    oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2.$3')"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column
                label="成本"
                align="center"
                prop="cost"
                width="150"
              >
                <template #default="scope">
                  <el-input
                    v-model="scope.row.cost"
                    :disabled="isApprovedOrder || orderSubmitting"
                    oninput="value=value.replace(/[^0-9.]/g,'').replace(/\.{2,}/g,'.').replace(/^(\-)*(\d+)\.(\d\d).*$/,'$1$2.$3')"
                  ></el-input>
                </template>
              </el-table-column>
              <el-table-column label="备注" align="center" width="180">
                <template #default="scope">
                  <el-input v-model="scope.row.remarks" :disabled="isApprovedOrder || orderSubmitting"></el-input>
                </template>
              </el-table-column>
              <el-table-column fixed="right" label="操作" align="center">
                <template #default="scope">
                  <el-button
                    type="danger"
                    plain
                    round
                    @click="form.details.splice(scope.$index, 1)"
                    :disabled="isApprovedOrder || orderSubmitting"
                    >删除</el-button
                  >
                </template>
              </el-table-column>
            </el-table>
          </div>
          <el-affix class="footer" position="bottom" :offset="20">
            <el-button
              color="#BB5500"
              icon="Plus"
              round
              @click="addOrder"
              v-hasPermi="['purchase:purchaseOrderProcessing:add']"
              >新增订单</el-button
            >
            <el-button
              color="#00AAAA"
              icon="CirclePlus"
              round
              @click="addItem"
              :disabled="isApprovedOrder || orderSubmitting"
              v-hasPermi="['purchase:purchaseOrderProcessing:detail']"
              >添加明细</el-button
            >
            <el-button
              type="success"
              icon="Upload"
              round
              @click="supplierAdd"
              v-hasPermi="['baseDate:supplier:add']"
              >新增供应商</el-button
            >
            <el-button
              type="warning"
              icon="Upload"
              round
              @click="handleAdd"
              v-hasPermi="['baseDate:product:add']"
              >新增货品</el-button
            >
            <el-button
              @click="submitForm"
              :loading="orderSubmitting"
              :disabled="isApprovedOrder"
              type="primary"
              icon="position"
              round
              v-hasPermi="['purchase:purchaseOrderProcessing:save']"
              ref="ref19"
              >保存订单</el-button
            >
            <el-button
              @click="takeEffectForm"
              type="success"
              icon="Check"
              round
              :disabled="takeEffectBtn || orderSubmitting"
              v-hasPermi="['purchase:purchaseOrderProcessing:takeEffect']"
              ref="ref20"
              >审核订单</el-button
            >
            <el-button v-if="isApprovedOrder" type="warning" round :loading="orderSubmitting" :disabled="progressLoading || hasReceiptProgress" @click="reversePurchaseApproval" v-hasPermi="['purchase:purchaseOrderProcessing:takeEffect']">反审核</el-button>
            <el-button
              @click="printOut"
              color="#626aef"
              icon="Printer"
              round
              :disabled="printBtn"
              v-hasPermi="['purchase:purchaseOrderProcessing:printOut']"
              ref="ref21"
              >打印订单</el-button
            >
            <el-tooltip
              class="box-item"
              content="删除订单，重新制作"
              placement="top"
            >
              <el-button
                @click="deleteOrder"
                type="danger"
                icon="delete"
                round
                :disabled="printBtn"
                v-hasPermi="['purchase:purchaseOrderProcessing:delete']"
                ref="ref22"
                >删除订单</el-button
              >
            </el-tooltip>
            <el-tooltip
              class="box-item"
              content="取消制作，返回查询"
              placement="top"
            >
              <el-button
                type="info"
                round
                icon="close"
                @click="cancel"
                ref="ref23"
                >取消制作</el-button
              >
            </el-tooltip>
          </el-affix>
        </div>
      </el-col>
    </el-row>
  </div>

  <el-dialog v-model="procurementDialogOpen" :title="procurementKind === 'receipt' ? '创建分批收货单' : '创建退供单'" width="min(860px, calc(100vw - 32px))" top="8vh" append-to-body :close-on-click-modal="false" :close-on-press-escape="!procurementSubmitting" :show-close="!procurementSubmitting">
    <el-table :data="procurementRows" row-key="sourceKey" @selection-change="rows => procurementSelection = rows" ref="procurementTable" border>
      <el-table-column type="selection" width="45" :selectable="row => row.limit > 0 && !procurementPending" :reserve-selection="true" />
      <el-table-column label="货品" min-width="220"><template #default="{ row }"><ProductIdentity :product="progressProduct(row)" /></template></el-table-column>
      <el-table-column v-if="procurementKind === 'return'" label="原入库单" prop="sourceReceiptId" min-width="160" />
      <el-table-column label="仓库" width="110"><template #default="{ row }">{{ warehouseLabel(row.warehouseId) }}</template></el-table-column>
      <el-table-column label="来源单价" width="110" align="right"><template #default="{ row }">¥{{ Number(row.unitPrice).toFixed(2) }}</template></el-table-column>
      <el-table-column label="折扣" prop="discount" width="75" align="right" />
      <el-table-column :label="procurementKind === 'receipt' ? '还可收货' : '可退数量'" prop="limit" width="100" align="right" />
      <el-table-column :label="procurementKind === 'receipt' ? '本次收货' : '本次退供'" width="175">
        <template #default="{ row }"><el-input-number v-model="row.selectedQuantity" :min="1" :max="Math.max(1, row.limit)" :precision="0" :step="1" step-strictly controls-position="right" :disabled="row.limit <= 0 || procurementSubmitting || Boolean(procurementPending)" style="width: 150px" /></template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button :disabled="procurementSubmitting" @click="procurementDialogOpen = false">取消</el-button>
      <el-button type="primary" :loading="procurementSubmitting" :disabled="!procurementSelection.length && !procurementPending" @click="submitProcurementDraft">{{ procurementPending ? '重试创建' : '创建草稿' }}</el-button>
    </template>
  </el-dialog>

  <!-- 添加供应商配置对话框 -->
  <el-dialog
    :title="titleSupplier"
    v-model="openSupplier"
    width="700px"
    append-to-body
  >
    <el-form
      :model="supplierForm"
      :rules="rules"
      ref="supplierRef"
      label-width="100px"
    >
      <el-row>
        <el-col :span="12">
          <el-form-item label="供应商编号" prop="supplierCode">
            <el-input
              v-model="supplierForm.supplierCode"
              placeholder="请输入供应商编号"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="供应商名称" prop="supplierName">
            <el-input
              v-model="supplierForm.supplierName"
              placeholder="请输入供应商名称"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="业务员" prop="supplierSalesPerson">
            <el-input
              v-model="supplierForm.supplierSalesPerson"
              placeholder="请输入业务员"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="联系人" prop="supplierContacts">
            <el-input
              v-model="supplierForm.supplierContacts"
              placeholder="请输入联系人"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="联系电话" prop="supplierContactNumber">
            <el-input
              v-model="supplierForm.supplierContactNumber"
              placeholder="请输入联系电话"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="邮编" prop="supplierPostalCode">
            <el-input
              v-model="supplierForm.supplierPostalCode"
              placeholder="请输入邮编"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="期初欠款" prop="supplierOpeningDebt">
            <el-input
              v-model="supplierForm.supplierOpeningDebt"
              placeholder="请输入期初欠款"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="开户银行" prop="supplierOpeningBank">
            <el-input
              v-model="supplierForm.supplierOpeningBank"
              placeholder="请输入开户银行"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="银行账号" prop="supplierAccountNumber">
            <el-input
              v-model="supplierForm.supplierAccountNumber"
              placeholder="请输入银行账号"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="纳税号" prop="supplierTaxId">
            <el-input
              v-model="supplierForm.supplierTaxId"
              placeholder="请输入纳税号"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="公司主页" prop="supplierCompanyHomepage">
            <el-input
              v-model="supplierForm.supplierCompanyHomepage"
              placeholder="请输入公司主页"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="电子邮箱" prop="supplierEmail">
            <el-input
              v-model="supplierForm.supplierEmail"
              placeholder="请输入电子邮箱"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="公司法人" prop="supplierLegalPerson">
            <el-input
              v-model="supplierForm.supplierLegalPerson"
              placeholder="请输入公司法人"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="状态" prop="status">
            <el-select
              v-model="supplierForm.status"
              placeholder="请选择"
              clearable
            >
              <el-option
                v-for="dict in sys_normal_disable"
                :key="dict.value"
                :label="dict.label"
                :value="dict.value"
              />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="24">
          <el-form-item label="单位地址" prop="supplierWorkAddress">
            <el-input
              v-model="supplierForm.supplierWorkAddress"
              placeholder="请输入单位地址"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="24">
          <el-form-item label="备注">
            <el-input
              v-model="supplierForm.remarks"
              type="textarea"
              placeholder="请输入备注"
            />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button type="primary" @click="submitSupplierForm">确 定</el-button>
        <el-button @click="cancelSupplierForm">取 消</el-button>
      </div>
    </template>
  </el-dialog>

  <!-- 添加货品配置对话框 -->
  <el-dialog :title="title" v-model="open" width="700px" append-to-body>
    <el-form
      :model="productForm"
      :rules="rules"
      ref="productRef"
      label-width="80px"
    >
      <el-row>
        <el-col :span="12">
          <el-form-item label="货品编号" prop="productCode">
            <el-input
              v-model="productForm.productCode"
              placeholder="请输入货品编号"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="货品名称" prop="productName">
            <el-input
              v-model="productForm.productName"
              placeholder="请输入货品名称"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="货品类型" prop="productType">
            <el-tree-select
              v-model="productForm.productType"
              :data="typeOptions"
              :props="{ value: 'id', label: 'label', children: 'children' }"
              value-key="id"
              placeholder="请选择货品类型"
              check-strictly
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="货品规格" prop="productSpecifications">
            <el-input
              v-model="productForm.productSpecifications"
              placeholder="请输入货品规格"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="计量单位" prop="measureUnit">
            <el-input
              v-model="productForm.measureUnit"
              placeholder="请输入计量单位"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="货品条码" prop="productBarcode">
            <el-input
              v-model="productForm.productBarcode"
              placeholder="请输入货品条码"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="24">
          <el-form-item label="产地" prop="producer">
            <el-input
              v-model="productForm.producer"
              placeholder="请输入产地"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="成本价" prop="costPrice">
            <el-input
              v-model="productForm.costPrice"
              placeholder="请输入成本价"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="参考售价" prop="univalence">
            <el-input
              v-model="productForm.univalence"
              placeholder="请输入参考售价"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="销售折扣" prop="discount">
            <el-input
              v-model="form.discount"
              placeholder="请输入销售折扣"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="库存数量" prop="costPrice">
            <el-input
              v-model="form.inventoryQty"
              placeholder="系统自动计算"
              disabled
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="库存上限" prop="upperLimit">
            <el-input
              v-model="productForm.upperLimit"
              placeholder="请输入库存上限"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="库存下限" prop="lowerLimit">
            <el-input
              v-model="productForm.lowerLimit"
              placeholder="请输入库存下限"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="12">
          <el-form-item label="默认仓库" prop="defaultWarehouse">
            <el-input
              v-model="productForm.defaultWarehouse"
              placeholder="请输入默认仓库"
              maxlength="30"
            />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="状态">
            <el-radio-group v-model="productForm.status">
              <el-radio
                v-for="dict in sys_normal_disable"
                :key="dict.value"
                :label="dict.value"
                >{{ dict.label }}</el-radio
              >
            </el-radio-group>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row>
        <el-col :span="24">
          <el-form-item label="备注">
            <el-input
              v-model="productForm.notes"
              type="textarea"
              placeholder="请输入内容"
            ></el-input>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button type="primary" @click="submitProductForm">确 定</el-button>
        <el-button @click="cancelProductForm">取 消</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup name="purchaseOrderProcessing">
import { getToken } from "@/utils/auth";
import { useRouter } from "vue-router";
import { listUser, getUserProfile } from "@/api/system/user";
import { listWarehouse } from "@/api/basedate/warehouse";
import { listSupplier, addSupplier } from "@/api/basedate/supplier";
import {
  addProduct,
  listProduct,
  productTypeTreeSelect,
} from "@/api/basedate/product";
import {
  getPurchaseOrder,
  savePurchaseOrder,
  delPurchaseOrder,
  getPurchaseProgress,
  createPurchaseReceipt,
  getPurchaseReceiptSources,
  createPurchaseReturn,
} from "@/api/purchase/purchaseOrderProcessing";
import { viewUrl } from "@/api/jimu/jiMuReport";
import { randomId } from "@/utils/RandomUtils";
import RMBConverter from "@/utils/RMBConverter";
import { ref, computed, nextTick, watch } from "vue";

const { proxy } = getCurrentInstance();
const { finding_of_audit } = proxy.useDict("finding_of_audit");
const { print_selected_files } = proxy.useDict("print_selected_files");
const { sys_normal_disable } = proxy.useDict("sys_normal_disable");
const { print_selected_sizes } = proxy.useDict("print_selected_sizes");

// 订单状态下拉框
const takeEffect = ref(false);
const takeEffectBtn = ref(false);
const finding = ref(false);
const loading = ref(false);
const router = useRouter();
const printBtn = ref(false);
const openUrl = ref("");
const open = ref(false);
const title = ref("");
const openSupplier = ref(false);
const titleSupplier = ref("");
const progressLines = ref([]);
const progressLoading = ref(false);
const orderSubmitting = ref(false);
const procurementDialogOpen = ref(false);
const procurementKind = ref("receipt");
const procurementRows = ref([]);
const procurementSelection = ref([]);
const procurementSubmitting = ref(false);
const procurementTable = ref();
const procurementRequestKeys = { receipt: null, return: null };
const pendingDrafts = reactive({ receipt: null, return: null });
const procurementPending = computed(() => pendingDrafts[procurementKind.value]);
const canReceive = computed(() => progressLines.value.some(line => Number(line.remainingToReceive) > 0));
const isApprovedOrder = computed(() => Number(form.value.orderFormStatus) === 2);
const hasReceiptProgress = computed(() => progressLines.value.some(line => Number(line.draftReceived) > 0 || Number(line.receivedQuantity) > 0 || Number(line.draftReturned) > 0 || Number(line.returnedQuantity) > 0));

const data = reactive({
  userOptions: undefined,
  supplierOptions: undefined,
  warehouseOptions: undefined,
  productOptions: undefined,
  typeOptions: undefined,
  option: {
    warehouseId: undefined,
    warehouseName: undefined,
    supplierId: undefined,
    supplierName: undefined,
    customerId: undefined,
    customerName: undefined,
    productCode: undefined,
    productName: undefined,
    pageSize: 50,
  },
  supplierForm: {
    supplierCode: undefined,
    supplierName: undefined,
    supplierSalesPerson: undefined,
    supplierContacts: undefined,
    supplierContactNumber: undefined,
    supplierWorkAddress: undefined,
    supplierPostalCode: undefined,
    supplierOpeningDebt: undefined,
    supplierOpeningBank: undefined,
    supplierAccountNumber: undefined,
    supplierTaxId: undefined,
    supplierCompanyHomepage: undefined,
    supplierEmail: undefined,
    supplierLegalPerson: undefined,
    status: undefined,
    remarks: undefined,
  },
  productForm: {
    productCode: undefined,
    productName: undefined,
    productType: undefined,
    productSpecifications: undefined,
    measureUnit: undefined,
    producer: undefined,
    costPrice: undefined,
    univalence: undefined,
    productBarcode: undefined,
    upperLimit: undefined,
    lowerLimit: undefined,
    defaultWarehouse: undefined,
    notes: undefined,
    status: undefined,
  },
  // 表单参数
  form: {
    details: [],
    systematicOrderForm: null,
    originalOrderForm: null,
    orderFormType: 1,
    orderFormStatus: null,
    warehousingIds: null,
    supplierIds: null,
    userIds: null,
    orderDate: null,
    deliveryDate: null,
    planReceipt: null,
    orderFormNotes: null,
    deposit: null,
    orderFormAmount: null,
    orderCapitalizeAmount: null,
    afterSalesInstallation: null,
    findingOfAudit: null,
    reviewComments: null,
    isWarehousing: null,
    // 采购入库单
    systematicReceipt: null,
    originalReceipt: null,
    receiptType: 1,
    receiptCategory: 1,
    receiptStatus: 1,
    invoiceDate: null,
    receiptNotes: null,
    totalAmount: null,
    capitalizeTotalAmount: null,
    // 打印ID
    printId: null,
    printSize: undefined,
  },
  // 表单校验
  rules: {
    warehousingIds: [
      { required: true, message: "仓库不能为空", trigger: "blur" },
    ],
    userIds: [{ required: true, message: "经手人不能为空", trigger: "blur" }],
    supplierIds: [
      { required: true, message: "供应商不能为空", trigger: "blur" },
    ],
    supplierCode: [
      { required: true, message: "供应商编号不能为空", trigger: "blur" },
      { max: 30, message: "供应商编号长度不能大于 30 ", trigger: "blur" },
    ],
    supplierName: [
      { required: true, message: "供应商名称不能为空", trigger: "blur" },
      { max: 30, message: "供应商名称长度不能大于 30 ", trigger: "blur" },
    ],
    status: [{ required: true, message: "状态不能为空", trigger: "blur" }],
    supplierEmail: [
      {
        type: "email",
        message: "请输入正确的邮箱地址",
        trigger: ["blur", "change"],
      },
    ],
    supplierContactNumber: [
      {
        pattern: /^1[3|4|5|6|7|8|9][0-9]\d{8}$/,
        message: "请输入正确的手机号码",
        trigger: "blur",
      },
    ],
    productCode: [
      { required: true, message: "货品编号不能为空", trigger: "blur" },
    ],
    productName: [
      { required: true, message: "货品名称不能为空", trigger: "blur" },
    ],
    productType: [
      { required: true, message: "货品类型不能为空", trigger: "blur" },
    ],
    measureUnit: [
      { required: true, message: "计量单位不能为空", trigger: "blur" },
    ],
  },
});

const {
  userOptions,
  supplierOptions,
  warehouseOptions,
  productOptions,
  typeOptions,
  option,
  supplierForm,
  productForm,
  form,
  rules,
} = toRefs(data);

const detailLoading = ref(false);
let detailLoadVersion = 0;
function currentOrder(id, version = detailLoadVersion) {
  return version === detailLoadVersion && proxy.$route.path === '/purchase/purchaseOrderProcessing'
    && String(proxy.$route.query.systematicOrderForm || form.value.systematicOrderForm || '') === String(id || '');
}
function canSubmitOrder() {
  return !detailLoading.value && currentOrder(form.value.systematicOrderForm);
}
function initialization() {
  detailLoadVersion++;
  detailLoading.value = false;
  procurementDialogOpen.value = false;
  progressLines.value = [];
  const { systematicOrderForm } = proxy.$route.query;
  if (systematicOrderForm) {
    loadDetail(systematicOrderForm);
  } else {
    reset();
    takeEffect.value = true;
    takeEffectBtn.value = true;
    printBtn.value = true;
  }
}
function getNowTime() {
  var now = new Date();
  var year = now.getFullYear(); //得到年份
  var month = now.getMonth(); //得到月份
  var date = now.getDate(); //得到日期
  month = month + 1;
  month = month.toString().padStart(2, "0");
  date = date.toString().padStart(2, "0");
  var defaultDate = `${year}-${month}-${date}`;
  return defaultDate;
}
async function getList() {
  option.value.pageSize = 500;
  listUser(option.value).then((response) => {
    userOptions.value = response.rows;
  });
  getUserProfile().then((response) => {
    if (!proxy.$route.query.systematicOrderForm) form.value.userIds = response.data.userId;
  });
  optionReset();
}
/** 跳转销售订单制作按钮操作 */
function salesOrder() {
  router.push({ path: "/sales/salesOrderProcessing" });
}
/** 新增按钮操作 */
async function addOrder() {
  await router.push({ path: "/index" });
  router.push({ path: "/purchase/purchaseOrderProcessing" });
}
/** 统计金额和成本 */
function calculateDetails(row) {
  row.money = row.cost = Number(
    Math.abs(row.planQuantity) * row.univalence * row.discount
  ).toFixed(2);
}
/** 取消按钮 */
function cancel() {
  router.push({ path: "/purchase/purchaseOrderQuery" });
}
/** 提交按钮 */
function submitForm() {
  if (!canSubmitOrder() || isApprovedOrder.value || orderSubmitting.value) return;
  proxy.$refs["orderRef"].validate(async (valid) => {
    if (form.value.details.length === 0) {
      proxy.$modal.msgError("货品明细不能为空");
      return;
    } else if (!valid) {
      return;
    }
    if (!canSubmitOrder() || orderSubmitting.value) return;
    orderSubmitting.value = true;
    try {
      await savePurchaseOrder({ ...form.value, details: orderDetails() });
      proxy.$modal.msgSuccess("采购订单保存成功");
      cancel();
    } finally {
      orderSubmitting.value = false;
    }
  });
}
/** 审核按钮 */
function takeEffectForm() {
  if (!canSubmitOrder() || isApprovedOrder.value || orderSubmitting.value) return;
  proxy.$refs["orderRef"].validate(async (valid) => {
    if (form.value.details.length == 0) {
      proxy.$modal.msgError("货品明细不能为空");
      return;
    } else if (!valid) {
      return;
    }
    if (!canSubmitOrder() || orderSubmitting.value) return;
    orderSubmitting.value = true;
    try {
      await savePurchaseOrder({ ...form.value, orderFormStatus: 2, details: orderDetails() });
      form.value.orderFormStatus = 2;
      proxy.$modal.msgSuccess("采购订单审核成功");
      loadDetail(form.value.systematicOrderForm);
    } finally {
      orderSubmitting.value = false;
    }
  });
}

function orderDetails() {
  return form.value.details.map(it => ({
    systematicOrderForm: form.value.systematicOrderForm,
    purchaseLineId: it.purchaseLineId,
    systematicId: it.systematicId,
    productId: it.productId,
    warehousingId: isApprovedOrder.value ? (it.warehousingId || form.value.warehousingIds) : form.value.warehousingIds,
    retrievalId: isApprovedOrder.value ? (it.retrievalId || form.value.warehousingIds) : form.value.warehousingIds,
    supplierId: isApprovedOrder.value ? (it.supplierId || form.value.supplierIds) : form.value.supplierIds,
    productSpecifications: it.productSpecifications,
    measureUnit: it.measureUnit,
    planQuantity: it.planQuantity,
    univalence: it.univalence,
    discount: it.discount,
    money: it.money,
    cost: it.cost,
    remarks: it.remarks,
  }));
}

async function reversePurchaseApproval() {
  if (!canSubmitOrder() || !isApprovedOrder.value || hasReceiptProgress.value || orderSubmitting.value) return;
  try {
    await proxy.$modal.confirm('确认反审核当前采购订单？');
  } catch {
    return;
  }
  orderSubmitting.value = true;
  try {
    await savePurchaseOrder({ ...form.value, orderFormStatus: 1, details: orderDetails() });
    form.value.orderFormStatus = 1;
    progressLines.value = [];
    proxy.$modal.msgSuccess('采购订单已反审核');
    loadDetail(form.value.systematicOrderForm);
  } finally {
    orderSubmitting.value = false;
  }
}
function loadDetail(systematicOrderForm) {
  const version = ++detailLoadVersion;
  detailLoading.value = true;
  getPurchaseOrder(systematicOrderForm).then((response) => {
    if (!currentOrder(systematicOrderForm, version) || !response.data) return;
    option.value.warehouseId = response.data.warehousingIds;
    listWarehouse(option.value).then((response) => {
      if (!currentOrder(systematicOrderForm, version)) return;
      warehouseOptions.value = response.rows;
    });
    option.value.supplierId = response.data.supplierIds;
    listSupplier(option.value).then((response) => {
      if (!currentOrder(systematicOrderForm, version)) return;
      supplierOptions.value = response.rows;
    });
    optionReset();
    takeEffect.value = Number(response.data.orderFormStatus) === 1;
    takeEffectBtn.value = Number(response.data.orderFormStatus) === 2;
    printBtn.value = false;
    finding.value = true;
    const details = response.data.details || [];
    const product = response.data.product;
    const map = {};
    (product || []).forEach((it) => {
      map[it.productId] = it;
    });
    form.value = {
      ...response.data,
      // 采购入库单
      systematicReceipt: response.data.systematicOrderForm,
      originalReceipt: response.data.originalOrderForm,
      receiptType: 1,
      receiptCategory: 1,
      receiptStatus: 1,
      invoiceDate: response.data.deliveryDate,
      receiptNotes: response.data.orderFormNotes,
      totalAmount: response.data.orderFormAmount,
      capitalizeTotalAmount: response.data.orderCapitalizeAmount,
      details: details.map(line => ({ ...map[line.productId], ...line })),
    };
    form.value.warehousingIds = Number(response.data.warehousingIds);
    form.value.userIds = Number(response.data.userIds);
    form.value.supplierIds = Number(response.data.supplierIds);
    if (Number(form.value.orderFormStatus) === 2) {
      loadProgress();
    } else {
      progressLines.value = [];
    }
  }).finally(() => { if (version === detailLoadVersion) detailLoading.value = false; });
}

async function loadProgress() {
  const orderId = form.value.systematicOrderForm, version = detailLoadVersion;
  if (!currentOrder(orderId, version)) return false;
  progressLoading.value = true;
  try {
    const response = await getPurchaseProgress(orderId);
    if (!currentOrder(orderId, version)) return false;
    progressLines.value = (response.data.lines || []).map(line => ({ ...line,
      draftReceived: line.draftReceived ?? line.draftReceiptQuantity ?? 0,
      draftReturned: line.draftReturned ?? line.draftReturnQuantity ?? 0,
      netReceived: line.netReceived ?? (Number(line.receivedQuantity) - Number(line.returnedQuantity)),
    }));
    return true;
  } catch {
    return false;
  } finally {
    if (version === detailLoadVersion) progressLoading.value = false;
  }
}

function progressProduct(line) {
  const detail = form.value.details.find(item => item.purchaseLineId === (line.purchaseLineId || line.sourcePurchaseLineId))
    || form.value.details.find(item => item.productId === line.productId);
  return { ...detail, ...line };
}

function warehouseLabel(warehouseId) {
  return warehouseOptions.value?.find(item => String(item.warehouseId) === String(warehouseId))?.warehouseName || warehouseId || '—';
}

async function openProcurementDialog(kind) {
  if (!canSubmitOrder()) return;
  const orderId = form.value.systematicOrderForm, version = detailLoadVersion;
  if (pendingDrafts[kind] && pendingDrafts[kind].orderId !== orderId) {
    pendingDrafts[kind] = null;
    procurementRequestKeys[kind] = null;
  }
  procurementKind.value = kind;
  if (pendingDrafts[kind]) {
    procurementRows.value = pendingDrafts[kind].rows;
    procurementSelection.value = pendingDrafts[kind].selection;
  } else {
    let lines;
    if (kind === 'receipt') {
      if (!await loadProgress()) return;
      lines = progressLines.value;
    } else {
      const response = await getPurchaseReceiptSources(orderId);
      if (!currentOrder(orderId, version)) return;
      lines = response.data || [];
    }
    procurementRows.value = lines.map(line => {
      const limit = Number(kind === 'receipt' ? line.remainingToReceive : line.availableReturnQuantity);
      return { ...line, sourceKey: kind === 'receipt' ? line.purchaseLineId : line.sourceReceiptLineId,
        limit, selectedQuantity: limit > 0 ? limit : 1 };
    });
    procurementSelection.value = [];
  }
  procurementDialogOpen.value = true;
  await nextTick();
  procurementTable.value?.clearSelection();
  if (pendingDrafts[kind]) {
    pendingDrafts[kind].selection.forEach(row => procurementTable.value?.toggleRowSelection(row, true));
  }
}

async function submitProcurementDraft() {
  if (!canSubmitOrder() || procurementSubmitting.value) return;
  const kind = procurementKind.value;
  if (!pendingDrafts[kind]) {
    if (!procurementSelection.value.length) return;
    if (procurementSelection.value.some(row => !Number.isInteger(row.selectedQuantity) || row.selectedQuantity <= 0 || row.selectedQuantity > row.limit)) {
      proxy.$modal.msgError('数量需为剩余额度内的正整数');
      return;
    }
    procurementRequestKeys[kind] ||= crypto.randomUUID();
    const items = procurementSelection.value.map(row => kind === 'receipt'
      ? { purchaseLineId: row.purchaseLineId, quantity: row.selectedQuantity, warehouseId: row.warehouseId }
      : { sourceReceiptLineId: row.sourceReceiptLineId, quantity: row.selectedQuantity });
    pendingDrafts[kind] = { orderId: form.value.systematicOrderForm, request: { requestKey: procurementRequestKeys[kind], items },
      rows: procurementRows.value, selection: [...procurementSelection.value] };
  }
  procurementSubmitting.value = true;
  try {
    const create = kind === 'receipt' ? createPurchaseReceipt : createPurchaseReturn;
    const orderId = pendingDrafts[kind].orderId;
    const response = await create(orderId, pendingDrafts[kind].request);
    const receiptId = response.data.receiptId;
    pendingDrafts[kind] = null;
    procurementRequestKeys[kind] = null;
    procurementDialogOpen.value = false;
    if (!currentOrder(orderId)) return;
    proxy.$modal.msgSuccess(kind === 'receipt' ? '收货草稿已创建' : '退供草稿已创建');
    await router.push({ path: '/purchase/purchaseDocumentProcessing', query: { systematicReceipt: receiptId } });
  } catch (error) {
    // 明确的业务拒绝允许调整数量；网络断开或超时保留同一请求和幂等键重试。
    if (!error?.config || error?.response) pendingDrafts[kind] = null;
  } finally {
    procurementSubmitting.value = false;
  }
}
//自定义合计行
function getSummaries(param) {
  const { columns, data } = param;
  const sums = [];
  columns.forEach((column, index) => {
    if (index === 0) {
      sums[index] = "合计";
      return;
    }

    if (column.property !== undefined) {
      if (!['inventoryQty', 'planQuantity', 'money', 'cost'].includes(column.property)) {
        sums[index] = '';
        return;
      }
      const rows = column.property === 'inventoryQty'
        ? [...new Map(data.map(item => [String(item.productId), item])).values()] : data;
      const values = rows.map((item) => Number(item[column.property]));
      if (!values.every((value) => isNaN(value))) {
        sums[index] = values.reduce((prev, curr) => {
          const value = Number(curr);
          if (!isNaN(value)) {
            return prev + curr;
          } else {
            return prev;
          }
        }, 0);
        if (index === 11) {
          sums[index] = Number(sums[index]).toFixed(2);
          form.value.orderFormAmount = sums[index];
          form.value.orderCapitalizeAmount = RMBConverter.numberToChinese(
            sums[index]
          );
        } else if (index === 12) {
          sums[index] = Number(sums[index]).toFixed(2);
        }
      } else {
        sums[index] = "";
      }
      if ([1, 2, 3, 4, 5, 6, 10].includes(index)) {
        sums[index] = "";
      }
    }
  });
  return sums;
}
// 表单重置
function reset() {
  progressLines.value = [];
  pendingDrafts.receipt = pendingDrafts.return = null;
  procurementRequestKeys.receipt = procurementRequestKeys.return = null;
  form.value = {
    systematicOrderForm: "CR-" + randomId(),
    originalOrderForm: null,
    orderFormType: 1,
    orderFormStatus: 1,
    orderDate: getNowTime(),
    deliveryDate: getNowTime(),
    warehousingIds: null,
    supplierIds: null,
    userIds: null,
    planReceipt: null,
    orderFormNotes: null,
    deposit: null,
    orderFormAmount: null,
    orderCapitalizeAmount: null,
    afterSalesInstallation: null,
    findingOfAudit: null,
    reviewComments: null,
    isWarehousing: null,
    details: [],
    // 采购单据
    systematicReceipt: null,
    originalReceipt: null,
    receiptType: 1,
    receiptCategory: 1,
    receiptStatus: 1,
    invoiceDate: null,
    receiptNotes: null,
    totalAmount: null,
    capitalizeTotalAmount: null,
    printId: null,
  };
  proxy.resetForm("orderRef");
}
/** 重置操作供应商表单 */
function supplierReset() {
  supplierForm.value = {
    supplierCode: undefined,
    supplierName: undefined,
    supplierSalesPerson: undefined,
    supplierContacts: undefined,
    supplierContactNumber: undefined,
    supplierWorkAddress: undefined,
    supplierPostalCode: undefined,
    supplierOpeningDebt: undefined,
    supplierOpeningBank: undefined,
    supplierAccountNumber: undefined,
    supplierTaxId: undefined,
    supplierCompanyHomepage: undefined,
    supplierEmail: undefined,
    supplierLegalPerson: undefined,
    status: undefined,
    remarks: undefined,
  };
  proxy.resetForm("supplierRef");
}
/** 重置操作货品表单 */
function productReset() {
  productForm.value = {
    productId: undefined,
    productCode: undefined,
    productName: undefined,
    productType: undefined,
    productSpecifications: undefined,
    measureUnit: undefined,
    phonenumber: undefined,
    producer: undefined,
    costPrice: undefined,
    univalence: undefined,
    discount: undefined,
    productBarcode: undefined,
    upperLimit: undefined,
    lowerLimit: undefined,
    defaultWarehouse: undefined,
    notes: undefined,
    status: "0",
  };
  proxy.resetForm("productRef");
}
// 添加货品
function addItem() {
  form.value.details.push({});
}
// 选择货品
function changeProduct(index, row) {
  const stableLine = { purchaseLineId: row.purchaseLineId, systematicId: row.systematicId };
  form.value.details[index] = {
    ...stableLine,
    productId: null,
    productCode: null,
    productName: null,
    productTypeName: null,
    productSpecifications: null,
    measureUnit: null,
    producer: null,
    inventoryQty: null,
    univalence: null,
    discount: null,
    retrievalId: null,
    warehousingId: null,
  };
  let lists = [];
  productOptions.value.forEach((item) => {
    if (row.productCode == item.productId) {
      lists = item;
    } else if (row.productName == item.productId) {
      lists = item;
    }
  });
  form.value.details[index] = {
    ...stableLine,
    systematicOrderForm: form.value.systematicOrderForm,
    systematicReceipt: form.value.systematicReceipt,
    productId: lists.productId,
    productCode: lists.productCode,
    productName: lists.productName,
    productTypeName: lists.productTypeName,
    productSpecifications: lists.productSpecifications,
    measureUnit: lists.measureUnit,
    producer: lists.producer,
    inventoryQty: lists.inventoryQty,
    retrievalId: form.value.warehousingIds,
    supplierId: form.value.supplierIds,
    planQuantity: 0,
    univalence: lists.univalence,
    discount: lists.discount,
    money: 0,
    cost: 0,
    remarks: null,
  };
  form.value.details = [...form.value.details];
}
/** 打印按钮 */
async function printOut() {
  form.value.printId = print_selected_files.value[0].label;
  form.value.printSize = print_selected_sizes.value[0].label;
  await viewUrl().then((res) => {
    openUrl.value = res;
  });
  const printUrl =
    openUrl.value +
    "/" +
    form.value.printId +
    "?token=Bearer " +
    getToken() +
    "&systematicOrderForm=" +
    form.value.systematicOrderForm +
    "&pageSize=" +
    form.value.printSize;
  window.open(printUrl, "_blank");
}
/** 删除按钮操作 */
function deleteOrder() {
  getPurchaseOrder(form.value.systematicOrderForm).then((response) => {
    const details = response.data.details;
    proxy.$modal
      .confirm(
        "确认要删除系统编号为" + form.value.systematicOrderForm + "的采购订单?"
      )
      .then(function () {
        return delPurchaseOrder(details);
      })
      .then(() => {
        proxy.$modal.msgSuccess(
          "已删除系统编号为" + form.value.systematicOrderForm + "的采购订单。"
        );
        reset();
      })
      .catch(() => {});
  });
}
/** 查询货品类型下拉树结构 */
function getProductTypeTree() {
  productTypeTreeSelect().then((response) => {
    typeOptions.value = response.data;
  });
}
/** 新增供应商按钮操作 */
function supplierAdd() {
  supplierReset();
  openSupplier.value = true;
  titleSupplier.value = "新增供应商";
}
/** 提交供应商按钮 */
function submitSupplierForm() {
  proxy.$refs["supplierRef"].validate((valid) => {
    if (valid) {
      addSupplier(supplierForm.value);
      proxy.$modal.msgSuccess(
        "已新增编号为" + supplierForm.value.supplierCode + "的供应商"
      );
      openSupplier.value = false;
      listSupplier(option.value).then((response) => {
        supplierOptions.value = response.rows;
      });
    }
  });
}
/** 取消供应商按钮 */
function cancelSupplierForm() {
  openSupplier.value = false;
}
/** 新增货品按钮操作 */
function handleAdd() {
  productReset();
  getProductTypeTree();
  open.value = true;
  title.value = "新增货品";
}
/** 提交货品按钮 */
function submitProductForm() {
  proxy.$refs["productRef"].validate((valid) => {
    if (valid) {
      addProduct(productForm.value);
      proxy.$modal.msgSuccess(
        "已新增编号为" + productForm.value.productCode + "的货品"
      );
      open.value = false;
      listProduct(option.value).then((response) => {
        productOptions.value = response.rows;
      });
    }
  });
}
/** 取消货品按钮 */
function cancelProductForm() {
  open.value = false;
}
/** 重置下拉框表单 */
function optionReset() {
  option.value = {
    warehouseId: undefined,
    warehouseName: undefined,
    supplierId: undefined,
    supplierName: undefined,
    customerId: undefined,
    customerName: undefined,
    productCode: undefined,
    productName: undefined,
    pageSize: 50,
  };
}
// 查询仓库名称
function remoteWarehouse(query) {
  optionReset();
  if (query) {
    loading.value = true;
    setTimeout(() => {
      option.value.warehouseName = query;
      listWarehouse(option.value).then((response) => {
        warehouseOptions.value = response.rows;
      });
      loading.value = false;
      warehouseOptions.value = list.value.filter((item) => {
        return item.label.toLowerCase().includes(query.toLowerCase());
      });
    }, 200);
  } else {
    listWarehouse(option.value).then((response) => {
      warehouseOptions.value = response.rows;
    });
  }
}
// 查询供应商名称
function remoteSupplier(query) {
  optionReset();
  if (query) {
    loading.value = true;
    setTimeout(() => {
      option.value.supplierName = query;
      listSupplier(option.value).then((response) => {
        supplierOptions.value = response.rows;
      });
      loading.value = false;
      supplierOptions.value = list.value.filter((item) => {
        return item.label.toLowerCase().includes(query.toLowerCase());
      });
    }, 200);
  } else {
    listSupplier(option.value).then((response) => {
      supplierOptions.value = response.rows;
    });
  }
}
// 查询货品编号
function remoteProductCode(query) {
  optionReset();
  if (query) {
    loading.value = true;
    setTimeout(() => {
      option.value.productCode = query;
      listProduct(option.value).then((response) => {
        productOptions.value = response.rows;
      });
      loading.value = false;
      productOptions.value = list.value.filter((item) => {
        return item.label.toLowerCase().includes(query.toLowerCase());
      });
    }, 200);
  } else {
    listProduct(option.value).then((response) => {
      productOptions.value = response.rows;
    });
  }
}
// 查询货品名称
function remoteProductName(query) {
  optionReset();
  if (query) {
    loading.value = true;
    setTimeout(() => {
      option.value.productName = query;
      listProduct(option.value).then((response) => {
        productOptions.value = response.rows;
      });
      loading.value = false;
      productOptions.value = list.value.filter((item) => {
        return item.label.toLowerCase().includes(query.toLowerCase());
      });
    }, 200);
  } else {
    listProduct(option.value).then((response) => {
      productOptions.value = response.rows;
    });
  }
}

watch(() => [proxy.$route.path, proxy.$route.query.systematicOrderForm], () => {
  if (proxy.$route.path === '/purchase/purchaseOrderProcessing') initialization();
});
initialization();
getList();
</script>

<style scoped>
.form-item {
  width: 200px;
}

.footer {
  text-align: center;
}

.purchase-progress { margin: 12px 0 24px; }
.purchase-progress-header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px 16px; margin-bottom: 12px; }
.purchase-progress-header > div { display: flex; flex-wrap: wrap; gap: 8px; }
.purchase-progress-header :deep(.el-button + .el-button) { margin-left: 0; }
</style>
