package server.life;

import client.Client;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import server.maps.AbstractAnimatedMapObject;
import server.maps.MapObjectType;
import tools.PacketCreator;

import java.awt.Point;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A player-looking map object that has no {@link Client} behind it, used to make maps feel
 * populated. Clients render these exactly like any other player, but the server never treats
 * them as one: {@link MapObjectType#FAKE_PLAYER} keeps them out of player counts, mist damage,
 * area buffs and every other bit of logic that casts a player map object to a Character.
 *
 * @see FakePlayerService
 */
public class FakePlayer extends AbstractAnimatedMapObject {
    /**
     * Length in bytes of the movement blob produced by {@link #getWalkMovement}. Mirrors the
     * layout of AbstractAnimatedMapObject's idle movement packet.
     */
    public static final int MOVEMENT_PACKET_LENGTH = 15;

    private static final int STANCE_WALK_RIGHT = 2;
    private static final int STANCE_WALK_LEFT = 3;
    private static final int STANCE_STAND_RIGHT = 4;
    private static final int STANCE_STAND_LEFT = 5;

    private final int characterId;
    private final String name;
    private final int level;
    private final int jobId;
    private final int gender;
    private final int skinColor;
    private final int face;
    private final int hair;
    private final Map<Short, Integer> equips;

    public FakePlayer(int characterId, String name, int level, int jobId, int gender, int skinColor, int face, int hair,
                      Map<Short, Integer> equips) {
        this.characterId = characterId;
        this.name = name;
        this.level = level;
        this.jobId = jobId;
        this.gender = gender;
        this.skinColor = skinColor;
        this.face = face;
        this.hair = hair;
        this.equips = new LinkedHashMap<>(equips);
        setStance(STANCE_STAND_RIGHT);
    }

    public int getCharacterId() {
        return characterId;
    }

    public String getName() {
        return name;
    }

    public int getLevel() {
        return level;
    }

    public int getJobId() {
        return jobId;
    }

    public int getGender() {
        return gender;
    }

    public int getSkinColor() {
        return skinColor;
    }

    public int getFace() {
        return face;
    }

    public int getHair() {
        return hair;
    }

    public Map<Short, Integer> getEquips() {
        return Collections.unmodifiableMap(equips);
    }

    /**
     * Builds a single-step movement blob taking this fake player to {@code destination} over
     * {@code durationMs}. The client interpolates the walk, so callers should keep steps short
     * enough that the fake player is never far from where the server thinks it is.
     */
    public InPacket getWalkMovement(Point destination, int durationMs) {
        OutPacket p = new ByteBufOutPacket();
        p.writeByte(1);                 // movement command count
        p.writeByte(0);                 // absolute movement
        p.writeShort(destination.x);
        p.writeShort(destination.y);
        p.writeShort(0);                // x wobble
        p.writeShort(0);                // y wobble
        p.writeShort(0);                // foothold
        p.writeByte(getStance());
        p.writeShort(durationMs);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
    }

    public void faceWalking(boolean left) {
        setStance(left ? STANCE_WALK_LEFT : STANCE_WALK_RIGHT);
    }

    public void faceStanding() {
        setStance(isFacingLeft() ? STANCE_STAND_LEFT : STANCE_STAND_RIGHT);
    }

    @Override
    public MapObjectType getType() {
        return MapObjectType.FAKE_PLAYER;
    }

    /**
     * Players are keyed by character id rather than by a map-assigned object id, and the client
     * addresses them that way in spawn/move/remove packets. Mirrors Character.
     */
    @Override
    public int getObjectId() {
        return characterId;
    }

    @Override
    public void setObjectId(int id) {
    }

    @Override
    public void sendSpawnData(Client client) {
        client.sendPacket(PacketCreator.spawnFakePlayer(this));
    }

    @Override
    public void sendDestroyData(Client client) {
        client.sendPacket(PacketCreator.removePlayerFromMap(characterId));
    }

    @Override
    public String toString() {
        return name;
    }
}
