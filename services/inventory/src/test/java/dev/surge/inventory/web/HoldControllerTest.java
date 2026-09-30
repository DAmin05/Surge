package dev.surge.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisException;
import org.junit.jupiter.api.Test;

class HoldControllerTest {

    private final HoldController controller = new HoldController(null);

    @Test
    void aRedisFailoverIsRetryLaterNotAServerError() {
        for (RedisException e : new RedisException[] {
                new RedisException("Connection reset"), new RedisCommandTimeoutException("timed out")}) {
            var res = controller.redisUnavailable(e);
            assertThat(res.getStatusCode().value()).isEqualTo(503);
            assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("1");
            assertThat(res.getBody()).containsEntry("error", "RETRY_LATER");
        }
    }
}
