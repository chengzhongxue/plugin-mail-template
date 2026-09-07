<script lang="ts" setup>
import { ref, watch } from "vue";
import {
  VModal,
  VSpace,
  VButton,
  VCard,
  VEmpty,
  IconRefreshLine,
  IconCheckboxFill,
  VPagination,
  VLoading,
} from "@halo-dev/components";
import type { ReasonType } from "@halo-dev/api-client";
import { useRouteQuery } from "@vueuse/router";
import type { ListedMailTemplateList, ListedMailTemplate } from "@/types";
import { useQuery } from "@tanstack/vue-query";
import LazyImage from "@/components/LazyImage.vue";
import storeApiClient, { TEMPLATE_STORE_URL } from "@/utils/store-api-client";
import { timeAgo } from "../utils/date";
import HtmlViewModal from "@/components/HtmlViewModal.vue";

const props = withDefaults(
  defineProps<{
    reasonType: ReasonType;
  }>(),
  {},
);

const emit = defineEmits<{
  (event: "close"): void;
  (event: "select", template: ListedMailTemplate): void; // 添加这行
}>();
const modal = ref<InstanceType<typeof VModal> | null>(null);

const allowedImageHosts = [
  "api.minio.yyds.pink",
  "ccxky.com",
  "cdn.baiwumm.com",
  "images.6uu.us",
  "img.timxs.com",
  "lsky.master-jsx.top",
  "oss.asteri5m.icu",
  "s2.loli.net",
  "www.hcjike.com",
  "www.wjlin0.com",
  "www.yunext.cn",
];

const selectedMailTemplate = ref<ListedMailTemplate | undefined>();

const page = useRouteQuery<number>("template-store-page", 1, {
  transform: Number,
});
const size = useRouteQuery<number>("template-store-size", 20, {
  transform: Number,
});
const keyword = useRouteQuery<string>("template-store-keyword", "");
const total = ref(0);

watch(
  () => [keyword.value],
  () => {
    page.value = 1;
    selectedMailTemplate.value = undefined;
  },
);

watch(
  () => [page.value, size.value],
  () => {
    selectedMailTemplate.value = undefined;
  },
);

const {
  data: mailTemplates,
  isLoading,
  isFetching,
  isError,
  refetch,
} = useQuery<ListedMailTemplate[]>({
  queryKey: [
    "plugin:mail:template:data",
    page,
    size,
    keyword,
    props.reasonType?.metadata.name,
  ],
  queryFn: async () => {
    if (!props.reasonType?.metadata.name) {
      return [];
    }
    const { data } = await storeApiClient.get<ListedMailTemplateList>(
      "/apis/anonymous.api.templatestore.kunkunyu.com/v1alpha1/mailtemplates",
      {
        params: {
          page: page.value,
          size: size.value,
          keyword: keyword.value,
          groupName: props.reasonType?.metadata.name,
        },
      },
    );
    total.value = data.total;
    return data.items;
  },
  retry: false,
  refetchInterval(data) {
    const hasDeletingGroup = data?.some(
      (mailTemplate) => !!mailTemplate.mailTemplate.metadata.deletionTimestamp,
    );
    return hasDeletingGroup ? 1000 : false;
  },
  refetchOnWindowFocus: false,
});

const isChecked = (mailTemplate: ListedMailTemplate) => {
  return (
    mailTemplate.mailTemplate.metadata.name ===
    selectedMailTemplate.value?.mailTemplate.metadata.name
  );
};

const handleSelect = () => {
  if (selectedMailTemplate.value) {
    emit("select", selectedMailTemplate.value);
    modal.value?.close();
  }
};

const htmlContent = ref("");
const htmlTitle = ref("");
const openHtmlViewModal = ref(false);

const onHtmlViewModalClose = () => {
  openHtmlViewModal.value = false;
};

const handleOpenHtmlViewModal = (content: string, title: string) => {
  htmlContent.value = content || "";
  htmlTitle.value = title;
  openHtmlViewModal.value = true;
};

function toMailTemplate() {
  window.open(
    `${TEMPLATE_STORE_URL}/mail-template`,
    "_blank",
    "noopener,noreferrer",
  );
}
</script>
<template>
  <HtmlViewModal
    v-if="openHtmlViewModal"
    :title="htmlTitle"
    :content="htmlContent"
    @close="onHtmlViewModalClose"
  />
  <VModal
    mount-to-body
    layer-closable
    ref="modal"
    title="模板市场"
    :width="1200"
    height="calc(-20px + 100vh)"
    :centered="false"
    @close="emit('close')"
  >
    <div>
      <div class=":uno: block w-full bg-gray-50 px-4 py-3">
        <div
          class=":uno: relative flex flex-col flex-wrap items-start gap-4 sm:flex-row sm:items-center"
        >
          <div class=":uno: flex w-full flex-1 items-center sm:w-auto">
            <SearchInput v-model="keyword" />
          </div>
          <VSpace spacing="lg" class=":uno: flex-wrap">
            <VButton
              v-tooltip="{
                content: '跳转到模板市场',
                delay: 300,
              }"
              type="default"
              size="sm"
              @click="toMailTemplate"
            >
              提交模板
            </VButton>
            <div class=":uno: flex flex-row gap-2">
              <button
                type="button"
                aria-label="刷新模板市场"
                class=":uno: group cursor-pointer rounded border-0 bg-transparent p-1 hover:bg-gray-200 focus-visible:outline-2 focus-visible:outline-primary"
                @click="refetch()"
              >
                <IconRefreshLine
                  v-tooltip="'刷新'"
                  :class="{ ':uno: animate-spin text-gray-900': isFetching }"
                  class=":uno: h-4 w-4 text-gray-600 group-hover:text-gray-900"
                />
              </button>
            </div>
          </VSpace>
        </div>
      </div>
      <div class=":uno: mt-3">
        <VCard>
          <VLoading v-if="isLoading" />
          <Transition v-else-if="isError" appear name="fade">
            <div
              class=":uno: flex min-h-48 flex-col items-center justify-center gap-3"
            >
              <VEmpty title="模板市场加载失败" />
              <VButton size="sm" @click="refetch()">重新加载</VButton>
            </div>
          </Transition>
          <Transition v-else-if="!mailTemplates?.length" appear name="fade">
            <VEmpty title="当前没有模板"> </VEmpty>
          </Transition>
          <Transition v-else appear name="fade">
            <div
              class=":uno: grid grid-cols-1 mt-2 gap-x-2 gap-y-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
              role="list"
            >
              <VCard
                v-for="mailTemplate in mailTemplates"
                :key="mailTemplate.mailTemplate.metadata.name"
                :body-class="[':uno: !p-0']"
                :class="{
                  ':uno: ring-primary ring-1': isChecked(mailTemplate),
                }"
                class=":uno: hover:shadow drag-element"
              >
                <div class=":uno: group relative bg-white">
                  <button
                    type="button"
                    :aria-pressed="isChecked(mailTemplate)"
                    :aria-label="`选择模板：${mailTemplate.mailTemplate.spec.displayName}`"
                    class=":uno: relative block aspect-16/9 size-full cursor-pointer overflow-hidden border-0 bg-gray-100 p-0 focus-visible:outline-2 focus-visible:outline-primary"
                    @click="
                      isChecked(mailTemplate)
                        ? (selectedMailTemplate = undefined)
                        : (selectedMailTemplate = mailTemplate)
                    "
                  >
                    <LazyImage
                      :key="mailTemplate.mailTemplate.metadata.name"
                      :alt="mailTemplate.mailTemplate.spec.displayName"
                      :src="mailTemplate.mailTemplate.spec.cover || ''"
                      :allowed-hosts="allowedImageHosts"
                      classes=":uno: size-full pointer-events-none group-hover:opacity-75"
                    >
                      <template #loading>
                        <div class=":uno: h-full flex justify-center">
                          <VLoading></VLoading>
                        </div>
                      </template>
                      <template #error>
                        <div
                          class=":uno: h-full flex items-center justify-center object-cover"
                        >
                          <span class=":uno: text-xs text-red-400">
                            加载异常
                          </span>
                        </div>
                      </template>
                    </LazyImage>
                  </button>

                  <div class=":uno: p-2 flex flex-col h-full">
                    <div>
                      <div class=":uno: flex items-center justify-between mb-1">
                        <h3 class=":uno: text-sm font-medium text-gray-900">
                          {{ mailTemplate.mailTemplate.spec.displayName }}
                        </h3>
                        <span class=":uno: text-sm text-gray-500">
                          {{
                            timeAgo(
                              mailTemplate.mailTemplate.metadata
                                .creationTimestamp || "",
                            )
                          }}</span
                        >
                      </div>
                      <p
                        v-if="mailTemplate.mailTemplate.spec.description"
                        class=":uno: overflow-hidden text-ellipsis whitespace-nowrap text-xs leading-4 text-slate-500 font-normal !line-clamp-2"
                      >
                        {{ mailTemplate.mailTemplate.spec.description }}
                      </p>
                    </div>
                    <div class=":uno: flex flex-col mt-auto">
                      <div
                        class=":uno: mt-1 w-full flex flex-1 items-center justify-between gap-2"
                        v-if="mailTemplate.owner"
                      >
                        <div class=":uno: inline-flex items-center gap-1.5">
                          <LazyImage
                            v-if="mailTemplate.owner?.avatar"
                            :src="mailTemplate.owner?.avatar"
                            :alt="mailTemplate.owner?.displayName || '作者头像'"
                            :allowed-hosts="allowedImageHosts"
                            classes=":uno: size-5 overflow-hidden rounded-full bg-gray-100"
                          >
                            <template #error>
                              <span
                                class=":uno: flex size-full items-center justify-center text-xs text-gray-400"
                              >
                                ·
                              </span>
                            </template>
                          </LazyImage>
                          <span class=":uno: text-xs text-gray-700">
                            {{ mailTemplate.owner?.displayName }}
                          </span>
                        </div>
                        <div class=":uno: inline-flex items-center gap-1">
                          <VButton
                            type="primary"
                            size="sm"
                            @click="
                              handleOpenHtmlViewModal(
                                mailTemplate.mailTemplate.spec.htmlBody,
                                mailTemplate.mailTemplate.spec.displayName,
                              )
                            "
                          >
                            预览
                          </VButton>
                        </div>
                      </div>
                    </div>
                  </div>

                  <div
                    v-if="!mailTemplate.mailTemplate.metadata.deletionTimestamp"
                    v-permission="['plugin:mail-template:manage']"
                    :class="{ ':uno: !flex': isChecked(mailTemplate) }"
                    class=":uno: pointer-events-none absolute left-0 top-0 hidden h-1/3 w-full justify-end from-gray-300 to-transparent bg-gradient-to-b ease-in-out group-hover:flex"
                  >
                    <IconCheckboxFill
                      :class="{
                        ':uno: !text-primary': isChecked(mailTemplate),
                      }"
                      class=":uno: hover:text-primary mr-1 mt-1 h-6 w-6 text-white transition-all"
                    />
                  </div>
                </div>
              </VCard>
            </div>
          </Transition>

          <template #footer>
            <VPagination
              v-model:page="page"
              v-model:size="size"
              :total="total"
              :size-options="[20, 30, 50, 100]"
            />
          </template>
        </VCard>
      </div>
    </div>
    <template #footer>
      <VSpace>
        <VButton
          type="secondary"
          :disabled="selectedMailTemplate == undefined"
          @click="handleSelect"
        >
          选择
        </VButton>
        <VButton @click="modal?.close()"> 关闭 </VButton>
      </VSpace>
    </template>
  </VModal>
</template>
