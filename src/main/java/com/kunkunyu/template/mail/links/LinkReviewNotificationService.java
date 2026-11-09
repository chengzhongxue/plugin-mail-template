package com.kunkunyu.template.mail.links;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Unstructured;
import run.halo.app.extension.Watcher;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

/** Watches the Links plugin without registering or modifying its extension schema. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LinkReviewNotificationService {
    public static final String APPROVED = "mail-template-link-application-approved";
    public static final String REJECTED = "mail-template-link-application-rejected";

    private final ReactiveExtensionClient client;
    private final NotificationCenter notificationCenter;
    private final NotificationReasonEmitter reasonEmitter;
    private Watcher watcher;
    private Disposable worker;

    public synchronized void start() {
        if (watcher != null && !watcher.isDisposed()) {
            return;
        }
        var queue = Sinks.many().unicast().onBackpressureBuffer(new ArrayBlockingQueue<Review>(256));
        worker = queue.asFlux()
            .publishOn(Schedulers.boundedElastic())
            .concatMap(review -> Mono.defer(() -> publish(review)).timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)))
                .doOnError(error -> log.error(
                    "Failed to notify applicant for link application {} ({})",
                    review.name(), review.status(), error))
                .onErrorResume(error -> Mono.empty()), 1)
            .subscribe();
        watcher = new Watcher() {
            private volatile boolean disposed;
            private Runnable disposeHook;

            @Override
            public synchronized void onUpdate(Extension oldExtension, Extension newExtension) {
                if (disposed) {
                    return;
                }
                try {
                    var review = reviewTransition(oldExtension, newExtension);
                    if (review != null && queue.tryEmitNext(review).isFailure()) {
                        log.error("Unable to queue link review notification for {}", review.name());
                    }
                } catch (RuntimeException error) {
                    log.warn("Unable to inspect link review update; ignoring notification", error);
                }
            }

            @Override
            public void registerDisposeHook(Runnable hook) {
                disposeHook = hook;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public synchronized void dispose() {
                if (disposed) return;
                disposed = true;
                if (disposeHook != null) {
                    disposeHook.run();
                }
            }
        };
        try {
            client.watch(watcher);
        } catch (RuntimeException error) {
            stop();
            log.error("Unable to start link review notifications", error);
        }
    }

    public synchronized void stop() {
        try {
            if (watcher != null) watcher.dispose();
        } catch (RuntimeException error) {
            log.warn("Unable to remove link review watcher", error);
        } finally {
            watcher = null;
            if (worker != null) worker.dispose();
            worker = null;
        }
    }

    Mono<Void> publish(Review review) {
        var reasonType = "APPROVED".equals(review.status()) ? APPROVED : REJECTED;
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(review.email()).name());
        var interest = new Subscription.InterestReason();
        interest.setReasonType(reasonType);
        interest.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion("core.halo.run/v1alpha1")
            .kind("LinkApplication")
            .name(review.name())
            .build());
        var subject = Reason.Subject.builder()
            .apiVersion("core.halo.run/v1alpha1")
            .kind("LinkApplication")
            .name(review.name())
            .title(review.displayName())
            .build();
        // Keep the subscription: Halo resolves recipients asynchronously after emit completes.
        return notificationCenter.subscribe(subscriber, interest)
            .then(Mono.defer(() -> reasonEmitter.emit(reasonType, builder -> builder
                .author(UserIdentity.of("system"))
                .subject(subject)
                .attributes(Map.of(
                    "displayName", review.displayName(),
                    "websiteUrl", review.websiteUrl(),
                    "reviewResult", "APPROVED".equals(review.status()) ? "审核通过" : "审核未通过"
                )))));
    }

    static Review reviewTransition(Extension oldExtension, Extension newExtension) {
        if (oldExtension == null || newExtension == null
            || !"core.halo.run/v1alpha1".equals(newExtension.getApiVersion())
            || !"LinkApplication".equals(newExtension.getKind())
            || newExtension.getMetadata() == null
            || newExtension.getMetadata().getDeletionTimestamp() != null) {
            return null;
        }
        var oldData = data(oldExtension);
        var newData = data(newExtension);
        var previous = value(oldData, "status");
        var status = value(newData, "status");
        if (!("PENDING".equals(previous) || "APPROVING".equals(previous))
            || !("APPROVED".equals(status) || "REJECTED".equals(status))) {
            return null;
        }
        var email = value(newData, "email").trim();
        if (StringUtils.isBlank(email)) {
            log.warn("Link application {} was reviewed without a contact email; skipping notification",
                newExtension.getMetadata().getName());
            return null;
        }
        return new Review(newExtension.getMetadata().getName(), status, email,
            reviewedValue(newData, status, "displayName"), reviewedValue(newData, status, "url"));
    }

    private static String reviewedValue(Map<?, ?> data, String status, String field) {
        if ("APPROVED".equals(status) && data.get("spec") instanceof Map<?, ?> spec
            && spec.get("approval") instanceof Map<?, ?> approval
            && approval.get("request") instanceof Map<?, ?> request
            && request.get(field) instanceof String approvedValue) {
            return approvedValue;
        }
        return value(data, field);
    }

    private static Map<?, ?> data(Extension extension) {
        return extension instanceof Unstructured unstructured ? unstructured.getData()
            : Unstructured.OBJECT_MAPPER.convertValue(extension, Map.class);
    }

    private static String value(Map<?, ?> data, String field) {
        if (!(data.get("spec") instanceof Map<?, ?> spec)) {
            return "";
        }
        return Objects.toString(spec.get(field), "");
    }

    record Review(String name, String status, String email, String displayName, String websiteUrl) {
    }
}
