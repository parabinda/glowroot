/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.api;

import io.informant.api.weaving.Pointcut;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.Nullable;

/**
 * This is the primary service exposed to plugins. Plugins acquire a {@code PluginServices} instance
 * from {@link #get(String)}, and they can (and should) cache the {@code PluginServices} instance
 * for the life of the jvm to avoid looking it up every time it is needed (which is often).
 * 
 * Here is a basic example of how to use {@code PluginServices} to create a plugin that captures
 * calls to Spring validators:
 * 
 * <pre>
 * &#064;Aspect
 * public class SpringAspect {
 * 
 *     private static final PluginServices pluginServices =
 *             PluginServices.get(&quot;io.informant.plugins:spring-plugin&quot;);
 * 
 *     &#064;Pointcut(typeName = &quot;org.springframework.validation.Validator&quot;, methodName = &quot;validate&quot;,
 *             methodArgs = { &quot;..&quot; }, metricName = &quot;spring validator&quot;)
 *     public static class ValidatorAdvice {
 *         private static final Metric metric = pluginServices.getMetric(ValidatorAdvice.class);
 *         &#064;IsEnabled
 *         public static boolean isEnabled() {
 *             return pluginServices.isEnabled();
 *         }
 *         &#064;OnBefore
 *         public static Span onBefore(@InjectTarget Object validator) {
 *             return pluginServices.startSpan(MessageSupplier.from(&quot;spring validator: {}&quot;,
 *                     validator.getClass().getName()), metric);
 *         }
 *         &#064;OnAfter
 *         public static void onAfter(@InjectTraveler Span span) {
 *             span.end();
 *         }
 *     }
 * }
 * </pre>
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class PluginServices {

    private static final Logger logger = LoggerFactory.getLogger(PluginServices.class);

    private static final String MAIN_ENTRY_POINT_CLASS_NAME = "io.informant.MainEntryPoint";
    private static final String GET_PLUGIN_SERVICES_METHOD_NAME = "getPluginServices";

    /**
     * Returns the {@code PluginServices} instance for the specified {@code pluginId}.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     * 
     * @param pluginId
     * @return the {@code PluginServices} instance for the specified {@code pluginId}
     */
    public static PluginServices get(String pluginId) {
        return getPluginServices(pluginId);
    }

    protected PluginServices() {}

    /**
     * Returns the {@code Metric} instance for the specified {@code adviceClass}.
     * 
     * {@code adviceClass} must be a {@code Class} with a {@link Pointcut} annotation that has a
     * non-empty {@link Pointcut#metricName()}. This is how the {@code Metric} is named.
     * 
     * The same {@code Metric} is always returned for a given {@code adviceClass}.
     * 
     * This {@code Metric} instance is needed for several of the {@code PluginServices} methods. It
     * is primarily an optimization to pass this {@code Metric} instance instead of a metric name,
     * so that {@code PluginServices} doesn't have to look up its internal metric object on each
     * call.
     * 
     * The return value can (and should) be cached by the plugin for the life of the jvm to avoid
     * looking it up every time it is needed (which is often).
     * 
     * @param adviceClass
     * @return the {@code Metric} instance for the specified {@code adviceClass}
     */
    public abstract MetricName getMetricName(Class<?> adviceClass);

    /**
     * Registers a listener that will receive a callback when the plugin's property values are
     * changed, the plugin is enabled/disabled, or Informant is enabled/disabled.
     * 
     * This allows the useful plugin optimization of caching the results of {@link #isEnabled()},
     * {@link #getStringProperty(String)}, {@link #getBooleanProperty(String)}, and
     * {@link #getDoubleProperty(String)} as {@code volatile} fields, and updating the cached values
     * anytime {@link ConfigListener#onChange()} is called.
     * 
     * @param listener
     */
    public abstract void registerConfigListener(ConfigListener listener);

    /**
     * Returns whether the plugin is enabled. When Informant itself is disabled, this returns
     * {@code false}.
     * 
     * Plugins can be individually disabled on the configuration page.
     * 
     * @return {@code true} if the plugin and Informant are both enabled
     */
    public abstract boolean isEnabled();

    /**
     * Returns the {@code String} plugin property value with the specified {@code name}.
     * {@code null} is never returned. If there is no {@code String} plugin property with the
     * specified {@code name} then the empty string {@code ""} is returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/io.informant.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     * 
     * @param name
     * @return the value of the {@code String} plugin property, or {@code ""} if there is no
     *         {@code String} plugin property with the specified {@code name}
     */
    public abstract String getStringProperty(String name);

    /**
     * Returns the {@code boolean} plugin property value with the specified {@code name}. If there
     * is no {@code boolean} plugin property with the specified {@code name} then {@code false} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/io.informant.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     * 
     * @param name
     * @return the value of the {@code boolean} plugin property, or {@code false} if there is no
     *         {@code boolean} plugin property with the specified {@code name}
     */
    public abstract boolean getBooleanProperty(String name);

    /**
     * Returns the {@code Double} plugin property value with the specified {@code name}. If there is
     * no {@code Double} plugin property with the specified {@code name} then {@code null} is
     * returned.
     * 
     * Plugin properties are scoped per plugin. The are defined in the plugin's
     * META-INF/io.informant.plugin.json file, and can be modified (assuming they are not marked as
     * hidden) on the configuration page under the plugin's configuration section.
     * 
     * @param name
     * @return the value of the {@code Double} plugin property, or {@code null} if there is no
     *         {@code Double} plugin property with the specified {@code name}
     */
    @Nullable
    public abstract Double getDoubleProperty(String name);

    /**
     * If there is no active trace, a new trace is started.
     * 
     * If there is already an active trace, this method acts the same as
     * {@link #startSpan(MessageSupplier, MetricName)}.
     * 
     * @param messageSupplier
     * @param metric
     * @return
     */
    public abstract Span startTrace(MessageSupplier messageSupplier, MetricName metricName);

    /**
     * If there is no active trace, a new trace is started and the trace is marked as a background
     * trace. Traces can be filtered by their background flag on the trace explorer page.
     * 
     * If there is already an active trace, this method acts the same as
     * {@link #startSpan(MessageSupplier, MetricName)} (the background flag is not modified on the
     * existing trace).
     * 
     * @param messageSupplier
     * @param metric
     * @return
     */
    public abstract Span startBackgroundTrace(MessageSupplier messageSupplier,
            MetricName metricName);

    /**
     * Creates and starts a span with the given {@code messageSupplier}. A metric timer for the
     * specified metric is also started.
     * 
     * Since spans can be expensive in great quantities, there is a {@code maxSpans} property on the
     * configuration page to limit the number of spans created for any given trace.
     * 
     * Once a trace has accumulated {@code maxSpans} spans, this method doesn't add new spans to the
     * trace, but instead returns a dummy span. A metric timer for the specified metric is still
     * started, since metrics are very cheap, even in great quantities. The dummy span adhere to the
     * {@link Span} contract and return the specified {@link MessageSupplier} in response to
     * {@link Span#getMessageSupplier()}. Calling {@link Span#end()} on the dummy span ends the
     * metric timer. If {@link Span#endWithError(ErrorMessage)} is called on the dummy span, then an
     * error span will get created and added to the trace (even though the span limit was already
     * hit), with the original {@link MessageSupplier} and the specified {@link ErrorMessage}. Just
     * in case of a runaway trace that generates tons of error messages, a hard cap
     * 
     * ({@code maxSpans * 2}) on number of spans is still applied when adding error spans.
     * 
     * @param messageSupplier
     * @param metric
     * @return
     */
    public abstract Span startSpan(MessageSupplier messageSupplier, MetricName metricName);

    /**
     * Starts a timer for the specified metric. If a timer is already running for the specified
     * metric, it will keep an internal counter of the number of starts, and it will only end the
     * timer after the corresponding number of ends.
     * 
     * @param metric
     * @return the timer for calling stop
     */
    public abstract MetricTimer startMetricTimer(MetricName metricName);

    /**
     * Adds a span with duration zero.
     * 
     * @param messageSupplier
     */
    public abstract void addSpan(MessageSupplier messageSupplier);

    /**
     * Adds an error span with duration zero. It does not set the error attribute on the trace,
     * which must be done with {@link Span#endWithError(ErrorMessage)} on the root span.
     * 
     * This method bypasses the regular {@code maxSpans} check so that errors after {@code maxSpans}
     * will still be included in the trace. Just in case of a runaway trace that generates tons of
     * error messages, a hard cap ({@code maxSpans * 2}) on number of spans is still applied when
     * adding error spans .
     * 
     * @param errorMessage
     */
    public abstract void addErrorSpan(ErrorMessage errorMessage);

    /**
     * Sets the user id attribute on the trace. This attribute is shared across all plugins, and is
     * generally set by the plugin that initiated the trace, but can be set by other plugins if
     * needed.
     * 
     * The user id is used in a few ways:
     * <ul>
     * <li>The user id is displayed when viewing a trace summary on the trace explorer page
     * <li>Traces can be filtered by their user id on the trace explorer page
     * <li>Informant can be configured (using the configuration page) to capture traces for a
     * specific user id using a lower threshold than normal (e.g. threshold=0 to capture all
     * requests for a specific user id)
     * <li>Informant can be configured (using the configuration page) to perform fine-grained
     * profiling on all traces for a specific user id
     * </ul>
     * 
     * If fine-grained profiling is enabled for a specific user id, this is activated (if the
     * {@code userId} matches) at the time that this method is called, so it is best to call this
     * method early in the trace.
     * 
     * @param userId
     */
    public abstract void setUserId(@Nullable String userId);

    /**
     * Adds an attribute on the current trace with the specified {@code name} and {@code value}. A
     * trace's attributes are displayed when viewing a trace summary on the trace explorer page.
     * 
     * Trace attributes are scoped per plugin, so two plugins can use the same attribute name,
     * although the trace explorer page only displays trace attribute by name, so it could be
     * confusing for a user to see the same attribute name twice with different values.
     * 
     * Subsequent calls to this method with the same {@code name} on the same trace (and from the
     * same plugin) will replace the previous {@code value}.
     * 
     * @param name
     *            name of the attribute
     * @param value
     *            value of the attribute
     */
    public abstract void setTraceAttribute(String name, @Nullable String value);

    private static PluginServices getPluginServices(String pluginId) {
        try {
            Class<?> mainEntryPointClass = Class.forName(MAIN_ENTRY_POINT_CLASS_NAME);
            Method getPluginServicesMethod = mainEntryPointClass.getMethod(
                    GET_PLUGIN_SERVICES_METHOD_NAME, String.class);
            PluginServices pluginServices = (PluginServices) getPluginServicesMethod.invoke(null,
                    pluginId);
            if (pluginServices == null) {
                // this really really really shouldn't happen
                logger.error(MAIN_ENTRY_POINT_CLASS_NAME + "." + GET_PLUGIN_SERVICES_METHOD_NAME
                        + "(" + pluginId + ") returned null");
                return new PluginServicesNop();
            }
            return pluginServices;
        } catch (ClassNotFoundException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (SecurityException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (NoSuchMethodException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (IllegalArgumentException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (IllegalAccessException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        } catch (InvocationTargetException e) {
            // this really really really shouldn't happen
            logger.error(e.getMessage(), e);
            return new PluginServicesNop();
        }
    }

    public interface ConfigListener {
        // the new config is not passed to onChange so that the receiver has to get the latest,
        // this avoids race condition worries that two updates may get sent to the receiver in the
        // wrong order
        void onChange();
    }

    private static class PluginServicesNop extends PluginServices {
        @Override
        public MetricName getMetricName(Class<?> adviceClass) {
            return NopMetric.INSTANCE;
        }
        @Override
        public boolean isEnabled() {
            return false;
        }
        @Override
        public String getStringProperty(String name) {
            return "";
        }
        @Override
        public boolean getBooleanProperty(String name) {
            return false;
        }
        @Override
        @Nullable
        public Double getDoubleProperty(String name) {
            return null;
        }
        @Override
        public void registerConfigListener(ConfigListener listener) {}
        @Override
        public Span startTrace(MessageSupplier messageSupplier, MetricName metricName) {
            return new NopSpan(messageSupplier);
        }
        @Override
        public Span startBackgroundTrace(MessageSupplier messageSupplier, MetricName metricName) {
            return new NopSpan(messageSupplier);
        }
        @Override
        public Span startSpan(MessageSupplier messageSupplier, MetricName metricName) {
            return new NopSpan(messageSupplier);
        }
        @Override
        public MetricTimer startMetricTimer(MetricName metricName) {
            return NopMetricTimer.INSTANCE;
        }
        @Override
        public void addSpan(MessageSupplier messageSupplier) {}
        @Override
        public void addErrorSpan(ErrorMessage errorMessage) {}
        @Override
        public void setUserId(@Nullable String userId) {}
        @Override
        public void setTraceAttribute(String name, @Nullable String value) {}

        private static class NopMetric implements MetricName {
            private static final NopMetric INSTANCE = new NopMetric();
        }

        private static class NopSpan implements Span {
            private final MessageSupplier messageSupplier;
            private NopSpan(MessageSupplier messageSupplier) {
                this.messageSupplier = messageSupplier;
            }
            public void end() {}
            public void endWithStackTrace(long threshold, TimeUnit unit) {}
            public void endWithError(ErrorMessage errorMessage) {}
            public MessageSupplier getMessageSupplier() {
                return messageSupplier;
            }
        }

        private static class NopMetricTimer implements MetricTimer {
            private static final NopMetricTimer INSTANCE = new NopMetricTimer();
            public void stop() {}
        }
    }
}
