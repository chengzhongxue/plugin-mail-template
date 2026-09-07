package com.kunkunyu.template.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.Metadata;

class NotificationTemplateValidatorTest {

    private final NotificationTemplateValidator validator =
        new NotificationTemplateValidator();

    @Test
    void acceptsTemplateRenderedByHaloThymeleafEngine() {
        var template = template("""
            <p th:text="${loginTime} ?: ${#dates.format(#dates.createNow(),
                'yyyy-MM-dd HH:mm')}">登录时间</p>
            <a th:href="${site.url} ?: '#'" th:text="${subscriber.displayName}">
                查看站点
            </a>
            """);

        validator.validate(template, reasonType());
    }

    @Test
    void rejectsMalformedFallbackExpressionWithLocation() {
        var template = template("""
            <div>
              <span th:text="${loginTime} ?:
                  #dates.format(#dates.createNow(),'yyyy-MM-dd HH:mm')}">
                登录时间
              </span>
            </div>
            """);

        assertThatThrownBy(() -> validator.validate(template, reasonType()))
            .isInstanceOf(TemplateValidationException.class)
            .hasMessageContaining("HTML 正文模板语法错误")
            .hasMessageContaining("Could not parse as expression")
            .hasMessageNotContaining("<div>");
    }

    @Test
    void rejectsBlankTitle() {
        var template = template("<p>content</p>");
        template.setTitle("  ");

        assertThatThrownBy(() -> validator.validate(template, reasonType()))
            .isInstanceOf(TemplateValidationException.class)
            .hasMessageContaining("模板标题不能为空");
    }

    @Test
    void createsAttributesWhenReasonPropertiesAreMissing() {
        var reasonType = reasonType();
        reasonType.getSpec().setProperties(null);

        assertThat(validator.createReasonAttributes(reasonType)).isEmpty();
    }

    private static NotificationTemplate.Template template(String htmlBody) {
        var template = new NotificationTemplate.Template();
        template.setTitle("[(${site.title})] 模板测试");
        template.setHtmlBody(htmlBody);
        template.setRawBody("登录时间：[(${loginTime})]");
        return template;
    }

    private static ReasonType reasonType() {
        var property = new ReasonType.ReasonProperty();
        property.setName("loginTime");
        property.setType("string");

        var spec = new ReasonType.Spec();
        spec.setDisplayName("新设备登录");
        spec.setDescription("新设备登录提醒");
        spec.setProperties(List.of(property));

        var metadata = new Metadata();
        metadata.setName("new-device-login");

        var reasonType = new ReasonType();
        reasonType.setMetadata(metadata);
        reasonType.setSpec(spec);
        return reasonType;
    }
}
