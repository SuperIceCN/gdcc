package gd.script.gdcc.rpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import gd.script.gdcc.api.VfsEntrySnapshot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.Instant;

/// Shared Gson codec for the JSON-RPC adaptation layer's DTO wire format.
///
/// The codec is deliberately result-shape oriented: `serializeNulls` keeps record components
/// present as JSON `null` so GDScript `Dictionary` consumers always see the same keys. It is only
/// used for DTO payloads inside the envelope — the JSON-RPC envelope itself is built manually by
/// `JsonRpcDispatcher` and never passes through this null-serializing instance.
///
/// Wire rules pinned here:
/// - records serialize by component name; derived methods (`success()`, `completed()`, ...)
///   are not components and never appear on the wire;
/// - enums serialize as their Java `name()` (e.g. `"V451"`, `"DEBUG"`, `"LINUX_X86_64"`), and
///   unknown enum names in params fail binding with Gson's `JsonSyntaxException`;
/// - `Path` round-trips through `Path.toString()` / `Path.of(...)` (host path text);
/// - `Instant` serializes as ISO-8601 UTC (`Instant.toString()`);
/// - `VfsEntrySnapshot` uses a custom serializer because `kind()`/`path()` are derived methods
///   rather than record components, and `FileEntrySnapshot.path()` is the display path.
public final class RpcJsonCodec {
    private final @NotNull Gson gson;

    public RpcJsonCodec() {
        gson = new GsonBuilder()
                .serializeNulls()
                // Strict scalar adapters: Gson's stock adapters silently coerce across types
                // (number → String, "7" → long, "garbage" → false), which would let type-wrong
                // params sail through binding instead of failing as `-32602`.
                .registerTypeAdapter(String.class, new StrictStringTypeAdapter().nullSafe())
                .registerTypeAdapter(Boolean.class, new StrictBooleanTypeAdapter().nullSafe())
                .registerTypeAdapter(Long.class, new StrictLongTypeAdapter().nullSafe())
                .registerTypeAdapter(Integer.class, new StrictIntegerTypeAdapter().nullSafe())
                // Hierarchy-wide for Path: the declared field type is the `Path` interface, and an
                // exact-type registration would let Gson eagerly build a reflective adapter for the
                // JDK-internal runtime class (e.g. `sun.nio.fs.UnixPath`), which is inaccessible.
                .registerTypeHierarchyAdapter(Path.class, new PathTypeAdapter().nullSafe())
                .registerTypeAdapter(Instant.class, new InstantTypeAdapter().nullSafe())
                // Gson's stock enum adapter silently maps unknown names to `null`; fail fast with
                // `JsonSyntaxException` so bad enum spellings bind as `-32602` with a clear cause.
                .registerTypeAdapterFactory(new StrictEnumTypeAdapterFactory())
                // Hierarchy-wide: snapshot results surface as concrete record types (fields like
                // `List<LinkEntrySnapshot>` included), which an exact-type registration misses.
                .registerTypeHierarchyAdapter(VfsEntrySnapshot.class, new VfsEntrySnapshotSerializer())
                .create();
    }

    /// Serializes a DTO result (record, map, list, string, or `null`) into a JSON tree ready to be
    /// embedded into the envelope's `result` member.
    public @NotNull JsonElement toJsonTree(@Nullable Object value) {
        return gson.toJsonTree(value);
    }

    /// Binds a `params` object to one dedicated param record. Missing required components stay
    /// `null` on boxed record components and are rejected by the record's compact constructor;
    /// type mismatches surface as Gson's `JsonParseException`/`JsonSyntaxException`.
    ///
    /// A JSON-`null` `params` member is normalized to `{}` up front (Gson would otherwise bind the
    /// whole record to `null` and bypass compact-constructor validation entirely).
    ///
    /// Gson wraps record compact-constructor validation failures (`IllegalArgumentException` /
    /// `NullPointerException`) into a plain `RuntimeException` whose cause chain still holds the
    /// original failure; this method unwraps and rethrows that original failure so callers can map
    /// every binding problem to `-32602` without catching broad `RuntimeException`.
    public <T> T bindParams(@NotNull JsonElement params, @NotNull Class<T> paramType) {
        try {
            return gson.fromJson(normalizeParams(params), paramType);
        } catch (RuntimeException exception) {
            throw unwrapBindingFailure(exception);
        }
    }

    public <T> T bindParams(@NotNull JsonElement params, @NotNull TypeToken<T> paramType) {
        try {
            return gson.fromJson(normalizeParams(params), paramType);
        } catch (RuntimeException exception) {
            throw unwrapBindingFailure(exception);
        }
    }

    private static @NotNull JsonElement normalizeParams(@NotNull JsonElement params) {
        return params.isJsonNull() ? new JsonObject() : params;
    }

    private static @NotNull RuntimeException unwrapBindingFailure(@NotNull RuntimeException exception) {
        if (exception instanceof JsonParseException) {
            return exception;
        }
        var cause = exception.getCause();
        while (cause != null) {
            if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
                return (RuntimeException) cause;
            }
            cause = cause.getCause();
        }
        return exception;
    }

    /// Strings must arrive as JSON strings; the stock adapter would stringify numbers/booleans.
    private static final class StrictStringTypeAdapter extends TypeAdapter<String> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull String value) throws IOException {
            out.value(value);
        }

        @Override
        public @NotNull String read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.STRING) {
                throw new JsonSyntaxException("Expected a JSON string but was " + in.peek());
            }
            return in.nextString();
        }
    }

    /// Booleans must arrive as JSON booleans; the stock adapter maps any string through
    /// `Boolean.parseBoolean`, turning `"garbage"` into a silent `false`.
    private static final class StrictBooleanTypeAdapter extends TypeAdapter<Boolean> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull Boolean value) throws IOException {
            out.value(value.booleanValue());
        }

        @Override
        public @NotNull Boolean read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.BOOLEAN) {
                throw new JsonSyntaxException("Expected a JSON boolean but was " + in.peek());
            }
            return in.nextBoolean();
        }
    }

    /// Longs must arrive as JSON numbers; the stock adapter also parses quoted numbers.
    private static final class StrictLongTypeAdapter extends TypeAdapter<Long> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull Long value) throws IOException {
            out.value(value.longValue());
        }

        @Override
        public @NotNull Long read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.NUMBER) {
                throw new JsonSyntaxException("Expected a JSON number but was " + in.peek());
            }
            try {
                return in.nextLong();
            } catch (NumberFormatException exception) {
                throw new JsonSyntaxException(exception);
            }
        }
    }

    private static final class StrictIntegerTypeAdapter extends TypeAdapter<Integer> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull Integer value) throws IOException {
            out.value(value.intValue());
        }

        @Override
        public @NotNull Integer read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.NUMBER) {
                throw new JsonSyntaxException("Expected a JSON number but was " + in.peek());
            }
            try {
                return in.nextInt();
            } catch (NumberFormatException exception) {
                throw new JsonSyntaxException(exception);
            }
        }
    }

    /// Host paths cross the wire as plain text; the client never resolves them against its own
    /// filesystem, so no platform normalization happens here.
    private static final class PathTypeAdapter extends TypeAdapter<Path> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull Path value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public @NotNull Path read(@NotNull JsonReader in) throws IOException {
            if (in.peek() != JsonToken.STRING) {
                throw new JsonSyntaxException("Expected a JSON string for a path but was " + in.peek());
            }
            return Path.of(in.nextString());
        }
    }

    private static final class InstantTypeAdapter extends TypeAdapter<Instant> {
        @Override
        public void write(@NotNull JsonWriter out, @NotNull Instant value) throws IOException {
            out.value(value.toString());
        }

        @Override
        public @NotNull Instant read(@NotNull JsonReader in) throws IOException {
            return Instant.parse(in.nextString());
        }
    }

    /// Enums cross the wire as their Java `name()`; unknown names are rejected instead of becoming
    /// `null` (the stock adapter's lenient behavior would defer the failure to a confusing
    /// "must not be null" deep inside param validation).
    private static final class StrictEnumTypeAdapterFactory implements TypeAdapterFactory {
        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public <T> @Nullable TypeAdapter<T> create(@NotNull Gson gson, @NotNull TypeToken<T> type) {
            if (!type.getRawType().isEnum()) {
                return null;
            }
            return (TypeAdapter<T>) new StrictEnumTypeAdapter<>(
                    (Class<? extends Enum>) type.getRawType().asSubclass(Enum.class)
            ).nullSafe();
        }
    }

    private static final class StrictEnumTypeAdapter<E extends Enum<E>> extends TypeAdapter<E> {
        private final @NotNull Class<E> enumType;

        StrictEnumTypeAdapter(@NotNull Class<E> enumType) {
            this.enumType = enumType;
        }

        @Override
        public void write(@NotNull JsonWriter out, @NotNull E value) throws IOException {
            out.value(value.name());
        }

        @Override
        public @NotNull E read(@NotNull JsonReader in) throws IOException {
            var name = in.nextString();
            try {
                return Enum.valueOf(enumType, name);
            } catch (IllegalArgumentException exception) {
                throw new JsonSyntaxException(
                        "Unknown " + enumType.getSimpleName() + " value '" + name + "'",
                        exception
                );
            }
        }
    }

    /// Hard wire contract for the sealed `VfsEntrySnapshot` hierarchy: common members
    /// (`kind`, `path`, `virtualPath`, `name`) plus the per-subtype fields. Snapshots are
    /// output-only, so no deserializer is registered; `brokenReason` is always emitted (JSON
    /// `null` when intact) to keep the shape stable for `Dictionary` consumers.
    private static final class VfsEntrySnapshotSerializer implements JsonSerializer<VfsEntrySnapshot> {
        @Override
        public @NotNull JsonElement serialize(
                @NotNull VfsEntrySnapshot snapshot,
                @NotNull Type typeOfSrc,
                @NotNull JsonSerializationContext context
        ) {
            var json = new JsonObject();
            json.addProperty("kind", snapshot.kind().name());
            json.addProperty("path", snapshot.path());
            json.addProperty("virtualPath", snapshot.virtualPath());
            json.addProperty("name", snapshot.name());
            switch (snapshot) {
                case VfsEntrySnapshot.DirectoryEntrySnapshot directory ->
                        json.addProperty("childCount", directory.childCount());
                case VfsEntrySnapshot.FileEntrySnapshot file -> {
                    json.addProperty("displayPath", file.displayPath());
                    json.addProperty("byteCount", file.byteCount());
                    json.addProperty("updatedAt", file.updatedAt().toString());
                }
                case VfsEntrySnapshot.LinkEntrySnapshot link -> {
                    json.addProperty("linkKind", link.linkKind().name());
                    json.addProperty("target", link.target());
                    json.add("brokenReason", link.brokenReason() == null
                            ? JsonNull.INSTANCE
                            : new JsonPrimitive(link.brokenReason().name()));
                }
            }
            return json;
        }
    }
}
