package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;

public class JobConfigTest {
    @Test
    public void test() throws IOException {
        var mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        mapper.readValue(getClass().getResource("example.yaml"), JobConfig.class);
    }
}
