package com.tiangong.common.config;

import com.tiangong.common.web.GlobalExceptionHandler;
import com.tiangong.common.web.InternalTokenFilter;
import com.tiangong.common.web.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MVC 服务公共装配：服务通过 @Import(CommonWebConfig.class) 引入。
 * gateway 是 WebFlux 栈，不引入本配置（避免 servlet 依赖）。
 */
@Configuration
public class CommonWebConfig {

    @Bean
    public TraceIdFilter traceIdFilter() {
        return new TraceIdFilter();
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration(TraceIdFilter filter) {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setOrder(Integer.MIN_VALUE + 10);
        reg.addUrlPatterns("/*");
        return reg;
    }

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(
            @Value("${tg.internal-token:dev-internal-token}") String token) {
        FilterRegistrationBean<InternalTokenFilter> reg =
                new FilterRegistrationBean<>(new InternalTokenFilter(token));
        reg.setOrder(Integer.MIN_VALUE + 20);
        reg.addUrlPatterns("/internal/*");
        return reg;
    }
}
