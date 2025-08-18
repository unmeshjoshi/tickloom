package com.tickloom.network;

import com.tickloom.ProcessId;
import com.tickloom.messaging.Message;
import com.tickloom.messaging.MessageType;
import com.tickloom.testkit.NodeGroup;

import java.util.*;

/**
 * Simulated network implementation that provides deterministic message delivery
 * with configurable delays and packet loss for testing distributed systems.
 * <p>
 * This implementation follows the Service Layer reactive tick() pattern:
 * - send() queues messages for future delivery
 * - tick() processes queued messages and delivers them.
 */
public class SimulatedNetwork extends Network {

    private final Random random;
    private long defaultDelayTicks;
    private double defaultPacketLossRate;

    private final PriorityQueue<QueuedMessage> pendingMessages = new PriorityQueue<>();

    // Internal counter for delivery timing (TigerBeetle pattern)
    private long currentTick = 0;

    // Network partitioning state
    private final Set<NetworkLink> partitionedLinks = new HashSet<>();

    // Per-link configuration
    private final Map<NetworkLink, Double> linkPacketLoss = new HashMap<>();
    private final Map<NetworkLink, Long> cloggedUntilTick = new HashMap<>();
    private final Map<NetworkLink, FaultRule> linkFaultRules = new HashMap<>();
    //Should use LinkedHashset for determinstic ordering while iterating for introducing
    //network partitions
    private LinkedHashSet<ProcessId> knownNodes = new LinkedHashSet<>();

    private boolean isClogged(NetworkLink link) {
        return cloggedUntilTick.getOrDefault(link, 0L) > currentTick;
    }

    private void clogFor(NetworkLink link, long durationTicks) {
        if (durationTicks <= 0) return;
        long until = currentTick + durationTicks;
        cloggedUntilTick.merge(link, until, Math::max); // extend if already clogged
    }

    private boolean shouldClog() {
        return options.pathClogProb > 0 && random.nextDouble() < options.pathClogProb;
    }

    public SimulatedNetwork withCloggingProbability(double v) {
        this.options.pathClogProb = v;
        return this;
    }

    static class FaultRule {
        NetworkLink link;
        MessageType messageType;
        int seenTimes = 0;
        int nth;

        public FaultRule(NetworkLink link, MessageType messageType) {
            this(link, messageType, 0);
        }

        public FaultRule(NetworkLink link, MessageType messageType, int nth) {
            this.link = link;
            this.messageType = messageType;
            this.nth = nth;
        }

        public boolean matches(Message message) {
            if (!linkFrom(message).equals(link)) return false;
            if (!message.messageType().equals(messageType)) return false;

            seenTimes++;
            return dropAllMatchingMessagesOfType()
                    || isNthOccurrenceReached();
        }

        private boolean dropAllMatchingMessagesOfType() {
            return nth == 0;
        }

        private boolean isNthOccurrenceReached() {
            return seenTimes == nth;
        }
    }

    private Message lastDeliveredMessage;


    /**
     * Creates a SimulatedNetwork with configurable behavior.
     *
     * @param random         seeded random generator for deterministic behavior
     * @param delayTicks     number of ticks to delay message delivery (0 = immediate)
     * @param packetLossRate probability [0.0-1.0] that a message will be lost
     */
    private SimulatedNetwork(Random random, int delayTicks, double packetLossRate, NetworkOptions options) {
        if (random == null) {
            throw new IllegalArgumentException("Random cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }
        if (packetLossRate < 0.0 || packetLossRate > 1.0) {
            throw new IllegalArgumentException("Packet loss rate must be between 0.0 and 1.0");
        }

        this.random = random;
        this.defaultDelayTicks = delayTicks;
        this.defaultPacketLossRate = packetLossRate;
        this.options = options;
    }

    public static SimulatedNetwork noLossNetwork(Random random) {
        return new SimulatedNetwork(random, 0, 0, NetworkOptions.noLoss());
    }

    public static SimulatedNetwork lossyNetwork(Random random, double packetLossRate) {
        return new SimulatedNetwork(random, 0, packetLossRate, NetworkOptions.create());
    }

    public SimulatedNetwork withDelayTicks(int delayTicks) {
        this.defaultDelayTicks = delayTicks;
        return this;
    }

    public SimulatedNetwork withPacketLossRate(double packetLossRate) {
        this.defaultPacketLossRate = packetLossRate;
        return this;
    }

    @Override
    public void send(Message message) {
        validateMessage(message);

        addKnownProcessIds(message);

        NetworkLink link = linkFrom(message);

        if (link.isPartitioned(partitionedLinks)) {
            return; // Message dropped due to partition
        }

        if (matchesFaultRule(link, message)) {
            return; // Message dropped due to fault rule
        }

        if (shouldDropPacket(link)) {
            return; // Message lost due to packet loss
        }

        // Use internal counter to calculate delivery tick (TigerBeetle pattern)
        long deliveryTick = calculateDeliveryTick(link, currentTick);
        queueForDelivery(message, deliveryTick);
    }

    private void addKnownProcessIds(Message message) {
        addIfAbsent(message.destination());
        addIfAbsent(message.source());
    }

    private void addIfAbsent(ProcessId message) {
        if (!knownNodes.contains(message)) {
            knownNodes.add(message);
        }
    }

    private void maybeClogPaths() {
        if (shouldClog()) {
            //create network links for all known nodes.
            List<NetworkLink> links = new ArrayList<>();
            for (ProcessId source : knownNodes) {
                for (ProcessId destination : knownNodes) {
                    if (source != destination) {
                        NetworkLink link = new NetworkLink(source, destination);
                        links.add(link);
                    }
                }
            }
            for (NetworkLink link : links) {
                if (shouldClog()) {
                    long duration = sampleExpTicks(random, options.pathClogMeanTicks);
                    clogFor(link, duration);
                }
            }
        }
    }

    static long sampleExpTicks(Random rng, long meanTicks) {
        if (meanTicks <= 0) return 0L;
        // Exp(1): -ln(U), U ~ Uniform(0,1)
        double u = rng.nextDouble();
        // Guard against u=0 producing +Inf; nextDouble() is (0,1), but be defensive:
        if (u == 0.0) u = Double.MIN_VALUE;
        double x = -Math.log(u);    // Exp(1)
        double d = x * meanTicks;   // mean = meanTicks
        if (d >= Long.MAX_VALUE) return Long.MAX_VALUE;
        long ns = (long) d;         // truncation is fine here; you can also Math.round
        return ns;
    }

    static class NetworkOptions {
        public final PartitionMode partitionMode;
        public final double  unpartitionProb;  // 7%/tick to heal once partitioned
        public final int     unpartitionStabilityTicks;    // stay healthy at least 20 ticks
        public final double  partitionProb;  // 2%/tick to create a partition
        public final boolean partitionSymmetry;  // cut both directions by default
        public final int     partitionStabilityTicks;    // once partitioned, hold it ≥150 ticks
        public double pathClogProb; //1% probability
        public final int pathClogMeanTicks;

        public NetworkOptions(PartitionMode partitionMode, double v, int i, double v1, boolean b, int i1, double v2, int i3) {

            this.partitionMode = partitionMode;
            this.unpartitionProb = v;
            this.unpartitionStabilityTicks = i;
            this.partitionProb = v1;
            this.partitionSymmetry = b;
            this.partitionStabilityTicks = i1;
            this.pathClogProb = v2;
            this.pathClogMeanTicks = i3;
        }

        public static NetworkOptions create() {
            NetworkOptions networkOptions = new NetworkOptions(
                    PartitionMode.HALF_HALF,
                    0.07,
                    20,
                    0.02,
                    true,
                    150, 1/100, 2000);
            return networkOptions;
        }

        public static NetworkOptions noLoss() {
            return new NetworkOptions(PartitionMode.NONE, 0.0, 0, 0.0, true,  0, 0.0, 0);
        }
    }

    enum PartitionMode {
        NONE,
        RANDOM,
        HALF_HALF
    }
    int autoPartitionStability = 0;
    boolean autoPartitionActive = false;
    NetworkOptions options;
    private void maybeFlipPartitions() {
        // No auto-partitioning configured?
        if (options.partitionMode == PartitionMode.NONE) return;

        // Respect min-stability timers
        if (autoPartitionStability > 0) {
            autoPartitionStability--;
            return;
        }

        // If a partition is active, maybe heal it:
        if (autoPartitionActive) {
            if (random.nextDouble() < options.unpartitionProb) {
                healAllPartitions();
                autoPartitionActive = false;
                autoPartitionStability = options.unpartitionStabilityTicks;
            }
            return;
        }

        // Otherwise maybe create a new partition:
        if (knownNodes.size() < 2) return; // nothing to split
        if (random.nextDouble() >= options.partitionProb) return;

        // Build a randomized list of nodes to split
        List<ProcessId> nodes = new ArrayList<>(knownNodes);

        int sizeA;
        switch (options.partitionMode) {
            case RANDOM:
                // 1..n-1
                sizeA = 1 + random.nextInt(Math.max(1, nodes.size() - 1));
                break;
            case HALF_HALF:
                sizeA = Math.max(1, nodes.size() / 2);
                break;
            default: // NONE already returned above
                return;
        }

        NodeGroup A = new NodeGroup(new LinkedHashSet<>(nodes.subList(0, sizeA)));
        NodeGroup B = new NodeGroup(new LinkedHashSet<>(nodes.subList(sizeA, nodes.size())));

        // Start fresh: clear old partitions first
        healAllPartitions();

        // Apply the partition cut
        boolean symmetric = options.partitionSymmetry;
        if (symmetric) {
            partitionTwoWay(A, B);
        } else {
            partitionOneWay(A, B);
        }

        autoPartitionActive = true;
        autoPartitionStability = options.partitionStabilityTicks;
    }

    private void partitionOneWay(NodeGroup A, NodeGroup B) {
        for (ProcessId a : A.processIds()) {
            for (ProcessId b : B.processIds()) {
                    // asymmetric: block only one direction, chosen randomly
                    if (random.nextBoolean()) {
                        partitionOneWay(a, b);
                    } else {
                        partitionOneWay(b, a);
                    }
            }
        }
    }

    private void partitionTwoWay(NodeGroup A, NodeGroup B) {
        for (ProcessId a : A.processIds()) {
            for (ProcessId b : B.processIds()) {
                partitionTwoWay(a, b);
            }
        }
    }


    private boolean matchesFaultRule(NetworkLink link, Message message) {
        if (!linkFaultRules.containsKey(link)) {
            return false;
        }

        FaultRule faultRule = linkFaultRules.get(link);
        return faultRule.matches(message);
    }

    /**
     * Advances the simulation by one tick and processes network operations.
     * <p>
     * This method implements the reactive Service Layer tick() pattern:
     * - It is called by the simulation loop to advance network state
     * - It processes I/O operations that have just completed (message deliveries)
     * - It does NOT initiate new actions (that's the Application Layer's role)
     * <p>
     * Timing Mechanics:
     * 1. Increments internal currentTick counter
     * 2. Processes all messages whose {@code deliveryTick <= currentTick}
     * 3. Moves processed messages from pendingMessages to deliveredMessages
     * 4. Messages become available via receive() after processing
     * <p>
     * Deterministic Behavior:
     * - Messages are processed in FIFO order from the pending queue
     * - Delivery timing is exact: message sent at tick T with delay D
     * becomes available at tick T+D+1 (after this tick() call)
     * - Multiple calls to tick() with same state produce identical results
     * <p>
     * Example Timeline:
     * Tick 0: send(message) with 2-tick delay -> queued for delivery at tick 2
     * Tick 1: tick() -> currentTick = 1, message not delivered ({@code 1 < 2})
     * Tick 2: tick() -> currentTick = 2, message delivered ({@code 2 <= 2})
     * Tick 2: receive() -> returns the delivered message
     * <p>
     * This method must be called by the simulation master thread in the correct
     * order relative to other components' tick() methods to maintain determinism.
     */
    @Override
    public void tick() {
        currentTick++; // Increment internal counter (TigerBeetle pattern)
        maybeFlipPartitions();
        maybeClogPaths();
        deliverPendingMessagesFor(currentTick);
    }

    // Domain-focused helper methods for message sending

    private void validateMessage(Message message) {
        if (message == null) {
            throw new IllegalArgumentException("Message cannot be null");
        }
    }

    private static NetworkLink linkFrom(Message message) {
        // TigerBeetle approach: Handle null source addresses gracefully
        if (message.source() == null) {
            // For client messages, use destination-only routing
            // This allows network partitioning and packet loss to work correctly
            // without requiring fake client addresses
            return new NetworkLink(message.destination(), message.destination());
        }

        // For server messages, use normal source->destination routing
        return new NetworkLink(message.source(), message.destination());
    }

    private boolean shouldDropPacket(NetworkLink link) {
        double effectivePacketLoss = linkPacketLoss.getOrDefault(link, defaultPacketLossRate);
        return effectivePacketLoss > 0.0 && random.nextDouble() < effectivePacketLoss;
    }

    private long calculateDeliveryTick(NetworkLink link, long currentTick) {
        // If the path is clogged now, delay to cloggedUntil and requeue.
        long scheduleAtTick = currentTick + 1;
        if (isClogged(link)) {
            scheduleAtTick =
                    currentTick + cloggedUntilTick.getOrDefault(link, defaultDelayTicks);


        }
        return scheduleAtTick;
    }

    private void queueForDelivery(Message message, long deliveryTick) {
        pendingMessages.offer(new QueuedMessage(message, deliveryTick, currentTick));
    }

    private void deliverPendingMessagesFor(long tickTime) {
        // Process messages whose delivery time has now arrived
        // PriorityQueue ensures we process in delivery time order
        while (!pendingMessages.isEmpty() &&
                pendingMessages.peek().deliveryTick <= tickTime) {

            QueuedMessage queuedMessage = pendingMessages.poll();
            Message message = queuedMessage.message;
            lastDeliveredMessage = message;

            dispatchReceivedMessage(message);

            System.out.println("SimNet: delivered " + message);
        }
    }

    // Network Partitioning Implementation
    public void partitionTwoWay(ProcessId source, ProcessId destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }

        // Add both directions of the link
        partitionedLinks.add(new NetworkLink(source, destination));
        partitionedLinks.add(new NetworkLink(destination, source));
    }

    public void partitionOneWay(ProcessId source, ProcessId destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }

        // Add only the specified direction
        partitionedLinks.add(new NetworkLink(source, destination));
    }

    public void healAllPartitions() {
        partitionedLinks.clear();
    }

    public void healPartition(ProcessId source, ProcessId destination) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        removePartitionBetween(source, destination);
    }

    public void removePartitionBetween(ProcessId a, ProcessId b) {
        for (NetworkLink link : bothDirections(a, b)) {
            partitionedLinks.remove(link);
            linkFaultRules.remove(link);
        }
    }

    private static List<NetworkLink> bothDirections(ProcessId a, ProcessId b) {
        return List.of(new NetworkLink(a, b), new NetworkLink(b, a));
    }

    public void setDelay(ProcessId source, ProcessId destination, long delayTicks) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        if (delayTicks < 0) {
            throw new IllegalArgumentException("Delay ticks cannot be negative");
        }

        cloggedUntilTick.put(new NetworkLink(source, destination), delayTicks);
    }

    public void setPacketLoss(ProcessId source, ProcessId destination, double lossRate) {
        if (source == null || destination == null) {
            throw new IllegalArgumentException("Source and destination addresses cannot be null");
        }
        if (lossRate < 0.0 || lossRate > 1.0) {
            throw new IllegalArgumentException("Packet loss rate must be between 0.0 and 1.0");
        }

        linkPacketLoss.put(new NetworkLink(source, destination), lossRate);
    }

    public void dropMessagesOfType(ProcessId source, ProcessId destination, MessageType messageType) {
        linkFaultRules
                .computeIfAbsent(new NetworkLink(source, destination), k -> new FaultRule(new NetworkLink(source, destination), messageType));
    }

    public void dropNthMessagesOfType(ProcessId source, ProcessId destination, MessageType messageType, int nth) {
        linkFaultRules
                .computeIfAbsent(new NetworkLink(source, destination), k -> new FaultRule(new NetworkLink(source, destination), messageType, nth));
    }

    /**
     * Returns the most recently delivered message (for testing).
     */
    public Message getLastDeliveredMessage() {
        return lastDeliveredMessage;
    }

    /**
     * Internal record to track messages with their delivery timing.
     * Implements Comparable to allow PriorityQueue ordering by delivery time.
     */
    private record QueuedMessage(Message message, long deliveryTick, long sequenceNumber)
            implements Comparable<QueuedMessage> {

        @Override
        public int compareTo(QueuedMessage other) {
            // Primary ordering: delivery tick (earlier messages first)
            int tickComparison = Long.compare(this.deliveryTick, other.deliveryTick);
            if (tickComparison != 0) {
                return tickComparison;
            }

            // Secondary ordering: sequence number for FIFO behavior when delivery ticks are equal
            return Long.compare(this.sequenceNumber, other.sequenceNumber);
        }
    }

    /**
     * Internal record to represent a directed network link between two addresses.
     * Used as a key for tracking partitions and per-link configurations.
     */
    private record NetworkLink(ProcessId source, ProcessId destination) {

        /**
         * Checks if this link is partitioned (blocked).
         * A link is partitioned if it exists in the partitioned links set.
         *
         * @param partitionedLinks the set of currently partitioned links
         * @return true if this link is partitioned, false otherwise
         */
        boolean isPartitioned(Set<NetworkLink> partitionedLinks) {
            return partitionedLinks.contains(this);
        }
    }
} 