package com.tickloom.config;


import com.tickloom.ProcessId;
import com.tickloom.network.InetAddressAndPort;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents the cluster layout of processes in a distributed system.
 * Each process is described by a {@link ProcessConfig}.
 * <p>
 * Currently it captures network coordinates (IP and port); in the future it
 * may include additional placement attributes such as rack, datacenter, and region.
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