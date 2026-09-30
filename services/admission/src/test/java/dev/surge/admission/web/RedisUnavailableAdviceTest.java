package dev.surge.admission.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisException;
import org.junit.jupiter.api.Test;

class RedisUnavailableAdviceTest {

    @Test
    void aRedisFailoverIsRetryLater() {
        var res = new RedisUnavailableAdvice().unavailable(new RedisException("Connection reset"));
        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(res.getBody()).containsEntry("error", "RETRY_LATER");
    }
}
