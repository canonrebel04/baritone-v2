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

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.utils.IPlayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.WitherSkull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
    }

    /**
     * Lazy distance check used during node expansion. Returns the avoidance
     * coefficient if the position is inside this sphere, {@code 1.0} otherwise.
     */
    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        return xDiff * xDiff + yDiff * yDiff + zDiff * zDiff <= radiusSq ? coefficient : 1.0D;
    }

    public static List<Avoidance> create(IPlayerContext ctx) {
        if (!Baritone.settings().avoidance.value) {
            return Collections.emptyList();
        }
        List<Avoidance> res = new ArrayList<>();
        double mobSpawnerCoeff = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        double mobCoeff = Baritone.settings().mobAvoidanceCoefficient.value;
        if (mobSpawnerCoeff != 1.0D) {
            ctx.worldData().getCachedWorld().getLocationsOf("mob_spawner", 1, ctx.playerFeet().x, ctx.playerFeet().z, 2)
                    .forEach(mobspawner -> res.add(new Avoidance(mobspawner, mobSpawnerCoeff, Baritone.settings().mobSpawnerAvoidanceRadius.value)));
        }
        if (mobCoeff != 1.0D) {
            ctx.entitiesStream()
                    .filter(entity -> entity instanceof Mob)
                    .filter(entity -> (!(entity instanceof Spider)) || ctx.player().getLightLevelDependentMagicValue() < 0.5)
                    .filter(entity -> !(entity instanceof ZombifiedPiglin) || ((ZombifiedPiglin) entity).getLastHurtByMob() != null)
                    .filter(entity -> !(entity instanceof Enderman) || ((Enderman) entity).isCreepy())
                    .forEach(entity -> res.add(new Avoidance(entity.blockPosition(), mobCoeff, Baritone.settings().mobAvoidanceRadius.value)));
        }
        double crystalCoeff = Baritone.settings().crystalAvoidanceCoefficient.value;
        int crystalRadius = Baritone.settings().crystalAvoidanceRadius.value;
        double explosiveCoeff = Baritone.settings().explosiveAvoidanceCoefficient.value;
        int explosiveRadius = Baritone.settings().explosiveAvoidanceRadius.value;
        double playerCoeff = Baritone.settings().playerAvoidanceCoefficient.value;
        int playerRadius = Baritone.settings().playerAvoidanceRadius.value;
        if ((crystalCoeff != 1.0D && crystalRadius > 0)
                || (explosiveCoeff != 1.0D && explosiveRadius > 0)
                || (playerCoeff != 1.0D && playerRadius > 0)) {
            ctx.entitiesStream().forEach(entity -> {
                if (entity instanceof EndCrystal && crystalCoeff != 1.0D && crystalRadius > 0) {
                    res.add(new Avoidance(entity.blockPosition(), crystalCoeff, crystalRadius));
                } else if ((entity instanceof PrimedTnt || entity instanceof WitherBoss || entity instanceof WitherSkull)
                        && explosiveCoeff != 1.0D && explosiveRadius > 0) {
                    res.add(new Avoidance(entity.blockPosition(), explosiveCoeff, explosiveRadius));
                } else if (entity instanceof Player other
                        && entity != ctx.player()
                        && playerCoeff != 1.0D
                        && playerRadius > 0
                        && !BaritoneAPI.getFriendFilter().test(other.getUUID())) {
                    res.add(new Avoidance(entity.blockPosition(), playerCoeff, playerRadius));
                }
            });
        }
        return res;
    }
}
