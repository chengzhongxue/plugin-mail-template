<script lang="ts" setup>
import { coreApiClient } from "@halo-dev/api-client";
import {
  VButton,
  VCard,
  VPageHeader,
  Toast,
} from "@halo-dev/components";
import { useQuery, useQueryClient } from "@tanstack/vue-query";
import { useRouteQuery } from "@vueuse/router";
import { computed, ref, watch } from "vue";
import CreateCustomTemplateModal from "@/components/CreateCustomTemplateModal.vue";
import { listCustomTemplates, type CustomTemplate } from "@/utils/custom-templates";
import MailTemplateView from "@/views/MailTemplateView.vue";
import FluentMailTemplate24Regular from '~icons/fluent/mail-template-24-regular';
import {useLocalStorage} from "@vueuse/core";
import { loadOrder, saveOrder, mergeOrder, moveTemplate, ORDER_QUERY_KEY } from "@/utils/template-order";


const fullscreen = useRouteQuery("fullscreen", void 0, {
  transform: a => a ? a === "true" : undefined
})

const showSidebar = useLocalStorage("plugin-mail-template:show-sidebar", true)
const queryClient = useQueryClient();
const createModal = ref(false);
const selectedCustomName = useRouteQuery<string | undefined>("custom-template");
const { data: customTemplates } = useQuery({ queryKey: ["custom-mail-templates"], queryFn: listCustomTemplates });
const selectedCustom = computed(() => customTemplates.value?.find(item => item.metadata.name === selectedCustomName.value));
async function onCreated(draft: CustomTemplate) {
  createModal.value = false;
  await queryClient.invalidateQueries({ queryKey: ["custom-mail-templates"] });
  selectedCustomName.value = draft.metadata.name;
}

const { data: reasonTypes } = useQuery({
  queryKey: ["mail-template-reason-types"],
  queryFn: async () => {
    const { data } =
      await coreApiClient.notification.reasonType.listReasonType({ page: 0, size: 0 });
    return data;
  },
});

const selectedReasonTypeName = useRouteQuery<string | undefined>(
  "notification-template-name"
);

const selectedReasonType = computed(() => {
  return reasonTypes.value?.items.find(
    (item) => item.metadata.name === (selectedCustomName.value ? selectedCustom.value?.data.reasonType : selectedReasonTypeName.value)
  );
});

const { data: savedOrder, isLoading: orderLoading, isError: orderError } = useQuery({
  queryKey: ORDER_QUERY_KEY, queryFn: loadOrder, refetchOnWindowFocus: false,
});
const entries = computed(() => [
  ...(reasonTypes.value?.items || []).map(reason => ({
    id: `reason:${reason.metadata.name}`, name: reason.spec?.displayName || reason.metadata.name,
    reasonName: reason.metadata.name, customName: undefined as string | undefined,
  })),
  ...(customTemplates.value || []).map(draft => ({
    id: `custom:${draft.metadata.name}`, name: draft.data.displayName,
    reasonName: draft.data.reasonType, customName: draft.metadata.name,
  })),
]);
const initialOrder = computed(() => mergeOrder(savedOrder.value?.order || [], entries.value.map(item => item.id)));
const pendingOrder = ref<string[] | null>(null);
const effectiveOrder = computed(() => pendingOrder.value ? mergeOrder(pendingOrder.value, entries.value.map(item => item.id)) : initialOrder.value);
const orderedEntries = computed(() => {
  const byId = new Map(entries.value.map(item => [item.id, item]));
  return effectiveOrder.value.flatMap(id => byId.has(id) ? [byId.get(id)!] : []);
});
const orderDirty = computed(() => JSON.stringify(effectiveOrder.value) !== JSON.stringify(initialOrder.value));
const orderSaving = ref(false);
const dragging = ref<string>();
const dropTarget = ref<string>();
const dropAfter = ref(false);
function startDrag(event: DragEvent, id: string) {
  if (orderLoading.value || orderError.value || orderSaving.value) { event.preventDefault(); return; }
  dragging.value = id;
  if (event.dataTransfer) { event.dataTransfer.effectAllowed = "move"; event.dataTransfer.setData("text/plain", id); }
}
function dragOver(event: DragEvent, id: string) {
  if (!dragging.value || orderSaving.value) return;
  event.preventDefault();
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  dropTarget.value = id;
  dropAfter.value = event.clientY > rect.top + rect.height / 2;
  if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
}
function endDrag() { dragging.value = undefined; dropTarget.value = undefined; }
function drop(event: DragEvent, id: string) {
  if (!dragging.value || orderSaving.value) return;
  event.preventDefault();
  pendingOrder.value = moveTemplate(effectiveOrder.value, dragging.value, id, dropAfter.value);
  endDrag();
}
async function persistOrder() {
  if (!savedOrder.value || !orderDirty.value || orderSaving.value) return;
  orderSaving.value = true;
  try {
    await saveOrder([...effectiveOrder.value], savedOrder.value);
    await queryClient.invalidateQueries({ queryKey: ORDER_QUERY_KEY });
    pendingOrder.value = null;
    Toast.success("排序已保存");
  } finally { orderSaving.value = false; }
}

watch(
  () => reasonTypes.value,
  (value) => {
    if (value?.items.length && !selectedReasonTypeName.value) {
      selectedReasonTypeName.value =
        value?.items[0].metadata.name;
    }
  },
  {
    immediate: true,
  }
);
</script>

<template>
  <div :class="{':uno: fixed inset-0 z-999 bg-white' : fullscreen}">
    <VPageHeader title="模版管理" :class="{':uno: shadow transition-all' : fullscreen}">
      <template #icon>
        <FluentMailTemplate24Regular class=":uno: mr-2 self-center" />
      </template>
      <template #actions>
        <VButton size="sm" @click="fullscreen = !fullscreen">
          {{ fullscreen ? "退出全屏" : "全屏" }}
        </VButton>
      </template>
    </VPageHeader>

    <div class=":uno: m-0 md:m-4">
      <VCard
        style="height: calc(100vh - 5.5rem)"
        :body-class="['h-full', '!p-0']"
      >
        <div :class="[':uno: grid h-full grid-cols-12 divide-y sm:divide-x sm:divide-y-0',{'!divide-none' : !showSidebar}]">
          <div class=":uno: relative col-span-12 flex h-full min-h-0 flex-col sm:col-span-4 md:col-span-5 lg:col-span-4 xl:col-span-3" :style="`${!showSidebar ? 'display:none' : ''}`">
            <div
              class=":uno: flex h-12 shrink-0 items-center justify-between border-b bg-white px-4"
            >
              <h2 class=":uno: font-semibold text-gray-900">
                模版
              </h2>
              <span class=":uno: text-xs text-gray-500">拖动 ⋮⋮ 调整顺序</span>
            </div>
            <ul
              class=":uno: box-border min-h-0 w-full flex-1 divide-y divide-gray-100 overflow-y-auto"
              role="list"
            >
              <li
                v-for="entry in orderedEntries"
                :key="entry.id"
                class=":uno: relative cursor-pointer"
                :class="{ 'opacity-50': dragging === entry.id, 'mail-template-drop-before': dropTarget === entry.id && !dropAfter && dragging !== entry.id, 'mail-template-drop-after': dropTarget === entry.id && dropAfter && dragging !== entry.id }"
                @dragover="dragOver($event, entry.id)"
                @drop="drop($event, entry.id)"
                @click="selectedCustomName = entry.customName; selectedReasonTypeName = entry.reasonName"
              >
                <div
                  v-show="
                  entry.customName ? selectedCustomName === entry.customName : !selectedCustomName && selectedReasonTypeName === entry.reasonName
                "
                  class=":uno: absolute inset-y-0 left-0 w-0.5 bg-primary"
                ></div>
                <div
                  class=":uno: flex items-center gap-2 px-3 py-2.5 hover:bg-gray-50"
                >
                  <button type="button" class=":uno: shrink-0 cursor-grab rounded p-1 text-gray-400 hover:bg-gray-100"
                    :draggable="!orderLoading && !orderError && !orderSaving" :disabled="orderLoading || orderError || orderSaving"
                    :aria-label="`拖动排序：${entry.name}`" title="按住拖动排序"
                    @click.stop @dragstart.stop="startDrag($event, entry.id)" @dragend="endDrag">⋮⋮</button>
                  <div class=":uno: min-w-0 flex-1">
                  <h3
                    class=":uno: line-clamp-1 break-words text-sm font-medium text-gray-900"
                  >
                    {{ entry.name }}
                  </h3>
                  <p v-if="entry.customName" class=":uno: text-xs text-gray-500">自定义模板</p>
                  </div>
                </div>
              </li>
            </ul>
            <div class=":uno: shrink-0 border-t bg-white p-3 space-y-2">
              <p v-if="orderError" class=":uno: text-xs text-red-600">排序读取失败，暂时无法调整顺序。</p>
              <p v-else-if="orderDirty" class=":uno: text-xs text-gray-500">排序尚未保存</p>
              <div class=":uno: flex gap-2">
                <VButton size="sm" type="primary" :loading="orderSaving" :disabled="!orderDirty || orderLoading || orderError" @click="persistOrder">保存排序</VButton>
                <VButton size="sm" :disabled="!orderDirty || orderSaving" @click="pendingOrder = null; endDrag()">放弃修改</VButton>
              </div>
              <VButton class=":uno: w-full" :disabled="!reasonTypes?.items.length" @click="createModal = true">＋ 新增模板</VButton>
            </div>
          </div>
          <div :class="[':uno: col-span-12 sm:col-span-8 md:col-span-7 lg:col-span-8 xl:col-span-9',{'!col-span-12' : !showSidebar}]">
            <MailTemplateView
              v-if="!selectedCustomName || selectedCustom"
              :key="selectedCustomName || selectedReasonTypeName"
              :reason-type="selectedReasonType"
              :custom-template="selectedCustom"
              @deleted="selectedCustomName = undefined"
            />
          </div>
        </div>
      </VCard>
    </div>
  </div>
  <CreateCustomTemplateModal v-if="createModal" :reason-types="reasonTypes?.items || []" @close="createModal = false" @created="onCreated" />
  
</template>

<style scoped>
.mail-template-drop-before { box-shadow: inset 0 2px #22c55e; }
.mail-template-drop-after { box-shadow: inset 0 -2px #22c55e; }
</style>
