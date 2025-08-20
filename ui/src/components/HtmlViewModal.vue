<script lang="ts" setup>
import {
  VModal,
  VButton,
  VSpace,
  VTabs,
  VTabItem
} from "@halo-dev/components";
import {computed, ref} from "vue";
import {EditorView} from "@codemirror/view";
import {Codemirror} from "vue-codemirror";
import type {LanguageSupport} from "@codemirror/language";
import {html} from "@codemirror/lang-html";

const props = withDefaults(
  defineProps<{
    content: string;
    title: string;
  }>(),
  {
  }
);

const emit = defineEmits<{
  (event: "close"): void;
}>();

const modal = ref<InstanceType<typeof VModal> | null>(null);
const activeTabId = ref("preview");

const getIframeContent = () => {
  return `
    <!DOCTYPE html>
    ${props.content || ''}
  `;
};

const languages: Record<string, LanguageSupport> = {
  html: html()
};



const iframeHeight = computed(() => {
  // 计算可用高度：Modal 高度 - header - footer - tab 头部
  return 'calc(100vh - 20px - 60px - 60px - 50px)';
});
</script>
<template>
  <VModal ref="modal" :title="title" :width="888" mount-to-body height="calc(100vh - 20px)" @close="emit('close')">
    <VTabs v-model:active-id="activeTabId" type="outline">
      <VTabItem
        id="preview"
        label="预览"
      >
        <div class=":uno: flex-1 relative">
          <iframe 
            ref="previewRef" 
            class=":uno: absolute inset-0 w-full h-full border-0"
            :style="{ height: iframeHeight }"
            :srcdoc="getIframeContent()"
            sandbox="allow-same-origin"
          />
        </div>
      </VTabItem>
      <VTabItem
        id="source"
        label="源代码"
      >
        <div class=":uno: border">
          <codemirror
            v-model="content"
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
      </VTabItem>
    </VTabs>
    <template #footer>
      <VSpace>
        <VButton type="default" @click="modal?.close()">
          关闭
        </VButton>
      </VSpace>
    </template>
  </VModal>

</template>
