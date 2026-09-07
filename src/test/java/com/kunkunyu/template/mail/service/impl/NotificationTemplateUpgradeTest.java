package com.kunkunyu.template.mail.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kunkunyu.template.mail.NotificationTemplateReconciler;
import com.kunkunyu.template.mail.service.NotificationTemplateValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import run.halo.app.core.extension.notification.NotificationTemplate;
import run.halo.app.core.extension.notification.ReasonType;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.controller.Reconciler;
import run.halo.app.notification.NotificationCenter;

class NotificationTemplateUpgradeTest {

    private static final String REASON_TYPE = "upgrade-test";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    private final ReactiveExtensionClient client = mock(ReactiveExtensionClient.class);
    private final Map<String, NotificationTemplate> resources = new ConcurrentHashMap<>();
    private final AtomicInteger clock = new AtomicInteger();
    private final MailTemplateServiceImpl service = new MailTemplateServiceImpl(
        client, mock(NotificationCenter.class), new NotificationTemplateValidator());
    private final NotificationTemplateReconciler reconciler =
        new NotificationTemplateReconciler(client, service);

    private String failingLanguage;

    @BeforeEach
    void setUp() {
        when(client.fetch(eq(NotificationTemplate.class), any(String.class)))
            .thenAnswer(invocation -> Mono.defer(() ->
                Mono.justOrEmpty(resources.get(invocation.getArgument(1)))));
        when(client.listAll(eq(NotificationTemplate.class), any(), any()))
            .thenAnswer(ignored -> Flux.defer(() -> Flux.fromIterable(resources.values())
                .sort(Comparator.comparing((NotificationTemplate template) ->
                    template.getMetadata().getCreationTimestamp()).reversed())));
        when(client.create(any(NotificationTemplate.class)))
            .thenAnswer(invocation -> Mono.fromCallable(() -> {
                NotificationTemplate template = invocation.getArgument(0);
                if (languageOf(template).equals(failingLanguage)) {
                    throw new IllegalStateException("create failed");
                }
                var sequence = clock.incrementAndGet();
                template.getMetadata().setName(
                    template.getMetadata().getGenerateName() + sequence);
                template.getMetadata().setCreationTimestamp(START.plusSeconds(sequence));
                resources.put(template.getMetadata().getName(), template);
                return template;
            }));
        when(client.delete(any(NotificationTemplate.class)))
            .thenAnswer(invocation -> Mono.fromCallable(() -> {
                NotificationTemplate template = invocation.getArgument(0);
                resources.remove(template.getMetadata().getName());
                return template;
            }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"template-one-upgrade-test", "template-one-upgrade-test-saved"})
    void retainsAllEditedContentWhenPluginUpgradeRecreatesBundledTemplate(String customName) {
        addTemplate("bundled", "default", "bundled v1");
        var custom = addTemplate(customName, "default", "user edits");
        assertThat(selected("default")).isSameAs(custom);

        // Halo removes packaged extensions on plugin stop and recreates them on start.
        resources.remove("bundled");
        var upgraded = addTemplate("bundled", "default", "bundled v2");
        assertThat(selected("default")).isSameAs(upgraded);

        assertThat(reconciler.reconcile(new Reconciler.Request("bundled")))
            .isEqualTo(Reconciler.Result.doNotRetry());

        var restored = selected("default");
        assertThat(restored.getMetadata().getName()).startsWith("template-one-upgrade-test-");
        assertThat(restored.getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(custom.getSpec().getTemplate());
        assertThat(restored.getSpec().getTemplate()).isNotSameAs(custom.getSpec().getTemplate());
        assertThat(resources).doesNotContainKey(customName).containsKey("bundled");
        assertThat(upgraded.getSpec().getTemplate().getTitle()).isEqualTo("bundled v2 title");

        reconciler.reconcile(new Reconciler.Request(restored.getMetadata().getName()));
        reconciler.reconcile(new Reconciler.Request("bundled"));
        verify(client, times(1)).create(any(NotificationTemplate.class));
    }

    @Test
    void keepsCurrentCustomTemplateWhenBundledTimestampDoesNotChange() {
        var bundled = addTemplate("bundled", "default", "bundled v1");
        var custom = addTemplate("template-one-upgrade-test", "default", "user edits");
        bundled.getSpec().getTemplate().setTitle("updated in place");

        reconciler.reconcile(new Reconciler.Request("bundled"));

        assertThat(selected("default")).isSameAs(custom);
        verify(client, never()).create(any(NotificationTemplate.class));
        verify(client, never()).delete(any(NotificationTemplate.class));
    }

    @Test
    void startupReconciliationPreservesDifferentLanguageEditsAndCoversNewLanguage() {
        var customDefault = addTemplate("template-one-upgrade-test", "default", "default edits");
        var customChinese = addTemplate("template-one-upgrade-test-zh", "zh_CN", "中文编辑");
        addTemplate("bundled-default", "default", "new default");
        addTemplate("bundled-zh", "zh_CN", "new Chinese");
        addTemplate("bundled-fr", "fr", "new French");

        // A controller starting after the upgrade also reconciles existing custom resources.
        reconciler.reconcile(new Reconciler.Request(customDefault.getMetadata().getName()));

        assertThat(selected("default").getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(customDefault.getSpec().getTemplate());
        assertThat(selected("zh_CN").getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(customChinese.getSpec().getTemplate());
        assertThat(selected("fr").getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(customDefault.getSpec().getTemplate());
        verify(client, times(3)).create(any(NotificationTemplate.class));
    }

    @Test
    void introducingLanguageKeepsAlreadySelectedDefaultCustomIdentity() {
        var custom = addTemplate("template-one-upgrade-test-current", "default", "user edits");
        addTemplate("bundled-zh", "zh_CN", "new Chinese");

        reconciler.reconcile(new Reconciler.Request("bundled-zh"));

        assertThat(selected("default")).isSameAs(custom);
        assertThat(selected("zh_CN").getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(custom.getSpec().getTemplate());
        verify(client, never()).delete(any(NotificationTemplate.class));
    }

    @Test
    void localizedOnlyCustomDoesNotReplaceUncustomizedLanguages() {
        var custom = addTemplate("template-one-upgrade-test-zh", "zh_CN", "中文编辑");
        var bundledDefault = addTemplate("bundled-default", "default", "new default");
        addTemplate("bundled-zh", "zh_CN", "new Chinese");

        reconciler.reconcile(new Reconciler.Request("bundled-zh"));

        assertThat(selected("default")).isSameAs(bundledDefault);
        assertThat(selected("zh_CN").getSpec().getTemplate())
            .usingRecursiveComparison().isEqualTo(custom.getSpec().getTemplate());
        verify(client, times(1)).create(any(NotificationTemplate.class));
    }

    @Test
    void missingOrDeletedCustomTemplatesAreNotRecreated() {
        addTemplate("bundled", "default", "bundled");
        reconciler.reconcile(new Reconciler.Request("bundled"));
        reconciler.reconcile(new Reconciler.Request("missing"));

        var deleted = addTemplate("template-one-upgrade-test", "default", "deleted edits");
        deleted.getMetadata().setDeletionTimestamp(Instant.now());
        reconciler.reconcile(new Reconciler.Request(deleted.getMetadata().getName()));
        reconciler.reconcile(new Reconciler.Request("bundled"));

        verify(client, never()).create(any(NotificationTemplate.class));
        verify(client, never()).delete(any(NotificationTemplate.class));
    }

    @Test
    void failedReconciliationRollsBackNewCopiesAndLeavesOriginalEditsForRetry() {
        var customDefault = addTemplate("template-one-upgrade-test", "default", "default edits");
        var customChinese = addTemplate("template-one-upgrade-test-zh", "zh_CN", "中文编辑");
        addTemplate("bundled-default", "default", "new default");
        addTemplate("bundled-zh", "zh_CN", "new Chinese");
        // Newest first: Chinese is created successfully, then the default copy fails.
        failingLanguage = "default";

        StepVerifier.create(service.restoreCustomTemplatePriority(REASON_TYPE))
            .expectErrorMessage("create failed")
            .verify(Duration.ofSeconds(3));

        assertThat(resources).hasSize(4)
            .containsEntry(customDefault.getMetadata().getName(), customDefault)
            .containsEntry(customChinese.getMetadata().getName(), customChinese);
        verify(client, never()).delete(customDefault);
        verify(client, never()).delete(customChinese);

        failingLanguage = null;
        StepVerifier.create(service.restoreCustomTemplatePriority(REASON_TYPE))
            .verifyComplete();
        assertThat(selected("default").getSpec().getTemplate().getTitle()).isEqualTo("default edits title");
        assertThat(selected("zh_CN").getSpec().getTemplate().getTitle()).isEqualTo("中文编辑 title");
    }

    @Test
    void reconciliationQueuedBehindRestoreDefaultsDoesNotResurrectUserEdits() {
        var custom = addTemplate("template-one-upgrade-test", "default", "user edits");
        addTemplate("bundled", "default", "bundled v2");
        when(client.fetch(ReasonType.class, REASON_TYPE)).thenReturn(Mono.just(new ReasonType()));
        var deleteEntered = Sinks.<Void>one();
        var finishDelete = Sinks.<Void>one();
        var deletionDone = Sinks.<Void>one();
        var reconciliationDone = Sinks.<Void>one();
        when(client.delete(custom)).thenReturn(Mono.defer(() -> {
            deleteEntered.tryEmitEmpty();
            return finishDelete.asMono().then(Mono.fromCallable(() -> {
                resources.remove(custom.getMetadata().getName());
                return custom;
            }));
        }));

        var deletion = service.deleteCustomTemplates(REASON_TYPE)
            .doOnSuccess(ignored -> deletionDone.tryEmitEmpty()).subscribe();
        deleteEntered.asMono().block(Duration.ofSeconds(2));
        var restoration = service.restoreCustomTemplatePriority(REASON_TYPE)
            .doOnSuccess(ignored -> reconciliationDone.tryEmitEmpty()).subscribe();
        try {
            StepVerifier.create(reconciliationDone.asMono())
                .expectTimeout(Duration.ofMillis(100)).verify();
            finishDelete.tryEmitEmpty();
            Mono.when(deletionDone.asMono(), reconciliationDone.asMono())
                .block(Duration.ofSeconds(2));
            assertThat(resources).doesNotContainKey(custom.getMetadata().getName());
            verify(client, never()).create(any(NotificationTemplate.class));
        } finally {
            deletion.dispose();
            restoration.dispose();
        }
    }

    private NotificationTemplate addTemplate(String name, String language, String content) {
        var metadata = new Metadata();
        metadata.setName(name);
        metadata.setCreationTimestamp(START.plusSeconds(clock.incrementAndGet()));
        var body = new NotificationTemplate.Template();
        body.setTitle(content + " title");
        body.setHtmlBody("<p>" + content + "</p>");
        body.setRawBody(content + " text");
        var selector = new NotificationTemplate.ReasonSelector();
        selector.setReasonType(REASON_TYPE);
        selector.setLanguage(language);
        var spec = new NotificationTemplate.Spec();
        spec.setReasonSelector(selector);
        spec.setTemplate(body);
        var template = new NotificationTemplate();
        template.setMetadata(metadata);
        template.setSpec(spec);
        resources.put(name, template);
        return template;
    }

    private NotificationTemplate selected(String language) {
        return resources.values().stream()
            .filter(template -> !ExtensionUtil.isDeleted(template))
            .filter(template -> language.equals(languageOf(template)))
            .max(Comparator.comparing(template -> template.getMetadata().getCreationTimestamp()))
            .orElseThrow();
    }

    private String languageOf(NotificationTemplate template) {
        return template.getSpec().getReasonSelector().getLanguage();
    }
}
