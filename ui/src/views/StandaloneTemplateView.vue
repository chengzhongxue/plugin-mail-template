<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { axiosInstance } from "@halo-dev/api-client";
import { VButton, Toast, Dialog } from "@halo-dev/components";
import { useQueryClient } from "@tanstack/vue-query";
import { Codemirror } from "vue-codemirror";
import { EditorView } from "@codemirror/view";
import { html } from "@codemirror/lang-html";
import { saveStandaloneTemplate, deleteCustomTemplate, type CustomTemplate } from "@/utils/custom-templates";

const props = defineProps<{ draft: CustomTemplate }>();
const emit = defineEmits<{ (event: "deleted"): void }>();
const queryClient = useQueryClient();
const fields = ref({ ...props.draft.data });
const busy = ref(false);
const recipient = ref("");
const rendered = ref<{ title: string; htmlBody: string; rawBody: string }>();
const editor = ref<EditorView>();
const expression = ref("order.number");
const tab = ref<"content" | "test" | "connect">("content");
const tabs = [
  { id: "content" as const, label: "1. 编写邮件" },
  { id: "test" as const, label: "2. 填参数并测试" },
  { id: "connect" as const, label: "3. 接入发送" },
];
const feedback = ref("");
const previewSnapshot = ref("");
const snapshot = computed(() => JSON.stringify([fields.value.title, fields.value.htmlBody, fields.value.rawBody, fields.value.sampleParameters, recipient.value]));
const previewOutdated = computed(() => !!rendered.value && previewSnapshot.value !== snapshot.value);
const dirty = computed(() => JSON.stringify(fields.value) !== JSON.stringify(props.draft.data));
const parsedParameters = computed(() => {
  try {
    const value = JSON.parse(fields.value.sampleParameters || "{}");
    if (!value || Array.isArray(value) || typeof value !== "object") return { error: "请填写 JSON 对象，例如 {\"order\": {\"number\": \"1001\"}}", value: {} };
    return { error: "", value: value as Record<string, unknown> };
  } catch { return { error: "示例数据格式有误，请检查引号、逗号和括号。", value: {} }; }
});
const parameterRows = computed(() => {
  const rows: { path: string; value: string }[] = [];
  function visit(value: unknown, path: string, depth: number) {
    if (rows.length >= 50 || depth > 16) return;
    if (value !== null && typeof value === "object") {
      Object.entries(value).forEach(([key, child]) => visit(child,
        Array.isArray(value) ? `${path}[${key}]` : path ? `${path}.${key}` : key, depth + 1));
    } else if (path !== "recipient") {
      rows.push({ path, value: value === null ? "null" : String(value) });
    }
  }
  visit(parsedParameters.value.value, "", 0);
  rows.push({ path: "recipient", value: recipient.value || "preview@example.com" });
  return rows;
});
const requestExample = computed(() => JSON.stringify({ recipient: recipient.value || "user@example.com", parameters: parsedParameters.value.value }, null, 2));
const extensions = [html(), EditorView.lineWrapping];
const endpoint = `/apis/api.mail.template.kunkunyu.com/v1alpha1/standalone-notifications/${props.draft.metadata.name}`;
watch(() => props.draft, value => { fields.value = { ...value.data }; });
function parameters() {
  if (parsedParameters.value.error) {
    tab.value = "test";
    throw new Error(parsedParameters.value.error);
  }
  return parsedParameters.value.value;
}
async function persist() {
  parameters();
  if (!fields.value.title?.trim()) throw new Error("邮件标题不能为空");
  await saveStandaloneTemplate(props.draft, fields.value);
  await queryClient.invalidateQueries({ queryKey: ["custom-mail-templates"] });
}
async function perform(action: "save" | "preview" | "send") {
  if (busy.value) return;
  busy.value = true;
  feedback.value = "";
  try {
    const input = parameters();
    if (action === "send" && (!recipient.value.trim() || fields.value.enabled !== "true")) {
      throw new Error("请填写收件邮箱并启用 API 发送");
    }
    await persist();
    if (action === "save") Toast.success("独立模板已保存");
    else if (action === "preview") {
      const { data } = await axiosInstance.post(`${endpoint}/preview`, { recipient: recipient.value.trim() || "preview@example.com", parameters: input });
      rendered.value = data;
      previewSnapshot.value = snapshot.value;
      tab.value = "test";
    } else {
      await axiosInstance.post(`${endpoint}/send`, { recipient: recipient.value.trim(), parameters: input });
      Toast.success("通知已提交，实际投递请查看 Halo 通知及邮件记录");
    }
  } catch (error) {
    const response = (error as { response?: { data?: { detail?: string } } })?.response;
    feedback.value = response?.data?.detail || (error instanceof Error ? error.message : "操作失败，请稍后重试");
    Toast.error(feedback.value);
  }
  finally { busy.value = false; }
}
function testSend() {
  Dialog.warning({ title: "发送测试邮件？", description: `将保存当前模板，并向 ${recipient.value || "所填邮箱"} 发送一封真实邮件。`,
    confirmText: "保存并发送", cancelText: "取消", onConfirm: () => perform("send") });
}
function remove() {
  Dialog.warning({ title: "删除独立模板？", description: "删除后该模板的 API 将无法继续发送。已经提交的通知可能仍会投递。", confirmType: "danger",
    confirmText: "删除", cancelText: "取消", onConfirm: async () => {
      await deleteCustomTemplate(props.draft);
      emit("deleted");
      await queryClient.invalidateQueries({ queryKey: ["custom-mail-templates"] });
    } });
}
function insert(path = expression.value) {
  if (!/^[A-Za-z_][\w]*(\.[A-Za-z_][\w]*|\[\d+\])*$/.test(path)) {
    Toast.error("请输入参数路径，例如 order.number 或 items[0].name"); return;
  }
  tab.value = "content";
  editor.value?.dispatch(editor.value.state.replaceSelection(`[[\${${path}}]]`));
  editor.value?.focus();
}
async function copy(value: string) {
  try { await navigator.clipboard.writeText(value); Toast.success("已复制"); }
  catch { Toast.error("复制失败，请选中文本手动复制"); }
}
</script>

<template>
  <div class="standalone-page">
    <header class="standalone-header">
      <div><h2>{{ draft.data.displayName }}</h2><p class="muted">独立通知 <span class="badge">{{ draft.data.enabled === 'true' ? '已启用发送' : '发送未启用' }}</span><span v-if="dirty" class="unsaved">有未保存的修改</span></p></div>
      <div class="actions"><VButton size="sm" :disabled="busy" @click="perform('preview')">保存并预览</VButton><VButton size="sm" type="primary" :loading="busy" @click="perform('save')">保存模板</VButton></div>
    </header>
    <div class="intro"><strong>先做好一封邮件，再让程序带着数据来发送。</strong><p>例如：你写好“订单号：<code v-pre>[[${order.number}]]</code>”，程序传入订单号 1001，收件人就会看到“订单号：1001”。</p><p>在这里先用示例数据试效果；正式使用时，需要脚本或其他插件调用发送接口。</p></div>
    <nav class="step-nav" aria-label="独立通知设置"><button v-for="item in tabs" :key="item.id" type="button" :class="{ selected: tab === item.id }" :aria-current="tab === item.id ? 'step' : undefined" @click="tab = item.id">{{ item.label }}</button></nav>
    <p v-if="feedback" role="alert" class="error-box">{{ feedback }}</p>

    <section v-show="tab === 'content'" class="content-grid">
      <div class="panel stack">
        <div><h3>邮件内容</h3><p class="muted">填写收件人看到的标题和正文，再用右侧参数替换需要变化的内容。</p></div>
        <label class="field">邮件标题<input v-model="fields.title" maxlength="10000" placeholder="例如：你的订单已付款" /></label>
        <div class="field"><span>邮件正文 <small class="muted">HTML</small></span><div class="code-editor"><Codemirror v-model="fields.htmlBody" :extensions="extensions" :style="{ height: '360px' }" @ready="editor = $event.view" /></div></div>
        <details><summary>纯文本备用正文（可选）</summary><p class="muted">用于显示纯文本邮件的客户端，也支持参数。</p><textarea v-model="fields.rawBody" rows="4" aria-label="纯文本备用正文" class="text-control mono" /></details>
        <div class="actions end"><VButton size="sm" @click="tab = 'test'">下一步：填参数并测试 →</VButton></div>
      </div>
      <aside class="panel stack">
        <div><h3>可以插入的参数</h3><p class="muted">根据第 2 步的示例数据生成。先点击正文中的位置，再点击“插入”。</p></div>
        <p v-if="parsedParameters.error" class="error-box">示例数据格式有误，请到第 2 步检查。</p>
        <div v-for="row in parameterRows" :key="row.path" class="parameter-row"><div class="parameter-info"><code>{{ row.path }}</code><span class="muted">{{ row.path === 'recipient' ? '本次收件邮箱 · 系统提供' : `示例：${row.value}` }}</span></div><VButton size="sm" @click="insert(row.path)">插入</VButton></div>
        <p class="muted">最多展示 50 个数据字段。新增字段后，这里的列表会同步更新。</p>
        <details><summary>手动输入参数路径</summary><div class="stack"><input v-model="expression" class="text-control mono" placeholder="例如 order.number" aria-label="手动参数路径" /><VButton size="sm" @click="insert()">插入到正文</VButton></div></details>
        <details><summary>参数使用示例</summary><p class="muted">参数是每次发送时可以变化的内容。以下两种写法都能显示订单号：</p><pre v-pre>[[${order.number}]]
&lt;span th:text="${order.number}"&gt;&lt;/span&gt;</pre><p class="muted">参数来自调用者传入的数据，其他插件的字段需要由调用程序获取后传入。</p></details>
      </aside>
    </section>

    <section v-show="tab === 'test'" class="test-grid">
      <div class="panel stack">
        <div><h3>用示例数据试一试</h3><p class="muted">这里的数据用于预览和测试邮件。正式发送时，程序会传入当次的实际数据。</p></div>
        <label class="field">示例数据（JSON）<textarea v-model="fields.sampleParameters" rows="8" class="mono" spellcheck="false" :aria-invalid="!!parsedParameters.error" /><span v-if="parsedParameters.error" class="error-text">{{ parsedParameters.error }}</span><span v-else class="muted">例如把 number 后的值改为“1002”，再点预览，邮件中的订单号就会跟着变化。</span></label>
        <label class="field">测试收件邮箱<input v-model="recipient" type="email" placeholder="填写你自己的邮箱" /><span class="muted">仅预览时可以不填；点击发送测试邮件时才会发出真实邮件。</span></label>
        <div class="actions"><VButton size="sm" type="primary" :disabled="busy || !!parsedParameters.error" @click="perform('preview')">保存并生成预览</VButton><VButton size="sm" :disabled="busy || !recipient || fields.enabled !== 'true' || !!parsedParameters.error" @click="testSend">保存并发送测试邮件</VButton></div>
        <p v-if="fields.enabled !== 'true'" class="hint">预览无需开启发送。要测试真实邮件，请先到 <button type="button" class="text-link" @click="tab = 'connect'">第 3 步启用发送</button>。</p>
        <label class="field">参数说明（选填，供自己或接入者查阅）<textarea v-model="fields.parameterDescription" maxlength="10000" rows="3" placeholder="例如：order.number 表示订单号，由订单插件提供。" /></label>
      </div>
      <div class="panel stack preview-panel">
        <div class="preview-heading"><h3>收件人看到的效果</h3><span class="badge">{{ rendered ? '上次生成的预览' : '尚未生成' }}</span></div>
        <template v-if="rendered"><p v-if="previewOutdated" class="hint">内容或数据已修改，请重新生成预览。</p><p class="preview-subject"><span class="muted">邮件标题</span><strong>{{ rendered.title }}</strong></p><iframe title="独立邮件预览" :srcdoc="rendered.htmlBody" sandbox="" referrerpolicy="no-referrer" /><details v-if="rendered.rawBody"><summary>查看纯文本效果</summary><pre>{{ rendered.rawBody }}</pre></details></template>
        <div v-else class="preview-empty"><span aria-hidden="true">✉</span><strong>预览会显示在这里</strong><p>填好左侧示例数据，点击“保存并生成预览”。<br />此操作会保存模板，不会发送邮件。</p></div>
      </div>
    </section>

    <section v-show="tab === 'connect'" class="panel stack">
      <div><h3>让脚本或插件发送这封邮件</h3><p class="muted">完成前两步后，把下方接入信息交给编写脚本或插件的人。每次调用时提供收件邮箱和参数，即可使用这份模板发送。</p></div>
      <div class="enable-box"><label class="check-label"><input v-model="fields.enabled" type="checkbox" true-value="true" false-value="false" /><strong>允许通过接口发送此模板</strong></label><p class="muted">勾选或取消后点击“保存模板”生效；测试发送也需要开启。关闭后仍可编辑和预览。</p><VButton size="sm" :disabled="busy" @click="perform('save')">保存发送设置</VButton></div>
      <div class="hint"><strong>调用前需要准备什么？</strong><ol><li>在 Halo 配置好邮件通知。</li><li>准备一个具备“独立模板预览与发送”权限的账号及个人访问令牌。</li><li>在服务端脚本或插件中调用下方接口，并传入实际数据。</li></ol></div>
      <details><summary>开发者接入信息：模板 ID、接口地址与请求示例</summary><div class="stack api-info">
        <div class="copy-row"><strong>模板 ID</strong><VButton size="sm" @click="copy(draft.metadata.name)">复制 ID</VButton></div><code class="code-block">{{ draft.metadata.name }}</code>
        <div class="copy-row"><strong>发送接口 · POST</strong><VButton size="sm" @click="copy(endpoint + '/send')">复制路径</VButton></div><code class="code-block">{{ endpoint }}/send</code><p class="muted">请求地址需在路径前加上你的博客域名。预览接口为相同路径的 /preview。</p>
        <strong>请求头</strong><pre>Authorization: Bearer &lt;Halo 个人访问令牌&gt;
Content-Type: application/json</pre>
        <div class="copy-row"><strong>请求体（根据当前示例数据生成）</strong><VButton size="sm" :disabled="!!parsedParameters.error" @click="copy(requestExample)">复制请求体</VButton></div><p v-if="parsedParameters.error" class="error-text">请先在第 2 步修正示例数据，才能生成有效的请求示例。</p><pre v-else>{{ requestExample }}</pre>
        <p class="muted">每分钟最多 30 次发送请求。返回 202 表示已提交给 Halo，实际投递结果请查看 Halo 通知及邮件记录。请勿在公开网页中暴露个人令牌。</p>
      </div></details>
      <details><summary>高级说明：参数来源与限制</summary><div class="stack"><p class="muted">支持嵌套对象和数组；recipient 始终为本次收件邮箱。site、subscriber 等数据需要调用者自行提供。示例数据和参数说明会保存到 Halo，请勿填入密码或令牌。</p><p class="muted">只支持单个收件邮箱。请求体最多 64 KiB，参数最多 48 KiB、16 层、2000 个节点。接口需要 Halo 身份认证与相应权限。</p></div></details>
      <div class="danger-zone"><div><strong>删除此模板</strong><p class="muted">删除后，程序将无法再使用这个模板 ID 发送邮件。</p></div><VButton size="sm" :disabled="busy" @click="remove">删除模板</VButton></div>
    </section>
  </div>
</template>

<style scoped>
.standalone-page { height: 100%; max-height: calc(100vh - 6rem); overflow-y: auto; padding: 20px; color: #1f2937; font-size: 14px; box-sizing: border-box; }
.standalone-page > * + * { margin-top: 18px; }
.standalone-page h2 { margin: 0 0 5px; font-size: 18px; font-weight: 600; }
.standalone-page h3 { margin: 0 0 6px; font-size: 15px; font-weight: 600; }
.standalone-page p { margin: 0; line-height: 1.7; }
.standalone-header, .actions, .preview-heading, .copy-row, .danger-zone { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.actions { justify-content: flex-start; }
.actions.end { justify-content: flex-end; }
.muted { color: #64748b; font-size: 13px; line-height: 1.65; }
.unsaved { margin-left: 10px; color: #a16207; font-size: 12px; }
.badge { display: inline-block; padding: 2px 8px; margin-left: 6px; background: #f1f5f9; color: #475569; border-radius: 20px; font-size: 12px; }
.intro { border: 1px solid #dbeafe; border-radius: 10px; background: #f0f7ff; padding: 14px 16px; }
.intro strong { color: #1e40af; }
.intro p { font-size: 13px; margin-top: 5px; color: #475569; }
.step-nav { display: flex; gap: 6px; padding: 5px; border-radius: 9px; background: #f1f5f9; }
.step-nav button { flex: 1; border: 0; background: transparent; color: #64748b; padding: 11px 8px; border-radius: 6px; cursor: pointer; font-size: 14px; }
.step-nav button.selected { background: #fff; color: #1d4ed8; font-weight: 600; box-shadow: 0 1px 4px #0f172a14; }
.step-nav button:focus-visible, .text-link:focus-visible { outline: 2px solid #2563eb; outline-offset: 2px; }
.content-grid { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 16px; align-items: start; }
.test-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.panel { border: 1px solid #e2e8f0; border-radius: 10px; padding: 18px; background: #fff; min-width: 0; }
.stack > * + * { margin-top: 16px; }
.field { display: flex; flex-direction: column; gap: 8px; font-size: 13px; font-weight: 500; }
.field input, .field textarea, .text-control { display: block; width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; background: #fff; border-radius: 6px; padding: 10px 12px; color: #1f2937; font-size: 14px; line-height: 1.6; }
.field input:focus, .field textarea:focus, .text-control:focus { outline: 2px solid #bfdbfe; border-color: #60a5fa; }
.field textarea, textarea.text-control { resize: vertical; }
.field .muted { font-weight: 400; }
.mono, pre, code { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
.code-editor { overflow: hidden; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; }
.parameter-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; border-bottom: 1px solid #f1f5f9; padding-bottom: 12px; }
.parameter-info { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.parameter-info code { font-size: 12px; color: #1d4ed8; overflow-wrap: anywhere; }
.parameter-info span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 180px; }
details { border-top: 1px solid #e2e8f0; padding-top: 14px; }
summary { cursor: pointer; color: #475569; font-size: 13px; line-height: 1.7; }
details[open] > summary { margin-bottom: 12px; }
pre, .code-block { display: block; padding: 12px; border: 1px solid #e2e8f0; border-radius: 6px; background: #f8fafc; font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
.hint, .error-box, .enable-box { padding: 12px 14px; border-radius: 7px; font-size: 13px; line-height: 1.7; }
.hint { background: #f8fafc; border: 1px solid #e2e8f0; color: #475569; }
.error-box { background: #fff1f2; color: #be123c; border: 1px solid #fecdd3; }
.error-text { color: #be123c; font-size: 13px; }
.text-link { color: #1d4ed8; cursor: pointer; background: transparent; border: 0; text-decoration: underline; }
.preview-panel iframe { display: block; background: #fff; width: 100%; height: 400px; border: 1px solid #e2e8f0; border-radius: 6px; }
.preview-subject { display: flex; flex-direction: column; gap: 5px; overflow-wrap: anywhere; }
.preview-empty { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px; min-height: 320px; text-align: center; color: #64748b; font-size: 13px; background: #f8fafc; border-radius: 7px; }
.preview-empty > span { font-size: 36px; color: #94a3b8; }
.enable-box { background: #f0f7ff; border: 1px solid #dbeafe; }
.enable-box p { margin: 8px 0 12px; }
.check-label { display: flex; align-items: center; gap: 8px; }
.check-label input { width: 16px; height: 16px; accent-color: #2563eb; }
.hint ol { margin: 8px 0 0; padding-left: 22px; list-style: decimal; }
.danger-zone { margin-top: 24px; border-top: 1px solid #fee2e2; padding-top: 18px; }
.danger-zone strong { color: #b91c1c; }
@media (max-width: 1200px) { .content-grid, .test-grid { grid-template-columns: minmax(0, 1fr); } .parameter-info span { max-width: 100%; } }
@media (max-width: 640px) { .standalone-page { padding: 12px; } .panel { padding: 14px; } .step-nav button { font-size: 12px; padding: 10px 4px; } }
</style>
