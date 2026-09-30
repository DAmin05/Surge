package dev.surge.inventory.redis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Runs the scripts in {@code resources/lua} by SHA, falling back to EVAL when a node
 * doesn't have the script cached (a fresh replica after failover, a restarted node).
 */
@Component
public class LuaScripts {

    public enum Script {
        HOLD("hold"), PIN("pin"), RELEASE("release"), EXPIRED("expired"), SNAPSHOT("snapshot");

        final String file;

        Script(String file) {
            this.file = file;
        }
    }

    private record Loaded(String source, String sha) {}

    private final RedisClusterCommands<String, String> redis;
    private final java.util.Map<Script, Loaded> scripts = new java.util.EnumMap<>(Script.class);

    public LuaScripts(RedisClusterCommands<String, String> redis) {
        this.redis = redis;
        for (Script s : Script.values()) {
            String source = read("lua/" + s.file + ".lua");
            scripts.put(s, new Loaded(source, redis.digest(source)));
        }
    }

    public List<Object> run(Script script, List<String> keys, List<String> args) {
        Loaded s = scripts.get(script);
        String[] k = keys.toArray(String[]::new);
        String[] a = args.toArray(String[]::new);
        try {
            return redis.evalsha(s.sha(), ScriptOutputType.MULTI, k, a);
        } catch (RedisNoScriptException e) {
            return redis.eval(s.source(), ScriptOutputType.MULTI, k, a);
        }
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
