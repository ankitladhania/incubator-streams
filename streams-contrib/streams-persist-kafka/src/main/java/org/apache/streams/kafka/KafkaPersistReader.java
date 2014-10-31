/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
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

package org.apache.streams.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Queues;
import com.typesafe.config.Config;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.consumer.Whitelist;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;
import org.apache.streams.config.StreamsConfigurator;
import org.apache.streams.core.DatumStatusCounter;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsPersistReader;
import org.apache.streams.core.StreamsResultSet;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.streams.kafka.KafkaConfiguration;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class KafkaPersistReader implements StreamsPersistReader, Serializable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaPersistReader.class);

    protected volatile Queue<StreamsDatum> persistQueue;

    private ObjectMapper mapper = new ObjectMapper();

    private KafkaConfiguration config;

    private ConsumerConfig consumerConfig;
    private ConsumerConnector consumerConnector;

    public List<KafkaStream<String, String>> inStreams;

    private ExecutorService executor = Executors.newSingleThreadExecutor();

    public KafkaPersistReader() {
        this(KafkaConfigurator.detectConfiguration(StreamsConfigurator.config.getConfig("kafka")));
    }

    public KafkaPersistReader(KafkaConfiguration config) {
        this.config = config;
    }

    @Override
    public StreamsResultSet readAll() {
        return readCurrent();
    }

    @Override
    public void startStream() {

        for (final KafkaStream stream : inStreams) {
            executor.submit(new KafkaPersistReaderTask(this, stream));
        }
    }

    @Override
    public StreamsResultSet readCurrent() {

        StreamsResultSet current;

        synchronized( KafkaPersistReader.class ) {
            current = new StreamsResultSet(Queues.newConcurrentLinkedQueue(persistQueue));
            persistQueue.clear();
        }

        return current;
    }

    @Override
    public StreamsResultSet readNew(BigInteger bigInteger) {
        return null;
    }

    @Override
    public StreamsResultSet readRange(DateTime dateTime, DateTime dateTime2) {
        return null;
    }

    @Override
    public boolean isRunning() {
        return !executor.isShutdown() && !executor.isTerminated();
    }

    @Override
    public void prepare(Object configurationObject) {

        Properties props = new Properties();
        props.put("zookeeper.connect", config.getZkconnect());
        props.put("group.id", "streams");
        props.put("zookeeper.session.timeout.ms", "1000");
        props.put("zookeeper.sync.time.ms", "200");
        props.put("auto.commit.interval.ms", "1000");
        props.put("auto.offset.reset", "smallest");

        consumerConfig = new ConsumerConfig(props);

        consumerConnector = Consumer.createJavaConsumerConnector(consumerConfig);

        Whitelist topics = new Whitelist(config.getTopic());
        VerifiableProperties vprops = new VerifiableProperties(props);

        inStreams = consumerConnector.createMessageStreamsByFilter(topics, 1, new StringDecoder(vprops), new StringDecoder(vprops));

        persistQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void cleanUp() {
        consumerConnector.shutdown();
    }
}
