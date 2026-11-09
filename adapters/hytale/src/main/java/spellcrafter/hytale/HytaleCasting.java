package spellcrafter.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.joml.Vector3d;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.modules.projectile.ProjectileModule;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.Resolve;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.hytale.casting.ActionLog;
import spellcrafter.hytale.casting.Casting;
import spellcrafter.hytale.casting.Casting.Caster;
import spellcrafter.hytale.casting.Casting.Line;
import spellcrafter.hytale.casting.Casting.Outcome;
import spellcrafter.hytale.casting.Casting.Step;
import spellcrafter.hytale.casting.Content;
import spellcrafter.hytale.casting.PayloadPort;

/**
 * The casting rules ({@link Casting}) on Hytale: reads the caster, mana and held item from an interaction, and does
 * what the rules decide: spends mana, uses up a scroll or potion, applies payloads, launches bolts, says the lines.
 * Every line also goes to the log as {@code line <caster> <key> <params>}, so headless runs can check them.
 */
public final class HytaleCasting {
    /** A wand stack's own id (P8): stamped on first cast, so "what this wand did" is traceable. */
    public static final String STACK_ID = "SpellCrafter_Id";
    static final double EYE = 1.6;

    /** Test hook: where a caster aims, instead of where they look (headless NPCs look anywhere). Null in play. */
    public static volatile BiFunction<Ref<EntityStore>, ComponentAccessor<EntityStore>, Vector3d> aimHook;

    private final Casting rules;
    private final Content content;
    private final PayloadPort payloads;
    private final Map<String, String> bolts;
    private final Consumer<String> log;
    /** Where each caster with a cast or bolt in progress is: for the watcher and refunds. */
    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    private final Map<Object, World> boltWorlds = new ConcurrentHashMap<>();

    record Tracked(World world, Ref<EntityStore> ref) {
    }

    HytaleCasting(Casting rules, Content content, PayloadPort payloads, Map<String, String> bolts, Consumer<String> log) {
        this.rules = rules;
        this.content = content;
        this.payloads = payloads;
        this.bolts = new ConcurrentHashMap<>(bolts);
        this.log = log;
    }

    /** A spell's bolt config, for spells the editor saved while the server runs. */
    void addBolt(String spellId, String configId) {
        bolts.put(spellId, configId);
    }

    /** Whether a spell has a bolt config. */
    boolean hasBolt(String spellId) {
        return bolts.containsKey(spellId);
    }

    static long now() {
        return System.nanoTime() / 1_000_000;
    }

    // ------------------------------------------------------------------------------------------------ steps

    boolean begin(InteractionContext ctx) {
        Use u = use(ctx);
        if (u == null) return false;
        Step s = rules.begin(u.caster, u.vessel, u.chain, mana(u.cb, u.ref), now());
        if (s.first()) {
            log.accept("begin " + u.vessel.id() + " by " + u.caster.id() + " chain=" + u.chain + " sim=" + sim(ctx)
                    + ": " + describe(s.outcome()));
            if (s.outcome() instanceof Outcome.Started st) {
                tracked.put(u.caster.id(), new Tracked(u.cb.getExternalData().getWorld(), u.ref));
                say(u.cb, u.ref, u.caster.id(), st.line());
            } else if (s.outcome() instanceof Outcome.Refused r) {
                say(u.cb, u.ref, u.caster.id(), r.line());
            }
        }
        return s.outcome() instanceof Outcome.Started;
    }

    boolean complete(InteractionContext ctx) {
        Use u = use(ctx);
        if (u == null) return false;
        Step s = rules.complete(u.caster, u.chain, mana(u.cb, u.ref), now());
        if (!s.first()) {
            // A repeat, or a run before the cast time is over: never fail the chain for it.
            return !(s.outcome() instanceof Outcome.Refused r) || r.why().equals("early");
        }
        log.accept("complete " + u.vessel.id() + " by " + u.caster.id() + " chain=" + u.chain + " sim=" + sim(ctx)
                + ": " + describe(s.outcome()));
        if (s.outcome() instanceof Outcome.Refused r) {
            say(u.cb, u.ref, u.caster.id(), r.line());
            return false;
        }
        Outcome.Go go = (Outcome.Go) s.outcome();
        String source = sourceId(ctx, u);
        if (!anyLoaded(go.plan())) {
            // Nothing in it can happen: say so and spend nothing, hit or miss (a missed Shadow Bolt used its scroll).
            log.accept("complete " + go.spell().id() + ": no effect of it is loaded; nothing spent");
            say(u.cb, u.ref, u.caster.id(), Line.of("spellcrafter.cast.refunded", "spell", go.spell().name()));
            return false;
        }
        // Spend: mana for a wand (read and spent in this one step: mana regenerates), the scroll otherwise.
        if (go.mana() > 0) {
            EntityStatMap stats = u.cb.getComponent(u.ref, EntityStatMap.getComponentType());
            if (stats != null) stats.subtractStatValue(DefaultEntityStatTypes.getMana(), go.mana());
        } else if (go.vessel().kind().equals("scroll") && !useOne(ctx)) {
            log.accept("complete: couldn't use up the scroll; nothing happens");
            return false;
        }
        deliver(u, go, source);
        return true;
    }

    boolean drink(InteractionContext ctx) {
        Use u = use(ctx);
        if (u == null || !u.vessel.kind().equals("potion")) return false;
        Step s = rules.drink(u.caster, u.vessel, u.chain, now());
        if (!s.first()) return s.outcome() instanceof Outcome.Go;
        log.accept("drink " + u.vessel.id() + " by " + u.caster.id() + " chain=" + u.chain + " sim=" + sim(ctx) + ": "
                + describe(s.outcome()));
        if (s.outcome() instanceof Outcome.Refused r) {
            say(u.cb, u.ref, u.caster.id(), r.line());
            return false;
        }
        Outcome.Go go = (Outcome.Go) s.outcome();
        List<PayloadPort.Result> rs = apply(u.cb, u.ref, go.spell().id(), go.plan(), u.ref, u.caster.id(),
                "item:" + u.caster.itemId(), position(u.cb, u.ref));
        if (PayloadPort.refund(rs)) {
            say(u.cb, u.ref, u.caster.id(), Line.of("spellcrafter.cast.refunded", "spell", go.spell().name()));
            return false;
        }
        useOne(ctx);
        return true;
    }

    /** A bolt landed: on an entity ({@code hit}) or a block. */
    void landed(InteractionContext ctx, boolean hit) {
        Ref<EntityStore> bolt = ctx.getEntity();
        try {
            land(ctx, hit);
        } finally {
            despawn(ctx.getCommandBuffer(), bolt, ctx.getOwningEntity());
        }
    }

    /**
     * Removes a landed bolt on the server. The bolts' chains used to end with vanilla's {@code RemoveEntity} on
     * {@code "User"}, but the client runs that step too, and for a bolt the server spawned it resolved to the player:
     * the caster vanished from their own third-person view until they rejoined (the author's playtests, BFX-D037/D038).
     * Only a bolt SpellCrafter launched is ever removed, never the caster.
     */
    private void despawn(CommandBuffer<EntityStore> cb, Ref<EntityStore> bolt, Ref<EntityStore> owner) {
        if (bolt == null || !bolt.isValid() || bolt.equals(owner) || boltWorlds.remove(bolt) == null) return;
        cb.tryRemoveEntity(bolt, RemoveReason.REMOVE);
        log.accept("bolt removed where it landed");
    }

    private void land(InteractionContext ctx, boolean hit) {
        var cb = ctx.getCommandBuffer();
        Ref<EntityStore> owner = ctx.getOwningEntity();
        String casterId = uuid(cb, owner);
        if (casterId == null) return;
        Casting.Flight f = rules.landed(casterId, chainKey(ctx, casterId), now());
        if (f == null) return;
        Ref<EntityStore> target = hit ? ctx.getTargetEntity() : null;
        String targetId = uuid(cb, target);
        log.accept((hit ? "hit " : "miss ") + f.spell().id() + " by " + casterId + (hit ? " on " + targetId : "")
                + " sim=" + sim(ctx));
        // A bolt bursts where it lands, on an entity or a block (7b); without a burst, a block hit does nothing.
        if ((!hit || targetId == null) && f.plan().burst() == null) return;
        Vector3d at = position(cb, ctx.getEntity()); // the bolt itself, before RemoveEntity
        if (at == null && target != null) at = position(cb, target);
        List<PayloadPort.Result> rs = apply(cb, owner, f.spell().id(), f.plan(), hit ? target : null, casterId,
                f.sourceId(), at);
        if (PayloadPort.refund(rs)) refund(cb, owner, casterId, f.spell().name(), f.mana(), f.vessel());
    }

    /** False when every payload names an effect its provider is sure it hasn't loaded. */
    private boolean anyLoaded(CastResult.CastPlan plan) {
        boolean any = false;
        for (CastResult.Planned p : plan.payloads()) {
            if (p.isReroll()) continue;
            String effect = p.fields() != null && p.fields().get("effect") instanceof spellcrafter.core.json.Json.Str s ? s.value() : null;
            if (effect == null || payloads.loaded(effect)) return true;
            any = true;
        }
        return !any;
    }

    // ------------------------------------------------------------------------------------------------ delivery

    private void deliver(Use u, Outcome.Go go, String source) {
        CastResult.Planned d = go.plan().delivery();
        switch (d.op()) {
            case "self" -> {
                List<PayloadPort.Result> rs = apply(u.cb, u.ref, go.spell().id(), go.plan(), u.ref, u.caster.id(),
                        source, position(u.cb, u.ref));
                if (PayloadPort.refund(rs)) refund(u.cb, u.ref, u.caster.id(), go.spell().name(), go.mana(), go.vessel());
            }
            case "projectile", "touch" -> launch(u, go, source, d);
            default -> log.accept("delivery " + d.op() + " isn't in 1.0; nothing happens");
        }
    }

    private void launch(Use u, Outcome.Go go, String source, CastResult.Planned d) {
        String cfgId = bolts.get(go.spell().id());
        ProjectileConfig cfg = cfgId == null ? null : ProjectileConfig.getAssetMap().getAsset(cfgId);
        TransformComponent t = u.cb.getComponent(u.ref, TransformComponent.getComponentType());
        if (cfg == null || t == null) {
            log.accept("launch failed: config " + cfgId + "=" + (cfg != null) + " transform=" + (t != null));
            return;
        }
        Vector3d from = new Vector3d(t.getPosition()).add(0, EYE, 0);
        Vector3d dir = null;
        var hook = aimHook;
        Vector3d aim = hook == null ? null : hook.apply(u.ref, u.cb);
        if (aim != null) {
            dir = new Vector3d(aim).sub(from).normalize();
        } else {
            HeadRotation head = u.cb.getComponent(u.ref, HeadRotation.getComponentType());
            if (head != null) dir = new Vector3d(head.getDirection()).normalize();
        }
        if (dir == null) {
            log.accept("launch failed: no look direction");
            return;
        }
        from.add(new Vector3d(dir).mul(0.8)); // ahead of the caster, so the bolt doesn't hit them
        Ref<EntityStore> bolt = ProjectileModule.get().spawnProjectile(u.ref, u.cb, cfg, from, dir);
        long speedMilli = d.op().equals("touch") ? 15_000 : 30_000;
        long life = Casting.lifetimeMs(d, speedMilli);
        Casting.Flight f = new Casting.Flight(u.caster.id(), go.spell(), go.vessel(), go.plan(), go.mana(), source,
                now() + life);
        Object handle = bolt != null ? bolt : new Object();
        rules.launched(f, handle);
        boltWorlds.put(handle, u.cb.getExternalData().getWorld());
        log.accept("launch " + cfgId + " by " + u.caster.id() + " life=" + life + "ms bolt=" + (bolt != null));
    }

    /**
     * Resolves the cast (spell-format 7b) and applies it: the burst's entities around {@code centre} (nearest first,
     * filtered by {@code affects}), one seed per cast from the game's random source (logged, so the cast replays),
     * then each application through the payload port.
     */
    private List<PayloadPort.Result> apply(ComponentAccessor<EntityStore> a, Ref<EntityStore> casterRef, String spellId,
            CastResult.CastPlan plan, Ref<EntityStore> struck, String actor, String source, Vector3d centre) {
        String struckId = uuid(a, struck);
        List<Resolve.Caught> caught = plan.burst() == null || centre == null ? List.of()
                : Bursts.caught(a, casterRef, struck, centre, plan, content.options() == null ? null : content.options().playerHits(), log);
        long seed = java.util.concurrent.ThreadLocalRandom.current().nextLong(1, 1L << 32);
        Resolve.Resolution res = Resolve.resolve(plan, actor, struckId, caught, seed, serverCaps());
        log.accept("resolve seed=" + res.seed() + " entities=" + res.entities().size() + " applications="
                + res.applications().size() + (res.capped().isEmpty() ? "" : " capped=" + res.capped().size()));
        List<PayloadPort.Result> out = new ArrayList<>();
        for (PayloadPort.Apply ap : Casting.applications(res, actor, source)) {
            PayloadPort.Result r = payloads.apply(ap);
            out.add(r);
            // A refusal that shows a spell needing adjustment goes in the log (commands-settings.md section 4).
            if (!r.applied() && ActionLog.logged(r.code())) {
                actionLog.write("refused", "spell", spellId, "caster", actor, "target", ap.target(), "effect", ap.effect(),
                        "code", r.code());
            }
        }
        if (rules.debugging(actor)) {
            int applied = 0;
            for (PayloadPort.Result r : out) if (r.applied()) applied++;
            StringBuilder rep = new StringBuilder();
            for (var e : plan.report().entrySet()) rep.append(rep.length() == 0 ? "" : ", ").append(e.getKey()).append('=')
                    .append(e.getValue().status());
            say(a, casterRef, actor, Line.of("spellcrafter.cast.debug", "spell", spellId, "seed", Long.toString(res.seed()),
                    "entities", Integer.toString(res.entities().size()), "applied", applied + "/" + out.size(),
                    "report", rep.length() == 0 ? "-" : rep.toString()));
        }
        return out;
    }

    private ActionLog actionLog = new ActionLog(null, null);

    /** Where refusals are logged (the plugin's {@code spellcrafter.log}). */
    void actionLog(ActionLog l) {
        actionLog = l;
    }

    /**
     * {@code /spell cast}: casts {@code s} now, with no vessel, no mana and no cooldown: at {@code target} (null for
     * nothing in sight), or on the caster for a self spell. On the world thread. Returns what happened, for the reply.
     */
    String castNow(ComponentAccessor<EntityStore> a, Ref<EntityStore> casterRef, Spell s, Ref<EntityStore> target) {
        Caster c = caster(a, casterRef, null);
        boolean self = s.delivery().kind().equals("self");
        Ref<EntityStore> struck = self ? casterRef : target;
        if (struck == null) return "no_target";
        CastResult r = rules.evaluateNow(s, c, uuid(a, struck));
        if (!(r instanceof CastResult.CastPlan plan)) return "not cast: " + r.getClass().getSimpleName();
        List<PayloadPort.Result> rs = apply(a, casterRef, s.id(), plan, struck, c.id(), "command:/spell cast",
                position(a, struck));
        int ok = 0;
        for (PayloadPort.Result x : rs) if (x.applied()) ok++;
        return ok + "/" + rs.size() + " applied";
    }

    /** The server's caps: the settings' when loaded, else the defaults (burst_targets 16, burst radius 8). */
    private spellcrafter.core.model.Model.ServerCaps serverCaps() {
        return content.settings().caps();
    }

    private static Vector3d position(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        TransformComponent t = a.getComponent(ref, TransformComponent.getComponentType());
        return t == null ? null : new Vector3d(t.getPosition());
    }

    /** Every payload refused by a cap: give the mana or the scroll back (release-1.0.md 4a). */
    private void refund(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref, String casterId, String spell, long mana,
            Vessel v) {
        if (ref == null || !ref.isValid()) return;
        if (mana > 0) {
            EntityStatMap stats = a.getComponent(ref, EntityStatMap.getComponentType());
            if (stats != null) stats.addStatValue(DefaultEntityStatTypes.getMana(), mana);
        } else if (v.kind().equals("scroll")) {
            Player.giveItem(new ItemStack(Content.hytaleItem(v), 1), ref, a);
        }
        say(a, ref, casterId, Line.of("spellcrafter.cast.refunded", "spell", spell));
    }

    // ------------------------------------------------------------------------------------------------ the watcher

    /** Runs on each world's thread: interrupts casts whose caster moved or swapped items, and ends spent bolts. */
    void tick(World w) {
        Store<EntityStore> store = w.getEntityStore().getStore();
        long now = now();
        for (Casting.Interrupt i : rules.watch(id -> {
            Tracked t = tracked.get(id);
            return t != null && t.world() == w;
        }, id -> {
            Tracked t = tracked.get(id);
            return t == null || !t.ref().isValid() ? null : caster(store, t.ref(), null);
        }, now)) {
            Tracked t = tracked.get(i.casterId());
            if (t != null) say(store, t.ref(), i.casterId(), i.line());
        }
        tracked.entrySet().removeIf(e -> e.getValue().world() == w && !rules.casting(e.getKey())
                && !e.getValue().ref().isValid());
    }

    /** Bolts past their lifetime: removed from their world (a miss). Called from the tick thread. */
    void expireBolts() {
        for (Object handle : rules.expired(now())) {
            World w = boltWorlds.remove(handle);
            if (w == null || !(handle instanceof Ref<?> r)) continue;
            @SuppressWarnings("unchecked")
            Ref<EntityStore> ref = (Ref<EntityStore>) r;
            w.execute(() -> {
                if (ref.isValid()) w.getEntityStore().getStore().removeEntity(ref, RemoveReason.REMOVE);
                log.accept("bolt expired: a miss");
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    private record Use(CommandBuffer<EntityStore> cb, Ref<EntityStore> ref, Caster caster, Vessel vessel,
            String chain) {
    }

    private Use use(InteractionContext ctx) {
        var cb = ctx.getCommandBuffer();
        Ref<EntityStore> ref = ctx.getEntity();
        ItemStack held = ctx.getHeldItem();
        if (cb == null || ref == null || !ref.isValid() || held == null) return null;
        Vessel v = content.vesselForItem(held.getItemId());
        if (v == null) return null;
        Caster c = caster(cb, ref, held.getItemId());
        if (c == null) return null;
        return new Use(cb, ref, c, v, chainKey(ctx, c.id()));
    }

    /** The caster now. {@code heldItem} overrides the hotbar's active item (inside an interaction, it's the used one). */
    static Caster caster(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref, String heldItem) {
        String id = uuid(a, ref);
        TransformComponent t = a.getComponent(ref, TransformComponent.getComponentType());
        if (id == null || t == null) return null;
        InventoryComponent.Hotbar hb = a.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        int slot = hb == null ? -1 : hb.getActiveSlot();
        String item = heldItem;
        if (item == null) {
            ItemStack s = hb == null ? null : hb.getActiveItem();
            item = s == null ? null : s.getItemId();
        }
        PlayerRef pr = a.getComponent(ref, PlayerRef.getComponentType());
        String name = pr != null ? pr.getUsername() : "Someone";
        Vector3d p = t.getPosition();
        return new Caster(id, name, p.x, p.y, p.z, slot, item);
    }

    static String uuid(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) return null;
        UUIDComponent u = a.getComponent(ref, UUIDComponent.getComponentType());
        return u == null ? null : u.getUuid().toString();
    }

    private static Double mana(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref) {
        EntityStatMap stats = a.getComponent(ref, EntityStatMap.getComponentType());
        EntityStatValue v = stats == null ? null : stats.get(DefaultEntityStatTypes.getMana());
        return v == null ? null : (double) v.get();
    }

    private static String chainKey(InteractionContext ctx, String casterId) {
        return casterId + "@" + Integer.toHexString(System.identityHashCode(ctx.getChain()));
    }

    private static String sim(InteractionContext ctx) {
        var e = ctx.getEntry();
        return e == null ? "?" : String.valueOf(e.isUseSimulationState());
    }

    /** Uses up one of the held item (a scroll or potion). */
    private static boolean useOne(InteractionContext ctx) {
        ItemContainer held = ctx.getHeldItemContainer();
        return held != null && held.removeItemStackFromSlot((short) ctx.getHeldItemSlot(), 1).succeeded();
    }

    /** {@code source_id}: the wand stack's own id (stamped now if new), or the scroll's item id. */
    private String sourceId(InteractionContext ctx, Use u) {
        if (!u.vessel.kind().equals("wand")) return "item:" + u.caster.itemId();
        ItemStack s = ctx.getHeldItem();
        String have = s.getFromMetadataOrNull(STACK_ID, Codec.STRING);
        if (have != null) return "wand:" + have;
        String id = UUID.randomUUID().toString();
        ItemStack stamped = s.withMetadata(STACK_ID, Codec.STRING, id);
        ItemContainer held = ctx.getHeldItemContainer();
        if (held != null) held.setItemStackForSlot((short) ctx.getHeldItemSlot(), stamped);
        ctx.setHeldItem(stamped);
        return "wand:" + id;
    }

    // ------------------------------------------------------------------------------------------------ lines

    private void say(ComponentAccessor<EntityStore> a, Ref<EntityStore> ref, String casterId, Line line) {
        if (line == null) return;
        log.accept("line " + casterId + " " + line.key() + " " + line.params());
        PlayerRef pr = ref == null || !ref.isValid() ? null : a.getComponent(ref, PlayerRef.getComponentType());
        if (pr == null) return;
        Message m = Message.translation(line.key());
        for (Map.Entry<String, String> e : line.params().entrySet()) m = m.param(e.getKey(), e.getValue());
        pr.sendMessage(m);
    }

    private static String describe(Outcome o) {
        if (o instanceof Outcome.Started s) return "started (" + s.castMs() + " ms)";
        if (o instanceof Outcome.Go g) return "go (mana " + g.mana() + ", " + g.plan().delivery().op() + ")";
        if (o instanceof Outcome.Refused r) return "refused (" + r.why() + ")";
        return String.valueOf(o);
    }
}
