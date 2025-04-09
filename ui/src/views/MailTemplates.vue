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
  <VPageHeader title="模版管理">
    <template #icon>
      <FluentMailTemplate24Regular class="mr-2 self-center" />
    </template>
    <template #actions>
      <VButton size="sm" @click="$router.back()">
        返回
      </VButton>
    </template>
  </VPageHeader>

  <div class="m-0 md:m-4">
    <VCard
      style="height: calc(100vh - 5.5rem)"
      :body-class="['h-full', '!p-0']"
    >
      <div class="flex h-full divide-x">
        <div class="w-72 flex-none">
          <div
            class="sticky top-0 z-10 flex h-12 items-center border-b bg-white px-4"
          >
            <h2 class="font-semibold text-gray-900">
              模版
            </h2>
          </div>
          <ul
            class="box-border h-full w-full divide-y divide-gray-100 overflow-auto pb-12"
            role="list"
          >
            <li
              v-for="reasonType in reasonTypes?.items"
              :key="reasonType.metadata.name"
              class="relative cursor-pointer"
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
                class="absolute inset-y-0 left-0 w-0.5 bg-primary"
              ></div>
              <div
                class="flex flex-col space-y-1.5 px-4 py-2.5 hover:bg-gray-50"
              >
                <h3
                  class="line-clamp-1 break-words text-sm font-medium text-gray-900"
                >
                  {{ reasonType.spec?.displayName }}
                </h3>
                <p class="line-clamp-2 text-xs text-gray-600">
<!--                  {{ reasonType.spec?.description }}-->
                </p>
              </div>
            </li>
          </ul>
        </div>
        <div class="flex min-w-0 flex-1 shrink flex-col overflow-auto">
          <div
            class="sticky top-0 z-10 flex h-12 items-center space-x-3 border-b bg-white px-4"
          >
            <h2 class="font-semibold text-gray-900">
              {{ selectedReasonType?.spec?.displayName }}
            </h2>
          </div>
          <MailTemplateView
            :reason-type="selectedReasonType"
          />
        </div>
      </div>
    </VCard>
  </div>
</template>
