# Graph Report - baritone-v2  (2026-08-31)

## Corpus Check
- 400 files · ~199,054 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3997 nodes · 11718 edges · 178 communities (135 shown, 43 thin omitted)
- Extraction: 90% EXTRACTED · 10% INFERRED · 0% AMBIGUOUS · INFERRED: 1114 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `8940a082`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Command
- .player
- net.minecraft.world.level.chunk.LevelChunk
- DummyArgConsumer
- StaticMask
- GuiClick.java
- .fillData
- net.minecraft.core.BlockPos
- IDatatypeContext
- ElytraBehavior
- IPlayerContext
- IWaypoint
- .execute
- Movement
- ProguardTask
- IArgConsumer
- CachedWorld
- ArgConsumer
- IPath
- ElytraProcess
- .getProvider
- BlockOptionalMetaLookup
- AbstractNodeCostSearch
- Goal
- BetterBlockPos
- MixinLootTable.java
- .settings
- Baritone
- net.minecraft.world.level.block.Block
- ICommand
- ExploreProcess
- .logDirect
- PathingBehavior
- net.minecraft.client.player.LocalPlayer
- PacketEvent
- .requireMax
- .execute
- GameEventHandler
- IBaritoneProcess
- LitematicaHelper.java
- MixinCommandSuggestionHelper.java
- ArgParserManager.java
- Settings
- IDatatypeFor
- EventState
- CutoffPath
- ISchematic
- PathManager
- Rotation
- BlockOptionalMeta.java
- Action
- ICommandArgument
- CachedChunk
- net.minecraft.core.Direction
- .getDatatypeFor
- Override
- net.minecraft.world.level.block.state.BlockState
- LitematicaSchematic
- org.spongepowered.asm.mixin.Mixin
- net.minecraft.world.level.Level
- GoalTwoBlocks
- IPlayerControllerMP
- ICustomGoalProcess
- IStaticSchematic
- AStarPathFinder.java
- AbstractAimProcessor
- SchematicaHelper.java
- Stateless
- GetToBlockProcess
- BuilderProcess
- MixinClientPlayerEntity.java
- CommandArgument
- ILookRequest
- AbstractGameEventListener
- Action
- java.lang.reflect.Type
- BaritoneAPI
- SpongeSchematic.java
- .execute
- FEATURES.md
- Override
- ICommandManager
- NetherPathfinderContext
- Selection
- CalculationContext
- BackfillProcess
- ChunkEvent
- PathEvent
- MutableMoveResult
- org.junit.Test
- GoalYLevel
- PathExecutor
- MovementAscend
- BlockStateInterfaceAccessWrapper
- InputOverrideHandler
- ICachedWorld
- LookBehavior.java
- Parser
- FabricMixinPlugin
- FarmProcess
- IPathingBehavior
- net.minecraft.core.Vec3i
- CompositeSchematic
- InventoryPauserProcess
- MixinRenderPipelines
- NeoForge Release Configuration
- NullElytraProcess
- MixinFireworkRocketEntity.java
- GoalInverted
- ActionCostsTest.java
- IEntityRenderManager
- InventoryBehavior
- net.minecraft.world.entity.Entity
- Forge Release Configuration
- MixinWorldRenderer.java
- IBaritoneProvider
- bolt.md
- GoalBlock
- net.minecraft.client.renderer.rendertype.RenderType
- CustomGoalProcess
- BaritonePlayerContext
- Baritone v2 - Release Information
- IWorldData
- GoalNear
- GoalXZ
- MixinItemStack
- BaritoneProvider
- Path
- MovementDownward
- ForceCancelCommand
- GoalRunAway
- PathingBlockType
- MovementFall
- Registry
- Overrideable
- SettingsUtil
- GoalStrictDirection
- FireworkBoost
- UnpackedSegment
- NotificationHelper
- [26.1] - 2026-05-21
- Contributor Covenant Code of Conduct
- ClickCommand
- GcCommand
- ComeCommand
- LitematicaCommand
- IBaritone
- MovementDescend
- SurfaceCommand
- MovementDiagonal
- BaritoneTweaker.java
- net.minecraft.client.multiplayer.ClientChunkCache
- gradlew
- BaritoneMixinConnector
- ExploreCommand.java
- MovementTraverse
- forge/src/main/java/baritone/launch/BaritoneForgeModXD.java
- neoforge/src/main/java/baritone/launch/BaritoneForgeModXD.java
- bug.md
- PathCalculationException
- RotationUtils
- Building from Source
- MovementOption
- IGuiScreen
- Release Files
- Installation Instructions
- MyChunkPos
- PlaceResult
- question.md
- Version Information
- DummyDatatypeContext

## God Nodes (most connected - your core abstractions)
1. `BetterBlockPos` - 212 edges
2. `IArgConsumer` - 182 edges
3. `IBaritone` - 168 edges
4. `CalculationContext` - 152 edges
5. `Baritone` - 139 edges
6. `Goal` - 103 edges
7. `Rotation` - 99 edges
8. `IPlayerContext` - 95 edges
9. `Command` - 81 edges
10. `Movement` - 77 edges

## Surprising Connections (you probably didn't know these)
- `BaritoneAPI` --references--> `IBaritoneProvider`  [EXTRACTED]
  src/api/java/baritone/api/BaritoneAPI.java → src/api/java/baritone/api/IBaritoneProvider.java
- `BaritoneAPI` --references--> `Settings`  [EXTRACTED]
  src/api/java/baritone/api/BaritoneAPI.java → src/api/java/baritone/api/Settings.java
- `Command` --references--> `IBaritone`  [EXTRACTED]
  src/api/java/baritone/api/command/Command.java → src/api/java/baritone/api/IBaritone.java
- `getWaypointNames()` --references--> `IBaritone`  [EXTRACTED]
  src/api/java/baritone/api/command/datatypes/ForWaypoints.java → src/api/java/baritone/api/IBaritone.java
- `getWaypoints()` --references--> `IBaritone`  [EXTRACTED]
  src/api/java/baritone/api/command/datatypes/ForWaypoints.java → src/api/java/baritone/api/IBaritone.java

## Import Cycles
- None detected.

## Communities (178 total, 43 thin omitted)

### Community 0 - "Command"
Cohesion: 0.07
Nodes (22): net.minecraft.ChatFormatting, net.minecraft.network.chat.ClickEvent, Command, Override, BlockById, INSTANCE, ForAxis, INSTANCE (+14 more)

### Community 1 - ".player"
Cohesion: 0.09
Nodes (7): Vec3, Vec3, RayTraceUtils, Deprecated, InventoryDelayTracker, BlockBreakHelper, BlockPlaceHelper

### Community 2 - "net.minecraft.world.level.chunk.LevelChunk"
Cohesion: 0.11
Nodes (13): it.unimi.dsi.fastutil.longs.Long2ObjectMap, java.lang.ref.SoftReference, java.util.concurrent.atomic.AtomicReferenceArray, net.minecraft.world.level.chunk.LevelChunk, org.spongepowered.asm.mixin.Shadow, SoftReference, Override, MixinChunkArray (+5 more)

### Community 4 - "StaticMask"
Cohesion: 0.05
Nodes (26): AbstractMask, Override, Mask, BinaryOperatorMask, Override, Static, Override, NotMask (+18 more)

### Community 5 - "GuiClick.java"
Cohesion: 0.08
Nodes (21): com.mojang.blaze3d.vertex.BufferBuilder, com.mojang.blaze3d.vertex.PoseStack, net.minecraft.client.gui.GuiGraphicsExtractor, net.minecraft.client.input.MouseButtonEvent, org.joml.Matrix4f, RenderEvent, AABB, Override (+13 more)

### Community 6 - ".fillData"
Cohesion: 0.18
Nodes (5): PrecomputedData, Ternary, MAYBE, NO, YES

### Community 7 - "net.minecraft.core.BlockPos"
Cohesion: 0.09
Nodes (25): net.minecraft.core.BlockPos, net.minecraft.world.level.block.state.properties.BooleanProperty, MovementStatus, CANCELED, FAILED, PREPPING, RUNNING, SUCCESS (+17 more)

### Community 8 - "IDatatypeContext"
Cohesion: 0.05
Nodes (45): get(), Override, tabComplete(), get(), Axis, Override, tabComplete(), ForBlockOptionalMeta (+37 more)

### Community 9 - "ElytraBehavior"
Cohesion: 0.11
Nodes (15): FloatArrayList, it.unimi.dsi.fastutil.floats.FloatArrayList, net.minecraft.world.phys.Vec3, Override, Pair, ClearViewKey, ElytraBehavior, IntTriFunction (+7 more)

### Community 10 - "IPlayerContext"
Cohesion: 0.11
Nodes (8): IPlayerContext, Deprecated, Vec3, Vec3, Override, Override, MovementState, MovementTarget

### Community 11 - "IWaypoint"
Cohesion: 0.08
Nodes (20): getByName(), IWaypoint, Tag, BED, DEATH, HOME, USER, IWaypointCollection (+12 more)

### Community 12 - ".execute"
Cohesion: 0.07
Nodes (9): Paginator, ExploreCommand, Override, FarmCommand, Override, Override, PickupCommand, Override (+1 more)

### Community 13 - "Movement"
Cohesion: 0.09
Nodes (23): Movement, apply(), apply0(), cost(), Moves, ASCEND_EAST, ASCEND_NORTH, ASCEND_SOUTH (+15 more)

### Community 14 - "ProguardTask"
Cohesion: 0.07
Nodes (13): BaritoneGradleTask, CreateDistTask, SuppressWarnings, ProguardTask, Determinizer, java.security.MessageDigest, JsonElement, org.gradle.api.DefaultTask (+5 more)

### Community 15 - "IArgConsumer"
Cohesion: 0.05
Nodes (11): IArgConsumer, AxisCommand, Override, CommandAlias, Override, Override, ReloadAllCommand, Override (+3 more)

### Community 16 - "CachedWorld"
Cohesion: 0.19
Nodes (5): java.util.concurrent.LinkedBlockingQueue, CachedWorld, BlockPos, Override, PackerThread

### Community 17 - "ArgConsumer"
Cohesion: 0.11
Nodes (3): ArgConsumer, Context, Override

### Community 18 - "IPath"
Cohesion: 0.09
Nodes (10): Long2DoubleOpenHashMap, IPath, IPathFinder, PathCalculationResult, Type, CANCELLATION, EXCEPTION, FAILURE (+2 more)

### Community 19 - "ElytraProcess"
Cohesion: 0.07
Nodes (19): MutableBlockPos, State, EXECUTING, GOAL_SET, NONE, PATH_REQUESTED, ElytraProcess, BlockPos (+11 more)

### Community 20 - ".getProvider"
Cohesion: 0.09
Nodes (20): ClientboundBlockUpdatePacket, ClientboundForgetLevelChunkPacket, ClientboundLevelChunkWithLightPacket, ClientboundPlayerCombatKillPacket, ClientboundSectionBlocksUpdatePacket, io.netty.channel.ChannelFutureListener, io.netty.channel.ChannelHandlerContext, net.minecraft.client.gui.screens.Screen (+12 more)

### Community 21 - "BlockOptionalMetaLookup"
Cohesion: 0.09
Nodes (33): LevelChunkSection, net.minecraft.world.level.ChunkPos, IWorldScanner, Override, ReplaceSchematic, BlockOptionalMetaLookup, Override, collectChunkSections() (+25 more)

### Community 22 - "AbstractNodeCostSearch"
Cohesion: 0.06
Nodes (16): it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap, Long2ObjectOpenHashMap, org.junit.runner.RunWith, Parameterized.Parameters, AbstractNodeCostSearch, Override, Override, BinaryHeapOpenSet (+8 more)

### Community 23 - "Goal"
Cohesion: 0.11
Nodes (17): it.unimi.dsi.fastutil.longs.LongOpenHashSet, Goal, GoalComposite, Override, Override, PathingCommand, PathingCommandType, CANCEL_AND_SET_GOAL (+9 more)

### Community 24 - "BetterBlockPos"
Cohesion: 0.14
Nodes (3): BetterBlockPos, Override, BetterBlockPosTest

### Community 25 - "MixinLootTable.java"
Cohesion: 0.50
Nodes (4): it.unimi.dsi.fastutil.objects.ObjectArrayList, net.minecraft.world.level.storage.loot.LootContext, ILootTable, MixinLootTable

### Community 26 - ".settings"
Cohesion: 0.16
Nodes (4): GoalThreeBlocks, BlockPos, Override, MineProcess

### Community 27 - "Baritone"
Cohesion: 0.09
Nodes (4): java.util.concurrent.ThreadPoolExecutor, IElytraProcess, Baritone, Override

### Community 28 - "net.minecraft.world.level.block.Block"
Cohesion: 0.07
Nodes (17): Builder, com.google.common.collect.ImmutableSet, net.minecraft.tags.TagKey, net.minecraft.world.item.Item, net.minecraft.world.item.ItemStack, net.minecraft.world.level.block.Block, get(), ItemById (+9 more)

### Community 29 - "ICommand"
Cohesion: 0.09
Nodes (12): net.minecraft.util.Tuple, CommandNotFoundException, Override, CommandUnhandledException, Override, ICommandException, ICommand, DefaultCommands (+4 more)

### Community 30 - "ExploreProcess"
Cohesion: 0.14
Nodes (12): BaritoneChunkCache, EitherChunk, ExploreProcess, IChunkFilter, BlockPos, LongOpenHashSet, Override, JsonChunkFilter (+4 more)

### Community 31 - ".logDirect"
Cohesion: 0.05
Nodes (13): Helper, BuildCommand, Override, ElytraCommand, Override, ExploreFilterCommand, Override, GoalCommand (+5 more)

### Community 32 - "PathingBehavior"
Cohesion: 0.11
Nodes (4): Override, PathingBehavior, Override, PathingControlManager

### Community 33 - "net.minecraft.client.player.LocalPlayer"
Cohesion: 0.10
Nodes (16): net.minecraft.client.player.LocalPlayer, net.minecraft.world.entity.player.Player, net.minecraft.world.InteractionHand, net.minecraft.world.InteractionResult, net.minecraft.world.inventory.ContainerInput, net.minecraft.world.level.GameType, net.minecraft.world.phys.BlockHitResult, get() (+8 more)

### Community 34 - "PacketEvent"
Cohesion: 0.25
Nodes (7): io.netty.channel.Channel, net.minecraft.network.Connection, net.minecraft.network.protocol.Packet, net.minecraft.network.protocol.PacketFlow, SuppressWarnings, PacketEvent, MixinNetworkManager

### Community 35 - ".requireMax"
Cohesion: 0.10
Nodes (8): BlacklistCommand, Override, GotoCommand, Override, Override, RenderCommand, Override, VersionCommand

### Community 36 - ".execute"
Cohesion: 0.15
Nodes (5): Deprecated, Override, SuppressWarnings, Setting, Deprecated

### Community 37 - "GameEventHandler"
Cohesion: 0.09
Nodes (4): IEventBus, IGameEventListener, GameEventHandler, Override

### Community 38 - "IBaritoneProcess"
Cohesion: 0.08
Nodes (5): IBaritoneProcess, IExploreProcess, IFarmProcess, IMineProcess, Override

### Community 39 - "LitematicaHelper.java"
Cohesion: 0.06
Nodes (20): com.google.common.collect.ImmutableMap, net.minecraft.world.level.block.Mirror, net.minecraft.world.level.block.Rotation, Override, MirroredSchematic, Override, RotatedSchematic, Override (+12 more)

### Community 40 - "MixinCommandSuggestionHelper.java"
Cohesion: 0.09
Nodes (14): com.mojang.brigadier.ParseResults, com.mojang.brigadier.suggestion.Suggestions, Context, Describe your suggestion, Final checklist, Settings, net.minecraft.client.gui.components.EditBox, ChatEvent (+6 more)

### Community 41 - "ArgParserManager.java"
Cohesion: 0.10
Nodes (16): Stated, IArgParserManager, CommandNoParserForTypeException, ICommandSystem, ArgParserManager, INSTANCE, getParserStated(), getParserStateless() (+8 more)

### Community 42 - "Settings"
Cohesion: 0.16
Nodes (11): java.lang.annotation.Retention, java.lang.annotation.Target, net.minecraft.network.chat.Component, org.slf4j.Logger, IBaritoneChatControl, Color, JavaOnly, Settings (+3 more)

### Community 43 - "IDatatypeFor"
Cohesion: 0.06
Nodes (23): net.minecraft.world.entity.EntityType, IArgParser, EntityClassById, INSTANCE, get(), Override, tabComplete(), IDatatype (+15 more)

### Community 44 - "EventState"
Cohesion: 0.09
Nodes (10): net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl, net.minecraft.client.multiplayer.CommonListenerCookie, PlayerUpdateEvent, TickEvent, Type, IN, OUT, EventState (+2 more)

### Community 45 - "CutoffPath"
Cohesion: 0.17
Nodes (6): CutoffPath, Override, Override, SplicedPath, Override, PathBase

### Community 46 - "ISchematic"
Cohesion: 0.08
Nodes (12): AbstractSchematic, Override, ISchematic, Axis, Override, MaskSchematic, Override, ShellSchematic (+4 more)

### Community 47 - "PathManager"
Cohesion: 0.16
Nodes (5): java.util.AbstractList, PathManager, Override, Vec3, NetherPath

### Community 48 - "Rotation"
Cohesion: 0.09
Nodes (11): Override, Rotation, LookBehavior, Mode, CLIENT, NONE, SERVER, QuantizedChase (+3 more)

### Community 49 - "BlockOptionalMeta.java"
Cohesion: 0.11
Nodes (16): java.lang.reflect.Method, LevelStorageAccess, net.minecraft.core.RegistryAccess, net.minecraft.server.level.ServerLevel, net.minecraft.server.MinecraftServer, net.minecraft.server.packs.VanillaPackResources, net.minecraft.world.flag.FeatureFlagSet, net.minecraft.world.level.CustomSpawner (+8 more)

### Community 50 - "Action"
Cohesion: 0.20
Nodes (10): Action, CLEAR, DELETE, GOAL, GOTO, INFO, LIST, RESTORE (+2 more)

### Community 51 - "ICommandArgument"
Cohesion: 0.12
Nodes (6): ICommandArgument, CommandInvalidArgumentException, getTarget(), Override, parseArg(), CommandArguments

### Community 52 - "CachedChunk"
Cohesion: 0.10
Nodes (7): Int2ObjectOpenHashMap, it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap, CachedChunk, BlockPos, CachedRegion, BlockPos, Override

### Community 53 - "net.minecraft.core.Direction"
Cohesion: 0.14
Nodes (6): net.minecraft.core.Direction, ISelection, ISelectionManager, transform(), Override, SelectionManager

### Community 54 - ".getDatatypeFor"
Cohesion: 0.18
Nodes (4): FindCommand, Override, Override, MineCommand

### Community 55 - "Override"
Cohesion: 0.12
Nodes (5): GoalAdjacent, GoalBreak, GoalPlace, JankyGoalComposite, Override

### Community 56 - "net.minecraft.world.level.block.state.BlockState"
Cohesion: 0.13
Nodes (8): net.minecraft.world.level.block.state.BlockState, net.minecraft.world.level.block.state.properties.Property, IBlockTypeAccess, BlockChangeEvent, Override, SubstituteSchematic, Override, StaticSchematic

### Community 57 - "LitematicaSchematic"
Cohesion: 0.15
Nodes (8): net.minecraft.nbt.CompoundTag, net.minecraft.nbt.ListTag, CompoundTag, Override, Vec3i, LitematicaBitArray, LitematicaSchematic, MCEditSchematic

### Community 58 - "org.spongepowered.asm.mixin.Mixin"
Cohesion: 0.10
Nodes (14): org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable, org.spongepowered.asm.mixin.Mixin, BlockInteractEvent, Type, START_BREAK, USE, RotationMoveEvent, Type (+6 more)

### Community 59 - "net.minecraft.world.level.Level"
Cohesion: 0.14
Nodes (10): net.minecraft.resources.ResourceKey, net.minecraft.world.level.dimension.DimensionType, net.minecraft.world.level.Level, BlockUtils, ChunkPacker, WorldData, Override, Tuple (+2 more)

### Community 60 - "GoalTwoBlocks"
Cohesion: 0.31
Nodes (3): GoalTwoBlocks, BlockPos, Override

### Community 61 - "IPlayerControllerMP"
Cohesion: 0.09
Nodes (13): net.minecraft.util.BitStorage, net.minecraft.world.level.chunk.Palette, org.spongepowered.asm.mixin.gen.Accessor, org.spongepowered.asm.mixin.gen.Invoker, org.spongepowered.asm.mixin.Unique, MixinPalettedContainer$Data, Override, MixinPalettedContainer (+5 more)

### Community 62 - "ICustomGoalProcess"
Cohesion: 0.14
Nodes (5): ICustomGoalProcess, InvertCommand, Override, Override, PathCommand

### Community 63 - "IStaticSchematic"
Cohesion: 0.08
Nodes (18): ISchematicFormat, ISchematicSystem, IStaticSchematic, DefaultSchematicFormats, LITEMATICA, MCEDIT, SPONGE, getFileExtensions() (+10 more)

### Community 64 - "AStarPathFinder.java"
Cohesion: 0.11
Nodes (8): it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap, LongToDoubleFunction, net.minecraft.world.level.border.WorldBorder, ActionCosts, AStarPathFinder, Avoidance, BetterWorldBorder, Favoring

### Community 65 - "AbstractAimProcessor"
Cohesion: 0.17
Nodes (4): ForkableRandom, AbstractAimProcessor, AimProcessor, Override

### Community 66 - "SchematicaHelper.java"
Cohesion: 0.09
Nodes (12): Override, SchematicAdapter, getOpenSchematic(), Tuple, SchematicaHelper, Override, MBlockPos, ISchematic (+4 more)

### Community 67 - "Stateless"
Cohesion: 0.19
Nodes (11): Stateless, BooleanArgumentParser, DefaultArgParsers, DoubleArgumentParser, INSTANCE, FloatArgumentParser, INSTANCE, IntArgumentParser (+3 more)

### Community 68 - "GetToBlockProcess"
Cohesion: 0.20
Nodes (3): GetToBlockCalculationContext, GetToBlockProcess, Override

### Community 69 - "BuilderProcess"
Cohesion: 0.09
Nodes (9): BuilderCalculationContext, BuilderProcess, BlockPos, ISchematic, LongOpenHashSet, Tuple, Vec3, Vec3i (+1 more)

### Community 70 - "MixinClientPlayerEntity.java"
Cohesion: 0.19
Nodes (7): java.lang.invoke.MethodHandle, net.minecraft.world.entity.player.Abilities, net.minecraft.world.entity.player.Input, org.spongepowered.asm.mixin.injection.Group, org.spongepowered.asm.mixin.injection.Redirect, SprintStateEvent, MixinClientPlayerEntity

### Community 71 - "CommandArgument"
Cohesion: 0.33
Nodes (3): CommandArgument, Override, SuppressWarnings

### Community 73 - "AbstractGameEventListener"
Cohesion: 0.11
Nodes (5): IBehavior, AbstractGameEventListener, Override, IInputOverrideHandler, Behavior

### Community 74 - "Action"
Cohesion: 0.07
Nodes (27): Action, CLEAR, CLEARAREA, CONTRACT, COPY, CYLINDER, EXPAND, HCYLINDER (+19 more)

### Community 75 - "java.lang.reflect.Type"
Cohesion: 0.22
Nodes (8): java.lang.reflect.Type, accepts(), ISettingParser, Override, parse(), LIST, MAPPING, toString()

### Community 77 - "SpongeSchematic.java"
Cohesion: 0.18
Nodes (6): java.util.regex.Pattern, net.minecraft.resources.Identifier, CompoundTag, SerializedBlockState, SpongeSchematic, VarInt

### Community 78 - ".execute"
Cohesion: 0.15
Nodes (4): ETACommand, Override, Override, ProcCommand

### Community 79 - "FEATURES.md"
Cohesion: 0.07
Nodes (28): Chat control, Future features, Goals, Pathing features, Pathing method, Additional Special Thanks To:, API, Baritone (+20 more)

### Community 80 - "Override"
Cohesion: 0.12
Nodes (3): Override, LookPriorityHub, LookRequest

### Community 81 - "ICommandManager"
Cohesion: 0.18
Nodes (3): ICommandManager, ExampleBaritoneControl, Override

### Community 82 - "NetherPathfinderContext"
Cohesion: 0.10
Nodes (8): dev.babbaj.pathfinder.PathSegment, java.util.concurrent.locks.ReentrantReadWriteLock, net.minecraft.world.level.chunk.LevelChunkSection, BlockStateOctreeInterface, Override, NetherPathfinderContext, PathKey, Visibility

### Community 83 - "Selection"
Cohesion: 0.20
Nodes (4): net.minecraft.world.phys.AABB, Override, Vec3i, Selection

### Community 84 - "CalculationContext"
Cohesion: 0.12
Nodes (3): CalculationContext, MovementPillar, Override

### Community 86 - "ChunkEvent"
Cohesion: 0.24
Nodes (6): ChunkEvent, Type, LOAD, POPULATE_FULL, POPULATE_PARTIAL, UNLOAD

### Community 87 - "PathEvent"
Cohesion: 0.13
Nodes (13): PathEvent, AT_GOAL, CALC_FAILED, CALC_FINISHED_NOW_EXECUTING, CALC_STARTED, CANCELED, CONTINUING_ONTO_PLANNED_NEXT, DISCARD_NEXT (+5 more)

### Community 88 - "MutableMoveResult"
Cohesion: 0.13
Nodes (6): MovementParkour, PARKOUR_EAST, PARKOUR_NORTH, PARKOUR_SOUTH, PARKOUR_WEST, MutableMoveResult

### Community 89 - "org.junit.Test"
Cohesion: 0.18
Nodes (6): org.junit.rules.TemporaryFolder, org.junit.Test, RelativeFileTest, CachedRegionTest, GoalGetToBlockTest, PathingBlockTypeTest

### Community 90 - "GoalYLevel"
Cohesion: 0.20
Nodes (4): GoalAxis, Override, GoalYLevel, Override

### Community 91 - "PathExecutor"
Cohesion: 0.08
Nodes (6): IMovement, Override, BlockPos, Tuple, Vec3, PathExecutor

### Community 93 - "BlockStateInterfaceAccessWrapper"
Cohesion: 0.24
Nodes (7): javax.annotation.Nullable, net.minecraft.world.level.block.entity.BlockEntity, net.minecraft.world.level.BlockGetter, net.minecraft.world.level.material.FluidState, BlockStateInterfaceAccessWrapper, Override, SuppressWarnings

### Community 94 - "InputOverrideHandler"
Cohesion: 0.25
Nodes (5): net.minecraft.client.player.ClientInput, InputOverrideHandler, Override, Override, PlayerMovementInput

### Community 95 - "ICachedWorld"
Cohesion: 0.12
Nodes (3): ICachedRegion, ICachedWorld, Override

### Community 96 - "LookBehavior.java"
Cohesion: 0.15
Nodes (4): ILookBehavior, IAimProcessor, ITickableAimProcessor, resolve()

### Community 97 - "Parser"
Cohesion: 0.15
Nodes (13): Parser, BLOCK, BOOLEAN, COLOR, DOUBLE, FLOAT, INTEGER, ITEM (+5 more)

### Community 98 - "FabricMixinPlugin"
Cohesion: 0.29
Nodes (5): FabricMixinPlugin, Override, org.objectweb.asm.tree.ClassNode, org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin, org.spongepowered.asm.mixin.extensibility.IMixinInfo

### Community 99 - "FarmProcess"
Cohesion: 0.11
Nodes (15): net.minecraft.world.level.block.CropBlock, FarmProcess, Harvest, BAMBOO, BEETROOT, CACTUS, CARROTS, COCOA (+7 more)

### Community 101 - "net.minecraft.core.Vec3i"
Cohesion: 0.13
Nodes (3): net.minecraft.core.Vec3i, IBuilderProcess, Deprecated

### Community 102 - "CompositeSchematic"
Cohesion: 0.26
Nodes (3): CompositeSchematic, Override, CompositeSchematicEntry

### Community 104 - "MixinRenderPipelines"
Cohesion: 0.23
Nodes (6): com.mojang.blaze3d.pipeline.RenderPipeline, Override, Snippet, MixinRenderPipelines, IRenderPipelines, Snippet

### Community 105 - "NeoForge Release Configuration"
Cohesion: 0.17
Nodes (12): Build Command, Build Configuration, Compatibility, Core Mod, Dependencies, Differences from Forge, Installation, Mod File (+4 more)

### Community 107 - "MixinFireworkRocketEntity.java"
Cohesion: 0.27
Nodes (6): net.minecraft.network.syncher.EntityDataAccessor, net.minecraft.world.entity.LivingEntity, net.minecraft.world.entity.projectile.FireworkRocketEntity, Override, MixinFireworkRocketEntity, IFireworkRocketEntity

### Community 110 - "IEntityRenderManager"
Cohesion: 0.24
Nodes (3): Override, MixinEntityRenderManager, IEntityRenderManager

### Community 112 - "net.minecraft.world.entity.Entity"
Cohesion: 0.16
Nodes (4): net.minecraft.world.entity.Entity, IFollowProcess, FollowProcess, Override

### Community 113 - "Forge Release Configuration"
Cohesion: 0.18
Nodes (11): Build Command, Build Configuration, Compatibility, Core Mod, Dependencies, Forge Release Configuration, Installation, Mod File (+3 more)

### Community 114 - "MixinWorldRenderer.java"
Cohesion: 0.31
Nodes (9): com.mojang.blaze3d.buffers.GpuBufferSlice, com.mojang.blaze3d.resource.GraphicsResourceAllocator, net.minecraft.client.DeltaTracker, net.minecraft.client.renderer.chunk.ChunkSectionsToRender, net.minecraft.client.renderer.state.level.CameraRenderState, org.joml.Matrix4fc, org.joml.Vector4f, MixinWorldRenderer (+1 more)

### Community 116 - "bolt.md"
Cohesion: 0.18
Nodes (5): 2025-05-21 - BetterBlockPos.longHash needs serializeToLong over BlockPos.asLong, 2025-05-21 - Test Suite Needs Java 25, 2026-06-05 - AStar Inner Loop Floating Point Division, 2026-06-05 - Hoisting invariant calculations in AStarPathFinder innermost loop, 2026-08-08 - Priority Queue Half-Exchange

### Community 117 - "GoalBlock"
Cohesion: 0.12
Nodes (7): javax.annotation.Nonnull, GoalBlock, BlockPos, Override, GoalGetToBlock, BlockPos, Override

### Community 118 - "net.minecraft.client.renderer.rendertype.RenderType"
Cohesion: 0.42
Nodes (5): net.minecraft.client.renderer.rendertype.RenderSetup, net.minecraft.client.renderer.rendertype.RenderType, Override, MixinRenderType, IRenderType

### Community 120 - "BaritonePlayerContext"
Cohesion: 0.26
Nodes (3): net.minecraft.world.phys.HitResult, BaritonePlayerContext, Override

### Community 121 - "Baritone v2 - Release Information"
Cohesion: 0.22
Nodes (9): Baritone v2 - Release Information, Changelog, Current Version, License, Quick Download Links, Release Process, Support, Supported Mod Loaders (+1 more)

### Community 123 - "GoalNear"
Cohesion: 0.33
Nodes (3): GoalNear, BlockPos, Override

### Community 125 - "MixinItemStack"
Cohesion: 0.27
Nodes (3): IItemStack, Override, MixinItemStack

### Community 131 - "PathingBlockType"
Cohesion: 0.25
Nodes (6): fromBits(), PathingBlockType, AIR, AVOID, SOLID, WATER

### Community 135 - "SettingsUtil"
Cohesion: 0.19
Nodes (3): IGoalRenderPos, getParser(), SettingsUtil

### Community 140 - "[26.1] - 2026-05-21"
Cohesion: 0.25
Nodes (7): [26.1] - 2026-05-21, 🐛 Bug Fixes, Changelog, 🧹 Code Health, ⚡ Performance Improvements, [Previous Versions], 🔒 Security Fixes

### Community 141 - "Contributor Covenant Code of Conduct"
Cohesion: 0.25
Nodes (7): Attribution, Contributor Covenant Code of Conduct, Enforcement, Our Pledge, Our Responsibilities, Our Standards, Scope

### Community 146 - "IBaritone"
Cohesion: 0.08
Nodes (8): IBaritone, IPathingControlManager, Override, RepackCommand, Override, SaveAllCommand, Override, SchematicaCommand

### Community 149 - "MovementDiagonal"
Cohesion: 0.39
Nodes (3): BlockPos, Override, MovementDiagonal

### Community 150 - "BaritoneTweaker.java"
Cohesion: 0.47
Nodes (4): io.github.impactdevelopment.simpletweaker.SimpleTweaker, net.minecraft.launchwrapper.LaunchClassLoader, BaritoneTweaker, Override

### Community 151 - "net.minecraft.client.multiplayer.ClientChunkCache"
Cohesion: 0.43
Nodes (3): net.minecraft.client.multiplayer.ClientChunkCache, MixinClientChunkProvider, IClientChunkProvider

### Community 152 - "gradlew"
Cohesion: 0.60
Nodes (3): gradlew script, die(), warn()

### Community 153 - "BaritoneMixinConnector"
Cohesion: 0.50
Nodes (3): org.spongepowered.asm.mixin.connect.IMixinConnector, BaritoneMixinConnector, Override

### Community 154 - "ExploreCommand.java"
Cohesion: 0.38
Nodes (5): apply(), Override, RelativeGoalXZ, INSTANCE, tabComplete()

### Community 158 - "bug.md"
Cohesion: 0.33
Nodes (5): Exception, error or logs, Final checklist, How to reproduce, Modified settings, Some information

### Community 164 - "Building from Source"
Cohesion: 0.40
Nodes (5): Build All Versions, Build Specific Loader, Building from Source, Output Location, Prerequisites

### Community 167 - "Release Files"
Cohesion: 0.50
Nodes (4): Fabric, Forge, NeoForge, Release Files

### Community 168 - "Installation Instructions"
Cohesion: 0.50
Nodes (4): Fabric, Forge, Installation Instructions, NeoForge

### Community 170 - "PlaceResult"
Cohesion: 0.50
Nodes (4): PlaceResult, ATTEMPTING, NO_OPTION, READY_TO_PLACE

### Community 173 - "Version Information"
Cohesion: 0.67
Nodes (3): Version Configuration, Version Information, Version Scheme

## Knowledge Gaps
- **253 isolated node(s):** `HOME`, `DEATH`, `BED`, `USER`, `IBaritoneChatControl` (+248 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **43 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `IBaritone` connect `IBaritone` to `Command`, `ForceCancelCommand`, `MovementDownward`, `Registry`, `net.minecraft.core.BlockPos`, `IDatatypeContext`, `IPlayerContext`, `IWaypoint`, `.execute`, `Movement`, `ClickCommand`, `IArgConsumer`, `ComeCommand`, `ArgConsumer`, `GcCommand`, `LitematicaCommand`, `.getProvider`, `BlockOptionalMetaLookup`, `SurfaceCommand`, `Goal`, `BetterBlockPos`, `ElytraProcess`, `ExploreCommand.java`, `Baritone`, `net.minecraft.world.level.block.Block`, `ICommand`, `.logDirect`, `net.minecraft.client.player.LocalPlayer`, `PacketEvent`, `.requireMax`, `IBaritoneProcess`, `IDatatypeFor`, `EventState`, `DummyDatatypeContext`, `net.minecraft.core.Direction`, `.getDatatypeFor`, `org.spongepowered.asm.mixin.Mixin`, `net.minecraft.world.level.Level`, `ICustomGoalProcess`, `MixinClientPlayerEntity.java`, `ILookRequest`, `AbstractGameEventListener`, `Action`, `.execute`, `ICommandManager`, `CalculationContext`, `net.minecraft.core.Vec3i`, `net.minecraft.world.entity.Entity`, `MixinWorldRenderer.java`, `IBaritoneProvider`, `IWorldData`, `BaritoneProvider`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **Why does `IArgConsumer` connect `IArgConsumer` to `Command`, `ForceCancelCommand`, `DummyArgConsumer`, `IDatatypeContext`, `.execute`, `ClickCommand`, `GcCommand`, `ComeCommand`, `ArgConsumer`, `LitematicaCommand`, `IBaritone`, `SurfaceCommand`, `ExploreCommand.java`, `ICommand`, `.logDirect`, `.requireMax`, `.execute`, `IDatatypeFor`, `DummyDatatypeContext`, `ICommandArgument`, `net.minecraft.core.Direction`, `.getDatatypeFor`, `ICustomGoalProcess`, `.execute`, `org.junit.Test`, `GoalXZ`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Why does `BetterBlockPos` connect `BetterBlockPos` to `Command`, `MovementDownward`, `MovementFall`, `GuiClick.java`, `SettingsUtil`, `IDatatypeContext`, `net.minecraft.core.BlockPos`, `IPlayerContext`, `IWaypoint`, `ElytraBehavior`, `Movement`, `UnpackedSegment`, `IPath`, `IBaritone`, `MovementDescend`, `BlockOptionalMetaLookup`, `AbstractNodeCostSearch`, `Goal`, `MovementDiagonal`, `ElytraProcess`, `ExploreCommand.java`, `MovementTraverse`, `PathingBehavior`, `Settings`, `CutoffPath`, `PathManager`, `net.minecraft.core.Direction`, `net.minecraft.world.level.Level`, `AStarPathFinder.java`, `BuilderProcess`, `Action`, `BaritoneAPI`, `NetherPathfinderContext`, `Selection`, `CalculationContext`, `MutableMoveResult`, `PathExecutor`, `MovementAscend`, `GoalBlock`, `BaritonePlayerContext`, `GoalXZ`, `Path`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **What connects `HOME`, `DEATH`, `BED` to the rest of the system?**
  _253 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Command` be split into smaller, more focused modules?**
  _Cohesion score 0.07203219315895372 - nodes in this community are weakly interconnected._
- **Should `.player` be split into smaller, more focused modules?**
  _Cohesion score 0.09269162210338681 - nodes in this community are weakly interconnected._
- **Should `net.minecraft.world.level.chunk.LevelChunk` be split into smaller, more focused modules?**
  _Cohesion score 0.11092436974789915 - nodes in this community are weakly interconnected._