package com.kunkunyu.template.mail;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class NotificationTemplateReconciler implements Reconciler<Reconciler.Request> {

    public static final String PREFIX = "template-one-";

    private final ExtensionClient client;

    @Override
    public Reconciler.Result reconcile(Reconciler.Request request) {
        client.fetch(NotificationTemplate.class, request.name())
            .ifPresent(notificationTemplate -> {
                if (ExtensionUtil.isDeleted(notificationTemplate)) {
                    return;
                }

                String name = notificationTemplate.getMetadata().getName();
                boolean isPrefix = name.startsWith(PREFIX);
                Instant creationTimestamp = notificationTemplate.getMetadata().getCreationTimestamp();
                var spec = notificationTemplate.getSpec();
                if (!isPrefix) {
                    if (spec.getReasonSelector() != null) {
                        String reasonType = spec.getReasonSelector().getReasonType();
                        if (StringUtils.isNotEmpty(reasonType)) {
                            String templateName = PREFIX+reasonType;
                            client.fetch(NotificationTemplate.class, templateName)
                                .ifPresent(notificationTemplateOne -> {
                                    Instant oneCreationTimestamp = notificationTemplateOne.getMetadata().getCreationTimestamp();
                                    boolean isAfter = creationTimestamp.isAfter(oneCreationTimestamp);
                                    if (isAfter) {
                                        client.delete(notificationTemplateOne);
                                        // 确保旧模板已被删除后再创建新模板
                                        try {
                                            // 检查模板是否已被删除
                                            boolean templateDeleted = false;
                                            int maxRetries = 5;
                                            int retryCount = 0;

                                            while (!templateDeleted && retryCount < maxRetries) {
                                                // 延迟检查，等待删除操作完成
                                                Thread.sleep(500);

                                                // 尝试获取模板，如果获取不到则表示已删除
                                                Optional<NotificationTemplate> checkTemplate = client.fetch(NotificationTemplate.class, templateName);

                                                if (checkTemplate.isEmpty()) {
                                                    templateDeleted = true;
                                                } else {
                                                    retryCount++;
                                                }
                                            }

                                            if (templateDeleted) {
                                                // 创建新模板
                                                NotificationTemplate newNotificationTemplateOne = new NotificationTemplate();
                                                BeanUtils.copyProperties(notificationTemplate, newNotificationTemplateOne);
                                                NotificationTemplate.Template template = notificationTemplateOne.getSpec().getTemplate();
                                                newNotificationTemplateOne.getMetadata().setName(templateName);
                                                newNotificationTemplateOne.getSpec().getTemplate().setHtmlBody(template.getHtmlBody());
                                                newNotificationTemplateOne.getSpec().getTemplate().setTitle(template.getTitle());

                                                client.create(newNotificationTemplateOne);
                                            }
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                    }
                                });
                        }
                    }
                }
            });
        return Reconciler.Result.doNotRetry();
    }



    @Override
    public Controller setupWith(ControllerBuilder builder) {
        return builder
            .extension(new NotificationTemplate())
            .build();
    }
}
