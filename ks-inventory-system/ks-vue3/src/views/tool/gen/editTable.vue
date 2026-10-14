<template>
  <el-card>
    <el-tabs v-model="activeName">
      <el-tab-pane label="基本信息" name="basic">
        <basic-info-form ref="basicInfo" :info="info" />
      </el-tab-pane>
      <el-tab-pane label="字段信息" name="columnInfo">
        <el-table ref="dragTable" :data="columns" row-key="columnId" :max-height="tableHeight">
          <el-table-column label="序号" type="index" min-width="64"/>
          <el-table-column
            label="字段列名"
            prop="columnName"
            min-width="140"
            :show-overflow-tooltip="true"
          />
          <el-table-column label="字段描述" min-width="140">
            <template #default="scope">
              <el-input v-model="scope.row.columnComment" :aria-label="`字段描述：${scope.row.columnName}`"></el-input>
            </template>
          </el-table-column>
          <el-table-column
            label="物理类型"
            prop="columnType"
            min-width="140"
            :show-overflow-tooltip="true"
          />
          <el-table-column label="Java类型" min-width="130">
            <template #default="scope">
              <el-select v-model="scope.row.javaType" :aria-label="`Java 类型：${scope.row.columnName}`">
                <el-option label="Long" value="Long" />
                <el-option label="String" value="String" />
                <el-option label="Integer" value="Integer" />
                <el-option label="Double" value="Double" />
                <el-option label="BigDecimal" value="BigDecimal" />
                <el-option label="Date" value="Date" />
                <el-option label="Boolean" value="Boolean" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="java属性" min-width="140">
            <template #default="scope">
              <el-input v-model="scope.row.javaField" :aria-label="`Java 属性：${scope.row.columnName}`"></el-input>
            </template>
          </el-table-column>

          <el-table-column label="插入" min-width="64">
            <template #default="scope">
              <el-checkbox true-label="1" false-label="0" v-model="scope.row.isInsert" :aria-label="`允许插入：${scope.row.columnName}`"></el-checkbox>
            </template>
          </el-table-column>
          <el-table-column label="编辑" min-width="64">
            <template #default="scope">
              <el-checkbox true-label="1" false-label="0" v-model="scope.row.isEdit" :aria-label="`允许编辑：${scope.row.columnName}`"></el-checkbox>
            </template>
          </el-table-column>
          <el-table-column label="列表" min-width="64">
            <template #default="scope">
              <el-checkbox true-label="1" false-label="0" v-model="scope.row.isList" :aria-label="`列表显示：${scope.row.columnName}`"></el-checkbox>
            </template>
          </el-table-column>
          <el-table-column label="查询" min-width="64">
            <template #default="scope">
              <el-checkbox true-label="1" false-label="0" v-model="scope.row.isQuery" :aria-label="`允许查询：${scope.row.columnName}`"></el-checkbox>
            </template>
          </el-table-column>
          <el-table-column label="查询方式" min-width="140">
            <template #default="scope">
              <el-select v-model="scope.row.queryType" :aria-label="`查询方式：${scope.row.columnName}`">
                <el-option label="=" value="EQ" />
                <el-option label="!=" value="NE" />
                <el-option label=">" value="GT" />
                <el-option label=">=" value="GTE" />
                <el-option label="<" value="LT" />
                <el-option label="<=" value="LTE" />
                <el-option label="LIKE" value="LIKE" />
                <el-option label="BETWEEN" value="BETWEEN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="必填" min-width="64">
            <template #default="scope">
              <el-checkbox true-label="1" false-label="0" v-model="scope.row.isRequired" :aria-label="`必填：${scope.row.columnName}`"></el-checkbox>
            </template>
          </el-table-column>
          <el-table-column label="显示类型" min-width="160">
            <template #default="scope">
              <el-select v-model="scope.row.htmlType" :aria-label="`显示类型：${scope.row.columnName}`">
                <el-option label="文本框" value="input" />
                <el-option label="文本域" value="textarea" />
                <el-option label="下拉框" value="select" />
                <el-option label="单选框" value="radio" />
                <el-option label="复选框" value="checkbox" />
                <el-option label="日期控件" value="datetime" />
                <el-option label="图片上传" value="imageUpload" />
                <el-option label="文件上传" value="fileUpload" />
                <el-option label="富文本控件" value="editor" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="字典类型" min-width="160">
            <template #default="scope">
              <el-select v-model="scope.row.dictType" :aria-label="`字典类型：${scope.row.columnName}`" clearable filterable placeholder="请选择">
                <el-option
                  v-for="dict in dictOptions"
                  :key="dict.dictType"
                  :label="dict.dictName"
                  :value="dict.dictType">
                  <span style="float: left">{{ dict.dictName }}</span>
                  <span style="float: right; color: var(--sc-muted); font-size: 13px">{{ dict.dictType }}</span>
              </el-option>
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="生成信息" name="genInfo">
        <gen-info-form ref="genInfo" :info="info" :tables="tables" />
      </el-tab-pane>
    </el-tabs>
    <el-form label-width="100px">
      <div style="display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px;">
        <el-button type="primary" @click="submitForm()">提交</el-button>
        <el-button @click="close()">返回</el-button>
      </div>
    </el-form>
  </el-card>
</template>

<script setup name="GenEdit">
import { getGenTable, updateGenTable } from "@/api/tool/gen";
import { optionselect as getDictOptionselect } from "@/api/system/dict/type";
import basicInfoForm from "./basicInfoForm";
import genInfoForm from "./genInfoForm";

const route = useRoute();
const { proxy } = getCurrentInstance();

const activeName = ref("columnInfo");
const tableHeight = ref(document.documentElement.scrollHeight - 245 + "px");
const tables = ref([]);
const columns = ref([]);
const dictOptions = ref([]);
const info = ref({});

/** 提交按钮 */
function submitForm() {
  const basicForm = proxy.$refs.basicInfo.$refs.basicInfoForm;
  const genForm = proxy.$refs.genInfo.$refs.genInfoForm;
  Promise.all([basicForm, genForm].map(getFormPromise)).then(res => {
    const validateResult = res.every(item => !!item);
    if (validateResult) {
      const genTable = Object.assign({}, info.value);
      genTable.columns = columns.value;
      genTable.params = {
        treeCode: info.value.treeCode,
        treeName: info.value.treeName,
        treeParentCode: info.value.treeParentCode,
        parentMenuId: info.value.parentMenuId
      };
      updateGenTable(genTable).then(res => {
        proxy.$modal.msgSuccess(res.msg);
        if (res.code === 200) {
          close();
        }
      });
    } else {
      proxy.$modal.msgError("表单校验未通过，请重新检查提交内容");
    }
  });
}

function getFormPromise(form) {
  return new Promise(resolve => {
    form.validate(res => {
      resolve(res);
    });
  });
}

function close() {
  const obj = { path: "/tool/gen", query: { t: Date.now(), pageNum: route.query.pageNum } };
  proxy.$tab.closeOpenPage(obj);
}

(() => {
  const tableId = route.params && route.params.tableId;
  if (tableId) {
    // 获取表详细信息
    getGenTable(tableId).then(res => {
      columns.value = res.data.rows;
      info.value = res.data.info;
      tables.value = res.data.tables;
    });
    /** 查询字典下拉列表 */
    getDictOptionselect().then(response => {
      dictOptions.value = response.data;
    });
  }
})();
</script>
