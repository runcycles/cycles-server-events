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

    // --- Fail-closed: unclassifiable / unknown / blank (raw-string allowlist) ---

    @Test
    void concreteOwner_bothNull_unclassifiable_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked(null, null, "t-1")).isTrue();
    }

    @Test
    void concreteOwner_unknownTypeAndUnknownCategory_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("mystery.event", "mystery", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_versionSkew_futureAdminLookingType_withTenantCategory_blocked() {
        // The DEFECT the raw-string allowlist fixes: a future admin event type
        // the enum has not learned yet ("system.*"/"api_key.*") reaches an old
        // worker; the type is unknown-to-the-enum but its NAMESPACE is admin, so
        // it must be BLOCKED even though the category happens to be tenant-accessible.
        assertThat(WebhookOwnershipBoundary.isBlocked("system.new_event", "tenant", "t-1")).isTrue();
        assertThat(WebhookOwnershipBoundary.isBlocked("api_key.future_event", "budget", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_tenantTypeButFutureUnknownCategory_blocked() {
        // A tenant-namespace type but an unknown/future category (not in the
        // tenant set) is a supplied-dimension violation → BLOCKED (fail-closed).
        assertThat(WebhookOwnershipBoundary.isBlocked("tenant.created", "future_admin_category", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_blankSuppliedType_withTenantCategory_blocked() {
        // A present-but-blank type is a supplied dimension that fails the tenant
        // namespace → BLOCKED even though the category is tenant-accessible.
        assertThat(WebhookOwnershipBoundary.isBlocked("   ", "tenant", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_blankSuppliedCategory_withTenantType_blocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.created", "   ", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_categoryLikePrefixOnly_notInSet_blocked() {
        // "budget" is tenant, but a mere prefix-looking category value that is
        // not EXACTLY in the set is blocked (categories are an exact-match set,
        // not a namespace).
        assertThat(WebhookOwnershipBoundary.isBlocked("tenant.created", "budgetary", "t-1")).isTrue();
    }

    @Test
    void concreteOwner_futureButTenantNamespacedType_allowed() {
        // Positive namespace match: a genuinely future tenant type is ALLOWED
        // (with a tenant category, or with no category at all).
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.new_thing", "budget", "t-1")).isFalse();
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.new_thing", null, "t-1")).isFalse();
    }

    @Test
    void concreteOwner_tenantAccessibleTypeButNullCategory_notBlocked() {
        assertThat(WebhookOwnershipBoundary.isBlocked("budget.created", null, "t-1")).isFalse();
    }

    @Test
    void concreteOwner_nullTypeButTenantCategory_notBlocked() {
        // A null (absent) type with a positively tenant-accessible category is
        // deliverable — null is "absent", not a supplied violation.
        assertThat(WebhookOwnershipBoundary.isBlocked(null, "reservation", "t-1")).isFalse();
    }

    @Test
    void tenantAccessibleHelpers_rawStringSemantics() {
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleType("reservation.expired")).isTrue();
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleType("policy.created")).isFalse();
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleType(null)).isFalse();
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleCategory("budget")).isTrue();
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleCategory("system")).isFalse();
        assertThat(WebhookOwnershipBoundary.isTenantAccessibleCategory(null)).isFalse();
    }
}
