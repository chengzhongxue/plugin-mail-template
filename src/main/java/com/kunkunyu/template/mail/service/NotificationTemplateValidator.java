package com.kunkunyu.template.mail.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.exceptions.TemplateEngineException;
import org.thymeleaf.exceptions.TemplateProcessingException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.notification.ReasonAttributes;

@Component
public class NotificationTemplateValidator {

    static final int MAX_TITLE_LENGTH = 10_000;
    static final int MAX_BODY_LENGTH = 512 * 1024;

    private static final TemplateEngine TEMPLATE_ENGINE = createTemplateEngine();

    public void validate(
        NotificationTemplate.Template template, ReasonType reasonType) {
        if (template == null) {
            throw new TemplateValidationException("模板内容不能为空");
        }
        if (StringUtils.isBlank(template.getTitle())) {
            throw new TemplateValidationException("模板标题不能为空");
        }

        var model = createTemplateModel(reasonType);
        validatePart("标题", template.getTitle(), MAX_TITLE_LENGTH, model);
        validatePart("HTML 正文", template.getHtmlBody(), MAX_BODY_LENGTH, model);
        validatePart("纯文本正文", template.getRawBody(), MAX_BODY_LENGTH, model);
    }

    public ReasonAttributes createReasonAttributes(ReasonType reasonType) {
        var attributes = new ReasonAttributes();
        Optional.ofNullable(reasonType)
            .map(ReasonType::getSpec)
            .map(ReasonType.Spec::getProperties)
            .orElseGet(List::of)
            .forEach(property -> attributes.put(
                property.getName(), sampleValue(property.getType(), property.getName())));
        return attributes;
    }

    Map<String, Object> createTemplateModel(ReasonType reasonType) {
        Map<String, Object> model = new HashMap<>(createReasonAttributes(reasonType));

        Map<String, Object> subscriber = new HashMap<>();
        subscriber.put("displayName", "模板预览用户");
        subscriber.put("id", "anonymousUser#preview@example.com");
        model.put("subscriber", subscriber);

        Map<String, Object> site = new HashMap<>();
        site.put("title", "Halo");
        site.put("subtitle", "Halo 站点");
        site.put("logo", "https://example.com/logo.png");
        site.put("url", "https://example.com");
        model.put("site", site);
        model.put("unsubscribeUrl", "https://example.com/unsubscribe");
        return model;
    }

    private void validatePart(
        String partName, String content, int maxLength, Map<String, Object> model) {
        var value = StringUtils.defaultString(content);
        if (value.length() > maxLength) {
            throw new TemplateValidationException(
                "%s不能超过 %d 个字符".formatted(partName, maxLength));
        }
        if (value.isEmpty()) {
            return;
        }

        try {
            TEMPLATE_ENGINE.process(value, new Context(Locale.getDefault(), model));
        } catch (TemplateEngineException error) {
            throw new TemplateValidationException(
                formatError(partName, error), error);
        }
    }

    private static String formatError(String partName, TemplateEngineException error) {
        TemplateProcessingException processingError = null;
        Throwable current = error;
        while (current != null) {
            if (current instanceof TemplateProcessingException candidate) {
                processingError = candidate;
            }
            current = current.getCause();
        }

        var location = "";
        if (processingError != null && processingError.hasLineAndCol()) {
            location = "（第 %d 行，第 %d 列）".formatted(
                processingError.getLine(), processingError.getCol());
        }
        var detail = compactMessage(
            processingError == null ? error.getMessage() : processingError.getMessage());
        return "%s模板语法错误%s：%s".formatted(partName, location, detail);
    }

    private static String compactMessage(String message) {
        var compact = StringUtils.defaultIfBlank(message, "无法解析模板")
            .replace('\n', ' ')
            .replace('\r', ' ');
        var templateIndex = compact.indexOf(" (template:");
        if (templateIndex >= 0) {
            compact = compact.substring(0, templateIndex);
        }
        return StringUtils.abbreviate(compact, 300);
    }

    private static Object sampleValue(String type, String name) {
        if (type == null) {
            return name;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "number", "integer", "int", "long", "float", "double" -> 123;
            case "boolean", "bool" -> false;
            case "array", "list" -> new ArrayList<>(List.of("示例"));
            case "object", "map" -> new HashMap<String, Object>();
            case "date", "datetime", "instant" -> new Date();
            case "url" -> "https://example.com";
            case "email" -> "preview@example.com";
            default -> name;
        };
    }

    private static TemplateEngine createTemplateEngine() {
        var templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(new StringTemplateResolver());
        return templateEngine;
    }
}
