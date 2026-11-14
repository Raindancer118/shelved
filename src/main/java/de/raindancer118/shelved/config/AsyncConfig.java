package de.raindancer118.shelved.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    /** Claude calls take seconds to minutes; virtual threads, but capped so one user can't burn the budget in parallel. */
    @Bean
    Executor aiExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ai-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(4);
        return executor;
    }
}
