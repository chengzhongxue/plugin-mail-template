import { coreApiClient } from "@halo-dev/api-client";

const CONFIG_NAME = "mail-template-sidebar-order";
export const ORDER_QUERY_KEY = ["mail-template-sidebar-order"];
const APPROVED = "reason:mail-template-link-application-approved";
const REJECTED = "reason:mail-template-link-application-rejected";
const APPLICATION = "reason:plugin-links-new-link-application";

/** Preserve the server order except for grouping the three friend-link notifications. */
export function defaultOrder(ids: string[]): string[] {
  const result = ids.filter(id => id !== APPROVED && id !== REJECTED);
  const position = result.indexOf(APPLICATION);
  result.splice(position < 0 ? result.length : position + 1, 0,
    ...[APPROVED, REJECTED].filter(id => ids.includes(id)));
  return result;
}

/** Retain absent IDs in storage so disabling/re-enabling a source preserves its position. */
export function mergeOrder(saved: string[], available: string[]): string[] {
  return [...new Set([...saved, ...defaultOrder(available)])];
}

export function moveTemplate(order: string[], source: string, target: string, after: boolean): string[] {
  if (source === target || !order.includes(source) || !order.includes(target)) return order;
  const result = order.filter(id => id !== source);
  result.splice(result.indexOf(target) + (after ? 1 : 0), 0, source);
  return result;
}

export async function loadOrder() {
  const { data } = await coreApiClient.configMap.listConfigMap({
    page: 0, size: 0, fieldSelector: [`metadata.name=${CONFIG_NAME}`],
  });
  const config = data.items.find(item => item.metadata.name === CONFIG_NAME && !item.metadata.deletionTimestamp);
  let order: string[] = [];
  try {
    const parsed: unknown = JSON.parse(config?.data?.order ?? "[]");
    if (Array.isArray(parsed)) order = [...new Set(parsed.filter((id): id is string => typeof id === "string"))];
  } catch { /* Fall back to the default order if stored data is invalid. */ }
  return { config, order };
}

export async function saveOrder(order: string[], previous: Awaited<ReturnType<typeof loadOrder>>) {
  const configMap = {
    apiVersion: "v1alpha1", kind: "ConfigMap",
    ...previous.config,
    metadata: previous.config?.metadata ?? { name: CONFIG_NAME },
    data: { ...previous.config?.data, order: JSON.stringify(order) },
  };
  // Use the version originally loaded so concurrent edits cannot silently overwrite each other.
  if (previous.config) {
    await coreApiClient.configMap.updateConfigMap({ name: CONFIG_NAME, configMap });
  } else {
    await coreApiClient.configMap.createConfigMap({ configMap });
  }
}
