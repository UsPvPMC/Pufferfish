package net.minecraft.server.dedicated;

import com.google.common.base.MoreObjects;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;
import org.slf4j.Logger;

import joptsimple.OptionSet; // CraftBukkit
import net.minecraft.core.RegistryAccess;

public abstract class Settings<T extends Settings<T>> {

    private static final Logger LOGGER = LogUtils.getLogger();
    public final Properties properties;
    // CraftBukkit start
    private OptionSet options = null;

    public Settings(Properties properties, final OptionSet options) {
        this.properties = properties;

        this.options = options;
    }

    private String getOverride(String name, String value) {
        if ((this.options != null) && (this.options.has(name)) && !name.equals( "online-mode")) { // Spigot
            return String.valueOf(this.options.valueOf(name));
        }

        return value;
        // CraftBukkit end
    }

    public static Properties loadFromFile(Path path) {
        Properties properties = new Properties();

        try {
            InputStream inputstream = Files.newInputStream(path);

            try {
                properties.load(inputstream);
            } catch (Throwable throwable) {
                if (inputstream != null) {
                    try {
                        inputstream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                }

                throw throwable;
            }

            if (inputstream != null) {
                inputstream.close();
            }
        } catch (IOException ioexception) {
            Settings.LOGGER.error("Failed to load properties from file: {}", path);
        }

        return properties;
    }

    public void store(Path path) {
        try {
            // CraftBukkit start - Don't attempt writing to file if it's read only
            if (path.toFile().exists() && !path.toFile().canWrite()) {
                return;
            }
            // CraftBukkit end
            OutputStream outputstream = Files.newOutputStream(path);

            try {
                this.properties.store(outputstream, "Minecraft server properties");
            } catch (Throwable throwable) {
                if (outputstream != null) {
                    try {
                        outputstream.close();
                    } catch (Throwable throwable1) {
                        throwable.addSuppressed(throwable1);
                    }
                }

                throw throwable;
            }

            if (outputstream != null) {
                outputstream.close();
            }
        } catch (IOException ioexception) {
            Settings.LOGGER.error("Failed to store properties to file: {}", path);
        }

    }

    private static <V extends Number> Function<String, V> wrapNumberDeserializer(Function<String, V> parser) {
        return (s) -> {
            try {
                return (V) parser.apply(s); // CraftBukkit - decompile error
            } catch (NumberFormatException numberformatexception) {
                return null;
            }
        };
    }

    protected static <V> Function<String, V> dispatchNumberOrString(IntFunction<V> intParser, Function<String, V> fallbackParser) {
        return (s) -> {
            try {
                return intParser.apply(Integer.parseInt(s));
            } catch (NumberFormatException numberformatexception) {
                return fallbackParser.apply(s);
            }
        };
    }

    @Nullable
    public String getStringRaw(String key) {
        return (String) this.getOverride(key, this.properties.getProperty(key)); // CraftBukkit
    }

    @Nullable
    protected <V> V getLegacy(String key, Function<String, V> stringifier) {
        String s1 = this.getStringRaw(key);

        if (s1 == null) {
            return null;
        } else {
            this.properties.remove(key);
            return stringifier.apply(s1);
        }
    }

    protected <V> V get(String key, Function<String, V> parser, Function<V, String> stringifier, V fallback) {
        // CraftBukkit start
        try {
            return this.get0(key, parser, stringifier, fallback);
        } catch (Exception ex) {
            throw new RuntimeException("Could not load invalidly configured property '" + key + "'", ex);
        }
    }

    private <V> V get0(String s, Function<String, V> function, Function<V, String> function1, V v0) {
        // CraftBukkit end
        String s1 = this.getStringRaw(s);
        V v1 = MoreObjects.firstNonNull(s1 != null ? function.apply(s1) : null, v0);

        this.properties.put(s, function1.apply(v1));
        return v1;
    }

    protected <V> Settings<T>.MutableValue<V> getMutable(String key, Function<String, V> parser, Function<V, String> stringifier, V fallback) {
        String s1 = this.getStringRaw(key);
        V v1 = MoreObjects.firstNonNull(s1 != null ? parser.apply(s1) : null, fallback);

        this.properties.put(key, stringifier.apply(v1));
        return new Settings.MutableValue(key, v1, stringifier); // CraftBukkit - decompile error
    }

    protected <V> V get(String key, Function<String, V> parser, UnaryOperator<V> parsedTransformer, Function<V, String> stringifier, V fallback) {
        return this.get(key, (s1) -> {
            V v1 = parser.apply(s1);

            return v1 != null ? parsedTransformer.apply(v1) : null;
        }, stringifier, fallback);
    }

    protected <V> V get(String key, Function<String, V> parser, V fallback) {
        return this.get(key, parser, Objects::toString, fallback);
    }

    protected <V> Settings<T>.MutableValue<V> getMutable(String key, Function<String, V> parser, V fallback) {
        return this.getMutable(key, parser, Objects::toString, fallback);
    }

    protected String get(String key, String fallback) {
        return (String) this.get(key, Function.identity(), Function.identity(), fallback);
    }

    @Nullable
    protected String getLegacyString(String key) {
        return (String) this.getLegacy(key, Function.identity());
    }

    protected int get(String key, int fallback) {
        return (Integer) this.get(key, Settings.wrapNumberDeserializer(Integer::parseInt), fallback);
    }

    protected Settings<T>.MutableValue<Integer> getMutable(String key, int fallback) {
        return this.getMutable(key, Settings.wrapNumberDeserializer(Integer::parseInt), fallback);
    }

    protected int get(String key, UnaryOperator<Integer> transformer, int fallback) {
        return (Integer) this.get(key, Settings.wrapNumberDeserializer(Integer::parseInt), transformer, Objects::toString, fallback);
    }

    protected long get(String key, long fallback) {
        return (Long) this.get(key, Settings.wrapNumberDeserializer(Long::parseLong), fallback);
    }

    protected boolean get(String key, boolean fallback) {
        return (Boolean) this.get(key, Boolean::valueOf, fallback);
    }

    protected Settings<T>.MutableValue<Boolean> getMutable(String key, boolean fallback) {
        return this.getMutable(key, Boolean::valueOf, fallback);
    }

    @Nullable
    protected Boolean getLegacyBoolean(String key) {
        return (Boolean) this.getLegacy(key, Boolean::valueOf);
    }

    protected Properties cloneProperties() {
        Properties properties = new Properties();

        properties.putAll(this.properties);
        return properties;
    }

    protected abstract T reload(RegistryAccess iregistrycustom, Properties properties, OptionSet optionset); // CraftBukkit

    public class MutableValue<V> implements Supplier<V> {

        private final String key;
        private final V value;
        private final Function<V, String> serializer;

        MutableValue(String s, V object, Function function) { // CraftBukkit - decompile error
            this.key = s;
            this.value = object;
            this.serializer = function;
        }

        public V get() {
            return this.value;
        }

        public T update(RegistryAccess registryManager, V value) {
            Properties properties = Settings.this.cloneProperties();

            properties.put(this.key, this.serializer.apply(value));
            return Settings.this.reload(registryManager, properties, Settings.this.options); // CraftBukkit
        }
    }
}
