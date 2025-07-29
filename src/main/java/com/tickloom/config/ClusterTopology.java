package com.tickloom.config;


import com.tickloom.ProcessId;
import com.tickloom.network.InetAddressAndPort;

import java.util.HashMap;
import java.util.Map;

/**
 * A class that represents the cluster layout of processes in a distributed system.
 * @ProcessConfig provides the configuration for each process. As of now
 * this is only the IP and port, but in future it can also include stuff like
 * rack, datacenter, region
 */
public class ClusterTopology {
    Map<ProcessId, ProcessConfig> processConfigs = new HashMap<>();

    public ClusterTopology(Config config) {
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