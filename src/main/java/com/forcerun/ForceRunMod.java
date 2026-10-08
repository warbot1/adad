package com.forcerun;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
            new SimpleCommandExceptionType(
                    Component.literal("You must be in the Nether to use this command.")
            );

    private static final SimpleCommandExceptionType ALREADY_PRESENT =
            new SimpleCommandExceptionType(
                    Component.literal("A fortress already starts in this chunk. Move to another chunk.")
            );

    private static final SimpleCommandExceptionType FAILED =
            new SimpleCommandExceptionType(
                    Component.literal("Fortress generation failed.")
            );

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        dispatcher.register(
                                Commands.literal("forcerun")
                                        .requires(
                                                Commands.hasPermission(
                                                        Commands.LEVEL_GAMEMASTERS
                                                )
                                        )
                                        .then(
                                                Commands.literal("fortress")
                                                        .executes(
                                                                context ->
                                                                        run(
                                                                                context.getSource()
                                                                        )
                                                        )
                                        )
                        )
        );
    }

    private static int run(CommandSourceStack source)
            throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        // The command only works in the Nether.
        if (level.dimension() != Level.NETHER) {
            throw NOT_IN_NETHER.create();
        }

        // Get the real vanilla Nether Fortress structure.
        Holder<Structure> fortressHolder = level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE)
                .getOrThrow(BuiltinStructures.FORTRESS);

        Structure fortress = fortressHolder.value();

        ChunkGenerator generator =
                level.getChunkSource().getGenerator();

        /*
         * Find the chunk containing the player.
         *
         * In Minecraft 26.1+, ChunkPos(BlockPos) was replaced
         * by ChunkPos.containing(BlockPos).
         */
        BlockPos playerPos = player.blockPosition();
        ChunkPos startChunkPos = ChunkPos.containing(playerPos);

        // Load the origin chunk.
        ChunkAccess startChunk = level.getChunk(
                startChunkPos.x(),
                startChunkPos.z()
        );

        // Don't overwrite an existing fortress start.
        if (startChunk.getStartForStructure(fortress) != null) {
            throw ALREADY_PRESENT.create();
        }

        /*
         * Generate the actual vanilla fortress structure.
         *
         * This produces the normal StructureStart and fortress
         * pieces rather than simply copying a block schematic.
         */
        StructureStart start = fortress.generate(
                fortressHolder,
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
                biome -> true
        );

        if (!start.isValid()) {
            throw FAILED.create();
        }

        BoundingBox box = start.getBoundingBox();

        /*
         * Convert the fortress bounding box into the first and
         * last chunks it occupies.
         */
        ChunkPos minChunk = new ChunkPos(
                box.minX() >> 4,
                box.minZ() >> 4
        );

        ChunkPos maxChunk = new ChunkPos(
                box.maxX() >> 4,
                box.maxZ() >> 4
        );

        /*
         * Place the actual generated fortress pieces.
         */
        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(chunkPos -> {

            ChunkAccess chunk = level.getChunk(
                    chunkPos.x(),
                    chunkPos.z()
            );

            BoundingBox chunkBox = new BoundingBox(
                    chunkPos.getMinBlockX(),
                    level.getMinY(),
                    chunkPos.getMinBlockZ(),
                    chunkPos.getMaxBlockX(),
                    level.getMaxY() + 1,
                    chunkPos.getMaxBlockZ()
            );

            start.placeInChunk(
                    level,
                    level.getStructureManager(),
                    generator,
                    level.getRandom(),
                    chunkBox,
                    chunkPos
            );

            chunk.markUnsaved();
        });

        /*
         * Store the StructureStart in the origin chunk.
         *
         * This is what makes Minecraft know a real structure start
         * exists there.
         */
        startChunk.setStartForStructure(
                fortress,
                start
        );

        startChunk.markUnsaved();

        /*
         * Register references from every chunk touched by the
         * fortress back to the origin chunk.
         *
         * ChunkPos.pack(...) is the 26.x replacement for the
         * older toLong()/asLong() APIs.
         */
        long startKey = ChunkPos.pack(
                startChunkPos.x(),
                startChunkPos.z()
        );

        ChunkPos.rangeClosed(minChunk, maxChunk).forEach(chunkPos -> {

            ChunkAccess chunk = level.getChunk(
                    chunkPos.x(),
                    chunkPos.z()
            );

            chunk.addReferenceForStructure(
                    fortress,
                    startKey
            );

            chunk.markUnsaved();
        });

        source.sendSuccess(
                () -> Component.literal(
                        "Generated a Nether Fortress at chunk "
                                + startChunkPos.x()
                                + ", "
                                + startChunkPos.z()
                                + "."
                ),
                true
        );

        return 1;
    }
}
