package com.hubspot.jackson.jaxrs;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.codahale.metrics.servlets.MetricsServlet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;

import javax.servlet.ServletContext;
import javax.ws.rs.Produces;
import javax.ws.rs.core.*;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

@Provider
@Produces(MediaType.APPLICATION_JSON)
public class PropertyFilteringMessageBodyWriter implements MessageBodyWriter<Object> {

    @Context
    Application application;

    @Context
    UriInfo uriInfo;

    @Context
    ServletContext servletContext;

    private volatile JacksonJsonProvider delegate;

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJsonType(mediaType) &&
                filteringEnabled(annotations) &&
                getJsonProvider().isWriteable(type, genericType, annotations, mediaType);
    }

    @Override
    public long getSize(Object object, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return -1;
    }

    @Override
    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream os) throws IOException {
        PropertyFiltering annotation = findPropertyFiltering(annotations);

        Collection<String> properties = getProperties(annotation.using());
        Collection<String> finalProperties = new ArrayList<String>();

        String prefix = annotation.prefix();

        if(!prefix.isEmpty()) {
            for(String property : properties)
                finalProperties.add(prefix + "." + property);

        } else {
            finalProperties.addAll(properties);
        }

        finalProperties.addAll(Arrays.asList(annotation.always()));

        PropertyFilter propertyFilter = new PropertyFilter(finalProperties);
        if (!propertyFilter.hasFilters()) {
            write(o, type, genericType, annotations, mediaType, httpHeaders, os);
            return;
        }

        Timer timer = getTimer();
        Timer.Context context = timer.time();

        try {
            JsonNode tree = getJsonProvider().locateMapper(type, mediaType).valueToTree(o);
            propertyFilter.filter(tree);
            write(tree, tree.getClass(), tree.getClass(), annotations, mediaType, httpHeaders, os);
        } finally {
            context.stop();
        }
    }


    private Collection<String> getProperties(String name) {
        List<String> values = uriInfo.getQueryParameters().get(name);

        List<String> properties = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String[] parts = value.split(",");
                for (String part : parts) {
                    part = part.trim();
                    if (!part.isEmpty()) {
                        properties.add(part);
                    }
                }
            }
        }

        return properties;
    }

    private void write(Object o, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                       MultivaluedMap<String, Object> httpHeaders, OutputStream os) throws IOException {
        getJsonProvider().writeTo(o, type, genericType, annotations, mediaType, httpHeaders, os);
    }

    private Timer getTimer() {
        return getMetricRegistry().timer(MetricRegistry.name(PropertyFilteringMessageBodyWriter.class, "filter"));
    }

    private MetricRegistry getMetricRegistry() {
        MetricRegistry registry = (MetricRegistry) servletContext.getAttribute(MetricsServlet.METRICS_REGISTRY);

        if (registry == null) {
            registry = SharedMetricRegistries.getOrCreate("com.hubspot");
        }

        return registry;
    }

    private JacksonJsonProvider getJsonProvider() {
        if (delegate != null) {
            return delegate;
        }

        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }

            for (Object o : application.getSingletons()) {
                if (o instanceof JacksonJsonProvider) {
                    delegate = (JacksonJsonProvider) o;
                    return delegate;
                }
            }

            delegate = new JacksonJsonProvider();
            return delegate;
        }
    }

    private static boolean isJsonType(MediaType mediaType) {
        return MediaType.APPLICATION_JSON_TYPE.getType().equals(mediaType.getType()) &&
                MediaType.APPLICATION_JSON_TYPE.getSubtype().equals(mediaType.getSubtype());
    }

    private static boolean filteringEnabled(Annotation... annotations) {
        return findPropertyFiltering(annotations) != null;
    }

    private static PropertyFiltering findPropertyFiltering(Annotation... annotations) {
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == PropertyFiltering.class) {
                return (PropertyFiltering) annotation;
            }
        }

        return null;
    }
}
