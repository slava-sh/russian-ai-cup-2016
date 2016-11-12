import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import model.ActionType;
import model.Building;
import model.Faction;
import model.Game;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.Tree;
import model.Unit;
import model.Wizard;
import model.World;

public final class MyStrategy implements Strategy {

  private Brain brain;

  @Override
  public void move(Wizard self, World world, Game game, Move move) {
    if (brain == null) {
      brain = new Brain(self, world, game);
    }
    brain.move(self, world, game, move);
  }

  public interface Visualizer {
    void drawBeforeScene();

    void drawAfterScene();

    void drawCircle(double x, double y, double r, Color color);

    void fillCircle(double x, double y, double r, Color color);

    void drawRect(double x1, double y1, double x2, double y2, Color color);

    void fillRect(double x1, double y1, double x2, double y2, Color color);

    void drawLine(double x1, double y1, double x2, double y2, Color color);

    void showText(double x, double y, String msg, Color color);
  }

  private static class Point2D {

    private final double x;
    private final double y;

    public Point2D(double x, double y) {
      this.x = x;
      this.y = y;
    }

    public Point2D(Unit unit) {
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

  private static class FieldPoint extends Point2D {

    private List<FieldPoint> neighbors;
    private boolean isReachable;
    private double score;

    public FieldPoint(Point2D point) {
      super(point.getX(), point.getY());
    }

    public List<FieldPoint> getNeighbors() {
      return neighbors;
    }

    public void setNeighbors(List<FieldPoint> neighbors) {
      if (this.neighbors != null) {
        throw new RuntimeException("Neighbors already set.");
      }
      this.neighbors = Collections.unmodifiableList(neighbors);
    }

    public boolean isReachable() {
      return isReachable;
    }

    public void setReachable(boolean reachable) {
      isReachable = reachable;
    }

    public double getScore() {
      return score;
    }

    public void setScore(double score) {
      this.score = score;
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

  private static class Brain {

    private final Faction ENEMY_FRACTION;

    private final Visualizer debug;
    private final Random random;
    private final Field field;
    private final Walker walker;

    private Wizard self;
    private World world;
    private Game game;

    public Brain(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;

      ENEMY_FRACTION = self.getFaction() == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

      Visualizer debugVisualizer = null;
      try {
        Class<?> klass = Class.forName("DebugVisualizer");
        Object instance = klass.getConstructor().newInstance();
        debugVisualizer = (Visualizer) instance;
      } catch (ClassNotFoundException e) {
        // Visualizer is not available.
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      debug = debugVisualizer;

      random = new Random(game.getRandomSeed());

      field = new Field(this);

      walker = new Walker(this);
    }

    public void move(Wizard self, World world, Game game, Move move) {
      this.self = self;
      this.world = world;
      this.game = game;

      field.update();

      FieldPoint bestPoint = null;
      FieldPoint worstPoint = null;
      for (FieldPoint point : field.getPoints()) {
        if (!point.isReachable() || point.getDistanceTo(self) > self.getVisionRange()) {
          continue;
        }

        if (bestPoint == null) {
          bestPoint = point;
          worstPoint = point;
        } else {
          if (point.getScore() > bestPoint.getScore()) {
            bestPoint = point;
          }
          if (point.getScore() < worstPoint.getScore()) {
            worstPoint = point;
          }
        }

        if (debug != null) {
          debug.showText(
              point.getX(),
              point.getY(),
              ((Number) (int) point.getScore()).toString(),
              Color.black);
        }
      }

      FieldPoint currentPoint = field.getClosestPoint(self.getX(), self.getY());
      List<FieldPoint> path = null;
      if (walker.target == null
          || !walker.target.isReachable()
          || walker.target.getDistanceTo(self) > self.getVisionRange()
          || bestPoint.getScore() - walker.target.getScore() > walker.RETARGET_THRESHOLD) {
        path = field.findPath(currentPoint, bestPoint);
        if (path.size() > 1) {
          walker.target = bestPoint;
        } else {
          path = null;
        }
      }
      if (path == null) {
        path = field.findPath(currentPoint, walker.target);
      }
      FieldPoint nextPoint = path.size() > 1 ? path.get(1) : currentPoint;

      if (debug != null) {
        drawHexTile(nextPoint, Color.blue);
        drawPath(path, Color.red);
        drawHexTile(walker.target, Color.red);
        debug.drawCircle(bestPoint.getX(), bestPoint.getY(), self.getRadius() / 2, Color.green);

        double scoreSpread = bestPoint.getScore() - worstPoint.getScore();
        for (FieldPoint point : field.getPoints()) {
          if (!point.isReachable() || point.getDistanceTo(self) > self.getVisionRange()) {
            continue;
          }
          double normedScore = (point.getScore() - worstPoint.getScore()) / scoreSpread;
          double k = StrictMath.max(0, StrictMath.min(1, 1 - StrictMath.max(0.3, normedScore)));
          Color color = new Color((float) k, 1f, (float) k);
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

      LivingUnit target = getTarget();

      walker.goTo(nextPoint, move);
      if (target == null) {
        walker.turnTo(nextPoint, move);
      } else {
        walker.turnTo(new Point2D(target), move);
        double distance = self.getDistanceTo(target);
        if (distance <= self.getCastRange()) {
          double angle = self.getAngleTo(target);
          if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
            move.setAction(ActionType.MAGIC_MISSILE);
            move.setCastAngle(angle);
            move.setMinCastDistance(distance - target.getRadius() + game.getMagicMissileRadius());
          }
        }
      }
    }

    private void drawPath(List<FieldPoint> path, Color color) {
      for (int i = 1; i < path.size(); ++i) {
        debug.drawLine(
            path.get(i - 1).getX(),
            path.get(i - 1).getY(),
            path.get(i).getX(),
            path.get(i).getY(),
            color);
      }
    }

    private void drawHexTile(FieldPoint point, Color color) {
      List<FieldPoint> points = point.getNeighbors();
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

    public boolean isEnemy(LivingUnit unit) {
      return unit.getFaction() == ENEMY_FRACTION;
    }

    public boolean isAlly(LivingUnit unit) {
      return unit.getFaction() == self.getFaction();
    }
  }

  private static class Field {

    private static final double REACHABILITY_EPS = 3;
    private static final List<HexPoint> HEX_DIRECTIONS =
        Collections.unmodifiableList(
            Arrays.asList(
                new HexPoint(1, 0),
                new HexPoint(1, -1),
                new HexPoint(0, -1),
                new HexPoint(-1, 0),
                new HexPoint(-1, 1),
                new HexPoint(0, 1)));

    private final double HEXAGON_SIZE;

    private final Brain brain;
    private Map<HexPoint, FieldPoint> hexToPoint;

    public Field(Brain brain) {
      this.brain = brain;

      HEXAGON_SIZE = brain.self.getRadius();
    }

    public void update() {
      if (hexToPoint == null) {
        hexToPoint = createHexToPoint();
      }
      hexToPoint
          .values()
          .forEach(
              point -> {
                if (point.getDistanceTo(brain.self) < brain.self.getVisionRange()) {
                  updatePoint(point);
                }
              });
    }

    public Collection<FieldPoint> getPoints() {
      return hexToPoint.values();
    }

    public FieldPoint getClosestPoint(double x, double y) {
      // TODO: Handle the case when the hex point is not in the map.
      return hexToPoint.get(pixelToHex(x, y));
    }

    public List<FieldPoint> findPath(FieldPoint start, FieldPoint end) {
      // Breadth-first search.
      Map<FieldPoint, FieldPoint> prev = new IdentityHashMap<>();
      prev.put(start, null);
      Queue<FieldPoint> queue = new LinkedList<>();
      queue.add(start);
      while (!queue.isEmpty()) {
        FieldPoint point = queue.poll();
        if (point.equals(end)) {
          break;
        }
        for (FieldPoint neighbor : point.getNeighbors()) {
          if (neighbor.isReachable() && !prev.containsKey(neighbor)) {
            prev.put(neighbor, point);
            queue.add(neighbor);

            if (brain.debug != null) {
              brain.debug.drawLine(
                  neighbor.getX(), neighbor.getY(), point.getX(), point.getY(), Color.lightGray);
            }
          }
        }
      }

      List<FieldPoint> path = new ArrayList<>();
      for (FieldPoint point = end; point != null; point = prev.get(point)) {
        path.add(point);
      }
      Collections.reverse(path);
      return path;
    }

    private void updatePoint(FieldPoint point) {
      double score = 0;
      boolean isReachable = true;

      double max_sum = brain.world.getHeight() + brain.world.getWidth();
      score +=
          (StrictMath.round(brain.world.getHeight() - point.getY()) + point.getX()) / max_sum * 50;

      for (Wizard wizard : brain.world.getWizards()) {
        double distance = point.getDistanceTo(wizard);

        if (!wizard.isMe()
            && distance < brain.self.getRadius() + wizard.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }

        if (brain.isAlly(wizard)) {
          if (wizard.isMaster() && distance < wizard.getVisionRange() / 3) {
            double MASTER_VISION_RANGE_FACTOR = 20;
            score += MASTER_VISION_RANGE_FACTOR;
          }

          if (!wizard.isMe()) {
            if (distance < wizard.getCastRange()) {
              if (StrictMath.abs(wizard.getAngleTo(point.getX(), point.getY()))
                  < StrictMath.PI / 4) {
                // TODO: Discount for rotation time.
                double ALLY_WIZARD_CAST_RANGE_FACTOR = 15;
                score += ALLY_WIZARD_CAST_RANGE_FACTOR;
              }
            }

            if (distance < wizard.getRadius() + 1.5 * brain.self.getRadius()) {
              double TIGHT_CLOSE_TO_ALLY_WIZARD_FACTOR = -10;
              score += TIGHT_CLOSE_TO_ALLY_WIZARD_FACTOR;
            }
          }
        }
      }

      for (Minion minion : brain.world.getMinions()) {
        double distance = point.getDistanceTo(minion);

        if (distance < brain.self.getRadius() + minion.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }

        if (brain.isAlly(minion)) {
          double angle = minion.getAngleTo(point.getX(), point.getY());

          if (minion.getType() == MinionType.ORC_WOODCUTTER && distance < minion.getVisionRange()) {
            // TODO: Discount for rotation time.
            double ALLY_WOODCUTTER_VISION_RANGE_FACTOR = 3;
            score += ALLY_WOODCUTTER_VISION_RANGE_FACTOR;
          }

          if (minion.getType() == MinionType.FETISH_BLOWDART
              && distance < minion.getVisionRange()) {
            //if (StrictMath.abs(angle) < brain.game.getFetishBlowdartAttackSector() / 2 * 2) {
            // TODO: Discount for rotation time.
            double ALLY_FETISH_VISION_RANGE_FACTOR = 5;
            score += ALLY_FETISH_VISION_RANGE_FACTOR;
          }
        }
      }

      for (Building building : brain.world.getBuildings()) {
        double distance = point.getDistanceTo(building);
        if (distance < brain.self.getRadius() + building.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }
        if (brain.isAlly(building)) {
          if (distance < building.getVisionRange()) {
            double ALLY_BUILDING_VISION_RANGE_FACTOR = 0;
            score += ALLY_BUILDING_VISION_RANGE_FACTOR;
          }
        }
      }

      for (Tree tree : brain.world.getTrees()) {
        if (point.getDistanceTo(tree)
            < brain.self.getRadius() + tree.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }
      }

      point.setScore(score);
      point.setReachable(isReachable);
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

    private Map<HexPoint, FieldPoint> createHexToPoint() {
      Map<HexPoint, FieldPoint> map = new HashMap<>();
      double radius = brain.self.getRadius();
      for (int q = -100; q <= 100; ++q) {
        for (int r = -100; r <= 100; ++r) {
          Point2D point = hexToPixel(q, r);
          if (radius < point.getX()
              && point.getX() < brain.world.getWidth() - radius
              && radius < point.getY()
              && point.getY() < brain.world.getHeight() - radius) {
            map.put(pixelToHex(point.getX(), point.getY()), new FieldPoint(point));
          }
        }
      }

      for (Map.Entry<HexPoint, FieldPoint> entry : map.entrySet()) {
        int q = entry.getKey().getQ();
        int r = entry.getKey().getR();
        FieldPoint point = entry.getValue();

        List<FieldPoint> neighbors = new ArrayList<>();
        for (HexPoint delta : HEX_DIRECTIONS) {
          FieldPoint neighbor = map.get(new HexPoint(q + delta.q, r + delta.r));
          if (neighbor == null) {
            continue;
          }
          neighbors.add(neighbor);
        }
        point.setNeighbors(neighbors);
      }

      return Collections.unmodifiableMap(map);
    }
  }

  private static class Walker {

    private static final double RETARGET_THRESHOLD = 10;

    private final Brain brain;

    private FieldPoint target;

    private Walker(Brain brain) {
      this.brain = brain;
    }

    private void goTo(Point2D point, Move move) {
      double speed = brain.self.getDistanceTo(point.getX(), point.getY());
      double angle = brain.self.getAngleTo(point.getX(), point.getY());
      // TODO: Account for the wizard having different speeds in different directions.
      move.setSpeed(speed * StrictMath.cos(angle));
      move.setStrafeSpeed(speed * StrictMath.sin(angle));
    }

    private double turnTo(Point2D point, Move move) {
      double angle = brain.self.getAngleTo(point.getX(), point.getY());
      move.setTurn(angle);
      return angle;
    }
  }
}
