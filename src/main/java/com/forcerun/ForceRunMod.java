package com.forcerun;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

public class ForceRunMod implements ModInitializer {

    private static final SimpleCommandExceptionType NOT_IN_NETHER =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "You must be in the Nether to use this command."
                    )
            );

    private static final SimpleCommandExceptionType ALREADY_PRESENT =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "A fortress already starts in this chunk."
                    )
            );

    private static final SimpleCommandExceptionType FAILED =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "Fortress generation failed."
                    )
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

    private static int run(
            CommandSourceStack source
    ) throws CommandSyntaxException {

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                source.getLevel();

        /*
         * Only allow the command in the Nether.
         */
        if (level.dimension() != Level.NETHER) {
            throw NOT_IN_NETHER.create();
        }

        /*
         * Get Minecraft's real vanilla Nether Fortress
         * structure definition.
         */
        Holder<Structure> fortressHolder =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getOrThrow(BuiltinStructures.FORTRESS);

        Structure fortress =
                fortressHolder.value();

        ChunkGenerator generator =
                level.getChunkSource().getGenerator();

        /*
         * Get the chunk containing the player.
         *
         * Minecraft 26.x:
         * ChunkPos(BlockPos) -> ChunkPos.containing(BlockPos)
         */
        ChunkPos startChunkPos =
                ChunkPos.containing(
                        player.blockPosition()
                );

        /*
         * Load the origin chunk.
         */
        ChunkAccess startChunk =
                level.getChunk(
                        startChunkPos.x(),
                        startChunkPos.z()
                );

        /*
         * Don't overwrite an existing fortress.
         */
        if (startChunk.getStartForStructure(fortress) != null) {
            throw ALREADY_PRESENT.create();
        }

        /*
         * Generate the actual vanilla StructureStart.
         *
         * This creates the real fortress pieces instead of
         * simply copying a structure as blocks.
         */
        StructureStart start =
                fortress.generate(
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

        /*
         * Get the fortress bounding box.
         */
        BoundingBox structureBox =
                start.getBoundingBox();

        /*
         * Convert the bounding box to chunk coordinates.
         */
        ChunkPos minChunk =
                new ChunkPos(
                        structureBox.minX() >> 4,
                        structureBox.minZ() >> 4
                );

        ChunkPos maxChunk =
                new ChunkPos(
                        structureBox.maxX() >> 4,
                        structureBox.maxZ() >> 4
                );

        /*
         * Get the StructureManager required by
         * StructureStart.placeInChunk().
         *
         * IMPORTANT:
         * level.getStructureManager() returns a
         * StructureTemplateManager in 26.2.
         */
        StructureManager structureManager =
                level.structureManager();

        /*
         * Place every generated fortress piece.
         */
        ChunkPos.rangeClosed(
                minChunk,
                maxChunk
        ).forEach(chunkPos -> {

            ChunkAccess chunk =
                    level.getChunk(
                            chunkPos.x(),
                            chunkPos.z()
                    );

            BoundingBox chunkBox =
                    new BoundingBox(
                            chunkPos.getMinBlockX(),
                            level.getMinY(),
                            chunkPos.getMinBlockZ(),
                            chunkPos.getMaxBlockX(),
                            level.getMaxY() + 1,
                            chunkPos.getMaxBlockZ()
                    );

            start.placeInChunk(
                    level,
                    structureManager,
                    generator,
                    level.getRandom(),
                    chunkBox,
                    chunkPos
            );

            chunk.markUnsaved();
        });

        /*
         * Register the StructureStart in the origin chunk.
         */
        startChunk.setStartForStructure(
                fortress,
                start
        );

        startChunk.markUnsaved();

        /*
         * Create the packed chunk reference.
         */
        long startKey =
                ChunkPos.pack(
                        startChunkPos.x(),
                        startChunkPos.z()
                );

        /*
         * Register the fortress reference in every
         * chunk touched by the structure.
         */
        ChunkPos.rangeClosed(
                minChunk,
                maxChunk
        ).forEach(chunkPos -> {

            ChunkAccess chunk =
                    level.getChunk(
                            chunkPos.x(),
                            chunkPos.z()
                    );

            chunk.addReferenceForStructure(
                    fortress,
                    startKey
            );

            chunk.markUnsaved();
        });

        /*
         * Tell the player the fortress was generated.
         */
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
