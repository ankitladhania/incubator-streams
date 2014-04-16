package org.apache.streams.datasift.provider;

import com.datasift.client.stream.Interaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.streams.core.StreamsDatum;
import org.apache.streams.datasift.Datasift;
import org.apache.streams.datasift.serializer.DatasiftInteractionActivitySerializer;
import org.apache.streams.datasift.serializer.DatasiftJsonActivitySerializer;
import org.apache.streams.datasift.twitter.Twitter;
import org.apache.streams.exceptions.ActivitySerializerException;
import org.apache.streams.pojo.json.Activity;
import org.apache.streams.twitter.pojo.Tweet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.Random;

/**
 * Created by sblackmon on 12/10/13.
 */
public class DatasiftEventProcessor implements Runnable {

    private final static Logger LOGGER = LoggerFactory.getLogger(DatasiftEventProcessor.class);

    private ObjectMapper mapper = new ObjectMapper();

    private Queue<Interaction> inQueue;
    private Queue<StreamsDatum> outQueue;

    private Class inClass;
    private Class outClass;

    private DatasiftJsonActivitySerializer datasiftJsonActivitySerializer = new DatasiftJsonActivitySerializer();

    public final static String TERMINATE = new String("TERMINATE");

    public DatasiftEventProcessor(Queue<Interaction> inQueue, Queue<StreamsDatum> outQueue, Class inClass, Class outClass) {
        this.inQueue = inQueue;
        this.outQueue = outQueue;
        this.inClass = inClass;
        this.outClass = outClass;
    }

    public DatasiftEventProcessor(Queue<Interaction> inQueue, Queue<StreamsDatum> outQueue, Class outClass) {
        this.inQueue = inQueue;
        this.outQueue = outQueue;
        this.outClass = outClass;
    }

    @Override
    public void run() {

        while(true) {
            Object item;
            try {
                item = inQueue.poll();
                if(item instanceof String && item.equals(TERMINATE)) {
                    LOGGER.info("Terminating!");
                    break;
                }

                String json;
                org.apache.streams.datasift.Datasift datasift;
                if(item instanceof String)
                    json = (String)item;
                if( item instanceof Interaction) {
                    datasift = mapper.convertValue(item, Datasift.class);
                    json = mapper.writeValueAsString(datasift);
                } else {
                    throw new ActivitySerializerException("unrecognized type");
                }

                // if the target is string, just pass-through
                if( String.class.equals(outClass)) {
                    outQueue.offer(new StreamsDatum(json));

                }
                else if( Interaction.class.equals(outClass))
                {
                    outQueue.offer(new StreamsDatum(item));
                }
                else if( Tweet.class.equals(outClass))
                {
                    // convert to desired format
                    Twitter twitter = datasift.getTwitter();

                    Tweet tweet = mapper.convertValue(twitter, Tweet.class);

                    if( tweet != null ) {

                        outQueue.offer(new StreamsDatum(tweet));

                    }
                }
                else if( Activity.class.equals(outClass))
                {
                    // convert to desired format
                    Interaction entry = (Interaction) item;
                    if( entry != null ) {
                        Activity out = datasiftJsonActivitySerializer.deserialize(json);

                        if( out != null )
                            outQueue.offer(new StreamsDatum(out));
                    }
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

};
