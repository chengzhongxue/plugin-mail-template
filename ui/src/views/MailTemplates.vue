<script lang="ts" setup>
import { coreApiClient } from "@halo-dev/api-client";
import {
  VButton,
  VCard,
  VPageHeader,
} from "@halo-dev/components";
import { useQuery } from "@tanstack/vue-query";
import { useRouteQuery } from "@vueuse/router";
import { computed, watch } from "vue";
import MailTemplateView from "@/views/MailTemplateView.vue";
import FluentMailTemplate24Regular from '~icons/fluent/mail-template-24-regular';
import {useLocalStorage} from "@vueuse/core";


const fullscreen = useRouteQuery("fullscreen", void 0, {
  transform: a => a ? a === "true" : undefined
})

const showSidebar = useLocalStorage("plugin-mail-template:show-sidebar", true)

const { data: reasonTypes } = useQuery({
  queryKey: ["reason-types"],
  queryFn: async () => {
    const { data } =
      await coreApiClient.notification.reasonType.listReasonType();
    return data;
  },
});

const selectedReasonTypeName = useRouteQuery<string | undefined>(
  "notification-template-name"
);

const selectedReasonType = computed(() => {
  return reasonTypes.value?.items.find(
    (item) => item.metadata.name === selectedReasonTypeName.value
  );
});

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
          <div class=":uno: relative col-span-12 h-full overflow-auto sm:col-span-4 md:col-span-5 lg:col-span-4 xl:col-span-3" :style="`${!showSidebar ? 'display:none' : ''}`">
            <div
              class=":uno: sticky top-0 z-10 flex h-12 items-center border-b bg-white px-4"
            >
              <h2 class=":uno: font-semibold text-gray-900">
                模版
              </h2>
            </div>
            <ul
              class=":uno: box-border w-full divide-y divide-gray-100 overflow-auto pb-12"
              role="list"
            >
              <li
                v-for="reasonType in reasonTypes?.items"
                :key="reasonType.metadata.name"
                class=":uno: relative cursor-pointer"
                @click="
                selectedReasonTypeName =
                  reasonType.metadata.name
              "
              >
                <div
                  v-show="
                  selectedReasonTypeName ===
                  reasonType.metadata.name
                "
                  class=":uno: absolute inset-y-0 left-0 w-0.5 bg-primary"
                ></div>
                <div
                  class=":uno: flex flex-col space-y-1.5 px-4 py-2.5 hover:bg-gray-50"
                >
                  <h3
                    class=":uno: line-clamp-1 break-words text-sm font-medium text-gray-900"
                  >
                    {{ reasonType.spec?.displayName }}
                  </h3>
                  <p class=":uno: line-clamp-2 text-xs text-gray-600">
                    <!--                  {{ reasonType.spec?.description }}-->
                  </p>
                </div>
              </li>
            </ul>
          </div>
          <div :class="[':uno: col-span-12 sm:col-span-8 md:col-span-7 lg:col-span-8 xl:col-span-9',{'!col-span-12' : !showSidebar}]">
            <MailTemplateView
              :reason-type="selectedReasonType"
            />
          </div>
        </div>
      </VCard>
    </div>
  </div>
  
</template>
