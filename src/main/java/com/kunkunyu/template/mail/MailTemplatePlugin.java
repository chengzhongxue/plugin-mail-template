package com.kunkunyu.template.mail;

import com.kunkunyu.template.mail.links.LinkReviewNotificationService;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;


@Component
public class MailTemplatePlugin extends BasePlugin {
    private final LinkReviewNotificationService linkReviewNotifications;

    public MailTemplatePlugin(PluginContext pluginContext,
        LinkReviewNotificationService linkReviewNotifications) {
        super(pluginContext);
        this.linkReviewNotifications = linkReviewNotifications;
    }

    @Override
    public void start() {
        linkReviewNotifications.start();
        System.out.println("插件启动成功！");
    }

    @Override
    public void stop() {
        linkReviewNotifications.stop();
        System.out.println("插件停止！");
    }
}
