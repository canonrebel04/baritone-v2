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

package baritone.process.getitem.tasks;

import baritone.Baritone;
import baritone.api.process.PathingCommand;
import baritone.process.getitem.GameData;
import baritone.process.getitem.plan.PlanStep;
import baritone.process.getitem.plan.Source;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A30 hunting and A35 shearing / milking. Shearing and milking are plain use-interactions Baritone does itself.
 * Hunting only walks up to the target and publishes it ({@link #huntTarget()}): the attack is done by Meteor's
 * Get Item module (kill handler), so Baritone never attacks. Drops are picked up after each kill.
 */
public final class AnimalTask extends GatherTask {
    private final Source.Kind kind;
    private Entity current;
    private Vec3 lastDeath;
    private int collect;
    private int done;

    public AnimalTask(Baritone baritone, GameData data, PlanStep step) {
        super(baritone, data, step);
        this.kind = step.source.kind;
    }

    /** Entity id Meteor should attack now, or -1. */
    public int huntTarget() {
        Entity e = current;
        return kind == Source.Kind.HUNT && e != null && e.isAlive() && ctx.player() != null && ctx.player().distanceTo(e) < 6 ? e.getId() : -1;
    }

    private boolean wanted(Entity e) {
        if (!(e instanceof LivingEntity) || !e.isAlive()) return false;
        if (e instanceof AgeableMob am && am.isBaby()) return false;
        switch (kind) {
            case SHEAR:
                return e instanceof Sheep sh && sh.readyForShearing() && sh.getColor().getName().equals(step.source.ref);
            case MILK:
                return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("minecraft:cow");
            default:
                return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(step.source.ref);
        }
    }

    private Entity nearest() {
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : ctx.world().getEntities((Entity) null, new AABB(ctx.playerFeet()).inflate(48), this::wanted)) {
            double d = e.distanceToSqr(ctx.player());
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    @Override
    protected PathingCommand onTick() {
        if (reached()) return finish();
        if (stalled() > 20 * 120) {
            return have() > target - step.count ? finish() : fail("no progress " + step.source.label.toLowerCase() + " for 2 minutes");
        }
        // pick up what the last kill / shear dropped
        if (lastDeath != null && collect-- > 0) {
            ItemEntity d = nearestDrop(lastDeath, 5);
            if (d != null) return pickUp(d);
        }
        if (kind == Source.Kind.HUNT && !baritone.getGetItemProcess().hasKillHandler()) {
            return fail("hunting needs Meteor's Get Item module (it does the attacking)");
        }
        if (current == null || !wanted(current)) {
            if (current != null && kind == Source.Kind.HUNT && !current.isAlive()) {
                lastDeath = current.position();
                collect = 100;
                done++;
                current = null;
                return pause();
            }
            current = nearest();
            if (current == null) {
                return have() > target - step.count || done > 0 ? finish() : fail("no " + step.source.ref.replace("minecraft:", "") + " nearby any more");
            }
        }
        if (done >= step.batches + 3) return finish(); // unlucky drops: re-plan
        double dist = ctx.player().distanceTo(current);
        if (kind == Source.Kind.HUNT) {
            // Meteor attacks once we're close; keep following
            return dist > 2.5 ? follow(current, 1) : pause();
        }
        if (dist > 2.8) return follow(current, 1);
        String tool = kind == Source.Kind.SHEAR ? "minecraft:shears" : "minecraft:bucket";
        if (!hold(is(tool))) return owns(tool) ? pause() : fail("no " + tool.replace("minecraft:", "") + " to use");
        look(current.position().add(0, current.getBbHeight() * 0.6, 0));
        if (interact(current)) {
            done++;
            lastDeath = current.position();
            collect = 60;
            current = null;
        }
        return pause();
    }

    @Override
    public void stop() {
        current = null;
    }
}
