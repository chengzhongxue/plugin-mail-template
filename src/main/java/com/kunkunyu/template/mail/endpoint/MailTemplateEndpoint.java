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
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailTemplateEndpoint implements CustomEndpoint {

    private final MailTemplateService mailTemplateService;



    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "api.mail.template.kunkunyu.com/v1alpha1/MailTemplate";
        return SpringdocRouteBuilder.route()
            .POST("mailtemplates/{reasonTypeName}/verify-send", this::verifySend,
                builder -> builder.operationId("VerifySend")
                    .tag(tag)
                    .description("验证模板")
                    .parameter(parameterBuilder().name("reasonTypeName")
                        .in(ParameterIn.PATH)
                        .required(true)
                        .implementation(String.class))
                    .response(responseBuilder()
                        .implementation(Void.class))
            ).build();
    }

    private Mono<ServerResponse> verifySend(ServerRequest request) {
        var reasonTypeName = request.pathVariable("reasonTypeName");
        return mailTemplateService.verifyMailTemplatSend(reasonTypeName)
            .then(ServerResponse.ok().build());
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.mail.template.kunkunyu.com/v1alpha1");
    }
}
