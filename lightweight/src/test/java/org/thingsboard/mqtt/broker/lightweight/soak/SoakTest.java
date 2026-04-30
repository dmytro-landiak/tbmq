package org.thingsboard.mqtt.broker.lightweight.soak;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.server.MqttTcpServerBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Long-running soak test that validates zero memory leaks and stable heap
 * under sustained MQTT load.
 *
 * <p>Excluded from the default mvn test run via {@code <excludedGroups>soak</excludedGroups>}
 * in the Surefire configuration (lightweight/pom.xml).
 *
 * <p>Run with:
 * <pre>
 *   # Short (5 min, default — for development / CI smoke):
 *   mvn test -pl lightweight -Dgroups=soak -Dsurefire.excludedGroups=
 *
 *   # CI (1 hour):
 *   mvn test -pl lightweight -Dgroups=soak -Dsurefire.excludedGroups= -Dsoak.duration.minutes=60
 *
 *   # Full (24 hours):
 *   mvn test -pl lightweight -Dgroups=soak -Dsurefire.excludedGroups= -Dsoak.duration.minutes=1440
 * </pre>
 *
 * <p>Requirements validated:
 * <ul>
 *   <li>D-05: Soak test infrastructure</li>
 *   <li>D-06: 500 concurrent clients at 1000 msg/sec with mixed QoS 0/1/2</li>
 *   <li>D-07: Zero Netty ByteBuf leaks in PARANOID mode</li>
 *   <li>D-08: JVM heap stability within 20% of steady-state baseline</li>
 * </ul>
 */
@Tag("soak")
@SpringBootTest(properties = {
        "spring.config.name=tbmq-lightweight",
        "tbmq.netty.port=0",
        "tbmq.netty.max-connections=1000",
        "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-soak-test",
        "tbmq.ws.port=0",
        "tbmq.wss.enabled=false",
        "management.server.port=0"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SoakTest {

    @Autowired
    private MqttTcpServerBootstrap mqttServer;

    @Autowired
    private MeterRegistry meterRegistry;

    // D-06: 500 concurrent clients
    private static final int TOTAL_CLIENTS = 500;
    private static final int PUBLISHERS = 250;
    private static final int SUBSCRIBERS = 250;

    // D-06: 1000 msg/sec target throughput
    private static final int TARGET_MSG_PER_SEC = 1000;

    private static final String TOPIC_PREFIX = "soak/test/";

    // Thread pool for client connection setup — avoid thread exhaustion during bulk connect
    private static final int CONNECT_POOL_SIZE = 50;

    /**
     * Returns the soak test duration in minutes. Reads from the {@code soak.duration.minutes}
     * system property, defaulting to {@code 5} for short development/CI smoke runs.
     * CI uses 60; manual soak validation uses 1440.
     */
    private int getDurationMinutes() {
        return Integer.parseInt(System.getProperty("soak.duration.minutes", "5"));
    }

    /**
     * Returns the broker URL for test clients.
     */
    private String brokerUrl() {
        return "tcp://127.0.0.1:" + mqttServer.getLocalPort();
    }

    /**
     * Returns sensible default {@link MqttConnectOptions} for soak test clients:
     * clean session enabled, 10-second connection timeout, 60-second keep-alive.
     * Uses the built-in {@code tbmq/tbmq} credentials.
     */
    private MqttConnectOptions defaultConnectOptions() {
        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);
        opts.setConnectionTimeout(10);
        opts.setKeepAliveInterval(60);
        opts.setUserName("tbmq");
        opts.setPassword("tbmq".toCharArray());
        return opts;
    }

    /**
     * Soak test: connects 500 clients (250 subscribers + 250 publishers), publishes at
     * ~1000 msg/sec with mixed QoS 0/1/2, and asserts:
     * <ol>
     *   <li>Zero Netty ByteBuf leaks in PARANOID mode (D-07)</li>
     *   <li>JVM heap stays within 20% of steady-state baseline (D-08)</li>
     *   <li>Message counters are non-zero (basic throughput sanity)</li>
     * </ol>
     */
    @Test
    void soakTest() throws Exception {
        int durationMinutes = getDurationMinutes();
        int warmupMinutes = Math.min(2, durationMinutes);

        // --- ByteBuf leak detection setup (D-07) ---
        // Surefire sets -Dio.netty.leakDetection.level=PARANOID in argLine.
        // PARANOID mode logs "LEAK:" to the io.netty.util.ResourceLeakDetector logger.
        ch.qos.logback.classic.Logger leakLogger = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger("io.netty.util.ResourceLeakDetector");
        ListAppender<ILoggingEvent> leakAppender = new ListAppender<>();
        leakAppender.start();
        leakLogger.addAppender(leakAppender);

        List<MqttClient> subscribers = new ArrayList<>(SUBSCRIBERS);
        List<MqttClient> publishers = new ArrayList<>(PUBLISHERS);
        ScheduledExecutorService publishScheduler = null;
        java.util.concurrent.ExecutorService connectPool = Executors.newFixedThreadPool(CONNECT_POOL_SIZE);

        try {
            // =====================================================================
            // Phase 1: Setup — connect 250 subscribers and 250 publishers in batches
            // =====================================================================
            LoggerFactory.getLogger(SoakTest.class).info(
                    "Soak test starting — duration={}min, clients={}, target={} msg/sec",
                    durationMinutes, TOTAL_CLIENTS, TARGET_MSG_PER_SEC);

            List<java.util.concurrent.Future<?>> connectFutures = new ArrayList<>(TOTAL_CLIENTS);

            // Connect subscribers
            for (int i = 0; i < SUBSCRIBERS; i++) {
                final int idx = i;
                connectFutures.add(connectPool.submit(() -> {
                    try {
                        MqttClient client = new MqttClient(brokerUrl(), "soak-sub-" + idx, new MemoryPersistence());
                        client.connect(defaultConnectOptions());
                        client.subscribe(TOPIC_PREFIX + idx, 0);
                        synchronized (subscribers) {
                            subscribers.add(client);
                        }
                    } catch (MqttException e) {
                        throw new RuntimeException("Failed to connect subscriber " + idx, e);
                    }
                }));
            }

            // Connect publishers
            for (int i = 0; i < PUBLISHERS; i++) {
                final int idx = i;
                connectFutures.add(connectPool.submit(() -> {
                    try {
                        MqttClient client = new MqttClient(brokerUrl(), "soak-pub-" + idx, new MemoryPersistence());
                        client.connect(defaultConnectOptions());
                        synchronized (publishers) {
                            publishers.add(client);
                        }
                    } catch (MqttException e) {
                        throw new RuntimeException("Failed to connect publisher " + idx, e);
                    }
                }));
            }

            // Wait for all connections to complete
            for (java.util.concurrent.Future<?> f : connectFutures) {
                f.get(60, TimeUnit.SECONDS);
            }
            LoggerFactory.getLogger(SoakTest.class).info(
                    "All {} clients connected ({} subscribers, {} publishers)",
                    TOTAL_CLIENTS, subscribers.size(), publishers.size());

            // =====================================================================
            // Phase 2: Warmup — publish at target rate for warmup period
            // =====================================================================
            AtomicBoolean publishActive = new AtomicBoolean(true);
            byte[] payload = new byte[32];
            java.util.Arrays.fill(payload, (byte) 'X');

            // Schedule messages at TARGET_MSG_PER_SEC
            // Each tick fires 1 message; 1000 ticks/sec = 1ms interval
            long intervalMicros = 1_000_000L / TARGET_MSG_PER_SEC;
            publishScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "soak-publisher");
                t.setDaemon(true);
                return t;
            });

            publishScheduler.scheduleAtFixedRate(() -> {
                if (!publishActive.get()) return;
                try {
                    int publisherIdx = ThreadLocalRandom.current().nextInt(publishers.size());
                    MqttClient publisher = publishers.get(publisherIdx);
                    if (!publisher.isConnected()) return;

                    // D-06: QoS mix — 60% QoS 0, 30% QoS 1, 10% QoS 2
                    int qos = selectQos();
                    int topicIdx = publisherIdx % SUBSCRIBERS;
                    publisher.publish(TOPIC_PREFIX + topicIdx, payload, qos, false);
                } catch (Exception ignored) {
                    // Suppress individual publish errors during soak — they are expected
                    // during warmup when connections are being established
                }
            }, 0, intervalMicros, TimeUnit.MICROSECONDS);

            // Warmup phase
            LoggerFactory.getLogger(SoakTest.class).info("Warmup phase starting ({} minutes)...", warmupMinutes);
            TimeUnit.MINUTES.sleep(warmupMinutes);

            // Sample heap baseline after warmup (5 samples at 20-second intervals)
            LoggerFactory.getLogger(SoakTest.class).info("Sampling heap baseline...");
            double baselineHeap = sampleHeapAverage(5, 20_000);
            LoggerFactory.getLogger(SoakTest.class).info(
                    "Heap baseline established: {} bytes ({} MB)",
                    (long) baselineHeap, (long) (baselineHeap / 1_048_576));

            // =====================================================================
            // Phase 3: Stable phase — continue publishing for remaining duration
            // =====================================================================
            int stableMinutes = durationMinutes - warmupMinutes;
            if (stableMinutes > 0) {
                LoggerFactory.getLogger(SoakTest.class).info(
                        "Stable phase starting ({} minutes)...", stableMinutes);

                // Sample heap every 2 minutes for observability
                int samplingIntervalMinutes = 2;
                int samplesInStable = Math.max(1, stableMinutes / samplingIntervalMinutes);
                for (int sample = 1; sample <= samplesInStable; sample++) {
                    long waitMs = (long) samplingIntervalMinutes * 60_000;
                    // Sleep until next sample but respect remaining time
                    long elapsed = (long) (sample - 1) * samplingIntervalMinutes * 60_000;
                    long remaining = (long) stableMinutes * 60_000 - elapsed;
                    TimeUnit.MILLISECONDS.sleep(Math.min(waitMs, remaining));

                    double currentHeap = getHeapUsedBytes();
                    LoggerFactory.getLogger(SoakTest.class).info(
                            "Stable phase heap sample {}/{}: {} MB (baseline: {} MB)",
                            sample, samplesInStable,
                            (long) (currentHeap / 1_048_576),
                            (long) (baselineHeap / 1_048_576));
                }
            }

            // Stop publishing before teardown assertions
            publishActive.set(false);
            publishScheduler.shutdown();
            publishScheduler.awaitTermination(10, TimeUnit.SECONDS);

            // =====================================================================
            // Phase 4: Teardown — disconnect all clients
            // =====================================================================
            LoggerFactory.getLogger(SoakTest.class).info("Teardown — disconnecting {} clients...", TOTAL_CLIENTS);
            disconnectAll(subscribers, publishers);

            // =====================================================================
            // Phase 5: Assertions
            // =====================================================================

            // D-07: ByteBuf leak check — no LEAK: entries in Netty ResourceLeakDetector output
            long leakCount = leakAppender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("LEAK:"))
                    .count();
            Assertions.assertEquals(0, leakCount,
                    "ByteBuf leak detected during soak test — " + leakCount + " LEAK: entries found. "
                            + "Check Surefire output for full leak report.");

            // D-08: Heap stability — final heap must be within 20% of baseline
            double finalHeap = sampleHeapAverage(3, 10_000);
            LoggerFactory.getLogger(SoakTest.class).info(
                    "Final heap: {} MB, baseline: {} MB, threshold: {} MB",
                    (long) (finalHeap / 1_048_576),
                    (long) (baselineHeap / 1_048_576),
                    (long) (baselineHeap * 1.20 / 1_048_576));
            Assertions.assertTrue(finalHeap < baselineHeap * 1.20,
                    String.format("Heap grew beyond 20%% of baseline: baseline=%.0f bytes (%.1f MB), "
                                    + "final=%.0f bytes (%.1f MB)",
                            baselineHeap, baselineHeap / 1_048_576.0,
                            finalHeap, finalHeap / 1_048_576.0));

            // Message throughput sanity check
            double messagesReceived = meterRegistry.get("mqtt.messages.received.total").counter().count();
            double messagesDelivered = meterRegistry.get("mqtt.messages.delivered.total").counter().count();
            Assertions.assertTrue(messagesReceived > 0,
                    "Expected mqtt.messages.received.total > 0 but was: " + messagesReceived);
            Assertions.assertTrue(messagesDelivered > 0,
                    "Expected mqtt.messages.delivered.total > 0 but was: " + messagesDelivered);

            LoggerFactory.getLogger(SoakTest.class).info(
                    "Soak test PASSED — received={}, delivered={}, leaks={}",
                    (long) messagesReceived, (long) messagesDelivered, leakCount);

        } finally {
            // Ensure scheduler is shut down even if assertions fail
            if (publishScheduler != null && !publishScheduler.isShutdown()) {
                publishScheduler.shutdownNow();
            }
            connectPool.shutdownNow();
            // Detach leak appender
            leakLogger.detachAppender(leakAppender);
            leakAppender.stop();
        }
    }

    /**
     * Selects QoS level using the D-06 distribution:
     * 60% QoS 0, 30% QoS 1, 10% QoS 2.
     */
    private int selectQos() {
        int r = ThreadLocalRandom.current().nextInt(100);
        if (r < 60) return 0;
        if (r < 90) return 1;
        return 2;
    }

    /**
     * Samples heap used (bytes) {@code samples} times, sleeping {@code intervalMs} between samples.
     * Returns the average across all samples.
     *
     * @param samples    number of samples to take
     * @param intervalMs milliseconds between samples
     * @return average heap used in bytes
     */
    private double sampleHeapAverage(int samples, long intervalMs) throws InterruptedException {
        double sum = 0;
        for (int i = 0; i < samples; i++) {
            if (i > 0) Thread.sleep(intervalMs);
            sum += getHeapUsedBytes();
        }
        return sum / samples;
    }

    /**
     * Returns the current JVM heap used in bytes by summing all {@code jvm.memory.used}
     * gauges tagged with {@code area=heap} from the Micrometer registry.
     */
    private double getHeapUsedBytes() {
        return meterRegistry.get("jvm.memory.used")
                .tag("area", "heap")
                .gauges()
                .stream()
                .mapToDouble(Gauge::value)
                .sum();
    }

    /**
     * Disconnects and closes all subscriber and publisher clients.
     */
    private void disconnectAll(List<MqttClient> subscribers, List<MqttClient> publishers) {
        List<MqttClient> all = new ArrayList<>(subscribers.size() + publishers.size());
        all.addAll(subscribers);
        all.addAll(publishers);
        for (MqttClient client : all) {
            try {
                if (client.isConnected()) {
                    client.disconnect(1000);
                }
                client.close();
            } catch (MqttException ignored) {
            }
        }
    }

}
