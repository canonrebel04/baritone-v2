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

package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import dev.babbaj.pathfinder.PathSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author Brady
 */
public final class UnpackedSegment {

    private final List<BetterBlockPos> positions;
    private final boolean finished;

    public UnpackedSegment(Stream<BetterBlockPos> path, boolean finished) {
        // A Stream is single-use. The path cache and segment splice both revisit the same
        // segment, so materialize here — Stream.concat on a consumed stream is what stuck
        // recalculation ("stream has already been operated upon or closed").
        this.positions = new ArrayList<>();
        path.forEach(this.positions::add);
        this.finished = finished;
    }

    private UnpackedSegment(List<BetterBlockPos> positions, boolean finished) {
        this.positions = positions;
        this.finished = finished;
    }

    public UnpackedSegment append(Stream<BetterBlockPos> other, boolean otherFinished) {
        // The new segment is only finished if the one getting added on is
        final List<BetterBlockPos> combined = new ArrayList<>(this.positions);
        other.forEach(combined::add);
        return new UnpackedSegment(combined, otherFinished);
    }

    public UnpackedSegment prepend(Stream<BetterBlockPos> other) {
        final List<BetterBlockPos> combined = new ArrayList<>();
        other.forEach(combined::add);
        combined.addAll(this.positions);
        return new UnpackedSegment(combined, this.finished);
    }

    public List<BetterBlockPos> collect() {
        final List<BetterBlockPos> path = new ArrayList<>(this.positions);

        // Remove backtracks
        final Map<BetterBlockPos, Integer> positionFirstSeen = new HashMap<>();
        for (int i = 0; i < path.size(); i++) {
            BetterBlockPos pos = path.get(i);
            if (positionFirstSeen.containsKey(pos)) {
                int j = positionFirstSeen.get(pos);
                while (i > j) {
                    path.remove(i);
                    i--;
                }
            } else {
                positionFirstSeen.put(pos, i);
            }
        }

        return path;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public static UnpackedSegment from(final PathSegment segment) {
        return new UnpackedSegment(
                Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong),
                segment.finished
        );
    }
}
