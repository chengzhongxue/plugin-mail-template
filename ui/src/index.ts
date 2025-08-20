import { VLoading } from '@halo-dev/components';
import './styles/main.css';
import { definePlugin } from "@halo-dev/console-shared";
import 'uno.css';
import { defineAsyncComponent, markRaw } from "vue";
import FluentMailTemplate24Regular from '~icons/fluent/mail-template-24-regular';


export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "/mail-template",
        name: "MailTemplate",
        component: defineAsyncComponent({
          loader: () => import('@/views/MailTemplates.vue'),
          loadingComponent: VLoading,
        }),
        meta: {
          title: "邮件模板管理",
          description: '查看、编辑 邮件模板',
          searchable: true,
          hideFooter: true,
          permissions: ["*"],
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
