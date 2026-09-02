<script lang="ts" setup>
import { computed, ref, watch } from "vue";

const props = withDefaults(
  defineProps<{
    src: string;
    alt?: string;
    classes?: string | string[];
    allowedHosts?: string[];
  }>(),
  {
    alt: "",
    classes: "",
    allowedHosts: () => [],
  },
);

const isLoading = ref(false);
const error = ref(false);
const safeSrc = computed(() => {
  try {
    const url = new URL(props.src);
    const hostAllowed = props.allowedHosts.some(
      (host) => host.toLowerCase() === url.hostname.toLowerCase(),
    );
    const portAllowed = !url.port || url.port === "443";
    return url.protocol === "https:" && hostAllowed && portAllowed
      ? url.href
      : "";
  } catch {
    return "";
  }
});

watch(
  safeSrc,
  (src, _previous, onCleanup) => {
    error.value = false;
    if (!src) {
      isLoading.value = false;
      error.value = true;
      return;
    }

    isLoading.value = true;
    const image = new Image();
    image.onload = () => {
      isLoading.value = false;
    };
    image.onerror = () => {
      isLoading.value = false;
      error.value = true;
    };
    image.referrerPolicy = "no-referrer";
    image.src = src;

    onCleanup(() => {
      image.onload = null;
      image.onerror = null;
    });
  },
  { immediate: true },
);
</script>

<template>
  <div :class="classes">
    <template v-if="isLoading">
      <slot name="loading">加载中...</slot>
    </template>
    <template v-else-if="error">
      <slot name="error">加载失败</slot>
    </template>
    <img
      v-else
      class=":uno: size-full object-cover"
      :src="safeSrc"
      :alt="alt"
      referrerpolicy="no-referrer"
    />
  </div>
</template>
