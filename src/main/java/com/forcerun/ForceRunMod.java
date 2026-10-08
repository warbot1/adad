package com.forcerun;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

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
                    Component.literal("You must be in the Nether.")
            );

    private static final SimpleCommandExceptionType ALREADY_PRESENT =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "This structure already starts in this chunk."
                    )
            );

    private static final SimpleCommandExceptionType FAILED =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "Structure generation failed. Try another location."
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
                                                        .executes(context ->
                                                                generate(
                                                                        context.getSource(),
                                                                        BuiltinStructures.FORTRESS,
                                                                        "Nether Fortress"
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("bastion")
                                                        .executes(context ->
                                                                generate(
                                                                        context.getSource(),
                                                                        BuiltinStructures.BASTION_REMNANT,
                                                                        "Bastion Remnant"
                                                                )
                                                        )
                                        )
                        )
        );
    }

    private static int generate(
            CommandSourceStack source,
            net.minecraft.resources.ResourceKey<Structure> structureKey,
            String structureName
    ) throws CommandSyntaxException {

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();

        if (level.dimension() != Level.NETHER) {
            throw NOT_IN_NETHER.create();
        }

        Holder<Structure> holder =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getOrThrow(structureKey);

        Structure structure = holder.value();

        ChunkGenerator generator =
                level.getChunkSource().getGenerator();

        ChunkPos origin = ChunkPos.containing(player.blockPosition());

        ChunkAccess startChunk =
                level.getChunk(origin.x(), origin.z());

        if (startChunk.getStartForStructure(structure) != null) {
            throw ALREADY_PRESENT.create();
        }

        StructureStart start = structure.generate(
                holder,
                level.dimension(),
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                origin,
                0,
                level,
                biome -> true
        );

        if (!start.isValid()) {
            throw FAILED.create();
        }

        BoundingBox bounds = start.getBoundingBox();

        ChunkPos min = new ChunkPos(
                bounds.minX() >> 4,
                bounds.minZ() >> 4
        );

        ChunkPos max = new ChunkPos(
                bounds.maxX() >> 4,
                bounds.maxZ() >> 4
        );

        StructureManager manager = level.structureManager();

        ChunkPos.rangeClosed(min, max).forEach(pos -> {
            ChunkAccess chunk = level.getChunk(pos.x(), pos.z());

            BoundingBox chunkBounds = new BoundingBox(
                    pos.getMinBlockX(),
                    level.getMinY(),
                    pos.getMinBlockZ(),
                    pos.getMaxBlockX(),
                    level.getMaxY() + 1,
                    pos.getMaxBlockZ()
            );

            start.placeInChunk(
                    level,
                    manager,
                    generator,
                    level.getRandom(),
                    chunkBounds,
                    pos
            );

            chunk.markUnsaved();
        });

        startChunk.setStartForStructure(structure, start);
        startChunk.markUnsaved();

        long startKey = ChunkPos.pack(origin.x(), origin.z());

        ChunkPos.rangeClosed(min, max).forEach(pos -> {
            ChunkAccess chunk = level.getChunk(pos.x(), pos.z());
            chunk.addReferenceForStructure(structure, startKey);
            chunk.markUnsaved();
        });

        source.sendSuccess(
                () -> Component.literal(
                        "Generated " + structureName
                                + " near chunk " + origin.x()
                                + ", " + origin.z() + "."
                ),
                true
        );

        return 1;
    }
}
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

    /*
     * ============================================================
     * ERROR MESSAGES
     * ============================================================
     */

    private static final SimpleCommandExceptionType
            FORTRESS_WRONG_DIMENSION =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "You must be in the Nether to use /forcerun fortress."
                    )
            );

    private static final SimpleCommandExceptionType
            STRONGHOLD_WRONG_DIMENSION =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "You must be in the Overworld to use /forcerun stronghold."
                    )
            );

    private static final SimpleCommandExceptionType
            ALREADY_PRESENT =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "A structure already starts in this chunk."
                    )
            );

    private static final SimpleCommandExceptionType
            GENERATION_FAILED =
            new SimpleCommandExceptionType(
                    Component.literal(
                            "Structure generation failed."
                    )
            );


    /*
     * ============================================================
     * COMMAND REGISTRATION
     * ============================================================
     */

    @Override
    public void onInitialize() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {

                    dispatcher.register(
                            Commands.literal("forcerun")

                                    .requires(
                                            Commands.hasPermission(
                                                    Commands.LEVEL_GAMEMASTERS
                                            )
                                    )

                                    /*
                                     * /forcerun fortress
                                     */
                                    .then(
                                            Commands.literal("fortress")
                                                    .executes(
                                                            context ->
                                                                    runFortress(
                                                                            context.getSource()
                                                                    )
                                                    )
                                    )

                                    /*
                                     * /forcerun stronghold
                                     */
                                    .then(
                                            Commands.literal("stronghold")
                                                    .executes(
                                                            context ->
                                                                    runStronghold(
                                                                            context.getSource()
                                                                    )
                                                    )
                                    )
                    );
                }
        );
    }


    /*
     * ============================================================
     * FORTRESS COMMAND
     * ============================================================
     *
     * This is the same working Fortress generation approach
     * from your current version.
     */

    private static int runFortress(
            CommandSourceStack source
    ) throws CommandSyntaxException {

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                source.getLevel();


        /*
         * Fortress must be generated in the Nether.
         */

        if (level.dimension() != Level.NETHER) {
            throw FORTRESS_WRONG_DIMENSION.create();
        }


        /*
         * Get the vanilla Fortress structure.
         */

        Holder<Structure> fortressHolder =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getOrThrow(
                                BuiltinStructures.FORTRESS
                        );


        Structure fortress =
                fortressHolder.value();


        /*
         * Generate the structure.
         */

        generateStructure(
                source,
                player,
                level,
                fortressHolder,
                fortress,
                "Nether Fortress"
        );

        return 1;
    }


    /*
     * ============================================================
     * STRONGHOLD COMMAND
     * ============================================================
     *
     * This uses Minecraft's registered Stronghold structure.
     *
     * We are NOT placing a saved structure.
     *
     * The vanilla Stronghold structure is responsible for
     * generating its rooms and pieces.
     */

    private static int runStronghold(
            CommandSourceStack source
    ) throws CommandSyntaxException {

        ServerPlayer player =
                source.getPlayerOrException();

        ServerLevel level =
                source.getLevel();


        /*
         * Strongholds belong in the Overworld.
         */

        if (level.dimension() != Level.OVERWORLD) {
            throw STRONGHOLD_WRONG_DIMENSION.create();
        }


        /*
         * Get Minecraft's actual vanilla Stronghold.
         */

        Holder<Structure> strongholdHolder =
                level.registryAccess()
                        .lookupOrThrow(Registries.STRUCTURE)
                        .getOrThrow(
                                BuiltinStructures.STRONGHOLD
                        );


        Structure stronghold =
                strongholdHolder.value();


        /*
         * Generate the structure.
         */

        generateStructure(
                source,
                player,
                level,
                strongholdHolder,
                stronghold,
                "Stronghold"
        );

        return 1;
    }


    /*
     * ============================================================
     * GENERIC STRUCTURE GENERATOR
     * ============================================================
     *
     * This is shared by Fortress and Stronghold.
     *
     * The important part is that Minecraft creates a real
     * StructureStart from the registered vanilla structure.
     */

    private static void generateStructure(
            CommandSourceStack source,
            ServerPlayer player,
            ServerLevel level,
            Holder<Structure> structureHolder,
            Structure structure,
            String structureName
    ) throws CommandSyntaxException {


        /*
         * Get the world's normal ChunkGenerator.
         */

        ChunkGenerator generator =
                level.getChunkSource().getGenerator();


        /*
         * Find the chunk where the player currently is.
         */

        ChunkPos startChunkPos =
                ChunkPos.containing(
                        player.blockPosition()
                );


        /*
         * Load that chunk.
         */

        ChunkAccess startChunk =
                level.getChunk(
                        startChunkPos.x(),
                        startChunkPos.z()
                );


        /*
         * Don't put another structure start over an existing
         * structure of the same type.
         */

        if (startChunk.getStartForStructure(structure) != null) {

            throw ALREADY_PRESENT.create();
        }


        /*
         * Ask Minecraft to create the actual StructureStart.
         *
         * This is NOT /place structure.
         *
         * The registered vanilla structure gets to generate
         * its own pieces.
         */

        StructureStart start =
                structure.generate(
                        structureHolder,
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


        /*
         * Make sure generation produced something.
         */

        if (!start.isValid()) {

            throw GENERATION_FAILED.create();
        }


        /*
         * Get the complete structure bounding box.
         */

        BoundingBox structureBox =
                start.getBoundingBox();


        /*
         * Convert the structure's block bounding box
         * into chunk coordinates.
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
         * StructureStart.placeInChunk() requires the actual
         * StructureManager.
         */

        StructureManager structureManager =
                level.structureManager();


        /*
         * ========================================================
         * PLACE STRUCTURE PIECES
         * ========================================================
         */

        ChunkPos.rangeClosed(
                minChunk,
                maxChunk
        ).forEach(chunkPos -> {

            /*
             * Load this chunk.
             */

            ChunkAccess chunk =
                    level.getChunk(
                            chunkPos.x(),
                            chunkPos.z()
                    );


            /*
             * Create a box covering this entire chunk.
             *
             * This allows the StructureStart to place every
             * piece that intersects this chunk.
             */

            BoundingBox chunkBox =
                    new BoundingBox(
                            chunkPos.getMinBlockX(),
                            level.getMinY(),
                            chunkPos.getMinBlockZ(),

                            chunkPos.getMaxBlockX(),
                            level.getMaxY() + 1,
                            chunkPos.getMaxBlockZ()
                    );


            /*
             * Let Minecraft place the generated structure
             * pieces into the chunk.
             */

            start.placeInChunk(
                    level,
                    structureManager,
                    generator,
                    level.getRandom(),
                    chunkBox,
                    chunkPos
            );


            /*
             * The chunk changed and needs to be saved.
             */

            chunk.markUnsaved();
        });


        /*
         * ========================================================
         * REGISTER THE STRUCTURE START
         * ========================================================
         *
         * This is important.
         *
         * We don't want Minecraft to merely see blocks.
         * We want the chunk to know that a real structure
         * starts here.
         */

        startChunk.setStartForStructure(
                structure,
                start
        );

        startChunk.markUnsaved();


        /*
         * Pack the origin chunk coordinates.
         *
         * This is the reference used by structure references.
         */

        long startKey =
                ChunkPos.pack(
                        startChunkPos.x(),
                        startChunkPos.z()
                );


        /*
         * ========================================================
         * REGISTER STRUCTURE REFERENCES
         * ========================================================
         *
         * Every chunk touched by the structure receives a
         * reference back to the structure's starting chunk.
         */

        ChunkPos.rangeClosed(
                minChunk,
                maxChunk
        ).forEach(chunkPos -> {

            /*
             * Load the affected chunk.
             */

            ChunkAccess chunk =
                    level.getChunk(
                            chunkPos.x(),
                            chunkPos.z()
                    );


            /*
             * Register the structure reference.
             */

            chunk.addReferenceForStructure(
                    structure,
                    startKey
            );


            /*
             * Save the changed chunk.
             */

            chunk.markUnsaved();
        });


        /*
         * ========================================================
         * SUCCESS MESSAGE
         * ========================================================
         */

        source.sendSuccess(
                () -> Component.literal(
                        "Generated a "
                                + structureName
                                + " at chunk "
                                + startChunkPos.x()
                                + ", "
                                + startChunkPos.z()
                                + "."
                ),
                true
        );
    }
}
