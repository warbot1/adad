package com.forcerun;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public class ForceRunMod implements ModInitializer {

    private static final SimpleCommandExceptionType NOT_IN_NETHER =
            new SimpleCommandExceptionType(Component.literal("You must be in the Nether to use this command."));
    private static final SimpleCommandExceptionType ALREADY_PRESENT =
            new SimpleCommandExceptionType(Component.literal("A fortress already starts in this chunk. Move to another chunk."));
    private static final SimpleCommandExceptionType FAILED =
            new SimpleCommandExceptionType(Component.literal("Fortress generation failed."));

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(
                        Commands.literal("forcerun")
                                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("fortress")
                                        .executes(ctx -> run(ctx.getSource())))));
    }

    private static int run(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        if (level.dimension() != Level.NETHER) {
            throw NOT_IN_NETHER.create();
        }

        Holder<Structure> holder = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(BuiltinStructures.FORTRESS);
        Structure structure = holder.value();

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        net.minecraft.core.BlockPos playerPos = player.blockPosition();
        ChunkPos startChunkPos = new ChunkPos(
                SectionPos.blockToSectionCoord(playerPos.getX()),
                SectionPos.blockToSectionCoord(playerPos.getZ()));

        ChunkAccess startChunk = level.getChunk(startChunkPos.x(), startChunkPos.z());
        if (startChunk.getStartForStructure(structure) != null) {
            throw ALREADY_PRESENT.create();
        }

        // Same call vanilla world generation makes (ChunkGenerator#tryGenerateStructure).
        StructureStart start = structure.generate(
                holder,
                level.dimension(),
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                startChunkPos,
                0,
                level,
                biome -> true);

        if (!start.isValid()) {
            throw FAILED.create();
        }

        BoundingBox box = start.getBoundingBox();
        ChunkPos min = new ChunkPos(
                SectionPos.blockToSectionCoord(box.minX()),
                SectionPos.blockToSectionCoord(box.minZ()));
        ChunkPos max = new ChunkPos(
                SectionPos.blockToSectionCoord(box.maxX()),
                SectionPos.blockToSectionCoord(box.maxZ()));

        // 1) Place the blocks (what /place structure does).
        ChunkPos.rangeClosed(min, max).forEach(chunkPos -> start.placeInChunk(
                level,
                level.structureManager(),
                generator,
                level.getRandom(),
                new BoundingBox(
                        chunkPos.getMinBlockX(), level.getMinY(), chunkPos.getMinBlockZ(),
                        chunkPos.getMaxBlockX(), level.getMaxY() + 1, chunkPos.getMaxBlockZ()),
                chunkPos));

        // 2) Register it like natural generation does (what /place structure skips):
        //    the start is stored in its origin chunk...
        startChunk.setStartForStructure(structure, start);
        startChunk.markUnsaved();

        //    ...and every chunk the fortress overlaps gets a reference to that start,
        //    which is what StructureManager uses to answer "is this position inside a fortress piece?"
        // Same packing vanilla uses for chunk coordinates in structure references.
        long startKey = (startChunkPos.x() & 0xFFFFFFFFL) | ((startChunkPos.z() & 0xFFFFFFFFL) << 32);
        ChunkPos.rangeClosed(min, max).forEach(chunkPos -> {
            ChunkAccess chunk = level.getChunk(chunkPos.x(), chunkPos.z());
            chunk.addReferenceForStructure(structure, startKey);
            chunk.markUnsaved();
        });

        source.sendSuccess(() -> Component.literal("Generated a Nether Fortress at chunk "
                + startChunkPos.x() + ", " + startChunkPos.z() + "."), true);
        return 1;
    }
}
