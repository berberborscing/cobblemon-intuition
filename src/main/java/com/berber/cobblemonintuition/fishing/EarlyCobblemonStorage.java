package com.berber.cobblemonintuition.fishing;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import kotlin.jvm.internal.Ref;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public interface EarlyCobblemonStorage {
    void cobblemon_intuition$setEarlyItems(List<ItemStack> itemStacks);
    void cobblemon_intuition$setEarlyPokemon(PokemonEntity p);

    List<ItemStack> cobblemon_intuition$getEarlyItems();
    PokemonEntity cobblemon_intuition$getEarlyPokemon();

    boolean cobblemon_intuition$hasEarlyItems();
    boolean cobblemon_intuition$hasEarlyPokemon();
}
