package com.kunkunyu.template.mail.mcp;

import com.kunkunyu.template.mail.service.MailTemplateService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import run.halo.app.extension.ReactiveExtensionClient;

@Configuration
@ConditionalOnClass(name = "run.halo.mcpserver.api.McpToolProvider")
public class McpToolConfiguration {

    @Bean
    MailTemplateMcpToolProvider mailTemplateMcpToolProvider(
        ReactiveExtensionClient client, MailTemplateService mailTemplateService) {
        return new MailTemplateMcpToolProvider(client, mailTemplateService);
    }
}
