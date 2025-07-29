package com.tickloom.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Config {
    private final List<ProcessConfig> processConfigs;

    @JsonCreator
    public Config(@JsonProperty("processConfigs") List<ProcessConfig> processConfigs) {
        this.processConfigs = processConfigs;
    }

    public static Config load(String yaml) {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            return mapper.readValue(yaml, Config.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Config loadFromFile(Path filePath) {
        try {
            String yaml = Files.readString(filePath);
            return load(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<ProcessConfig> processConfigs() {
        return processConfigs;
    }
}


