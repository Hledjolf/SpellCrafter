package spellcrafter.hytale.window;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.json.Json;
import spellcrafter.core.window.WindowModel;

/**
 * The spell editor as a raw {@code .ui} page (CommandDeck's pattern, as BuffEffects' EditorPage). All state and layout
 * live in the game-neutral {@link WindowModel}; this class builds the page, decodes the client's events, sends the
 * model's updates through {@link HytaleRenderer}, and hands each event's after-effects (files to write, requests) to
 * {@code afterEvent}.
 */
public final class EditorPage extends InteractiveCustomUIPage<EditorPage.NoEvent> {
    /** Events are decoded from the raw JSON, so the typed codec is never used. */
    public static final class NoEvent {
        static final BuilderCodec<NoEvent> CODEC = BuilderCodec.builder(NoEvent.class, NoEvent::new).build();
    }

    static final long TYPING_DELAY_MS = 300;

    private final WindowModel model;
    private final Consumer<String> log;
    private final Runnable afterEvent;
    private volatile ScheduledFuture<?> pendingRedraw;
    private volatile boolean closed;

    EditorPage(PlayerRef player, WindowModel model, Consumer<String> log, Runnable afterEvent) {
        super(player, CustomPageLifetime.CanDismiss, NoEvent.CODEC);
        this.model = model;
        this.log = log;
        this.afterEvent = afterEvent;
    }

    /** Opens the page for a player, on the player's world thread. False if the player is not in a world. */
    public static boolean open(PlayerRef player, WindowModel model, Consumer<String> log, Runnable afterEvent) {
        Ref<EntityStore> ref = player.getReference();
        if (ref == null || !ref.isValid()) return false;
        World world = ref.getStore().getExternalData().getWorld();
        Runnable r = () -> {
            Ref<EntityStore> now = player.getReference();
            if (now == null || !now.isValid()) return;
            Store<EntityStore> store = now.getStore();
            Player p = store.getComponent(now, Player.getComponentType());
            if (p != null) p.getPageManager().openCustomPage(now, store, new EditorPage(player, model, log, afterEvent));
        };
        if (world.isInThread()) r.run();
        else world.execute(r);
        return true;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder cmd, UIEventBuilder events, Store<EntityStore> store) {
        cmd.append(HytaleRenderer.DOC);
        UiOps ops;
        synchronized (model) {
            ops = HytaleRenderer.render(model.open());
        }
        UiTranslate.into(ops, cmd, events);
    }

    /** An event's raw JSON as the model reads it: strings, booleans and numbers' text. Null when undecodable. */
    public static Map<String, Object> decode(String rawData) {
        Parsed<Json> p = Formats.json(rawData);
        if (!(p instanceof Parsed.Ok<Json> ok) || !(ok.value() instanceof Json.Obj o)) return null;
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Json> e : o.members().entrySet()) {
            Json v = e.getValue();
            Object x = v instanceof Json.Str s ? s.value() : v instanceof Json.Bool b ? (Object) b.value()
                    : v instanceof Json.Num n ? n.text() : v instanceof Json.Null ? null : v.toString();
            data.put(e.getKey(), x);
        }
        return data;
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, String rawData) {
        Map<String, Object> data = decode(rawData);
        if (data == null) {
            log.accept("undecodable window event " + rawData);
            return;
        }
        Object a = data.remove("Action");
        String action = a == null ? null : a.toString();
        Map<String, Object> event = HytaleRenderer.event(data);
        // Every event in the server log, so a playtest shows what the client really sent (2026-11-08: a "Hits" choice
        // that the saved spell didn't have, with nothing to show why).
        if (!"search".equals(action)) log.accept("window " + action + " " + event);
        UiOps ops;
        boolean defer;
        try {
            synchronized (model) {
                ops = HytaleRenderer.render(model.onEvent(action, event));
                defer = action != null && WindowModel.deferred(action);
            }
        } catch (RuntimeException e) {
            log.accept("the window failed on " + action + ": " + e);
            return;
        }
        try {
            afterEvent.run();
        } catch (RuntimeException e) {
            log.accept("after the window's " + action + ": " + e);
        }
        if (defer) {
            // Each keystroke sends an event, and every page update must be acknowledged before the client
            // sends the next one. Redraw once typing pauses, so keystrokes aren't dropped.
            ScheduledFuture<?> old = pendingRedraw;
            if (old != null) old.cancel(false);
            String act = action;
            pendingRedraw = HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
                UiOps later;
                synchronized (model) {
                    later = HytaleRenderer.render(model.deferredOps(act));
                }
                send(later);
            }, TYPING_DELAY_MS, TimeUnit.MILLISECONDS);
            return;
        }
        send(ops);
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        closed = true;
        ScheduledFuture<?> f = pendingRedraw;
        if (f != null) f.cancel(false);
        super.onDismiss(ref, store);
    }

    private void send(UiOps ops) {
        // A late redraw must never land on another page the player opened since.
        if (closed || ops == null || ops.isEmpty()) return;
        try {
            UICommandBuilder cmd = new UICommandBuilder();
            UIEventBuilder ev = new UIEventBuilder();
            UiTranslate.into(ops, cmd, ev);
            sendUpdate(cmd, ev, false);
        } catch (RuntimeException e) {
            log.accept("window update failed: " + e);
        }
    }
}
