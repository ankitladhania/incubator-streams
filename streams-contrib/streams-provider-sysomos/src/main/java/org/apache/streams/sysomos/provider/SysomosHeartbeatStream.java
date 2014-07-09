/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.streams.sysomos.provider;

import com.sysomos.xml.BeatApi;
import org.apache.streams.core.StreamsDatum;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a {@link java.lang.Runnable} query mechanism for grabbing documents from the Sysomos API
 */
public class SysomosHeartbeatStream implements Runnable {

    private static enum OperatingMode { DATE, DOC_MATCH}

    private final static Logger LOGGER = LoggerFactory.getLogger(SysomosHeartbeatStream.class);

    private final SysomosProvider provider;
    private final SysomosClient client;
    private final String heartbeatId;
    private final long maxApiBatch;
    private final long minLatency;
    private final OperatingMode mode;

    private String lastID;
    private DateTime beforeTime;
    private DateTime afterTime;
    private DateTime lastRunTime;
    private int offsetCount = 0;
    private boolean enabled = true;

    public SysomosHeartbeatStream(SysomosProvider provider, String heartbeatId) {
        this(provider, heartbeatId, null, DateTime.now());
    }

    public SysomosHeartbeatStream(SysomosProvider provider, String heartbeatId, DateTime beforeTime, DateTime afterTime) {
        this(provider, heartbeatId, OperatingMode.DATE);
        this.beforeTime = beforeTime;
        this.afterTime = afterTime;
    }

    public SysomosHeartbeatStream(SysomosProvider provider, String heartbeatId, String documentId) {
        this(provider, heartbeatId, OperatingMode.DOC_MATCH);
        this.lastID = documentId;
    }

    public SysomosHeartbeatStream(SysomosProvider provider, String heartbeatId, OperatingMode mode) {
        this.provider = provider;
        this.heartbeatId = heartbeatId;

        this.client = provider.getClient();
        this.maxApiBatch = provider.getMaxApiBatch();
        this.minLatency = provider.getMinLatency();
        this.mode = mode;
    }

    @Override
    public void run() {
        try {
            executeRun();
        } catch (Exception e) {
            LOGGER.error("Error executing heartbeat stream", e);
        } finally {
            shutdown();
        }
    }

    protected void executeRun() {
        QueryResult result;
        String mostCurrentId = null;
        lastRunTime = DateTime.now();
        //Iff we are trying to get to a specific document ID, continue to query after minimum delay
        do {
            LOGGER.debug("Querying API to match last ID of {} or time range of {} - {}", lastID, afterTime, beforeTime);
            result = queryAPI();
            //Ensure that we are only assigning lastID to the latest ID, even if there is backfill query.
            //Since offset is calcuated at the end of the run, if we detect the need to backfill, it will increment to 1
            if(offsetCount == 1) {
                mostCurrentId = result.getCurrentId();
            }
            updateOffset(result);
        } while (offsetCount > 0);

        updateState(result, mostCurrentId);
        LOGGER.debug("Completed current execution with a final docID of {} or time of {}", lastID, afterTime);
    }

    protected void updateState(QueryResult result, String mostCurrentId) {
        if(OperatingMode.DOC_MATCH.equals(mode)) {
            //Set the last ID so that the next time we are executed we will continue to query only so long as we haven't
            //found the specific ID
            lastID = mostCurrentId == null ? result.getCurrentId() : mostCurrentId;
        } else {
            afterTime = lastRunTime;
        }

        if(SysomosProvider.Mode.BACKFILL_AND_TERMINATE.equals(provider.getMode())) {
            shutdown();
            LOGGER.info("Completed backfill to {} for heartbeat {}", OperatingMode.DOC_MATCH.equals(mode) ? lastID : afterTime, heartbeatId);
        }
    }

    protected void updateOffset(QueryResult result) {
        if(OperatingMode.DOC_MATCH.equals(mode)) {
            //Reset the offset iff we have found a match or this is the first execution
            offsetCount = lastID == null || result.isMatchedLastId() ? 0 : offsetCount + 1;
        } else {
            offsetCount = result.getResponseSize() == 0 ? 0 : offsetCount + 1;
        }
        if(offsetCount > 0) {
            sleep();
        }
    }

    protected void sleep() {
        try {
            Thread.sleep(this.minLatency);
        } catch (InterruptedException e) {
            LOGGER.warn("Thread interrupted while sleeping minimum delay", e);
            shutdown();
        }
    }

    protected QueryResult queryAPI() {
        BeatApi.BeatResponse response = executeAPIRequest();

        String currentId = null;
        boolean matched = false;
        int responseSize = 0;
        if(response != null) {
            for (BeatApi.BeatResponse.Beat beat : response.getBeat()) {
                String docId = beat.getDocid();
                //We get documents in descending time order.  This will set the id to the latest document
                if (currentId == null) {
                    currentId = docId;
                }
                //We only want to process documents that we know we have not seen before
                if (lastID != null && lastID.equals(docId)) {
                    matched = true;
                    break;
                }
                StreamsDatum item = new StreamsDatum(beat, docId);
                item.getMetadata().put("heartbeat", this.heartbeatId);
                this.provider.enqueueItem(item);
            }
            responseSize = response.getCount();
        }
        return new QueryResult(matched, currentId, responseSize);
    }

    protected BeatApi.BeatResponse executeAPIRequest() {
        BeatApi.BeatResponse response = null;
        try {
            if(enabled) {
                RequestBuilder requestBuilder = this.client.createRequestBuilder()
                        .setHeartBeatId(heartbeatId)
                        .setOffset(offsetCount * maxApiBatch)
                        .setReturnSetSize(maxApiBatch);
                if(beforeTime != null) {
                    requestBuilder.setAddedBeforeDate(beforeTime);
                }
                if(afterTime != null) {
                    requestBuilder.setAddedAfterDate(afterTime);
                }
                response = requestBuilder.execute();

                LOGGER.debug("Received {} results from API query", response.getCount());
            }
        } catch (Exception e) {
            LOGGER.warn("Error querying Sysomos API", e);
        }
        return response;
    }

    protected void shutdown() {
        provider.signalComplete(heartbeatId);
        enabled = false;
    }

    protected class QueryResult {
        private boolean matchedLastId;
        private String currentId;
        private int responseSize;


        public QueryResult(boolean matchedLastId, String currentId, int responseSize) {
            this.matchedLastId = matchedLastId;
            this.currentId = currentId;
            this.responseSize = responseSize;
        }

        public boolean isMatchedLastId() {
            return matchedLastId;
        }

        public void setMatchedLastId(boolean matchedLastId) {
            this.matchedLastId = matchedLastId;
        }

        public String getCurrentId() {
            return currentId;
        }

        public void setCurrentId(String currentId) {
            this.currentId = currentId;
        }

        public int getResponseSize() {
            return responseSize;
        }

        public void setResponseSize(int responseSize) {
            this.responseSize = responseSize;
        }
    }
}
