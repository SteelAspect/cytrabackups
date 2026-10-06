package dev.steelaspect.cytrabackups.mc;

import io.netty.buffer.Unpooled;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Shares a schematic with everyone through Cytra Syncmatica (mod id {@code cytra-syncmatica}), the same way a player
 * sharing one from Litematica would. Done by reflection so CytraBackups needs no compile dependency on it; every call
 * must run on the server thread.
 */
public final class SyncmaticaShare {
	public static final String MOD_ID = "cytra-syncmatica";
	private static final String PKG = "com.steelaspect.cytrasyncmatica.";

	private SyncmaticaShare() {
	}

	public static boolean available() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID);
	}

	/** Shares {@code file} placed with its min corner at {@code pos}; returns the placement id. */
	public static UUID share(Path file, String dimension, BlockPos pos, ServerPlayer owner) throws IOException {
		try {
			Object ctx = context();
			Object who;
			if (owner != null) {
				Object provider = call(ctx, "getPlayerIdentifierProvider");
				who = find(provider.getClass(), "createOrGet", 1, com.mojang.authlib.GameProfile.class).invoke(provider, owner.getGameProfile());
			} else {
				who = Class.forName(PKG + "extended_core.PlayerIdentifier").getField("MISSING_PLAYER").get(null);
			}
			Class<?> placementClass = Class.forName(PKG + "ServerPlacement");
			Object placement = placementClass.getConstructor(UUID.class, File.class, who.getClass()).newInstance(UUID.randomUUID(), file.toFile(), who);
			Object storage = call(ctx, "getFileStorage");
			File local = (File) find(storage.getClass(), "createLocalLitematic", 1, null).invoke(storage, placement);
			Files.copy(file, local.toPath(), StandardCopyOption.REPLACE_EXISTING);
			find(placementClass, "move", 4, String.class).invoke(placement, dimension, pos, Rotation.NONE, Mirror.NONE);
			Object comms = call(ctx, "getCommunicationManager");
			boolean ok = (Boolean) find(comms.getClass(), "registerNewPlacement", 1, null).invoke(comms, placement);
			if (!ok) throw new IOException("Cytra Syncmatica refused the schematic (too many shared schematics?)");
			return (UUID) call(placement, "getId");
		} catch (IOException e) {
			throw e;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
			throw new IOException("Could not share through Cytra Syncmatica: " + cause, cause);
		}
	}

	/** Unshares the given placements for everyone; returns how many were still shared. */
	public static int remove(Collection<UUID> ids) throws IOException {
		try {
			Object ctx = context();
			Object manager = call(ctx, "getSyncmaticManager");
			Object comms = call(ctx, "getCommunicationManager");
			Field targetsField = Class.forName(PKG + "communication.CommunicationManager").getDeclaredField("broadcastTargets");
			targetsField.setAccessible(true);
			List<Object> targets = new ArrayList<>((Collection<?>) targetsField.get(comms));
			Class<?> packetType = Class.forName(PKG + "communication.PacketType");
			Object removePacket = packetType.getField("REMOVE_SYNCMATIC").get(null);
			int removed = 0;
			for (UUID id : ids) {
				Object placement = find(manager.getClass(), "getPlacement", 1, UUID.class).invoke(manager, id);
				if (placement == null) continue;
				for (Object target : targets) {
					Object flavor = call(target, "getProtocolFlavor");
					Object packetId = find(packetType, "toIdentifier", 1, null).invoke(removePacket, flavor);
					FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
					buf.writeUUID(id);
					find(target.getClass(), "sendPacket", 3, null).invoke(target, packetId, buf, ctx);
				}
				find(manager.getClass(), "removePlacement", 1, null).invoke(manager, placement);
				removed++;
			}
			return removed;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
			throw new IOException("Could not remove from Cytra Syncmatica: " + cause, cause);
		}
	}

	private static Object context() throws ReflectiveOperationException, IOException {
		Class<?> syncmatica = Class.forName(PKG + "Syncmatica");
		Object key = syncmatica.getField("SERVER_CONTEXT").get(null);
		Object ctx = find(syncmatica, "getContext", 1, null).invoke(null, key);
		if (ctx == null) throw new IOException("Cytra Syncmatica isn't running on this server");
		return ctx;
	}

	private static Object call(Object target, String name) throws ReflectiveOperationException {
		return find(target.getClass(), name, 0, null).invoke(target);
	}

	/** A public method by name and parameter count (and first parameter type when given), searching superclasses. */
	private static Method find(Class<?> type, String name, int params, Class<?> firstParam) throws NoSuchMethodException {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Method m : c.getDeclaredMethods()) {
				if (!m.getName().equals(name) || m.getParameterCount() != params) continue;
				if (firstParam != null && !m.getParameterTypes()[0].isAssignableFrom(firstParam)) continue;
				m.setAccessible(true);
				return m;
			}
		}
		throw new NoSuchMethodException(type.getName() + "." + name);
	}
}
