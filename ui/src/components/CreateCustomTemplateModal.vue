<script setup lang="ts">
import { computed, ref } from "vue";
import { VModal, VButton, VSpace, Toast } from "@halo-dev/components";
import type { ReasonType } from "@halo-dev/api-client";
import TemplatePropertiesDetailModal from "./TemplatePropertiesDetailModal.vue";
import { createCustomTemplate, type CustomTemplate } from "@/utils/custom-templates";

const props = defineProps<{ reasonTypes: ReasonType[] }>();
const emit = defineEmits<{ (event: "close"): void; (event: "created", draft: CustomTemplate): void }>();
const name = ref("");
const source = ref(props.reasonTypes[0]?.metadata.name ?? "");
const selected = computed(() => props.reasonTypes.find(item => item.metadata.name === source.value));
const showParameters = ref(false);
const busy = ref(false);
async function create() {
  if (!name.value.trim() || !selected.value || busy.value) return;
  busy.value = true;
  try {
    const draft = await createCustomTemplate(name.value, source.value);
    Toast.success("自定义模板已创建");
    emit("created", draft);
  } finally { busy.value = false; }
}
</script>

<template>
  <VModal title="新增自定义模板" :width="640" @close="emit('close')">
    <form id="create-custom-template" class=":uno: space-y-4" @submit.prevent="create">
      <label class=":uno: block">模板名称
        <input v-model="name" required maxlength="100" class=":uno: mt-2 w-full rounded border p-2" placeholder="例如：我的评论回复样式" />
      </label>
      <label class=":uno: block">通知类型与参数来源
        <select v-model="source" required class=":uno: mt-2 w-full rounded border p-2">
          <option v-for="reason in reasonTypes" :key="reason.metadata.name" :value="reason.metadata.name">{{ reason.spec?.displayName }}</option>
        </select>
      </label>
      <p class=":uno: text-sm text-gray-600">复制该类型当前的邮件样式，继承系统参数及该通知类型的参数。保存为独立模板后，可编辑并应用到该通知。</p>
      <VButton v-if="selected" size="sm" @click="showParameters = true">查看可用参数</VButton>
    </form>
    <template #footer><VSpace>
      <VButton @click="emit('close')">取消</VButton>
      <VButton type="primary" :loading="busy" :disabled="!name.trim() || !selected" @click="create">创建模板</VButton>
    </VSpace></template>
  </VModal>
  <TemplatePropertiesDetailModal v-if="showParameters && selected" :reason-type="selected" @close="showParameters = false" />
</template>
