package com.kunkunyu.template.mail;

import com.kunkunyu.template.mail.service.MailTemplateService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

@Component
@RequiredArgsConstructor
public class NotificationTemplateReconciler implements Reconciler<Reconciler.Request> {

    private final ReactiveExtensionClient client;
    private final MailTemplateService mailTemplateService;

    @Override
    public Result reconcile(Request request) {
        // Halo's controller contract is synchronous; bound the wait and let it retry failures.
        client.fetch(NotificationTemplate.class, request.name())
            .filter(template -> !ExtensionUtil.isDeleted(template))
            .filter(template -> template.getSpec().getReasonSelector() != null)
            .mapNotNull(template -> template.getSpec().getReasonSelector().getReasonType())
            .filter(StringUtils::isNotBlank)
            .flatMap(mailTemplateService::restoreCustomTemplatePriority)
            .block(Duration.ofSeconds(30));
        return Result.doNotRetry();
    }

    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder.extension(new NotificationTemplate()).build();
    }
}
