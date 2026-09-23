package com.hourhive.domain;

/**
 * Booking state machine. Every status change in {@code BookingService} goes through
 * {@link #canMoveTo(BookingStatus)}, so an illegal transition is a bug caught at the source
 * rather than a row silently left in a bad state.
 *
 * <pre>
 * REQUESTED -&gt; ACCEPTED | DECLINED | CANCELLED
 * ACCEPTED  -&gt; COMPLETED | CANCELLED | REQUESTED   (learner reschedules; provider must reconfirm)
 * COMPLETED, DECLINED, CANCELLED are terminal.
 * </pre>
 */
public enum BookingStatus {
    REQUESTED, ACCEPTED, COMPLETED, DECLINED, CANCELLED;

    public boolean canMoveTo(BookingStatus next) {
        return switch (this) {
            case REQUESTED -> next == ACCEPTED || next == DECLINED || next == CANCELLED;
            case ACCEPTED -> next == COMPLETED || next == CANCELLED || next == REQUESTED;
            default -> false;
        };
    }

    public boolean isOpen() {
        return this == REQUESTED || this == ACCEPTED;
    }
}
