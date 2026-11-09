package spellcrafter.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import buffeffects.api.ApplyOptions;
import buffeffects.api.ApplyResult;
import buffeffects.api.BuffEffects;
import buffeffects.api.BuffEffectsApi;
import buffeffects.api.MessageText;

import spellcrafter.core.json.Json;
import spellcrafter.hytale.casting.PayloadPort;

/**
 * The payload port on BuffEffects' API ({@code buffeffects.api} 1.0, BFX-D034; 0.4 and 0.5 are accepted, so dev builds keep working): each payload is one {@code apply} on its
 * target, with the planned strength and duration, the caster as {@code actor}, the vessel as {@code source_id}, and
 * the payload's {@code messages} and {@code suppress_on_apply} passed through (release-1.0.md section 4). A vanilla
 * effect with no duration in the spell lasts the game's own (BuffEffects 0.1.0-dev from 2026-11-06 23:30, BFX-016).
 *
 * <p>Only loaded when BuffEffects' classes are there; {@link #find} checks first.
 */
final class BuffEffectsPayloads implements PayloadPort {
    static final String CALLER = "Hledjolf:SpellCrafter";
    private final BuffEffects api;

    private BuffEffectsPayloads(BuffEffects api) {
        this.api = api;
    }

    /** BuffEffects' API, when BuffEffects is installed and started and speaks 0.4 or later; else null. */
    static PayloadPort find(Consumer<String> log) {
        try {
            Class.forName("buffeffects.api.BuffEffectsApi", false, BuffEffectsPayloads.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            log.accept("BuffEffects isn't installed: payloads do nothing");
            return null;
        }
        return attach(log);
    }

    private static PayloadPort attach(Consumer<String> log) {
        BuffEffects api = BuffEffectsApi.forCaller(CALLER);
        if (api == null) {
            log.accept("BuffEffects hasn't started: payloads do nothing");
            return null;
        }
        String v = api.apiVersion();
        if (!(v.startsWith("0.4") || v.startsWith("0.5") || v.startsWith("1."))) {
            log.accept("BuffEffects' API is " + v + "; SpellCrafter needs 0.4 (the hit and instant parts): payloads do nothing");
            return null;
        }
        return new BuffEffectsPayloads(api);
    }

    @Override
    public Result apply(Apply a) {
        if (!"BuffEffects".equals(a.provider())) return new Result(false, "unknown_provider", a.provider() + " isn't in 1.0");
        if (a.effect() == null) return new Result(false, "invalid", "the payload names no effect");
        ApplyOptions o = new ApplyOptions(a.durationSeconds(), null, a.strength(), a.sourceId(), a.actor(), null,
                a.suppressOnApply() ? Boolean.TRUE : null, null, messages(a.messages()));
        ApplyResult r = api.apply(a.target(), a.effect(), o);
        return r.applied() ? Result.ok() : new Result(false, r.code(), r.message());
    }

    /** A payload's {@code messages}, as written, to BuffEffects' {@code MessageText}s. */
    static List<MessageText> messages(Json j) {
        List<MessageText> out = new ArrayList<>();
        if (!(j instanceof Json.Arr arr)) return out;
        for (Json x : arr.items()) {
            if (!(x instanceof Json.Obj o)) continue;
            List<String> pick = null;
            if (o.get("pick") instanceof Json.Arr p) {
                pick = new ArrayList<>();
                for (Json s : p.items()) if (s instanceof Json.Str str) pick.add(str.value());
            }
            out.add(new MessageText(str(o, "when"), str(o, "to"), str(o, "text"), pick));
        }
        return out;
    }

    private static String str(Json.Obj o, String k) {
        return o.get(k) instanceof Json.Str s ? s.value() : null;
    }

    @Override
    public spellcrafter.core.editor.Effect describe(String effect) {
        try {
            buffeffects.api.EffectInfo info = api.describe(effect);
            if (info == null || info.definitionJson() == null) return null;
            var p = spellcrafter.core.format.Formats.json(info.definitionJson());
            if (!(p instanceof spellcrafter.core.format.Parsed.Ok<Json> ok) || !(ok.value() instanceof Json.Obj o)) return null;
            int slash = effect.lastIndexOf('/');
            return spellcrafter.core.editor.Effect.fromDefinition(o, slash > 0 ? effect.substring(0, slash) : "");
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean loaded(String effect) {
        try {
            return api.describe(effect) != null;
        } catch (RuntimeException e) {
            return true; // not sure: let the cast go ahead, and the apply refunds if it must
        }
    }

    @Override
    public java.util.List<String> list() {
        try {
            return api.list(buffeffects.api.ListFilter.all());
        } catch (RuntimeException e) {
            return java.util.List.of();
        }
    }

    @Override
    public String name() {
        return "BuffEffects' API " + api.apiVersion();
    }
}
