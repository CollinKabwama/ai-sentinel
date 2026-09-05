package dev.aisentinel.benchmark.deployment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

final class RedisContainerSupport implements AutoCloseable {

    private static final String IMAGE = "redis:7-alpine";

    private String containerId;
    private int hostPort;

    void start() throws IOException, InterruptedException {
        if (containerId == null) {
            hostPort = reservePort();
            containerId = run("docker", "run", "-d", "-p", "127.0.0.1:" + hostPort + ":6379", IMAGE);
            return;
        }
        run("docker", "start", containerId);
    }

    void stopContainer() throws IOException, InterruptedException {
        if (containerId != null) {
            run("docker", "stop", containerId);
        }
    }

    void pause() throws IOException, InterruptedException {
        if (containerId != null) {
            run("docker", "pause", containerId);
        }
    }

    void unpause() throws IOException, InterruptedException {
        if (containerId != null) {
            run("docker", "unpause", containerId);
        }
    }

    String host() {
        return "127.0.0.1";
    }

    int port() {
        return hostPort;
    }

    String redisVersion() {
        return IMAGE;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (containerId != null) {
            try {
                run("docker", "rm", "-f", containerId);
            } finally {
                containerId = null;
            }
        }
    }

    private static int reservePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        String output;
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().reduce("", (a, b) -> a + (a.isEmpty() ? "" : "\n") + b).trim();
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Command failed (" + String.join(" ", command) + "): " + output);
        }
        return output;
    }
}
