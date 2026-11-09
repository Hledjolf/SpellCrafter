package spellcrafter.core.window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the window draws, as a list of commands any toolkit can follow, in order (CommandDeck's pattern; the same contract as BuffEffects' window, so one renderer pattern draws both).
 * Elements are named by their part of the window, a row index and a child, never by a toolkit's selectors;
 * rows are named by kind, never by a layout file. Each game's renderer turns these into its own UI calls.
 * <p>
 * A binding sends its action and params when it fires, plus the current value of each captured element under
 * the capture's name. Buttons capture every field on the right, because a client may drop field events while
 * an update is unacknowledged.
 */
public final class ViewOps {
    /** The parts of the window. {@link #TAB} is indexed by the tab's ordinal. */
    public enum Part {
        TAB, SEARCH, FILTER, COUNT, LIST, HEADING, META, ROWS, PREVIEW, PREVIEW_NOTE, STATUS, FOOTER
    }

    /** Elements inside a row, a list entry or the footer. */
    public enum Child {
        NONE, LABEL, INPUT, HINT, BUTTON, PICK, UP, DOWN, DEL, B1, B2, B3, B4, B5, B6
    }

    /** The kinds of row appended into the list, the right pane's rows, and the footer. */
    public enum RowKind {
        /** A button in the list on the left. */
        LIST_ROW,
        /** A heading over a block of the list. */
        LIST_HEADING,
        /** A paragraph on the right. */
        NOTE_ROW,
        /** A heading over a block of fields on the right. */
        SECTION_ROW,
        /** A text field, with an optional Pick button. */
        TEXT_ROW,
        /** A true or false field. */
        CHECK_ROW,
        /** A dropdown field. */
        CHOICE_ROW,
        /** One part's title, with up, down and delete. */
        PART_ROW,
        /** A dropdown and a button: add a part, copy a look. */
        ADD_ROW,
        /** A line of text and a button: one application in the Active tab. */
        ITEM_ROW,
        /** Up to six buttons. */
        FOOTER_ROW
    }

    public enum Prop {
        TEXT, VALUE, VISIBLE, DISABLED
    }

    public enum Trigger {
        /** A button press. */
        ACTIVATE,
        /** A field's value changed. */
        CHANGE
    }

    /** An element: a part, the index of a row in it (-1 for the part itself), and a child (NONE for the row itself). */
    public record Ref(Part part, int index, Child child) {
        public static Ref of(Part part) {
            return new Ref(part, -1, Child.NONE);
        }

        public static Ref row(int index, Child child) {
            return new Ref(Part.ROWS, index, child);
        }

        public static Ref list(int index, Child child) {
            return new Ref(Part.LIST, index, child);
        }

        public static Ref footer(Child child) {
            return new Ref(Part.FOOTER, 0, child);
        }
    }

    public sealed interface Op permits Set, Entries, Clear, Append, Bind {}

    /** Sets a property; the value is a String or Boolean (the core has no floating point). */
    public record Set(Ref ref, Prop prop, Object value) implements Op {}

    /** Sets a dropdown's entries. */
    public record Entries(Ref ref, List<Entry> entries) implements Op {}

    public record Entry(String label, String value) {}

    /** Removes every row of a part. */
    public record Clear(Part part) implements Op {}

    /** Appends a row of a kind at the end of a part; it gets the next index. */
    public record Append(Part part, RowKind kind) implements Op {}

    /** Binds an event: its action and params, and the current values of the captured elements, by name. */
    public record Bind(Ref ref, Trigger trigger, String action, Map<String, String> params, Map<String, Ref> captures) implements Op {}

    private final List<Op> ops = new ArrayList<>();

    public ViewOps set(Ref ref, Prop prop, Object value) {
        if (!(value instanceof String || value instanceof Boolean)) {
            throw new IllegalArgumentException("UI values are String or Boolean");
        }
        ops.add(new Set(ref, prop, value));
        return this;
    }

    public ViewOps text(Ref ref, String text) {
        return set(ref, Prop.TEXT, text == null ? "" : text);
    }

    public ViewOps value(Ref ref, Object value) {
        return set(ref, Prop.VALUE, value);
    }

    public ViewOps visible(Ref ref, boolean visible) {
        return set(ref, Prop.VISIBLE, visible);
    }

    public ViewOps disabled(Ref ref, boolean disabled) {
        return set(ref, Prop.DISABLED, disabled);
    }

    public ViewOps entries(Ref ref, List<Entry> entries) {
        ops.add(new Entries(ref, List.copyOf(entries)));
        return this;
    }

    public ViewOps clear(Part part) {
        ops.add(new Clear(part));
        return this;
    }

    public ViewOps append(Part part, RowKind kind) {
        ops.add(new Append(part, kind));
        return this;
    }

    public ViewOps bind(Ref ref, Trigger trigger, String action, Map<String, String> params, Map<String, Ref> captures) {
        ops.add(new Bind(ref, trigger, action, new LinkedHashMap<>(params), new LinkedHashMap<>(captures)));
        return this;
    }

    public ViewOps addAll(ViewOps other) {
        ops.addAll(other.ops);
        return this;
    }

    public List<Op> ops() {
        return List.copyOf(ops);
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }

    // ------------------------------------------------------------------------------------- for tests

    /** The last value set on an element's property, or null. */
    public Object valueOf(Ref ref, Prop prop) {
        Object v = null;
        for (Op op : ops) if (op instanceof Set s && s.ref().equals(ref) && s.prop() == prop) v = s.value();
        return v;
    }

    /** The kinds appended into a part, in order. */
    public List<RowKind> appended(Part part) {
        List<RowKind> out = new ArrayList<>();
        for (Op op : ops) {
            if (op instanceof Clear c && c.part() == part) out.clear();
            if (op instanceof Append a && a.part() == part) out.add(a.kind());
        }
        return out;
    }

    /** The binding on an element, or null. */
    public Bind bindingOf(Ref ref) {
        Bind b = null;
        for (Op op : ops) if (op instanceof Bind x && x.ref().equals(ref)) b = x;
        return b;
    }
}
