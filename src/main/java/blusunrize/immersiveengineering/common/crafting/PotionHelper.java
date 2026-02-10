/*
 * BluSunrize
 * Copyright (c) 2020
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 *
 */

package blusunrize.immersiveengineering.common.crafting;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.crafting.IngredientWithSize;
import blusunrize.immersiveengineering.common.fluids.PotionFluid.PotionBottleType;
import blusunrize.immersiveengineering.common.register.IEDataComponents;
import blusunrize.immersiveengineering.common.register.IEFluids;
import blusunrize.immersiveengineering.common.util.IELogger;
import blusunrize.immersiveengineering.mixin.accessors.PotionBrewingAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.brewing.BrewingRecipe;
import net.neoforged.neoforge.common.brewing.IBrewingRecipe;
import net.neoforged.neoforge.fluids.crafting.CompoundFluidIngredient;
import net.neoforged.neoforge.fluids.crafting.DataComponentFluidIngredient;
import net.neoforged.neoforge.fluids.crafting.FluidIngredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Arrays;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PotionHelper
{
	public static BiFunction<Holder<Potion>, PotionBottleType, FluidIngredient> CREATE_POTION_BUILDER;

	public static SizedFluidIngredient getFluidIngredientForType(Holder<Potion> type, int amount, PotionBottleType potionBottleType)
	{
		if((type==Potions.WATER||type==null)&&potionBottleType==PotionBottleType.REGULAR)
			return SizedFluidIngredient.of(FluidTags.WATER, amount);
		else
		{
			DataComponentPredicate.Builder pred = DataComponentPredicate.builder().expect(DataComponents.POTION_CONTENTS, new PotionContents(type));
			if(potionBottleType!=null)
				pred.expect(IEDataComponents.POTION_BOTTLE_TYPE.get(), potionBottleType);
			FluidIngredient fluidIngredient = DataComponentFluidIngredient.of(false, pred.build(), IEFluids.POTION);

			// Support Create if installed
			if(CREATE_POTION_BUILDER!=null)
				fluidIngredient = CompoundFluidIngredient.of(
						fluidIngredient,
						CREATE_POTION_BUILDER.apply(type, potionBottleType)
				);

			return new SizedFluidIngredient(fluidIngredient, amount);
		}
	}

	public static void applyToAllPotionRecipes(PotionRecipeProcessor out)
	{
		final PotionBrewing brewingData;
		if(ServerLifecycleHooks.getCurrentServer()!=null)
			brewingData = ServerLifecycleHooks.getCurrentServer().potionBrewing();
		else
			brewingData = ImmersiveEngineering.proxy.getClientWorld().potionBrewing();
		// Vanilla
		for(var mixPredicate : ((PotionBrewingAccess)brewingData).getConversions())
		{
			if(mixPredicate.getTo()==Potions.MUNDANE||mixPredicate.getTo()==Potions.THICK)
				continue;
			if(mixPredicate.getTo().unwrapKey().isEmpty()||mixPredicate.getFrom().unwrapKey().isEmpty())
			{
				IELogger.logger.warn(
						"Skipping potion brewing mix with unregistered potion holder. Input: {}, Output: {}, Ingredient items: {}",
						describePotion(mixPredicate.getFrom()),
						describePotion(mixPredicate.getTo()),
						describeIngredient(mixPredicate.getIngredient())
				);
				continue;
			}
			out.apply(
					mixPredicate.getTo(), mixPredicate.getFrom(),
					new IngredientWithSize(mixPredicate.getIngredient())
			);
		}

		// Modded
		for(IBrewingRecipe recipe : brewingData.getRecipes())
			if(recipe instanceof BrewingRecipe brewingRecipe)
			{
				IngredientWithSize ingredient = new IngredientWithSize(brewingRecipe.getIngredient());
				Ingredient input = brewingRecipe.getInput();
				ItemStack output = brewingRecipe.getOutput();
				if(output.getItem()==Items.POTION&&input.getItems().length > 0)
				{
					Holder<Potion> outputPotion = getPotion(output);
					Holder<Potion> inputPotion = getPotion(input.getItems()[0]);
					if(outputPotion.unwrapKey().isEmpty()||inputPotion.unwrapKey().isEmpty())
					{
						IELogger.logger.warn(
								"Skipping modded brewing recipe with unregistered potion holder. Recipe class: {}, Input: {}, Output: {}, Ingredient items: {}",
								recipe.getClass().getName(),
								describePotion(inputPotion),
								describePotion(outputPotion),
								describeIngredient(brewingRecipe.getIngredient())
						);
						continue;
					}
					out.apply(outputPotion, inputPotion, ingredient);
				}
			}
	}

	private static Holder<Potion> getPotion(ItemStack potion)
	{
		PotionContents potionData = potion.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY);
		return potionData.potion().orElse(Potions.WATER);
	}

	private static String describePotion(Holder<Potion> holder)
	{
		return holder.unwrapKey()
				.map(k -> k.location().toString())
				.orElseGet(() -> {
					String effects = holder.value().getEffects().stream()
							.map(MobEffectInstance::getEffect)
							.map(e -> e.unwrapKey()
									.map(k -> k.location().toString())
									.orElse("unknown_effect"))
							.collect(Collectors.joining(", "));
					return "unregistered[effects=["+effects+"]]";
				});
	}

	private static String describeIngredient(Ingredient ingredient)
	{
		try
		{
			return Arrays.stream(ingredient.getItems())
					.map(stack -> stack.getItem().toString())
					.collect(Collectors.joining(", ", "[", "]"));
		}
		catch(Exception e)
		{
			return "[error describing ingredient: "+e.getMessage()+"]";
		}
	}

	public interface PotionRecipeProcessor
	{
		void apply(Holder<Potion> output, Holder<Potion> input, IngredientWithSize reagent);
	}
}
