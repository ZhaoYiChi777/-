package com.intelligence.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 异步任务配置
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public ExecutorService taskExecutor() {
        ThreadFactory threadFactory = new ThreadFactory() {
            private int counter = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "deep-research-" + (++counter));
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(4, threadFactory);
    }
}
