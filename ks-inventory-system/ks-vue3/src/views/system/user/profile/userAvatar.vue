<template>
  <div class="user-info-head">
    <button type="button" class="avatar-trigger" aria-label="更换头像" @click="editCropper()">
      <img :src="options.img" alt="账户头像" width="120" height="120" class="img-circle img-lg" />
    </button>
    <el-dialog :title="title" v-model="open" width="800px" append-to-body @opened="modalOpened" @close="closeDialog">
      <el-row>
        <el-col :xs="24" :md="12" :style="{ height: '350px' }">
          <vue-cropper
            ref="cropper"
            :img="options.img"
            :info="true"
            :autoCrop="options.autoCrop"
            :autoCropWidth="options.autoCropWidth"
            :autoCropHeight="options.autoCropHeight"
            :fixedBox="options.fixedBox"
            :outputType="options.outputType"
            @realTime="realTime"
            v-if="visible"
          />
        </el-col>
        <el-col :xs="24" :md="12" class="avatar-preview-column">
          <div class="avatar-upload-preview">
            <img :src="options.previews.url" :style="options.previews.img" alt="裁剪预览" />
          </div>
        </el-col>
      </el-row>
      <br />
      <el-row class="avatar-actions">
        <el-col :span="24">
          <el-upload
            action="#"
            :http-request="requestUpload"
            :show-file-list="false"
            :before-upload="beforeUpload"
          >
            <el-button>
              选择
              <el-icon class="el-icon--right"><Upload /></el-icon>
            </el-button>
          </el-upload>
        </el-col>
        <el-col :span="24">
          <el-button icon="Plus" aria-label="放大头像" @click="changeScale(1)"></el-button>
        </el-col>
        <el-col :span="24">
          <el-button icon="Minus" aria-label="缩小头像" @click="changeScale(-1)"></el-button>
        </el-col>
        <el-col :span="24">
          <el-button icon="RefreshLeft" aria-label="向左旋转头像" @click="rotateLeft()"></el-button>
        </el-col>
        <el-col :span="24">
          <el-button icon="RefreshRight" aria-label="向右旋转头像" @click="rotateRight()"></el-button>
        </el-col>
        <el-col :span="24">
          <el-button type="primary" @click="uploadImg()">保存头像</el-button>
        </el-col>
      </el-row>
    </el-dialog>
  </div>
</template>

<script setup>
import "vue-cropper/dist/index.css";
import { VueCropper } from "vue-cropper";
import { uploadAvatar } from "@/api/system/user";
import useUserStore from "@/store/modules/user";

const userStore = useUserStore();
const { proxy } = getCurrentInstance();

const open = ref(false);
const visible = ref(false);
const title = ref("修改头像");

//图片裁剪数据
const options = reactive({
  img: userStore.avatar,     // 裁剪图片的地址
  autoCrop: true,            // 是否默认生成截图框
  autoCropWidth: 200,        // 默认生成截图框宽度
  autoCropHeight: 200,       // 默认生成截图框高度
  fixedBox: true,            // 固定截图框大小 不允许改变
  outputType: "png",         // 默认生成截图为PNG格式
  filename: 'avatar',        // 文件名称
  previews: {}               //预览数据
});

/** 编辑头像 */
function editCropper() {
  open.value = true;
}

/** 打开弹出层结束时的回调 */
function modalOpened() {
  visible.value = true;
}

/** 覆盖默认上传行为 */
function requestUpload() {}

/** 向左旋转 */
function rotateLeft() {
  proxy.$refs.cropper.rotateLeft();
}

/** 向右旋转 */
function rotateRight() {
  proxy.$refs.cropper.rotateRight();
}

/** 图片缩放 */
function changeScale(num) {
  num = num || 1;
  proxy.$refs.cropper.changeScale(num);
}

/** 上传预处理 */
function beforeUpload(file) {
  if (file.type.indexOf("image/") == -1) {
    proxy.$modal.msgError("文件格式错误，请上传图片类型,如：JPG，PNG后缀的文件。");
  } else {
    const reader = new FileReader();
    reader.readAsDataURL(file);
    reader.onload = () => {
      options.img = reader.result;
      options.filename = file.name;
    };
  }
}

/** 上传图片 */
function uploadImg() {
  proxy.$refs.cropper.getCropBlob(data => {
    let formData = new FormData();
    formData.append("avatarfile", data, options.filename);
    uploadAvatar(formData).then(response => {
      open.value = false;
      options.img = import.meta.env.VITE_APP_BASE_API + response.imgUrl;
      userStore.avatar = options.img;
      proxy.$modal.msgSuccess("修改成功");
      visible.value = false;
    });
  });
}

/** 实时预览 */
function realTime(data) {
  options.previews = data;
}

/** 关闭窗口 */
function closeDialog() {
  options.img = userStore.avatar;
  options.visible = false;
}
</script>

<style lang='scss' scoped>
.user-info-head {
  position: relative;
  display: inline-block;
  height: 120px;
}

.avatar-trigger { position: relative; display: block; width: 120px; height: 120px; padding: 0; border: 0; border-radius: 50%; background: transparent; cursor: pointer; }
.avatar-trigger:hover:after {
  content: "+";
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  color: #eee;
  background: rgba(0, 0, 0, 0.5);
  font-size: 24px;
  font-style: normal;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  cursor: pointer;
  line-height: 110px;
  border-radius: 50%;
}
.avatar-actions { gap: 10px; }
.avatar-actions > .el-col { width: auto; max-width: none; flex: 0 0 auto; }
.avatar-preview-column { height: 350px; position: relative; display: grid; place-items: center; }
.avatar-preview-column .avatar-upload-preview { position: relative; top: auto; transform: none; box-shadow: none; border: 1px solid var(--sc-border); }
@media (max-width: 767px) { .avatar-preview-column { height: 230px; } }
</style>
