package com.tickloom.cmd;

import com.tickloom.ProcessId;
import com.tickloom.Replica;
import com.tickloom.algorithms.replication.quorum.QuorumReplica;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.NioNetwork;
import com.tickloom.storage.RocksDbStorage;
import com.tickloom.storage.Storage;
import com.tickloom.util.Clock;
import com.tickloom.util.SystemClock;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Server {

    private static final String OPT_CONFIG = "--config";
    private static final String OPT_ID = "--id";
    private static final String OPT_DATA = "--data";
    private static final String OPT_TIMEOUT = "--timeout"; // in ticks

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        if (needsHelp(options)) {
            printUsageAndExit(0);
        }

        String configPathStr = options.get(OPT_CONFIG);
        String processIdStr = options.get(OPT_ID);
        if (isBlank(configPathStr) || isBlank(processIdStr)) {
            System.err.println("Missing required arguments.");
            printUsageAndExit(1);
        }

        Path configPath = Paths.get(configPathStr);
        Config config = Config.loadFromFile(configPath);
        ClusterTopology clusterTopology = new ClusterTopology(config);

        ProcessId processId = ProcessId.of(processIdStr);
        ensureProcessExists(config, processId);

        String dataDir = options.getOrDefault(OPT_DATA, defaultDataDir(processId));
        int timeoutTicks = parseIntOrDefault(options.get(OPT_TIMEOUT), 10);

        JsonMessageCodec codec = new JsonMessageCodec();
        try {
            NioNetwork network = NioNetwork.create(clusterTopology, codec);
            MessageBus messageBus = new MessageBus(network, codec);
            network.bind(processId);

            List<ProcessId> peerIds = peersExcludingSelf(config, processId);
            Clock clock = new SystemClock();
            Storage storage = new RocksDbStorage(dataDir);

            Replica replica = new QuorumReplica(processId, peerIds, messageBus, codec, storage, clock, timeoutTicks);

            addShutdownHook(network, storage);

            System.out.println("TickLoom QuorumReplica starting");
            System.out.println("  id        = " + processId);
            System.out.println("  peers     = " + peerIds);
            System.out.println("  dataDir   = " + dataDir);
            System.out.println("  timeout   = " + timeoutTicks + " ticks");

            runEventLoop(network, replica, storage);

        } catch (IOException e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static void runEventLoop(NioNetwork network, Replica replica, Storage storage) throws IOException {
        while (true) {
            network.runToNanos(NioNetwork.MAX_IDLE_MS);
            replica.tick();
            storage.tick();
        }
    }

    private static void addShutdownHook(NioNetwork network, Storage storage) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            try {
                if (storage != null) storage.close();
            } catch (Exception ignored) {
            }
            try {
                if (network != null) network.close();
            } catch (Exception ignored) {
            }
        }, "tickloom-shutdown"));
    }

    private static void ensureProcessExists(Config config, ProcessId processId) {
        boolean exists = config.processConfigs().stream().anyMatch(pc -> Objects.equals(pc.processId(), processId));
        if (!exists) {
            throw new IllegalArgumentException("Process id not found in config: " + processId);
        }
    }

    private static List<ProcessId> peersExcludingSelf(Config config, ProcessId self) {
        List<ProcessId> all = new ArrayList<>();
        for (ProcessConfig pc : config.processConfigs()) {
            if (!pc.processId().equals(self)) {
                all.add(pc.processId());
            }
        }
        return all;
    }

    private static String defaultDataDir(ProcessId processId) {
        return Paths.get("build", "data", processId.value()).toString();
    }

    private static boolean needsHelp(Map<String, String> options) {
        return options.containsKey("-h") || options.containsKey("--help");
    }

    private static void printUsageAndExit(int code) {
        System.out.println("Usage: java -cp <jar> com.tickloom.cmd.Server --config <path/to/config.yaml> --id <process-id> [--data <data-dir>] [--timeout <ticks>]");
        System.out.println("  --config   Path to YAML config containing processConfigs");
        System.out.println("  --id       Process id to run (must exist in config)");
        System.out.println("  --data     Directory for RocksDB data (default: build/data/<id>)");
        System.out.println("  --timeout  Request timeout in ticks (default: 10)");
        System.exit(code);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-h":
                case "--help":
                    opts.put(arg, "true");
                    break;
                case OPT_CONFIG:
                case OPT_ID:
                case OPT_DATA:
                case OPT_TIMEOUT:
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for option: " + arg);
                        printUsageAndExit(1);
                    }
                    opts.put(arg, args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + arg);
                    printUsageAndExit(1);
            }
        }
        return opts;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (isBlank(value)) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}


