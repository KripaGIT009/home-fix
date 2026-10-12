package com.homefix.shared.observability.json;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The platform's JSON settings on top of Jackson 3's defaults, shared by every service's
 * {@code JsonMapper} (through {@link HomefixJacksonAutoConfiguration}) and by tests that build a
 * mapper of their own, such as a standalone MockMvc.
 *
 * <p>Jackson 3 turned {@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} on by default:
 * a request that leaves out a {@code boolean} or {@code int} field would be refused as unreadable
 * (a bare 400) instead of the field taking its default and the request reaching validation.
 * The API contracts were written against Jackson 2's behaviour, so it is kept.
 */
public final class HomefixJson {

    private HomefixJson() {
    }

    /** Applies the platform settings to {@code builder}. */
    public static JsonMapper.Builder configure(JsonMapper.Builder builder) {
        return builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }

    /** A builder with the platform settings, for code and tests outside a Spring context. */
    public static JsonMapper.Builder builder() {
        return configure(JsonMapper.builder());
    }
}
