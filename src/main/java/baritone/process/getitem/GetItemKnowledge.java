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

package baritone.process.getitem;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.BaritoneLog;
import baritone.api.utils.IPlayerContext;
import baritone.cache.WorldData;
import baritone.process.getitem.knowledge.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Get Item world knowledge capture (plan A11-A20): indexes every container screen you or the bot open (contents,
 * custom name), merchant offers, nearby stations, farms and hazards, and persists it per world (A19). Runs for
 * manual play too, so chests you open by hand become usable stock.
 */
public final class GetItemKnowledge implements AbstractGameEventListener {
    private final Baritone baritone;
    private final IPlayerContext ctx;
    private KnowledgeBase kb = new KnowledgeBase();
    private Path file;
    private long tick;
    private BlockPos lastBlock;
    private Entity lastEntity;
    private Screen indexedScreen;
    private BlockPos openPos;
    private long lastSave;

    private static final Set<Block> STATION_BLOCKS = new HashSet<>();
    private static final Set<Block> CONTAINER_BLOCKS = new HashSet<>(Arrays.asList(
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST));
    private static final Set<Block> CROPS = new HashSet<>(Arrays.asList(
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS, Blocks.SUGAR_CANE, Blocks.PUMPKIN_STEM,
            Blocks.MELON_STEM, Blocks.NETHER_WART, Blocks.SWEET_BERRY_BUSH, Blocks.ATTACHED_MELON_STEM, Blocks.ATTACHED_PUMPKIN_STEM,
            Blocks.COCOA, Blocks.BAMBOO, Blocks.CACTUS));
    private volatile Census census = Census.EMPTY;
    /** Last fishing-bobber splash heard (A29 bite detection): nanoTime and position. */
    private volatile long splashNanos;
    private volatile double splashX, splashY, splashZ;

    static {
        for (String s : GameData.STATIONS) STATION_BLOCKS.add(GameData.block(s));
    }

    public GetItemKnowledge(Baritone baritone) {
        this.baritone = baritone;
        this.ctx = baritone.getPlayerContext();
    }

    public KnowledgeBase base() {
        return kb;
    }

    public Census census() {
        return census;
    }

    /** True when a bobber splash was heard after {@code sinceNanos} within {@code r} blocks of (x, y, z). */
    public boolean splashSince(long sinceNanos, double x, double y, double z, double r) {
        if (splashNanos <= sinceNanos) return false;
        double dx = splashX - x, dy = splashY - y, dz = splashZ - z;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    @Override
    public void onReceivePacket(baritone.api.event.events.PacketEvent event) {
        if (event.getState() == EventState.PRE && event.getPacket() instanceof net.minecraft.network.protocol.game.ClientboundSoundPacket p
                && p.getSound().value() == net.minecraft.sounds.SoundEvents.FISHING_BOBBER_SPLASH) {
            splashX = p.getX();
            splashY = p.getY();
            splashZ = p.getZ();
            splashNanos = System.nanoTime();
        }
    }

    /** Farm type key for a crop block (attached stems count as their stem's farm). */
    static String farmType(Block b) {
        if (b == Blocks.ATTACHED_MELON_STEM) return "minecraft:melon_stem";
        if (b == Blocks.ATTACHED_PUMPKIN_STEM) return "minecraft:pumpkin_stem";
        return GameData.id(b);
    }

    /** A39 growth-stage awareness: is the plant at {@code p} ready to harvest now? */
    private boolean ripe(BlockPos p, net.minecraft.world.level.block.state.BlockState st) {
        Block b = st.getBlock();
        if (b instanceof CropBlock crop) return crop.isMaxAge(st);
        if (b == Blocks.NETHER_WART) return st.getValue(NetherWartBlock.AGE) >= 3;
        if (b == Blocks.SWEET_BERRY_BUSH) return st.getValue(SweetBerryBushBlock.AGE) >= 2;
        if (b == Blocks.COCOA) return st.getValue(CocoaBlock.AGE) >= 2;
        if (b == Blocks.ATTACHED_MELON_STEM || b == Blocks.ATTACHED_PUMPKIN_STEM) return true;
        if (b == Blocks.SUGAR_CANE || b == Blocks.BAMBOO || b == Blocks.CACTUS) return ctx.world().getBlockState(p.below()).getBlock() == b;
        return false;
    }

    private void takeCensus(BlockPos me, long now) {
        Map<String, double[]> mobs = new HashMap<>();
        Map<String, double[]> sheep = new HashMap<>();
        Map<String, double[]> cows = new HashMap<>();
        for (Entity e : ctx.world().getEntities((Entity) null, new AABB(me).inflate(48), x -> x instanceof net.minecraft.world.entity.LivingEntity && x.isAlive())) {
            if (e == ctx.player() || e instanceof net.minecraft.world.entity.player.Player) continue;
            if (e instanceof net.minecraft.world.entity.AgeableMob am && am.isBaby()) continue;
            double d = Math.sqrt(e.distanceToSqr(ctx.player()));
            String type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            Census.see(mobs, type, d);
            if (e instanceof net.minecraft.world.entity.animal.sheep.Sheep sh && sh.readyForShearing()) {
                Census.see(sheep, sh.getColor().getName(), d);
            }
            if (type.equals("minecraft:cow")) Census.see(cows, "cow", d);
        }
        double water = -1;
        for (BlockPos w : baritone.getGetItemProcess().data().findBlocks(Collections.singleton(Blocks.WATER), 24)) {
            if (!ctx.world().getFluidState(w).isSource() || !ctx.world().getBlockState(w.above()).isAir()) continue;
            int open = 0;
            for (BlockPos n : new BlockPos[]{w.north(), w.south(), w.east(), w.west()}) if (ctx.world().getFluidState(n).isSource()) open++;
            if (open < 2) continue;
            double d = Math.sqrt(w.distSqr(me));
            if (water < 0 || d < water) water = d;
        }
        census = new Census(mobs, sheep, cows.get("cow"), water, now);
    }

    public ProtectPolicy policy() {
        Set<String> items = new HashSet<>();
        for (var i : Baritone.settings().getItemProtectItems.value) items.add(GameData.id(i));
        return new ProtectPolicy(Baritone.settings().getItemProtectChestNames.value, items,
                Baritone.settings().getItemProtectChestPositions.value, Baritone.settings().getItemUseEnderChest.value);
    }

    public static boolean isContainerBlock(Block b) {
        return CONTAINER_BLOCKS.contains(b) || b instanceof ShulkerBoxBlock;
    }

    static String typeOf(Block b) {
        if (b == Blocks.ENDER_CHEST) return ContainerIndex.ENDER_CHEST;
        if (b == Blocks.BARREL) return ContainerIndex.BARREL;
        if (b instanceof ShulkerBoxBlock) return ContainerIndex.SHULKER;
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST) return ContainerIndex.CHEST;
        return ContainerIndex.OTHER;
    }

    /** Custom name of a container screen, or null for the default (translated) title. */
    public static String customName(Component title) {
        if (title == null || title.getContents() instanceof TranslatableContents) return null;
        String s = title.getString();
        return s.isBlank() ? null : s;
    }

    // ---------------------------------------------------------------- events

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.OUT || event.getState() != EventState.PRE) {
            if (event.getType() == TickEvent.Type.OUT) unload();
            return;
        }
        if (!Baritone.settings().getItemKnowledge.value || ctx.player() == null || ctx.world() == null) return;
        tick++;
        ensureLoaded();
        HitResult hit = ctx.objectMouseOver();
        Screen screen = ctx.minecraft().gui.screen();
        if (screen == null) {
            if (hit instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK) lastBlock = bh.getBlockPos();
            else if (hit instanceof EntityHitResult eh) lastEntity = eh.getEntity();
            indexedScreen = null;
            openPos = null;
        } else if (tick % 5 == 0 || screen != indexedScreen) {
            captureScreen(screen);
        }
        if (tick % 100 == 0) scan();
        if (kb.dirty() && tick - lastSave > 1200) save();
    }

    /** Remember the goal to resume after a disconnect (A50). */
    public void setPending(String goal, int amount) {
        if (!Objects.equals(kb.pendingGoal, goal) || kb.pendingAmount != amount) {
            kb.pendingGoal = goal;
            kb.pendingAmount = amount;
            kb.markDirty();
        }
    }

    // ---------------------------------------------------------------- capture

    private void captureScreen(Screen screen) {
        AbstractContainerMenu menu = ctx.player().containerMenu;
        if (menu instanceof MerchantMenu mm) {
            if (screen != indexedScreen) captureMerchant(mm);
            indexedScreen = screen;
            return;
        }
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) return;
        if (screen != indexedScreen) {
            openPos = lastBlock != null && isContainerBlock(ctx.world().getBlockState(lastBlock).getBlock()) ? lastBlock : null;
            indexedScreen = screen;
        }
        if (openPos == null) return;
        indexContainer(openPos, menu, customName(screen.getTitle()));
    }

    /** Index the open container menu as the container at {@code pos} (also used by the tasks). */
    public void indexContainer(BlockPos pos, AbstractContainerMenu menu, String name) {
        int size = menu instanceof ChestMenu cm ? cm.getRowCount() * 9 : 27;
        Map<String, Integer> items = new TreeMap<>();
        int free = 0;
        for (int i = 0; i < size && i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) free++;
            else items.merge(GameData.id(s.getItem()), s.getCount(), Integer::sum);
        }
        Block b = ctx.world().getBlockState(pos).getBlock();
        String dim = dimension();
        ContainerIndex.Container c = new ContainerIndex.Container(pos.getX(), pos.getY(), pos.getZ(), dim, typeOf(b), name, items, free, size, System.currentTimeMillis());
        ContainerIndex.Container old = kb.containers.get(dim, pos.getX(), pos.getY(), pos.getZ());
        if (old == null || !old.items.equals(items) || !Objects.equals(old.name, name) || old.freeSlots != free) kb.markDirty();
        kb.containers.record(c);
        // double chests: index the other half under the same contents so either half is found
        if (size == 54) {
            for (BlockPos o : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                if (ctx.world().getBlockState(o).getBlock() == b) {
                    kb.containers.remove(dim, o.getX(), o.getY(), o.getZ());
                }
            }
        }
    }

    private void captureMerchant(MerchantMenu mm) {
        if (!(lastEntity instanceof AbstractVillager av)) return;
        VillagerMemory.Villager v = new VillagerMemory.Villager();
        v.uuid = av.getUUID().toString();
        if (av instanceof Villager vil) {
            v.profession = vil.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("villager");
            v.level = vil.getVillagerData().level();
        } else {
            v.profession = "wandering_trader";
        }
        BlockPos p = av.blockPosition();
        v.x = p.getX();
        v.y = p.getY();
        v.z = p.getZ();
        v.dim = dimension();
        v.seen = System.currentTimeMillis();
        for (MerchantOffer o : mm.getOffers()) {
            ItemStack a = o.getCostA(), b = o.getCostB(), r = o.getResult();
            if (a.isEmpty() || r.isEmpty()) continue;
            v.offers.add(new VillagerMemory.Offer(GameData.id(a.getItem()), a.getCount(), b.isEmpty() ? null : GameData.id(b.getItem()),
                    b.getCount(), GameData.id(r.getItem()), r.getCount(), o.getUses(), o.getMaxUses()));
        }
        kb.villagers.record(v);
        kb.markDirty();
    }

    // ---------------------------------------------------------------- periodic scan

    private void scan() {
        GameData data = baritone.getGetItemProcess().data();
        String dim = dimension();
        BlockPos me = ctx.playerFeet();
        long now = System.currentTimeMillis();
        // stations (A12), and forget ones that are gone
        Set<BlockPos> stations = new HashSet<>(data.findBlocks(STATION_BLOCKS, 24));
        for (BlockPos p : stations) kb.stations.record(GameData.id(ctx.world().getBlockState(p).getBlock()), p.getX(), p.getY(), p.getZ(), dim, now);
        for (StationRegistry.Station s : kb.stations.near(null, dim, me.getX(), me.getY(), me.getZ(), 20)) {
            if (!stations.contains(new BlockPos(s.x, s.y, s.z)) && loaded(s.x, s.z)) {
                kb.stations.remove(dim, s.x, s.y, s.z);
                kb.markDirty();
            }
        }
        takeCensus(me, now);
        // containers that were broken
        for (ContainerIndex.Container c : kb.containers.all()) {
            if (!Objects.equals(c.dim, dim) || c.dist2(me.getX(), me.getY(), me.getZ()) > 32 * 32 || !loaded(c.x, c.z)) continue;
            if (!isContainerBlock(ctx.world().getBlockState(new BlockPos(c.x, c.y, c.z)).getBlock())) {
                kb.containers.remove(dim, c.x, c.y, c.z);
                kb.markDirty();
            }
        }
        if (tick % 400 == 0) {
            // farms (A18)
            List<BlockPos> crops = data.findBlocks(CROPS, 32);
            Map<String, List<int[]>> byType = new HashMap<>();
            Map<String, List<int[]>> ripeByType = new HashMap<>();
            for (BlockPos p : crops.subList(0, Math.min(400, crops.size()))) {
                var st = ctx.world().getBlockState(p);
                String type = farmType(st.getBlock());
                int[] xyz = {p.getX(), p.getY(), p.getZ()};
                byType.computeIfAbsent(type, k -> new ArrayList<>()).add(xyz);
                if (ripe(p, st)) ripeByType.computeIfAbsent(type, k -> new ArrayList<>()).add(xyz);
            }
            for (Map.Entry<String, List<int[]>> e : byType.entrySet()) {
                kb.farms.update(e.getKey(), false, e.getValue(), ripeByType.getOrDefault(e.getKey(), Collections.emptyList()), dim, me.getX(), me.getZ(), 32, now);
            }
            Map<String, List<int[]>> animals = new HashMap<>();
            for (Entity e : ctx.world().getEntities((Entity) null, new AABB(me).inflate(32), x -> x instanceof Animal)) {
                animals.computeIfAbsent(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(), k -> new ArrayList<>())
                        .add(new int[]{e.getBlockX(), e.getBlockY(), e.getBlockZ()});
            }
            for (Map.Entry<String, List<int[]>> e : animals.entrySet()) kb.farms.update(e.getKey(), true, e.getValue(), dim, me.getX(), me.getZ(), 32, now);
            kb.markDirty();
        }
        if (Baritone.settings().getItemAvoidHazards.value) {
            // hazards (A20): lava (sampled), spawners, mob hotspots
            List<BlockPos> lava = data.findBlocks(Collections.singleton(Blocks.LAVA), 16);
            for (int i = 0; i < lava.size() && i < 64; i += 4) {
                BlockPos p = lava.get(i);
                kb.hazards.add(HazardMap.Type.LAVA, p.getX(), p.getY(), p.getZ(), dim, 4, 6, now);
            }
            for (BlockPos p : data.findBlocks(Collections.singleton(Blocks.SPAWNER), 24)) {
                kb.hazards.add(HazardMap.Type.SPAWNER, p.getX(), p.getY(), p.getZ(), dim, 12, 10, now);
            }
            List<Entity> mobs = ctx.world().getEntities((Entity) null, new AABB(me).inflate(24), x -> x instanceof Enemy && x.isAlive());
            if (mobs.size() >= 4) {
                double sx = 0, sy = 0, sz = 0;
                for (Entity m : mobs) {
                    sx += m.getX();
                    sy += m.getY();
                    sz += m.getZ();
                }
                kb.hazards.add(HazardMap.Type.MOBS, (int) (sx / mobs.size()), (int) (sy / mobs.size()), (int) (sz / mobs.size()), dim, 16, 4, now);
            }
            kb.hazards.expire(now, 5 * 60_000L);
        }
    }

    private boolean loaded(int x, int z) {
        return ctx.world().getChunkSource().hasChunk(x >> 4, z >> 4);
    }

    private String dimension() {
        return ctx.world().dimension().identifier().toString();
    }

    // ---------------------------------------------------------------- persistence (A19)

    private Path fileFor() {
        WorldData wd = baritone.getWorldProvider().getCurrentWorld();
        if (wd == null || wd.directory == null) return null;
        Path parent = wd.directory.getParent();
        return (parent == null ? wd.directory : parent).resolve("getitem.json");
    }

    private void ensureLoaded() {
        Path f = fileFor();
        if (f == null || f.equals(file)) return;
        if (file != null) save();
        file = f;
        kb = new KnowledgeBase();
        if (Files.exists(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                if (!kb.read(r)) BaritoneLog.CACHE.info("Get Item: ignored unreadable knowledge file " + f);
            } catch (IOException | RuntimeException e) {
                BaritoneLog.CACHE.info("Get Item: couldn't read " + f + ": " + e);
                kb = new KnowledgeBase();
            }
        }
        lastSave = tick;
        baritone.getGetItemProcess().onKnowledgeLoaded(kb);
    }

    public void save() {
        lastSave = tick;
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                kb.write(w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            BaritoneLog.CACHE.info("Get Item: couldn't save " + file + ": " + e);
        }
    }

    private void unload() {
        if (file == null) return;
        baritone.getGetItemProcess().onDisconnect();
        save();
        file = null;
        kb = new KnowledgeBase();
        lastBlock = null;
        lastEntity = null;
    }
}
