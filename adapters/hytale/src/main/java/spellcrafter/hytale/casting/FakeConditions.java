package spellcrafter.hytale.casting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import spellcrafter.core.eval.Tri;

/**
 * The condition port SpellCrafter casts through: answers a test has set with {@link #set} win; otherwise the real
 * library answers ({@link #useLibrary}, CommandDeck's condition library when it's installed); with neither, every
 * leaf is UNKNOWN ("the condition library isn't installed"), so riders stay off and a gated spell is unavailable.
 */
public final class FakeConditions implements ConditionPort {
    private final Map<String, Tri> byType = new ConcurrentHashMap<>();
    private volatile ConditionPort library;

    /** Answers every leaf of {@code type} with {@code value} from now on; null clears it. */
    public void set(String type, Tri value) {
        if (value == null) byType.remove(type);
        else byType.put(type, value);
    }

    /** The real library, for every leaf no test has set; null for none. */
    public void useLibrary(ConditionPort lib) {
        library = lib;
    }

    @Override
    public Answer answer(String leafJson, String actor, String target) {
        String type = typeOf(leafJson);
        Tri t = type == null ? null : byType.get(type);
        if (t != null) return new Answer(t, null);
        ConditionPort lib = library;
        if (lib != null) return lib.answer(leafJson, actor, target);
        return new Answer(Tri.UNKNOWN, "the condition library isn't installed");
    }

    @Override
    public String name() {
        ConditionPort lib = library;
        return lib != null ? lib.name() + (byType.isEmpty() ? "" : ", with test answers for " + byType.keySet())
                : "none (CommandDeck's condition library isn't installed)";
    }

    /** The leaf's {@code "type"}: canonical JSON has sorted keys, so read it plainly. */
    static String typeOf(String leafJson) {
        int k = leafJson.indexOf("\"type\":\"");
        if (k < 0) return null;
        int start = k + 8;
        int end = leafJson.indexOf('"', start);
        return end < 0 ? null : leafJson.substring(start, end);
    }
}
