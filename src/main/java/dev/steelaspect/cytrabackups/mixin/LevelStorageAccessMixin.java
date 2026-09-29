package dev.steelaspect.cytrabackups.mixin;

import dev.steelaspect.cytrabackups.mc.StartupRestore;
import java.nio.file.Path;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies queued restores the moment a world folder is opened and locked, before Minecraft reads level.dat or
 * any chunk. This is the "restore on next startup" path for both dedicated servers and singleplayer.
 */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
public abstract class LevelStorageAccessMixin {
	@Inject(method = "<init>", at = @At("TAIL"))
	private void cytrabackups$applyPendingRestore(LevelStorageSource source, String levelId, Path levelDir, CallbackInfo ci) {
		StartupRestore.onLevelOpened(levelId, levelDir);
	}
}
