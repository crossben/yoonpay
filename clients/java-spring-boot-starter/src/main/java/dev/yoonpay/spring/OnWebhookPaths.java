package dev.yoonpay.spring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** True when {@code yoon.webhook.paths} lists at least one path (comma-separated or YAML list). */
class OnWebhookPaths implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("yoon.webhook.paths", Bindable.listOf(String.class))
                .map(paths -> !paths.isEmpty())
                .orElse(false);
    }
}
