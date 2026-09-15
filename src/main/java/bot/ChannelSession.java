package bot;

/**
 * The result of {@link BotSession#loginAndEnterChannel}: a channel connection that has already sent
 * {@code PLAYER_LOGGEDIN} and is ready for a read loop. Shared by every bot entry point that needs
 * to get a character into the game world before doing its own thing from there (see
 * {@link BotSession} and {@link Spectator}).
 */
public record ChannelSession(MapleConnection connection, int charId, String charName) {
    /**
     * The bot's own character name. A bot needs this to tell whether a line of chat was addressed to
     * it - the server says who is speaking but never reminds a client what it is called.
     */
}
