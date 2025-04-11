package com.kunkunyu.template.mail;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.extension.router.selector.FieldSelector;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.springframework.data.domain.Sort.Order.asc;
import static run.halo.app.extension.index.query.QueryFactory.and;
import static run.halo.app.extension.index.query.QueryFactory.equal;
import static run.halo.app.extension.index.query.QueryFactory.isNull;

@Component
@RequiredArgsConstructor
public class NotificationTemplateReconciler implements Reconciler<Reconciler.Request> {

    public static final String PREFIX = "template-one-";

    private final ExtensionClient client;

    @Override
    public Reconciler.Result reconcile(Reconciler.Request request) {
        client.fetch(NotificationTemplate.class, request.name())
            .ifPresent(notificationTemplate -> {
                String name = notificationTemplate.getMetadata().getName();
                boolean isPrefix = name.startsWith(PREFIX);
                Instant creationTimestamp = notificationTemplate.getMetadata().getCreationTimestamp();
                var spec = notificationTemplate.getSpec();

                if (ExtensionUtil.isDeleted(notificationTemplate)) {
                    client.update(notificationTemplate);
                    /**
                     * 如果插件被删除那就重新创建
                     */
                    if (isPrefix) {
                        if (spec.getReasonSelector() != null) {
                            String reasonType = spec.getReasonSelector().getReasonType();
                            if (StringUtils.isNotEmpty(reasonType)) {
                                var listOptions = new ListOptions();
                                listOptions.setFieldSelector(FieldSelector.of(
                                    and(
                                        equal("spec.reasonSelector.reasonType", reasonType),
                                        isNull("metadata.deletionTimestamp")
                                    )
                                ));
                                List<NotificationTemplate> notificationTemplates = client.listAll(NotificationTemplate.class, listOptions,
                                        Sort.by(asc("metadata.creationTimestamp")));

                                if (!notificationTemplates.isEmpty()) {
                                    NotificationTemplate oldNotificationTemplate = notificationTemplates.get(0);
                                    boolean isAfter = oldNotificationTemplate.getMetadata().getCreationTimestamp().isAfter(creationTimestamp);
                                    if (isAfter) {
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
                                                Optional<NotificationTemplate> checkTemplate = 
                                                    client.fetch(NotificationTemplate.class, name);
                                                
                                                if (checkTemplate.isEmpty()) {
                                                    templateDeleted = true;
                                                } else {
                                                    retryCount++;
                                                }
                                            }
                                            
                                            if (templateDeleted) {
                                                // 创建新模板
                                                NotificationTemplate newNotificationTemplateOne = new NotificationTemplate();
                                                BeanUtils.copyProperties(oldNotificationTemplate, newNotificationTemplateOne);
                                                newNotificationTemplateOne.getMetadata().setName(name);
                                                newNotificationTemplateOne.getSpec().getTemplate().setHtmlBody(spec.getTemplate().getHtmlBody());
                                                
                                                client.create(newNotificationTemplateOne);
                                            }
                                        } catch (InterruptedException e) {
                                            e.printStackTrace();
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
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
