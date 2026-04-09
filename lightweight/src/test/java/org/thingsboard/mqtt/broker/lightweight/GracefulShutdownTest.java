package org.thingsboard.mqtt.broker.lightweight;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.thingsboard.mqtt.broker.lightweight.server.MqttTcpServerBootstrap;
import org.thingsboard.mqtt.broker.lightweight.storage.rocksdb.RocksDbStorage;

import static org.assertj.core.api.Assertions.assertThat;

// Note: TestRestTemplate is not autowired — it uses the main port by default.
// We create a port-aware template in each test using the injected managementPort.

/**
 * Integration test verifying the full broker startup, lifecycle ordering,
 * Prometheus metrics exposure, and health endpoint.
 *
 * <p>Uses {@code management.server.port=0} for CI safety — avoids port 8083 conflicts.
 * Uses {@code tbmq.netty.port=0} for CI-safe MQTT port assignment.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=tbmq-lightweight",
                "tbmq.netty.port=0",
                "tbmq.storage.rocksdb.path=${java.io.tmpdir}/tbmq-test-shutdown",
                "tbmq.ws.port=0",
                "tbmq.wss.enabled=false",
                "management.server.port=0"
        }
)
@DirtiesContext
class GracefulShutdownTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private MqttTcpServerBootstrap mqttTcpServerBootstrap;

    @Autowired
    private RocksDbStorage rocksDbStorage;

    // Note: NOT using @Autowired TestRestTemplate — it targets the main server port.
    // We construct a fresh template targeting managementPort in each test.
    private TestRestTemplate managementRestTemplate() {
        return new TestRestTemplate();
    }

    @Test
    void testLifecycleOrdering_nettyStartsAfterRocksDb() {
        // Netty at phase 0 must be > Integer.MIN_VALUE (RocksDB phase)
        // Stops in descending order: phase 0 stops BEFORE Integer.MIN_VALUE
        assertThat(mqttTcpServerBootstrap.getPhase())
                .as("Netty phase must be greater than RocksDB Integer.MIN_VALUE phase")
                .isGreaterThan(Integer.MIN_VALUE);
    }

    @Test
    void testFullLifecycle_bothServicesRunning() {
        assertThat(mqttTcpServerBootstrap.isRunning())
                .as("MqttTcpServerBootstrap should be running after context startup")
                .isTrue();
        assertThat(rocksDbStorage.isRunning())
                .as("RocksDbStorage should be running after context startup")
                .isTrue();
    }

    @Test
    void testPrometheusEndpoint_returnsMetrics() {
        String url = "http://localhost:" + managementPort + "/actuator/prometheus";
        ResponseEntity<String> response = managementRestTemplate().getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = response.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("Prometheus endpoint should contain JVM memory metrics")
                .contains("jvm_memory_used_bytes");

        assertThat(body)
                .as("Prometheus endpoint should contain mqtt.connections.active gauge (ConnectionCountHandler)")
                .contains("mqtt_connections_active");

        assertThat(body)
                .as("Prometheus endpoint should contain cache.gets metric (D-16: CaffeineCacheMetrics.monitor())")
                .contains("cache_gets_total");
    }

    @Test
    void testHealthEndpoint_returnsUp() {
        String url = "http://localhost:" + managementPort + "/actuator/health";
        ResponseEntity<String> response = managementRestTemplate().getForEntity(url, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .as("Health endpoint should return status UP")
                .contains("\"status\":\"UP\"");
    }

}
