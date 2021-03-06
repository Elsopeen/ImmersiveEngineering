/*
 * BluSunrize
 * Copyright (c) 2017
 *
 * This code is licensed under "Blu's License of Common Sense"
 * Details can be found in the license file in the root folder of this project
 */

package blusunrize.immersiveengineering.common.world;

import blusunrize.immersiveengineering.ImmersiveEngineering;
import blusunrize.immersiveengineering.api.excavator.ExcavatorHandler;
import blusunrize.immersiveengineering.common.IEConfig;
import blusunrize.immersiveengineering.common.IEContent;
import blusunrize.immersiveengineering.common.util.IELogger;
import com.google.common.collect.HashMultimap;
import com.mojang.serialization.Codec;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.SharedSeedRandom;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.ISeedReader;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.GenerationStage.Decoration;
import net.minecraft.world.gen.PerlinNoiseGenerator;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.NoFeatureConfig;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import net.minecraft.world.gen.feature.OreFeatureConfig.FillerBlockType;
import net.minecraft.world.gen.feature.structure.StructureManager;
import net.minecraft.world.gen.placement.*;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.ChunkDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class IEWorldGen
{
	public static Map<String, ConfiguredFeature<?, ?>> features = new HashMap<>();
	public static Map<String, ConfiguredFeature<?, ?>> retroFeatures = new HashMap<>();
	public static List<RegistryKey<DimensionType>> oreDimBlacklist = new ArrayList<>();
	public static Set<String> retrogenOres = new HashSet<>();

	public static void addOreGen(String name, BlockState state, int maxVeinSize, int minY, int maxY, int chunkOccurence)
	{
		OreFeatureConfig cfg = new OreFeatureConfig(FillerBlockType.NATURAL_STONE, state, maxVeinSize);
		ConfiguredFeature<?, ?> feature = new ConfiguredFeature<>(Feature.ORE, cfg)
				.withPlacement(
						new ConfiguredPlacement<>(COUNT_RANGE_IE, new CountRangeConfig(chunkOccurence, minY, minY, maxY))
				);
		for(Biome biome : ForgeRegistries.BIOMES.getValues())
			biome.addFeature(Decoration.UNDERGROUND_ORES, feature);
		features.put(name, feature);

		ConfiguredFeature<?, ?> retroFeature = new ConfiguredFeature<>(IEContent.ORE_RETROGEN, cfg)
				.withPlacement(
						new ConfiguredPlacement<>(COUNT_RANGE_IE, new CountRangeConfig(chunkOccurence, minY, minY, maxY))
				);
		retroFeatures.put(name, retroFeature);
	}

	public static void registerMineralVeinGen()
	{
		ConfiguredFeature<?, ?> vein_feature = new ConfiguredFeature<>(MINERAL_VEIN_FEATURE, new NoFeatureConfig())
				.withPlacement(
						new ConfiguredPlacement<>(Placement.NOPE, IPlacementConfig.NO_PLACEMENT_CONFIG)
				);
		for(Biome biome : ForgeRegistries.BIOMES.getValues())
			biome.addFeature(Decoration.RAW_GENERATION, vein_feature);
	}

	public void generateOres(Random random, int chunkX, int chunkZ, ServerWorld world, boolean newGeneration)
	{
		if(!oreDimBlacklist.contains(world.func_234922_V_()))
		{
			if(newGeneration)
			{
				for(Entry<String, ConfiguredFeature<?, ?>> gen : features.entrySet())
					gen.getValue().func_236265_a_(world, world.func_241112_a_(), world.getChunkProvider().getChunkGenerator(), random, new BlockPos(16*chunkX, 0, 16*chunkZ));
			}
			else
			{
				for(Entry<String, ConfiguredFeature<?, ?>> gen : retroFeatures.entrySet())
				{
					if(retrogenOres.contains("retrogen_"+gen.getKey()))
						gen.getValue().func_236265_a_(world, world.func_241112_a_(), world.getChunkProvider().getChunkGenerator(), random, new BlockPos(16*chunkX, 0, 16*chunkZ));
				}
			}
		}

	}

	@SubscribeEvent
	public void chunkDataSave(ChunkDataEvent.Save event)
	{
		CompoundNBT levelTag = event.getData().getCompound("Level");
		CompoundNBT nbt = new CompoundNBT();
		levelTag.put("ImmersiveEngineering", nbt);
		nbt.putBoolean(IEConfig.ORES.retrogen_key.get(), true);
	}

	@SubscribeEvent
	public void chunkDataLoad(ChunkDataEvent.Load event)
	{
		if(event.getChunk().getStatus()==ChunkStatus.FULL)
		{
			if(!event.getData().getCompound("ImmersiveEngineering").contains(IEConfig.ORES.retrogen_key.get())&&
					!retrogenOres.isEmpty())
			{
				if(IEConfig.ORES.retrogen_log_flagChunk.get())
					IELogger.info("Chunk "+event.getChunk().getPos()+" has been flagged for Ore RetroGeneration by IE.");
				RegistryKey<World> dimension = event.getChunk().getWorldForge().getWorld().func_234923_W_();
				synchronized(retrogenChunks)
				{
					retrogenChunks.computeIfAbsent(dimension, d -> new ArrayList<>()).add(event.getChunk().getPos());
				}
			}
		}
	}

	public static final Map<RegistryKey<World>, List<ChunkPos>> retrogenChunks = new HashMap<>();

	int indexToRemove = 0;

	@SubscribeEvent
	public void serverWorldTick(TickEvent.WorldTickEvent event)
	{
		if(event.side==LogicalSide.CLIENT||event.phase==TickEvent.Phase.START||!(event.world instanceof ServerWorld))
			return;
		RegistryKey<World> dimension = event.world.func_234923_W_();
		int counter = 0;
		int remaining;
		synchronized(retrogenChunks)
		{
			final List<ChunkPos> chunks = retrogenChunks.get(dimension);

			if(chunks!=null&&chunks.size() > 0)
			{
				if(indexToRemove >= chunks.size())
					indexToRemove = 0;
				for(int i = 0; i < 2&&indexToRemove < chunks.size(); ++i)
				{
					if(chunks.size() <= 0)
						break;
					ChunkPos loc = chunks.get(indexToRemove);
					if(event.world.chunkExists(loc.x, loc.z))
					{
						long worldSeed = ((ISeedReader)event.world).getSeed();
						Random fmlRandom = new Random(worldSeed);
						long xSeed = (fmlRandom.nextLong() >> 3);
						long zSeed = (fmlRandom.nextLong() >> 3);
						fmlRandom.setSeed(xSeed*loc.x+zSeed*loc.z^worldSeed);
						this.generateOres(fmlRandom, loc.x, loc.z, (ServerWorld)event.world, false);
						counter++;
						chunks.remove(indexToRemove);
					}
					else
						++indexToRemove;
				}
			}
			remaining = chunks==null?0: chunks.size();
		}
		if(counter > 0&&IEConfig.ORES.retrogen_log_remaining.get())
			IELogger.info("Retrogen was performed on "+counter+" Chunks, "+remaining+" chunks remaining");
	}

	private static CountRangeInIEDimensions COUNT_RANGE_IE;
	private static FeatureMineralVein MINERAL_VEIN_FEATURE;

	public void registerPlacements(RegistryEvent.Register<Placement<?>> ev)
	{
		COUNT_RANGE_IE = new CountRangeInIEDimensions(CountRangeConfig.field_236485_a_);
		COUNT_RANGE_IE.setRegistryName(ImmersiveEngineering.MODID, "count_range_in_ie_dimensions");
		ev.getRegistry().register(COUNT_RANGE_IE);
	}

	public void registerFeatures(RegistryEvent.Register<Feature<?>> ev)
	{
		MINERAL_VEIN_FEATURE = new FeatureMineralVein();
		MINERAL_VEIN_FEATURE.setRegistryName(ImmersiveEngineering.MODID, "mineral_vein");
		ev.getRegistry().register(MINERAL_VEIN_FEATURE);
	}

	private static class CountRangeInIEDimensions extends Placement<CountRangeConfig>
	{
		private final CountRange countRange;

		public CountRangeInIEDimensions(Codec<CountRangeConfig> configFactoryIn)
		{
			super(configFactoryIn);
			this.countRange = new CountRange(configFactoryIn);
		}

		@Nonnull
		@Override
		public Stream<BlockPos> getPositions(@Nonnull IWorld worldIn,
											 @Nonnull ChunkGenerator generatorIn,
											 @Nonnull Random random, @Nonnull CountRangeConfig configIn, @Nonnull BlockPos pos)
		{
			if(!oreDimBlacklist.contains(worldIn.getWorld().func_234922_V_()))
			{
				return countRange.getPositions(worldIn, generatorIn, random, configIn, pos);
			}
			else
			{
				return Stream.empty();
			}
		}
	}

	private static class FeatureMineralVein extends Feature<NoFeatureConfig>
	{
		public static HashMultimap<RegistryKey<World>, ChunkPos> veinGeneratedChunks = HashMultimap.create();

		public FeatureMineralVein()
		{
			super(NoFeatureConfig.field_236558_a_);
		}

		@Override
		public boolean func_230362_a_(@Nonnull ISeedReader worldIn, @Nonnull StructureManager p_230362_2_,
									  @Nonnull ChunkGenerator p_230362_3_, @Nonnull Random random,
									  @Nonnull BlockPos pos, @Nonnull NoFeatureConfig p_230362_6_)
		{
			if(ExcavatorHandler.noiseGenerator==null)
				ExcavatorHandler.noiseGenerator = new PerlinNoiseGenerator(
						new SharedSeedRandom(worldIn.getSeed()),
						IntStream.of(0)
				);

			RegistryKey<World> dimension = worldIn.getWorld().func_234923_W_();
			IChunk chunk = worldIn.getChunk(pos);
			if(!veinGeneratedChunks.containsEntry(dimension, chunk.getPos()))
			{
				veinGeneratedChunks.put(dimension, chunk.getPos());
				ExcavatorHandler.generatePotentialVein(worldIn.getWorld(), chunk.getPos(), random);
				return true;
			}
			return false;
		}
	}
}
