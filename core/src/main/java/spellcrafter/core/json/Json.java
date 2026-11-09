package spellcrafter.core.json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A JSON value, as the core's own reader produces it (design/spell-format.md, section 12: the core reads JSON from
 * text itself). Objects keep their insertion order. Numbers keep their source text; {@code spellcrafter.core.num.Milli}
 * turns them into integers, so no floating point is ever involved.
 */
public sealed interface Json permits Json.Obj, Json.Arr, Json.Str, Json.Num, Json.Bool, Json.Null {

    /** An object, members in source order. */
    record Obj(Map<String, Json> members) implements Json {
        public Obj {
            members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
        }

        public Json get(String key) {
            return members.get(key);
        }

        public boolean has(String key) {
            return members.containsKey(key);
        }
    }

    /** An array. */
    record Arr(List<Json> items) implements Json {
        public Arr {
            items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    /** A string. */
    record Str(String value) implements Json {
    }

    /** A number, as written in the source (for example {@code "12.5"} or {@code "-3"}). */
    record Num(String text) implements Json {
    }

    /** {@code true} or {@code false}. */
    record Bool(boolean value) implements Json {
    }

    /** {@code null}. */
    record Null() implements Json {
    }

    /** The result of reading text: a value, or the reason it isn't JSON. */
    sealed interface Read permits Read.Ok, Read.Error {
        record Ok(Json value) implements Read {
        }

        /** {@code offset} is the character position where reading stopped. */
        record Error(String message, int offset) implements Read {
        }
    }

    /** Reads one JSON document. Duplicate keys in an object are an error. */
    static Read read(String text) {
        return new JsonReader(text).document();
    }

    /** A short name for a value's kind, for messages. */
    static String kind(Json v) {
        if (v instanceof Obj) return "object";
        if (v instanceof Arr) return "array";
        if (v instanceof Str) return "string";
        if (v instanceof Num) return "number";
        if (v instanceof Bool) return "boolean";
        return "null";
    }
}
