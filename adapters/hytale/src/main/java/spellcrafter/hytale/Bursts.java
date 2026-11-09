package spellcrafter.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.role.support.WorldSupport;

import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.Resolve;
import spellcrafter.hytale.casting.Settings;

/**
 * Who a burst catches on Hytale (spell-format 7b; probes B1-B3): the entity spatial resource's {@code ordered} query
 * around the centre, living entities only, filtered by {@code affects}. The core sorts, caps and rolls.
 */
final class Bursts {
    private Bursts() {
    }

    static List<Resolve.Caught> caught(ComponentAccessor<EntityStore> a, Ref<EntityStore> caster, Ref<EntityStore> struck,
            Vector3d centre, CastResult.CastPlan plan, Settings.PlayerHits players, Consumer<String> log) {
        Long radiusMilli = plan.delivery().params().get("burst.radius");
        if (radiusMilli == null || radiusMilli <= 0) return List.of();
        double radius = radiusMilli / 1000.0;
        String affects = plan.burst();
        SpatialResource<Ref<EntityStore>, EntityStore> sr = a.getResource(EntityModule.get().getEntitySpatialResourceType());
        List<Ref<EntityStore>> found = new ArrayList<>();
        sr.getSpatialStructure().ordered(centre, radius, found);
        // The entity the projectile hit is in its own burst (spell-format 7b, T2: "A: hit and Burn"), at distance 0:
        // the burst centres on it. Measured from its feet, a big creature hit high up was outside a 2-block burst
        // (the author's playtest, 2026-11-07: a Prismatic Spray on a bear caught "0 of 1").
        if (struck != null && struck.isValid() && !found.contains(struck)) found.add(0, struck);
        World world = a.getExternalData().getWorld();
        // The server's setting may override the world's PvP flag (commands-settings.md section 3, Combat).
        boolean pvp = players == Settings.PlayerHits.ALWAYS
                || (players != Settings.PlayerHits.NEVER && world != null && world.getWorldConfig().isPvpEnabled());
        List<Resolve.Caught> out = new ArrayList<>();
        for (Ref<EntityStore> r : found) {
            if (r == null || !r.isValid()) continue;
            if (a.getComponent(r, EntityStatMap.getComponentType()) == null) continue; // not alive: items, bolts
            boolean isCaster = r.equals(caster);
            if (isCaster && !affects.equals("all")) continue;
            boolean player = a.getComponent(r, Player.getComponentType()) != null;
            if (player && !isCaster && !pvp) continue; // other players only where PvP allows it, whatever affects says
            if (affects.equals("enemies") && !(player ? pvp && !isCaster : hostile(a, r, caster))) continue;
            String id = HytaleCasting.uuid(a, r);
            TransformComponent t = a.getComponent(r, TransformComponent.getComponentType());
            if (id == null || t == null) continue;
            double d = r.equals(struck) ? 0 : t.getPosition().distance(centre);
            if (d > radius) continue;
            out.add(new Resolve.Caught(id, Math.round(d * 1000)));
        }
        log.accept("burst r=" + radius + " affects=" + affects + " pvp=" + pvp + ": " + out.size() + " of " + found.size());
        return out;
    }

    /** An NPC whose current attitude toward the caster is hostile (B2: read from its WorldSupport, never changed). */
    private static boolean hostile(ComponentAccessor<EntityStore> a, Ref<EntityStore> npc, Ref<EntityStore> caster) {
        WorldSupport ws = a.getComponent(npc, NPCPlugin.get().getWorldSupportComponentType());
        if (ws == null || caster == null) return false;
        Attitude at = ws.getAttitude(npc, caster, a);
        return at == Attitude.HOSTILE;
    }
}
