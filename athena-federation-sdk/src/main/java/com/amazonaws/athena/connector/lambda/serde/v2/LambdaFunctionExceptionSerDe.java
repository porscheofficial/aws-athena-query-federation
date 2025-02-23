/*-
 * #%L
 * Amazon Athena Query Federation SDK
 * %%
 * Copyright (C) 2019 - 2020 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connector.lambda.serde.v2;

import com.amazonaws.athena.connector.lambda.serde.BaseDeserializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Joiner;
import software.amazon.awssdk.services.lambda.model.LambdaException;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Used strictly for deserialization only since we do not own {@link LambdaFunctionException} and never need-to/should serialize it.
 */
public class LambdaFunctionExceptionSerDe
{
    private static final Joiner COMMA_JOINER = Joiner.on(",");

    private static final String ERROR_TYPE_FIELD = "errorType";
    private static final String ERROR_MESSAGE_FIELD = "errorMessage";
    private static final String CAUSE_FIELD = "cause";
    private static final String STACK_TRACE_FIELD = "stackTrace";

    private LambdaFunctionExceptionSerDe() {}

    public static final class Deserializer extends BaseDeserializer<LambdaException>
    {
        public Deserializer()
        {
            super(LambdaException.class);
        }

        @Override
        public LambdaException deserialize(JsonParser jparser, DeserializationContext ctxt)
                throws IOException
        {
            validateObjectStart(jparser.getCurrentToken());
            // readTree consumes object end token so skip validation
            return doDeserialize(jparser, ctxt);
        }

        @Override
        public LambdaException doDeserialize(JsonParser jparser, DeserializationContext ctxt)
                throws IOException
        {
            JsonNode root = jparser.getCodec().readTree(jparser);
            return recursiveParse(root);
        }

        private LambdaException recursiveParse(JsonNode root)
        {
            String errorType = getNullableStringValue(root, ERROR_TYPE_FIELD);
            String errorMessage = getNullableStringValue(root, ERROR_MESSAGE_FIELD);
            LambdaException cause = null;
            JsonNode causeNode = root.get(CAUSE_FIELD);
            if (causeNode != null) {
                cause = recursiveParse(causeNode);
            }
            List<List<String>> stackTraces = new LinkedList<>();
            JsonNode stackTraceNode = root.get(STACK_TRACE_FIELD);
            if (stackTraceNode != null) {
                if (stackTraceNode.isArray()) {
                    Iterator<JsonNode> elements = stackTraceNode.elements();
                    while (elements.hasNext()) {
                        List<String> innerList = new LinkedList<>();
                        JsonNode element = elements.next();
                        if (element.isArray()) {
                            Iterator<JsonNode> innerElements = element.elements();
                            while (innerElements.hasNext()) {
                                innerList.add(innerElements.next().asText());
                            }
                        }
                        else {
                            // emulate DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY
                            innerList.add(element.asText());
                        }
                        stackTraces.add(innerList);
                    }
                }
            }

            return (LambdaException) LambdaException.builder().cause(cause).message(appendStackTrace(errorMessage, stackTraces) + "\nErrorType: " + errorType).build();
        }

        private String getNullableStringValue(JsonNode parent, String field)
        {
            JsonNode child = parent.get(field);
            if (child != null) {
                return child.asText();
            }
            return null;
        }

        private String appendStackTrace(String errorMessage, List<List<String>> stackTraces)
        {
            return errorMessage + ". Stack trace: " + stackTraces;
        }
    }
}
