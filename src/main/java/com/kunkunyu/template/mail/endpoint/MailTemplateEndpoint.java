package com.kunkunyu.template.mail.endpoint;

import com.kunkunyu.template.mail.service.MailTemplateService;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailTemplateEndpoint implements CustomEndpoint {

    private final MailTemplateService mailTemplateService;



    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.mail.template.kunkunyu.com/v1alpha1/MailTemplate";
        return SpringdocRouteBuilder.route()
            .GET("mailtemplates/{reasonTypeName}/pending-reasons",
                this::pendingReasons,
                builder -> builder.operationId("CountPendingMailReasons")
                    .tag(tag)
                    .description("统计修复模板后可能被 Halo 重新投递的待处理通知")
                    .parameter(reasonTypeParameter())
                    .response(responseBuilder()
                        .responseCode("200")
                        .implementation(PendingReasonStatus.class))
            )
            .PUT("mailtemplates/{reasonTypeName}", this::save,
                builder -> builder.operationId("SaveMailTemplate")
                    .tag(tag)
                    .description("校验并保存一个始终优先于旧版本的自定义模板")
                    .parameter(reasonTypeParameter())
                    .parameter(parameterBuilder().name("allowPendingReplay")
                        .in(ParameterIn.QUERY)
                        .required(false)
                        .description("存在待处理通知时，是否明确允许 Halo 补发")
                        .implementation(Boolean.class))
                    .requestBody(templateRequestBody())
                    .response(responseBuilder()
                        .responseCode("200")
                        .implementation(NotificationTemplate.class))
            )
            .DELETE("mailtemplates/{reasonTypeName}", this::delete,
                builder -> builder.operationId("DeleteCustomMailTemplates")
                    .tag(tag)
                    .description("删除此通知类型由插件管理的全部自定义模板")
                    .parameter(reasonTypeParameter())
                    .response(responseBuilder()
                        .responseCode("204")
                        .implementation(Void.class))
            )
            .POST("mailtemplates/{reasonTypeName}/validate", this::validate,
                builder -> builder.operationId("ValidateMailTemplate")
                    .tag(tag)
                    .description("使用 Halo 当前的 Thymeleaf 引擎校验邮件模板")
                    .parameter(reasonTypeParameter())
                    .requestBody(templateRequestBody())
                    .response(responseBuilder()
                        .responseCode("204")
                        .implementation(Void.class))
            )
            .POST("mailtemplates/{reasonTypeName}/verify-send", this::verifySend,
                builder -> builder.operationId("SendMailTemplateVerification")
                    .tag(tag)
                    .description("同步校验并渲染测试通知，然后交给 Halo 异步投递；"
                        + "204 不代表 SMTP 已送达，请同时检查邮箱和 Halo 日志")
                    .parameter(reasonTypeParameter())
                    .parameter(parameterBuilder().name("templateName")
                        .in(ParameterIn.QUERY)
                        .required(true)
                        .description("刚保存并准备测试的模板 metadata.name")
                        .implementation(String.class))
                    .response(responseBuilder()
                        .responseCode("204")
                        .implementation(Void.class))
            ).build();
    }

    private Mono<ServerResponse> save(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        var allowPendingReplay = request.queryParam("allowPendingReplay")
            .map(Boolean::parseBoolean)
            .orElse(false);
        return readTemplate(request)
            .flatMap(template ->
                mailTemplateService.saveTemplate(
                    reasonTypeName, template, allowPendingReplay))
            .flatMap(saved -> ServerResponse.ok().bodyValue(saved));
    }

    private Mono<ServerResponse> pendingReasons(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        return mailTemplateService.countPendingReasons(reasonTypeName)
            .map(PendingReasonStatus::new)
            .flatMap(status -> ServerResponse.ok().bodyValue(status));
    }

    private Mono<ServerResponse> delete(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        return mailTemplateService.deleteCustomTemplates(reasonTypeName)
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> validate(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        return readTemplate(request)
            .flatMap(template ->
                mailTemplateService.validateTemplate(reasonTypeName, template))
            .then(ServerResponse.noContent().build());
    }

    private Mono<ServerResponse> verifySend(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        var templateName = request.queryParam("templateName")
            .filter(org.springframework.util.StringUtils::hasText)
            .orElseThrow(() -> new ServerWebInputException(
                "templateName 不能为空"));
        return mailTemplateService.sendVerification(reasonTypeName, templateName)
            .then(ServerResponse.noContent().build());
    }

    private Mono<NotificationTemplate.Template> readTemplate(ServerRequest request) {
        return request.bodyToMono(NotificationTemplate.Template.class)
            .switchIfEmpty(Mono.error(() ->
                new ServerWebInputException("模板内容不能为空")));
    }

    private static org.springdoc.core.fn.builders.parameter.Builder
        reasonTypeParameter() {
        return parameterBuilder().name("reasonTypeName")
            .in(ParameterIn.PATH)
            .required(true)
            .implementation(String.class);
    }

    private static org.springdoc.core.fn.builders.requestbody.Builder
        templateRequestBody() {
        return requestBodyBuilder()
            .required(true)
            .implementation(NotificationTemplate.Template.class);
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.mail.template.kunkunyu.com/v1alpha1");
    }

    public record PendingReasonStatus(long count) {
    }
}
