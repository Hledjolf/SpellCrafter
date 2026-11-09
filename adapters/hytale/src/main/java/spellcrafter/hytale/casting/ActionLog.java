package spellcrafter.hytale.casting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Consumer;

/**
 * SpellCrafter's log, {@code spellcrafter/spellcrafter.log} in the plugin's data folder (design/commands-settings.md
 * section 4), in Mod Settings Manager's {@code changes.log} style: one line per event, the time in UTC to the second,
 * then tab-separated {@code key=value} fields. Ordinary casts aren't logged: editor writes, {@code give}, {@code cast},
 * {@code reload} and BuffEffects' refusals are.
 */
public final class ActionLog {
    private final Path file;
    private final Consumer<String> fallback;

    /** {@code file} may be null (headless tests): lines then go only to {@code fallback}. */
    public ActionLog(Path file, Consumer<String> fallback) {
        this.file = file;
        this.fallback = fallback;
    }

    /** One line: {@code <time>\tevent=<event>\t<key>=<value>...}; tabs and line breaks in values become spaces. */
    public static String line(Instant at, String event, String... keysAndValues) {
        StringBuilder b = new StringBuilder(at.truncatedTo(ChronoUnit.SECONDS).toString());
        b.append("\tevent=").append(clean(event));
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            b.append('\t').append(keysAndValues[i]).append('=').append(clean(keysAndValues[i + 1]));
        }
        return b.toString();
    }

    private static String clean(String s) {
        return s == null ? "-" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    public void write(String event, String... keysAndValues) {
        String l = line(Instant.now(), event, keysAndValues);
        if (fallback != null) fallback.accept("log " + l.replace('\t', ' '));
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            synchronized (this) {
                Files.writeString(file, l + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            if (fallback != null) fallback.accept("couldn't write " + file + ": " + e);
        }
    }

    /** Whether BuffEffects' refusal code is one the log keeps (a spell that needs adjusting). */
    public static boolean logged(String code) {
        return code != null && (code.startsWith("capped") || code.equals("out_of_bounds") || code.equals("immune"));
    }
}
