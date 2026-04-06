package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT CONNECT / CONNACK / DISCONNECT — PROTO-01 and TRAN-01.
 */
class MqttConnectIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testConnect_withValidClientId_returnsConnAck() throws Exception {
        MqttClient client = createClient("test-connect-1");
        client.connect(defaultConnectOptions());
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    @Test
    void testConnect_withEmptyClientIdAndCleanSession_assignsClientId() throws Exception {
        // Paho requires a non-null clientId; use a UUID-like string via empty-string override
        // Paho's MqttClient("", ...) sends empty clientId in the CONNECT packet.
        // The broker assigns a UUID server-side. Paho receives CONNACK and reports connected.
        MqttClient client = new org.eclipse.paho.client.mqttv3.MqttClient(
                brokerUrl(), "", new org.eclipse.paho.client.mqttv3.persist.MemoryPersistence());
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setCleanSession(true);
        client.connect(opts);
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
        client.close();
    }

    @Test
    @Disabled("Requires raw socket test for protocol version manipulation — deferred to protocol edge case testing")
    void testConnect_secondConnectOnSameChannel_disconnects() {
        // Client sends a second CONNECT on an already-connected channel.
        // Expected per MQTT 3.1.1 spec section 3.1: broker MUST disconnect.
        // Testing this with Paho is not possible (Paho doesn't allow 2nd CONNECT).
        // Deferred: use raw socket approach in integration test suite expansion.
    }

    @Test
    @Disabled("Requires raw socket test for protocol version manipulation")
    void testConnect_protocolVersionNotSupported_returnsUnacceptable() {
        // Client connects with an unsupported protocol version (e.g., MQTT 3.1.0 magic bytes
        // with version byte set to an unknown value).
        // Expected: CONNACK with returnCode=1 (UNACCEPTABLE_PROTOCOL_VERSION), then disconnect.
    }

    @Test
    void testDisconnect_cleanDisconnect_closesChannel() throws Exception {
        MqttClient client = createClient("test-disconnect-1");
        client.connect(defaultConnectOptions());
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
        assertThat(client.isConnected()).isFalse();
    }

}
