import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import model.ActionType;
import model.Faction;
import model.Game;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.Unit;
import model.Wizard;
import model.World;

public final class MyStrategy implements Strategy {

  private static final int STRAFE_PERIOD = 80;

  private static final List<HexPoint> HEX_DIRECTIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              new HexPoint(1, 0),
              new HexPoint(1, -1),
              new HexPoint(0, -1),
              new HexPoint(-1, 0),
              new HexPoint(-1, 1),
              new HexPoint(0, 1)));

  private double HEXAGON_SIZE;

  private Map<HexPoint, MapPoint> map;
  private Faction ENEMY_FRACTION;

  private Random random;
  private Visualizer debug;

  private Wizard self;
  private World world;
  private Game game;
  private Move move;

  @Override
  public void move(Wizard self, World world, Game game, Move move) {
    initializeTick(self, world, game, move);
    initializeStrategy();
    updateMap();

    MapPoint bestPoint = null;
    double bestScore = 0;
    double worstScore = 0;
    for (MapPoint point : map.values()) {
      if (point.getDistanceTo(self) > self.getVisionRange()) {
        continue;
      }
      double score = scorePoint(point);
      if (bestPoint == null) {
        bestPoint = point;
        bestScore = score;
        worstScore = score;
      } else {
        worstScore = StrictMath.min(worstScore, score);
        if (score > bestScore) {
          bestPoint = point;
          bestScore = score;
        }
      }
    }

    MapPoint currentPoint = map.get(pixelToHex(self.getX(), self.getY()));
    List<MapPoint> path = findPath(currentPoint, bestPoint);
    MapPoint nextPoint = path.size() >= 1 ? path.get(1) : currentPoint;

    if (debug != null) {
      drawHexTile(nextPoint, Color.blue);
      drawPath(path, Color.red);
      drawHexTile(bestPoint, Color.red);

      for (MapPoint point : map.values()) {
        if (point.getDistanceTo(self) > self.getVisionRange()) {
          continue;
        }
        double score = scorePoint(point);
        double normedScore = StrictMath.max(0.3, (score - worstScore) / (bestScore - worstScore));
        double clampedComplement = StrictMath.max(0, StrictMath.min(1, 1 - normedScore));
        Color color = new Color((float) clampedComplement, 1f, (float) clampedComplement);
        debug.fillCircle(point.getX(), point.getY(), 3, color);
      }

      debug.drawCircle(self.getX(), self.getY(), self.getVisionRange(), Color.lightGray);

      for (Wizard wizard : world.getWizards()) {
        if (wizard.isMaster()) {
          double r = wizard.getRadius() / 2;
          debug.fillRect(
              wizard.getX() - r,
              wizard.getY() - r,
              wizard.getX() + r,
              wizard.getY() + r,
              Color.red);
        }
      }

      debug.drawBeforeScene();
    }

    goTo(nextPoint);

    LivingUnit target = getTarget();
    if (target != null) {
      double distance = self.getDistanceTo(target);

      if (distance <= self.getCastRange()) {
        double angle = self.getAngleTo(target);
        move.setTurn(angle);

        if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
          move.setAction(ActionType.MAGIC_MISSILE);
          move.setCastAngle(angle);
          move.setMinCastDistance(distance - target.getRadius() + game.getMagicMissileRadius());
        }

        int strafeDirection = world.getTickIndex() % STRAFE_PERIOD * 2 < STRAFE_PERIOD ? 1 : -1;
        move.setStrafeSpeed(strafeDirection * game.getWizardStrafeSpeed());
      }
    }
  }

  private void drawPath(List<MapPoint> path, Color color) {
    for (int i = 1; i < path.size(); ++i) {
      debug.drawLine(
          path.get(i - 1).getX(),
          path.get(i - 1).getY(),
          path.get(i).getX(),
          path.get(i).getY(),
          color);
    }
  }

  private void drawHexTile(MapPoint point, Color color) {
    List<MapPoint> points = point.getNeighbors();
    for (int i = 0; i < points.size(); ++i) {
      int j = (i + 1) % points.size();
      debug.drawLine(
          points.get(i).getX(),
          points.get(i).getY(),
          points.get(j).getX(),
          points.get(j).getY(),
          color);
    }
  }

  private List<MapPoint> findPath(MapPoint start, MapPoint end) {
    // Breadth-first search.
    Map<MapPoint, MapPoint> prev = new IdentityHashMap<>();
    prev.put(start, null);
    Queue<MapPoint> queue = new LinkedList<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      MapPoint point = queue.poll();
      if (point.equals(end)) {
        break;
      }
      for (MapPoint neighbor : point.getNeighbors()) {
        if (!prev.containsKey(neighbor)) {
          prev.put(neighbor, point);
          queue.add(neighbor);
        }
      }
    }

    if (!prev.containsKey(end)) {
      return null;
    }

    List<MapPoint> path = new ArrayList<>();
    for (MapPoint point = end; point != null; point = prev.get(point)) {
      path.add(point);
    }
    Collections.reverse(path);
    return path;
  }

  private void updateMap() {
    if (map == null) {
      map = createMap();
    }
  }

  private double scorePoint(Point2D point) {
    double score = 0;
    for (Wizard wizard : world.getWizards()) {
      double distance = point.getDistanceTo(wizard);

      if (wizard.isMaster() && distance < wizard.getVisionRange() / 3) {
        double MASTER_VISION_RANGE_FACTOR = 50;
        score += MASTER_VISION_RANGE_FACTOR;
      }

      if (isAlly(wizard) && !wizard.isMe()) {
        if (distance < wizard.getCastRange()) {
          if (StrictMath.abs(wizard.getAngleTo(point.getX(), point.getY()))
              < StrictMath.PI / 4) { // TODO: Discount for rotation time.
            double ALLY_WIZARD_CAST_RANGE_FACTOR = 10;
            score += ALLY_WIZARD_CAST_RANGE_FACTOR;
          }
        }
      }
    }
    for (Minion minion : world.getMinions()) {
      double distance = point.getDistanceTo(minion);
      if (isAlly(minion)) {
        double angle = minion.getAngleTo(point.getX(), point.getY());
        if (minion.getType() == MinionType.ORC_WOODCUTTER
            && distance < game.getOrcWoodcutterAttackRange()) {
          if (StrictMath.abs(angle)
              < game.getOrcWoodcutterAttackSector() / 2 * 2) { // TODO: Discount for rotation time.
            double ALLY_WOODCUTTER_ATTACK_RANGE_FACTOR = 5;
            score += ALLY_WOODCUTTER_ATTACK_RANGE_FACTOR;
          }
        }
        if (minion.getType() == MinionType.FETISH_BLOWDART
            && distance < game.getFetishBlowdartAttackRange()) {
          if (StrictMath.abs(angle)
              < game.getFetishBlowdartAttackSector() / 2 * 2) { // TODO: Discount for rotation time.
            double ALLY_FETISH_ATTACK_RANGE_FACTOR = 5;
            score += ALLY_FETISH_ATTACK_RANGE_FACTOR;
          }
        }
      }
    }
    return score;
  }

  private void initializeStrategy() {
    if (random != null) {
      return;
    }
    random = new Random(game.getRandomSeed());

    try {
      Class<?> klass = Class.forName("DebugVisualizer");
      Object instance = klass.getConstructor().newInstance();
      debug = (Visualizer) instance;
    } catch (ClassNotFoundException e) {
      // Visualizer is not available.
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    ENEMY_FRACTION = self.getFaction() == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

    HEXAGON_SIZE = 20;
  }

  private Point2D hexToPixel(double q, double r) {
    double x = HEXAGON_SIZE * 3 / 2 * q;
    double y = HEXAGON_SIZE * StrictMath.sqrt(3) * (r + q / 2);
    return new Point2D(x, y);
  }

  private HexPoint pixelToHex(double x, double y) {
    double q = x * 2 / 3 / HEXAGON_SIZE;
    double r = (-x / 3 + StrictMath.sqrt(3) / 3 * y) / HEXAGON_SIZE;
    double s = -q - r;
    int rx = (int) StrictMath.round(q);
    int ry = (int) StrictMath.round(r);
    int rz = (int) StrictMath.round(s);
    double x_diff = StrictMath.abs(rx - q);
    double y_diff = StrictMath.abs(ry - r);
    double z_diff = StrictMath.abs(rz - s);
    if (x_diff > y_diff && x_diff > z_diff) {
      rx = -ry - rz;
    } else if (y_diff > z_diff) {
      ry = -rx - rz;
    }
    return new HexPoint(rx, ry);
  }

  private Map<HexPoint, MapPoint> createMap() {
    Map<HexPoint, MapPoint> map = new HashMap<>();
    double radius = self.getRadius();
    for (int q = 0; q < 600; ++q) {
      for (int r = 0; r < 600; ++r) {
        Point2D point = hexToPixel(q, r);
        if (radius < point.getX()
            && point.getX() < world.getWidth() - radius
            && radius < point.getY()
            && point.getY() < world.getHeight() - radius) {
          map.put(new HexPoint(q, r), new MapPoint(point));
        }
      }
    }

    List<LivingUnit> obstacles = new ArrayList<>();
    obstacles.addAll(Arrays.asList(world.getTrees()));
    obstacles.addAll(Arrays.asList(world.getBuildings()));
    map.values()
        .removeIf(
            point -> {
              boolean isReachable = true;
              for (LivingUnit unit : obstacles) {
                // TODO: Convert Point2D to Hex and find neighbors instead.
                if (point.getDistanceTo(unit) < self.getRadius() + unit.getRadius()) {
                  isReachable = false;
                  break;
                }
              }
              return !isReachable;
            });

    for (Map.Entry<HexPoint, MapPoint> entry : map.entrySet()) {
      int q = entry.getKey().getQ();
      int r = entry.getKey().getR();
      MapPoint point = entry.getValue();

      List<MapPoint> neighbors = new ArrayList<>();
      for (HexPoint delta : HEX_DIRECTIONS) {
        MapPoint neighbor = map.get(new HexPoint(q + delta.q, r + delta.r));
        if (neighbor == null) {
          continue;
        }
        neighbors.add(neighbor);
      }
      point.setNeighbors(neighbors);
    }

    return Collections.unmodifiableMap(map);
  }

  private void initializeTick(Wizard self, World world, Game game, Move move) {
    this.self = self;
    this.world = world;
    this.game = game;
    this.move = move;
  }

  private void goTo(Point2D point) {
    double angle = turnTo(point);
    if (StrictMath.abs(angle) < game.getStaffSector() / 4.0D) {
      move.setSpeed(game.getWizardForwardSpeed());
    }
  }

  private double turnTo(Point2D point) {
    double angle = self.getAngleTo(point.getX(), point.getY());
    move.setTurn(angle);
    return angle;
  }

  private LivingUnit getTarget() {
    List<LivingUnit> targets = new ArrayList<>();
    targets.addAll(Arrays.asList(world.getBuildings()));
    targets.addAll(Arrays.asList(world.getWizards()));
    targets.addAll(Arrays.asList(world.getMinions()));

    LivingUnit bestTarget = null;

    for (LivingUnit target : targets) {
      if (!isEnemy(target)) {
        continue;
      }

      double distance = self.getDistanceTo(target);
      if (distance > self.getCastRange()) {
        continue;
      }

      if (bestTarget == null || target.getLife() < bestTarget.getLife()) {
        bestTarget = target;
      }
    }

    return bestTarget;
  }

  private boolean isEnemy(LivingUnit unit) {
    return unit.getFaction() == ENEMY_FRACTION;
  }

  private boolean isAlly(LivingUnit unit) {
    return unit.getFaction() == self.getFaction();
  }

  public interface Visualizer {
    public void drawBeforeScene();

    public void drawAfterScene();

    public void drawCircle(double x, double y, double r, Color color);

    public void fillCircle(double x, double y, double r, Color color);

    public void drawRect(double x1, double y1, double x2, double y2, Color color);

    public void fillRect(double x1, double y1, double x2, double y2, Color color);

    public void drawLine(double x1, double y1, double x2, double y2, Color color);

    public void showText(double x, double y, String msg, Color color);
  }

  private static class Point2D {
    private final double x;
    private final double y;

    private Point2D(double x, double y) {
      this.x = x;
      this.y = y;
    }

    private Point2D(Unit unit) {
      this(unit.getX(), unit.getY());
    }

    public double getX() {
      return x;
    }

    public double getY() {
      return y;
    }

    public double getDistanceTo(double x, double y) {
      return StrictMath.hypot(this.x - x, this.y - y);
    }

    public double getDistanceTo(Point2D point) {
      return getDistanceTo(point.x, point.y);
    }

    public double getDistanceTo(Unit unit) {
      return getDistanceTo(unit.getX(), unit.getY());
    }
  }

  private static class MapPoint extends Point2D {
    private List<MapPoint> neighbors;

    public MapPoint(Point2D point) {
      super(point.getX(), point.getY());
    }

    public List<MapPoint> getNeighbors() {
      return neighbors;
    }

    public void setNeighbors(List<MapPoint> neighbors) {
      if (this.neighbors != null) {
        throw new RuntimeException("Neighbors already set.");
      }
      this.neighbors = neighbors;
    }
  }

  private static class HexPoint {
    private final int q;
    private final int r;

    public HexPoint(int q, int r) {
      this.q = q;
      this.r = r;
    }

    public int getQ() {
      return q;
    }

    public int getR() {
      return r;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      HexPoint hexPoint = (HexPoint) o;

      if (q != hexPoint.q) return false;
      return r == hexPoint.r;
    }

    @Override
    public int hashCode() {
      int result = q;
      result = 31 * result + r;
      return result;
    }
  }
}
