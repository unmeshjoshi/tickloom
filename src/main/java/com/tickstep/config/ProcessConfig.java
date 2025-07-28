package com.tickstep.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tickstep.ProcessId;

public class ProcessConfig {
    private final ProcessId processId;
    private final String ip;
    private final int port;

    @JsonCreator
    public ProcessConfig(@JsonProperty("processId") String processId,
                         @JsonProperty("ip") String ip,
                         @JsonProperty("port") int port) {
        this.processId = ProcessId.of(processId);
        this.ip = ip;
        this.port = port;
    }

    public ProcessId processId() {
        return processId;
    }

    public String ip() {
        return ip;
    }

    public int port() {
        return port;
    }

}
