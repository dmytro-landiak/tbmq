package org.thingsboard.mqtt.broker.lightweight;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Slf4j
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableAsync
@EnableScheduling
@ComponentScan({"org.thingsboard.mqtt.broker.lightweight"})
public class TbmqLightweightApplication {

    private static final String SPRING_CONFIG_NAME_KEY = "--spring.config.name";
    private static final String DEFAULT_SPRING_CONFIG_PARAM = SPRING_CONFIG_NAME_KEY + "=" + "tbmq-lightweight";

    private static long startTs;

    public static void main(String[] args) {
        try {
            startTs = System.currentTimeMillis();
            SpringApplication.run(TbmqLightweightApplication.class, updateArguments(args));
        } catch (Exception e) {
            log.error("Failed to start TBMQ Lightweight application.", e);
            System.exit(1);
        }
    }

    private static String[] updateArguments(String[] args) {
        if (Arrays.stream(args).noneMatch(arg -> arg.startsWith(SPRING_CONFIG_NAME_KEY))) {
            String[] modifiedArgs = new String[args.length + 1];
            System.arraycopy(args, 0, modifiedArgs, 0, args.length);
            modifiedArgs[args.length] = DEFAULT_SPRING_CONFIG_PARAM;
            return modifiedArgs;
        }
        return args;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void afterStartUp() {
        long startupTimeMs = System.currentTimeMillis() - startTs;
        log.info("Started TBMQ Lightweight in {} seconds", TimeUnit.MILLISECONDS.toSeconds(startupTimeMs));
    }

}
