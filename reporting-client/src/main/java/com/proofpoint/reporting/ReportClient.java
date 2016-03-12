/*
 * Copyright 2013 Proofpoint, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.proofpoint.reporting;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Table;
import com.google.common.collect.Table.Cell;
import com.google.inject.Inject;
import com.proofpoint.http.client.DynamicBodySource;
import com.proofpoint.http.client.HttpClient;
import com.proofpoint.http.client.Request;
import com.proofpoint.http.client.StringResponseHandler.StringResponse;
import com.proofpoint.log.Logger;
import com.proofpoint.node.NodeInfo;

import java.io.OutputStream;
import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.proofpoint.http.client.Request.Builder.preparePost;
import static com.proofpoint.http.client.StringResponseHandler.createStringResponseHandler;

class ReportClient
{
    private static final Logger logger = Logger.get(ReportClient.class);
    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final URI UPLOAD_URI = URI.create("api/v1/datapoints");
    private final Map<String, String> instanceTags;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    @Inject
    ReportClient(NodeInfo nodeInfo, @ForReportClient HttpClient httpClient, ReportClientConfig reportClientConfig, ObjectMapper objectMapper)
    {
        this.objectMapper = objectMapper;
        checkNotNull(nodeInfo, "nodeInfo is null");
        checkNotNull(reportClientConfig, "reportClientConfig is null");

        Builder<String, String> builder = ImmutableMap.builder();
        builder.put("application", nodeInfo.getApplication());
        builder.put("host", nodeInfo.getInternalHostname());
        builder.put("environment", nodeInfo.getEnvironment());
        builder.put("pool", nodeInfo.getPool());
        builder.putAll(reportClientConfig.getTags());
        this.instanceTags = builder.build();

        this.httpClient = checkNotNull(httpClient, "httpClient is null");
        enabled = reportClientConfig.isEnabled();
    }

    public void report(long systemTimeMillis, Table<String, Map<String, String>, Object> collectedData)
    {
        if (!enabled) {
            return;
        }

        Request request = preparePost()
                .setUri(UPLOAD_URI)
                .setHeader("Content-Type", "application/gzip")
                .setBodySource(new CompressBodySource(systemTimeMillis, collectedData))
                .build();
        try {
            StringResponse response = httpClient.execute(request, createStringResponseHandler());
            if (response.getStatusCode() != 204) {
                logger.warn("Failed to report stats: %s %s %s", response.getStatusCode(), response.getStatusMessage(), response.getBody());
            }
        }
        catch (RuntimeException e) {
            logger.warn(e, "Exception when trying to report stats");
        }
    }

    private static class DataPoint
    {
        private static final Pattern NOT_ACCEPTED_CHARACTER_PATTERN = Pattern.compile("[^-A-Za-z0-9./_]");
        @JsonProperty
        private final String name;
        @JsonProperty
        private final long timestamp;
        @JsonProperty
        private final String type;
        @JsonProperty
        private final Object value;
        @JsonProperty
        private final Map<String, String> tags;

        DataPoint(long systemTimeMillis, Cell<String, Map<String, String>, Object> cell, Map<String, String> instanceTags)
        {
            name = NOT_ACCEPTED_CHARACTER_PATTERN.matcher(cell.getRowKey()).replaceAll("_");

            timestamp = systemTimeMillis;
            value = cell.getValue();
            if (value instanceof Number) {
                type = null;
            } else {
                type = "string";
            }
            Builder<String, String> builder = ImmutableMap.<String, String>builder()
                    .putAll(instanceTags);
            for (Entry<String, String> entry : cell.getColumnKey().entrySet()) {
                builder.put(entry.getKey(), NOT_ACCEPTED_CHARACTER_PATTERN.matcher(entry.getValue()).replaceAll("_"));
            }
            tags = builder.build();
        }
    }

    private class CompressBodySource implements DynamicBodySource
    {
        private final long systemTimeMillis;
        private final Table<String, Map<String, String>, Object> collectedData;

        CompressBodySource(long systemTimeMillis, Table<String, Map<String, String>, Object> collectedData)
        {
            this.systemTimeMillis = systemTimeMillis;
            this.collectedData = collectedData;
        }

        @Override
        public Writer start(final OutputStream out)
                throws Exception
        {
            final GZIPOutputStream gzipOutputStream = new GZIPOutputStream(out);
            final JsonGenerator generator = JSON_FACTORY.createGenerator(gzipOutputStream, JsonEncoding.UTF8)
                .setCodec(objectMapper);
            final Iterator<Cell<String, Map<String, String>, Object>> iterator = collectedData.cellSet().iterator();

            generator.writeStartArray();

            return () -> {
                if (iterator.hasNext()) {
                    generator.writeObject(new DataPoint(systemTimeMillis, iterator.next(), instanceTags));
                }
                else {
                    generator.writeEndArray();
                    generator.flush();
                    gzipOutputStream.finish();
                    out.close();
                }
            };
        }
    }
}
