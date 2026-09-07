<script lang="ts" setup>
import type { ListedMailTemplate } from "@/types";
import { html } from "@codemirror/lang-html";
import { EditorView } from "@codemirror/view";
import {
  axiosInstance,
  coreApiClient,
  paginate,
  type NotificationTemplate,
  type NotificationTemplateV1alpha1ApiListNotificationTemplateRequest,
  type ReasonType,
  type TemplateContent,
} from "@halo-dev/api-client";
import {
  Dialog,
  Toast,
  VButton,
  VEmpty,
  VLoading,
  VSpace,
} from "@halo-dev/components";
import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import { useEventListener, useLocalStorage, useMediaQuery } from "@vueuse/core";
import { computed, defineAsyncComponent, ref, toRefs, watch } from "vue";
import { Codemirror } from "vue-codemirror";
import { onBeforeRouteLeave } from "vue-router";
import RiMenuFoldLine from "~icons/ri/menu-fold-line?width=1.2em&height=1.2em";
import RiMenuUnfoldLine from "~icons/ri/menu-unfold-line?width=1.2em&height=1.2em";

const CUSTOM_TEMPLATE_PREFIX = "template-one-";
const MailTemplateStoreModal = defineAsyncComponent(
  () => import("@/components/MailTemplateStoreModal.vue"),
);
const TemplatePropertiesDetailModal = defineAsyncComponent(
  () => import("@/components/TemplatePropertiesDetailModal.vue"),
);

const props = withDefaults(defineProps<{ reasonType?: ReasonType }>(), {
  reasonType: undefined,
});
const emit = defineEmits<{
  "dirty-change": [value: boolean];
}>();

const { reasonType } = toRefs(props);
const queryClient = useQueryClient();
const showSidebar = useLocalStorage("plugin-mail-template:show-sidebar", true);
const isMobile = useMediaQuery("(max-width: 639px)");

const reasonTypeName = computed(() => reasonType.value?.metadata.name);
const queryKey = computed(() => [
  "plugin-mail-template:template",
  reasonTypeName.value,
]);

const isUpdate = ref(false);
const propertiesDetailModal = ref(false);
const openMailTemplateStoreModal = ref(false);
const activePanel = ref<"html" | "raw" | "preview">("html");
const draft = ref<TemplateContent>(emptyTemplate());
const original = ref<TemplateContent>(emptyTemplate());
const appliedReasonTypeName = ref<string>();
const appliedResourceVersion = ref<string>();

const isDirty = computed(() => !templatesEqual(draft.value, original.value));

interface LoadedTemplate {
  reasonTypeName: string;
  template: NotificationTemplate | null;
  custom: boolean;
}

const {
  data: loadedTemplate,
  isLoading,
  isFetching,
  isError,
  error: loadError,
  refetch,
} = useQuery<LoadedTemplate>({
  queryKey,
  enabled: computed(() => Boolean(reasonTypeName.value)),
  retry: false,
  refetchOnWindowFocus: false,
  queryFn: async ({ queryKey: currentQueryKey }) => {
    const requestedReasonType = currentQueryKey[1];
    if (typeof requestedReasonType !== "string" || !requestedReasonType) {
      throw new Error("未选择通知类型");
    }

    const templates = await paginate<
      NotificationTemplateV1alpha1ApiListNotificationTemplateRequest,
      NotificationTemplate
    >(
      (params) =>
        coreApiClient.notification.notificationTemplate.listNotificationTemplate(
          params,
        ),
      {
        fieldSelector: [
          `spec.reasonSelector.reasonType=${requestedReasonType}`,
        ],
        size: 100,
      },
    );

    const available = templates
      .filter((item) => !item.metadata.deletionTimestamp)
      .sort(compareByCreationTimeDescending);
    const custom = available.find((item) =>
      isCustomTemplate(item.metadata.name, requestedReasonType),
    );
    const fallback =
      available.find(
        (item) => item.spec?.reasonSelector?.language === "default",
      ) ?? available[0];

    return {
      reasonTypeName: requestedReasonType,
      template: custom ?? fallback ?? null,
      custom: Boolean(custom),
    };
  },
});

const selectedTemplate = computed(() => {
  const loaded = loadedTemplate.value;
  if (!loaded || loaded.reasonTypeName !== reasonTypeName.value) {
    return null;
  }
  return loaded.template;
});

const loadErrorMessage = computed(() =>
  loadError.value instanceof Error
    ? loadError.value.message
    : "请检查权限或网络连接",
);

watch(
  loadedTemplate,
  (loaded) => {
    if (!loaded || loaded.reasonTypeName !== reasonTypeName.value) {
      return;
    }
    const resourceVersion = resourceIdentity(loaded.template);
    if (resourceVersion === appliedResourceVersion.value) {
      return;
    }
    if (
      loaded.reasonTypeName === appliedReasonTypeName.value &&
      isDirty.value
    ) {
      return;
    }
    isUpdate.value = loaded.custom;
    applyTemplate(loaded.template?.spec?.template);
    appliedReasonTypeName.value = loaded.reasonTypeName;
    appliedResourceVersion.value = resourceVersion;
  },
  { immediate: true },
);

watch(isDirty, (value) => emit("dirty-change", value), { immediate: true });

interface SaveOptions {
  silent: boolean;
  reasonTypeName: string;
  template: TemplateContent;
}

interface PendingReasonStatus {
  count: number;
}

class SaveCancelledError extends Error {
  constructor() {
    super("用户取消了保存");
    this.name = "SaveCancelledError";
  }
}

const {
  mutate: save,
  mutateAsync: saveAsync,
  isLoading: saveIsLoading,
} = useMutation({
  mutationKey: ["plugin-mail-template:save"],
  mutationFn: async (options: SaveOptions) => {
    const endpoint = mailTemplateEndpoint(options.reasonTypeName);
    const normalizedTemplate = normalizeTemplate(options.template);
    await axiosInstance.post(`${endpoint}/validate`, normalizedTemplate);

    const { data: pendingStatus } =
      await axiosInstance.get<PendingReasonStatus>(
        `${endpoint}/pending-reasons`,
      );
    let allowPendingReplay = false;
    if (pendingStatus.count > 0) {
      allowPendingReplay = await confirmPendingReplay(pendingStatus.count);
      if (!allowPendingReplay) {
        throw new SaveCancelledError();
      }
    }

    const { data } = await axiosInstance.put<NotificationTemplate>(
      endpoint,
      normalizedTemplate,
      {
        params: { allowPendingReplay },
      },
    );
    return data;
  },
  onSuccess(saved, options) {
    const loaded: LoadedTemplate = {
      reasonTypeName: options.reasonTypeName,
      template: saved,
      custom: true,
    };

    if (options.reasonTypeName === reasonTypeName.value) {
      const savedTemplate = normalizeTemplate(
        saved.spec?.template ?? emptyTemplate(),
      );
      const unchangedSinceSubmit = templatesEqual(
        draft.value,
        options.template,
      );
      original.value = { ...savedTemplate };
      if (unchangedSinceSubmit) {
        draft.value = { ...savedTemplate };
      }
      isUpdate.value = true;
      appliedReasonTypeName.value = options.reasonTypeName;
      appliedResourceVersion.value = resourceIdentity(saved);
    }

    queryClient.setQueryData(
      ["plugin-mail-template:template", options.reasonTypeName],
      loaded,
    );
    if (!options.silent) {
      Toast.success("模板已保存并通过语法校验");
    }
  },
  onError(error) {
    if (error instanceof SaveCancelledError) {
      Toast.info("已取消保存，待处理通知不会被触发");
    }
  },
});

const { mutate: verifySend, isLoading: verifySendIsLoading } = useMutation({
  mutationKey: ["plugin-mail-template:verify-send"],
  mutationFn: async () => {
    const options = createSaveOptions(true);
    const saved = await saveAsync(options);
    try {
      return await axiosInstance.post(
        `${mailTemplateEndpoint(options.reasonTypeName)}/verify-send`,
        undefined,
        {
          params: {
            templateName: saved.metadata.name,
          },
        },
      );
    } catch (error) {
      Toast.warning("模板已保存，但测试通知执行失败");
      throw error;
    }
  },
  onSuccess() {
    Toast.success("测试通知已执行，请检查当前用户邮箱和 Halo 日志");
  },
});

const { mutateAsync: restoreAsync, isLoading: restoreIsLoading } = useMutation({
  mutationKey: ["plugin-mail-template:restore"],
  mutationFn: async (currentReasonType: string) => {
    await axiosInstance.delete(mailTemplateEndpoint(currentReasonType));
  },
  onSuccess: async (_data, currentReasonType) => {
    if (currentReasonType === reasonTypeName.value) {
      isUpdate.value = false;
      appliedReasonTypeName.value = undefined;
      appliedResourceVersion.value = undefined;
      await refetch();
    } else {
      await queryClient.invalidateQueries({
        queryKey: ["plugin-mail-template:template", currentReasonType],
      });
    }
    Toast.success("已恢复默认模板");
  },
});

const isOperationPending = computed(
  () =>
    saveIsLoading.value || verifySendIsLoading.value || restoreIsLoading.value,
);

function saveCurrentTemplate() {
  if (!isOperationPending.value && selectedTemplate.value) {
    save(createSaveOptions(false));
  }
}

function verifyCurrentTemplate() {
  if (!isOperationPending.value && selectedTemplate.value) {
    verifySend();
  }
}

function emptyTemplate(): TemplateContent {
  return { title: "", htmlBody: "", rawBody: "" };
}

function normalizeTemplate(template: TemplateContent): TemplateContent {
  return {
    title: template.title ?? "",
    htmlBody: template.htmlBody ?? "",
    rawBody: template.rawBody ?? "",
  };
}

function templatesEqual(left: TemplateContent, right: TemplateContent) {
  const normalizedLeft = normalizeTemplate(left);
  const normalizedRight = normalizeTemplate(right);
  return (
    normalizedLeft.title === normalizedRight.title &&
    normalizedLeft.htmlBody === normalizedRight.htmlBody &&
    normalizedLeft.rawBody === normalizedRight.rawBody
  );
}

function createSaveOptions(silent: boolean): SaveOptions {
  const currentReasonType = reasonTypeName.value;
  if (!currentReasonType || !selectedTemplate.value) {
    throw new Error("没有可保存的模板");
  }
  return {
    silent,
    reasonTypeName: currentReasonType,
    template: { ...draft.value },
  };
}

function applyTemplate(template?: TemplateContent) {
  const normalized = normalizeTemplate(template ?? emptyTemplate());
  draft.value = { ...normalized };
  original.value = { ...normalized };
}

function confirmPendingReplay(count: number): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false;
    const settle = (value: boolean) => {
      if (settled) {
        return;
      }
      settled = true;
      resolve(value);
    };

    Dialog.warning({
      uniqueId: "plugin-mail-template:pending-replay",
      title: `检测到 ${count} 条待处理通知`,
      description:
        "修复并保存模板后，Halo 可能立即补发这些历史通知。请先确认数量、时间范围和收件人；只有明确接受补发时才继续。",
      confirmType: "danger",
      confirmText: `允许补发并保存（${count} 条）`,
      cancelText: "取消保存",
      onConfirm: () => settle(true),
      onCancel: () => settle(false),
    });
  });
}

function restoreTemplate() {
  const currentReasonType = reasonTypeName.value;
  if (!isUpdate.value || !currentReasonType || isOperationPending.value) {
    return;
  }
  Dialog.warning({
    title: "确定要恢复默认模板吗？",
    description: "全部自定义模板版本和当前未保存内容都将被删除，无法撤销。",
    confirmType: "danger",
    confirmText: "恢复默认",
    cancelText: "取消",
    onConfirm: async () => {
      if (isOperationPending.value) {
        return;
      }
      await restoreAsync(currentReasonType);
    },
  });
}

useEventListener("keydown", (event: KeyboardEvent) => {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    saveCurrentTemplate();
  }
});

useEventListener("beforeunload", (event: BeforeUnloadEvent) => {
  if (isDirty.value) {
    event.preventDefault();
    event.returnValue = "";
  }
});

onBeforeRouteLeave(() => {
  if (!isDirty.value) {
    return true;
  }
  return window.confirm("当前模板还有未保存的修改，确定要离开吗？");
});

function handleTemplateSelect(selected: ListedMailTemplate) {
  draft.value.htmlBody = selected.mailTemplate.spec.htmlBody || "";
  openMailTemplateStoreModal.value = false;
}

function getIframeContent() {
  return `<!doctype html>${draft.value.htmlBody || ""}`;
}

function mailTemplateEndpoint(currentReasonType: string) {
  return `/apis/api.mail.template.kunkunyu.com/v1alpha1/mailtemplates/${encodeURIComponent(currentReasonType)}`;
}

function isCustomTemplate(name: string, currentReasonType: string) {
  const baseName = CUSTOM_TEMPLATE_PREFIX + currentReasonType;
  return name === baseName || name.startsWith(`${baseName}-`);
}

function compareByCreationTimeDescending(
  left: NotificationTemplate,
  right: NotificationTemplate,
) {
  const leftTime = Date.parse(left.metadata.creationTimestamp ?? "");
  const rightTime = Date.parse(right.metadata.creationTimestamp ?? "");
  return (
    (Number.isNaN(rightTime) ? 0 : rightTime) -
    (Number.isNaN(leftTime) ? 0 : leftTime)
  );
}

function resourceIdentity(template: NotificationTemplate | null) {
  if (!template) {
    return "none";
  }
  return `${template.metadata.name}:${template.metadata.version ?? ""}`;
}
</script>

<template>
  <div class="mail-template-toolbar">
    <VSpace>
      <button
        class=":uno: inline-flex cursor-pointer items-center justify-center rounded border-0 bg-transparent p-1.5 transition-all hover:bg-gray-100"
        :class="{ 'bg-gray-100': !showSidebar }"
        type="button"
        :aria-label="
          isMobile ? '返回通知类型' : showSidebar ? '收起侧边栏' : '展开侧边栏'
        "
        @click="showSidebar = !showSidebar"
      >
        <RiMenuFoldLine v-if="showSidebar" />
        <RiMenuUnfoldLine v-else />
      </button>
      <h2 class=":uno: font-semibold text-gray-900">
        {{ reasonType?.spec?.displayName || "邮件模板" }}
      </h2>
      <span
        class="mail-template-mode-pill"
        :class="isUpdate ? 'is-custom' : 'is-default'"
      >
        <span class="mail-template-mode-dot" />
        {{ isUpdate ? "自定义模板" : "默认模板" }}
      </span>
      <span v-if="isDirty" class=":uno: text-xs text-amber-600">未保存</span>
    </VSpace>

    <VSpace>
      <VButton
        type="primary"
        :loading="verifySendIsLoading"
        :disabled="!selectedTemplate || isOperationPending"
        size="sm"
        @click="verifyCurrentTemplate"
      >
        保存并测试
      </VButton>
      <VButton
        :loading="restoreIsLoading"
        :disabled="!isUpdate || isOperationPending"
        size="sm"
        @click="restoreTemplate"
      >
        恢复默认
      </VButton>
      <VButton
        type="secondary"
        :loading="saveIsLoading"
        :disabled="!selectedTemplate || isOperationPending"
        size="sm"
        @click="saveCurrentTemplate"
      >
        保存
      </VButton>
    </VSpace>
  </div>

  <TemplatePropertiesDetailModal
    v-if="propertiesDetailModal && reasonType"
    :reason-type="reasonType"
    @close="propertiesDetailModal = false"
  />
  <MailTemplateStoreModal
    v-if="openMailTemplateStoreModal && reasonType"
    :reason-type="reasonType"
    @close="openMailTemplateStoreModal = false"
    @select="handleTemplateSelect"
  />

  <div
    class=":uno: h-full w-full mail-template-workspace"
    style="height: calc(100vh - 11rem)"
  >
    <VLoading v-if="isLoading || (isFetching && !selectedTemplate)" />
    <div
      v-else-if="isError"
      class=":uno: flex h-56 flex-col items-center justify-center gap-3 px-6 text-center"
    >
      <VEmpty title="模板加载失败" :message="loadErrorMessage" />
      <VButton size="sm" @click="refetch()">重新加载</VButton>
    </div>
    <VEmpty v-else-if="!selectedTemplate" title="没有可编辑的模板" />
    <div v-else class=":uno: flex h-full flex-col">
      <div
        class=":uno: flex min-h-10 items-center justify-between gap-3 border-b px-2"
      >
        <input
          v-model="draft.title"
          aria-label="模板标题"
          placeholder="输入模板标题"
          class=":uno: w-full max-w-xl border-0 bg-transparent px-1 text-sm outline-none"
        />
        <VSpace>
          <VButton
            v-if="reasonType"
            size="sm"
            type="primary"
            @click="openMailTemplateStoreModal = true"
          >
            模板市场
          </VButton>
          <VButton
            v-if="reasonType"
            size="sm"
            @click="propertiesDetailModal = true"
          >
            模板参数
          </VButton>
        </VSpace>
      </div>

      <div
        class=":uno: flex h-10 items-center gap-1 border-b px-2 mail-template-editor-tabs"
      >
        <VButton
          size="sm"
          :type="activePanel === 'html' ? 'secondary' : 'default'"
          @click="activePanel = 'html'"
        >
          HTML
        </VButton>
        <VButton
          size="sm"
          :type="activePanel === 'raw' ? 'secondary' : 'default'"
          @click="activePanel = 'raw'"
        >
          纯文本
        </VButton>
        <VButton
          size="sm"
          :type="activePanel === 'preview' ? 'secondary' : 'default'"
          @click="activePanel = 'preview'"
        >
          预览
        </VButton>
        <span class=":uno: ml-auto text-xs text-gray-500">
          保存前会使用 Halo 2.26 的 Thymeleaf 引擎校验语法
        </span>
      </div>

      <div v-show="activePanel === 'html'" class=":uno: min-h-0 flex-1">
        <Codemirror
          v-model="draft.htmlBody"
          :style="{ height: '100%' }"
          :autofocus="true"
          :indent-with-tab="true"
          :tab-size="2"
          :extensions="[html(), EditorView.lineWrapping]"
        />
      </div>
      <div v-show="activePanel === 'raw'" class=":uno: min-h-0 flex-1">
        <Codemirror
          v-model="draft.rawBody"
          :style="{ height: '100%' }"
          :indent-with-tab="true"
          :tab-size="2"
          :extensions="[EditorView.lineWrapping]"
        />
      </div>
      <div v-show="activePanel === 'preview'" class=":uno: min-h-0 flex-1">
        <iframe
          class=":uno: h-full w-full border-0"
          :srcdoc="getIframeContent()"
          sandbox=""
          referrerpolicy="no-referrer"
          title="邮件模板预览"
        />
      </div>
    </div>
  </div>
</template>
