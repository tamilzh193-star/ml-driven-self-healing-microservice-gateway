package com.selfhealing.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Executes genuine container restarts using Docker Engine API (Unix domain socket or Docker CLI).
 */
@Component
public class DockerRestartExecutor implements RestartExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerRestartExecutor.class);

    @Value("${docker.container.mode:false}")
    private boolean dockerContainerMode;

    @Value("${docker.socket.path:/var/run/docker.sock}")
    private String dockerSocketPath;

    @Override
    public String getMode() {
        return "DOCKER";
    }

    @Override
    public boolean isAvailable() {
        if (dockerContainerMode) {
            return true;
        }
        if ("true".equalsIgnoreCase(System.getenv("DOCKER_CONTAINER_MODE"))) {
            return true;
        }
        File socketFile = new File(dockerSocketPath);
        if (socketFile.exists()) {
            return true;
        }
        try {
            Process process = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public RestartResult restart(String serviceName, String baseUrl) {
        String containerName = mapServiceToContainer(serviceName);
        long startTime = System.currentTimeMillis();
        log.info("[DOCKER RESTART] Requesting genuine restart of container '{}'", containerName);

        // 1. Try native Unix Domain Socket API
        if (restartViaDockerSocket(containerName)) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = String.format("Container '%s' successfully restarted via Docker Unix Socket in %dms", containerName, duration);
            log.info("[DOCKER RESTART] {}", msg);
            return new RestartResult(true, "DOCKER", msg, duration);
        }

        // 2. Try Docker CLI subprocess
        if (restartViaDockerCli(containerName)) {
            long duration = System.currentTimeMillis() - startTime;
            String msg = String.format("Container '%s' successfully restarted via Docker CLI in %dms", containerName, duration);
            log.info("[DOCKER RESTART] {}", msg);
            return new RestartResult(true, "DOCKER", msg, duration);
        }

        long duration = System.currentTimeMillis() - startTime;
        String err = String.format("Failed to restart Docker container '%s' (socket and CLI unavailable)", containerName);
        log.warn("[DOCKER RESTART] {}", err);
        return new RestartResult(false, "DOCKER", err, duration);
    }

    private boolean restartViaDockerSocket(String containerName) {
        File socketFile = new File(dockerSocketPath);
        if (!socketFile.exists()) {
            return false;
        }

        try {
            UnixDomainSocketAddress address = UnixDomainSocketAddress.of(dockerSocketPath);
            try (SocketChannel channel = SocketChannel.open(address)) {
                String httpRequest = "POST /v1.41/containers/" + containerName + "/restart?t=5 HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n";

                channel.write(ByteBuffer.wrap(httpRequest.getBytes(StandardCharsets.US_ASCII)));

                ByteBuffer responseBuffer = ByteBuffer.allocate(1024);
                int bytesRead = channel.read(responseBuffer);
                if (bytesRead > 0) {
                    responseBuffer.flip();
                    String response = StandardCharsets.US_ASCII.decode(responseBuffer).toString();
                    return response.contains("204 No Content") || response.contains("200 OK");
                }
            }
        } catch (Exception e) {
            log.debug("[DOCKER SOCKET] Exception restarting {}: {}", containerName, e.getMessage());
        }
        return false;
    }

    private boolean restartViaDockerCli(String containerName) {
        try {
            Process process = new ProcessBuilder("docker", "restart", containerName)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("[DOCKER CLI] Subprocess execution failed: {}", e.getMessage());
            return false;
        }
    }

    private String mapServiceToContainer(String serviceName) {
        switch (serviceName) {
            case "order-service": return "self-healing-order-service";
            case "payment-service": return "self-healing-payment-service";
            case "inventory-service": return "self-healing-inventory-service";
            case "ml-service": return "self-healing-ml-service";
            default: return serviceName;
        }
    }
}
