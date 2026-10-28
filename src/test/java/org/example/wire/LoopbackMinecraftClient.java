package org.example.wire;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.MainHand;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.message.ChatMessageType;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientSettingsPacket;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.configuration.ClientSelectKnownPacksPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.play.ClientChunkBatchReceivedPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerLoadedPacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.configuration.SelectKnownPacksPacket;
import net.minestom.server.network.packet.server.login.LoginDisconnectPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.ChunkBatchFinishedPacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.DeathCombatEventPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.RespawnPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.server.network.player.ClientSettings;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.InflaterInputStream;

/**
 * Small headless protocol client. Every outbound request is serialized and written to a real
 * loopback TCP socket; the server performs its normal login, configuration and play dispatch.
 * Unrelated inbound payloads (chunks, inventory, recipes, etc.) are skipped by their framed length.
 * This helper is not a rendered Minecraft client and does not demonstrate local visual prediction.
 */
final class LoopbackMinecraftClient implements AutoCloseable {
    private static final Set<Class<?>> DECODED_PACKETS = Set.of(
            SetCompressionPacket.class, LoginSuccessPacket.class, LoginDisconnectPacket.class,
            DisconnectPacket.class, SelectKnownPacksPacket.class, FinishConfigurationPacket.class,
            KeepAlivePacket.class, PlayerPositionAndLookPacket.class, ChunkBatchFinishedPacket.class,
            SpawnEntityPacket.class, EntityPositionPacket.class, EntityPositionAndRotationPacket.class,
            EntityPositionSyncPacket.class, EntityTeleportPacket.class, UpdateHealthPacket.class,
            ChangeGameStatePacket.class, DeathCombatEventPacket.class, RespawnPacket.class);

    record Received(long sequence, ServerPacket packet) {}
    record ObservedPosition(long sequence, int entityId, Pos position) {}

    private final Socket socket = new Socket();
    private final InputStream input;
    private final OutputStream output;
    private final Thread reader;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final List<ObservedPosition> observedPositions = new CopyOnWriteArrayList<>();
    private final Map<Integer, Pos> entityPositions = new ConcurrentHashMap<>();
    private volatile ConnectionState state = ConnectionState.HANDSHAKE;
    private volatile int compressionThreshold;
    private volatile boolean closed;
    private volatile Throwable failure;
    private volatile long sequence;

    LoopbackMinecraftClient(int port, String username) throws IOException {
        socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        socket.setTcpNoDelay(true);
        input = socket.getInputStream();
        output = socket.getOutputStream();
        send(new ClientHandshakePacket(MinecraftServer.PROTOCOL_VERSION, "localhost", port,
                ClientHandshakePacket.Intent.LOGIN));
        state = ConnectionState.LOGIN;
        send(new ClientLoginStartPacket(username,
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))));
        reader = Thread.ofVirtual().name("wire-client-" + username).start(this::readLoop);
    }

    synchronized void send(ClientPacket packet) throws IOException {
        checkHealthy();
        NetworkBuffer frame = PacketWriting.allocateTrimmedPacket(state, packet, compressionThreshold);
        byte[] bytes = new byte[Math.toIntExact(frame.writeIndex())];
        frame.copyTo(0, bytes, 0, bytes.length);
        output.write(bytes);
        output.flush();
    }

    long checkpoint() {
        return sequence;
    }

    List<Received> receivedSince(long checkpoint) {
        return received.stream().filter(value -> value.sequence() > checkpoint).toList();
    }

    List<ObservedPosition> positionsSince(long checkpoint, int entityId) {
        return observedPositions.stream()
                .filter(value -> value.sequence() > checkpoint && value.entityId() == entityId).toList();
    }

    Pos observedPosition(int entityId) {
        return entityPositions.get(entityId);
    }

    void checkHealthy() {
        if (failure != null) throw new AssertionError("Loopback protocol client failed in " + state, failure);
        if (closed) throw new AssertionError("Loopback protocol client is closed");
    }

    private void readLoop() {
        try {
            while (!closed) {
                int frameLength = readVarInt(input);
                if (frameLength <= 0 || frameLength > 2_097_151) {
                    throw new IOException("Invalid server frame size: " + frameLength);
                }
                byte[] bytes = input.readNBytes(frameLength);
                if (bytes.length != frameLength) throw new EOFException("Truncated server frame");
                NetworkBuffer payload = payload(bytes);
                int packetId = payload.read(NetworkBuffer.VAR_INT);
                var info = PacketVanilla.SERVER_PACKET_PARSER.stateRegistry(state).packetInfo(packetId);
                if (!DECODED_PACKETS.contains(info.packetClass())) continue;
                ServerPacket packet = info.serializer().read(payload);
                long packetSequence = ++sequence;
                received.add(new Received(packetSequence, packet));
                recordObservedPosition(packetSequence, packet);
                respond(packet);
            }
        } catch (Throwable problem) {
            if (!closed) failure = problem;
        }
    }

    private NetworkBuffer payload(byte[] framedBytes) throws IOException {
        if (compressionThreshold <= 0) {
            return NetworkBuffer.wrap(framedBytes, 0, framedBytes.length, MinecraftServer.getRegistries());
        }
        var compressedInput = new ByteArrayInputStream(framedBytes);
        int uncompressedLength = readVarInt(compressedInput);
        byte[] data;
        if (uncompressedLength == 0) {
            data = compressedInput.readAllBytes();
        } else {
            if (uncompressedLength < 0 || uncompressedLength > 2_097_151) {
                throw new IOException("Invalid uncompressed server frame size: " + uncompressedLength);
            }
            try (var inflater = new InflaterInputStream(compressedInput)) {
                data = inflater.readNBytes(uncompressedLength + 1);
            }
            if (data.length != uncompressedLength) throw new IOException("Invalid compressed server frame");
        }
        return NetworkBuffer.wrap(data, 0, data.length, MinecraftServer.getRegistries());
    }

    private void respond(ServerPacket packet) throws IOException {
        switch (packet) {
            case SetCompressionPacket compression -> compressionThreshold = compression.threshold();
            case LoginSuccessPacket ignored -> {
                send(new ClientLoginAcknowledgedPacket());
                state = ConnectionState.CONFIGURATION;
                send(new ClientSettingsPacket(new ClientSettings(Locale.US, (byte) 2,
                        ChatMessageType.FULL, true, (byte) 0x7f, MainHand.RIGHT, false, true,
                        ClientSettings.ParticleSetting.MINIMAL)));
            }
            case SelectKnownPacksPacket packs -> send(new ClientSelectKnownPacksPacket(packs.entries()));
            case FinishConfigurationPacket ignored -> {
                send(new ClientFinishConfigurationPacket());
                state = ConnectionState.PLAY;
                send(new ClientPlayerLoadedPacket());
            }
            case KeepAlivePacket keepAlive -> send(new ClientKeepAlivePacket(keepAlive.id()));
            case PlayerPositionAndLookPacket correction ->
                    send(new ClientTeleportConfirmPacket(correction.teleportId()));
            case ChunkBatchFinishedPacket ignored -> send(new ClientChunkBatchReceivedPacket(64));
            case LoginDisconnectPacket disconnect -> throw new IOException("Login rejected: " + disconnect);
            case DisconnectPacket disconnect -> throw new IOException("Disconnected: " + disconnect);
            default -> { }
        }
    }

    private void recordObservedPosition(long packetSequence, ServerPacket packet) {
        switch (packet) {
            case SpawnEntityPacket spawn -> observe(packetSequence, spawn.entityId(), spawn.position());
            case EntityPositionSyncPacket sync -> observe(packetSequence, sync.entityId(), sync.position().asPos());
            case EntityTeleportPacket teleport -> {
                Pos position = teleport.position();
                if ((teleport.flags() & RelativeFlags.COORD) != 0) {
                    Pos previous = entityPositions.get(teleport.entityId());
                    if (previous == null) throw new AssertionError("Relative teleport received before entity spawn");
                    position = position.add(
                            (teleport.flags() & RelativeFlags.X) != 0 ? previous.x() : 0,
                            (teleport.flags() & RelativeFlags.Y) != 0 ? previous.y() : 0,
                            (teleport.flags() & RelativeFlags.Z) != 0 ? previous.z() : 0);
                }
                observe(packetSequence, teleport.entityId(), position);
            }
            case EntityPositionPacket relative -> observeRelative(packetSequence, relative.entityId(),
                    relative.deltaX(), relative.deltaY(), relative.deltaZ());
            case EntityPositionAndRotationPacket relative -> observeRelative(packetSequence, relative.entityId(),
                    relative.deltaX(), relative.deltaY(), relative.deltaZ());
            default -> { }
        }
    }

    private void observeRelative(long packetSequence, int entityId, short dx, short dy, short dz) {
        Pos previous = entityPositions.get(entityId);
        if (previous == null) throw new AssertionError("Movement received before entity spawn: " + entityId);
        observe(packetSequence, entityId, previous.add(dx / 4096.0, dy / 4096.0, dz / 4096.0));
    }

    private void observe(long packetSequence, int entityId, Pos position) {
        entityPositions.put(entityId, position);
        observedPositions.add(new ObservedPosition(packetSequence, entityId, position));
    }

    private static int readVarInt(InputStream source) throws IOException {
        int result = 0;
        for (int byteIndex = 0; byteIndex < 5; byteIndex++) {
            int next = source.read();
            if (next == -1) throw new EOFException("Socket closed while reading frame size");
            result |= (next & 0x7f) << (7 * byteIndex);
            if ((next & 0x80) == 0) return result;
        }
        throw new IOException("VarInt exceeds five bytes");
    }

    @Override
    public void close() throws IOException {
        closed = true;
        socket.close();
        try {
            reader.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
