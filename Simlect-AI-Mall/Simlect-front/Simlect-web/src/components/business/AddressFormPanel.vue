<!-- [zh] SFC 区块开始（单文件组件 template/script/style 三段） -->
<template>
  <!-- [zh] 开始标签 `<el-dialog>` -->
  <el-dialog
    v-if="isDesktop"
    v-model="visible"
    :title="form.addressId ? '编辑地址' : '新增地址'"
    width="480px"
    align-center
    destroy-on-close
    class="address-form-dialog ignore"
    @closed="onClosed"
  >
    <!-- [zh] 开始标签 `<AddressFormFields>` -->
    <AddressFormFields :form="form" :saving="saving" @submit="save" />
  <!-- [zh] 闭合标签 `</el-dialog>` -->
  </el-dialog>

  <!-- [zh] 开始标签 `<el-drawer>` -->
  <el-drawer
    v-else
    v-model="visible"
    :title="form.addressId ? '编辑地址' : '新增地址'"
    direction="btt"
    size="92%"
    class="address-form-drawer"
    destroy-on-close
    @closed="onClosed"
  >
    <!-- [zh] 开始标签 `<div>` -->
    <div class="address-drawer-inner">
      <!-- [zh] 开始标签 `<AddressFormFields>` -->
      <AddressFormFields :form="form" :saving="saving" embedded @submit="save" />
    <!-- [zh] 闭合标签 `</div>` -->
    </div>
  <!-- [zh] 闭合标签 `</el-drawer>` -->
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { codeToText } from 'element-china-area-data';
import { matchRegionFromFullAddress } from '@/utils/regionGeocode';
import { addressApi } from '@/api/modules';
import { useDevice } from '@/composables/useDevice';
import AddressFormFields from '@/components/business/AddressFormFields.vue';
import { toast } from '@/utils/toast';
import { DEMO_MODE } from '@/integrations/demo';

export interface AddressFormItem {
  addressId: string;
  addressee: string;
  phone: string;
  address: string;
  defaultType?: number;
}

const props = defineProps<{
  modelValue: boolean;
  editItem?: AddressFormItem | null;
}>();

const emit = defineEmits<{
  'update:modelValue': [boolean];
  saved: [];
}>();

const { isDesktop } = useDevice();
const saving = ref(false);

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
});

const form = reactive({
  addressId: '',
  addressee: '',
  phone: '',
  regionCodes: [] as string[],
  detailAddress: '',
  defaultType: 0 as number
});

const resetForm = () => {
  form.addressId = '';
  form.addressee = '';
  form.phone = '';
  form.regionCodes = [];
  form.detailAddress = '';
  form.defaultType = 0;
};

const matchRegion = matchRegionFromFullAddress;

const fillForm = (item?: AddressFormItem | null) => {
  if (!item) {
    resetForm();
    return;
  }
  form.addressId = item.addressId;
  form.addressee = item.addressee || '';
  form.phone = item.phone || '';
  const fullAddress = item.address || '';
  const regionCodes = matchRegion(fullAddress);
  if (regionCodes) {
    form.regionCodes = regionCodes;
    const regionText = regionCodes.map((c) => codeToText[c] || '').join('');
    form.detailAddress = fullAddress.slice(regionText.length).trim();
  } else {
    form.regionCodes = [];
    form.detailAddress = fullAddress;
  }
  form.defaultType = item.defaultType === 1 ? 1 : 0;
};

watch(
  () => props.modelValue,
  (open) => {
    if (open) fillForm(props.editItem);
  }
);

watch(
  () => props.editItem,
  (item) => {
    if (props.modelValue) fillForm(item);
  }
);

const validateForm = () => {
  const name = form.addressee.trim();
  const phone = form.phone.trim();
  const detail = form.detailAddress.trim();
  if (!name || name.length > (DEMO_MODE ? 64 : 20)) {
    toast.warning('请填写有效的收货人姓名');
    return false;
  }
  if (DEMO_MODE ? !/^[+0-9 ()-]{6,24}$/.test(phone) || !/[0-9]/.test(phone) : !/^1\d{10}$/.test(phone)) {
    toast.warning('请输入正确的联系电话');
    return false;
  }
  if (!DEMO_MODE && !form.regionCodes.length) {
    toast.warning('请选择所在地区');
    return false;
  }
  const fullAddress = form.regionCodes.map((c) => codeToText[c] || '').join('') + detail;
  if (!detail || (DEMO_MODE && (fullAddress.length < 5 || fullAddress.length > 300))) {
    toast.warning(DEMO_MODE ? '收货地址须为5至300字' : '请输入详细地址');
    return false;
  }
  form.addressee = name;
  form.phone = phone;
  form.detailAddress = detail;
  return true;
};

const save = async () => {
  if (!validateForm() || saving.value) return;
  saving.value = true;
  try {
    const regionText = form.regionCodes.map((c) => codeToText[c] || '').join('');
    const fullAddress = regionText + form.detailAddress.trim();
    const payload = {
      addressee: form.addressee,
      phone: form.phone,
      address: fullAddress,
      defaultType: form.defaultType
    };
    if (form.addressId) {
      await addressApi.updateAddress({ addressId: form.addressId, ...payload });
    } else {
      await addressApi.addAddress(payload);
    }
    toast.success('保存成功');
    visible.value = false;
    emit('saved');
  } finally {
    saving.value = false;
  }
};

const onClosed = () => resetForm();
</script>
