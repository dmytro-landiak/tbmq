package org.thingsboard.mqtt.broker.lightweight.mqtt;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for MQTT session state handling — PROTO-07.
 *
 * <p>All tests are disabled until Plan 03 implements the CONNECT handler with session state.
 *
 * <p>R1 constraint: only clean sessions are supported (no persistent sessions). A client
 * connecting with cleanSession=false is treated as cleanSession=true per the design decision
 * to ship core broker faster and defer persistence to R2.
 */
class MqttSessionIntegrationTest extends AbstractMqttIntegrationTest {

    @Test
    @Disabled("Enabled in Plan 03")
    void testCleanSession_true_noSessionPresent() {
        // Client connects with cleanSession=true.
        // Expected: CONNACK with sessionPresent=0 (no prior session).
    }

    @Test
    @Disabled("Enabled in Plan 03")
    void testCleanSession_false_treatedAsTrue() {
        // Client connects with cleanSession=false (persistent session requested).
        // Expected per R1 scope: broker treats as cleanSession=true.
        // CONNACK sessionPresent=0 (no prior session state retained).
    }

}
