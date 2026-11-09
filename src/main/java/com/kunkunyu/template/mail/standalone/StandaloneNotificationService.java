package com.kunkunyu.template.mail.standalone;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/** Independent templates accept data only; they never execute scripts or fetch external resources. */
@Component
@RequiredArgsConstructor
public class StandaloneNotificationService {
    public static final String REASON = "mail-template-standalone-dispatch";
    private static final String LABEL = "mail-template.kunkunyu.com/custom-template";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s<>;,]+@[^@\\s<>;,]+\\.[^@\\s<>;,]+$");
    private static final SpringTemplateEngine ENGINE = engine();
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ReactiveExtensionClient client;
    private final NotificationCenter center;
    private final NotificationReasonEmitter emitter;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger rendering = new AtomicInteger();
    private long windowStart;
    private int sentInWindow;

    public record Input(String recipient, Map<String, Object> parameters) { }
    public record Rendered(String title, String htmlBody, String rawBody) { }

    public Mono<Rendered> preview(String id, Input input) {
        return prepare(id, input, false);
    }

    private Mono<Rendered> prepare(String id, Input input, boolean sending) {
        return Mono.defer(() -> {
            validate(input);
            return client.fetch(ConfigMap.class, id)
                .filter(cm -> cm.getMetadata().getDeletionTimestamp() == null
                    && cm.getMetadata().getLabels() != null
                    && "true".equals(cm.getMetadata().getLabels().get(LABEL))
                    && cm.getData() != null && "standalone".equals(cm.getData().get("mode")))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "独立模板不存在")))
                .flatMap(cm -> Mono.fromCallable(() -> {
                    if (sending && !"true".equals(cm.getData().get("enabled")))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "该独立模板尚未启用 API 发送");
                    if (rendering.incrementAndGet() > 4) {
                        rendering.decrementAndGet();
                        throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "模板渲染繁忙，请稍后重试");
                    }
                    try { return render(cm.getData(), input); }
                    finally { rendering.decrementAndGet(); }
                })
                    .subscribeOn(Schedulers.boundedElastic()));
        });
    }

    public Mono<Void> send(String id, Input input, String author) {
        return Mono.defer(() -> {
            if (!reserve()) return Mono.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "独立通知发送繁忙，请稍后重试（每分钟最多 30 次，同时最多 4 次）"));
            return prepare(id, input, true).flatMap(rendered -> {
                var identity = UserIdentity.anonymousWithEmail(input.recipient());
                var subscriber = new Subscription.Subscriber();
                subscriber.setName(identity.name());
                var interest = new Subscription.InterestReason();
                interest.setReasonType(REASON);
                interest.setSubject(Subscription.ReasonSubject.builder().apiVersion("v1alpha1")
                    .kind("User").name(identity.name()).build());
                // A separate subject per recipient prevents subscriptions leaking mail across recipients.
                return center.subscribe(subscriber, interest)
                    .then(Mono.defer(() -> emitter.emit(REASON, builder -> builder
                        .author(UserIdentity.of(author))
                        .subject(Reason.Subject.builder().apiVersion("v1alpha1").kind("User")
                            .name(identity.name()).title("自定义邮件通知").build())
                        .attributes(Map.of("mail", Map.of("title", rendered.title(),
                            "htmlBody", rendered.htmlBody(), "rawBody", rendered.rawBody()))))));
            }).timeout(Duration.ofSeconds(30)).doFinally(signal -> active.decrementAndGet());
        });
    }

    private synchronized boolean reserve() {
        long now = System.nanoTime();
        if (now - windowStart >= Duration.ofMinutes(1).toNanos()) {
            windowStart = now;
            sentInWindow = 0;
        }
        if (sentInWindow >= 30 || active.get() >= 4) return false;
        sentInWindow++;
        active.incrementAndGet();
        return true;
    }

    static void validate(Input input) {
        if (input == null || input.recipient() == null || input.recipient().length() > 254
            || !EMAIL.matcher(input.recipient()).matches()) throw bad("请提供一个有效的收件邮箱");
        if (input.parameters() == null) throw bad("parameters 必须是 JSON 对象");
        validateValue(input.parameters(), 0, new AtomicInteger());
        try {
            if (JSON.writeValueAsBytes(input.parameters()).length > 48 * 1024)
                throw bad("参数不能超过 48 KiB");
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw bad("参数必须是 JSON 数据");
        }
    }

    private static void validateValue(Object value, int depth, AtomicInteger count) {
        if (depth > 16 || count.incrementAndGet() > 2000) throw bad("参数最多嵌套 16 层、包含 2000 个节点");
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                if (!(key instanceof String name) || name.isBlank() || name.length() > 100)
                    throw bad("参数名称长度必须为 1–100 个字符");
                validateValue(child, depth + 1, count);
            });
        } else if (value instanceof Iterable<?> values) {
            values.forEach(child -> validateValue(child, depth + 1, count));
        } else if (value != null && !(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw bad("参数仅支持 JSON 数据类型");
        }
    }

    static Rendered render(Map<String, String> data, Input input) {
        String title = data.getOrDefault("title", "");
        String html = data.getOrDefault("htmlBody", "");
        String raw = data.getOrDefault("rawBody", "");
        if (title.isBlank() || title.length() > 10_000 || html.length() > 256 * 1024 || raw.length() > 256 * 1024)
            throw bad("标题不能为空，标题最多 10000 字符，正文最多 256 Ki 字符");
        var model = new HashMap<>(input.parameters());
        model.put("recipient", input.recipient());
        try {
            var context = new Context(Locale.getDefault(), model);
            var result = new Rendered(process(title, context, 10_000), process(html, context, 512 * 1024),
                process(raw, context, 512 * 1024));
            if (result.title().isBlank() || result.title().length() > 10_000
                || result.title().contains("\r") || result.title().contains("\n")
                || result.htmlBody().length() > 512 * 1024 || result.rawBody().length() > 512 * 1024)
                throw bad("渲染结果超过限制或邮件标题包含换行");
            return result;
        } catch (org.thymeleaf.exceptions.TemplateEngineException error) {
            throw bad("模板渲染失败，请检查表达式和参数结构");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static String process(String template, Context context, int limit) {
        var output = new StringBuilder();
        var writer = new java.io.Writer() {
            @Override public void write(char[] chars, int offset, int length) {
                if (output.length() + length > limit) throw bad("模板渲染结果超过大小限制");
                output.append(chars, offset, length);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        ENGINE.process(template, context, writer);
        return output.toString();
    }

    private static SpringTemplateEngine engine() {
        var resolver = new StringTemplateResolver();
        resolver.setCacheable(false);
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
