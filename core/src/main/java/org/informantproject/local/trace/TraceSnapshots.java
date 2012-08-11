/**
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.informantproject.local.trace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.informantproject.api.Message;
import org.informantproject.core.stack.MergedStackTreeNode;
import org.informantproject.core.trace.Span;
import org.informantproject.core.trace.Trace;
import org.informantproject.core.trace.Trace.TraceAttribute;
import org.informantproject.core.trace.TraceMetric;
import org.informantproject.core.trace.TraceMetric.Snapshot;
import org.informantproject.core.util.ByteStream;
import org.informantproject.core.util.Static;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.hash.Hashing;
import com.google.common.io.CharStreams;
import com.google.gson.Gson;
import com.google.gson.stream.JsonWriter;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public final class TraceSnapshots {

    private static final Gson gson = new Gson();

    public static TraceSnapshot from(Trace trace, long captureTick) throws IOException {
        return from(trace, captureTick, true);
    }

    public static TraceSnapshot from(Trace trace, long captureTick, boolean includeDetail)
            throws IOException {

        TraceSnapshot.Builder builder = TraceSnapshot.builder();
        builder.id(trace.getId());
        builder.startAt(trace.getStartDate().getTime());
        builder.stuck(trace.isStuck() && !trace.isCompleted());
        builder.error(trace.isError());
        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        long endTick = trace.getEndTick();
        if (endTick != 0 && endTick <= captureTick) {
            builder.duration(trace.getDuration());
            builder.completed(true);
        } else {
            builder.duration(captureTick - trace.getStartTick());
            builder.completed(false);
        }
        Message message = trace.getRootSpan().getMessageSupplier().get();
        builder.description(message.getText());
        builder.username(trace.getUsernameSupplier().get());
        List<TraceAttribute> attributes = trace.getAttributes();
        if (!attributes.isEmpty()) {
            builder.attributes(gson.toJson(attributes));
        }
        builder.metrics(getMetricsJson(trace));
        if (includeDetail) {
            SpansByteStream spansByteStream = new SpansByteStream(trace.getSpans(), captureTick);
            builder.spans(spansByteStream);
            builder.spanStackTraces(spansByteStream.stackTraces.build());
            builder.mergedStackTree(TraceSnapshots.getMergedStackTree(trace));
        }
        return builder.build();
    }

    public static ByteStream toByteStream(TraceSnapshot snapshot, boolean includeDetail)
            throws UnsupportedEncodingException {

        List<ByteStream> byteStreams = Lists.newArrayList();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"");
        sb.append(snapshot.getId());
        sb.append("\",\"start\":");
        sb.append(snapshot.getStartAt());
        sb.append(",\"stuck\":");
        sb.append(snapshot.isStuck());
        sb.append(",\"error\":");
        sb.append(snapshot.isError());
        sb.append(",\"duration\":");
        sb.append(snapshot.getDuration());
        sb.append(",\"completed\":");
        sb.append(snapshot.isCompleted());
        sb.append(",\"description\":\"");
        sb.append(snapshot.getDescription());
        sb.append("\"");
        if (snapshot.getUsername() != null) {
            sb.append(",\"username\":\"");
            sb.append(snapshot.getUsername());
            sb.append("\"");
        }
        // inject raw json into stream
        if (snapshot.getAttributes() != null) {
            sb.append(",\"attributes\":");
            sb.append(snapshot.getAttributes());
        }
        if (snapshot.getMetrics() != null) {
            sb.append(",\"metrics\":");
            sb.append(snapshot.getMetrics());
        }
        if (includeDetail && snapshot.getSpans() != null) {
            // spans could be null if spans text has been rolled out
            sb.append(",\"spans\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(snapshot.getSpans());
        }
        if (includeDetail && snapshot.getMergedStackTree() != null) {
            sb.append(",\"mergedStackTree\":");
            // flush current StringBuilder as its own chunk and reset StringBuffer
            byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
            sb.setLength(0);
            byteStreams.add(snapshot.getMergedStackTree());
        }
        sb.append("}");
        // flush current StringBuilder as its own chunk
        byteStreams.add(ByteStream.of(sb.toString().getBytes(Charsets.UTF_8.name())));
        return ByteStream.of(byteStreams);
    }

    @Nullable
    private static String getMetricsJson(Trace trace) {
        List<TraceMetric> traceMetrics = trace.getTraceMetrics();
        if (traceMetrics.isEmpty()) {
            return null;
        }
        List<Snapshot> items = Lists.newArrayList();
        for (TraceMetric traceMetric : traceMetrics) {
            items.add(traceMetric.getSnapshot());
        }
        Ordering<Snapshot> byTotalOrdering = Ordering.natural().onResultOf(
                new Function<Snapshot, Long>() {
                    public Long apply(Snapshot input) {
                        return input.getTotal();
                    }
                });
        return gson.toJson(byTotalOrdering.reverse().sortedCopy(items));
    }

    @VisibleForTesting
    @Nullable
    static ByteStream getMergedStackTree(Trace trace) {
        MergedStackTreeNode rootNode = trace.getMergedStackTree().getRootNode();
        if (rootNode == null) {
            return null;
        }
        List<Object> toVisit = new ArrayList<Object>();
        toVisit.add(rootNode);
        return new MergedStackTreeByteStream(toVisit);
    }

    private static String getStackTraceJson(StackTraceElement[] stackTraceElements)
            throws IOException {

        StringBuilder sb = new StringBuilder();
        JsonWriter jw = new JsonWriter(CharStreams.asWriter(sb));
        jw.beginArray();
        for (StackTraceElement stackTraceElement : stackTraceElements) {
            jw.value(stackTraceElement.toString());
        }
        jw.endArray();
        jw.close();
        return sb.toString();
    }

    private static class SpansByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private final Iterator<Span> spans;
        private final ImmutableMap.Builder<String, String> stackTraces = ImmutableMap.builder();
        private final long captureTick;
        private final ByteArrayOutputStream baos;
        private final Writer raw;
        private final JsonWriter jw;

        private SpansByteStream(Iterator<Span> spans, long captureTick) throws IOException {
            this.spans = spans;
            this.captureTick = captureTick;
            baos = new ByteArrayOutputStream(2 * TARGET_CHUNK_SIZE);
            raw = new OutputStreamWriter(baos, Charsets.UTF_8);
            jw = new JsonWriter(raw);
            jw.beginArray();
        }

        @Override
        public boolean hasNext() {
            return spans.hasNext();
        }

        @Override
        public byte[] next() throws IOException {
            while (baos.size() < TARGET_CHUNK_SIZE && hasNext()) {
                writeSpan(spans.next());
                jw.flush();
            }
            if (!hasNext()) {
                jw.endArray();
                jw.close();
            }
            byte[] chunk = baos.toByteArray();
            baos.reset();
            return chunk;
        }

        // timings for traces that are still active are normalized to the capture tick in order to
        // *attempt* to present a picture of the trace at that exact tick
        // (without using synchronization to block updates to the trace while it is being read)
        private void writeSpan(Span span) throws IOException {
            if (span.getStartTick() > captureTick) {
                // this span started after the capture tick
                return;
            }
            jw.beginObject();
            jw.name("offset");
            jw.value(span.getOffset());
            jw.name("duration");
            long endTick = span.getEndTick();
            if (endTick != 0 && endTick <= captureTick) {
                jw.value(span.getEndTick() - span.getStartTick());
            } else {
                jw.value(captureTick - span.getStartTick());
                jw.name("active");
                jw.value(true);
            }
            jw.name("index");
            jw.value(span.getIndex());
            jw.name("parentIndex");
            jw.value(span.getParentIndex());
            jw.name("level");
            jw.value(span.getLevel());
            // inject raw json into stream
            Message message = span.getMessageSupplier().get();
            jw.name("description");
            jw.value(message.getText());
            boolean error = span.isError();
            if (error) {
                // no need to clutter up json with this mostly unused attribute
                jw.name("error");
                jw.value(true);
            }
            Map<String, ?> contextMap = message.getContextMap();
            if (contextMap != null) {
                jw.name("contextMap");
                new ContextMapSerializer(jw).write(contextMap);
            }
            StackTraceElement[] stackTraceElements = span.getStackTraceElements();
            if (stackTraceElements != null) {
                String stackTraceJson = getStackTraceJson(stackTraceElements);
                String stackTraceHash = Hashing.sha1().hashString(stackTraceJson, Charsets.UTF_8)
                        .toString();
                stackTraces.put(stackTraceHash, stackTraceJson);
                jw.name("stackTraceHash");
                jw.value(stackTraceHash);
            }
            jw.endObject();
        }
    }

    private static class MergedStackTreeByteStream extends ByteStream {

        private static final int TARGET_CHUNK_SIZE = 8192;

        private static final Pattern metricMarkerMethodPattern = Pattern
                .compile("^.*\\$informant\\$metric\\$(.*)\\$[0-9]+$");

        private final List<Object> toVisit;
        private final ByteArrayOutputStream baos;
        private final JsonWriter jw;
        private final List<String> metricNameStack = Lists.newArrayList();

        private MergedStackTreeByteStream(List<Object> toVisit) {
            this.toVisit = toVisit;
            baos = new ByteArrayOutputStream(2 * TARGET_CHUNK_SIZE);
            jw = new JsonWriter(new OutputStreamWriter(baos, Charsets.UTF_8));
        }

        @Override
        public boolean hasNext() {
            return !toVisit.isEmpty();
        }

        @Override
        public byte[] next() throws IOException {
            while (baos.size() < TARGET_CHUNK_SIZE && hasNext()) {
                writeNext();
                jw.flush();
            }
            if (!hasNext()) {
                jw.close();
            }
            byte[] chunk = baos.toByteArray();
            baos.reset();
            return chunk;
        }

        private void writeNext() throws IOException {
            Object curr = toVisit.remove(toVisit.size() - 1);
            if (curr instanceof MergedStackTreeNode) {
                MergedStackTreeNode currNode = (MergedStackTreeNode) curr;
                jw.beginObject();
                toVisit.add(JsonWriterOp.END_OBJECT);
                if (currNode.isSyntheticRoot()) {
                    jw.name("stackTraceElement").value("<multiple root nodes>");
                } else {
                    jw.name("stackTraceElement").value(currNode.getStackTraceElement().toString());
                    String newMetricName = getMetricName(currNode.getStackTraceElement());
                    if (newMetricName != null && !newMetricName.equals(top(metricNameStack))) {
                        // filter out successive duplicates which are common from weaving groups of
                        // overloaded methods
                        metricNameStack.add(newMetricName);
                        toVisit.add(JsonWriterOp.POP_METRIC_NAME);
                    }
                }
                jw.name("sampleCount").value(currNode.getSampleCount());
                if (currNode.isLeaf()) {
                    jw.name("leafThreadState").value(currNode.getLeafThreadState().name());
                    jw.name("metricNames");
                    jw.beginArray();
                    for (String metricName : metricNameStack) {
                        jw.value(metricName);
                    }
                    jw.endArray();
                }
                List<MergedStackTreeNode> childNodes = Lists.newArrayList(currNode.getChildNodes());
                if (!childNodes.isEmpty()) {
                    jw.name("childNodes").beginArray();
                    toVisit.add(JsonWriterOp.END_ARRAY);
                    toVisit.addAll(Lists.reverse(childNodes));
                }
            } else if (curr == JsonWriterOp.END_ARRAY) {
                jw.endArray();
            } else if (curr == JsonWriterOp.END_OBJECT) {
                jw.endObject();
            } else if (curr == JsonWriterOp.POP_METRIC_NAME) {
                metricNameStack.remove(metricNameStack.size() - 1);
            }
        }

        @Nullable
        private static String top(List<String> stack) {
            if (stack.isEmpty()) {
                return null;
            } else {
                return stack.get(stack.size() - 1);
            }
        }

        @Nullable
        private static String getMetricName(StackTraceElement stackTraceElement) {
            return getMetricNameFromMethodName(stackTraceElement);
        }

        @Nullable
        private static String getMetricNameFromMethodName(StackTraceElement stackTraceElement) {
            Matcher matcher = metricMarkerMethodPattern.matcher(stackTraceElement.getMethodName());
            if (matcher.matches()) {
                return matcher.group(1).replace("$", " ");
            } else {
                return null;
            }
        }

        private static enum JsonWriterOp {
            END_OBJECT, END_ARRAY, POP_METRIC_NAME;
        }
    }

    private TraceSnapshots() {}
}
