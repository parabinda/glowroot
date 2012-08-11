/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core.trace;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.Immutable;

import org.informantproject.api.Message;
import org.informantproject.api.Supplier;
import org.informantproject.api.Suppliers;
import org.informantproject.core.stack.MergedStackTree;
import org.informantproject.core.util.Clock;
import org.informantproject.core.util.PartiallyThreadSafe;

import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Contains all data that has been captured for a given trace (e.g. servlet request).
 * 
 * This class needs to be thread safe, only one thread updates it, but multiple threads can read it
 * at the same time as it is being updated.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@PartiallyThreadSafe("pushSpan(), addSpan(), popSpan(), startTraceMetric(),"
        + "  resetThreadLocalMetrics() can only be called from constructing thread")
public class Trace {

    // a unique identifier
    private final TraceUniqueId id;

    // timing data is tracked in nano seconds which cannot be converted into dates
    // (see javadoc for System.nanoTime())
    // so the start time is also tracked in a date object here
    private final Date startDate;

    private final AtomicBoolean stuck = new AtomicBoolean();

    private volatile boolean error;

    private volatile Supplier<String> usernameSupplier = Suppliers.ofInstance(null);

    // attribute name ordering is maintained for consistent display (assumption is order of entry is
    // order of importance)
    @GuardedBy("attributes")
    private final List<TraceAttribute> attributes = new ArrayList<TraceAttribute>();

    // this is mostly updated and rarely read, so it seems like synchronized ArrayList is the best
    // collection
    private final List<TraceMetric> traceMetrics = Collections
            .synchronizedList(new ArrayList<TraceMetric>());

    // this doesn't need to be thread safe as it is only accessed by the trace thread
    private final List<MetricImpl> metrics = Lists.newArrayList();

    // root span for this trace
    private final RootSpan rootSpan;

    // stack trace data constructed from sampled stack traces
    // this is lazy instantiated since most traces won't exceed the threshold for stack sampling
    // and early initialization would use up memory unnecessarily
    private volatile MergedStackTree mergedStackTree = new MergedStackTree();

    // the thread is needed so that stack traces can be taken from a different thread
    // a weak reference is used just to be safe and make sure it can't accidentally prevent a thread
    // from being garbage collected
    private final WeakReference<Thread> threadHolder = new WeakReference<Thread>(
            Thread.currentThread());

    // these are stored in the trace so that they can be cancelled
    @Nullable
    private volatile ScheduledFuture<?> captureStackTraceScheduledFuture;
    @Nullable
    private volatile ScheduledFuture<?> stuckCommandScheduledFuture;

    private final Ticker ticker;

    public Trace(MetricImpl metric, org.informantproject.api.Supplier<Message> messageSupplier,
            Clock clock, Ticker ticker) {

        this.ticker = ticker;
        long startTimeMillis = clock.currentTimeMillis();
        id = new TraceUniqueId(startTimeMillis);
        startDate = new Date(startTimeMillis);
        long startTick = ticker.read();
        TraceMetric traceMetric = metric.startInternal(startTick);
        rootSpan = new RootSpan(messageSupplier, traceMetric, startTick, ticker);
        traceMetrics.add(traceMetric);
        metrics.add(metric);
    }

    public Date getStartDate() {
        return startDate;
    }

    public String getId() {
        return id.get();
    }

    // a couple of properties make sense to expose as part of trace
    public long getStartTick() {
        return rootSpan.getStartTick();
    }

    public long getEndTick() {
        return rootSpan.getEndTick();
    }

    // duration of trace in nanoseconds
    public long getDuration() {
        return rootSpan.getDuration();
    }

    public boolean isCompleted() {
        return rootSpan.isCompleted();
    }

    public boolean isStuck() {
        return stuck.get();
    }

    public Supplier<String> getUsernameSupplier() {
        return usernameSupplier;
    }

    public ImmutableList<TraceAttribute> getAttributes() {
        synchronized (attributes) {
            return ImmutableList.copyOf(attributes);
        }
    }

    public boolean isError() {
        return error;
    }

    public List<TraceMetric> getTraceMetrics() {
        return traceMetrics;
    }

    public Span getRootSpan() {
        return rootSpan.getRootSpan();
    }

    public int getSpanCount() {
        return rootSpan.getSize();
    }

    public Iterator<Span> getSpans() {
        return rootSpan.getSpans().iterator();
    }

    public MergedStackTree getMergedStackTree() {
        return mergedStackTree;
    }

    @Nullable
    public ScheduledFuture<?> getCaptureStackTraceScheduledFuture() {
        return captureStackTraceScheduledFuture;
    }

    @Nullable
    public ScheduledFuture<?> getStuckCommandScheduledFuture() {
        return stuckCommandScheduledFuture;
    }

    // must be called by the trace thread
    public void resetThreadLocalMetrics() {
        for (MetricImpl metric : metrics) {
            metric.resetThreadLocal();
        }
    }

    // returns previous value
    boolean setStuck() {
        return stuck.getAndSet(true);
    }

    public void setUsernameSupplier(Supplier<String> usernameSupplier) {
        this.usernameSupplier = usernameSupplier;
    }

    public void putAttribute(String name, @Nullable String value) {
        synchronized (attributes) {
            for (ListIterator<TraceAttribute> i = attributes.listIterator(); i.hasNext();) {
                if (i.next().getName().equals(name)) {
                    i.set(new TraceAttribute(name, value));
                    return;
                }
            }
            attributes.add(new TraceAttribute(name, value));
        }
    }

    // this method doesn't need to be synchronized
    void setCaptureStackTraceScheduledFuture(ScheduledFuture<?> stackTraceScheduledFuture) {
        this.captureStackTraceScheduledFuture = stackTraceScheduledFuture;
    }

    // this method doesn't need to be synchronized
    void setStuckCommandScheduledFuture(ScheduledFuture<?> stuckCommandScheduledFuture) {
        this.stuckCommandScheduledFuture = stuckCommandScheduledFuture;
    }

    void captureStackTrace() {
        Thread thread = threadHolder.get();
        if (thread != null) {
            ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
            ThreadInfo threadInfo = threadBean.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
            mergedStackTree.addStackTrace(threadInfo);
        }
    }

    public Span pushSpan(MetricImpl metric,
            org.informantproject.api.Supplier<Message> messageSupplier) {

        long startTick = ticker.read();
        TraceMetric traceMetric = metric.startInternal(startTick);
        Span span = rootSpan.pushSpan(startTick, messageSupplier, traceMetric);
        if (traceMetric.isFirstStart()) {
            traceMetrics.add(metric.get());
            traceMetric.firstStartSeen();
            metrics.add(metric);
        }
        return span;
    }

    public Span addSpan(org.informantproject.api.Supplier<Message> messageSupplier, boolean error) {
        Span span = rootSpan.addSpan(ticker.read(), messageSupplier, error);
        if (error) {
            this.error = true;
        }
        return span;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span to pop is
    // passed in just to make sure it is the one on top (and if not, then pop until is is found,
    // preventing any nasty bugs from a missed pop, e.g. a trace never being marked as complete)
    public void popSpan(Span span, long endTick, boolean error) {
        if (error) {
            this.error = true;
        }
        rootSpan.popSpan(span, endTick, error);
        TraceMetric traceMetric = span.getTraceMetric();
        if (traceMetric != null) {
            traceMetric.stop(endTick);
        }
    }

    public TraceMetric startTraceMetric(MetricImpl metric) {
        TraceMetric traceMetric = metric.startInternal();
        if (traceMetric.isFirstStart()) {
            traceMetrics.add(metric.get());
            traceMetric.firstStartSeen();
            metrics.add(metric);
        }
        return traceMetric;
    }

    @Immutable
    public static class TraceAttribute {
        private final String name;
        @Nullable
        private final String value;
        private TraceAttribute(String name, @Nullable String value) {
            this.name = name;
            this.value = value;
        }
        public String getName() {
            return name;
        }
        @Nullable
        public String getValue() {
            return value;
        }
    }
}
