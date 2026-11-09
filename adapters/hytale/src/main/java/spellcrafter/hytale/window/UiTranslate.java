package spellcrafter.hytale.window;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import java.util.ArrayList;
import java.util.List;


/** Turns {@link UiOps} into Hytale's UI command and event builders. */
public final class UiTranslate {
    private UiTranslate() {}

    public static void into(UiOps ops, UICommandBuilder cmd, UIEventBuilder events) {
        for (UiOps.Op op : ops.ops()) {
            switch (op) {
                case UiOps.Set s -> {
                    Object v = s.value();
                    // Strings stay strings, tooltips included: a Label's TooltipText is a plain string on the client,
                    // and sending a Message there disconnects the player ("No parameterless constructor ... System.String").
                    if (v instanceof String str) cmd.set(s.selector(), str);
                    else if (v instanceof Boolean b) cmd.set(s.selector(), b.booleanValue());
                    else cmd.set(s.selector(), ((Double) v).doubleValue());
                }
                case UiOps.Entries e -> {
                    List<DropdownEntryInfo> list = new ArrayList<>();
                    for (UiOps.Entry x : e.entries()) list.add(new DropdownEntryInfo(LocalizableString.fromString(x.label()), x.value()));
                    cmd.set(e.selector() + ".Entries", list);
                }
                case UiOps.Clear c -> cmd.clear(c.selector());
                case UiOps.Append a -> {
                    if (a.selector() == null) cmd.append(a.document());
                    else cmd.append(a.selector(), a.document());
                }
                case UiOps.Bind b -> {
                    EventData data = new EventData();
                    b.data().forEach(data::append);
                    events.addEventBinding(CustomUIEventBindingType.valueOf(b.event()), b.selector(), data, false);
                }
            }
        }
    }

    /** Counts of the Hytale commands and bindings the ops produce, for scenarios (proves they translate). */
    public static int[] translateCounts(UiOps ops) {
        UICommandBuilder cmd = new UICommandBuilder();
        UIEventBuilder ev = new UIEventBuilder();
        into(ops, cmd, ev);
        return new int[] {cmd.getCommands().length, ev.getEvents().length};
    }
}
