import "./styles/main.css";
import { definePlugin } from "@halo-dev/ui-shared";
import "uno.css";
import { markRaw } from "vue";
import FluentMailTemplate24Regular from "~icons/fluent/mail-template-24-regular";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "/mail-template",
        name: "MailTemplate",
        component: () => import("@/views/MailTemplates.vue"),
        meta: {
          title: "邮件模板管理",
          description: "查看、校验、编辑和测试邮件通知模板",
          searchable: true,
          hideFooter: true,
          permissions: ["plugin:mail-template:manage"],
          menu: {
            name: "邮件模板管理",
            icon: markRaw(FluentMailTemplate24Regular),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
