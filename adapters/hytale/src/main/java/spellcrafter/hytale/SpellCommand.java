package spellcrafter.hytale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;

import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.hytale.casting.Casting.Line;
import spellcrafter.hytale.casting.Content;
import spellcrafter.hytale.casting.SpellCommands;

/**
 * {@code /spell} (design/commands-settings.md sections 1-2). Each form is a real subcommand, so CommandDeck's sidecar
 * can describe it; each parses the words after its name itself. The answers are lang keys from {@link SpellCommands}.
 */
final class SpellCommand extends AbstractCommandCollection {
    static final String EDITOR = "spellcrafter.editor";
    static final String GIVE = "spellcrafter.give";
    static final String CAST_ANY = "spellcrafter.cast.any";
    static final String DEBUG = "spellcrafter.debug";
    static final String ADMIN = "spellcrafter.admin";

    private final SpellCrafterPlugin plugin;

    SpellCommand(SpellCrafterPlugin plugin) {
        super("spell", "SpellCrafter: list and inspect spells, give vessels, test casts");
        this.plugin = plugin;
        requireNoPermission();
        // Each optional argument is a usage variant, Hytale's way of optional positional arguments, so the game (and
        // CommandDeck, which reads the game's own list) knows every form's arguments.
        String[] filter = {"filter", "greedy", "Part of a name, a school or a category"};
        String[] spell = {"spell", "greedy", "The spell's name or id"};
        String[] spellWord = {"spell", "string", "The spell's id, such as flame_bolt"};
        String[] player = {"player", "string", "A player's name"};
        String[] vessel = {"vessel", "string", "The vessel's id, such as scroll_flame_bolt, or its item id"};
        String[] count = {"count", "int", "How many (1-64)"};
        String[] state = {"state", "string", "on or off"};
        add("list", "The loaded spells, optionally filtered by name, school or category", null, this::list,
                new String[][][] {{}, {filter}});
        add("info", "A spell's details", null, this::info, new String[][][] {{spell}});
        add("editor", "Opens the spell editor", EDITOR, this::editor, new String[][][] {{}});
        add("give", "Gives a player vessels", GIVE, this::give, new String[][][] {{player, vessel}, {player, vessel, count}});
        add("cast", "Casts any spell with no vessel, mana or cooldown", CAST_ANY, this::cast,
                new String[][][] {{spellWord}, {spellWord, player}});
        add("cooldowns", "Each spell's cooldown left, yours or a player's", null, this::cooldowns,
                new String[][][] {{}, {player}});
        add("debug", "Your casts print their rider report, seed and rolls", DEBUG, this::debug,
                new String[][][] {{}, {state}});
        add("reload", "Reloads spell packs, vessels, frequencies and settings", ADMIN, this::reload, new String[][][] {{}});
    }

    /** A subcommand: its first form is the command itself, the others its usage variants. */
    private void add(String name, String description, String permission, Action action, String[][][] forms) {
        Sub main = new Sub(name, false, description, permission, action, forms[0]);
        for (int i = 1; i < forms.length; i++) main.addUsageVariant(new Sub(name, true, description, permission, action, forms[i]));
        addSubCommand(main);
    }

    private interface Action {
        void run(CommandContext ctx, List<String> args);
    }

    /**
     * One {@code /spell} form: its permission (null for everyone) and its arguments ({@code {name, string|greedy|int,
     * description}}). A variant is a usage variant of the subcommand {@code name}. The action reads the words after the subcommand's name.
     */
    private final class Sub extends CommandBase {
        private final Action action;
        private final String sub;

        Sub(String name, boolean variant, String description, String permission, Action action, String[][] args) {
            super(variant ? null : name, description); // a variant has no name of its own (AbstractCommand(String))
            this.action = action;
            this.sub = name;
            for (String[] a : args) {
                switch (a[1]) {
                    case "greedy" -> withRequiredArg(a[0], a[2], ArgTypes.GREEDY_STRING);
                    case "int" -> withRequiredArg(a[0], a[2], ArgTypes.INTEGER);
                    default -> withRequiredArg(a[0], a[2], ArgTypes.STRING);
                }
            }
            if (permission == null) requireNoPermission();
            else requirePermission(permission);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            action.run(ctx, words(ctx.getInputString(), sub));
        }
    }

    /** The words after {@code /spell <name>}. */
    static List<String> words(String input, String name) {
        List<String> w = new ArrayList<>(Arrays.asList((input == null ? "" : input.trim()).split("\\s+")));
        w.removeIf(String::isEmpty);
        if (!w.isEmpty() && w.get(0).replaceFirst("^/", "").equalsIgnoreCase("spell")) w.remove(0);
        if (!w.isEmpty() && w.get(0).equalsIgnoreCase(name)) w.remove(0);
        return w;
    }

    // ------------------------------------------------------------------------------------------------ forms

    private void list(CommandContext ctx, List<String> args) {
        Content c = content(ctx);
        if (c != null) send(ctx, SpellCommands.list(c, String.join(" ", args)));
    }

    private void info(CommandContext ctx, List<String> args) {
        Content c = content(ctx);
        if (c == null) return;
        Spell s = SpellCommands.findSpell(c, String.join(" ", args));
        if (s == null) {
            send(ctx, Line.of("spellcrafter.cmd.no_spell", "spell", String.join(" ", args)));
            return;
        }
        send(ctx, SpellCommands.infoLines(s, plugin.fakePayloads()::describe));
    }

    private void editor(CommandContext ctx, List<String> args) {
        // The window (design/window.md): the model is the core's, drawn by spellcrafter.hytale.window.
        PlayerRef self = Universe.get().getPlayer(ctx.sender().getUuid());
        if (self == null || plugin.editors() == null || !plugin.editors().open(self)) {
            send(ctx, Line.of("spellcrafter.cmd.players_only"));
        }
    }

    private void give(CommandContext ctx, List<String> args) {
        Content c = content(ctx);
        if (c == null) return;
        if (args.size() < 2) {
            send(ctx, Line.of("spellcrafter.cmd.usage", "usage", "/spell give <player> <vessel> [count]"));
            return;
        }
        PlayerRef pr = Universe.get().getPlayerByUsername(args.get(0), NameMatching.EXACT_IGNORE_CASE);
        if (pr == null) {
            send(ctx, Line.of("spellcrafter.cmd.no_player", "player", args.get(0)));
            return;
        }
        Vessel v = SpellCommands.findVessel(c, args.get(1));
        String item = v == null ? null : Content.hytaleItem(v);
        if (item == null) {
            send(ctx, Line.of("spellcrafter.cmd.no_vessel", "vessel", args.get(1)));
            return;
        }
        int count = 1;
        if (args.size() > 2) {
            try {
                count = Math.max(1, Math.min(64, Integer.parseInt(args.get(2))));
            } catch (NumberFormatException e) {
                send(ctx, Line.of("spellcrafter.cmd.usage", "usage", "/spell give <player> <vessel> [count]"));
                return;
            }
        }
        int n = count;
        onWorld(pr, (store, ref) -> {
            Player.giveItem(new ItemStack(item, n), ref, store);
            send(ctx, Line.of("spellcrafter.cmd.give.done", "count", Integer.toString(n), "vessel", v.id(), "player", pr.getUsername()));
            plugin.actionLog().write("give", "by", who(ctx.sender()), "player", pr.getUsername(), "vessel", v.id(),
                    "count", Integer.toString(n));
        });
    }

    private void cast(CommandContext ctx, List<String> args) {
        Content c = content(ctx);
        if (c == null) return;
        if (args.isEmpty()) {
            send(ctx, Line.of("spellcrafter.cmd.usage", "usage", "/spell cast <spell> [player]"));
            return;
        }
        // The last word is a player when one is online by that name; the rest is the spell.
        PlayerRef named = args.size() > 1
                ? Universe.get().getPlayerByUsername(args.get(args.size() - 1), NameMatching.EXACT_IGNORE_CASE) : null;
        String spellText = String.join(" ", named == null ? args : args.subList(0, args.size() - 1));
        Spell s = SpellCommands.findSpell(c, spellText);
        if (s == null) {
            send(ctx, Line.of("spellcrafter.cmd.no_spell", "spell", spellText));
            return;
        }
        PlayerRef self = Universe.get().getPlayer(ctx.sender().getUuid());
        if (self == null) {
            send(ctx, Line.of("spellcrafter.cmd.players_only"));
            return;
        }
        HytaleCasting hc = SpellCrafterPlugin.casting();
        onWorld(self, (store, ref) -> {
            Ref<EntityStore> target = null;
            if (named != null && named.getReference() != null && named.getReference().isValid()
                    && named.getReference().getStore() == store) target = named.getReference();
            else if (named == null) target = TargetUtil.getTargetEntity(ref, store);
            String what = hc.castNow(store, ref, s, target);
            if (what.equals("no_target")) {
                send(ctx, Line.of("spellcrafter.cmd.cast.no_target", "spell", s.name()));
                return;
            }
            String tname = named != null ? named.getUsername() : target == null ? "you" : String.valueOf(HytaleCasting.uuid(store, target));
            send(ctx, Line.of("spellcrafter.cmd.cast.done", "spell", s.name(), "target", tname, "result", what));
            plugin.actionLog().write("cast", "by", who(ctx.sender()), "spell", s.id(), "target", tname, "result", what);
        });
    }

    private void cooldowns(CommandContext ctx, List<String> args) {
        Content c = content(ctx);
        if (c == null) return;
        PlayerRef pr;
        if (args.isEmpty()) {
            pr = Universe.get().getPlayer(ctx.sender().getUuid());
            if (pr == null) {
                send(ctx, Line.of("spellcrafter.cmd.players_only"));
                return;
            }
        } else {
            if (!ctx.sender().hasPermission(ADMIN)) {
                send(ctx, Line.of("spellcrafter.cmd.no_permission", "node", ADMIN));
                return;
            }
            pr = Universe.get().getPlayerByUsername(args.get(0), NameMatching.EXACT_IGNORE_CASE);
            if (pr == null) {
                send(ctx, Line.of("spellcrafter.cmd.no_player", "player", args.get(0)));
                return;
            }
        }
        Map<String, Long> left = plugin.rules().cooldowns(pr.getUuid().toString(), HytaleCasting.now());
        send(ctx, SpellCommands.cooldowns(c, left, pr.getUsername()));
    }

    private void debug(CommandContext ctx, List<String> args) {
        boolean on = args.isEmpty() || !args.get(0).equalsIgnoreCase("off");
        plugin.rules().debug(ctx.sender().getUuid().toString(), on);
        send(ctx, Line.of(on ? "spellcrafter.cmd.debug.on" : "spellcrafter.cmd.debug.off"));
    }

    private void reload(CommandContext ctx, List<String> args) {
        List<String> changes = plugin.reload();
        Content c = plugin.content();
        send(ctx, Line.of("spellcrafter.cmd.reload.done", "spells", Integer.toString(c.spells().size()), "vessels",
                Integer.toString(c.vessels().size()), "problems", Integer.toString(c.problems().size()),
                "changes", changes.isEmpty() ? "none" : String.join("; ", changes)));
        plugin.actionLog().write("reload", "by", who(ctx.sender()), "spells", Integer.toString(c.spells().size()),
                "vessels", Integer.toString(c.vessels().size()), "changes", changes.isEmpty() ? "none" : String.join("; ", changes));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private interface OnWorld {
        void run(Store<EntityStore> store, Ref<EntityStore> ref);
    }

    /** Runs on the player's world thread, with their entity. */
    private void onWorld(PlayerRef pr, OnWorld r) {
        World w = pr.getWorldUuid() == null ? null : Universe.get().getWorld(pr.getWorldUuid());
        if (w == null) return;
        w.execute(() -> {
            Ref<EntityStore> ref = pr.getReference();
            if (ref == null || !ref.isValid()) return;
            r.run(w.getEntityStore().getStore(), ref);
        });
    }

    private Content content(CommandContext ctx) {
        Content c = plugin.content();
        if (c == null) send(ctx, Line.of("spellcrafter.cmd.starting"));
        return c;
    }

    private static String who(CommandSender s) {
        return s.getUsername() + " (" + s.getUuid() + ")";
    }

    static void send(CommandContext ctx, Line l) {
        Message m = Message.translation(l.key());
        for (Map.Entry<String, String> e : l.params().entrySet()) m = m.param(e.getKey(), e.getValue());
        ctx.sendMessage(m);
        SpellCrafterPlugin p = SpellCrafterPlugin.get();
        if (p != null) p.log("reply " + l.key() + " " + l.params());
    }

    static void send(CommandContext ctx, List<Line> lines) {
        for (Line l : lines) send(ctx, l);
    }
}
