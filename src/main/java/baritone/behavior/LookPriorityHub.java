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

import baritone.api.behavior.look.ILookPriorityHub;
import baritone.api.behavior.look.ILookRequest;
import baritone.api.utils.Rotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Implementation of {@link ILookPriorityHub} that manages and resolves rotation requests from multiple sources.
 */
public class LookPriorityHub implements ILookPriorityHub {

    private final List<LookRequest> requests = new CopyOnWriteArrayList<>();

    /**
     * Advances the internal tick state of all active rotation requests.
     * Decrements lifetime counters and removes expired or released requests.
     */
    public void onTick() {
        for (LookRequest req : this.requests) {
            if (req.isReleased()) {
                this.requests.remove(req);
                continue;
            }
            if (req.getTicksRemaining() > 0) {
                req.decrementLifetime();
                if (req.getTicksRemaining() <= 0) {
                    req.release();
                    this.requests.remove(req);
                }
            }
        }
    }

    @Override
    public ILookRequest requestRotation(double priority, Rotation rotation, int lifetimeTicks) {
        LookRequest req = new LookRequest(this, priority, rotation, null, false, lifetimeTicks);
        this.requests.add(0, req);
        return req;
    }

    @Override
    public ILookRequest requestRotation(double priority, Supplier<Rotation> rotationSupplier, int lifetimeTicks) {
        LookRequest req = new LookRequest(this, priority, null, rotationSupplier, false, lifetimeTicks);
        this.requests.add(0, req);
        return req;
    }

    @Override
    public ILookRequest requestRotation(double priority, Rotation rotation, boolean blockInteract, int lifetimeTicks) {
        LookRequest req = new LookRequest(this, priority, rotation, null, blockInteract, lifetimeTicks);
        this.requests.add(0, req);
        return req;
    }

    @Override
    public ILookRequest requestRotation(double priority, Supplier<Rotation> rotationSupplier, boolean blockInteract, int lifetimeTicks) {
        LookRequest req = new LookRequest(this, priority, null, rotationSupplier, blockInteract, lifetimeTicks);
        this.requests.add(0, req);
        return req;
    }

    @Override
    public Optional<ILookRequest> getHighestPriorityRequest() {
        LookRequest highest = null;
        for (LookRequest req : this.requests) {
            if (req.isActive()) {
                if (highest == null || req.getPriority() > highest.getPriority()) {
                    if (req.getRotation() != null) {
                        highest = req;
                    }
                }
            }
        }
        return Optional.ofNullable(highest);
    }

    @Override
    public boolean hasActiveRequests() {
        for (LookRequest req : this.requests) {
            if (req.isActive()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ILookRequest> getActiveRequests() {
        List<ILookRequest> active = new ArrayList<>();
        for (LookRequest req : this.requests) {
            if (req.isActive()) {
                active.add(req);
            }
        }
        return Collections.unmodifiableList(active);
    }

    @Override
    public void release(ILookRequest request) {
        if (request instanceof LookRequest) {
            ((LookRequest) request).release();
        }
        this.requests.remove(request);
    }

    @Override
    public void releaseAll() {
        for (LookRequest req : this.requests) {
            req.release();
        }
        this.requests.clear();
    }

    @Override
    public void clear() {
        this.requests.clear();
    }

    /**
     * Internal implementation of {@link ILookRequest}.
     */
    public static class LookRequest implements ILookRequest {

        private final LookPriorityHub hub;
        private double priority;
        private Rotation rotation;
        private Supplier<Rotation> rotationSupplier;
        private boolean blockInteract;
        private int ticksRemaining;
        private Boolean smooth;
        private boolean silent;
        private volatile boolean released;

        public LookRequest(LookPriorityHub hub, double priority, Rotation rotation, Supplier<Rotation> rotationSupplier, boolean blockInteract, int ticksRemaining) {
            this.hub = hub;
            this.priority = priority;
            this.rotation = rotation;
            this.rotationSupplier = rotationSupplier;
            this.blockInteract = blockInteract;
            this.ticksRemaining = ticksRemaining;
            this.released = false;
        }

        public void decrementLifetime() {
            if (this.ticksRemaining > 0) {
                this.ticksRemaining--;
            }
        }

        @Override
        public double getPriority() {
            return this.priority;
        }

        @Override
        public void setPriority(double priority) {
            this.priority = priority;
        }

        @Override
        public Rotation getRotation() {
            if (this.rotationSupplier != null) {
                try {
                    return this.rotationSupplier.get();
                } catch (Exception ignored) {
                    return null;
                }
            }
            return this.rotation;
        }

        @Override
        public void setRotation(Rotation rotation) {
            this.rotation = rotation;
            this.rotationSupplier = null;
        }

        @Override
        public Supplier<Rotation> getRotationSupplier() {
            return this.rotationSupplier;
        }

        @Override
        public void setRotationSupplier(Supplier<Rotation> rotationSupplier) {
            this.rotationSupplier = rotationSupplier;
            this.rotation = null;
        }

        @Override
        public int getTicksRemaining() {
            return this.ticksRemaining;
        }

        @Override
        public void setTicksRemaining(int ticksRemaining) {
            this.ticksRemaining = ticksRemaining;
        }

        @Override
        public boolean isBlockInteract() {
            return this.blockInteract;
        }

        @Override
        public void setBlockInteract(boolean blockInteract) {
            this.blockInteract = blockInteract;
        }

        @Override
        public Boolean isSmooth() {
            return this.smooth;
        }

        @Override
        public void setSmooth(Boolean smooth) {
            this.smooth = smooth;
        }

        @Override
        public boolean isSilent() {
            return this.silent;
        }

        @Override
        public void setSilent(boolean silent) {
            this.silent = silent;
        }

        @Override
        public boolean isActive() {
            return !this.released && (this.ticksRemaining < 0 || this.ticksRemaining > 0);
        }

        @Override
        public boolean isReleased() {
            return this.released;
        }

        @Override
        public void release() {
            this.released = true;
            if (this.hub != null) {
                this.hub.requests.remove(this);
            }
        }
    }
}
