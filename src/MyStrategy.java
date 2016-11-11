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
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class MyStrategy implements Strategy {

    private static final int STRAFE_PERIOD = 80;

    private static final List<HexPoint> HEX_DIRECTIONS = Collections.unmodifiableList(Arrays.asList(
            new HexPoint(1, 0),
            new HexPoint(1, -1),
            new HexPoint(0, -1),
            new HexPoint(-1, 0),
            new HexPoint(-1, 1),
            new HexPoint(0, 1)));

    private List<MapPoint> map;
    private Faction enemyFaction;

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

        MapPoint bestPoint = null;
        double bestScore = 0;
        double worstScore = 0;
        for (MapPoint point : map) {
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

        if (debug != null) {
            for (MapPoint point : map) {
                if (point.getDistanceTo(self) > self.getVisionRange()) {
                    continue;
                }
                double score = scorePoint(point);
                double normedScore = Math.max(0.3, (score - worstScore) / (bestScore - worstScore));
                Color color = new Color((float) (1 - normedScore), (float) 1, (float) (1 - normedScore));
                debug.fillCircle(point.getX(), point.getY(), 3, color);
            }

            debug.drawCircle(self.getX(), self.getY(), self.getVisionRange(), Color.lightGray);

            for (Wizard wizard : world.getWizards()) {
                if (wizard.isMaster()) {
                    double r = wizard.getRadius() / 2;
                    debug.fillRect(
                            wizard.getX() - r, wizard.getY() - r,
                            wizard.getX() + r, wizard.getY() + r,
                            Color.red);
                }
            }

            debug.drawBeforeScene();
        }

        if (debug != null) {
            List<MapPoint> points = bestPoint.getNeighbors();
            for (int i = 0; i < points.size(); ++i) {
                int j = (i + 1) % points.size();
                debug.drawLine(points.get(i).getX(), points.get(i).getY(), points.get(j).getX(), points.get(j).getY(), Color.red);
            }
            debug.drawAfterScene();
        }

        goTo(bestPoint);

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

        enemyFaction = self.getFaction() == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

        map = createMap();
    }

    private Point2D hexToPixel(double q, double r) {
        final double HEXAGON_SIZE = game.getWizardRadius();
        double x = HEXAGON_SIZE * 3 / 2 * q;
        double y = HEXAGON_SIZE * StrictMath.sqrt(3) * (r + q / 2);
        return new Point2D(x, y);
    }

    private List<MapPoint> createMap() {
        Map<HexPoint, MapPoint> points = new HashMap<>();
        double radius = self.getRadius();
        for (int q = 0; q < 100; ++q) {
            for (int r = 0; r < 100; ++r) {
                Point2D point = hexToPixel(q, r);
                if (radius < point.getX() && point.getX() < world.getWidth() - radius &&
                        radius < point.getY() && point.getY() < world.getHeight() - radius) {
                    points.put(new HexPoint(q, r), new MapPoint(point));
                }
            }
        }
        for (Map.Entry<HexPoint, MapPoint> entry : points.entrySet()) {
            int q = entry.getKey().getQ();
            int r = entry.getKey().getR();
            MapPoint point = entry.getValue();
            List<MapPoint> neighbors = new ArrayList<>();
            for (HexPoint delta : HEX_DIRECTIONS) {
                MapPoint neighbor = points.get(new HexPoint(q + delta.q, r + delta.r));
                if (neighbor == null) {
                    continue;
                }
                neighbors.add(neighbor);
            }
            point.setNeighbors(neighbors);
        }
        return Collections.unmodifiableList(new ArrayList<>(points.values()));
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
        return unit.getFaction() == enemyFaction;
    }

    private boolean isAlly(LivingUnit unit) {
        return unit.getFaction() == self.getFaction();
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
        private boolean isReachable;
        private List<MapPoint> neighbors;

        public MapPoint(Point2D point) {
            super(point.getX(), point.getY());
        }

        public boolean isReachable() {
            return isReachable;
        }

        public void setReachable(boolean reachable) {
            isReachable = reachable;
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
