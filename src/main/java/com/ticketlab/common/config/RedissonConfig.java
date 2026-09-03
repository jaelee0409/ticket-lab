package com.ticketlab.common.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    /**
     * Builds the Redisson client from Spring Boot's resolved connection details
     * rather than from the raw spring.data.redis.* properties.
     *
     * The distinction matters in tests. @ServiceConnection on a Testcontainers
     * Redis container contributes a DataRedisConnectionDetails bean pointing at
     * the container's random port - it does not rewrite the properties. Reading
     * the properties directly gave localhost:6379, which happened to work on a
     * machine with a local Redis running and failed everywhere else.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(DataRedisConnectionDetails details) {
        DataRedisConnectionDetails.Standalone standalone = details.getStandalone();

        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + standalone.getHost() + ":" + standalone.getPort())
                .setDatabase(standalone.getDatabase());

        if (details.getPassword() != null) {
            server.setUsername(details.getUsername()).setPassword(details.getPassword());
        }

        return Redisson.create(config);
    }
}
