package org.example.world;

import net.hollowcube.polar.PolarLoader;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class WorldManager {
    public static final Pos SPAWN = new Pos(0.5, 100, 4.5, 180, 0);

    private WorldManager() { }

    public static InstanceContainer createDemoWorld(Consumer<String> log) throws Exception {
        var instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        Path map = Path.of("world.polar");
        if (Files.isRegularFile(map)) {
            instance.setChunkLoader(new PolarLoader(map));
            log.accept("WORLD loaded=" + map.toAbsolutePath());
        } else {
            instance.setGenerator(unit -> unit.modifier().fillHeight(96, 100, Block.STONE));
            log.accept("WORLD generated flat fallback; world.polar was not found");
        }
        instance.setTime(6000);
        var loads = new ArrayList<CompletableFuture<?>>();
        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 1; z++) loads.add(instance.loadChunk(x, z));
        }
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).join();
        // A clear, level stage in memory; the supplied Polar file is never saved over.
        for (int x = -31; x <= 31; x++) {
            for (int z = -31; z <= 31; z++) {
                Block floor = (x % 5 == 0 || z % 5 == 0) ? Block.LIGHT_BLUE_CONCRETE : Block.QUARTZ_BLOCK;
                instance.setBlock(x, 99, z, floor);
                for (int y = 100; y <= 104; y++) instance.setBlock(x, y, z, Block.AIR);
            }
        }
        log.accept("WORLD demo platform x/z=-31..31 feetY=100; grid spacing=5 blocks; map file unchanged");
        return instance;
    }
}
