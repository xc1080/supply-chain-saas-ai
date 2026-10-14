<template>
  <div class="prod-sku-select-wrapper">
    <div>
      <el-form
        :inline="true"
        :model="queryParams"
        ref="queryRef"
        label-width="90px">
        <el-form-item label="货品编号" prop="productCode">
            <el-input
                v-model="queryParams.productCode"
                placeholder="请输入货品编号"
                clearable
                class="form-item"
                @keyup.enter="clickQuery"/>
        </el-form-item>
        <el-form-item label="货品名称" prop="productName">
            <el-input
                v-model="queryParams.productName"
                placeholder="请输入货品名称"
                clearable
                class="form-item"
                @keyup.enter="clickQuery"/>
        </el-form-item>
        <el-form-item label="货品类型">
          <el-tree-select
            v-model="queryParams.productType"
            :data="typeOptions"
            :props="{ value: 'id', label: 'label', children: 'children' }"
            value-key="id"
            placeholder="请选择货品类型"
            check-strictly
            clearable/>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" icon="Search" @click="clickQuery">查询</el-button>
          <!-- <el-button icon="Refresh" @click="resetQuery">重置</el-button> -->
        </el-form-item>
      </el-form>
    </div>
    <div class="m-table-wrap">
      <table class="m-table" v-if="list.length !== 0" aria-label="选择货品">
        <thead>
          <tr class="m-tr">
            <th class="m-th">
              <!-- <el-checkbox
                :value="leftAllChecked === 1"
                @input="leftAllChecked = $event"
                :indeterminate="leftAllChecked === 2"
                :disabled="editableList.length === 0"
              ></el-checkbox> -->
            </th>
            <th class="m-th">货品编号</th>
            <th class="m-th">货品名称</th>
            <th class="m-th">类型</th>
            <th class="m-th">规格</th>
            <th class="m-th">单位</th>
            <th class="m-th">产地</th>
          </tr>
        </thead>
        <tbody class="m-body">
          <tr class="m-tr" v-for="p in list" :key="p.productId">
            <td class="m-td">
              <el-checkbox
                v-model="p.checked"
                :aria-label="`选择 ${p.productName || p.productCode}`"
                :disabled="rightListKeySet.has(p.productId)">
                <span class="selection-name">选择 {{ p.productName || p.productCode }}</span>
              </el-checkbox>
            </td>
            <td class="m-td"> {{ p.productCode }} </td>
            <td class="m-td"><ProductIdentity :product="p" /></td>
            <td class="m-td"> {{ p.type?.productTypeName || '—' }} </td>
            <td class="m-td"> {{ p.productSpecifications }} </td>
            <td class="m-td"> {{ p.measureUnit }} </td>
            <td class="m-td"> {{ p.producer }} </td>
          </tr>
        </tbody>
      </table>
      <el-empty v-else :image-size="64" description="没有匹配的货品，试试名称或编号"></el-empty>
    </div>
    <pagination
      v-show="total > 0"
      :total="total"
      v-model:page="pageReq.page"
      v-model:limit="pageReq.size"
      @pagination="loadAll"/>
  </div>
</template>

<script>
import { listProduct, productTypeTreeSelect } from "@/api/basedate/product";
import ProductIdentity from "@/components/ProductIdentity/index.vue";

export default {
  name: "ItemSelect",
  components: { ProductIdentity },
  props: ['data'],
  data() {
    return {
      dateRange: undefined,
      checkAll: false,
      //物料类别
      typeOptions: [],
      queryParams: {
        productCode: null,
        productName: null,
        productType: null,
        productSpecifications: null,
        measureUnit: null,
        producer: null,
      },
      list: [],
      total: 0,
      pageReq: {
        page: 1,
        size: 10,
      },
      rightList: [],
      rightListKeySet: new Set
    };
  },
  computed: {
    leftAllChecked: {
      get() {
        return this.editableList.length > 0 &&
        this.editableList.every((it) => it.checked)
          ? 1
          : this.editableList.some((it) => it.checked)
            ? 2
            : 0;
      },
      set(v) {
        this.editableList.forEach((it) => (it.checked = v));
      },
    },
    editableList() {
      return this.list.filter((it) => !this.rightListKeySet.has(it.productId));
    },
    leftCheckedList() {
      return this.list.filter((it) => it.checked);
    // },
    // resetQuery() {
    //   this.dateRange = [];
    //   proxy.value.resetForm("queryRef");
    }
  },
  watch: {
    rightList: {
      handler(newVal, oldVal) {
        const set = new Set();
        newVal.forEach((it) => set.add(it.productId));
        this.rightListKeySet = set;
      },
      deep: true
    }
  },
  created() {
    if (this.data) {
      this.initDetailsList(this.data)
    }
    this.loadAll();
    productTypeTreeSelect().then((response) => {
      this.typeOptions = response.data;
    });
  },
  methods: {
    initDetailsList(list) {
      this.rightList = list.map(it => {
        return it.prod
      })
    },
    loadAll() {
      const pageReq = {...this.pageReq};
      pageReq.page -= 1;
      listProduct(this.queryParams, pageReq).then((res) => {
        const {rows, total} = res;
        rows.forEach((it) => (it.checked = false));
        this.list = rows;
        this.total = total;
      });
    },
    clickQuery() {
      this.pageReq.page = 1;
      this.loadAll();
    },
    getRightList() {
      this.moveRight();
      return this.rightList;
    },
    moveRight() {
      for (let it of this.leftCheckedList) {
        if (!this.rightListKeySet.has(it.productId)) {
          it.checked = false;
          this.rightList.push({...it, checked: false});
        }
      }
    },
  },
};
</script>

<style scoped lang="scss">
.selection-name { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.form-item{
    width: 220px; max-width: 100%
}
.m-table-wrap {
  color: var(--sc-muted);
  overflow-x: auto;
  overscroll-behavior-x: contain;
  font-size: 14px;
  line-height: 1.5714285714285714;
  border-radius: 8px 8px 0 0;
  .m-table {
    .m-th:first-child { width: 48px; }
    .m-th:nth-child(2) { width: 160px; }
    .m-th:nth-child(3) { width: 250px; }
    display: table;
    table-layout: fixed;
    width: 100%;
    min-width: 940px;
    text-align: left;
    border-radius: 8px 8px 0 0;
    border-collapse: separate;
    border-spacing: 0;
    margin: 0;
    .m-th {
      padding: 12px;
      color: var(--sc-text);
      font-weight: 500;
      text-align: left;
      background: var(--sc-surface-soft);
      border: none;
      border-bottom: 1px solid var(--sc-border);
      transition: background .3s ease;
      &:first-child {
        border-top-left-radius: 8px;
      }
      &:last-child {
        border-top-right-radius: 8px;
      }
    }
    .m-body {
      position: relative;
      .m-tr-loading {
        border: none;
        background-color: var(--sc-surface);
        .m-loading {
          position: absolute;
          width: 100%;
          height: 100%;
        }
      }
      .m-tr-empty {
        border: none;
        background-color: var(--sc-surface);
        &:hover {
          background: var(--sc-surface);
        }
        .m-td-empty {
          padding: 12px;
          border: none;
          border-bottom: 1px solid var(--sc-border);
          .empty {
            margin: 32px 0;
          }
        }
      }
    }
    .m-tr {
      border: none;
      background-color: var(--sc-surface);
      transition: background-color .3s;
      .m-td {
        padding: 12px;
        border: none;
        border-bottom: 1px solid var(--sc-border);
        transition: background .3s;
        overflow: hidden;
        white-space: normal;
        overflow-wrap: anywhere;
        text-overflow: ellipsis;
      }
      &:hover {
        background-color: var(--sc-surface-soft);
      }
    }
  }
}

@media (max-width: 700px) {
  .prod-sku-select-wrapper :deep(.el-form-item) { display: flex; flex-direction: column; align-items: stretch; width: 100%; margin-right: 0; }
  .prod-sku-select-wrapper :deep(.el-form-item__label) { justify-content: flex-start; width: auto !important; height: auto; padding: 0 0 6px; }
  .prod-sku-select-wrapper :deep(.el-form-item__content) { min-width: 0; }
  .prod-sku-select-wrapper :deep(.el-input), .prod-sku-select-wrapper :deep(.el-select) { width: 100%; }
}
</style>
