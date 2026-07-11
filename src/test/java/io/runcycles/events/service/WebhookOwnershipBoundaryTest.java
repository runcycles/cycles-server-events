package io.runcycles.events.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for the self-contained delivery-side WEBHOOK SUBSCRIPTION
 * INVARIANT 2 predicate (#209). Mirrors cycles-server-admin's
 * WebhookDispatchService.isBlockedByOwnershipBoundary semantics.
 */
class WebhookOwnershipBoundaryTest {

    // --- Owner classification (isSystemOwner) ---

    @Test
    void isSystemOwner_nullAndSentinel_true_blankAndConcrete_false() {
        assertThat(WebhookOwnershipBoundary.isSystemOwner(null)).isTrue();
        assertThat(WebhookOwnershipBoundary.isSystemOwner("__system__")).isTrue();
        // Blank is CONCRETE per admin semantics (isSystemOwner exempts only null / literal sentinel).
        assertThat(WebhookOwnershipBoundary.isSystemOwner("   ")).isFalse();
        assertThat(WebhookOwnershipBoundary.isSystemOwner("t-1")).isFalse();
    }

    // --- System owner: never blocked ---

    @Test
    void systemOwner_adminEvent_notBlocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("api_key.revoked", "api_key", null)).isFalse();
        assertThat(WebhookOwnershipBoundary.isBlocked("policy.updated", "policy", "__system__")).isFalse();
    }

    // --- Concrete owner: admin-only blocked ---

    @Test
    void concreteOwner_adminType_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("api_key.revoked", "api_key", "t-1")).isTrue();
        assertThat(WebhookOwnershipBoundary.isBlocked("policy.created", "policy", "t-1")).isTrue();
        assertThat(WebhookOwnershipBoundary.isBlocked("webhook.disabled", "webhook", "t-1")).isTrue();
        assertThat(WebhookOwnershipBoundary.isBlocked("system.high_latency", "system", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_tenantAccessibleEvent_notBlocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.created", "budget", "t-1")).isFalse();
        assertThat(WebhookOwnershipBoundary.isBlocked("reservation.denied", "reservation", "t-1")).isFalse();
        assertThat(WebhookOwnershipBoundary.isBlocked("tenant.created", "tenant", "t-1")).isFalse();
    }

    // --- Fail-closed: independent type/category dimensions ---

    @Test
    void concreteOwner_tenantTypeButAdminCategory_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("tenant.created", "webhook", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_adminTypeButTenantCategory_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("api_key.revoked", "budget", "t-1")).isTrue();
    }

    // --- Fail-closed: unclassifiable ---

    @Test
    void concreteOwner_bothNull_unclassifiable_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked(null, null, "t-1")).isTrue();
    }

    @Test
    void concreteOwner_unknownTypeAndUnknownCategory_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("mystery.event", "mystery", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_unknownTypeButTenantAccessibleCategory_notBlocked() {
        // A future/unknown tenant event still carrying a tenant-accessible
        // category is positively classifiable — must NOT be over-blocked.
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.brand_new", "budget", "t-1")).isFalse();
    }

    @Test
    void concreteOwner_tenantAccessibleTypeButNullCategory_notBlocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.created", null, "t-1")).isFalse();
    }
}
