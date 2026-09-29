package dev.steelaspect.cytrabackups.net;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Command output target for commands started from the GUI: everything goes to the player's chat as usual and is
 * mirrored into the GUI's output panel. Also marks the source so confirmations open a dialog instead of relying on
 * chat links.
 */
public class GuiCommandSource implements CommandSource {
	private final ServerPlayer player;

	public GuiCommandSource(ServerPlayer player) {
		this.player = player;
	}

	public ServerPlayer player() {
		return player;
	}

	/** Copies a message into the GUI output panel. */
	protected void mirror(Component component) {
		CytraNetworking.sendMessage(player, component, false);
	}

	/** Asks the GUI to show a confirmation dialog; false if the client cannot receive it. */
	protected boolean prompt(PromptPayload prompt) {
		if (!ServerPlayNetworking.canSend(player, PromptPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, prompt);
		return true;
	}

	@Override
	public void sendSystemMessage(Component component) {
		player.sendSystemMessage(component);
		mirror(component);
	}

	@Override
	public boolean acceptsSuccess() {
		return true; // the GUI shows results even when the sendCommandFeedback game rule is off
	}

	@Override
	public boolean acceptsFailure() {
		return true;
	}

	@Override
	public boolean shouldInformAdmins() {
		// Vanilla would echo "[player: ...]" back to the acting op as well (it only skips the player's own source);
		// GUI commands are written to the server log instead (see CytraNetworking.runCommand).
		return false;
	}
}
