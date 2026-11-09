# plugin-mail-template

## 功能预览
![Snipaste_2025-04-11_17-39-45.webp](https://api.minio.yyds.pink/halo-docs/2025/04/Snipaste_2025-04-11_17-39-45.webp)

![Snipaste_2025-04-11_17-39-58.webp](https://api.minio.yyds.pink/halo-docs/2025/04/Snipaste_2025-04-11_17-39-58.webp)

![Snipaste_2025-04-10_01-37-00.webp](https://api.minio.yyds.pink/halo-docs/2025/04/Snipaste_2025-04-10_01-37-00.webp)

## 使用文档

- 文档：https://docs.kunkunyu.com/docs/mail-template
- 注意事项：不会改的请不要随意修改，否则会导致模版失效
- 安装完成后，在 Halo 后台管理界面左侧菜单栏中找到`工具`选项
- 点击`邮件模板管理` 选择对应的模版

## 友链审核结果邮件

对接官方 [链接管理插件](https://github.com/halo-sigs/plugin-links) 的友链申请功能
（`core.halo.run/v1alpha1` / `LinkApplication`）。无需修改链接管理插件或主题。

- 在原有模板列表中新增「友链审核通过」和「友链审核未通过」，支持编辑、还原及验证邮件。
- 申请从 `PENDING` / `APPROVING` 变为 `APPROVED` / `REJECTED` 时，自动向申请中填写的邮箱发送对应结果。
- 模板参数：`displayName`（网站名称）、`websiteUrl`（网站地址）、`reviewResult`（审核结果），以及原有的 `site`、`subscriber` 等系统参数。
- 通过时优先使用链接插件 `spec.approval.request` 中保存的最终名称、地址，因此审核弹窗中修改的这两个字段也会反映在邮件中；旧版未提供此数据时使用申请值。拒绝时使用原始申请值，删除不发审核邮件。
- 没有申请邮箱时跳过通知并记录日志；普通链接修改、重复保存同一审核结果、删除申请及启动时的历史申请不会触发邮件。

使用前请在 Halo 配置并启用邮件通知，且确认申请人填写了联系邮箱。更新本插件并启用后，
可先在模板管理页保存模板并点击「验证模板」，再用自己的邮箱提交一条新申请，分别验证通过和拒绝两种流程。
审核通知由 Halo 通知系统投递，通知提交失败会自动重试 3 次，最终失败会记录日志；
SMTP 投递状态请查看 Halo 通知日志。仅监听本插件启用期间的审核操作，停用期间的审核及重启前尚未完成的队列不会补发。
通知异步处理，每次提交超时 30 秒；队列设有容量上限，队列已满会记录失败日志，不阻止友链审核。
监听异常仅影响本插件通知，不写入链接插件的数据、不注册或修改它的资源结构。停用时移除监听并取消通知任务。
默认拒绝模板提供通用提示，当前官方审核数据不包含拒绝原因，因此不会自动生成具体原因。

## 自定义模板

左侧列表默认将「友链审核通过」「友链审核未通过」紧接在「新的友链申请」下方。
按住模板左侧的拖动手柄可上下排序，包括自定义模板。拖动结果只暂存于当前页面，
点击底部「保存排序」后才写入 Halo；「放弃修改」恢复上次保存顺序。
顺序由后台管理者共享，刷新或更换浏览器仍保留，不影响通知触发或其他插件数据。

在模板管理左侧列表下方点击「＋ 新增模板」，填写名称并选择已有通知类型。
插件会复制该通知当前的邮件模板，作为独立草稿保存在 Halo 中，可创建多个样式。

- 「模板参数」展示系统参数及所选通知类型声明的参数，点击「插入」可在编辑器光标处插入表达式。
- 「保存」只保存草稿；「应用到通知」保存当前内容并替换对应通知正在使用的模板。
- 应用后可在原通知模板页面发送验证邮件。预览仅展示 HTML 样式，不渲染实际事件参数。
- 删除草稿不会删除已应用的通知样式；可通过原通知页面「还原模板」恢复默认样式。
- 可用通知类型来自当前 Halo 与已安装插件。新增样式沿用所选事件及参数，不会自动创建新的事件触发逻辑，不能读取任意 Halo 数据。

升级时保留 Halo 中原有的通知模板与配置；自定义草稿同样持久保存在 Halo 中。
已移除原插件按创建时间自动删除、重建自定义模板的处理，避免其他插件加载默认模板时误删用户配置。

## MCP Server 集成

安装并启用 [Halo MCP Server](https://github.com/halo-dev/plugin-mcp-server) 1.x 后，本插件会自动提供以下 MCP 工具：

| 工具 | 说明 |
| --- | --- |
| `plugin-mail-template__list_mail_templates` | 分页查询邮件模板 |
| `plugin-mail-template__get_mail_template` | 按原因类型的资源名称获取邮件模板 |
| `plugin-mail-template__update_mail_template` | 修改邮件模板；尚未自定义时以默认模板为基础创建，未传入的字段保持原值 |
| `plugin-mail-template__restore_mail_template` | 按原因类型的资源名称还原邮件模板，调用前必须获得用户明确确认 |
| `plugin-mail-template__verify_mail_template` | 按原因类型的资源名称发送验证邮件 |
| `plugin-mail-template__list_system_properties` | 查询邮件模板可用的系统属性 |

需要在 MCP Server 的访问密钥设置中选择需要开放的工具。未安装 MCP Server 时，不影响邮件模板插件的其他功能。

## 交流群
* 添加企业微信 （备注进群）
<img width="360" src="https://api.minio.yyds.pink/kunkunyu/files/2025/02/%E5%BE%AE%E4%BF%A1%E5%9B%BE%E7%89%87_20250212142105-pbceif.jpg" />

* QQ群
<img width="360" src="https://api.minio.yyds.pink/kunkunyu/files/2025/05/qq-708998089-iqowsh.webp" />
