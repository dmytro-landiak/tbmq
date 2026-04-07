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
package org.thingsboard.mqtt.broker.lightweight.actors.client;

import lombok.RequiredArgsConstructor;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActor;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorCreator;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorId;
import org.thingsboard.mqtt.broker.lightweight.actors.TbTypeActorId;
import org.thingsboard.mqtt.broker.lightweight.config.MqttConfiguration;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.MqttMessageGenerator;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.retain.RetainedMsgService;
import org.thingsboard.mqtt.broker.lightweight.service.mqtt.will.LastWillService;
import org.thingsboard.mqtt.broker.lightweight.service.subscription.SubscriptionRegistry;
import org.thingsboard.mqtt.broker.lightweight.session.ClientSessionRegistry;

/**
 * Factory for creating {@link ClientActor} instances.
 *
 * <p>One creator is instantiated per MQTT client connection. The creator
 * produces a stable actor ID from the clientId and constructs a new
 * {@link ClientActor} with all required dependencies.
 */
@RequiredArgsConstructor
public class ClientActorCreator implements TbActorCreator {

    private final String clientId;
    private final ClientSessionRegistry sessionRegistry;
    private final MqttMessageGenerator messageGenerator;
    private final MqttConfiguration mqttConfig;
    private final SubscriptionRegistry subscriptionRegistry;
    private final RetainedMsgService retainedMsgService;
    private final LastWillService lastWillService;

    @Override
    public TbActorId createActorId() {
        return new TbTypeActorId("client", clientId);
    }

    @Override
    public TbActor createActor() {
        return new ClientActor(sessionRegistry, messageGenerator, mqttConfig, subscriptionRegistry,
                retainedMsgService, lastWillService);
    }

}
