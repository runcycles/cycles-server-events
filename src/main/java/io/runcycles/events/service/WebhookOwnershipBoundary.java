package io.runcycles.events.service;

import io.runcycles.events.model.EventCategory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Self-contained, I/O-free evaluation of the DELIVERY half of governance
 * WEBHOOK SUBSCRIPTION INVARIANT 2 (cycles-governance-admin-v0.1.25,
 * ~lines 282-304):
 *
 * <blockquote>a subscription owned by a CONCRETE tenant (owning {@code tenant_id}
 * present and != {@code "__system__"}) MUST NOT carry / be delivered admin-only
 * event types or categories. Its {@code event_types} MUST be drawn only from
 * {@code budget.*} / {@code reservation.*} / {@code tenant.*}, and its
 * {@code event_categories} only from {@code budget} / {@code reservation} /
 * {@code tenant}; an admin-only type or category ({@code api_key}, {@code policy},
 * {@code webhook}, {@code system}) MUST be rejected.</blockquote>
 *
 * <p>The admin service (cycles-server-admin) enforces this at subscription
 * write time and at ENQUEUE (dispatch) time. This worker performs the actual
 * HTTP send, retries, and recovered-processing redeliveries, so it re-applies
 * the boundary immediately before every send — the LAST-MILE guarantee. That
 * closes deliveries queued BEFORE this version deployed (evaluated at send
 * time), every retry, and every recovered/orphaned redelivery, none of which
 * pass back through the admin enqueue check. Issue runcycles/cycles-server-admin#209.
 *
 * <h2>Fail-closed classification via a RAW-STRING allowlist (version-skew safe)</h2>
 * The classification does NOT depend on this service's {@link EventCategory}
 * enum resolving the value, because that would fail OPEN under version skew:
 * this worker deliberately preserves unknown event strings byte-for-byte, so a
 * NEWLY-introduced admin event type (a future {@code system.*} / {@code api_key.*})
 * reaches an old worker before its enum knows the value. An enum-only check
 * ({@code fromValue(...) == null → "unknown, ignore"}) would let such a value
 * slip through whenever the OTHER dimension happened to be tenant-accessible.
 *
 * <p>Instead, for a concrete-tenant subscription the delivery is ALLOWED only if
 * <b>every SUPPLIED (non-null) selector dimension is POSITIVELY tenant-accessible
 * by raw string</b>, AND <b>at least one dimension positively classifies</b>:
 * <ul>
 *   <li><b>type</b> is tenant-accessible IFF the raw string starts with a tenant
 *       NAMESPACE prefix — {@code "budget."} / {@code "reservation."} /
 *       {@code "tenant."}. A future {@code "budget.new_thing"} is correctly
 *       allowed; a future/unknown {@code "system.*"} / {@code "api_key.*"} /
 *       anything-else is BLOCKED.</li>
 *   <li><b>category</b> is tenant-accessible IFF the raw string is exactly one of
 *       {@code {budget, reservation, tenant}}. Any other / unknown / blank
 *       supplied category is BLOCKED.</li>
 * </ul>
 * So the delivery is BLOCKED when: a supplied {@code type} is not in the tenant
 * namespaces, OR a supplied {@code category} is not in the tenant set, OR neither
 * dimension positively classifies as tenant-accessible (a typeless+categoryless,
 * blank, or wholly-unknown record). A {@code null} dimension is "absent" (not a
 * violation by itself); a present-but-blank/whitespace or non-tenant value is a
 * violation. This matches admin's fail-closed intent while remaining correct for
 * event vocabulary this worker's enum has not yet learned.
 *
 * <p>Owner classification mirrors admin's {@code WebhookSubscription.isSystemOwner}
 * EXACTLY: only {@code null}/omitted and the literal {@code "__system__"} sentinel
 * are system-owned (receive everything); any other value — including a
 * blank/whitespace-only string — is CONCRETE and subject to the boundary.
 *
 * <p>The tenant category set and the tenant type-namespace prefixes are DERIVED
 * from {@link EventCategory#isTenantAccessible()} (the single source of truth,
 * to which {@link io.runcycles.events.model.EventType#isTenantAccessible()} also
 * delegates), relying on the governance alignment that a tenant type namespace is
 * the tenant category value followed by {@code "."} (budget/reservation/tenant).
 */
public final class WebhookOwnershipBoundary {

    /** Sentinel owning-tenant for non-tenant-owned (operator-owned) subscriptions. */
    public static final String SYSTEM_TENANT = "__system__";

    /**
     * Raw tenant-accessible category values ({@code budget}, {@code reservation},
     * {@code tenant}) and their type-namespace prefixes ({@code budget.}, …).
     * Derived from the enum so there is ONE source of truth, but matched as raw
     * strings so unknown-to-the-enum future values are still classified.
     */
    private static final Set<String> TENANT_CATEGORIES;
    private static final Set<String> TENANT_TYPE_NAMESPACES;

    static {
        Set<String> categories = new LinkedHashSet<>();
        Set<String> namespaces = new LinkedHashSet<>();
        for (EventCategory c : EventCategory.values()) {
            if (c.isTenantAccessible()) {
                categories.add(c.getValue());
                namespaces.add(c.getValue() + ".");
            }
        }
        TENANT_CATEGORIES = Set.copyOf(categories);
        TENANT_TYPE_NAMESPACES = Set.copyOf(namespaces);
    }

    private WebhookOwnershipBoundary() {
    }

    /**
     * True when the owning tenant is system-owned (not tenant-owned), and thus
     * exempt from the boundary. Matches admin's
     * {@code WebhookSubscription.isSystemOwner}: ONLY a {@code null}/omitted
     * owner and the literal {@code "__system__"} sentinel are exempt; a blank
     * (whitespace-only) tenant_id is treated as CONCRETE.
     */
    public static boolean isSystemOwner(String subscriptionTenantId) {
        return subscriptionTenantId == null || SYSTEM_TENANT.equals(subscriptionTenantId);
    }

    /** True IFF the raw event-type string is in a tenant type namespace. */
    static boolean isTenantAccessibleType(String eventTypeValue) {
        if (eventTypeValue == null) {
            return false;
        }
        for (String namespace : TENANT_TYPE_NAMESPACES) {
            if (eventTypeValue.startsWith(namespace)) {
                return true;
            }
        }
        return false;
    }

    /** True IFF the raw category string is exactly a tenant-accessible category. */
    static boolean isTenantAccessibleCategory(String categoryValue) {
        return categoryValue != null && TENANT_CATEGORIES.contains(categoryValue);
    }

    /**
     * True when this event MUST NOT be delivered to a subscription with the
     * given owning tenant. I/O-free; safe to call immediately before every HTTP
     * send attempt (initial, retry, recovered).
     *
     * @param eventTypeValue       the event's {@code event_type} wire value
     * @param categoryValue        the event's {@code category} wire value
     * @param subscriptionTenantId the OWNING tenant of the target subscription
     */
    public static boolean isBlocked(String eventTypeValue, String categoryValue,
                                    String subscriptionTenantId) {
        if (isSystemOwner(subscriptionTenantId)) {
            return false; // system-owned subs legitimately receive admin-only events
        }
        // Concrete owner: fail-closed per SUPPLIED dimension, raw-string allowlist.
        boolean typeTenant = isTenantAccessibleType(eventTypeValue);
        boolean categoryTenant = isTenantAccessibleCategory(categoryValue);
        // A supplied (non-null) dimension MUST positively classify; a present-but-
        // blank/unknown/admin value fails its clause. A null dimension is absent.
        boolean typeClauseOk = eventTypeValue == null || typeTenant;
        boolean categoryClauseOk = categoryValue == null || categoryTenant;
        boolean atLeastOnePositive = typeTenant || categoryTenant;
        boolean deliver = typeClauseOk && categoryClauseOk && atLeastOnePositive;
        return !deliver;
    }
}
