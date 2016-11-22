import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import model.ActionType;
import model.Building;
import model.Faction;
import model.Game;
import model.LaneType;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.Tree;
import model.Unit;
import model.Wizard;
import model.World;

public final class MyStrategy implements Strategy {

  private static final double LOOKAHEAD_TICKS = 10;

  private Brain brain;

  private static double binarySearch(double a, double b, Predicate<Double> p) {
    final double tolerance = 1e-6;
    for (int i = 0; b - a > tolerance && i < 50; ++i) {
      double m = (a + b) / 2;
      if (p.test(m)) {
        a = m;
      } else {
        b = m;
      }
    }
    return a;
  }

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

    void drawAbsolute();

    void drawCircle(double x, double y, double r, Color color);

    void fillCircle(double x, double y, double r, Color color);

    void drawArc(
        double x, double y, double radius, double startAngle, double arcAngle, Color color);

    void fillArc(
        double x, double y, double radius, double startAngle, double arcAngle, Color color);

    void drawRect(double x1, double y1, double x2, double y2, Color color);

    void fillRect(double x1, double y1, double x2, double y2, Color color);

    void drawLine(double x1, double y1, double x2, double y2, Color color);

    void showText(double x, double y, String msg, Color color);
  }

  private static class Brain {

    private static final int IDLE_TICKS = 125;
    private final Faction ALLY_FRACTION;
    private final Faction ENEMY_FRACTION;

    private final Visualizer debug;
    private final Random random;
    private final Field field;
    private final Walker walker;
    private final Shooter shooter;

    public Brain(Wizard self, World world, Game game) {
      ALLY_FRACTION = self.getFaction();
      ENEMY_FRACTION = ALLY_FRACTION == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

      Visualizer debugVisualizer = null;
      try {
        Class<?> clazz = Class.forName("DebugVisualizer");
        Object instance = clazz.getConstructor().newInstance();
        debugVisualizer = (Visualizer) instance;
      } catch (ClassNotFoundException e) {
        // Visualizer is not available.
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      debug = debugVisualizer;

      random = new Random(game.getRandomSeed());
      field = new Field(this, debug, self, game);
      walker = new Walker(this, debug);
      shooter = new Shooter(this, debug);

      if (debug != null) {
        System.out.println("StaffDamage = " + game.getStaffDamage());
        System.out.println("MagicMissileDirectDamage = " + game.getMagicMissileDirectDamage());
        System.out.println("StaffCooldownTicks = " + game.getStaffCooldownTicks());
        System.out.println("MagicMissileCooldownTicks = " + game.getMagicMissileCooldownTicks());
        System.out.println("BuildingDamageScoreFactor = " + game.getBuildingDamageScoreFactor());
        System.out.println(
            "BuildingEliminationScoreFactor = " + game.getBuildingEliminationScoreFactor());
        System.out.println("MinionDamageScoreFactor = " + game.getMinionDamageScoreFactor());
        System.out.println(
            "MinionEliminationScoreFactor = " + game.getMinionEliminationScoreFactor());
        System.out.println("TeamWorkingScoreFactor = " + game.getTeamWorkingScoreFactor());
        System.out.println("WizardDamageScoreFactor = " + game.getWizardDamageScoreFactor());
        System.out.println(
            "WizardEliminationScoreFactor = " + game.getWizardEliminationScoreFactor());
        System.out.println("MaxLife = " + self.getMaxLife());
      }
    }

    public void move(Wizard self, World world, Game game, Move move) {
      field.update(self, world, game);
      walker.update(self, world, game);
      shooter.update(self, world, game);

      if (world.getTickIndex() < IDLE_TICKS) {
        move.setTurn(2 * Math.PI / IDLE_TICKS);
        return;
      }

      boolean lowHP = self.getLife() < 40;
      Point2D walkingTarget = field.getNextWaypoint();
      LivingUnit shootingTarget = shooter.getTarget();

      if (shootingTarget != null) {
        double distance = self.getDistanceTo(shootingTarget);
        if (distance <= self.getCastRange()) {
          double angle = self.getAngleTo(shootingTarget);
          if (StrictMath.abs(angle) < game.getStaffSector() / 2.0D) {
            move.setAction(ActionType.MAGIC_MISSILE);
            move.setCastAngle(angle);
            move.setMinCastDistance(
                distance - shootingTarget.getRadius() + game.getMagicMissileRadius());
          }
        }
      }

      if (lowHP == false && shootingTarget == null) {
        walker.turnTo(walkingTarget, move);
        walker.goTo(walkingTarget, move);
      } else if (lowHP == false && shootingTarget != null) {
        walker.turnTo(shootingTarget, move);
        if (self.getDistanceTo(shootingTarget) > self.getCastRange()) {
          walker.goTo(shootingTarget, move);
        }
      } else if (lowHP == true && shootingTarget == null) {
        walker.turnTo(walkingTarget, move);
        walker.goTo(field.getPreviousWaypoint(), move);
      } else if (lowHP == true && shootingTarget != null) {
        walker.turnTo(shootingTarget, move);
        walker.goTo(field.getPreviousWaypoint(), move);
      }

      if (debug != null) {
        int N = 5;
        double R = self.getCastRange() * 2 / 3;
        for (double dx = -R; dx < R; dx += R / N) {
          for (double dy = -R; dy < R; dy += R / N) {
            Point2D point = new Point2D(self.getX() + dx, self.getY() + dy);
            debug.drawCircle(point.getX(), point.getY(), 3, Color.lightGray);
            for (Building building : world.getBuildings()) {
              if (isEnemy(building)) {}
            }
          }
        }

        if (walkingTarget != null) {
          debug.fillCircle(walkingTarget.getX(), walkingTarget.getY(), 5, Color.blue);
        }

        if (shootingTarget != null) {
          debug.fillCircle(shootingTarget.getX(), shootingTarget.getY(), 5, Color.red);
        }

        for (Building building : world.getBuildings()) {
          debug.drawCircle(
              building.getX(), building.getY(), building.getVisionRange(), Color.lightGray);
          debug.drawCircle(building.getX(), building.getY(), building.getAttackRange(), Color.pink);
          debug.showText(
              building.getX(),
              building.getY(),
              " " + building.getRemainingActionCooldownTicks(),
              Color.black);
        }

        debug.showText(
            self.getX(), self.getY(), " " + self.getRemainingActionCooldownTicks(), Color.black);
        debug.showText(
            self.getX(),
            self.getY() + 20,
            " " + self.getRemainingCooldownTicksByAction()[ActionType.MAGIC_MISSILE.ordinal()],
            Color.black);

        //for (LivingUnit unit : field.getAllObstacles()) {
        //  debug.drawCircle(
        //      unit.getX(),
        //      unit.getY(),
        //      unit.getRadius() + self.getRadius(),
        //      Color.yellow.brighter().brighter().brighter());
        //}

        debug.drawBeforeScene();
      }
    }

    private Tree getClosestTree(Wizard self, World world) {
      Tree closestTree = null;
      double closestTreeDistance = 0;
      for (Tree tree : world.getTrees()) {
        double distance = self.getDistanceTo(tree);
        if (closestTree == null || distance < closestTreeDistance) {
          closestTree = tree;
          closestTreeDistance = distance;
        }
      }
      return closestTree;
    }

    boolean isEnemy(Unit unit) {
      return unit.getFaction() == ENEMY_FRACTION;
    }

    boolean isAlly(Unit unit) {
      return unit.getFaction() == ALLY_FRACTION;
    }

    Point2D predictPosition(Unit unit, double ticksFromNow) {
      // TODO: Figure out where the minus sign comes from.
      return new Point2D(
          unit.getX() + unit.getSpeedX() * ticksFromNow,
          unit.getY() + unit.getSpeedY() * ticksFromNow);
    }

    void drawWaves(
        double x, double y, double radius, double startAngle, double arcAngle, Color color) {
      final int N = 10;
      for (int i = 1; i <= N; ++i) {
        debug.drawArc(x, y, radius * i / N, startAngle, arcAngle, color);
      }
    }

    void drawPath(List<FieldPoint> path, Color color) {
      for (int i = 1; i < path.size(); ++i) {
        debug.drawLine(
            path.get(i - 1).getX(),
            path.get(i - 1).getY(),
            path.get(i).getX(),
            path.get(i).getY(),
            color);
      }
    }

    void drawHexTile(FieldPoint point, Color color) {
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
  }

  private abstract static class BrainPart {

    protected final Brain brain;
    protected final Visualizer debug;
    protected Wizard self;
    protected World world;
    protected Game game;

    public BrainPart(Brain brain, Visualizer debug) {
      this.brain = brain;
      this.debug = debug;
    }

    public void update(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;
      update();
    }

    protected void update() {}
  }

  private static class Field extends BrainPart {

    private static final List<HexPoint> HEX_DIRECTIONS =
        Collections.unmodifiableList(
            Arrays.asList(
                new HexPoint(1, 0),
                new HexPoint(1, -1),
                new HexPoint(0, -1),
                new HexPoint(-1, 0),
                new HexPoint(-1, 1),
                new HexPoint(0, 1)));
    private static final double REACHABILITY_EPS = 3;

    private final double HEXAGON_SIZE;
    private final Map<LaneType, Point2D[]> waypointsByLane = new EnumMap<>(LaneType.class);

    private Map<HexPoint, FieldPoint> hexToPoint;

    public Field(Brain brain, Visualizer debug, Wizard self, Game game) {
      super(brain, debug);
      HEXAGON_SIZE = self.getRadius();

      double mapSize = game.getMapSize();

      waypointsByLane.put(
          LaneType.MIDDLE,
          new Point2D[] {
            new Point2D(100.0D, mapSize - 100.0D),
            new Point2D(200.0D, mapSize - 600.0D),
            new Point2D(800.0D, mapSize - 800.0D),
            new Point2D(mapSize - 550.0D, 400.0D)
          });

      waypointsByLane.put(
          LaneType.TOP,
          new Point2D[] {
            new Point2D(100.0D, mapSize - 100.0D),
            new Point2D(100.0D, mapSize - 400.0D),
            new Point2D(200.0D, mapSize - 800.0D),
            new Point2D(200.0D, mapSize * 0.75D),
            new Point2D(200.0D, mapSize * 0.5D),
            new Point2D(200.0D, mapSize * 0.25D),
            new Point2D(200.0D, 200.0D),
            new Point2D(mapSize * 0.25D, 200.0D),
            new Point2D(mapSize * 0.5D, 200.0D),
            new Point2D(mapSize * 0.75D, 200.0D),
            new Point2D(mapSize - 200.0D, 200.0D)
          });

      waypointsByLane.put(
          LaneType.BOTTOM,
          new Point2D[] {
            new Point2D(100.0D, mapSize - 100.0D),
            new Point2D(400.0D, mapSize - 100.0D),
            new Point2D(800.0D, mapSize - 200.0D),
            new Point2D(mapSize * 0.25D, mapSize - 200.0D),
            new Point2D(mapSize * 0.5D, mapSize - 200.0D),
            new Point2D(mapSize * 0.75D, mapSize - 200.0D),
            new Point2D(mapSize - 200.0D, mapSize - 200.0D),
            new Point2D(mapSize - 200.0D, mapSize * 0.75D),
            new Point2D(mapSize - 200.0D, mapSize * 0.5D),
            new Point2D(mapSize - 200.0D, mapSize * 0.25D),
            new Point2D(mapSize - 200.0D, 200.0D)
          });
    }

    @Override
    public void update() {
      if (debug != null) {
        for (Point2D[] waypoints : waypointsByLane.values()) {
          for (int i = 0; i < waypoints.length; ++i) {
            debug.fillCircle(waypoints[i].getX(), waypoints[i].getY(), 5, Color.lightGray);
            if (i != 0) {
              debug.drawLine(
                  waypoints[i - 1].getX(),
                  waypoints[i - 1].getY(),
                  waypoints[i].getX(),
                  waypoints[i].getY(),
                  Color.lightGray);
            }
          }
        }
      }
    }

    private Point2D getNextWaypoint() {
      Point2D[] waypoints = waypointsByLane.get(LaneType.MIDDLE);
      int lastWaypointIndex = waypoints.length - 1;
      Point2D lastWaypoint = waypoints[lastWaypointIndex];

      for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
        Point2D waypoint = waypoints[waypointIndex];

        if (waypoint.getDistanceTo(self) <= self.getRadius() * 2) {
          return waypoints[waypointIndex + 1];
        }

        if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(self)) {
          return waypoint;
        }
      }

      return lastWaypoint;
    }

    private Point2D getPreviousWaypoint() {
      Point2D[] waypoints = waypointsByLane.get(LaneType.MIDDLE);
      Point2D firstWaypoint = waypoints[0];

      for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex) {
        Point2D waypoint = waypoints[waypointIndex];

        if (waypoint.getDistanceTo(self) <= self.getRadius() * 2) {
          return waypoints[waypointIndex - 1];
        }

        if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(self)) {
          return waypoint;
        }
      }

      return firstWaypoint;
    }

    public void oldUpdate() {
      if (hexToPoint == null) {
        hexToPoint = createHexToPoint();
      }
      hexToPoint
          .values()
          .forEach(
              point -> {
                if (point.getDistanceTo(self) < self.getVisionRange()) {
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

            if (debug != null) {
              debug.drawLine(
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
      double enemy_factor = 0;
      boolean isReachable = true;

      double max_sum = world.getHeight() + world.getWidth();
      score += (StrictMath.round(world.getHeight() - point.getY()) + point.getX()) / max_sum * 50;

      for (Wizard wizard : world.getWizards()) {
        if (wizard.isMe()) {
          continue;
        }

        double distance = point.getDistanceTo(brain.predictPosition(wizard, LOOKAHEAD_TICKS));

        if (distance < self.getRadius() + wizard.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }

        if (brain.isAlly(wizard)) {
          if (wizard.isMaster() && distance < wizard.getVisionRange() / 3) {
            double MASTER_VISION_RANGE_FACTOR = 20;
            score += MASTER_VISION_RANGE_FACTOR;
          }

          if (distance < wizard.getCastRange()) {
            if (StrictMath.abs(wizard.getAngleTo(point.getX(), point.getY())) < StrictMath.PI / 4) {
              // TODO: Discount for rotation time.
              double ALLY_WIZARD_CAST_RANGE_FACTOR = 15;
              score += ALLY_WIZARD_CAST_RANGE_FACTOR;
            }
          }

          if (distance < wizard.getRadius() + 1.5 * self.getRadius()) {
            double TIGHT_CLOSE_TO_ALLY_WIZARD_FACTOR = -10;
            score += TIGHT_CLOSE_TO_ALLY_WIZARD_FACTOR;
          }
        } else {
          if (distance < wizard.getCastRange()) {
            double ENEMY_WIZARD_CAST_RANGE_FACTOR = -5;
            enemy_factor += ENEMY_WIZARD_CAST_RANGE_FACTOR;
          }
        }
      }

      for (Minion minion : world.getMinions()) {
        double distance = point.getDistanceTo(brain.predictPosition(minion, LOOKAHEAD_TICKS));

        if (distance < self.getRadius() + minion.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }

        double angle = minion.getAngleTo(point.getX(), point.getY());

        if (brain.isAlly(minion)) {
          if (minion.getType() == MinionType.ORC_WOODCUTTER && distance < minion.getVisionRange()) {
            // TODO: Discount for rotation time.
            double ALLY_WOODCUTTER_VISION_RANGE_FACTOR = 3;
            score += ALLY_WOODCUTTER_VISION_RANGE_FACTOR;
          }

          if (minion.getType() == MinionType.FETISH_BLOWDART
              && distance < minion.getVisionRange()) {
            //if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2 * 2) {
            // TODO: Discount for rotation time.
            double ALLY_FETISH_VISION_RANGE_FACTOR = 5;
            score += ALLY_FETISH_VISION_RANGE_FACTOR;
          }
        } else {
          if (minion.getType() == MinionType.ORC_WOODCUTTER && distance < minion.getVisionRange()) {
            // TODO: Discount for rotation time.
            double ENEMY_WOODCUTTER_VISION_RANGE_FACTOR = -1;
            enemy_factor += ENEMY_WOODCUTTER_VISION_RANGE_FACTOR;
          }

          if (minion.getType() == MinionType.FETISH_BLOWDART
              && distance < minion.getVisionRange()) {
            //if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2 * 2) {
            // TODO: Discount for rotation time.
            double ENEMY_FETISH_VISION_RANGE_FACTOR = -2;
            enemy_factor += ENEMY_FETISH_VISION_RANGE_FACTOR;
          }
        }
      }

      for (Building building : world.getBuildings()) {
        double distance = point.getDistanceTo(building);
        if (distance < self.getRadius() + building.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }
        if (brain.isAlly(building)) {
          if (distance < building.getVisionRange()) {
            double ALLY_BUILDING_VISION_RANGE_FACTOR = 0;
            score += ALLY_BUILDING_VISION_RANGE_FACTOR;
          }
        } else {
          if (distance < building.getVisionRange()) {
            double ENEMY_BUILDING_VISION_RANGE_FACTOR = -5;
            enemy_factor += ENEMY_BUILDING_VISION_RANGE_FACTOR;
          }
        }
      }

      for (Tree tree : world.getTrees()) {
        if (point.getDistanceTo(tree) < self.getRadius() + tree.getRadius() + REACHABILITY_EPS) {
          isReachable = false;
        }
        if (point.getDistanceTo(tree) < HEXAGON_SIZE * 3) {
          double CLOSE_TO_TREE_FACTOR = -3;
          score += CLOSE_TO_TREE_FACTOR;
        }
      }

      enemy_factor *= 1 + (1 - self.getLife() / self.getMaxLife());
      score += enemy_factor;

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
      double radius = self.getRadius();
      for (int q = -100; q <= 100; ++q) {
        for (int r = -100; r <= 100; ++r) {
          Point2D point = hexToPixel(q, r);
          if (radius < point.getX()
              && point.getX() < world.getWidth() - radius
              && radius < point.getY()
              && point.getY() < world.getHeight() - radius) {
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

    List<LivingUnit> getAllObstacles() {
      List<LivingUnit> units = new ArrayList<>();
      units.addAll(
          Arrays.asList(world.getWizards())
              .stream()
              .filter(w -> !w.isMe())
              .collect(Collectors.toList()));
      units.addAll(Arrays.asList(world.getMinions()));
      units.addAll(Arrays.asList(world.getBuildings()));
      units.addAll(Arrays.asList(world.getTrees()));
      return units;
    }
  }

  private static class Walker extends BrainPart {

    private static final double RETARGET_THRESHOLD = 1;

    private FieldPoint target;

    public Walker(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    public void goTo(Point2D target, Move move) {
      double angle = self.getAngleTo(target.getX(), target.getY());

      // TODO: Add bonus effects.
      int aSign = Math.abs(angle) < Math.PI / 2 ? 1 : -1;
      Point2D a =
          aSign == 1
              ? Point2D.fromPolar(game.getWizardForwardSpeed(), self.getAngle())
              : Point2D.fromPolar(game.getWizardBackwardSpeed(), self.getAngle()).negate();

      int bSign = angle > 0 ? 1 : -1;
      Point2D b =
          bSign == 1
              ? Point2D.fromPolar(game.getWizardStrafeSpeed(), self.getAngle() + Math.PI / 2)
              : Point2D.fromPolar(game.getWizardStrafeSpeed(), self.getAngle() - Math.PI / 2);

      Point2D ba = a.sub(b);
      boolean aIsClockwiseToB = a.isClockwiseTo(b);
      Point2D direction = target.sub(new Point2D(self));
      double k =
          binarySearch(
              0,
              1,
              m -> {
                Point2D v = direction.mul(m);
                Point2D bv = v.sub(b);
                return bv.isClockwiseTo(ba) == aIsClockwiseToB;
              });

      // Speed.
      Point2D v = direction.mul(k);

      // Try avoiding collisions.
      final int T = 8; // Look-ahead ticks.
      final int N = 4 * 3; // Angles to consider.

      if (debug != null) {
        for (int i = 1; i <= N * 2; ++i) {
          int j = i / 2;
          int sign = i % 1 == 0 ? 1 : -1;
          Point2D newV = v.rotate(sign * Math.PI * 2 * j / N);
          debug.drawCircle(
              self.getX() + T * newV.getX(), self.getY() + T * newV.getY(), 3, Color.orange);
        }
        debug.fillCircle(self.getX() + T * v.getX(), self.getY() + T * v.getY(), 4, Color.cyan);
      }

      List<LivingUnit> obstacles = brain.field.getAllObstacles();
      for (int i = 1; i <= N * 2; ++i) {
        int j = i / 2;
        int sign = i % 1 == 0 ? 1 : -1;
        Point2D newV = v.rotate(sign * Math.PI * 2 * j / N);
        Point2D newSelf = newV.mul(T).add(new Point2D(self));
        boolean noCollisions =
            obstacles
                .stream()
                .allMatch(u -> newSelf.getDistanceTo(u) > u.getRadius() + self.getRadius());
        if (noCollisions) {
          v = newV;
          break;
        }
      }

      move.setSpeed(aSign * v.project(a));
      move.setStrafeSpeed(bSign * v.project(b));

      if (debug != null) {
        debug.fillCircle(self.getX() + T * v.getX(), self.getY() + T * v.getY(), 5, Color.orange);

        boolean DISPLAY_BOX = false;
        if (DISPLAY_BOX) {
          List<Point2D> box = new ArrayList<>();
          box.add(
              new Point2D(
                  self.getX() + T * game.getWizardForwardSpeed() * Math.cos(self.getAngle()),
                  self.getY() + T * game.getWizardForwardSpeed() * Math.sin(self.getAngle())));
          box.add(
              new Point2D(
                  self.getX()
                      + T * game.getWizardStrafeSpeed() * Math.cos(self.getAngle() + Math.PI / 2),
                  self.getY()
                      + T * game.getWizardStrafeSpeed() * Math.sin(self.getAngle() + Math.PI / 2)));
          box.add(
              new Point2D(
                  self.getX()
                      + T * game.getWizardBackwardSpeed() * Math.cos(self.getAngle() + Math.PI),
                  self.getY()
                      + T * game.getWizardBackwardSpeed() * Math.sin(self.getAngle() + Math.PI)));
          box.add(
              new Point2D(
                  self.getX()
                      + T
                          * game.getWizardStrafeSpeed()
                          * Math.cos(self.getAngle() + Math.PI * 3 / 2),
                  self.getY()
                      + T
                          * game.getWizardStrafeSpeed()
                          * Math.sin(self.getAngle() + Math.PI * 3 / 2)));
          for (int i = 0; i < box.size(); ++i) {
            int j = (i + 1) % box.size();
            // debug.fillCircle(box.get(i).getX(), box.get(i).getY(), 5, Color.blue);
            debug.drawLine(
                box.get(i).getX(),
                box.get(i).getY(),
                box.get(j).getX(),
                box.get(j).getY(),
                Color.blue);
          }
        }

        //debug.drawLine(
        //    self.getX(),
        //    self.getY(),
        //    self.getX() + T * a.getX(),
        //    self.getY() + T * a.getY(),
        //    Color.magenta);
        //debug.drawLine(
        //    self.getX(),
        //    self.getY(),
        //    self.getX() + T * b.getX(),
        //    self.getY() + T * b.getY(),
        //    Color.cyan);
        //debug.drawLine(
        //    self.getX(),
        //    self.getY(),
        //    self.getX() + T * v.getX(),
        //    self.getY() + T * v.getY(),
        //    Color.red);
        //debug.fillCircle(
        //    self.getX()
        //        + T * move.getSpeed() * Math.cos(self.getAngle())
        //        + T * move.getStrafeSpeed() * Math.cos(self.getAngle() + Math.PI / 2),
        //    self.getY()
        //        + T * move.getSpeed() * Math.sin(self.getAngle())
        //        + T * move.getStrafeSpeed() * Math.sin(self.getAngle() + Math.PI / 2),
        //    5,
        //    Color.orange);
      }
    }

    public void turnTo(Point2D point, Move move) {
      double angle = self.getAngleTo(point.getX(), point.getY());
      move.setTurn(angle);
    }

    public void goTo(Unit unit, Move move) {
      goTo(new Point2D(unit), move);
    }

    public void turnTo(Unit unit, Move move) {
      turnTo(new Point2D(unit), move);
    }
  }

  private static class Shooter extends BrainPart {

    public Shooter(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    @Override
    protected void update() {
      if (debug != null) {
        debug.drawCircle(self.getX(), self.getY(), self.getVisionRange(), Color.lightGray);
        debug.drawCircle(self.getX(), self.getY(), self.getCastRange(), Color.lightGray);
        brain.drawWaves(
            self.getX(),
            self.getY(),
            self.getCastRange(),
            self.getAngle() - game.getStaffSector() / 2,
            game.getStaffSector(),
            Color.pink);
        brain.drawWaves(
            self.getX(),
            self.getY(),
            game.getStaffRange(),
            self.getAngle() - game.getStaffSector() / 2,
            game.getStaffSector(),
            Color.red);
      }
    }

    public LivingUnit getTarget() {
      LivingUnit buildingTarget = getTargetHomo(world.getBuildings());
      LivingUnit wizardTarget = getTargetHomo(world.getWizards());
      if (buildingTarget == null && wizardTarget == null) {
        return getTargetHomo(world.getMinions());
      }
      if (buildingTarget == null) {
        return wizardTarget;
      }
      return buildingTarget;
    }

    private LivingUnit getTargetHomo(LivingUnit[] units) {
      LivingUnit bestTarget = null;
      for (LivingUnit target : units) {
        if (!brain.isEnemy(target)) {
          continue;
        }
        if (self.getDistanceTo(target) > self.getVisionRange()) {
          continue;
        }
        if (bestTarget == null || target.getLife() < bestTarget.getLife()) {
          bestTarget = target;
        }
      }
      return bestTarget;
    }
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

    public static Point2D fromPolar(double radius, double angle) {
      return new Point2D(radius * Math.cos(angle), radius * Math.sin(angle));
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

    public Point2D negate() {
      return new Point2D(-x, -y);
    }

    public Point2D unit() {
      double len = length();
      return new Point2D(x / len, y / len);
    }

    public double length() {
      return Math.sqrt(x * x + y * y);
    }

    public Point2D mul(double k) {
      return new Point2D(k * x, k * y);
    }

    public Point2D add(Point2D other) {
      return new Point2D(this.x + other.x, this.y + other.y);
    }

    public Point2D sub(Point2D other) {
      return new Point2D(this.x - other.x, this.y - other.y);
    }

    public double dot(Point2D other) {
      return this.x * other.x + this.y * other.y;
    }

    public double cross(Point2D other) {
      return this.x * other.y - this.y * other.x;
    }

    public boolean isClockwiseTo(Point2D other) {
      return cross(other) < 0;
    }

    public double project(Point2D other) {
      return this.dot(other.unit());
    }

    public Point2D rotate(double angle) {
      double sin = Math.sin(angle);
      double cos = Math.cos(angle);
      return new Point2D(x * cos - y * sin, y * cos + x * sin);
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
        throw new AssertionError("neighbors are already set.");
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

      return q == hexPoint.q && r == hexPoint.r;
    }

    @Override
    public int hashCode() {
      int result = q;
      result = 31 * result + r;
      return result;
    }
  }
}
