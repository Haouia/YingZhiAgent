package com.campagent.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class DocumentConfig {
    @Bean("documentExecutor") ThreadPoolTaskExecutor executor(){
        var executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(8);executor.setThreadNamePrefix("document-");executor.initialize();return executor;
    }
}

