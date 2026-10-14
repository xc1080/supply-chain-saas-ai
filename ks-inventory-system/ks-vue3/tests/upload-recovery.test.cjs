const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const { parse } = require('@vue/compiler-sfc');
const { baseParse } = require('@vue/compiler-dom');

// Execute the component's own script and callbacks without a browser or a test framework dependency.
function fixture(component) {
  const source = fs.readFileSync(path.join(__dirname, '../src/components', component, 'index.vue'), 'utf8');
  const { descriptor, errors } = parse(source);
  assert.equal(errors.length, 0);
  const findUploader = nodes => {
    for (const node of nodes) {
      if (node.tag === 'el-upload') return node;
      const child = node.children && findUploader(node.children);
      if (child) return child;
    }
  };
  const uploaderNode = findUploader(baseParse(descriptor.template.content).children);
  const bindings = Object.fromEntries(uploaderNode.props.filter(prop => prop.type === 7 && prop.arg)
    .map(prop => [prop.arg.content, prop.exp?.content]));
  for (const callback of ['before-upload', 'on-success', 'on-error']) {
    assert.ok(bindings[callback], `The upload template must wire ${callback}`);
  }
  const script = descriptor.scriptSetup.content
    .replace(/^import .*;\r?\n/gm, '')
    .replaceAll('import.meta.env.VITE_APP_BASE_API', '"/dev-api"');
  const events = [];
  const calls = { opened: 0, closed: 0, errors: [], removed: [] };
  const uploader = { handleRemove: file => calls.removed.push(file) };
  const proxy = {
    $refs: { fileUpload: uploader, imageUpload: uploader },
    $modal: {
      loading: () => calls.opened++,
      closeLoading: () => calls.closed++,
      msgError: message => calls.errors.push(message),
    },
  };
  const context = {
    getToken: () => 'fixture-token',
    getCurrentInstance: () => ({ proxy }),
    defineProps: schema => Object.fromEntries(Object.entries(schema).map(([key, value]) => [key,
      key === 'modelValue' ? '' : typeof value.default === 'function' ? value.default() : value.default])),
    defineEmits: () => (...args) => events.push(args),
    ref: value => ({ value }),
    computed: callback => ({ get value() { return callback(); } }),
    watch: (getter, callback) => callback(getter()),
  };
  vm.createContext(context);
  vm.runInContext(script + `\nthis.api = {
    handleBeforeUpload: ${bindings['before-upload']},
    handleUploadSuccess: ${bindings['on-success']},
    handleUploadError: ${bindings['on-error']}, number, uploadList, fileList
  };`, context);
  return { ...context.api, calls, events };
}

const orders = [
  ['success', 'network', 'business'], ['success', 'business', 'network'],
  ['network', 'success', 'business'], ['network', 'business', 'success'],
  ['business', 'network', 'success'], ['business', 'success', 'network'],
  ['network', 'network', 'business'], ['business', 'business', 'business'],
];

for (const component of ['FileUpload', 'ImageUpload']) {
  test(`${component}: a single network error clears loading and pending work`, () => {
    const state = fixture(component);
    const extension = component === 'ImageUpload' ? 'png' : 'pdf';
    const file = { name: `upload.${extension}`, type: `image/${extension}`, size: 100 };
    state.handleBeforeUpload(file);
    state.handleUploadError(new Error('Network Error'), file);
    assert.equal(state.number.value, 0);
    assert.equal(state.calls.closed, 1);
    assert.equal(state.uploadList.value.length, 0);
  });
  for (const order of orders) {
    test(`${component}: ${order.join(', ')} settles and permits another batch`, () => {
      const state = fixture(component);
      const extension = component === 'ImageUpload' ? 'png' : 'pdf';
      const files = order.map((_, i) => ({ name: `upload-${i}.${extension}`, type: `image/${extension}`, size: 100, uid: i }));
      files.forEach(file => state.handleBeforeUpload(file));
      assert.equal(state.number.value, order.length);
      assert.equal(state.calls.opened, 1);
      order.forEach((outcome, i) => {
        if (outcome === 'network') state.handleUploadError(new Error('Network Error'), files[i]);
        else state.handleUploadSuccess(outcome === 'success'
          ? { code: 200, fileName: `/profile/success-${i}` }
          : { code: 500, msg: 'fixture failure' }, files[i]);
        assert.equal(state.number.value, order.length - i - 1);
        assert.equal(state.calls.closed, i === order.length - 1 ? 1 : 0);
      });
      assert.equal(state.uploadList.value.length, 0);
      assert.equal(state.fileList.value.length, order.filter(item => item === 'success').length);
      assert.equal(state.events.length, 1);
      assert.equal(state.calls.errors.length, order.filter(item => item !== 'success').length);
      state.handleBeforeUpload(files[0]);
      state.handleUploadSuccess({ code: 200, fileName: '/profile/retry' }, files[0]);
      assert.equal(state.number.value, 0);
      assert.equal(state.calls.opened, 2);
      assert.equal(state.calls.closed, 2);
      assert.ok(state.events.at(-1)[1].includes('/profile/retry'));
    });
  }
  test(`${component}: validation rejection does not leave pending work`, () => {
    const state = fixture(component);
    assert.equal(state.handleBeforeUpload({ name: 'invalid.exe', type: 'application/exe', size: 100 }), false);
    assert.equal(state.number.value, 0);
    assert.equal(state.calls.opened, 0);
  });
}

function sharingFixture() {
  const source = fs.readFileSync(path.join(__dirname, '../src/views/system/basedate/fileSharing.vue'), 'utf8');
  const script = parse(source).descriptor.scriptSetup.content
    .replace(/import\s+[\s\S]*?\s+from\s+["'][^"']+["'];?/g, '')
    .replaceAll('import.meta.env.VITE_APP_BASE_API', '"/dev-api"');
  const state = { uploadCalls: 0, listCalls: 0, messages: [], listFailure: false, uploadFailure: false };
  const context = {
    FormData,
    ref: value => ({ value }),
    reactive: value => value,
    toRefs: value => Object.fromEntries(Object.entries(value).map(([key, item]) => [key, { value: item }])),
    getCurrentInstance: () => ({ proxy: {
      addDateRange: value => value,
      $modal: { msgSuccess: message => state.messages.push(message) },
    } }),
    listFile: () => {
      state.listCalls++;
      return state.listFailure ? Promise.reject(new Error('list failure')) : Promise.resolve({ rows: [], total: 0 });
    },
    fileUpload: data => {
      state.uploadCalls++;
      state.uploadData = data;
      return state.uploadFailure ? Promise.reject(new Error('upload failure'))
        : Promise.resolve({ code: 200, fileId: 42, fileName: 'example.pdf', fileUrl: '/profile/example.pdf' });
    },
  };
  vm.createContext(context);
  vm.runInContext(script + '\nthis.api = { getList, uploadFile, loading, fileSharingList };', context);
  return Object.assign(state, context.api);
}

test('fileSharing: upload returns the saved identifier with one upload request', async () => {
  const state = sharingFixture();
  await state.getList();
  const before = state.listCalls;
  const response = await state.uploadFile({ file: new Blob(['example']) });
  assert.equal(response.fileId, 42);
  assert.equal(state.uploadCalls, 1);
  assert.equal(state.listCalls, before + 1);
  assert.equal(state.messages.length, 1);
  assert.ok(state.uploadData.get('file') instanceof Blob);
});

test('fileSharing: list failure releases loading and keeps the displayed list', async () => {
  const state = sharingFixture();
  await state.getList();
  const previous = [{ fileId: 5 }];
  state.fileSharingList.value = previous;
  state.listFailure = true;
  await state.getList();
  assert.equal(state.loading.value, false);
  assert.equal(state.fileSharingList.value, previous);
});

test('fileSharing: failed upload does not announce success and permits retry', async () => {
  const state = sharingFixture();
  await state.getList();
  const before = state.listCalls;
  state.uploadFailure = true;
  await assert.rejects(state.uploadFile({ file: new Blob(['example']) }), /upload failure/);
  assert.equal(state.messages.length, 0);
  assert.equal(state.listCalls, before);
  state.uploadFailure = false;
  assert.equal((await state.uploadFile({ file: new Blob(['retry']) })).fileId, 42);
});

test('fileSharing API keeps distinct FormData bodies as multipart uploads', async () => {
  const configs = [];
  const source = fs.readFileSync(path.join(__dirname, '../src/api/basedate/fileSharing.js'), 'utf8')
    .replace(/^import .*\r?\n/gm, '')
    .replaceAll('export function', 'function');
  const context = { request: config => { configs.push(config); return Promise.resolve(config); } };
  vm.createContext(context);
  vm.runInContext(source + '\nthis.upload = fileUpload;', context);
  const one = new FormData();
  const two = new FormData();
  one.append('file', new Blob(['one']));
  two.append('file', new Blob(['two']));
  await Promise.all([context.upload(one), context.upload(two)]);
  const axios = require('axios');
  for (let i = 0; i < configs.length; i++) {
    const config = configs[i];
    assert.equal(config.url, '/baseDate/fileSharing/upload');
    assert.equal(config.method, 'post');
    assert.equal(config.headers.repeatSubmit, false);
    const body = axios.defaults.transformRequest[0].call(axios.defaults, config.data, config.headers);
    assert.equal(body, i === 0 ? one : two);
  }
});
