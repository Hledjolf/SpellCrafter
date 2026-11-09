package spellcrafter.core.format;

import java.util.List;

/** The result of reading a document: the value (with any warnings), or every problem found. Never an exception. */
public sealed interface Parsed<T> permits Parsed.Ok, Parsed.Invalid {

    record Ok<T>(T value, List<Problem> warnings) implements Parsed<T> {
        public Ok {
            warnings = List.copyOf(warnings);
        }
    }

    record Invalid<T>(List<Problem> problems) implements Parsed<T> {
        public Invalid {
            problems = List.copyOf(problems);
        }
    }
}
