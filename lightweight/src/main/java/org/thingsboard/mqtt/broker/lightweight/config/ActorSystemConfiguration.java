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
package org.thingsboard.mqtt.broker.lightweight.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thingsboard.mqtt.broker.lightweight.actors.DefaultTbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystem;
import org.thingsboard.mqtt.broker.lightweight.actors.TbActorSystemSettings;

import java.util.concurrent.ForkJoinPool;

/**
 * Configures and manages the lifecycle of the actor system.
 *
 * <p>Implements SmartLifecycle at phase -1 so the actor system starts after RocksDB
 * (which runs at Integer.MIN_VALUE) and before Netty (which runs at phase 0).
 * This ensures the actor system is ready to receive messages when the first MQTT
 * connection arrives.
 */
@Slf4j
@Configuration
public class ActorSystemConfiguration implements SmartLifecycle {

    private static final String CLIENT_DISPATCHER_ID = "client-dispatcher";

    @Value("${tbmq.actors.throughput:10}")
    private int actorThroughput;

    @Value("${tbmq.actors.scheduler-pool-size:1}")
    private int schedulerPoolSize;

    @Value("${tbmq.actors.max-init-attempts:10}")
    private int maxInitAttempts;

    private volatile DefaultTbActorSystem actorSystem;
    private volatile boolean running = false;

    @Bean
    public TbActorSystem actorSystem() {
        TbActorSystemSettings settings = TbActorSystemSettings.builder()
                .actorThroughput(actorThroughput)
                .schedulerPoolSize(schedulerPoolSize)
                .maxActorInitAttempts(maxInitAttempts)
                .build();
        actorSystem = new DefaultTbActorSystem(settings);
        return actorSystem;
    }

    @Override
    public void start() {
        log.info("Starting actor system...");
        int parallelism = Runtime.getRuntime().availableProcessors();
        actorSystem.createDispatcher(CLIENT_DISPATCHER_ID, new ForkJoinPool(parallelism));
        running = true;
        log.info("Actor system started with {} dispatcher threads (ForkJoinPool parallelism={})", parallelism, parallelism);
    }

    @Override
    public void stop() {
        log.info("Stopping actor system...");
        running = false;
        if (actorSystem != null) {
            actorSystem.destroy();
        }
        log.info("Actor system stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return -1;
    }

}
