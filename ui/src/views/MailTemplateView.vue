<script lang="ts" setup>
import {
  coreApiClient, 
  type NotificationTemplate,
  type ReasonType,
} from "@halo-dev/api-client";
import { Toast, VEmpty, VLoading, VButton, VSpace } from "@halo-dev/components";
import {useMutation, useQuery, useQueryClient} from "@tanstack/vue-query";
import {computed, ref, toRefs, type Ref} from "vue";
import { EditorView } from "@codemirror/view";
import {Codemirror} from "vue-codemirror";
import type { LanguageSupport } from '@codemirror/language';
import { html } from '@codemirror/lang-html';
import TemplatePropertiesDetailModal from "@/views/TemplatePropertiesDetailModal.vue";
import { useEventListener,useLocalStorage } from "@vueuse/core";
import RiMenuUnfoldLine from '~icons/ri/menu-unfold-line?width=1.2em&height=1.2em';
import RiMenuFoldLine from '~icons/ri/menu-fold-line?width=1.2em&height=1.2em';

const props = withDefaults(
  defineProps<{ reasonType?: ReasonType }>(),
  {
    reasonType: undefined
  }
);

const queryClient = useQueryClient();
const showSidebar = useLocalStorage("plugin-mail-template:show-sidebar", true)

const Q_KEY = (name?: Ref<string | undefined>) => [
  "notification-template-value",
  name,
];

const isUpdate = ref(false);
const propertiesDetailModal = ref(false);

const { reasonType } = toRefs(props);

const reasonTypeName = computed(() => {
  return reasonType.value?.metadata.name;
});

const notificationTemplateOneName = computed(() => {
  return "template-one-"+reasonType.value?.metadata.name;
});


const languages: Record<string, LanguageSupport> = {
  html: html()
};



const formState = ref<NotificationTemplate>({
  apiVersion: "notification.halo.run/v1alpha1",
  kind: "NotificationTemplate",
  metadata: {
    name: ""
  },
  spec: {
    reasonSelector: {
      reasonType: "",
      language:"default"
    },
    template: {
      htmlBody: "",
      rawBody: "",
      title: ""
    }
  }
});

const htmlBody =ref<string>("");

const { data: value , isLoading} = useQuery({
  queryKey: Q_KEY(reasonTypeName),
  queryFn: async () => {
    if (!reasonType.value) return null;

    const { data } = await coreApiClient.notification.notificationTemplate.listNotificationTemplate(
      {
        fieldSelector: [
          `spec.reasonSelector.reasonType=${reasonTypeName.value}`,
        ]
      }
    )

    const notificationTemplate = data?.items.find((item) => item.metadata.name === notificationTemplateOneName.value);
    
    if (notificationTemplate) {
      isUpdate.value = true;
      return notificationTemplate;
    }else {
      isUpdate.value = false;
      return data?.items[0];
    }
  },
  onSuccess(data) {
    htmlBody.value =  data?.spec?.template?.htmlBody || ""
    if (data) {
      formState.value = data
    }
  },
  enabled: computed(() => !!reasonType.value),
});

const previewRef = ref(null);
const preview = ref(false);

const { mutate:save, isLoading:saveIsLoading } = useMutation({
  mutationKey: ["template-save"],
  mutationFn: async () => {
    if (isUpdate.value) {
      const { data: data } = await coreApiClient.notification.notificationTemplate.getNotificationTemplate({
        name: notificationTemplateOneName.value
      });
      formState.value = {
        ...data,
        spec: {
          reasonSelector: data.spec?.reasonSelector,
          template: {
            title: data.spec?.template?.title || "",
            rawBody: data.spec?.template?.rawBody,
            htmlBody: htmlBody.value,
            
          }
        }
      };
      return await coreApiClient.notification.notificationTemplate.updateNotificationTemplate({
        name: notificationTemplateOneName.value,
        notificationTemplate: formState.value
      });
    } else {
      const metadata  = formState.value.metadata;
      formState.value = {
        ...formState.value,
        spec: {
          reasonSelector: formState.value.spec?.reasonSelector,
          template: {
            title: formState.value.spec?.template?.title || "",
            rawBody: formState.value.spec?.template?.rawBody,
            htmlBody: htmlBody.value,
          }
        },
        metadata: {
          ...metadata,
          name: notificationTemplateOneName.value
        },
      };
      return await coreApiClient.notification.notificationTemplate.createNotificationTemplate({
        notificationTemplate: formState.value
      });
    }
    
  },
  onSuccess(data) {
    queryClient.invalidateQueries({
      queryKey: Q_KEY(notificationTemplateOneName),
    });
    formState.value = data.data
    Toast.success("保存成功");
  }
});

useEventListener("keydown", (e: KeyboardEvent) => {
  if (e.ctrlKey || e.metaKey) {
    if (e.key === "s") {
      e.preventDefault();
      save()
    }
  }
})

</script>

<template>
  <div
    class="sticky top-0 z-10 flex h-12 items-center justify-between space-x-3 border-b bg-white px-4"
  >
    <VSpace>
      <div :class="['inline-flex cursor-pointer items-center justify-center rounded p-1.5 transition-all hover:bg-gray-100',{'bg-gray-100': !showSidebar}]"
           @click="showSidebar = !showSidebar"
      >
        <RiMenuUnfoldLine v-if="showSidebar" />
        <RiMenuFoldLine v-else />
      </div>
      <h2 class="font-semibold text-gray-900">
        {{ reasonType?.spec?.displayName }}
      </h2>
    </VSpace>
    <VSpace>
      <VButton
        type="secondary"
        :loading="saveIsLoading"
        :cronIsLoading="isLoading"
        size="sm"
        @click="save"
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
  <div class="h-full w-full" style="height: calc(-11rem + 100vh);">
    <VLoading v-if="isLoading"></VLoading>
    <Transition
      v-else-if="!value"
      appear
      name="fade"
    >
      <VEmpty
        title="没有模板"
      ></VEmpty>
    </Transition>
    <Transition v-else name="fade" appear>
      <div class="h-full">
        <div class="flex h-10 items-center justify-between border-b px-2">
          <div class="flex gap-1 rounded"></div>
          <VSpace>
            <VButton
              v-if="reasonType"
              size="sm"
              @click="propertiesDetailModal = true"
            >
              模版参数
            </VButton>
            <VButton
              size="sm"
              @click="preview = !preview"
            >
              {{ preview ? "编辑" : "预览" }}
            </VButton>
          </VSpace>

        </div>
        <div class="h-full" v-show="!preview">
          <codemirror
            v-model="htmlBody"
            :style="{ height: '100%'}"
            :autofocus="true"
            :indent-with-tab="true"
            :tab-size="2"
            :extensions="[
              languages.html,
              EditorView.lineWrapping,
            ]"
          />
        </div>
        <div class="h-full" v-show="preview">
          <VLoading v-if="isLoading" />
          <div v-else
               ref="previewRef"
               class="preview line-numbers"
               v-html="htmlBody">
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>
