package com.america.backend.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /** 여러 비서를 동시에 호출할 때 사용합니다. gRPC 대기는 I/O이므로 가상 스레드를 사용합니다. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService aiExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
