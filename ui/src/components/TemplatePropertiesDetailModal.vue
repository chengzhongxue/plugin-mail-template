<script lang="ts" setup>
import { computed } from "vue";
import { VModal, VButton } from "@halo-dev/components";
import type { ReasonType } from "@halo-dev/api-client";
const props = defineProps<{ reasonType: ReasonType; insertable?: boolean }>();
const emit = defineEmits<{ (event: "close"): void; (event: "insert", expression: string): void }>();
const systemAttributes = [
  { name: "site.title", type: "string", description: "站点标题" },
  { name: "site.subtitle", type: "string", description: "站点副标题" },
  { name: "site.logo", type: "string", description: "站点 Logo URL" },
  { name: "site.url", type: "string", description: "站点外部访问地址" },
  { name: "subscriber.displayName", type: "string", description: "订阅者显示名称" },
  { name: "subscriber.id", type: "string", description: "订阅者唯一标识符" },
  { name: "unsubscribeUrl", type: "string", description: "取消订阅链接" },
];
const groups = computed(() => [
  { title: "系统参数", items: systemAttributes },
  { title: "通知参数", items: props.reasonType.spec?.properties || [] },
]);
function valid(name: string) { return /^[A-Za-z_]\w*(\.[A-Za-z_]\w*)*$/.test(name); }
</script>
<template>
  <VModal :title="`${reasonType.spec?.displayName} 模板参数`" :width="900" :centered="false" @close="emit('close')">
    <p class=":uno: mb-3 text-sm text-gray-600">通知类型：{{ reasonType.metadata.name }}。参数由 Halo 和该通知来源提供，具体值取决于实际通知事件。</p>
    <p class=":uno: mb-3 text-sm text-gray-600">文本示例：<code>[[${site.title}]]</code>；HTML 属性示例：<code>th:text="${site.title}"</code>。插入按钮使用转义的文本表达式。</p>
    <section v-for="group in groups" :key="group.title" class=":uno: mb-4">
      <h3 class=":uno: mb-2 font-medium">{{ group.title }}</h3>
      <table class=":uno: w-full border-collapse text-left text-sm">
        <thead><tr class=":uno: border-b bg-gray-50"><th class=":uno: p-2">字段名</th><th class=":uno: p-2">类型</th><th class=":uno: p-2">说明</th><th v-if="insertable" class=":uno: p-2">操作</th></tr></thead>
        <tbody><tr v-for="item in group.items" :key="item.name" class=":uno: border-b">
          <td class=":uno: p-2"><code>{{ item.name }}</code></td><td class=":uno: p-2">{{ item.type }}</td><td class=":uno: p-2">{{ item.description }}</td>
          <td v-if="insertable" class=":uno: p-2"><VButton size="sm" :disabled="!valid(item.name)" @click="emit('insert', '[[' + '${' + item.name + '}' + ']]')">插入</VButton></td>
        </tr></tbody>
      </table>
      <p v-if="!group.items.length" class=":uno: p-2 text-sm text-gray-500">该通知未声明额外参数。</p>
    </section>
    <template #footer><VButton @click="emit('close')">关闭</VButton></template>
  </VModal>
</template>
