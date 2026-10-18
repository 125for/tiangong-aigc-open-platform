package com.tiangong.common.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/** 统一 LocalDateTime 序列化格式（避免数组形式/时区歧义）。 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer localDateTimeFormat() {
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(DT))
                .deserializers(new LocalDateTimeDeserializer(DT));
    }
}
