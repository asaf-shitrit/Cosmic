package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.life.FakePlayerService;

public class FakePlayerRemoveCommand extends Command {
    {
        setDescription("Remove all fake players from this map.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int removed = FakePlayerService.getInstance().despawnAll(player.getMap());
        player.dropMessage(6, "Removed " + removed + " fake player(s) from this map.");
    }
}
