import java.io.IOException;
import java.net.ConnectException;

import model.Game;
import model.Move;
import model.PlayerContext;
import model.Wizard;

public final class Runner {
  private final RemoteProcessClient remoteProcessClient;
  private final String token;

  private Runner(String[] args) throws IOException {
    remoteProcessClient = new RemoteProcessClient(args[0], Integer.parseInt(args[1]));
    token = args[2];
  }

  public static void main(String[] args) throws IOException {
    Runner runner = null;
    for (int i = 0; i < 50; ++i) {
      try {
        runner =
            new Runner(
                args.length == 3 ? args : new String[] {"127.0.0.1", "31001", "0000000000000000"});
        break;
      } catch (ConnectException e) {
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (runner == null) {
      throw new ConnectException();
    }
    runner.run();
  }

  public void run() throws IOException {
    try {
      remoteProcessClient.writeToken(token);
      remoteProcessClient.writeProtocolVersion();
      int teamSize = remoteProcessClient.readTeamSize();
      Game game = remoteProcessClient.readGameContext();

      Strategy[] strategies = new Strategy[teamSize];

      for (int strategyIndex = 0; strategyIndex < teamSize; ++strategyIndex) {
        strategies[strategyIndex] = new MyStrategy();
      }

      PlayerContext playerContext;

      while ((playerContext = remoteProcessClient.readPlayerContext()) != null) {
        Wizard[] playerWizards = playerContext.getWizards();
        if (playerWizards == null || playerWizards.length != teamSize) {
          break;
        }

        Move[] moves = new Move[teamSize];

        for (int wizardIndex = 0; wizardIndex < teamSize; ++wizardIndex) {
          Wizard playerWizard = playerWizards[wizardIndex];

          Move move = new Move();
          moves[wizardIndex] = move;
          strategies[wizardIndex /*playerWizard.getTeammateIndex()*/]
              .move(playerWizard, playerContext.getWorld(), game, move);
        }

        remoteProcessClient.writeMoves(moves);
      }
    } finally {
      remoteProcessClient.close();
    }
  }
}
