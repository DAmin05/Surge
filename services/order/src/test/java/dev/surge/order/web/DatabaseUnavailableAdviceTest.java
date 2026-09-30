package dev.surge.order.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

class DatabaseUnavailableAdviceTest {

    @Test
    void anUnreachableDatabaseIsRetryLater() {
        var advice = new DatabaseUnavailableAdvice();
        for (Exception e : new Exception[] {
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection"),
                new CannotCreateTransactionException("Could not open JDBC Connection")}) {
            var res = advice.unavailable(e);
            assertThat(res.getStatusCode().value()).isEqualTo(503);
            assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(res.getBody()).containsEntry("error", "RETRY_LATER");
        }
    }
}
