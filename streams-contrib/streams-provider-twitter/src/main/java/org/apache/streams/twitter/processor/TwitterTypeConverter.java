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

package org.apache.streams.twitter.processor;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.core.StreamsProcessor;
import org.apache.streams.exceptions.ActivitySerializerException;
import org.apache.streams.pojo.json.Activity;
import org.apache.streams.twitter.pojo.Delete;
import org.apache.streams.twitter.pojo.Retweet;
import org.apache.streams.twitter.pojo.Tweet;
import org.apache.streams.twitter.provider.TwitterEventClassifier;
import org.apache.streams.twitter.serializer.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Queue;

/**
 * This class performs conversion of a twitter event to a specified outClass
 *
 * Deprecated: use TypeConverterProcessor and ActivityConverterProcessor instead
 */
@Deprecated
public class TwitterTypeConverter implements StreamsProcessor {

    public final static String STREAMS_ID = "TwitterTypeConverter";

    private final static Logger LOGGER = LoggerFactory.getLogger(TwitterTypeConverter.class);

    private ObjectMapper mapper;

    private Queue<StreamsDatum> inQueue;
    private Queue<StreamsDatum> outQueue;

    private Class inClass;
    private Class outClass;

    private TwitterJsonActivitySerializer twitterJsonActivitySerializer;

    private int count = 0;

    public final static String TERMINATE = new String("TERMINATE");

    public TwitterTypeConverter(Class inClass, Class outClass) {
        this.inClass = inClass;
        this.outClass = outClass;
    }

    public Queue<StreamsDatum> getProcessorOutputQueue() {
        return outQueue;
    }

    public void setProcessorInputQueue(Queue<StreamsDatum> inputQueue) {
        inQueue = inputQueue;
    }

    public Object convert(ObjectNode event, Class inClass, Class outClass) throws ActivitySerializerException, JsonProcessingException {

        Object result = null;

        if( outClass.equals( Activity.class )) {
            LOGGER.debug("ACTIVITY");
            result = twitterJsonActivitySerializer.deserialize(
                    mapper.writeValueAsString(event));
        } else if( outClass.equals( Tweet.class )) {
            if ( inClass.equals( Tweet.class )) {
                LOGGER.debug("TWEET");
                result = mapper.convertValue(event, Tweet.class);
            }
        } else if( outClass.equals( Retweet.class )) {
            if ( inClass.equals( Retweet.class )) {
                LOGGER.debug("RETWEET");
                result = mapper.convertValue(event, Retweet.class);
            }
        } else if( outClass.equals( Delete.class )) {
            if ( inClass.equals( Delete.class )) {
                LOGGER.debug("DELETE");
                result = mapper.convertValue(event, Delete.class);
            }
        } else if( outClass.equals( ObjectNode.class )) {
            LOGGER.debug("OBJECTNODE");
            result = mapper.convertValue(event, ObjectNode.class);
        }

        // no supported conversion were applied
        if( result != null ) {
            count ++;
            return result;
        }

        LOGGER.debug("CONVERT FAILED");

        return null;

    }

    public boolean validate(Object document, Class klass) {

        // TODO
        return true;
    }

    public boolean isValidJSON(final String json) {
        boolean valid = false;
        try {
            final JsonParser parser = new ObjectMapper().getJsonFactory()
                    .createJsonParser(json);
            while (parser.nextToken() != null) {
            }
            valid = true;
        } catch (JsonParseException jpe) {
            LOGGER.warn("validate: {}", jpe);
        } catch (IOException ioe) {
            LOGGER.warn("validate: {}", ioe);
        }

        return valid;
    }

    @Override
    public List<StreamsDatum> process(StreamsDatum entry) {

        StreamsDatum result = null;

        try {

            Object item = entry.getDocument();
            ObjectNode node;

            LOGGER.debug("{} processing {}", STREAMS_ID, item.getClass());

            if( item instanceof String ) {

                // if the target is string, just pass-through
                if( String.class.equals(outClass)) {
                    result = entry;
                }
                else {
                    // first check for valid json
                    node = (ObjectNode)mapper.readTree((String)item);

                    // since data is coming from outside provider, we don't know what type the events are
                    Class inClass = TwitterEventClassifier.detectClass((String) item);

                    Object out = convert(node, inClass, outClass);

                    if( out != null && validate(out, outClass))
                        result = new StreamsDatum(out);
                }

            } else if( item instanceof ObjectNode ) {

                // first check for valid json
                node = (ObjectNode)mapper.valueToTree(item);

                // since data is coming from outside provider, we don't know what type the events are
                Class inClass = TwitterEventClassifier.detectClass(mapper.writeValueAsString(item));

                Object out = convert(node, inClass, outClass);

                if( out != null && validate(out, outClass))
                    result = new StreamsDatum(out);

            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        if( result != null )
            return Lists.newArrayList(result);
        else
            return Lists.newArrayList();
    }

    @Override
    public void prepare(Object o) {
        mapper = new StreamsTwitterMapper();
        twitterJsonActivitySerializer = new TwitterJsonActivitySerializer();
    }

    @Override
    public void cleanUp() {

    }

}
