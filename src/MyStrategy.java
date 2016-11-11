import model.ActionType;
import model.CircularUnit;
import model.Faction;
import model.Game;
import model.LineType;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.Unit;
import model.Wizard;
import model.World;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

public final class MyStrategy implements Strategy {

    private static final double LOW_HP_FACTOR = 0.25D;
    private static final double SAFE_HP_FACTOR = 0.40D;
    private static final int STRAFE_PERIOD = 80;
    private static final double WAYPOINT_RADIUS = 200.0D;

    private final Map<LineType, Point2D[]> waypointsByLine = new EnumMap<>(LineType.class);
    private List<Point2D> points = new ArrayList<>();
    private Faction enemyFaction;

    private Random random;
    private Visualizer debug;

    private LineType line;
    private Point2D[] waypoints;

    private Wizard self;
    private World world;
    private Game game;
    private Move move;

    @Override
    public void move(Wizard self, World world, Game game, Move move) {
        initializeTick(self, world, game, move);
        initializeStrategy();

        Point2D bestPoint = new Point2D(self);
        double bestScore = scorePoint(bestPoint);
        double worstScore = bestScore;
        for (Point2D point : points) {
            if (point.getDistanceTo(self) > self.getVisionRange()) {
                continue;
            }
            double score = scorePoint(point);
            worstScore = StrictMath.min(worstScore, score);
            if (score > bestScore) {
                bestPoint = point;
                bestScore = score;
            }
        }
        for (Point2D point : points) {
            if (point.getDistanceTo(self) > self.getVisionRange()) {
                continue;
            }
            double score = scorePoint(point);
            double normedScore = (score - worstScore) / (bestScore - worstScore);
            Color color = new Color((float) (1 - normedScore), (float) 1, (float) (1 - normedScore));
            debug.fillCircle(point.getX(), point.getY(), 3, color);
        }

        if (debug != null) {
            for (Wizard wizard : world.getWizards()) {
                if (wizard.isMaster()) {
                    double radius = 8;
                    debug.fillRect(wizard.getX() - radius, wizard.getY() - radius, wizard.getX() + radius, wizard.getY() + radius, Color.red);
                }
            }
            debug.drawBeforeScene();
        }

        if (debug != null && world.getTickIndex() % STRAFE_PERIOD * 2 < STRAFE_PERIOD) {
            debug.showText(self.getX(), self.getY(), "Move!", Color.blue);
        }
        if (debug != null) {
            debug.fillCircle(bestPoint.getX(), bestPoint.getY(), 6, Color.red);
            debug.drawAfterScene();
        }

        if (self.getLife() < self.getMaxLife() * LOW_HP_FACTOR) {
            goTo(getPreviousWaypoint());
            return;
        }

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

                return;
            }
        }

        if (self.getLife() > self.getMaxLife() * SAFE_HP_FACTOR) {
            goTo(bestPoint);
        }
    }

    private boolean isPermanentlyUnreacheble(Point2D point) {
        for (CircularUnit unit : world.getTrees()) {
            if (point.isCoveredBy(unit)) {
                return true;
            }
        }
        for (CircularUnit unit : world.getBuildings()) {
            if (point.isCoveredBy(unit)) {
                return true;
            }
        }
        return false;
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
                    if (StrictMath.abs(wizard.getAngleTo(point.getX(), point.getY())) < StrictMath.PI / 4) { // TODO: Discount for rotation time.
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
                if (minion.getType() == MinionType.ORC_WOODCUTTER && distance < game.getOrcWoodcutterAttackRange()) {
                    if (StrictMath.abs(angle) < game.getOrcWoodcutterAttackSector() / 2 * 2) { // TODO: Discount for rotation time.
                        double ALLY_WOODCUTTER_ATTACK_RANGE_FACTOR = 5;
                        score += ALLY_WOODCUTTER_ATTACK_RANGE_FACTOR;
                    }
                }
                if (minion.getType() == MinionType.FETISH_BLOWDART && distance < game.getFetishBlowdartAttackRange()) {
                    if (StrictMath.abs(angle) < game.getFetishBlowdartAttackSector() / 2 * 2) { // TODO: Discount for rotation time.
                        double ALLY_FETISH_ATTACK_RANGE_FACTOR = 5;
                        score += ALLY_FETISH_ATTACK_RANGE_FACTOR;
                    }
                }
            }
        }
        return score;
    }

    private List<Point2D> getPointsAroundSelf() {
        int depth = 3;
        double hexagonSize = self.getRadius();
        List<Point2D> points = new ArrayList<>();
        for (int q = -depth; q <= depth; ++q) {
            for (int r = -depth; r <= depth; ++r) {
                int s = -q - r;
                if (-depth <= s && s <= depth) {
                    double x = hexagonSize * 3 / 2 * q;
                    double y = hexagonSize * StrictMath.sqrt(3) * (r + (double) q / 2);
                    Point2D point = new Point2D(self.getX() + x, self.getY() + y);
                    if (isPermanentlyUnreacheble(point)) {
                        continue;
                    }
                    points.add(point);
                }
            }
        }
        return points;
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

        double mapSize = game.getMapSize();

        waypointsByLine.put(LineType.MIDDLE, new Point2D[]{
                new Point2D(100.0D, mapSize - 100.0D),
                random.nextBoolean()
                        ? new Point2D(600.0D, mapSize - 200.0D)
                        : new Point2D(200.0D, mapSize - 600.0D),
                new Point2D(800.0D, mapSize - 800.0D),
                new Point2D(mapSize - 600.0D, 600.0D)
        });

        waypointsByLine.put(LineType.TOP, new Point2D[]{
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

        waypointsByLine.put(LineType.BOTTOM, new Point2D[]{
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

        line = LineType.MIDDLE;
        waypoints = waypointsByLine.get(line);

        enemyFaction = self.getFaction() == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

        points = getPoints();
    }

    private List<Point2D> getPoints() {
        List<Point2D> points = new ArrayList<>();
        double hexagonSize = game.getWizardRadius();
        for (int q = 0; q < 100; ++q) {
            for (int r = 0; r < 100; ++r) {
                double x = hexagonSize * 3 / 2 * q;
                double y = hexagonSize * StrictMath.sqrt(3) * (r + (double) q / 2);
                if (0 < x && x < world.getWidth() && 0 < y && y < world.getHeight()) { // TODO: Figure out width and height.
                    points.add(new Point2D(x, y));
                }
            }
        }
        return points;
    }

    private void initializeTick(Wizard self, World world, Game game, Move move) {
        this.self = self;
        this.world = world;
        this.game = game;
        this.move = move;
    }

    /**
     * Данный метод предполагает, что все ключевые точки на линии упорядочены по уменьшению дистанции до последней
     * ключевой точки. Перебирая их по порядку, находим первую попавшуюся точку, которая находится ближе к последней
     * точке на линии, чем волшебник. Это и будет следующей ключевой точкой.
     * <p>
     * Дополнительно проверяем, не находится ли волшебник достаточно близко к какой-либо из ключевых точек. Если это
     * так, то мы сразу возвращаем следующую ключевую точку.
     */
    private Point2D getNextWaypoint() {
        int lastWaypointIndex = waypoints.length - 1;
        Point2D lastWaypoint = waypoints[lastWaypointIndex];

        for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex + 1];
            }

            if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return lastWaypoint;
    }

    private Point2D getPreviousWaypoint() {
        Point2D firstWaypoint = waypoints[0];

        for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex) {
            Point2D waypoint = waypoints[waypointIndex];

            if (waypoint.getDistanceTo(self) <= WAYPOINT_RADIUS) {
                return waypoints[waypointIndex - 1];
            }

            if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(self)) {
                return waypoint;
            }
        }

        return firstWaypoint;
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
        return unit.getFaction() == enemyFaction;
    }

    private boolean isAlly(LivingUnit unit) {
        return unit.getFaction() == self.getFaction();
    }

    private static final class Point2D {
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

        public boolean isCoveredBy(CircularUnit unit) {
            return getDistanceTo(unit) <= unit.getRadius();
        }
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
}
