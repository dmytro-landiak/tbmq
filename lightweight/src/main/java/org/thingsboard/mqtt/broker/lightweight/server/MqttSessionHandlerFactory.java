/**
 * Copyright © 2016-2026 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.mqtt.broker.lightweight.server;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.security.acl.AuthorizationRuleService;
import org.thingsboard.mqtt.broker.lightweight.security.auth.LightweightAuthService;
import org.thingsboard.mqtt.broker.lightweight.service.dispatch.MsgDispatcherService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsgService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.will.LastWillService;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;

/**
 * Factory that centralizes construction of {@link MqttSessionHandler} instances.
 *
 * <p>Eliminates the 11-parameter duplication that would otherwise be required in each
 * channel initializer (TCP, TLS, WS, WSS). Each call to {@link #create()} produces
 * a new {@link MqttSessionHandler} instance — required because MqttSessionHandler is
 * NOT @Sharable (stateful per channel).
 */
@Component
@RequiredArgsConstructor
public class MqttSessionHandlerFactory {

    private final TbActorSystem actorSystem;
    private final ClientSessionRegistry sessionRegistry;
    private final MqttMessageGenerator messageGenerator;
    private final MqttConfiguration mqttConfig;
    private final SubscriptionRegistry subscriptionRegistry;
    private final RetainedMsgService retainedMsgService;
    private final LastWillService lastWillService;
    private final MsgDispatcherService msgDispatcherService;
    private final LightweightAuthService authService;
    private final AuthorizationRuleService authorizationRuleService;
    private final MeterRegistry meterRegistry;

    /**
     * Creates a new {@link MqttSessionHandler} instance.
     *
     * <p>Must be called once per channel connection — MqttSessionHandler is stateful
     * and NOT thread-safe across multiple channels.
     *
     * @return a new MqttSessionHandler with all dependencies wired
     */
    public MqttSessionHandler create() {
        return new MqttSessionHandler(actorSystem, sessionRegistry, messageGenerator,
                mqttConfig, subscriptionRegistry, retainedMsgService, lastWillService,
                msgDispatcherService, authService, authorizationRuleService, meterRegistry);
    }

}
