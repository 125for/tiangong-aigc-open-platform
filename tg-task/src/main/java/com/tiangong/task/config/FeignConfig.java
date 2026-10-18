package com.tiangong.task.config;

import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 服务间调用统一附带 X-Internal-Token。 */
@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor internalTokenInterceptor(
            @Value("${tg.internal-token}") String token) {
        return template -> template.header("X-Internal-Token", token);
    }
}
