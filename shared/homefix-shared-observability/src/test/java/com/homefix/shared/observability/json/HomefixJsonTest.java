package com.homefix.shared.observability.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

class HomefixJsonTest {

    record Request(String name, boolean emergency, int count) {
    }

    @Test
    void aMissingPrimitiveTakesItsDefault() {
        Request request = HomefixJson.builder().build().readValue("{\"name\":\"a\"}", Request.class);

        assertThat(request).isEqualTo(new Request("a", false, 0));
    }

    @Test
    void jackson3AloneWouldRefuseIt() {
        assertThatThrownBy(() -> JsonMapper.builder().build().readValue("{\"name\":\"a\"}", Request.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void springBootsMapperGetsTheSettings() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class,
                        HomefixJacksonAutoConfiguration.class))
                .run(context -> assertThat(context.getBean(JsonMapper.class)
                        .readValue("{}", Request.class)).isEqualTo(new Request(null, false, 0)));
    }
}
