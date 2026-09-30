package dev.surge.order.web;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Postgres unreachable (partition, failover, pool exhausted): 503 with Retry-After, not
 * 500. Nothing was committed, so a retry is safe; a checkout retried with the same
 * Idempotency-Key resumes or replays.
 */
@RestControllerAdvice
public class DatabaseUnavailableAdvice {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableAdvice.class);

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class,
            RecoverableDataAccessException.class, CannotCreateTransactionException.class,
            TransactionSystemException.class})
    public ResponseEntity<Map<String, String>> unavailable(Exception e) {
        log.warn("database unavailable: {}", e.toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(Map.of("error", "RETRY_LATER"));
    }
}
