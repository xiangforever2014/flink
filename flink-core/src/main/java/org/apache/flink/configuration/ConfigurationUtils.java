/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.configuration;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.TimeUtils;

import javax.annotation.Nonnull;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.apache.flink.configuration.MetricOptions.SYSTEM_RESOURCE_METRICS;
import static org.apache.flink.configuration.MetricOptions.SYSTEM_RESOURCE_METRICS_PROBING_INTERVAL;
import static org.apache.flink.configuration.StructuredOptionsSplitter.escapeWithSingleQuote;
import static org.apache.flink.util.Preconditions.checkArgument;

/** Utility class for {@link Configuration} related helper functions. */
public class ConfigurationUtils {

    private static final String[] EMPTY = new String[0];

    /**
     * @return extracted {@link MetricOptions#SYSTEM_RESOURCE_METRICS_PROBING_INTERVAL} or {@code
     *     Optional.empty()} if {@link MetricOptions#SYSTEM_RESOURCE_METRICS} are disabled.
     */
    public static Optional<Time> getSystemResourceMetricsProbingInterval(
            Configuration configuration) {
        if (!configuration.get(SYSTEM_RESOURCE_METRICS)) {
            return Optional.empty();
        } else {
            return Optional.of(
                    Time.milliseconds(configuration.get(SYSTEM_RESOURCE_METRICS_PROBING_INTERVAL)));
        }
    }

    /**
     * Extracts the task manager directories for temporary files as defined by {@link
     * org.apache.flink.configuration.CoreOptions#TMP_DIRS}.
     *
     * @param configuration configuration object
     * @return array of configured directories (in order)
     */
    @Nonnull
    public static String[] parseTempDirectories(Configuration configuration) {
        return splitPaths(configuration.get(CoreOptions.TMP_DIRS));
    }

    /**
     * Picks a temporary directory randomly from the given configuration.
     *
     * @param configuration to extract the temp directory from
     * @return a randomly picked temporary directory
     */
    @Nonnull
    public static File getRandomTempDirectory(Configuration configuration) {
        final String[] tmpDirectories = parseTempDirectories(configuration);

        Preconditions.checkState(
                tmpDirectories.length > 0,
                String.format(
                        "No temporary directory has been specified for %s",
                        CoreOptions.TMP_DIRS.key()));

        final int randomIndex = ThreadLocalRandom.current().nextInt(tmpDirectories.length);

        return new File(tmpDirectories[randomIndex]);
    }

    /**
     * Extracts the local state directories as defined by {@link
     * CheckpointingOptions#LOCAL_RECOVERY_TASK_MANAGER_STATE_ROOT_DIRS}.
     *
     * @param configuration configuration object
     * @return array of configured directories (in order)
     */
    @Nonnull
    public static String[] parseLocalStateDirectories(Configuration configuration) {
        String configValue =
                configuration.get(
                        CheckpointingOptions.LOCAL_RECOVERY_TASK_MANAGER_STATE_ROOT_DIRS, "");
        return splitPaths(configValue);
    }

    /**
     * Parses a string as a map of strings. The expected format of the map to be parsed` by FLINK
     * parser is:
     *
     * <pre>
     * key1:value1,key2:value2
     * </pre>
     *
     * <p>The expected format of the map to be parsed by standard YAML parser is:
     *
     * <pre>
     * {key1: value1, key2: value2}
     * </pre>
     *
     * <p>Parts of the string can be escaped by wrapping with single or double quotes.
     *
     * @param stringSerializedMap a string to parse
     * @return parsed map
     */
    public static Map<String, String> parseStringToMap(String stringSerializedMap) {
        return convertToProperties(stringSerializedMap, GlobalConfiguration.isStandardYaml());
    }

    public static String parseMapToString(Map<String, String> map) {
        return convertToString(map, GlobalConfiguration.isStandardYaml());
    }

    public static Time getStandaloneClusterStartupPeriodTime(Configuration configuration) {
        final Time timeout;
        long standaloneClusterStartupPeriodTime =
                configuration.get(ResourceManagerOptions.STANDALONE_CLUSTER_STARTUP_PERIOD_TIME);
        if (standaloneClusterStartupPeriodTime >= 0) {
            timeout = Time.milliseconds(standaloneClusterStartupPeriodTime);
        } else {
            timeout = Time.milliseconds(configuration.get(JobManagerOptions.SLOT_REQUEST_TIMEOUT));
        }
        return timeout;
    }

    /**
     * Creates a new {@link Configuration} from the given {@link Properties}.
     *
     * @param properties to convert into a {@link Configuration}
     * @return {@link Configuration} which has been populated by the values of the given {@link
     *     Properties}
     */
    @Nonnull
    public static Configuration createConfiguration(Properties properties) {
        final Configuration configuration = new Configuration();

        final Set<String> propertyNames = properties.stringPropertyNames();

        for (String propertyName : propertyNames) {
            configuration.setString(propertyName, properties.getProperty(propertyName));
        }

        return configuration;
    }

    /**
     * Replaces values whose keys are sensitive according to {@link
     * GlobalConfiguration#isSensitive(String)} with {@link GlobalConfiguration#HIDDEN_CONTENT}.
     *
     * <p>This can be useful when displaying configuration values.
     *
     * @param keyValuePairs for which to hide sensitive values
     * @return A map where all sensitive value are hidden
     */
    @Nonnull
    public static Map<String, String> hideSensitiveValues(Map<String, String> keyValuePairs) {
        final HashMap<String, String> result = new HashMap<>();

        for (Map.Entry<String, String> keyValuePair : keyValuePairs.entrySet()) {
            if (GlobalConfiguration.isSensitive(keyValuePair.getKey())) {
                result.put(keyValuePair.getKey(), GlobalConfiguration.HIDDEN_CONTENT);
            } else {
                result.put(keyValuePair.getKey(), keyValuePair.getValue());
            }
        }

        return result;
    }

    @Nonnull
    public static String[] splitPaths(@Nonnull String separatedPaths) {
        return separatedPaths.length() > 0
                ? separatedPaths.split(",|" + File.pathSeparator)
                : EMPTY;
    }

    /**
     * Creates a dynamic parameter list {@code String} of the passed configuration map.
     *
     * @param config A {@code Map} containing parameter/value entries that shall be used in the
     *     dynamic parameter list.
     * @return The dynamic parameter list {@code String}.
     */
    public static String assembleDynamicConfigsStr(final Map<String, String> config) {
        return config.entrySet().stream()
                .map(e -> String.format("-D %s=%s", e.getKey(), e.getValue()))
                .collect(Collectors.joining(" "));
    }

    @VisibleForTesting
    public static Map<String, String> parseTmResourceDynamicConfigs(String dynamicConfigsStr) {
        Map<String, String> configs = new HashMap<>();
        String[] configStrs = dynamicConfigsStr.split(" ");

        checkArgument(
                configStrs.length % 2 == 0,
                "Dynamic option string contained odd number of arguments: #arguments=%s, (%s)",
                configStrs.length,
                dynamicConfigsStr);
        for (int i = 0; i < configStrs.length; ++i) {
            String configStr = configStrs[i];
            if (i % 2 == 0) {
                checkArgument(configStr.equals("-D"));
            } else {
                String[] configKV = configStr.split("=");
                checkArgument(configKV.length == 2);
                configs.put(configKV[0], configKV[1]);
            }
        }

        checkConfigContains(configs, TaskManagerOptions.CPU_CORES.key());
        checkConfigContains(configs, TaskManagerOptions.FRAMEWORK_HEAP_MEMORY.key());
        checkConfigContains(configs, TaskManagerOptions.FRAMEWORK_OFF_HEAP_MEMORY.key());
        checkConfigContains(configs, TaskManagerOptions.TASK_HEAP_MEMORY.key());
        checkConfigContains(configs, TaskManagerOptions.TASK_OFF_HEAP_MEMORY.key());
        checkConfigContains(configs, TaskManagerOptions.NETWORK_MEMORY_MAX.key());
        checkConfigContains(configs, TaskManagerOptions.NETWORK_MEMORY_MIN.key());
        checkConfigContains(configs, TaskManagerOptions.MANAGED_MEMORY_SIZE.key());
        checkConfigContains(configs, TaskManagerOptions.JVM_METASPACE.key());
        checkConfigContains(configs, TaskManagerOptions.JVM_OVERHEAD_MIN.key());
        checkConfigContains(configs, TaskManagerOptions.JVM_OVERHEAD_MAX.key());
        checkConfigContains(configs, TaskManagerOptions.NUM_TASK_SLOTS.key());

        return configs;
    }

    private static void checkConfigContains(Map<String, String> configs, String key) {
        checkArgument(
                configs.containsKey(key), "Key %s is missing present from dynamic configs.", key);
    }

    @VisibleForTesting
    public static Map<String, String> parseJvmArgString(String jvmParamsStr) {
        final String xmx = "-Xmx";
        final String xms = "-Xms";
        final String maxDirect = "-XX:MaxDirectMemorySize=";
        final String maxMetadata = "-XX:MaxMetaspaceSize=";

        Map<String, String> configs = new HashMap<>();
        for (String paramStr : jvmParamsStr.split(" ")) {
            if (paramStr.startsWith(xmx)) {
                configs.put(xmx, paramStr.substring(xmx.length()));
            } else if (paramStr.startsWith(xms)) {
                configs.put(xms, paramStr.substring(xms.length()));
            } else if (paramStr.startsWith(maxDirect)) {
                configs.put(maxDirect, paramStr.substring(maxDirect.length()));
            } else if (paramStr.startsWith(maxMetadata)) {
                configs.put(maxMetadata, paramStr.substring(maxMetadata.length()));
            }
        }

        checkArgument(configs.containsKey(xmx));
        checkArgument(configs.containsKey(xms));
        checkArgument(configs.containsKey(maxMetadata));

        return configs;
    }

    /**
     * Extract and parse Flink configuration properties with a given name prefix and return the
     * result as a Map.
     */
    public static Map<String, String> getPrefixedKeyValuePairs(
            String prefix, Configuration configuration) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : configuration.toMap().entrySet()) {
            if (entry.getKey().startsWith(prefix) && entry.getKey().length() > prefix.length()) {
                String key = entry.getKey().substring(prefix.length());
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    // --------------------------------------------------------------------------------------------
    //  Type conversion
    // --------------------------------------------------------------------------------------------

    /**
     * Tries to convert the raw value into the provided type.
     *
     * @param rawValue rawValue to convert into the provided type clazz
     * @param clazz clazz specifying the target type
     * @param <T> type of the result
     * @return the converted value if rawValue is of type clazz
     * @throws IllegalArgumentException if the rawValue cannot be converted in the specified target
     *     type clazz
     */
    @SuppressWarnings("unchecked")
    public static <T> T convertValue(Object rawValue, Class<?> clazz) {
        return convertValue(rawValue, clazz, GlobalConfiguration.isStandardYaml());
    }

    @SuppressWarnings("unchecked")
    public static <T> T convertValue(Object rawValue, Class<?> clazz, boolean standardYaml) {
        if (Integer.class.equals(clazz)) {
            return (T) convertToInt(rawValue);
        } else if (Long.class.equals(clazz)) {
            return (T) convertToLong(rawValue);
        } else if (Boolean.class.equals(clazz)) {
            return (T) convertToBoolean(rawValue);
        } else if (Float.class.equals(clazz)) {
            return (T) convertToFloat(rawValue);
        } else if (Double.class.equals(clazz)) {
            return (T) convertToDouble(rawValue);
        } else if (String.class.equals(clazz)) {
            return (T) convertToString(rawValue, standardYaml);
        } else if (clazz.isEnum()) {
            return (T) convertToEnum(rawValue, (Class<? extends Enum<?>>) clazz);
        } else if (clazz == Duration.class) {
            return (T) convertToDuration(rawValue);
        } else if (clazz == MemorySize.class) {
            return (T) convertToMemorySize(rawValue);
        } else if (clazz == Map.class) {
            return (T) convertToProperties(rawValue, standardYaml);
        }

        throw new IllegalArgumentException("Unsupported type: " + clazz);
    }

    @SuppressWarnings("unchecked")
    static <T> T convertToList(Object rawValue, Class<?> atomicClass, boolean standardYaml) {
        if (rawValue instanceof List) {
            return (T) rawValue;
        } else if (standardYaml) {
            try {
                List<Object> data =
                        YamlParserUtils.convertToObject(rawValue.toString(), List.class);
                // The Yaml parser conversion results in data of type List<Map<Object, Object>>,
                // such as List<Map<Object, Boolean>>. However, ConfigOption currently requires that
                // the data for Map type be strictly of the type Map<String, String>. Therefore, we
                // convert each map in the list to Map<String, String>.
                if (atomicClass == Map.class) {
                    return (T)
                            data.stream()
                                    .map(map -> convertToStringMap((Map<Object, Object>) map, true))
                                    .collect(Collectors.toList());
                }

                return (T)
                        data.stream()
                                .map(s -> convertValue(s, atomicClass, true))
                                .collect(Collectors.toList());
            } catch (Exception e) {
                // Fallback to legacy pattern
                return convertToListWithLegacyProperties(rawValue, atomicClass);
            }
        } else {
            return convertToListWithLegacyProperties(rawValue, atomicClass);
        }
    }

    @Nonnull
    private static <T> T convertToListWithLegacyProperties(Object rawValue, Class<?> atomicClass) {
        return (T)
                StructuredOptionsSplitter.splitEscaped(rawValue.toString(), ';').stream()
                        .map(s -> convertValue(s, atomicClass, false))
                        .collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> convertToProperties(Object o, boolean standardYaml) {
        if (o instanceof Map) {
            return (Map<String, String>) o;
        } else if (standardYaml) {
            try {
                Map<Object, Object> map = YamlParserUtils.convertToObject(o.toString(), Map.class);
                return convertToStringMap(map, true);
            } catch (Exception e) {
                // Fallback to legacy pattern
                return convertToPropertiesWithLegacyPattern(o);
            }
        } else {
            return convertToPropertiesWithLegacyPattern(o);
        }
    }

    @Nonnull
    private static Map<String, String> convertToPropertiesWithLegacyPattern(Object o) {
        List<String> listOfRawProperties =
                StructuredOptionsSplitter.splitEscaped(o.toString(), ',');
        return listOfRawProperties.stream()
                .map(s -> StructuredOptionsSplitter.splitEscaped(s, ':'))
                .peek(
                        pair -> {
                            if (pair.size() != 2) {
                                throw new IllegalArgumentException(
                                        "Map item is not a key-value pair (missing ':'?)");
                            }
                        })
                .collect(Collectors.toMap(a -> a.get(0), a -> a.get(1)));
    }

    private static Map<String, String> convertToStringMap(
            Map<Object, Object> map, boolean standardYaml) {
        return map.entrySet().stream()
                .collect(
                        Collectors.toMap(
                                entry -> convertToString(entry.getKey(), standardYaml),
                                entry -> convertToString(entry.getValue(), standardYaml)));
    }

    @SuppressWarnings("unchecked")
    public static <E extends Enum<?>> E convertToEnum(Object o, Class<E> clazz) {
        if (o.getClass().equals(clazz)) {
            return (E) o;
        }

        return Arrays.stream(clazz.getEnumConstants())
                .filter(
                        e ->
                                e.toString()
                                        .toUpperCase(Locale.ROOT)
                                        .equals(o.toString().toUpperCase(Locale.ROOT)))
                .findAny()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        String.format(
                                                "Could not parse value for enum %s. Expected one of: [%s]",
                                                clazz, Arrays.toString(clazz.getEnumConstants()))));
    }

    static Duration convertToDuration(Object o) {
        if (o.getClass() == Duration.class) {
            return (Duration) o;
        }

        return TimeUtils.parseDuration(o.toString());
    }

    static MemorySize convertToMemorySize(Object o) {
        if (o.getClass() == MemorySize.class) {
            return (MemorySize) o;
        }

        return MemorySize.parse(o.toString());
    }

    static String convertToString(Object o, boolean standardYaml) {
        if (standardYaml) {
            if (o.getClass() == String.class) {
                return (String) o;
            } else {
                return YamlParserUtils.toYAMLString(o);
            }
        }

        if (o.getClass() == String.class) {
            return (String) o;
        } else if (o.getClass() == Duration.class) {
            Duration duration = (Duration) o;
            return TimeUtils.formatWithHighestUnit(duration);
        } else if (o instanceof List) {
            return ((List<?>) o)
                    .stream()
                            .map(e -> escapeWithSingleQuote(convertToString(e, false), ";"))
                            .collect(Collectors.joining(";"));
        } else if (o instanceof Map) {
            return ((Map<?, ?>) o)
                    .entrySet().stream()
                            .map(
                                    e -> {
                                        String escapedKey =
                                                escapeWithSingleQuote(e.getKey().toString(), ":");
                                        String escapedValue =
                                                escapeWithSingleQuote(e.getValue().toString(), ":");

                                        return escapeWithSingleQuote(
                                                escapedKey + ":" + escapedValue, ",");
                                    })
                            .collect(Collectors.joining(","));
        }

        return o.toString();
    }

    static Integer convertToInt(Object o) {
        if (o.getClass() == Integer.class) {
            return (Integer) o;
        } else if (o.getClass() == Long.class) {
            long value = (Long) o;
            if (value <= Integer.MAX_VALUE && value >= Integer.MIN_VALUE) {
                return (int) value;
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Configuration value %s overflows/underflows the integer type.",
                                value));
            }
        }

        return Integer.parseInt(o.toString());
    }

    static Long convertToLong(Object o) {
        if (o.getClass() == Long.class) {
            return (Long) o;
        } else if (o.getClass() == Integer.class) {
            return ((Integer) o).longValue();
        }

        return Long.parseLong(o.toString());
    }

    static Boolean convertToBoolean(Object o) {
        if (o.getClass() == Boolean.class) {
            return (Boolean) o;
        }

        switch (o.toString().toUpperCase()) {
            case "TRUE":
                return true;
            case "FALSE":
                return false;
            default:
                throw new IllegalArgumentException(
                        String.format(
                                "Unrecognized option for boolean: %s. Expected either true or false(case insensitive)",
                                o));
        }
    }

    static Float convertToFloat(Object o) {
        if (o.getClass() == Float.class) {
            return (Float) o;
        } else if (o.getClass() == Double.class) {
            double value = ((Double) o);
            if (value == 0.0
                    || (value >= Float.MIN_VALUE && value <= Float.MAX_VALUE)
                    || (value >= -Float.MAX_VALUE && value <= -Float.MIN_VALUE)) {
                return (float) value;
            } else {
                throw new IllegalArgumentException(
                        String.format(
                                "Configuration value %s overflows/underflows the float type.",
                                value));
            }
        }

        return Float.parseFloat(o.toString());
    }

    static Double convertToDouble(Object o) {
        if (o.getClass() == Double.class) {
            return (Double) o;
        } else if (o.getClass() == Float.class) {
            return ((Float) o).doubleValue();
        }

        return Double.parseDouble(o.toString());
    }

    // --------------------------------------------------------------------------------------------
    //  Prefix map handling
    // --------------------------------------------------------------------------------------------

    /**
     * Maps can be represented in two ways.
     *
     * <p>With constant key space:
     *
     * <pre>
     *     avro-confluent.properties = schema: 1, other-prop: 2
     * </pre>
     *
     * <p>Or with variable key space (i.e. prefix notation):
     *
     * <pre>
     *     avro-confluent.properties.schema = 1
     *     avro-confluent.properties.other-prop = 2
     * </pre>
     */
    public static boolean canBePrefixMap(ConfigOption<?> configOption) {
        return configOption.getClazz() == Map.class && !configOption.isList();
    }

    /** Filter condition for prefix map keys. */
    public static boolean filterPrefixMapKey(String key, String candidate) {
        final String prefixKey = key + ".";
        return candidate.startsWith(prefixKey);
    }

    static Map<String, String> convertToPropertiesPrefixed(
            Map<String, Object> confData, String key, boolean standardYaml) {
        final String prefixKey = key + ".";
        return confData.keySet().stream()
                .filter(k -> k.startsWith(prefixKey))
                .collect(
                        Collectors.toMap(
                                k -> k.substring(prefixKey.length()),
                                k -> convertToString(confData.get(k), standardYaml)));
    }

    static boolean containsPrefixMap(Map<String, Object> confData, String key) {
        return confData.keySet().stream().anyMatch(candidate -> filterPrefixMapKey(key, candidate));
    }

    static boolean removePrefixMap(Map<String, Object> confData, String key) {
        final List<String> prefixKeys =
                confData.keySet().stream()
                        .filter(candidate -> filterPrefixMapKey(key, candidate))
                        .collect(Collectors.toList());
        prefixKeys.forEach(confData::remove);
        return !prefixKeys.isEmpty();
    }

    // Make sure that we cannot instantiate this class
    private ConfigurationUtils() {}
}
