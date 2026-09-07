<script lang="ts" setup>
import { html } from "@codemirror/lang-html";
import { EditorView } from "@codemirror/view";
import { VButton, VModal, VSpace, VTabItem, VTabs } from "@halo-dev/components";
import { computed, ref } from "vue";
import { Codemirror } from "vue-codemirror";

const props = defineProps<{
  content: string;
  title: string;
}>();

const emit = defineEmits<{
  close: [];
}>();

const modal = ref<InstanceType<typeof VModal> | null>(null);
const activeTabId = ref("preview");
const iframeHeight = computed(() => "calc(100vh - 20px - 60px - 60px - 50px)");
const iframeContent = computed(() => `<!doctype html>${props.content || ""}`);
</script>

<template>
  <VModal
    ref="modal"
    :title="title"
    :width="888"
    mount-to-body
    height="calc(100vh - 20px)"
    @close="emit('close')"
  >
    <VTabs v-model:active-id="activeTabId" type="outline">
      <VTabItem id="preview" label="预览">
        <div class=":uno: relative flex-1">
          <iframe
            class=":uno: absolute inset-0 h-full w-full border-0"
            :style="{ height: iframeHeight }"
            :srcdoc="iframeContent"
            sandbox=""
            referrerpolicy="no-referrer"
            title="市场模板预览"
          />
        </div>
      </VTabItem>
      <VTabItem id="source" label="源代码">
        <div class=":uno: border">
          <Codemirror
            :model-value="content"
            :style="{ height: iframeHeight }"
            :disabled="true"
            :extensions="[html(), EditorView.lineWrapping]"
          />
        </div>
      </VTabItem>
    </VTabs>
    <template #footer>
      <VSpace>
        <VButton type="default" @click="modal?.close()">关闭</VButton>
      </VSpace>
    </template>
  </VModal>
</template>
