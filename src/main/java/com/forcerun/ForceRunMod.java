
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
