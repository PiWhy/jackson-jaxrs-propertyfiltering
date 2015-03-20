package com.hubspot.jackson.jaxrs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.Map.Entry;

public class PropertyFilter {
    private final NestedPropertyFilter filter;

    public PropertyFilter(Collection<String> properties, Collection<String> expandedProperties) {

        filter = new NestedPropertyFilter(expandedProperties);

        for (String property : properties) {
            if (!property.isEmpty()) {
                filter.addProperty(property);
            }
        }
    }

    public boolean hasFilters() {
        return filter.hasFilters();
    }

    public void filter(JsonNode node) {
        filter.filter(node);
    }

    private static class NestedPropertyFilter {
        private final Set<String> includedProperties = new HashSet<String>();
        private final Set<String> excludedProperties = new HashSet<String>();
        private final Map<String, NestedPropertyFilter> nestedProperties = new HashMap<String, NestedPropertyFilter>();
        private final Set<String> expandedProperties = new HashSet<String>();

        public NestedPropertyFilter(Collection<String> expandedProperties) {
            for(String expandedProperty : expandedProperties)
                this.expandedProperties.add(expandedProperty);
        }

        public void addProperty(String property) {
            boolean excluded = property.startsWith("!");
            if (excluded) {
                property = property.substring(1);
            }

            if (property.contains(".")) {
                String prefix = property.substring(0, property.indexOf('.'));
                String suffix = property.substring(property.indexOf('.') + 1);

                NestedPropertyFilter nestedFilter = nestedProperties.get(prefix);
                if (nestedFilter == null) {
                    nestedFilter = new NestedPropertyFilter(new ArrayList<String>());
                    nestedProperties.put(prefix, nestedFilter);
                }

                if (excluded) {
                    nestedFilter.addProperty("!" + suffix);
                } else {
                    nestedFilter.addProperty(suffix);
                    includedProperties.add(prefix);
                }
            } else if (excluded) {
                excludedProperties.add(property);
            } else {
                includedProperties.add(property);
            }
        }

        public boolean hasFilters() {
            return !(includedProperties.isEmpty() && excludedProperties.isEmpty() && nestedProperties.isEmpty());
        }

        public void filter(JsonNode node) {
            if (node.isObject()) {
                filter((ObjectNode) node);
            } else if (node.isArray()) {
                filter((ArrayNode) node);
            }
        }

        private void filter(ArrayNode array) {
            for (JsonNode node : array) {
                filter(node);
            }
        }

        private void filter(ObjectNode object) {
            if (!includedProperties.isEmpty()) {
                object.retain(includedProperties);
            }

            object.remove(excludedProperties);

            for(String name : expandedProperties) {
                JsonNode node = object.get(name);

                if(node != null)
                    nestedProperties.remove(name);
            }

            for (Entry<String, NestedPropertyFilter> entry : nestedProperties.entrySet()) {
                JsonNode node = object.get(entry.getKey());

                if (node != null) {
                    entry.getValue().filter(node);
                }
            }
        }
    }
}
