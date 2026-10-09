import "@/assets/icon/iconfont.css"
import '@/assets/base.scss';
import '@/assets/simlect-admin.scss';
import '@/assets/simlect-admin-layout.scss';
import '@/assets/desktop-admin.scss';
import '@/assets/mobile-glass.scss';
import '@/assets/mobile-page.scss';
import '@/assets/mobile-product-edit.scss';
import '@/assets/admin-ios26-message.scss';
import '@/assets/liquid-glass-surface.scss';

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import Request from "@/utils/Request"
import Message from "@/utils/Message"
import Utils from "@/utils/Utils"
import { Confirm, Alert } from "@/utils/Confirm.js"
import { ConfirmSensitive } from "@/utils/sensitiveConfirm.js"
import { Api } from "@/utils/Api.js"
import Verify from "@/utils/Verify.js"
import { ensureLiquidGlassFilters } from "@/utils/liquidGlassFilters.js"

ensureLiquidGlassFilters();

import Dialog from "@/components/Dialog.vue";
import Drawer from "@/components/Drawer.vue";
import Table from "@/components/Table.vue";
import ImageSelect from "@/components/ImageSelect.vue";
import Cover from "@/components/Cover.vue";
import CouponOrderCover from "@/components/CouponOrderCover.vue";
import Avatar from "@/components/Avatar.vue";
import OpBtn from "@/components/OpBtn.vue";
import Price from "@/components/Price.vue";

const app = createApp(App)
app.use(ElementPlus);
app.use(createPinia())
app.use(router)

app.component('ImageSelect', ImageSelect)
app.component('Cover', Cover)
app.component('CouponOrderCover', CouponOrderCover)
app.component("Avatar", Avatar);
app.component('Dialog', Dialog)
app.component('Drawer', Drawer)
app.component('Table', Table)
app.component('OpBtn', OpBtn)
app.component("Price", Price);

app.config.globalProperties.Request = Request;
app.config.globalProperties.Message = Message;
app.config.globalProperties.Utils = Utils;
app.config.globalProperties.Api = Api
app.config.globalProperties.Confirm = Confirm;
app.config.globalProperties.ConfirmSensitive = ConfirmSensitive;
app.config.globalProperties.Alert = Alert;
app.config.globalProperties.Verify = Verify;

app.config.globalProperties.productMainImageCount = 5;
app.config.globalProperties.imageThumbnailSuffix = "_thumbnail"

app.config.globalProperties.imageAccept = ".jpg,.png,.gif,.bmp,.webp";
app.mount('#app')
