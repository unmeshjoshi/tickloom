package com.tickloom.testkit.dsl.quorum;

import com.tickloom.ProcessId;
import com.tickloom.testkit.dsl.SetupScope;
import com.tickloom.testkit.dsl.semanticmodel.ClusterEvent;

/**
 * Quorum-specific initial-condition verbs. Other protocols (Txn, Raft, …)
 * define their own {@link SetupScope} subtype with verbs that match their
 * state model — there is no shared notion of "clock" or "seed" in the base.
 */
public interface QuorumSetupScope extends SetupScope {
    QuorumSetupScope serverTimeAt(ProcessId node, long time);
    QuorumSetupScope clientTimeAt(ProcessId client, long time);

    @Override
    QuorumSetupScope apply(ClusterEvent event);
}
