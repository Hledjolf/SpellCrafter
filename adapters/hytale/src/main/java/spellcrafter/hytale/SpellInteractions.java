package spellcrafter.hytale;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

/**
 * SpellCrafter's interaction steps, named in the generated item and bolt assets (make_assets.py). Each hands its
 * context to {@link HytaleCasting}; a refusal fails the step, so the chain takes its {@code Failed} branch. Hytale
 * runs every step twice (SC-004): the rules answer both runs alike and act on the first.
 */
public final class SpellInteractions {
    private SpellInteractions() {
    }

    public static final String BEGIN = "SpellCrafter_Cast_Begin";
    public static final String COMPLETE = "SpellCrafter_Cast_Complete";
    public static final String DRINK = "SpellCrafter_Drink";
    public static final String HIT = "SpellCrafter_Bolt_Hit";
    public static final String MISS = "SpellCrafter_Bolt_Miss";

    static void fail(InteractionContext ctx, boolean ok) {
        if (!ok) ctx.getState().state = InteractionState.Failed;
    }

    /** Start casting a scroll or wand. */
    public static final class Begin extends SimpleInstantInteraction {
        public static final BuilderCodec<Begin> CODEC =
                BuilderCodec.builder(Begin.class, Begin::new, SimpleInstantInteraction.CODEC).build();

        @Override
        protected void firstRun(InteractionType type, InteractionContext ctx, CooldownHandler cd) {
            HytaleCasting h = SpellCrafterPlugin.casting();
            fail(ctx, h != null && h.begin(ctx));
        }
    }

    /** The cast time is over. */
    public static final class Complete extends SimpleInstantInteraction {
        public static final BuilderCodec<Complete> CODEC =
                BuilderCodec.builder(Complete.class, Complete::new, SimpleInstantInteraction.CODEC).build();

        @Override
        protected void firstRun(InteractionType type, InteractionContext ctx, CooldownHandler cd) {
            HytaleCasting h = SpellCrafterPlugin.casting();
            fail(ctx, h != null && h.complete(ctx));
        }
    }

    /** Drink a potion. */
    public static final class Drink extends SimpleInstantInteraction {
        public static final BuilderCodec<Drink> CODEC =
                BuilderCodec.builder(Drink.class, Drink::new, SimpleInstantInteraction.CODEC).build();

        @Override
        protected void firstRun(InteractionType type, InteractionContext ctx, CooldownHandler cd) {
            HytaleCasting h = SpellCrafterPlugin.casting();
            fail(ctx, h != null && h.drink(ctx));
        }
    }

    /** A bolt hit an entity. */
    public static final class Hit extends SimpleInstantInteraction {
        public static final BuilderCodec<Hit> CODEC =
                BuilderCodec.builder(Hit.class, Hit::new, SimpleInstantInteraction.CODEC).build();

        @Override
        protected void firstRun(InteractionType type, InteractionContext ctx, CooldownHandler cd) {
            HytaleCasting h = SpellCrafterPlugin.casting();
            if (h != null) h.landed(ctx, true);
        }
    }

    /** A bolt hit a block. */
    public static final class Miss extends SimpleInstantInteraction {
        public static final BuilderCodec<Miss> CODEC =
                BuilderCodec.builder(Miss.class, Miss::new, SimpleInstantInteraction.CODEC).build();

        @Override
        protected void firstRun(InteractionType type, InteractionContext ctx, CooldownHandler cd) {
            HytaleCasting h = SpellCrafterPlugin.casting();
            if (h != null) h.landed(ctx, false);
        }
    }
}
