package spellcrafter.hytale.window;

import spellcrafter.core.window.ViewOps;
import spellcrafter.core.window.ViewOps.Child;
import spellcrafter.core.window.ViewOps.Part;
import spellcrafter.core.window.ViewOps.Ref;
import spellcrafter.core.window.WindowModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The window drawn on Hytale (CommandDeck's pattern): each of the window model's commands becomes the
 * matching command on SpellCrafter's {@code .ui} page (copied from BuffEffects', the same contract). A part is an element id ({@code #Rows}), a row is an
 * index into it ({@code #Rows[3]}), a child is an element inside the row ({@code #Rows[3] #Input}), and a
 * row kind is one of the page's row documents. Buttons are a {@code Button} with a {@code #Label} inside, so
 * their text is the label's. A binding's captures are sent back as {@code @<name>}; {@link #event} removes
 * the {@code @} again.
 */
public final class HytaleRenderer {
    private HytaleRenderer() {
    }

    public static final String DIR = "SpellCrafter/";
    public static final String DOC = DIR + "Window.ui";

    /** The page's document for each row kind (path relative to {@code Common/UI/Custom}). */
    public static String document(ViewOps.RowKind kind) {
        return switch (kind) {
            case LIST_ROW -> DIR + "ListRow.ui";
            case LIST_HEADING -> DIR + "ListHeading.ui";
            case NOTE_ROW -> DIR + "NoteRow.ui";
            case SECTION_ROW -> DIR + "SectionRow.ui";
            case TEXT_ROW -> DIR + "TextRow.ui";
            case CHECK_ROW -> DIR + "CheckRow.ui";
            case CHOICE_ROW -> DIR + "ChoiceRow.ui";
            case PART_ROW -> DIR + "PartRow.ui";
            case ADD_ROW -> DIR + "AddRow.ui";
            case ITEM_ROW -> DIR + "ItemRow.ui";
            case FOOTER_ROW -> DIR + "FooterRow.ui";
        };
    }

    /** Every document the window uses. */
    public static List<String> documents() {
        List<String> out = new ArrayList<>();
        out.add(DOC);
        for (ViewOps.RowKind k : ViewOps.RowKind.values()) out.add(document(k));
        return out;
    }

    public static UiOps render(ViewOps view) {
        UiOps ops = new UiOps();
        for (ViewOps.Op op : view.ops()) {
            if (op instanceof ViewOps.Set s) {
                ops.set(property(s.ref(), s.prop()), s.value());
            } else if (op instanceof ViewOps.Entries e) {
                List<UiOps.Entry> entries = new ArrayList<>();
                for (ViewOps.Entry x : e.entries()) entries.add(new UiOps.Entry(x.label(), x.value()));
                ops.entries(selector(e.ref()), entries);
            } else if (op instanceof ViewOps.Clear c) {
                ops.clear(part(c.part(), -1));
            } else if (op instanceof ViewOps.Append a) {
                ops.append(part(a.part(), -1), document(a.kind()));
            } else if (op instanceof ViewOps.Bind b) {
                Map<String, String> data = new LinkedHashMap<>();
                data.put("Action", b.action());
                data.putAll(b.params());
                for (Map.Entry<String, Ref> c : b.captures().entrySet()) data.put("@" + c.getKey(), selector(c.getValue()) + ".Value");
                ops.bind(selector(b.ref()), b.trigger() == ViewOps.Trigger.ACTIVATE ? "Activating" : "ValueChanged", data);
            }
        }
        return ops;
    }

    /** An event's data as the model reads it: captured values under their names, without the {@code @}. */
    public static Map<String, Object> event(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            out.put(e.getKey().startsWith("@") ? e.getKey().substring(1) : e.getKey(), e.getValue());
        }
        return out;
    }

    static boolean isButton(Child c) {
        return switch (c) {
            case BUTTON, PICK, UP, DOWN, DEL, B1, B2, B3, B4, B5, B6 -> true;
            default -> false;
        };
    }

    /** A property's selector. A button's text is its label's; a tab is a text button, so its text is its own. */
    static String property(Ref r, ViewOps.Prop p) {
        String s = selector(r);
        if (p == ViewOps.Prop.TEXT && isButton(r.child())) return s + " #Label.Text";
        return s + "." + cap(p.name());
    }

    static String selector(Ref r) {
        String s = part(r.part(), r.index());
        return r.child() == Child.NONE ? s : s + " " + child(r.child());
    }

    private static String part(Part p, int index) {
        String id = switch (p) {
            case TAB -> "#Tab" + cap(WindowModel.Tab.values()[index].name());
            case SEARCH -> "#Search";
            case FILTER -> "#Filter";
            case COUNT -> "#Count";
            case LIST -> "#List";
            case HEADING -> "#Heading";
            case META -> "#Meta";
            case ROWS -> "#Rows";
            case PREVIEW -> "#Preview";
            case PREVIEW_NOTE -> "#PreviewNote";
            case STATUS -> "#Status";
            case FOOTER -> "#Footer";
        };
        return p == Part.TAB || index < 0 ? id : id + "[" + index + "]";
    }

    private static String child(Child c) {
        return "#" + cap(c.name());
    }

    private static String cap(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
