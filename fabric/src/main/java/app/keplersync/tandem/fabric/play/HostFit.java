package app.keplersync.tandem.fabric.play;

import app.keplersync.tandem.core.HostNeeds;
import app.keplersync.tandem.fabric.Guard;
import app.keplersync.tandem.fabric.account.Prefs;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * How well each player's PC could host this synced world, for the hand-off list ({@link HostNeeds}). When someone
 * joins, this game asks their Tandem for the memory their game may use and how this world ran the last time they
 * hosted it. While this PC hosts a synced world, it measures the same for itself and keeps it for next time.
 */
public final class HostFit {
	private HostFit() {}

	/** The host asking: sent to each player who joins a synced world. */
	public record Ask(String worldId) implements CustomPacketPayload {
		static final Type<Ask> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tandem", "host_ask"));
		static final StreamCodec<ByteBuf, Ask> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Ask::worldId, Ask::new);

		@Override
		public Type<Ask> type() {
			return TYPE;
		}
	}

	/** A player's answer: bytes their game may use, and ms a tick the last time they hosted the world (0: never). */
	public record Answer(long maxMemory, float msPerTick) implements CustomPacketPayload {
		static final Type<Answer> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("tandem", "host_answer"));
		static final StreamCodec<ByteBuf, Answer> CODEC = StreamCodec.composite(
				ByteBufCodecs.VAR_LONG, Answer::maxMemory, ByteBufCodecs.FLOAT, Answer::msPerTick, Answer::new);

		@Override
		public Type<Answer> type() {
			return TYPE;
		}
	}

	/** What each player in the game this PC hosts told it, by Minecraft account. */
	private static final Map<UUID, Answer> heard = new ConcurrentHashMap<>();
	/** The least memory this game used while hosting, sampled every 5 seconds: about what the world needs. */
	private static volatile long inUse;
	private static double tickMs;
	private static int samples;

	/** Why [player]'s PC would struggle to host the world this PC hosts, or null if nothing says so. */
	public static HostNeeds.Problem problem(UUID player) {
		Answer a = heard.get(player);
		return a == null ? null : HostNeeds.problem(a.maxMemory(), a.msPerTick(), inUse);
	}

	/** Dev checks: whether [player]'s Tandem answered, and with how this world ran when they last hosted it. */
	public static Float heardMsPerTick(UUID player) {
		Answer a = heard.get(player);
		return a == null ? null : a.msPerTick();
	}

	public static void init() {
		PayloadTypeRegistry.playS2C().register(Ask.TYPE, Ask.CODEC);
		PayloadTypeRegistry.playC2S().register(Answer.TYPE, Answer.CODEC);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> Guard.run("asking how well a player could host", () -> {
			WorldRules rules = WorldRules.of(server);
			// Players without Tandem can't take a hand-off anyway.
			if (rules != null && !server.isSingleplayerOwner(handler.getPlayer().getGameProfile()) && ServerPlayNetworking.canSend(handler, Ask.TYPE)) {
				sender.sendPacket(new Ask(rules.worldId));
			}
		}));
		ServerPlayNetworking.registerGlobalReceiver(Answer.TYPE, (answer, context) -> heard.put(context.player().getUUID(), answer));
		ClientPlayNetworking.registerGlobalReceiver(Ask.TYPE, (ask, context) -> Guard.run("telling the host how well this PC could host", () ->
				context.responseSender().sendPacket(new Answer(Runtime.getRuntime().maxMemory(), Prefs.get().hosted.getOrDefault(ask.worldId(), 0f)))));
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			heard.clear();
			inUse = 0;
			tickMs = 0;
			samples = 0;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			// From the 10th second: a world's first ticks load and generate the land around everyone.
			if (server.getTickCount() < 200 || server.getTickCount() % 100 != 0 || WorldRules.of(server) == null) return;
			Runtime rt = Runtime.getRuntime();
			long used = rt.totalMemory() - rt.freeMemory();
			if (inUse == 0 || used < inUse) inUse = used;
			tickMs += server.getAverageTickTimeNanos() / 1e6;
			samples++;
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Guard.run("noting how this world ran", () -> {
			WorldRules rules = WorldRules.of(server);
			// Half a minute at least, so a quick look in doesn't stand for the world.
			if (rules == null || samples < 6) return;
			Prefs.get().hosted.put(rules.worldId, (float) (tickMs / samples));
			Prefs.save();
		}));
	}
}
