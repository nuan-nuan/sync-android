/**
 * Copyright (c) 2015 Cloudant, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.cloudant.sync.notifications;

import com.cloudant.sync.replication.Replicator;

/**
 * <p>Event posted when a state transition to COMPLETE or STOPPED is
 * completed.</p>
 *
 * <p>{@code complete} may be called from one of the replicator's
 * worker threads.</p>
 *
 * <p>Continuous replications (when implemented) will never complete.</p>
 *
 */
public class ReplicationCompleted {

    public ReplicationCompleted(Replicator replicator,
                                int documentsReplicated,
                                int batchesReplicated) {
        this.replicator = replicator;
        this.documentsReplicated = documentsReplicated;
        this.batchesReplicated = batchesReplicated;
    }
    
    /** 
     * The {@code Replicator} issuing the event
     */
    public final Replicator replicator;

    /**
     * The total number of documents replicated by the {@link #replicator}
     */
    public final int documentsReplicated;

    /**
     * The total number of batches replicated by the {@link #replicator}
     */
    public final int batchesReplicated;
    
    @Override
    public boolean equals(Object other) {
        if (other instanceof ReplicationCompleted) {
            ReplicationCompleted rc = (ReplicationCompleted)other;
            return this.replicator == rc.replicator;
        }
        return false;
    }

}
