package spellcrafter.hytale;

import java.util.Map;
import java.util.function.Consumer;

import hledjolf.conditions.api.Conditions;
import hledjolf.conditions.api.Context;
import hledjolf.conditions.api.Evaluation;

import spellcrafter.core.eval.Tri;
import spellcrafter.hytale.casting.ConditionPort;

/**
 * The condition port on CommandDeck's condition library ({@code hledjolf.conditions.api} 1.0, CommandDeck 1.2.0).
 * The caster is the actor ({@code {self}}); the spell's target, once there is one, is {@code {spell_target}}
 * (CommandDeck's conditions.md). Evaluated on the caster's world thread, as the library asks: SpellCrafter only
 * evaluates inside the caster's interactions.
 *
 * <p>Only loaded when the library's classes are there; {@link #find} checks first, so without CommandDeck this class
 * is never touched.
 */
final class LibraryConditions implements ConditionPort {
    private final Conditions lib;

    private LibraryConditions(Conditions lib) {
        this.lib = lib;
    }

    /** The library, when CommandDeck is installed and started and speaks 1.x; else null, with the reason logged. */
    static ConditionPort find(Consumer<String> log) {
        try {
            Class.forName("hledjolf.conditions.api.Conditions", false, LibraryConditions.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            log.accept("CommandDeck's condition library isn't installed: every condition is UNKNOWN");
            return null;
        }
        return attach(log);
    }

    private static ConditionPort attach(Consumer<String> log) {
        Conditions c = Conditions.lookup();
        if (c == null) {
            log.accept("CommandDeck's condition library hasn't started: every condition is UNKNOWN");
            return null;
        }
        if (!c.apiVersion().startsWith("1.")) {
            log.accept("the condition library speaks " + c.apiVersion() + "; SpellCrafter knows 1.x: every condition is UNKNOWN");
            return null;
        }
        return new LibraryConditions(c);
    }

    @Override
    public Answer answer(String leafJson, String actor, String target) {
        Map<String, String> ph = target == null ? Map.of() : Map.of("spell_target", target);
        Evaluation e = lib.evaluate(leafJson, new Context(actor, target, ph, null, 0));
        Tri t = switch (e.answer()) {
            case TRUE -> Tri.TRUE;
            case FALSE -> Tri.FALSE;
            default -> Tri.UNKNOWN;
        };
        return new Answer(t, t == Tri.UNKNOWN ? e.reason() : null);
    }

    @Override
    public String name() {
        return "CommandDeck's condition library " + lib.apiVersion();
    }
}
