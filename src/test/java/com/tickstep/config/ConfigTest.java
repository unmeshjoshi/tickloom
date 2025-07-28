package com.tickstep.config;

import com.tickstep.ProcessId;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ConfigTest {

    @Test
    public void hasConfigsForProcessId() {
        ProcessId pid = ProcessId.random();
        //create example yaml with multiple processes

        String yaml = "processConfigs:\n" +
                "  - processId: \"" + pid.name() + "\"\n" +
                "    ip: \"192.168.1.100\"\n" +
                "    port: 8080";

        Config config = Config.load(yaml);
        assertEquals(pid, config.processConfigs().get(0).processId());
    }

    @Test
    public void hasConfigsForMultipleProcessIds() {
        ProcessId pid1 = ProcessId.random();
        ProcessId pid2 = ProcessId.random();
        //create example yaml file content
        //yaml has processid to ip and port
        String yaml = "processConfigs:\n" +
                "  - processId: \"" + pid1.name() + "\"\n" +
                "    ip: \"127.0.0.1\"\n" +
                "    port: 8080\n" +
                "  - processId: \"" + pid2.name() + "\"\n" +
                "    ip: \"127.0.0.1\"\n" +
                "    port: 8080";

        Config config = Config.load(yaml);
        assertEquals(2, config.processConfigs().size());
        assertEquals(pid1, config.processConfigs().get(0).processId());
        assertEquals(pid2, config.processConfigs().get(1).processId());
    }

    @Test
    public void loadFromFile() {
        Path configFile = Path.of("src/test/resources/sample-config.yaml");
        Config config = Config.loadFromFile(configFile);
        
        assertNotNull(config);
        assertEquals(4, config.processConfigs().size());
        
        // Verify first server
        var server1 = config.processConfigs().get(0);
        assertEquals("server-1", server1.processId().name());
        assertEquals("192.168.1.100", server1.ip());
        assertEquals(8080, server1.port());
        
        // Verify second server
        var server2 = config.processConfigs().get(1);
        assertEquals("server-2", server2.processId().name());
        assertEquals("192.168.1.101", server2.ip());
        assertEquals(8081, server2.port());
        
        // Verify first worker
        var worker1 = config.processConfigs().get(2);
        assertEquals("worker-1", worker1.processId().name());
        assertEquals("10.0.0.50", worker1.ip());
        assertEquals(9000, worker1.port());
        
        // Verify second worker
        var worker2 = config.processConfigs().get(3);
        assertEquals("worker-2", worker2.processId().name());
        assertEquals("10.0.0.51", worker2.ip());
        assertEquals(9001, worker2.port());
    }
}
