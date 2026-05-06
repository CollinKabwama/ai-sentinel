package io.aisentinel.trainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TrainerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TrainerConfig.class);

    @Test
    void exposesObjectMapperBean() {
        runner.run(ctx -> assertThat(ctx.getBean(ObjectMapper.class)).isNotNull());
    }
}
