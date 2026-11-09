package com.kunkunyu.template.mail.endpoint;

import com.kunkunyu.template.mail.standalone.StandaloneNotificationService;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class StandaloneNotificationEndpoint implements CustomEndpoint {
    private final StandaloneNotificationService service;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .POST("standalone-notifications/{templateId}/send", request -> authenticated(request)
                .flatMap(author -> body(request).flatMap(input -> service.send(
                    request.pathVariable("templateId"), input, author)))
                .then(ServerResponse.accepted().bodyValue(java.util.Map.of("status", "accepted"))))
            .POST("standalone-notifications/{templateId}/preview", request -> authenticated(request)
                .then(body(request)).flatMap(input -> service.preview(request.pathVariable("templateId"), input))
                .flatMap(value -> ServerResponse.ok().bodyValue(value)))
            .build();
    }

    private Mono<String> authenticated(ServerRequest request) {
        // Halo RBAC also checks the endpoint resource; never register anonymous permissions.
        return request.principal().filter(principal -> principal instanceof Authentication auth
                && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken))
            .map(java.security.Principal::getName)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    private Mono<StandaloneNotificationService.Input> body(ServerRequest request) {
        return DataBufferUtils.join(request.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), 64 * 1024)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空")))
            .map(buffer -> {
                try {
                    return JSON.readValue(buffer.toString(StandardCharsets.UTF_8),
                        StandaloneNotificationService.Input.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体必须包含 recipient 和 parameters JSON 对象");
                } finally {
                    DataBufferUtils.release(buffer);
                }
            }).onErrorMap(DataBufferLimitException.class, error ->
                new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "请求体最多 64 KiB"));
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.mail.template.kunkunyu.com/v1alpha1");
    }
}
