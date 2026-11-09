package spellcrafter.hytale.casting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import spellcrafter.core.editor.Editor;
import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.json.Json;

/**
 * The editor's files on the server (design/editor.md section 5): the {@code default} mod's spells and vessels in the
 * plugin's data folder, and replacements of other mods' spells under {@code overrides/<mod>/}. Game-free, so the
 * file rules are unit-tested. The editor's {@link Editor.Write}s name a place and a file; this class maps them to
 * paths and writes pretty-printed JSON.
 */
public final class DefaultPack {
    public static final String SPELLS = "default.spells.json";
    public static final String VESSELS = "default.vessels.json";

    private final Path dir;

    public DefaultPack(Path dataDir) {
        this.dir = dataDir;
    }

    public Path spellsFile() {
        return dir.resolve(SPELLS);
    }

    public Path vesselsFile() {
        return dir.resolve(VESSELS);
    }

    /** The default mod as the editor's library entry: open (the editor may write it); null docs when absent. */
    public Editor.ModFiles read() {
        return new Editor.ModFiles(obj(spellsFile()), obj(vesselsFile()), true);
    }

    /** The default pack's files as {@link Content} sources; empty when there are none. */
    public List<Content.Source> spellSources() {
        return source(spellsFile());
    }

    public List<Content.Source> vesselSources() {
        return source(vesselsFile());
    }

    /** Where a write goes: {@code default} files in the data folder, {@code overrides/<mod>} below it. */
    public Path pathOf(Editor.Write w) {
        String file = w.file().equals("spells.bak") ? "spells.json.bak" : w.file() + ".json";
        if (w.where().equals(Editor.DEFAULT_MOD)) return dir.resolve("default." + file);
        if (w.where().startsWith("overrides/")) {
            return dir.resolve("overrides").resolve(safe(w.where().substring("overrides/".length()))).resolve(file);
        }
        return dir.resolve("mods").resolve(safe(w.where())).resolve(file);
    }

    /** Performs a write: creates the folder, writes pretty JSON. Returns the path written. */
    public Path write(Editor.Write w) throws IOException {
        Path p = pathOf(w);
        Files.createDirectories(p.getParent());
        Files.writeString(p, pretty(w.doc()), StandardCharsets.UTF_8);
        return p;
    }

    /** A mod id as a folder name: {@code Hledjolf:Mod} becomes {@code Hledjolf_Mod}. */
    static String safe(String mod) {
        StringBuilder b = new StringBuilder();
        for (char c : mod.toCharArray()) b.append(Character.isLetterOrDigit(c) || c == '-' || c == '.' ? c : '_');
        return b.toString();
    }

    private static List<Content.Source> source(Path p) {
        try {
            if (!Files.isRegularFile(p)) return List.of();
            return List.of(new Content.Source(p.getFileName().toString(), Files.readString(p, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Json.Obj obj(Path p) {
        try {
            if (!Files.isRegularFile(p)) return null;
            Parsed<Json> j = Formats.json(Files.readString(p, StandardCharsets.UTF_8));
            return j instanceof Parsed.Ok<Json> ok && ok.value() instanceof Json.Obj o ? o : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** JSON with two-space indents, keys in their own order. */
    public static String pretty(Json v) {
        StringBuilder b = new StringBuilder();
        pretty(v, b, "");
        return b.append('\n').toString();
    }

    private static void pretty(Json v, StringBuilder b, String indent) {
        if (v instanceof Json.Obj o) {
            if (o.members().isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int i = 0;
            for (Map.Entry<String, Json> e : o.members().entrySet()) {
                b.append(indent).append("  ");
                string(e.getKey(), b);
                b.append(": ");
                pretty(e.getValue(), b, indent + "  ");
                if (++i < o.members().size()) b.append(',');
                b.append('\n');
            }
            b.append(indent).append('}');
        } else if (v instanceof Json.Arr a) {
            if (a.items().isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int i = 0; i < a.items().size(); i++) {
                b.append(indent).append("  ");
                pretty(a.items().get(i), b, indent + "  ");
                if (i + 1 < a.items().size()) b.append(',');
                b.append('\n');
            }
            b.append(indent).append(']');
        } else if (v instanceof Json.Str s) {
            string(s.value(), b);
        } else if (v instanceof Json.Num n) {
            b.append(n.text());
        } else if (v instanceof Json.Bool bo) {
            b.append(bo.value());
        } else {
            b.append("null");
        }
    }

    private static void string(String s, StringBuilder b) {
        b.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
