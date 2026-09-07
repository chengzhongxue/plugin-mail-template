<script lang="ts" setup>
import MailTemplateView from "@/views/MailTemplateView.vue";
import { coreApiClient, type ReasonType } from "@halo-dev/api-client";
import {
  Dialog,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPageHeader,
} from "@halo-dev/components";
import { useQuery } from "@tanstack/vue-query";
import { useLocalStorage, useMediaQuery } from "@vueuse/core";
import { useRouteQuery } from "@vueuse/router";
import { computed, ref, watch } from "vue";
import FluentMailTemplate24Regular from "~icons/fluent/mail-template-24-regular";

const fullscreenQuery = useRouteQuery<string | undefined>("fullscreen");
const fullscreen = computed({
  get: () => fullscreenQuery.value === "true",
  set: (value: boolean) => {
    fullscreenQuery.value = value ? "true" : undefined;
  },
});

const showSidebar = useLocalStorage("plugin-mail-template:show-sidebar", true);
const isMobile = useMediaQuery("(max-width: 639px)");
const editorDirty = ref(false);

const {
  data: reasonTypes,
  isLoading,
  isError,
  refetch,
} = useQuery<ReasonType[]>({
  queryKey: ["plugin-mail-template:reason-types"],
  queryFn: async () => {
    const { data } = await coreApiClient.notification.reasonType.listReasonType(
      {
        size: 1_000,
      },
    );
    return data.items.sort((left, right) => {
      const leftName = left.spec?.displayName || left.metadata.name;
      const rightName = right.spec?.displayName || right.metadata.name;
      return leftName.localeCompare(rightName, "zh-CN");
    });
  },
  retry: false,
  refetchOnWindowFocus: false,
});

const selectedReasonTypeName = useRouteQuery<string | undefined>(
  "notification-template-name",
);

const selectedReasonType = computed(() =>
  reasonTypes.value?.find(
    (item) => item.metadata.name === selectedReasonTypeName.value,
  ),
);

watch(
  reasonTypes,
  (items) => {
    if (!items) {
      return;
    }
    if (!items.length) {
      selectedReasonTypeName.value = undefined;
      return;
    }
    const selectionStillExists = items.some(
      (item) => item.metadata.name === selectedReasonTypeName.value,
    );
    if (!selectionStillExists) {
      selectedReasonTypeName.value = items[0]?.metadata.name;
    }
  },
  { immediate: true },
);

function selectReasonType(name: string) {
  if (name === selectedReasonTypeName.value) {
    if (isMobile.value) {
      showSidebar.value = false;
    }
    return;
  }

  const applySelection = () => {
    selectedReasonTypeName.value = name;
    if (isMobile.value) {
      showSidebar.value = false;
    }
  };

  if (!editorDirty.value) {
    applySelection();
    return;
  }

  Dialog.warning({
    title: "放弃未保存的修改吗？",
    description: "切换通知类型会丢弃当前编辑器中的未保存内容。",
    confirmType: "danger",
    confirmText: "放弃并切换",
    cancelText: "继续编辑",
    onConfirm: applySelection,
  });
}
</script>

<template>
  <div
    :class="{
      ':uno: fixed inset-0 z-999 overflow-hidden bg-white': fullscreen,
    }"
  >
    <VPageHeader
      title="模板管理"
      :class="{ ':uno: shadow transition-all': fullscreen }"
    >
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
        <div
          :class="[
            ':uno: grid h-full grid-cols-12 divide-y sm:divide-x sm:divide-y-0',
            { '!divide-none': !showSidebar },
          ]"
        >
          <aside
            v-show="showSidebar"
            class=":uno: relative col-span-12 h-full overflow-auto sm:col-span-4 md:col-span-5 lg:col-span-4 xl:col-span-3"
          >
            <div
              class=":uno: sticky top-0 z-10 flex h-12 items-center border-b bg-white px-4"
            >
              <h2 class=":uno: font-semibold text-gray-900">通知类型</h2>
            </div>

            <VLoading v-if="isLoading" />
            <div
              v-else-if="isError"
              class=":uno: flex h-40 flex-col items-center justify-center gap-3 px-4 text-sm text-gray-600"
            >
              <span>通知类型加载失败</span>
              <VButton size="sm" @click="refetch()">重试</VButton>
            </div>
            <VEmpty
              v-else-if="!reasonTypes?.length"
              title="没有可管理的通知类型"
            />
            <ul
              v-else
              class=":uno: box-border w-full divide-y divide-gray-100 overflow-auto pb-12"
              role="list"
            >
              <li
                v-for="reasonType in reasonTypes"
                :key="reasonType.metadata.name"
                class=":uno: relative"
              >
                <div
                  v-show="selectedReasonTypeName === reasonType.metadata.name"
                  class=":uno: absolute inset-y-0 left-0 w-0.5 bg-primary"
                />
                <button
                  type="button"
                  class=":uno: flex w-full flex-col space-y-1.5 border-0 bg-transparent px-4 py-2.5 text-left hover:bg-gray-50 focus-visible:outline-2 focus-visible:outline-primary"
                  :aria-current="
                    selectedReasonTypeName === reasonType.metadata.name
                      ? 'page'
                      : undefined
                  "
                  @click="selectReasonType(reasonType.metadata.name)"
                >
                  <h3
                    class=":uno: flex items-center gap-2 break-words text-sm font-medium text-gray-900"
                  >
                    <span class=":uno: line-clamp-1">
                      {{
                        reasonType.spec?.displayName || reasonType.metadata.name
                      }}
                    </span>
                    <span
                      v-if="
                        reasonType.metadata.labels?.['halo.run/hidden'] ===
                        'true'
                      "
                      class=":uno: shrink-0 rounded-full bg-amber-50 px-1.5 py-0.5 text-[10px] font-semibold text-amber-700"
                    >
                      系统消息
                    </span>
                  </h3>
                  <p class=":uno: line-clamp-2 text-xs text-gray-600">
                    {{ reasonType.spec?.description }}
                  </p>
                </button>
              </li>
            </ul>
          </aside>

          <main
            v-show="!isMobile || !showSidebar"
            :class="[
              ':uno: col-span-12 sm:col-span-8 md:col-span-7 lg:col-span-8 xl:col-span-9',
              { '!col-span-12': !showSidebar },
            ]"
          >
            <MailTemplateView
              :reason-type="selectedReasonType"
              @dirty-change="editorDirty = $event"
            />
          </main>
        </div>
      </VCard>
    </div>
  </div>
</template>
