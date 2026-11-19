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

package baritone.process.getitem.safety;

/**
 * Safety monitor (plan A45/A46): decides each tick whether Get Item may keep working. Hostiles nearby -> flee or
 * pause (Meteor's CombatBrain may take over while paused); low food -> eat; low health -> wait for regen;
 * optionally sit out the night on the surface.
 */
public final class SafetyMonitor {
    public enum Action { OK, EAT, PAUSE, FLEE }

    public enum HostileAction {
        FLEE, PAUSE, IGNORE;

        public static HostileAction parse(String s) {
            if (s == null) return FLEE;
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "pause":
                    return PAUSE;
                case "ignore":
                    return IGNORE;
                default:
                    return FLEE;
            }
        }
    }

    public static final class Inputs {
        public double health = 20, maxHealth = 20;
        public int food = 20;
        public boolean hasFood;
        public int hostilesNear;
        public double nearestHostile = 99;
        public boolean night;
        public boolean skyVisible;
        public boolean inLava;
        public boolean eating;
    }

    public static final class Settings {
        public boolean autoEat = true;
        public int minFood = 6;
        public double minHealth = 8;
        public HostileAction hostileAction = HostileAction.FLEE;
        public double hostileRadius = 10;
        public boolean avoidNight;
    }

    public static final class Verdict {
        public final Action action;
        public final String reason;

        Verdict(Action action, String reason) {
            this.action = action;
            this.reason = reason;
        }

        public boolean ok() {
            return action == Action.OK;
        }
    }

    private SafetyMonitor() {
    }

    public static Verdict evaluate(Inputs in, Settings s) {
        if (in.eating) return new Verdict(Action.EAT, "eating");
        boolean hostile = in.hostilesNear > 0 && in.nearestHostile <= s.hostileRadius && s.hostileAction != HostileAction.IGNORE;
        if (hostile) {
            // a hungry player can't sprint away; fleeing is still better than standing still
            return new Verdict(s.hostileAction == HostileAction.PAUSE ? Action.PAUSE : Action.FLEE,
                    in.hostilesNear + " hostile" + (in.hostilesNear > 1 ? "s" : "") + " within " + Math.round(in.nearestHostile) + "m");
        }
        if (s.autoEat && in.hasFood && (in.food <= s.minFood || (in.health < s.minHealth && in.food < 18))) {
            return new Verdict(Action.EAT, "hungry (food " + in.food + ")");
        }
        if (in.health < s.minHealth && in.food >= 18) {
            return new Verdict(Action.PAUSE, "low health (" + Math.round(in.health) + "), regenerating");
        }
        if (s.avoidNight && in.night && in.skyVisible) return new Verdict(Action.PAUSE, "night on the surface");
        return new Verdict(Action.OK, null);
    }
}
