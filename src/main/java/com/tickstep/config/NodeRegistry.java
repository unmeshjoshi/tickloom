package com.tickstep.config;


import com.tickstep.ProcessId;
import com.tickstep.network.InetAddressAndPort;

import java.util.HashMap;
import java.util.Map;

public class NodeRegistry {
    Map<ProcessId, ProcessConfig> processConfigs = new HashMap<>();

    public NodeRegistry(Config config) {
        for (ProcessConfig processConfig : config.processConfigs()) {
            processConfigs.put(processConfig.processId(), processConfig);
        }
    }

    public InetAddressAndPort getInetAddress(ProcessId processId) {
        ProcessConfig processConfig = processConfigs.get(processId);
        if (processConfig == null) {
            throw new IllegalArgumentException("No config found for processId: " + processId);
        }
        return InetAddressAndPort.from(processConfig.ip(), processConfig.port()); //processConfig.();
    }
}