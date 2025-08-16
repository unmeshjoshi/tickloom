package com.tickloom.cmd;

import com.tickloom.ProcessId;
import com.tickloom.algorithms.replication.quorum.QuorumReplicaClient;
import com.tickloom.config.ClusterTopology;
import com.tickloom.config.Config;
import com.tickloom.config.ProcessConfig;
import com.tickloom.future.ListenableFuture;
import com.tickloom.messaging.MessageBus;
import com.tickloom.network.JsonMessageCodec;
import com.tickloom.network.NioNetwork;
import com.tickloom.util.Clock;
import com.tickloom.util.SystemClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;

/**
 * Command-line client to send GET/SET requests to quorum replicas.
 */
public class Client {

    private static final String OPT_CONFIG = "--config";
    private static final String OPT_ID = "--id"; // client id
    private static final String OPT_REPLICAS = "--replicas"; // comma-separated process ids
    private static final String OPT_GET = "--get"; // key
    private static final String OPT_SET = "--set"; // key
    private static final String OPT_VALUE = "--value"; // value for set
    private static final String OPT_TIMEOUT = "--timeout"; // ticks
    private static final String OPT_DEADLINE_MS = "--deadline-ms"; // wall-clock budget

    public static void main(String[] args) {
        Map<String, String> options = parseArgs(args);
        if (options.containsKey("-h") || options.containsKey("--help")) {
            printUsageAndExit(0);
        }

        String configPathStr = options.get(OPT_CONFIG);
        String clientIdStr = options.get(OPT_ID);
        String replicasCsv = options.get(OPT_REPLICAS);

        boolean isGet = options.containsKey(OPT_GET);
        boolean isSet = options.containsKey(OPT_SET);

        if (isBlank(configPathStr) || isBlank(clientIdStr) || isBlank(replicasCsv) || (isGet == isSet)) {
            System.err.println("Invalid or missing arguments.");
            printUsageAndExit(1);
        }

        String keyArg = isGet ? options.get(OPT_GET) : options.get(OPT_SET);
        if (isBlank(keyArg)) {
            System.err.println("Key is required for GET/SET");
            printUsageAndExit(1);
        }
        String valueArg = options.get(OPT_VALUE);
        if (isSet && isBlank(valueArg)) {
            System.err.println("--value is required for --set");
            printUsageAndExit(1);
        }

        int timeoutTicks = parseIntOrDefault(options.get(OPT_TIMEOUT), 100);
        long deadlineMs = parseLongOrDefault(options.get(OPT_DEADLINE_MS), 10000L);

        Path configPath = Paths.get(configPathStr);
        Config config = Config.loadFromFile(configPath);
        ClusterTopology clusterTopology = new ClusterTopology(config);

        ProcessId clientId = ProcessId.of(clientIdStr);
        List<ProcessId> replicas = parseReplicasCsv(replicasCsv);
        validateReplicasExist(config, replicas);

        JsonMessageCodec codec = new JsonMessageCodec();
        try {
            NioNetwork network = NioNetwork.create(clusterTopology, codec);
            MessageBus bus = new MessageBus(network, codec);

            Clock clock = new SystemClock();

            QuorumReplicaClient client = new QuorumReplicaClient(clientId, replicas, bus, codec, clock, timeoutTicks);

            addShutdownHook(network);

            byte[] key = keyArg.getBytes(StandardCharsets.UTF_8);
            ListenableFuture<?> future;
            if (isGet) {
                future = client.get(key);
            } else {
                byte[] value = valueArg.getBytes(StandardCharsets.UTF_8);
                future = client.set(key, value);
            }

            boolean ok = runUntilComplete(network, client, future, deadlineMs);
            if (!ok) {
                System.err.println("Timed out waiting for response");
                System.exit(2);
            }

            if (future.isFailed()) {
                System.err.println("Request failed: " + future.getException().getMessage());
                System.exit(3);
            }

            if (isGet) {
                var resp = (com.tickloom.algorithms.replication.quorum.GetResponse) future.getResult();
                if (resp.found()) {
                    System.out.println(new String(resp.value(), StandardCharsets.UTF_8));
                } else {
                    System.out.println("<not-found>");
                }
            } else {
                var resp = (com.tickloom.algorithms.replication.quorum.SetResponse) future.getResult();
                System.out.println(resp.success() ? "OK" : "FAILED");
            }

            System.exit(0);
        } catch (IOException e) {
            System.err.println("Failed to start client: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(4);
        }
    }

    private static boolean runUntilComplete(NioNetwork network, com.tickloom.Process client, ListenableFuture<?> future, long deadlineMs) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < deadlineMs) {
            network.tick();
            client.tick();
            if (future.isCompleted() || future.isFailed()) {
                return true;
            }
        }
        return false;
    }

    private static void addShutdownHook(NioNetwork network) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (network != null) network.close();
            } catch (Exception ignored) {
            }
        }, "tickloom-client-shutdown"));
    }

    private static List<ProcessId> parseReplicasCsv(String csv) {
        List<ProcessId> ids = new ArrayList<>();
        for (String s : csv.split(",")) {
            if (!s.isBlank()) ids.add(ProcessId.of(s.trim()));
        }
        return ids;
    }

    private static void validateReplicasExist(Config config, List<ProcessId> replicas) {
        Set<ProcessId> all = new HashSet<>();
        for (ProcessConfig pc : config.processConfigs()) all.add(pc.processId());
        for (ProcessId r : replicas) {
            if (!all.contains(r)) {
                throw new IllegalArgumentException("Replica id not found in config: " + r);
            }
        }
    }

    private static void printUsageAndExit(int code) {
        System.out.println("Usage: java -cp <jar> com.tickloom.cmd.Client --config <path/to/config.yaml> --id <client-id> --replicas <id1,id2,...> (--get <key> | --set <key> --value <value>) [--timeout <ticks>] [--deadline-ms <ms>]");
        System.exit(code);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h":
                case "--help":
                    opts.put(a, "true");
                    break;
                case OPT_CONFIG:
                case OPT_ID:
                case OPT_REPLICAS:
                case OPT_GET:
                case OPT_SET:
                case OPT_VALUE:
                case OPT_TIMEOUT:
                case OPT_DEADLINE_MS:
                    if (i + 1 >= args.length) {
                        System.err.println("Missing value for option: " + a);
                        printUsageAndExit(1);
                    }
                    opts.put(a, args[++i]);
                    break;
                default:
                    System.err.println("Unknown option: " + a);
                    printUsageAndExit(1);
            }
        }
        return opts;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static int parseIntOrDefault(String v, int d) { try { return isBlank(v) ? d : Integer.parseInt(v); } catch (NumberFormatException e) { return d; } }
    private static long parseLongOrDefault(String v, long d) { try { return isBlank(v) ? d : Long.parseLong(v); } catch (NumberFormatException e) { return d; } }
}


