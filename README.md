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
