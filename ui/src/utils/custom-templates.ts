import { coreApiClient, type NotificationTemplate, type Metadata } from "@halo-dev/api-client";

const LABEL = "mail-template.kunkunyu.com/custom-template";

export interface CustomTemplate {
  apiVersion: string;
  kind: string;
  metadata: Metadata;
  data: Record<string, string>;
}

export async function listCustomTemplates(): Promise<CustomTemplate[]> {
  const { data } = await coreApiClient.configMap.listConfigMap({page: 0, size: 0, labelSelector: [`${LABEL}=true`]});
  return data.items.filter(item => !item.metadata.deletionTimestamp && item.data?.reasonType) as CustomTemplate[];
}

export async function createCustomTemplate(name: string, reasonType: string) {
  const { data } = await coreApiClient.notification.notificationTemplate.listNotificationTemplate({
    fieldSelector: [`spec.reasonSelector.reasonType=${reasonType}`], page: 0, size: 0,
  });
  const templates = data.items.filter(item => !item.metadata.deletionTimestamp);
  const base = templates.find(item => item.metadata.name === `template-one-${reasonType}`)
    ?? templates.find(item => item.spec?.reasonSelector?.language === "default") ?? templates[0];
  const template = base?.spec?.template;
  const draft: CustomTemplate = {
    apiVersion: "v1alpha1", kind: "ConfigMap",
    metadata: { name: `mail-template-custom-${crypto.randomUUID()}`, labels: { [LABEL]: "true" } },
    data: {
      displayName: name.trim(), reasonType,
      title: template?.title ?? name.trim(),
      htmlBody: template?.htmlBody ?? '<p th:text="${site.title}"></p>',
      rawBody: template?.rawBody ?? "",
    },
  };
  return (await coreApiClient.configMap.createConfigMap({configMap: draft})).data as CustomTemplate;
}

export async function saveCustomTemplate(draft: CustomTemplate, title: string, htmlBody: string) {
  const { data: latest } = await coreApiClient.configMap.getConfigMap({name: draft.metadata.name});
  return (await coreApiClient.configMap.updateConfigMap({name: draft.metadata.name, configMap: {
    ...latest, data: { ...latest.data, title, htmlBody },
  }})).data as CustomTemplate;
}

export async function deleteCustomTemplate(draft: CustomTemplate) {
  await coreApiClient.configMap.deleteConfigMap({name: draft.metadata.name});
}

export async function applyCustomTemplate(draft: CustomTemplate) {
  const reasonType = draft.data.reasonType;
  const name = `template-one-${reasonType}`;
  const { data } = await coreApiClient.notification.notificationTemplate.listNotificationTemplate({
    fieldSelector: [`spec.reasonSelector.reasonType=${reasonType}`], page: 0, size: 0,
  });
  const existing = data.items.find(item => item.metadata.name === name);
  if (existing?.metadata.deletionTimestamp) {
    throw new Error("原模板正在还原，请稍后再应用。");
  }
  const template: NotificationTemplate = {
    apiVersion: "notification.halo.run/v1alpha1", kind: "NotificationTemplate",
    metadata: existing?.metadata ?? { name },
    spec: {
      reasonSelector: { reasonType, language: "default" },
      template: { title: draft.data.title, htmlBody: draft.data.htmlBody, rawBody: draft.data.rawBody },
    },
  };
  if (existing) {
    await coreApiClient.notification.notificationTemplate.updateNotificationTemplate({ name, notificationTemplate: template });
  } else {
    await coreApiClient.notification.notificationTemplate.createNotificationTemplate({ notificationTemplate: template });
  }
}
