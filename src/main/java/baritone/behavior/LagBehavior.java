/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.utils.pacing.GuiPacer;
import baritone.api.utils.pacing.LagModel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

/**
 * Plan S50: feeds the shared {@link LagModel} (smoothed ping + server TPS from world-time packets) and ticks the
 * shared GUI click budget. Pacing layers read {@link LagModel#factor()} to stretch on laggy servers.
 */
public final class LagBehavior extends Behavior {
    private int pingPoll;

    public LagBehavior(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT) {
            LagModel.global().reset();
            return;
        }
        GuiPacer.globalTick();
        if (++pingPoll < 20) return;
        pingPoll = 0;
        if (!Baritone.settings().lagAwarePacing.value) {
            LagModel.global().reset();
            return;
        }
        try {
            PlayerInfo info = ctx.player().connection.getPlayerInfo(ctx.player().getUUID());
            if (info != null) LagModel.global().ping(info.getLatency());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onReceivePacket(PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof ClientboundSetTimePacket p
                && Baritone.settings().lagAwarePacing.value) {
            LagModel.global().timeUpdate(p.gameTime(), System.nanoTime());
        }
    }
}
