package io.runcycles.events.service;

import io.runcycles.events.model.EventCategory;
import io.runcycles.events.model.EventType;

/**
 * Self-contained, I/O-free evaluation of the DELIVERY half of governance
 * WEBHOOK SUBSCRIPTION INVARIANT 2 (cycles-governance-admin-v0.1.25):
 *
 * <blockquote>a subscription owned by a CONCRETE tenant (owning {@code tenant_id}
 * present and != {@code "__system__"}) MUST NOT carry / be delivered admin-only
 * event types or categories (api_key, policy, webhook, system).</blockquote>
 *
 * <p>The admin service (cycles-server-admin) enforces this at subscription
 * write time and at ENQUEUE (dispatch) time. This worker performs the actual
 * HTTP send, retries, and recovered-processing redeliveries, so it re-applies
 * the SAME predicate immediately before every send — the LAST-MILE guarantee.
 * That closes deliveries queued BEFORE this version deployed (evaluated at send
 * time), every retry, and every recovered/orphaned redelivery, none of which
 * pass back through the admin enqueue check. Issue runcycles/cycles-server-admin#209.
 *
 * <p>Deliberately mirrors cycles-server-admin's
 * {@code WebhookDispatchService.isBlockedByOwnershipBoundary} +
 * {@code WebhookSubscription.isSystemOwner} semantics EXACTLY, implemented
 * against THIS service's own {@link EventType}/{@link EventCategory}/subscription
 * models (no dependency on admin classes):
 * <ul>
 *   <li>Owner classification: {@code tenant_id == null} or the literal
 *       {@code "__system__"} sentinel is SYSTEM-owned (receives everything); any
 *       other value — including a blank/whitespace-only string — is CONCRETE and
 *       subject to the boundary.</li>
 *   <li>Admin-only classification is FAIL-CLOSED and checks BOTH dimensions,
 *       because {@code Event.category} is an independent cross-plane field (not
 *       always derived from the type): block if the type is admin-only, OR the
 *       category is admin-only, OR the record is unclassifiable (neither the
 *       type nor the category resolves to a known tenant-accessible value).
 *       So a tenant-accessible type carrying an admin-only category is blocked,
 *       and a typeless/categoryless record is blocked.</li>
 * </ul>
 */
public final class WebhookOwnershipBoundary {

    /** Sentinel owning-tenant for non-tenant-owned (operator-owned) subscriptions. */
    public static final String SYSTEM_TENANT = "__system__";

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
        // Concrete owner: fail-closed. Block if EITHER dimension is admin-only,
        // AND block a record that cannot be positively classified as
        // tenant-accessible by EITHER dimension (unknown/blank type AND
        // unknown/blank category) — a malformed or unknown-vocabulary record must
        // not reach a tenant-controlled endpoint.
        EventType type = EventType.fromValueOrNull(eventTypeValue);
        EventCategory category = EventCategory.fromValue(categoryValue);
        boolean typeAdmin = type != null && !type.isTenantAccessible();
        boolean categoryAdmin = category != null && !category.isTenantAccessible();
        boolean unclassifiable = type == null && category == null;
        return typeAdmin || categoryAdmin || unclassifiable;
    }
}
