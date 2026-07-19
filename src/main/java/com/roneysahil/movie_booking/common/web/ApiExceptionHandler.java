package com.roneysahil.movie_booking.common.web;

import com.roneysahil.movie_booking.common.exception.ApiExceptions.BusinessRuleException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.DiscountNotApplicableException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.NotFoundException;
import com.roneysahil.movie_booking.common.exception.ApiExceptions.SeatUnavailableException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Translates exceptions into RFC 7807 responses. No stack traces reach the client. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail onNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), "not-found");
    }

    @ExceptionHandler(SeatUnavailableException.class)
    ProblemDetail onSeatUnavailable(SeatUnavailableException ex) {
        ProblemDetail pd = problem(
                HttpStatus.CONFLICT, "Seats Unavailable", ex.getMessage(), "seats-unavailable");
        pd.setProperty("unavailableSeatIds", ex.getUnavailableSeatIds());
        return pd;
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail onBusinessRule(BusinessRuleException ex) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY, "Rule Violation", ex.getMessage(), "rule-violation");
    }

    @ExceptionHandler(DiscountNotApplicableException.class)
    ProblemDetail onDiscount(DiscountNotApplicableException ex) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Discount Not Applicable",
                ex.getMessage(),
                "discount-not-applicable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = problem(
                HttpStatus.BAD_REQUEST, "Validation Failed", "Request body is invalid", "validation");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail onAccessDenied(AccessDeniedException ex) {
        return problem(
                HttpStatus.FORBIDDEN, "Forbidden", "You may not perform this action", "forbidden");
    }

    /** Catch-all. Logs the cause; the client gets nothing internal. */
    @ExceptionHandler(Exception.class)
    ProblemDetail onUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "Something went wrong. Please try again.",
                "internal");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String slug) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://movie-booking.test/problems/" + slug));
        return pd;
    }
}
