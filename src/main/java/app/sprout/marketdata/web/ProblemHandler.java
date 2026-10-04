package app.sprout.marketdata.web;

import app.sprout.marketdata.domain.ErrorCode;
import app.sprout.marketdata.domain.MarketDataException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Turns every failure into RFC 9457 problem details with the contract's stable codes. */
@RestControllerAdvice
public class ProblemHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);
    private static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @ExceptionHandler(MarketDataException.class)
    ResponseEntity<Map<String, Object>> marketData(MarketDataException e) {
        var body = problem(e.code().status(), e.code().title(), e.code().name(), e.getMessage());
        var res = ResponseEntity.status(e.code().status()).contentType(PROBLEM);
        if (e.retryAfterSeconds() != null) {
            body.put("retryAfterSeconds", e.retryAfterSeconds());
            res.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        return res.body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException e) {
        return validation("The query parameter '" + e.getParameterName() + "' is required.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> mismatch(MethodArgumentTypeMismatchException e) {
        return validation("The parameter '" + e.getName() + "' has the wrong type.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Map<String, Object>> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).contentType(PROBLEM)
                .body(problem(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", ErrorCode.VALIDATION_FAILED.name(), e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(PROBLEM)
                .body(problem(HttpStatus.NOT_FOUND, "Not found", ErrorCode.VALIDATION_FAILED.name(), "There's nothing at this address."));
    }

    /** A stream's client disconnected mid-write: nothing to answer. */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    void gone(AsyncRequestNotUsableException e) {
        // intentionally empty
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unexpected failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).contentType(PROBLEM)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong", "INTERNAL",
                        "Try again in a moment. If it keeps happening, quote the request id."));
    }

    private ResponseEntity<Map<String, Object>> validation(String detail) {
        ErrorCode c = ErrorCode.VALIDATION_FAILED;
        return ResponseEntity.status(c.status()).contentType(PROBLEM).body(problem(c.status(), c.title(), c.name(), detail));
    }

    private static Map<String, Object> problem(HttpStatus status, String title, String code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "https://sainayakk.github.io/sprout-platform/errors/#" + code.toLowerCase());
        body.put("title", title);
        body.put("status", status.value());
        body.put("code", code);
        body.put("detail", detail);
        String requestId = MDC.get("requestId");
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return body;
    }
}
