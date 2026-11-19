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

package baritone.process.getitem.knowledge;

import java.util.*;

/** Villager/trade memory (plan A17): what each villager sold the last time its trade screen was open. */
public final class VillagerMemory {
    public static final int MAX = 128;

    public static final class Offer {
        public String costA;
        public int countA;
        public String costB;
        public int countB;
        public String result;
        public int resultCount;
        public int uses, maxUses;

        public Offer() {
        }

        public Offer(String costA, int countA, String costB, int countB, String result, int resultCount, int uses, int maxUses) {
            this.costA = costA;
            this.countA = countA;
            this.costB = costB;
            this.countB = countB;
            this.result = result;
            this.resultCount = resultCount;
            this.uses = uses;
            this.maxUses = maxUses;
        }

        public boolean outOfStock() {
            return maxUses > 0 && uses >= maxUses;
        }

        public String describe() {
            String s = countA + "x " + costA.replace("minecraft:", "");
            if (costB != null && countB > 0) s += " + " + countB + "x " + costB.replace("minecraft:", "");
            return s + " -> " + resultCount + "x " + result.replace("minecraft:", "") + (outOfStock() ? " (sold out)" : "");
        }
    }

    public static final class Villager {
        public String uuid;
        public String profession;
        public int level;
        public int x, y, z;
        public String dim;
        public List<Offer> offers = new ArrayList<>();
        public long seen;
    }

    private final LinkedHashMap<String, Villager> byId = new LinkedHashMap<>(16, 0.75f, true);

    public synchronized void record(Villager v) {
        byId.remove(v.uuid);
        byId.put(v.uuid, v);
        while (byId.size() > MAX) {
            Iterator<String> it = byId.keySet().iterator();
            it.next();
            it.remove();
        }
    }

    public synchronized List<Villager> all() {
        return new ArrayList<>(byId.values());
    }

    /** Known offers selling {@code item}, cheapest (by first cost count) first: "villager pos: offer". */
    public synchronized List<String> sellers(String item) {
        List<Object[]> rows = new ArrayList<>();
        for (Villager v : byId.values()) {
            for (Offer o : v.offers) {
                if (item.equals(o.result)) {
                    rows.add(new Object[]{o.countA, v.profession + " (lvl " + v.level + ") at " + v.x + "," + v.y + "," + v.z + ": " + o.describe()});
                }
            }
        }
        rows.sort(Comparator.comparingInt(r -> (Integer) r[0]));
        List<String> out = new ArrayList<>();
        for (Object[] r : rows) out.add((String) r[1]);
        return out;
    }
}
