package com.client360.common.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JSON conventions shared by every service (SPEC.md §4.1). */
@Configuration(proxyBeanMethods = false)
public class JsonConfig {

    @Bean
    Jackson2ObjectMapperBuilderCustomizer client360JsonConventions() {
        return builder -> builder.serializerByType(Instant.class, new InstantMillisSerializer())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // §5.3 PATCH: an unknown field is 400 VALIDATION_FAILED, not silently ignored.
                .featuresToEnable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * §4.1: RFC 3339 UTC with exactly three fraction digits, {@code 2026-09-09T11:42:07.113Z}.
     * PostgreSQL keeps microseconds; the API does not expose them.
     */
    static final class InstantMillisSerializer extends StdSerializer<Instant> {

        private static final DateTimeFormatter FORMAT =
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

        InstantMillisSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            gen.writeString(FORMAT.format(value));
        }
    }
}
