package dev.surge.admission.web;

import java.util.Map;

import io.lettuce.core.RedisException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Redis unreachable or failing over: the waiting room answers 503, clients retry. */
@RestControllerAdvice
public class RedisUnavailableAdvice {

    @ExceptionHandler(RedisException.class)
    public ResponseEntity<Map<String, String>> unavailable(RedisException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(Map.of("error", "RETRY_LATER"));
    }
}
