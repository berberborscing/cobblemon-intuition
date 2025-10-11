package com.berber.cobblemonintuition.mixin;

import com.berber.cobblemonintuition.fishing.EarlyCobblemonStorage;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.ModAPI;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.fishing.BobberSpawnPokemonEvent;
import com.cobblemon.mod.common.api.reactive.ObservableSubscription;
import com.cobblemon.mod.common.api.spawning.BestSpawner;
import com.cobblemon.mod.common.api.spawning.SpawnBucket;
import com.cobblemon.mod.common.api.spawning.detail.EntitySpawnResult;
import com.cobblemon.mod.common.api.spawning.fishing.FishingSpawnCause;
import com.cobblemon.mod.common.api.spawning.fishing.FishingSpawner;
import com.cobblemon.mod.common.api.spawning.influence.PlayerLevelRangeInfluence;
import com.cobblemon.mod.common.api.spawning.spawner.Spawner;
import com.cobblemon.mod.common.api.text.TextKt;
import com.cobblemon.mod.common.entity.fishing.PokeRodFishingBobberEntity;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.item.interactive.PokerodItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.util.LocalizationUtilsKt;
import com.cobblemon.mod.common.util.MiscUtilsKt;
import com.cobblemon.mod.common.util.Vec3ExtensionsKt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import kotlin.collections.CollectionsKt;
import kotlin.jvm.internal.Intrinsics;
import kotlin.jvm.internal.Ref;
import kotlin.ranges.IntRange;
import net.berber.berbersbrews.effect.ModEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Mixin(PokeRodFishingBobberEntity.class)
public abstract class PokeRodMixin extends FishingHook implements EarlyCobblemonStorage {
    @Shadow private PokeRodFishingBobberEntity.TypeCaught typeCaught;
    @Shadow private int hookCountdown;
    @Shadow private int fishTravelCountdown;
    @Shadow private SpawnBucket chosenBucket;

    private List<ItemStack> earlyItems = List.of();
    private PokemonEntity earlyPokemon;
    private boolean flag = false;
    private boolean hasTried = false; //Fixes a bug where nothing rerolls into items when bobber sinks in the End
    private boolean pokemonCaught;
    public String displayText = "DebugText";
    private int i, cycle, deployCounter = 0;
    BobberSpawnPokemonEvent.Modify copy;

    ObservableSubscription<BobberSpawnPokemonEvent.Modify> subModify = CobblemonEvents.BOBBER_SPAWN_POKEMON_MODIFY.subscribe(Priority.NORMAL, event -> {
        if(!level().isClientSide() && pokemonCaught) {
            cobblemon_intuition$setEarlyPokemon(event.getPokemon());
            if(copy == null) {
                copy = event.copy(event.component1(), event.component2(), event.component3());
                event.component3().setInvulnerable(true); //so it doesn't make suffocation sounds
                event.component3().setPos(getX(), -400, getZ()); //so it doesn't appear
            } else if(copy != null) {
                event.component3().setInvisible(true);
                event.component3().setUUID(UUID.randomUUID());
                event.component3().setPokemon(copy.component3().getPokemon());
                cleanUp();
            }
        }
        return null;
    });

    @Override
    public boolean cobblemon_intuition$hasEarlyItems() {
        return !earlyItems.isEmpty();
    }

    @Override
    public boolean cobblemon_intuition$hasEarlyPokemon() {
        return earlyPokemon != null;
    }

    @Override
    public List<ItemStack> cobblemon_intuition$getEarlyItems() {
        return earlyItems;
    }

    @Override
    public PokemonEntity cobblemon_intuition$getEarlyPokemon() {
        return earlyPokemon;
    }

    @Override
    public void cobblemon_intuition$setEarlyItems(List<ItemStack> li) {
        this.earlyItems = li;
    }

    @Override
    public void cobblemon_intuition$setEarlyPokemon(PokemonEntity p) {
        this.earlyPokemon = p;
    }

    public PokeRodMixin(EntityType<? extends FishingHook> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method="tickFishingLogic", at = @At("HEAD"))
    public void cobint$tickFishingLogic(BlockPos blockPos, CallbackInfo ci) {

        if(this instanceof FishingHook fh && fh.getPlayerOwner() != null) {
            //Generate the outcome early
            if (this instanceof EarlyCobblemonStorage storage && !storage.cobblemon_intuition$hasEarlyItems() && !storage.cobblemon_intuition$hasEarlyPokemon()) {
                if (!this.level().isClientSide() && !flag && !hasTried) {
                    generateOutcomeEarly((PokeRodFishingBobberEntity) (Object) this, storage);
                    flag = true;
                    hasTried = true;
                }
            }

            //Reroll loot if the bite is missed
            if (this.hookCountdown <= 0) {
                if (this instanceof EarlyCobblemonStorage storage) {
                    if(!flag) {
                        generateOutcomeEarly((PokeRodFishingBobberEntity) (Object) this, storage);
                        this.setCustomNameVisible(false); //Fixes a bug where the deployCounter isn't obeyed when the line isn't pulled
                        flag = true;
                    }
                }
            } else { //Reset this flag when something bites and there is still time
                flag = false;
            }

            //Displays the name tag only when Berber's Brews INTUITION status effect is active
            if (fh.getPlayerOwner().hasEffect(ModEffects.SONAR)) {
                if(this.fishTravelCountdown > 0 || this.hookCountdown > 0 || deployCounter > 4) {
                    this.setCustomNameVisible(true);
                }
            } else { this.setCustomNameVisible(false); }

            if(this instanceof EarlyCobblemonStorage storage && (storage.cobblemon_intuition$hasEarlyItems() || storage.cobblemon_intuition$hasEarlyPokemon()) && this.getPlayerOwner() != null) {
                cycle++;
                if(cycle > 20) { //iterate i and deploycounter
                    i = i + 1;
                    deployCounter = deployCounter + 1;
                    cycle = 0;
                }
                if(i >= storage.cobblemon_intuition$getEarlyItems().size()) {
                    i = 0; //prevents game crash
                }

                if(pokemonCaught) {
                    this.typeCaught = PokeRodFishingBobberEntity.TypeCaught.POKEMON;
                    if(cobblemon_intuition$hasEarlyPokemon()) {
                        displayText = storage.cobblemon_intuition$getEarlyPokemon().getDisplayName().getString();
                    } else {
                        displayText = "...";
                    }
                } else {
                    this.typeCaught = PokeRodFishingBobberEntity.TypeCaught.ITEM;
                    displayText = storage.cobblemon_intuition$getEarlyItems().get(i).getDisplayName().getString().substring(1, storage.cobblemon_intuition$getEarlyItems().get(i).getDisplayName().getString().length()-1);
                }
                if(!pokemonCaught || !cobblemon_intuition$getEarlyPokemon().getPokemon().getShiny()) {
                    this.setCustomName(Component.literal(displayText));
                } else {
                    this.setCustomName(Component.literal(displayText).setStyle(Style.EMPTY.withColor(16514837)));
                }
            }
        }
    }

    //Unique method used to generate new Items or Pokemon
    @Unique
    public final void generateOutcomeEarly(PokeRodFishingBobberEntity bobber, EarlyCobblemonStorage storage) {
        copy = null; deployCounter = 0;
        Level world = bobber.getCommandSenderWorld();
        Player player = bobber.getPlayerOwner();

        if(player == null) return;

        ItemStack tool = player.getMainHandItem();
        if(!(tool.getItem() instanceof PokerodItem)) {
            tool = player.getOffhandItem();
        }

        this.cobblemon_intuition$setEarlyPokemon(null);

        PokeRodFishingBobberEntity poke = (PokeRodFishingBobberEntity) (Object) this;
        //First, determine whether to create an item or a Pokemon
        if (Mth.nextInt(super.random, 0, 100) >= poke.getPokemonSpawnChance(poke.getBobberBait())) {
            //Item created
            this.typeCaught = PokeRodFishingBobberEntity.TypeCaught.ITEM;
            this.setCustomName(Component.literal("Item"));
        } else {
            //Pokemon created
            this.typeCaught = PokeRodFishingBobberEntity.TypeCaught.POKEMON;
            this.setCustomName(Component.literal("..."));
        }

        if (this.typeCaught == PokeRodFishingBobberEntity.TypeCaught.ITEM) {
            pokemonCaught = false;
            Entity owner = this.getOwner();
            if (owner != null) {
                Level var49 = this.level();
                Intrinsics.checkNotNull(var49, "null cannot be cast to non-null type net.minecraft.server.level.ServerLevel");
                LootParams.Builder lootTable = (new LootParams.Builder((ServerLevel)var49)).withParameter(LootContextParams.ORIGIN, this.position()).withParameter(LootContextParams.TOOL, tool).withParameter(LootContextParams.THIS_ENTITY, this);
                if (Cobblemon.INSTANCE.getImplementation().getModAPI() != ModAPI.FABRIC) {
                    lootTable.withParameter(LootContextParams.ATTACKING_ENTITY, owner);
                }

                LootParams lootContextParameterSet = lootTable.create(LootContextParamSets.FISHING);
                MinecraftServer var44 = this.level().getServer();
                Intrinsics.checkNotNull(var44);
                LootTable lootTable2 = var44.reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE, MiscUtilsKt.cobblemonResource("fishing/pokerod")));
                ObjectArrayList var45 = lootTable2.getRandomItems(lootContextParameterSet);
                Intrinsics.checkNotNullExpressionValue(var45, "getRandomItems(...)");
                List list = (List)var45;
                cobblemon_intuition$setEarlyItems(list);
            }
        } else {
            pokemonCaught = true;

            //Start procedure to generate a Pokemon
            Intrinsics.checkNotNullParameter(player, "player");
            Intrinsics.checkNotNullParameter(chosenBucket, "chosenBucket");
            Intrinsics.checkNotNullParameter(tool, "rodItemStack");
            FishingSpawner spawner = BestSpawner.INSTANCE.getFishingSpawner();
            FishingSpawnCause spawnCause = new FishingSpawnCause((Spawner)spawner, chosenBucket, (Entity)player, tool);
            Level var10002 = this.level();
            Intrinsics.checkNotNull(var10002, "null cannot be cast to non-null type net.minecraft.server.level.ServerLevel");
            ServerLevel var31 = (ServerLevel)var10002;
            Vec3 var10005 = this.position();
            Intrinsics.checkNotNullExpressionValue(var10005, "position(...)");

            //CompletableFuture result = spawner.run(spawnCause, var31, Vec3ExtensionsKt.toBlockPos(var10005), CollectionsKt.listOf(new PlayerLevelRangeInfluence((ServerPlayer)player, PlayerLevelRangeInfluence.Companion.getTYPICAL_VARIATION(), new IntRange(0, 0),0L)));
            CompletableFuture result = spawner.run(spawnCause, var31, Vec3ExtensionsKt.toBlockPos(var10005), CollectionsKt.listOf(new PlayerLevelRangeInfluence((ServerPlayer)player, PlayerLevelRangeInfluence.Companion.getTYPICAL_VARIATION(), new IntRange(0, 0),0L)));

            if (result != null && !result.isCompletedExceptionally()) {
                Ref.ObjectRef spawnedPokemon = new Ref.ObjectRef();
                Object resultingSpawn = null;
                try {
                    resultingSpawn = result.get();
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                } catch (ExecutionException ex) {
                    throw new RuntimeException(ex);
                }

                if (resultingSpawn instanceof EntitySpawnResult) {
                    for(Entity entity : ((EntitySpawnResult)resultingSpawn).getEntities()) {
                        Intrinsics.checkNotNull(entity, "null cannot be cast to non-null type com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
                        spawnedPokemon.element = (PokemonEntity)entity;

                        //Since a Pokemon must be spawned in order to make a prediction, we immediately despawn this dummy Pokemon out of sight of the player
                        ((PokemonEntity) spawnedPokemon.element).setQueuedToDespawn(true);
                    }
                }
                this.setCustomName(Component.literal(displayText));
            }
        }
    }

    //Overwrite items at reel-in with pregenerated outcome
    @ModifyArg(
            method = "retrieve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/ItemEntity;<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V"
            ),
            index = 4
    )
    public ItemStack replaceCaughtItem(ItemStack original) {

        return this.earlyItems.get(0).copy(); //Replace only the first items. dropExtraLoot handles extra loot like smithing templates
    }

    //Handles additional loot
    @Inject(
            method = "retrieve",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/ItemEntity;<init>(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)V",
                    shift = At.Shift.AFTER
            )
    )
    public void dropExtraLoot(ItemStack usedItem, CallbackInfoReturnable<Integer> cir) {
        PokeRodFishingBobberEntity self = (PokeRodFishingBobberEntity) (Object)this;

        if (this instanceof EarlyCobblemonStorage storage && storage.cobblemon_intuition$hasEarlyItems()) {
            List<ItemStack> loot = storage.cobblemon_intuition$getEarlyItems();
            storage.cobblemon_intuition$setEarlyItems(List.of());

            //skip first item since it was used in replaceCaughtItem
            for (int i = 1; i < loot.size(); i++) {
                ItemEntity extra = new ItemEntity(self.level(), self.getX(), self.getY(), self.getZ(), loot.get(i).copy());
                if (self.getPlayerOwner() != null) {
                    double dx = self.getPlayerOwner().getX() - self.getX();
                    double dy = self.getPlayerOwner().getEyeY() - self.getY();
                    double dz = self.getPlayerOwner().getZ() - self.getZ();
                    ((Entity)extra).setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
                }
                self.level().addFreshEntity(extra);
            }
        }
        cleanUp();
    }

    //Always clean up your event subscriptions to prevent memory leaks!
    @Unique
    private void cleanUp() {
        subModify.unsubscribe();
    }
}