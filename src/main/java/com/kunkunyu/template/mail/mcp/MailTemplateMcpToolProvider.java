package com.kunkunyu.template.mail.mcp;

import static org.springframework.data.domain.Sort.Order.desc;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.in;
import static run.halo.app.extension.index.query.Queries.isNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.kunkunyu.template.mail.ListedMailTemplate;
import com.kunkunyu.template.mail.NotificationTemplateReconciler;
import com.kunkunyu.template.mail.exception.NotFoundException;
import com.kunkunyu.template.mail.service.MailTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.crossstore.ChangeSetPersister;
import org.springframework.data.domain.Sort;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.mcpserver.api.McpToolAnnotations;
import run.halo.mcpserver.api.McpToolDefinition;
import run.halo.mcpserver.api.McpToolException;
import run.halo.mcpserver.api.McpToolInvocation;
import run.halo.mcpserver.api.McpToolProvider;
import run.halo.mcpserver.api.McpToolResult;

@Slf4j
public class MailTemplateMcpToolProvider implements McpToolProvider {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String DEFAULT_LANGUAGE = "default";
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Map<String, Object> REASON_TYPE_OUTPUT_SCHEMA = reasonTypeOutputSchema();
    private static final Map<String, Object> NOTIFICATION_TEMPLATE_OUTPUT_SCHEMA =
        notificationTemplateOutputSchema();
    private static final Map<String, Object> LISTED_MAIL_TEMPLATE_OUTPUT_SCHEMA =
        listedMailTemplateOutputSchema();
    private static final Map<String, Object> SYSTEM_PROPERTY_OUTPUT_SCHEMA =
        systemPropertyOutputSchema();
    private static final List<Map<String, Object>> SYSTEM_PROPERTIES = List.of(
        systemProperty("site.title", "string", "站点标题"),
        systemProperty("site.subtitle", "string", "站点副标题"),
        systemProperty("site.logo", "string", "站点 Logo URL"),
        systemProperty("site.url", "string", "站点外部访问地址"),
        systemProperty("subscriber.displayName", "string", "订阅者显示名称"),
        systemProperty("subscriber.id", "string", "订阅者唯一标识符"),
        systemProperty("unsubscribeUrl", "string", "退订地址，用于取消订阅的链接")
    );

    private final ReactiveExtensionClient client;

    private final MailTemplateService mailTemplateService;

    public MailTemplateMcpToolProvider(
        ReactiveExtensionClient client, MailTemplateService mailTemplateService) {
        this.client = client;
        this.mailTemplateService = mailTemplateService;
    }

    @Override
    public Flux<McpToolDefinition> tools() {
        return Flux.just(
            listSystemProperties(),
            listMailTemplates(),
            getMailTemplate(),
            updateMailTemplate(),
            restoreMailTemplate(),
            verifyMailTemplate());
    }

    private McpToolDefinition listMailTemplates() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("page", Map.of(
            "type", "integer",
            "minimum", 1,
            "default", 1,
            "description", "Page number, starting from 1."));
        properties.put("size", Map.of(
            "type", "integer",
            "minimum", 1,
            "maximum", MAX_PAGE_SIZE,
            "default", DEFAULT_PAGE_SIZE,
            "description", "Page size."));
        return McpToolDefinition.builder()
            .name("list_mail_templates")
            .title("List mail templates")
            .description("List Halo mail templates.")
            .displayTitle("查询邮件模版")
            .displayDescription("分页查询邮件模版")
            .inputSchema(objectSchema(properties, List.of()))
            .outputSchema(objectSchema(Map.of(
                "page", Map.of("type", "integer", "minimum", 1),
                "size", Map.of("type", "integer", "minimum", 1),
                "total", Map.of("type", "integer", "minimum", 0),
                "items", Map.of(
                    "type", "array", "items", LISTED_MAIL_TEMPLATE_OUTPUT_SCHEMA)),
                List.of("page", "size", "total", "items")))
            .annotations(McpToolAnnotations.readOnly("List mail templates"))
            .permission(ignored -> Mono.just(true))
            .handler(this::listMailTemplates)
            .build();
    }

    private McpToolDefinition getMailTemplate() {
        return McpToolDefinition.builder()
            .name("get_mail_template")
            .title("Get a mail template")
            .description("Get a Halo mail template by ReasonType metadata name. ")
            .displayTitle("获取邮件模版详情")
            .displayDescription("按原因类型的资源名称获取邮件模版。")
            .inputSchema(objectSchema(
                Map.of("reasonTypeName", reasonTypeNameSchema()),
                List.of("reasonTypeName")))
            .outputSchema(LISTED_MAIL_TEMPLATE_OUTPUT_SCHEMA)
            .annotations(McpToolAnnotations.readOnly("Get a mail template"))
            .permission(ignored -> Mono.just(true))
            .handler(this::getMailTemplate)
            .build();
    }

    private McpToolDefinition updateMailTemplate() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("reasonTypeName", reasonTypeNameSchema());
        properties.put("language", Map.of(
            "type", "string",
            "minLength", 1,
            "description", "Template language, such as default. Omitted values keep the current language.",
            "default", "default"));
        properties.put("title", Map.of(
            "type", "string",
            "minLength", 1,
            "description", "Mail subject. Omitted values keep the current title."));
        properties.put("htmlBody", Map.of(
            "type", "string",
            "description", "HTML body. Omitted values keep the current HTML body."));
        properties.put("rawBody", Map.of(
            "type", "string",
            "description", "Plain-text body. Omitted values keep the current raw body."));
        return McpToolDefinition.builder()
            .name("update_mail_template")
            .title("Update a mail template")
            .description("Update a Halo mail template. Creates the customized template when it "
                + "does not exist, using the default template as the base. Omitted fields are left unchanged.")
            .displayTitle("修改邮件模版")
            .displayDescription("修改自定义模版；未传入的字段保持原值。")
            .inputSchema(objectSchema(properties, List.of("reasonTypeName")))
            .outputSchema(NOTIFICATION_TEMPLATE_OUTPUT_SCHEMA)
            .annotations(new McpToolAnnotations(
                false, true, true, false, "Update a mail template"))
            .permission(ignored -> Mono.just(true))
            .handler(this::updateMailTemplate)
            .build();
    }

    private McpToolDefinition listSystemProperties() {
        return McpToolDefinition.builder()
            .name("list_system_properties")
            .title("List system properties")
            .description("List the fixed system properties available in mail templates.")
            .displayTitle("查询系统属性")
            .displayDescription("查询邮件模版可用的系统属性。")
            .inputSchema(objectSchema(Map.of(), List.of()))
            .outputSchema(objectSchema(Map.of(
                "items", Map.of("type", "array", "items", SYSTEM_PROPERTY_OUTPUT_SCHEMA)),
                List.of("items")))
            .annotations(McpToolAnnotations.readOnly("List system properties"))
            .permission(ignored -> Mono.just(true))
            .handler(invocation -> Mono.just(
                McpToolResult.success(Map.of("items", SYSTEM_PROPERTIES))))
            .build();
    }

    private McpToolDefinition restoreMailTemplate() {
        return McpToolDefinition.builder()
            .name("restore_mail_template")
            .title("Restore a mail template")
            .description("Restore the Halo mail template by ReasonType metadata name. "
                + "You MUST obtain the user's explicit confirmation immediately before calling this tool.")
            .displayTitle("还原邮件模版")
            .displayDescription("按原因类型的资源名称还原邮件模版，调用前必须获得用户明确确认。")
            .inputSchema(objectSchema(
                Map.of("reasonTypeName", reasonTypeNameSchema()),
                List.of("reasonTypeName")))
            .outputSchema(NOTIFICATION_TEMPLATE_OUTPUT_SCHEMA)
            .annotations(new McpToolAnnotations(
                false, true, true, false, "Restore a mail template"))
            .permission(ignored -> Mono.just(true))
            .handler(this::restoreMailTemplate)
            .build();
    }

    private McpToolDefinition verifyMailTemplate() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("reasonTypeName", reasonTypeNameSchema());
        return McpToolDefinition.builder()
            .name("verify_mail_template")
            .title("Verify a mail template")
            .description("Send a verification email for a Halo mail template by ReasonType metadata name.")
            .displayTitle("验证邮件模版")
            .displayDescription("按原因类型的元数据名称发送验证邮件。")
            .inputSchema(objectSchema(properties, List.of("reasonTypeName")))
            .outputSchema(objectSchema(Map.of(
                "reasonTypeName", Map.of("type", "string")),
                List.of("reasonTypeName")))
            .annotations(new McpToolAnnotations(
                false, false, false, false, "Verify a mail template"))
            .permission(ignored -> Mono.just(true))
            .handler(this::verifyMailTemplate)
            .build();
    }

    private Mono<McpToolResult> listMailTemplates(McpToolInvocation invocation) {
        return Mono.defer(() -> {
            var arguments = argumentsOf(invocation);
            var page = integer(arguments, "page", 1, 1, Integer.MAX_VALUE);
            var size = integer(arguments, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
            var listOptions = ListOptions.builder()
                .fieldQuery(ExtensionUtil.notDeleting())
                .build();
            var pageRequest = PageRequestImpl.of(page, size, Sort.by(
                desc("metadata.creationTimestamp"), desc("metadata.name")));
            return client.listBy(ReasonType.class, listOptions, pageRequest)
                .flatMap(list -> convertToListed(list.getItems())
                    .map(templates -> new ListResult<>(
                        list.getPage(), list.getSize(), list.getTotal(), templates)))
                .map(result -> McpToolResult.success(Map.of(
                    "page", result.getPage(),
                    "size", result.getSize(),
                    "total", result.getTotal(),
                    "items", result.getItems().stream()
                        .map(MailTemplateMcpToolProvider::listedMailTemplatePayload)
                        .toList())));
        });
    }

    private Mono<McpToolResult> getMailTemplate(McpToolInvocation invocation) {
        return Mono.defer(() -> client.fetch(
                ReasonType.class, requiredString(argumentsOf(invocation), "reasonTypeName"))
            .switchIfEmpty(Mono.error(new NotFoundException("ReasonType not found.")))
            .flatMap(this::toListedMailTemplate)
            .map(mailTemplate -> McpToolResult.success(listedMailTemplatePayload(mailTemplate)))
            .onErrorMap(ChangeSetPersister.NotFoundException.class,
                error -> new McpToolException("NOT_FOUND", "MailTemplate not found", error)));
    }

    private Mono<McpToolResult> updateMailTemplate(McpToolInvocation invocation) {
        return Mono.defer(() -> {
            var arguments = argumentsOf(invocation);
            var reasonTypeName = requiredString(arguments, "reasonTypeName");
            var language = optionalString(arguments, "language");
            var title = optionalString(arguments, "title");
            var htmlBody = optionalBody(arguments, "htmlBody");
            var rawBody = optionalBody(arguments, "rawBody");
            if (language == null && title == null && htmlBody == null && rawBody == null) {
                throw invalid(
                    "At least one of language, title, htmlBody or rawBody is required");
            }
            return client.fetch(ReasonType.class, reasonTypeName)
                .switchIfEmpty(Mono.error(notFound("ReasonType not found")))
                .then(getNotificationTemplates(reasonTypeName).collectList())
                .flatMap(templates -> {
                    var customized = findByName(templates, customizedName(reasonTypeName));
                    if (customized != null) {
                        applyTemplatePatch(customized, language, title, htmlBody, rawBody);
                        return client.update(customized);
                    }
                    var base = selectBaseTemplate(templates, language);
                    if (base == null) {
                        return Mono.error(notFound("MailTemplate not found"));
                    }
                    return client.create(copyAsCustomized(
                        base, reasonTypeName, language, title, htmlBody, rawBody));
                })
                .map(saved -> McpToolResult.success(
                    notificationTemplatePayload(saved), "MailTemplate updated"));
        });
    }

    private Mono<McpToolResult> restoreMailTemplate(McpToolInvocation invocation) {
        return Mono.defer(() -> {
            var reasonTypeName = requiredString(argumentsOf(invocation), "reasonTypeName");
            return client.fetch(ReasonType.class, reasonTypeName)
                .switchIfEmpty(Mono.error(notFound("ReasonType not found")))
                .then(getNotificationTemplates(reasonTypeName).collectList())
                .flatMap(templates -> {
                    var customized = findByName(templates, customizedName(reasonTypeName));
                    if (customized == null) {
                        return requireBaseTemplate(templates);
                    }
                    return client.delete(customized)
                        .then(getNotificationTemplates(reasonTypeName).collectList())
                        .flatMap(MailTemplateMcpToolProvider::requireBaseTemplate);
                })
                .map(template -> McpToolResult.success(
                    notificationTemplatePayload(template), "MailTemplate restored"));
        });
    }

    private Mono<McpToolResult> verifyMailTemplate(McpToolInvocation invocation) {
        return Mono.defer(() -> {
            var arguments = argumentsOf(invocation);
            var reasonTypeName = requiredString(arguments, "reasonTypeName");
            return client.fetch(ReasonType.class, reasonTypeName)
                .switchIfEmpty(Mono.error(notFound("ReasonType not found")))
                .flatMap(reasonType -> mailTemplateService.verifyMailTemplatSend(reasonType))
                .thenReturn(McpToolResult.success(Map.of(
                    "reasonTypeName", reasonTypeName
                ), "Verification mail sent"));
        });
    }

    private Mono<List<ListedMailTemplate>> convertToListed(List<ReasonType> reasonTypes) {
        if (reasonTypes.isEmpty()) {
            return Mono.just(List.of());
        }
        var reasonTypeNames = reasonTypes.stream()
            .map(reasonType -> reasonType.getMetadata().getName())
            .toList();
        return getNotificationTemplates(reasonTypeNames)
            .filter(template -> reasonTypeOf(template) != null)
            .collectMultimap(MailTemplateMcpToolProvider::reasonTypeOf)
            .map(grouped -> reasonTypes.stream()
                .map(reasonType -> {
                    var name = reasonType.getMetadata().getName();
                    var templates = grouped.get(name);
                    return new ListedMailTemplate()
                        .setReasonType(reasonType)
                        .setTemplates(templates == null ? List.of() : List.copyOf(templates));
                })
                .toList());
    }

    private Flux<NotificationTemplate> getNotificationTemplates(String reasonTypeName) {
        return getNotificationTemplates(List.of(reasonTypeName));
    }

    private Flux<NotificationTemplate> getNotificationTemplates(Collection<String> reasonTypeNames) {
        if (CollectionUtils.isEmpty(reasonTypeNames)) {
            return Flux.empty();
        }
        var listOptions = ListOptions.builder()
            .fieldQuery(and(
                in("spec.reasonSelector.reasonType", reasonTypeNames),
                isNull("metadata.deletionTimestamp")
            ))
            .build();
        return client.listAll(NotificationTemplate.class, listOptions, ExtensionUtil.defaultSort());
    }

    private Mono<ListedMailTemplate> toListedMailTemplate(ReasonType reasonType) {
        return getNotificationTemplates(reasonType.getMetadata().getName())
            .collectList()
            .map(templates -> new ListedMailTemplate()
                .setReasonType(reasonType)
                .setTemplates(templates));
    }

    private static Mono<NotificationTemplate> requireBaseTemplate(
        List<NotificationTemplate> templates) {
        var base = selectBaseTemplate(templates, null);
        if (base == null) {
            return Mono.error(notFound("MailTemplate not found"));
        }
        return Mono.just(base);
    }

    private static NotificationTemplate copyAsCustomized(
        NotificationTemplate base,
        String reasonTypeName,
        String language,
        String title,
        String htmlBody,
        String rawBody) {
        var baseSpec = base.getSpec();
        var baseSelector = baseSpec == null ? null : baseSpec.getReasonSelector();
        var baseTemplate = baseSpec == null ? null : baseSpec.getTemplate();
        if (baseSelector == null || baseTemplate == null) {
            throw notFound("MailTemplate not found");
        }

        var reasonSelector = new NotificationTemplate.ReasonSelector();
        reasonSelector.setReasonType(reasonTypeName);
        reasonSelector.setLanguage(
            language != null ? language : baseSelector.getLanguage());

        var template = new NotificationTemplate.Template();
        template.setTitle(title != null ? title : baseTemplate.getTitle());
        template.setHtmlBody(htmlBody != null ? htmlBody : baseTemplate.getHtmlBody());
        template.setRawBody(rawBody != null ? rawBody : baseTemplate.getRawBody());

        var spec = new NotificationTemplate.Spec();
        spec.setReasonSelector(reasonSelector);
        spec.setTemplate(template);

        var metadata = new Metadata();
        metadata.setName(customizedName(reasonTypeName));
        var notificationTemplate = new NotificationTemplate();
        notificationTemplate.setMetadata(metadata);
        notificationTemplate.setSpec(spec);
        return notificationTemplate;
    }

    private static void applyTemplatePatch(
        NotificationTemplate existing,
        String language,
        String title,
        String htmlBody,
        String rawBody) {
        var spec = existing.getSpec();
        if (spec == null) {
            spec = new NotificationTemplate.Spec();
            existing.setSpec(spec);
        }
        if (language != null) {
            var reasonSelector = spec.getReasonSelector();
            if (reasonSelector == null) {
                reasonSelector = new NotificationTemplate.ReasonSelector();
                spec.setReasonSelector(reasonSelector);
            }
            reasonSelector.setLanguage(language);
        }
        var template = spec.getTemplate();
        if (template == null) {
            template = new NotificationTemplate.Template();
            spec.setTemplate(template);
        }
        if (title != null) {
            template.setTitle(title);
        }
        if (htmlBody != null) {
            template.setHtmlBody(htmlBody);
        }
        if (rawBody != null) {
            template.setRawBody(rawBody);
        }
    }

    private static NotificationTemplate selectBaseTemplate(
        List<NotificationTemplate> templates, String language) {
        var candidates = templates.stream()
            .filter(template -> !isCustomized(template))
            .toList();
        if (candidates.isEmpty()) {
            return null;
        }
        if (language != null) {
            var matched = candidates.stream()
                .filter(template -> language.equals(templateLanguage(template)))
                .findFirst();
            if (matched.isPresent()) {
                return matched.get();
            }
        }
        return candidates.stream()
            .filter(template -> DEFAULT_LANGUAGE.equals(templateLanguage(template)))
            .findFirst()
            .orElse(candidates.get(0));
    }

    private static NotificationTemplate effectiveTemplate(ListedMailTemplate mailTemplate) {
        var templates = mailTemplate.getTemplates();
        if (templates == null || templates.isEmpty()) {
            return null;
        }
        var reasonType = mailTemplate.getReasonType();
        var reasonTypeName = reasonType == null || reasonType.getMetadata() == null
            ? null
            : reasonType.getMetadata().getName();
        if (reasonTypeName != null) {
            var customized = findByName(templates, customizedName(reasonTypeName));
            if (customized != null) {
                return customized;
            }
        }
        return selectBaseTemplate(templates, null);
    }

    private static NotificationTemplate findByName(
        List<NotificationTemplate> templates, String name) {
        return templates.stream()
            .filter(template -> name.equals(metadataName(template)))
            .findFirst()
            .orElse(null);
    }

    private static boolean isCustomized(NotificationTemplate template) {
        var name = metadataName(template);
        return name != null && name.startsWith(NotificationTemplateReconciler.PREFIX);
    }

    private static String customizedName(String reasonTypeName) {
        return NotificationTemplateReconciler.PREFIX + reasonTypeName;
    }

    private static String metadataName(NotificationTemplate notificationTemplate) {
        var metadata = notificationTemplate.getMetadata();
        return metadata == null ? null : metadata.getName();
    }

    private static String reasonTypeOf(NotificationTemplate notificationTemplate) {
        var spec = notificationTemplate.getSpec();
        if (spec == null || spec.getReasonSelector() == null) {
            return null;
        }
        return spec.getReasonSelector().getReasonType();
    }

    private static String templateLanguage(NotificationTemplate notificationTemplate) {
        var spec = notificationTemplate.getSpec();
        if (spec == null || spec.getReasonSelector() == null) {
            return null;
        }
        return spec.getReasonSelector().getLanguage();
    }

    private static Map<String, Object> argumentsOf(McpToolInvocation invocation) {
        var arguments = invocation.arguments();
        return arguments == null ? Map.<String, Object>of() : arguments;
    }

    private static int integer(
        Map<String, Object> arguments,
        String name,
        int defaultValue,
        int minimum,
        int maximum) {
        var value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof Number number)
            || number.doubleValue() != number.intValue()
            || number.intValue() < minimum
            || number.intValue() > maximum) {
            throw invalid(name + " must be an integer between " + minimum + " and " + maximum);
        }
        return number.intValue();
    }

    private static String requiredEmail(Map<?, ?> arguments, String name) {
        var email = requiredString(arguments, name);
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw invalid(name + " must be a valid email address");
        }
        return email;
    }

    private static String requiredString(Map<?, ?> arguments, String name) {
        var value = optionalString(arguments, name);
        if (value == null) {
            throw invalid(name + " must be a non-empty string");
        }
        return value;
    }

    private static String optionalString(Map<?, ?> arguments, String name) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
            return null;
        }
        var value = arguments.get(name);
        if (!(value instanceof String string) || string.isBlank()) {
            throw invalid(name + " must be a non-empty string");
        }
        return string;
    }

    private static String optionalBody(Map<?, ?> arguments, String name) {
        if (!arguments.containsKey(name) || arguments.get(name) == null) {
            return null;
        }
        var value = arguments.get(name);
        if (!(value instanceof String string)) {
            throw invalid(name + " must be a string");
        }
        return string;
    }

    private static Map<String, Object> reasonTypePayload(ReasonType reasonType) {
        var metadata = reasonType.getMetadata();
        var spec = reasonType.getSpec();
        var result = new LinkedHashMap<String, Object>();
        result.put("name", metadata == null ? null : metadata.getName());
        result.put("displayName", spec == null ? null : spec.getDisplayName());
        result.put("description", spec == null ? null : spec.getDescription());
        result.put("properties", spec == null || spec.getProperties() == null
            ? List.of()
            : spec.getProperties().stream()
                .map(MailTemplateMcpToolProvider::propertyPayload)
                .toList());
        return result;
    }

    private static Map<String, Object> notificationTemplatePayload(
        NotificationTemplate notificationTemplate) {
        var metadata = notificationTemplate.getMetadata();
        var spec = notificationTemplate.getSpec();
        var reasonSelector = spec == null ? null : spec.getReasonSelector();
        var template = spec == null ? null : spec.getTemplate();

        var result = new LinkedHashMap<String, Object>();
        result.put("name", metadata == null ? null : metadata.getName());
        result.put("reasonSelector",
            reasonSelector == null ? null : reasonSelectorPayload(reasonSelector));
        result.put("template", template == null ? null : templatePayload(template));
        return result;
    }

    private static Map<String, Object> listedMailTemplatePayload(ListedMailTemplate mailTemplate) {
        var reasonType = mailTemplate.getReasonType();
        var template = effectiveTemplate(mailTemplate);
        var result = new LinkedHashMap<String, Object>();
        result.put("reasonType", reasonType == null ? null : reasonTypePayload(reasonType));
        result.put("template", template == null ? null : notificationTemplatePayload(template));
        log.info("result", result.toString());
        return result;
    }

    private static Map<String, Object> reasonSelectorPayload(
        NotificationTemplate.ReasonSelector reasonSelector) {
        var result = new LinkedHashMap<String, Object>();
        result.put("reasonType", reasonSelector.getReasonType());
        result.put("language", reasonSelector.getLanguage());
        return result;
    }

    private static Map<String, Object> templatePayload(NotificationTemplate.Template template) {
        var result = new LinkedHashMap<String, Object>();
        result.put("title", template.getTitle());
        result.put("htmlBody", template.getHtmlBody());
        result.put("rawBody", template.getRawBody());
        return result;
    }

    private static Map<String, Object> propertyPayload(ReasonType.ReasonProperty reasonProperty) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", reasonProperty.getName());
        result.put("description", reasonProperty.getDescription());
        result.put("optional", reasonProperty.isOptional());
        result.put("type", reasonProperty.getType());
        return result;
    }

    private static McpToolException invalid(String message) {
        return new McpToolException("INVALID_ARGUMENT", message);
    }

    private static McpToolException notFound(String message) {
        return new McpToolException("NOT_FOUND", message);
    }

    private static Map<String, Object> reasonTypeNameSchema() {
        return Map.of(
            "type", "string",
            "minLength", 1,
            "description", "Metadata name of the ReasonType.");
    }

    private static Map<String, Object> reasonTypeOutputSchema() {
        var propertiesSchema = objectSchema(Map.of(
                "name", Map.of("type", "string", "minLength", 1),
                "type", Map.of("type", "string", "minLength", 1, "description", "Attribute value type, such as string, number, boolean, or object."),
                "description", Map.of(
                    "type", List.of("string", "null"),
                    "description", "Human-readable description of the attribute. Absent values are null."),
                "optional", Map.of("type", "boolean", "default", false, "description", "Whether the attribute may be absent from a reason.")),
            List.of("name", "description", "optional", "type"));

        return objectSchema(Map.of(
                "name", Map.of("type", "string", "description", "Metadata name"),
                "displayName", Map.of("type", "string", "minLength", 1),
                "description", Map.of("type", "string", "minLength", 1),
                "properties", Map.of("type", "array", "items", propertiesSchema)),
            List.of("name", "displayName", "description", "properties"));
    }

    private static Map<String, Object> notificationTemplateOutputSchema() {
        var reasonSelectorSchema = nullableObjectSchema(Map.of(
                "reasonType", Map.of("type", "string", "minLength", 1, "description", "ReasonType metadata.name this template applies to."),
                "language", Map.of("type", "string", "minLength", 1, "default", "default", "description", "Language tag this template applies to, or default for the fallback template.")),
            List.of("reasonType", "language"));
        var templateSchema = nullableObjectSchema(Map.of(
                "htmlBody", Map.of(
                    "type", List.of("string", "null"),
                    "description", "HTML body rendered for notification channels that support HTML."),
                "rawBody", Map.of(
                    "type", List.of("string", "null"),
                    "description", "Plain text or source body rendered for notification channels that do not use HTML."),
                "title", Map.of("type", "string", "minLength", 1, "description", "Rendered notification title.")),
            List.of("htmlBody", "rawBody", "title"));
        return objectSchema(Map.of(
                "name", Map.of("type", "string", "description", "Metadata name"),
                "reasonSelector", reasonSelectorSchema,
                "template", templateSchema),
            List.of("name", "reasonSelector", "template"));
    }

    private static Map<String, Object> systemProperty(String name, String type, String description) {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("type", type);
        result.put("description", description);
        return result;
    }

    private static Map<String, Object> systemPropertyOutputSchema() {
        return objectSchema(Map.of(
                "name", Map.of("type", "string"),
                "type", Map.of("type", "string"),
                "description", Map.of("type", "string")),
            List.of("name", "type", "description"));
    }

    private static Map<String, Object> listedMailTemplateOutputSchema() {
        return objectSchema(Map.of(
                "reasonType", REASON_TYPE_OUTPUT_SCHEMA,
                "template", NOTIFICATION_TEMPLATE_OUTPUT_SCHEMA),
            List.of("reasonType", "template"));
    }

    private static Map<String, Object> nullableObjectSchema(
        Map<String, Object> properties, List<String> required) {
        return Map.of(
            "type", List.of("object", "null"),
            "properties", properties,
            "required", required,
            "additionalProperties", false);
    }

    private static Map<String, Object> nullableStringSchema() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> objectSchema(
        Map<String, Object> properties, List<String> required) {
        return Map.of(
            "type", "object",
            "properties", properties,
            "required", required,
            "additionalProperties", false);
    }
}
