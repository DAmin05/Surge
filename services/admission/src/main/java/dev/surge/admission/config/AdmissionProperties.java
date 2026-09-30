package dev.surge.admission.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param admitRatePerSec users let through per second per event, across all instances;
 *                        sized to what checkout can absorb
 * @param tokenLifetime   admission token lifetime, from the moment of admission
 * @param keysDir         Ed25519 keys written by scripts/keys.sh
 * @param cookieSecure    Secure flag on the session cookie (off for plain-http local)
 */
@ConfigurationProperties("surge.admission")
public record AdmissionProperties(
        int admitRatePerSec,
        Duration admitInterval,
        Duration tokenLifetime,
        Duration sessionLifetime,
        Path keysDir,
        boolean cookieSecure,
        List<String> redisNodes) {}
