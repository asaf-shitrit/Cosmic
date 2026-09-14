package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import server.life.FakePlayerService;

public class FakePlayerCommand extends Command {
    private static final int MAX_PER_COMMAND = 30;

    {
        setDescription("Spawn wandering fake players on this map.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.yellowMessage("Syntax: !fakeplayer <count>");
            return;
        }

        int count;
        try {
            count = Integer.parseInt(params[0]);
        } catch (NumberFormatException e) {
            player.yellowMessage("Syntax: !fakeplayer <count>");
            return;
        }

        if (count < 1 || count > MAX_PER_COMMAND) {
            player.yellowMessage("Count must be between 1 and " + MAX_PER_COMMAND + ".");
            return;
        }

        FakePlayerService service = FakePlayerService.getInstance();
        int spawned = service.populate(player.getMap(), count);
        if (spawned < count) {
            player.dropMessage(5, "Only " + spawned + " of " + count
                    + " found ground to stand on. This map may have little walkable space.");
        }
        player.dropMessage(6, "Fake players on this map: " + service.countIn(player.getMap())
                + " (server-wide: " + service.countAll() + ")");
    }
}
