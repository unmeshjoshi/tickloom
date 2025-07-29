package com.tickstep.config;

import com.tickstep.ProcessId;
import com.tickstep.network.InetAddressAndPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTopologyTest {

    @Test
    public void getAddressByProcessId() {
        ProcessId pid1 = ProcessId.random();
        ProcessId pid2 = ProcessId.random();
        //create example yaml file content
        //yaml has processid to ip and port
        String yaml = "processConfigs:\n" +
                "  - processId: \"" + pid1.name() + "\"\n" +
                "    ip: \"10.12.0.10\"\n" +
                "    port: 8080\n" +
                "  - processId: \"" + pid2.name() + "\"\n" +
                "    ip: \"10.12.10.10\"\n" +
                "    port: 8080";

        Config config = Config.load(yaml);
        ClusterTopology registry = new ClusterTopology(config);

        assertEquals(InetAddressAndPort.from("10.12.0.10", 8080)
                , registry.getInetAddress(pid1));
        assertEquals(InetAddressAndPort.from("10.12.10.10", 8080), registry.getInetAddress(pid2));

    }
}