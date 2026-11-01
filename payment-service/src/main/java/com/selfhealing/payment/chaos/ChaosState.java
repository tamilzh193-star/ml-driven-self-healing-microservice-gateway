package com.selfhealing.payment.chaos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ChaosState {

    private static final Logger log = LoggerFactory.getLogger(ChaosState.class);

    private final AtomicInteger delayMs = new AtomicInteger(0);
    private final AtomicBoolean errorActive = new AtomicBoolean(false);
    private final AtomicBoolean cpuActive = new AtomicBoolean(false);
    private final List<byte[]> memoryChunks = new CopyOnWriteArrayList<>();
    // Non-resettable heap leak: retained across reset(), cleared only by true process restart
    private static final List<byte[]> permanentLeak = new CopyOnWriteArrayList<>();
    private volatile Thread cpuStressThread;

    public void setSlow(int delay) {
        this.delayMs.set(Math.max(0, delay));
        log.warn("Chaos injected: slow latency set to {} ms", this.delayMs.get());
    }

    public void setError(boolean active) {
        this.errorActive.set(active);
        log.warn("Chaos injected: error simulation set to {}", active);
    }

    public synchronized void triggerCpuStress(int durationSeconds) {
        if (cpuActive.get()) {
            return;
        }
        cpuActive.set(true);
        int duration = Math.min(Math.max(5, durationSeconds), 120);

        cpuStressThread = new Thread(() -> {
            log.warn("Chaos injected: CPU stress started for {} seconds", duration);
            long endTime = System.currentTimeMillis() + (duration * 1000L);
            while (cpuActive.get() && System.currentTimeMillis() < endTime) {
                double x = 0.0001;
                for (int i = 0; i < 50000; i++) {
                    x += Math.sin(x) * Math.cos(x);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            cpuActive.set(false);
            log.info("Chaos: CPU stress ended");
        }, "chaos-cpu-stress");

        cpuStressThread.setDaemon(true);
        cpuStressThread.start();
    }

    public synchronized void allocateMemory(int megabytes) {
        int mb = Math.min(Math.max(10, megabytes), 200);
        try {
            for (int i = 0; i < mb; i++) {
                byte[] chunk = new byte[1024 * 1024];
                Arrays.fill(chunk, (byte) 1);
                memoryChunks.add(chunk);
            }
            log.warn("Chaos injected: allocated {} MB of heap memory. Total chunks: {}", mb, memoryChunks.size());
        } catch (OutOfMemoryError e) {
            log.error("Chaos memory allocation stopped to protect JVM: {}", e.getMessage());
        }
    }

    public synchronized void leakMemory(int megabytes) {
        int mb = Math.min(Math.max(10, megabytes), 200);
        try {
            for (int i = 0; i < mb; i++) {
                byte[] chunk = new byte[1024 * 1024];
                Arrays.fill(chunk, (byte) 2);
                permanentLeak.add(chunk);
            }
            log.warn("Chaos injected: PERMANENT memory leak allocated {} MB (total permanent chunks: {}). Reset will NOT clear this!",
                    mb, permanentLeak.size());
        } catch (OutOfMemoryError e) {
            log.error("Chaos leak stopped to protect JVM: {}", e.getMessage());
        }
    }

    public synchronized void reset() {
        delayMs.set(0);
        errorActive.set(false);
        cpuActive.set(false);

        if (cpuStressThread != null && cpuStressThread.isAlive()) {
            cpuStressThread.interrupt();
        }

        memoryChunks.clear();
        System.gc();
        log.info("Chaos: resettable faults have been RESET. Permanent leak retained: {} MB", permanentLeak.size());
    }

    public int getDelayMs() {
        return delayMs.get();
    }

    public boolean isErrorActive() {
        return errorActive.get();
    }

    public boolean isCpuActive() {
        return cpuActive.get();
    }

    public int getAllocatedMb() {
        return memoryChunks.size() + permanentLeak.size();
    }

    public int getPermanentLeakMb() {
        return permanentLeak.size();
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("slowActive", delayMs.get() > 0);
        status.put("delayMs", delayMs.get());
        status.put("errorActive", errorActive.get());
        status.put("cpuStressActive", cpuActive.get());
        status.put("allocatedMemoryMb", memoryChunks.size() + permanentLeak.size());
        status.put("permanentLeakMb", permanentLeak.size());
        status.put("healthy", delayMs.get() == 0 && !errorActive.get() && !cpuActive.get() && memoryChunks.isEmpty() && permanentLeak.isEmpty());
        return status;
    }
}
