package com.kunkunyu.template.mail;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Slf4j
@Component
public class MailTemplatePlugin extends BasePlugin {

    public MailTemplatePlugin(PluginContext pluginContext) {
        super(pluginContext);
    }

    @Override
    public void start() {
        log.info("Mail template plugin started");
    }

    @Override
    public void stop() {
        log.info("Mail template plugin stopped");
    }
}
