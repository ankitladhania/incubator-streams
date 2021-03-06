/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/

package org.apache.streams.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.streams.data.DocumentClassifier;
import org.apache.streams.data.util.ActivityUtil;
import org.apache.streams.jackson.StreamsJacksonMapper;
import org.apache.streams.pojo.json.Activity;

import java.io.IOException;
import java.util.List;

/**
 * BaseDocumentClassifier is included by default in all
 * @see {@link org.apache.streams.converter.ActivityConverterProcessor}
 *
 * Ensures generic String and ObjectNode documents can be converted to Activity
 *
 */
public class BaseDocumentClassifier implements DocumentClassifier {

    private ObjectMapper mapper = StreamsJacksonMapper.getInstance();

    @Override
    @SuppressWarnings("unchecked")
    public List<Class> detectClasses(Object document) {
        Preconditions.checkArgument(
                document instanceof String
             || document instanceof ObjectNode);

        Activity activity = null;
        ObjectNode node = null;

        List<Class> classes = Lists.newArrayList();
        // Soon javax.validation will available in jackson
        //   That will make this simpler and more powerful
        if( document instanceof String ) {
            classes.add(String.class);
            try {
                activity = this.mapper.readValue((String)document, Activity.class);
                if(activity != null && ActivityUtil.isValid(activity))
                    classes.add(Activity.class);
            } catch (IOException e1) {
                try {
                    node = this.mapper.readValue((String)document, ObjectNode.class);
                    classes.add(ObjectNode.class);
                } catch (IOException e2) { }
            }
        } else if( document instanceof ObjectNode ){
            classes.add(ObjectNode.class);
            activity = this.mapper.convertValue((ObjectNode)document, Activity.class);
            if(ActivityUtil.isValid(activity))
                classes.add(Activity.class);
        } else {
            classes.add(document.getClass());
        }

        return classes;

    }

}
