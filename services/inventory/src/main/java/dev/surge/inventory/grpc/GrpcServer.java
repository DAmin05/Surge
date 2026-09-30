package dev.surge.inventory.grpc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dev.surge.inventory.config.InventoryProperties;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Serves {@link InventoryGrpcService}; calls run on virtual threads. */
@Component
public class GrpcServer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServer.class);

    private final Server server;
    private volatile boolean running;

    public GrpcServer(InventoryProperties props, InventoryGrpcService service) {
        this.server = NettyServerBuilder.forPort(props.grpcPort())
                .addService(service)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    @Override
    public void start() {
        try {
            server.start();
            running = true;
            log.info("gRPC listening on {}", server.getPort());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void stop() {
        server.shutdown();
        try {
            server.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
