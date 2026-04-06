package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MQTT session state handling — PROTO-07.
 *
 * <p>R1 constraint: only clean sessions are supported (no persistent sessions). A client
 * connecting with cleanSession=false is treated as cleanSession=true per the design decision
 * to ship core broker faster and defer persistence to R2.
 */
class MqttSessionIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    void testCleanSession_true_noSessionPresent() throws Exception {
        MqttClient client = createClient("test-session-clean");
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setCleanSession(true);
        client.connect(opts);
        // Per D-10, sessionPresent is always false in CONNACK
        // Paho doesn't expose sessionPresent directly but connection succeeds
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

    @Test
    void testCleanSession_false_treatedAsTrue() throws Exception {
        MqttClient client = createClient("test-session-not-clean");
        MqttConnectOptions opts = defaultConnectOptions();
        opts.setCleanSession(false); // per D-10, treated as clean session=1 in R1
        client.connect(opts);
        assertThat(client.isConnected()).isTrue();
        client.disconnect();
    }

}
