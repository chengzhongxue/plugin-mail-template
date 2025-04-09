<script lang="ts" setup>
import {ref} from "vue";
import {
  VModal,
  VSpace,
  VButton,
} from "@halo-dev/components";
import type {ReasonType} from "@halo-dev/api-client";

const props = withDefaults(
  defineProps<{
    reasonType: ReasonType;
  }>(),
  {
  }
);

const emit = defineEmits<{
  (event: "close"): void;
}>();
const modal = ref<InstanceType<typeof VModal> | null>(null);

const systemAttributes = ref([
  {
    "name": "site.title",
    "type": "string",
    "description": "站点标题",
  },
  {
    "name": "site.subtitle",
    "type": "string",
    "description": "站点副标题",
  },
  {
    "name": "site.logo",
    "type": "string",
    "description": "站点 Logo URL",
  },
  {
    "name": "site.url",
    "type": "string",
    "description": "站点外部访问地址",
  },
  {
    "name": "subscriber.displayName",
    "type": "string",
    "description": "订阅者显示名称",
  },
  {
    "name": "subscriber.id",
    "type": "string",
    "description": "订阅者唯一标识符",
  },
  {
    "name": "unsubscribeUrl",
    "type": "string",
    "description": "退订地址，用于取消订阅的链接",
  }
])

</script>
<template>
  <VModal
    :body-class="['!p-0']"
    mount-to-body
    layer-closable
    ref="modal"
    :title="reasonType.spec?.displayName + ' 模版参数'"
    :width="800"
    :centered="false"
    @close="emit('close')">
    <!-- 系统变量 -->
    <div class="px-4 pt-4">
      <div class="mb-2 font-medium text-base flex items-center">
        <div class="w-1 h-4 bg-primary rounded mr-2"></div>
        系统变量
      </div>
      <div class="border rounded overflow-hidden">
        <!-- Table Header -->
        <div class="grid grid-cols-3 bg-gray-50 border-b">
          <div class="p-2 font-medium">字段名</div>
          <div class="p-2 font-medium">类型</div>
          <div class="p-2 font-medium">说明</div>
        </div>
        <!-- Table Body -->
        <div v-for="systemAttribute in systemAttributes" :key="systemAttribute.name">
          <div class="grid grid-cols-3 border-b last:border-b-0 hover:bg-gray-50">
            <div class="p-3">
              <code class="bg-gray-100 px-2 py-1 rounded text-sm">{{ systemAttribute.name }}</code>
            </div>
            <div class="p-3">
              <code class="bg-gray-100 px-2 py-1 rounded text-sm">{{ systemAttribute.type }}</code>
            </div>
            <div class="p-3">{{ systemAttribute.description }}</div>
          </div>
        </div>
      </div>
    </div>
    <!-- 模版变量 -->
    <div class="p-4">
      <div class="mb-2 font-medium text-base flex items-center">
        <div class="w-1 h-4 bg-primary rounded mr-2"></div>
        模版变量
      </div>
      <div class="border rounded overflow-hidden">
        <!-- Table Header -->
        <div class="grid grid-cols-3 bg-gray-50 border-b">
          <div class="p-2 font-medium">字段名</div>
          <div class="p-2 font-medium">类型</div>
          <div class="p-2 font-medium">说明</div>
        </div>
        <!-- Table Body -->
        <div v-for="property in reasonType.spec?.properties" :key="property.name">
          <div class="grid grid-cols-3 border-b last:border-b-0 hover:bg-gray-50">
            <div class="p-3">
              <code class="bg-gray-100 px-2 py-1 rounded text-sm">{{ property.name }}</code>
            </div>
            <div class="p-3">
              <code class="bg-gray-100 px-2 py-1 rounded text-sm">{{ property.type }}</code>
            </div>
            <div class="p-3">{{ property.description }}</div>
          </div>
        </div>
      </div>
    </div>
    <template #footer>
      <VSpace>
        <VButton @click="modal?.close()">
          关闭
        </VButton>
      </VSpace>
    </template>
  </VModal>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: 250px 120px 1fr;
}
</style>
