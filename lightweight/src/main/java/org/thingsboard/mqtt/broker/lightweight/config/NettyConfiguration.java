package org.thingsboard.mqtt.broker.lightweight.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the Netty TCP server.
 *
 * <p>All properties can be overridden via environment variables using
 * Spring Boot's relaxed binding with the TBMQ_ prefix convention:
 * <ul>
 *   <li>TBMQ_MQTT_PORT=1883</li>
 *   <li>TBMQ_MAX_CONNECTIONS=10000</li>
 *   <li>TBMQ_NETTY_BOSS_THREADS=1</li>
 *   <li>TBMQ_NETTY_WORKER_THREADS=0 (0 = available processors)</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "tbmq.netty")
@Data
public class NettyConfiguration {

    private int port = 1883;
    private int maxConnections = 10000;
    private int bossThreads = 1;
    private int workerThreads = 0; // 0 means Runtime.getRuntime().availableProcessors()

}
