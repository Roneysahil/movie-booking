package com.roneysahil.movie_booking.common.exception;

import java.util.List;

/** Domain exceptions mapped to HTTP status by {@code ApiExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {}

    /** Requested entity does not exist, or the caller may not see it. Maps to 404. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** A business rule was violated (expired hold, non-cancellable booking). Maps to 422. */
    public static class BusinessRuleException extends RuntimeException {
        public BusinessRuleException(String message) {
            super(message);
        }
    }

    /**
     * Seats were taken by someone else between browsing and holding. Maps to 409 and
     * names the offending seats so the client can re-render the seat map.
     */
    public static class SeatUnavailableException extends RuntimeException {
        private final List<Long> unavailableSeatIds;

        public SeatUnavailableException(List<Long> unavailableSeatIds) {
            super("One or more selected seats are no longer available");
            this.unavailableSeatIds = List.copyOf(unavailableSeatIds);
        }

        public List<Long> getUnavailableSeatIds() {
            return unavailableSeatIds;
        }
    }

    /** Discount code failed validation. Maps to 422. */
    public static class DiscountNotApplicableException extends RuntimeException {
        public DiscountNotApplicableException(String message) {
            super(message);
        }
    }
}
