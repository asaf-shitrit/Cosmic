package net.encryption;

public class InitializationVector {
    private final byte[] bytes;

    private InitializationVector(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        return bytes;
    }

    /**
     * Wraps the 4 IV bytes read off the wire. The server generates its own IVs, but a client
     * has to rebuild both of them from the unencrypted hello packet to set up its cyphers.
     */
    public static InitializationVector of(byte[] bytes) {
        if (bytes == null || bytes.length != 4) {
            throw new IllegalArgumentException("An initialization vector must be exactly 4 bytes");
        }
        return new InitializationVector(bytes.clone());
    }

    public static InitializationVector generateSend() {
        byte[] ivSend = {82, 48, 120, getRandomByte()};
        return new InitializationVector(ivSend);
    }

    public static InitializationVector generateReceive() {
        byte[] ivRecv = {70, 114, 122, getRandomByte()};
        return new InitializationVector(ivRecv);
    }

    private static byte getRandomByte() {
        return (byte) (Math.random() * 255);
    }
}
