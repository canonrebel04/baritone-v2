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

package baritone.launch.mixins;

import baritone.utils.accessor.IEntityRenderManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderManager implements IEntityRenderManager {

    private Camera getCamera() {
        Camera cam = ((EntityRenderDispatcher) (Object) this).camera;
        if (cam == null) {
            cam = Minecraft.getInstance().gameRenderer.mainCamera();
        }
        return cam;
    }

    @Override
    public double renderPosX() {
        return getCamera().position().x;
    }

    @Override
    public double renderPosY() {
        return getCamera().position().y;
    }

    @Override
    public double renderPosZ() {
        return getCamera().position().z;
    }
}
