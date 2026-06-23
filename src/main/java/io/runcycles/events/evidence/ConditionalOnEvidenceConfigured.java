package io.runcycles.events.evidence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Evidence signing is enabled only when the issuing server identity is set.
 * A blank server_id means evidence is intentionally off for this deployment.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${cycles.evidence.server-id:}')")
public @interface ConditionalOnEvidenceConfigured {
}
