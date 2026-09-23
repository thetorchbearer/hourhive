package com.hourhive.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BookingStatusTest {

    @Test
    void requestedCanMoveToAcceptedDeclinedOrCancelled() {
        assertTrue(BookingStatus.REQUESTED.canMoveTo(BookingStatus.ACCEPTED));
        assertTrue(BookingStatus.REQUESTED.canMoveTo(BookingStatus.DECLINED));
        assertTrue(BookingStatus.REQUESTED.canMoveTo(BookingStatus.CANCELLED));
        assertFalse(BookingStatus.REQUESTED.canMoveTo(BookingStatus.COMPLETED));
        assertFalse(BookingStatus.REQUESTED.canMoveTo(BookingStatus.REQUESTED));
    }

    @Test
    void acceptedCanMoveToCompletedCancelledOrBackToRequested() {
        assertTrue(BookingStatus.ACCEPTED.canMoveTo(BookingStatus.COMPLETED));
        assertTrue(BookingStatus.ACCEPTED.canMoveTo(BookingStatus.CANCELLED));
        assertTrue(BookingStatus.ACCEPTED.canMoveTo(BookingStatus.REQUESTED));
        assertFalse(BookingStatus.ACCEPTED.canMoveTo(BookingStatus.DECLINED));
    }

    @Test
    void terminalStatesAreTerminal() {
        for (BookingStatus terminal : new BookingStatus[] {
                BookingStatus.COMPLETED, BookingStatus.DECLINED, BookingStatus.CANCELLED}) {
            for (BookingStatus next : BookingStatus.values()) {
                assertFalse(terminal.canMoveTo(next), terminal + " should never move to " + next);
            }
        }
    }

    @Test
    void isOpenMatchesRequestedAndAccepted() {
        assertTrue(BookingStatus.REQUESTED.isOpen());
        assertTrue(BookingStatus.ACCEPTED.isOpen());
        assertFalse(BookingStatus.COMPLETED.isOpen());
        assertFalse(BookingStatus.DECLINED.isOpen());
        assertFalse(BookingStatus.CANCELLED.isOpen());
    }
}
