package org.netpreserve.warcaroo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JobConfigTest {
    @Test
    public void test() throws IOException {
        var mapper = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();
        var jobConfig = mapper.readValue(getClass().getResource("example.yaml"), JobConfig.class);
        var browser = jobConfig.browsers().getFirst();
        assertEquals(List.of("ssh", "-i", "key file", "user@host"), browser.shell());
        assertEquals(List.of("--proxy-server=socks://127.0.0.1:1080"), browser.options());
    }
}
