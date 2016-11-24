import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import model.ActionType;
import model.Building;
import model.Faction;
import model.Game;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.StatusType;
import model.Tree;
import model.Unit;
import model.Wizard;
import model.World;

public final class MyStrategy implements Strategy {

  private static final int SQUARE_CRUDENESS = 30;

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

    void sync();

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

    private static final int IDLE_TICKS = 0; //125;
    private final Faction ALLY_FRACTION;
    private final Faction ENEMY_FRACTION;

    private final Visualizer debug;
    private final List<WorldObserver> observers;
    private final BonusFinder bonusFinder;
    private final Field field;
    private final Walker walker;
    private final Shooter shooter;
    protected Wizard self;
    protected World world;
    protected Game game;
    Point target = new Point(4000 * 0.2, 4000 * 0.3);
    Random random = new Random();
    Point oldPos = new Point(0, 0);

    public Brain(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;

      ALLY_FRACTION = self.getFaction();
      ENEMY_FRACTION = ALLY_FRACTION == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

      Visualizer debugVisualizer = null;
      try {
        Class<?> clazz = Class.forName("DebugVisualizer");
        Object instance = clazz.getConstructor().newInstance();
        debugVisualizer = (Visualizer) instance;
      } catch (Exception e) {
        // Visualizer is not available.
      }
      debug = debugVisualizer;

      observers = new ArrayList<>();

      bonusFinder = new BonusFinder(this, debug);
      observers.add(bonusFinder);

      field = new Field(this, debug, self, game);
      observers.add(field);

      walker = new Walker(this, debug);
      observers.add(walker);

      shooter = new Shooter(this, debug);
      observers.add(shooter);

      if (debug != null) {
        printGameParameters();
      }
    }

    private void printGameParameters() {
      System.out.println("RandomSeed = " + game.getRandomSeed());
      System.out.println("TickCount = " + game.getTickCount());
      System.out.println("MapSize = " + game.getMapSize());
      System.out.println("isSkillsEnabled = " + game.isSkillsEnabled());
      System.out.println("isRawMessagesEnabled = " + game.isRawMessagesEnabled());
      System.out.println("FriendlyFireDamageFactor = " + game.getFriendlyFireDamageFactor());
      System.out.println("BuildingDamageScoreFactor = " + game.getBuildingDamageScoreFactor());
      System.out.println(
          "BuildingEliminationScoreFactor = " + game.getBuildingEliminationScoreFactor());
      System.out.println("MinionDamageScoreFactor = " + game.getMinionDamageScoreFactor());
      System.out.println(
          "MinionEliminationScoreFactor = " + game.getMinionEliminationScoreFactor());
      System.out.println("WizardDamageScoreFactor = " + game.getWizardDamageScoreFactor());
      System.out.println(
          "WizardEliminationScoreFactor = " + game.getWizardEliminationScoreFactor());
      System.out.println("TeamWorkingScoreFactor = " + game.getTeamWorkingScoreFactor());
      System.out.println("VictoryScore = " + game.getVictoryScore());
      System.out.println("ScoreGainRange = " + game.getScoreGainRange());
      System.out.println("RawMessageMaxLength = " + game.getRawMessageMaxLength());
      System.out.println("RawMessageTransmissionSpeed = " + game.getRawMessageTransmissionSpeed());
      System.out.println("WizardRadius = " + game.getWizardRadius());
      System.out.println("WizardCastRange = " + game.getWizardCastRange());
      System.out.println("WizardVisionRange = " + game.getWizardVisionRange());
      System.out.println("WizardForwardSpeed = " + game.getWizardForwardSpeed());
      System.out.println("WizardBackwardSpeed = " + game.getWizardBackwardSpeed());
      System.out.println("WizardStrafeSpeed = " + game.getWizardStrafeSpeed());
      System.out.println("WizardBaseLife = " + game.getWizardBaseLife());
      System.out.println("WizardLifeGrowthPerLevel = " + game.getWizardLifeGrowthPerLevel());
      System.out.println("WizardBaseMana = " + game.getWizardBaseMana());
      System.out.println("WizardManaGrowthPerLevel = " + game.getWizardManaGrowthPerLevel());
      System.out.println("WizardBaseLifeRegeneration = " + game.getWizardBaseLifeRegeneration());
      System.out.println(
          "WizardLifeRegenerationGrowthPerLevel = "
              + game.getWizardLifeRegenerationGrowthPerLevel());
      System.out.println("WizardBaseManaRegeneration = " + game.getWizardBaseManaRegeneration());
      System.out.println(
          "WizardManaRegenerationGrowthPerLevel = "
              + game.getWizardManaRegenerationGrowthPerLevel());
      System.out.println("WizardMaxTurnAngle = " + game.getWizardMaxTurnAngle());
      System.out.println(
          "WizardMaxResurrectionDelayTicks = " + game.getWizardMaxResurrectionDelayTicks());
      System.out.println(
          "WizardMinResurrectionDelayTicks = " + game.getWizardMinResurrectionDelayTicks());
      System.out.println("WizardActionCooldownTicks = " + game.getWizardActionCooldownTicks());
      System.out.println("StaffCooldownTicks = " + game.getStaffCooldownTicks());
      System.out.println("MagicMissileCooldownTicks = " + game.getMagicMissileCooldownTicks());
      System.out.println("FrostBoltCooldownTicks = " + game.getFrostBoltCooldownTicks());
      System.out.println("FireballCooldownTicks = " + game.getFireballCooldownTicks());
      System.out.println("HasteCooldownTicks = " + game.getHasteCooldownTicks());
      System.out.println("ShieldCooldownTicks = " + game.getShieldCooldownTicks());
      System.out.println("MagicMissileManacost = " + game.getMagicMissileManacost());
      System.out.println("FrostBoltManacost = " + game.getFrostBoltManacost());
      System.out.println("FireballManacost = " + game.getFireballManacost());
      System.out.println("HasteManacost = " + game.getHasteManacost());
      System.out.println("ShieldManacost = " + game.getShieldManacost());
      System.out.println("StaffDamage = " + game.getStaffDamage());
      System.out.println("StaffSector = " + game.getStaffSector());
      System.out.println("StaffRange = " + game.getStaffRange());
      System.out.println("LevelUpXpValues = " + game.getLevelUpXpValues());
      System.out.println("MinionRadius = " + game.getMinionRadius());
      System.out.println("MinionVisionRange = " + game.getMinionVisionRange());
      System.out.println("MinionSpeed = " + game.getMinionSpeed());
      System.out.println("MinionMaxTurnAngle = " + game.getMinionMaxTurnAngle());
      System.out.println("MinionLife = " + game.getMinionLife());
      System.out.println(
          "FactionMinionAppearanceIntervalTicks = "
              + game.getFactionMinionAppearanceIntervalTicks());
      System.out.println(
          "OrcWoodcutterActionCooldownTicks = " + game.getOrcWoodcutterActionCooldownTicks());
      System.out.println("OrcWoodcutterDamage = " + game.getOrcWoodcutterDamage());
      System.out.println("OrcWoodcutterAttackSector = " + game.getOrcWoodcutterAttackSector());
      System.out.println("OrcWoodcutterAttackRange = " + game.getOrcWoodcutterAttackRange());
      System.out.println(
          "FetishBlowdartActionCooldownTicks = " + game.getFetishBlowdartActionCooldownTicks());
      System.out.println("FetishBlowdartAttackRange = " + game.getFetishBlowdartAttackRange());
      System.out.println("FetishBlowdartAttackSector = " + game.getFetishBlowdartAttackSector());
      System.out.println("BonusRadius = " + game.getBonusRadius());
      System.out.println(
          "BonusAppearanceIntervalTicks = " + game.getBonusAppearanceIntervalTicks());
      System.out.println("BonusScoreAmount = " + game.getBonusScoreAmount());
      System.out.println("DartRadius = " + game.getDartRadius());
      System.out.println("DartSpeed = " + game.getDartSpeed());
      System.out.println("DartDirectDamage = " + game.getDartDirectDamage());
      System.out.println("MagicMissileRadius = " + game.getMagicMissileRadius());
      System.out.println("MagicMissileSpeed = " + game.getMagicMissileSpeed());
      System.out.println("MagicMissileDirectDamage = " + game.getMagicMissileDirectDamage());
      System.out.println("FrostBoltRadius = " + game.getFrostBoltRadius());
      System.out.println("FrostBoltSpeed = " + game.getFrostBoltSpeed());
      System.out.println("FrostBoltDirectDamage = " + game.getFrostBoltDirectDamage());
      System.out.println("FireballRadius = " + game.getFireballRadius());
      System.out.println("FireballSpeed = " + game.getFireballSpeed());
      System.out.println(
          "FireballExplosionMaxDamageRange = " + game.getFireballExplosionMaxDamageRange());
      System.out.println(
          "FireballExplosionMinDamageRange = " + game.getFireballExplosionMinDamageRange());
      System.out.println("FireballExplosionMaxDamage = " + game.getFireballExplosionMaxDamage());
      System.out.println("FireballExplosionMinDamage = " + game.getFireballExplosionMinDamage());
      System.out.println("GuardianTowerRadius = " + game.getGuardianTowerRadius());
      System.out.println("GuardianTowerVisionRange = " + game.getGuardianTowerVisionRange());
      System.out.println("GuardianTowerLife = " + game.getGuardianTowerLife());
      System.out.println("GuardianTowerAttackRange = " + game.getGuardianTowerAttackRange());
      System.out.println("GuardianTowerDamage = " + game.getGuardianTowerDamage());
      System.out.println("GuardianTowerCooldownTicks = " + game.getGuardianTowerCooldownTicks());
      System.out.println("FactionBaseRadius = " + game.getFactionBaseRadius());
      System.out.println("FactionBaseVisionRange = " + game.getFactionBaseVisionRange());
      System.out.println("FactionBaseLife = " + game.getFactionBaseLife());
      System.out.println("FactionBaseAttackRange = " + game.getFactionBaseAttackRange());
      System.out.println("FactionBaseDamage = " + game.getFactionBaseDamage());
      System.out.println("FactionBaseCooldownTicks = " + game.getFactionBaseCooldownTicks());
      System.out.println("BurningDurationTicks = " + game.getBurningDurationTicks());
      System.out.println("BurningSummaryDamage = " + game.getBurningSummaryDamage());
      System.out.println("EmpoweredDurationTicks = " + game.getEmpoweredDurationTicks());
      System.out.println("EmpoweredDamageFactor = " + game.getEmpoweredDamageFactor());
      System.out.println("FrozenDurationTicks = " + game.getFrozenDurationTicks());
      System.out.println("HastenedDurationTicks = " + game.getHastenedDurationTicks());
      System.out.println("HastenedBonusDurationFactor = " + game.getHastenedBonusDurationFactor());
      System.out.println("HastenedMovementBonusFactor = " + game.getHastenedMovementBonusFactor());
      System.out.println("HastenedRotationBonusFactor = " + game.getHastenedRotationBonusFactor());
      System.out.println("ShieldedDurationTicks = " + game.getShieldedDurationTicks());
      System.out.println("ShieldedBonusDurationFactor = " + game.getShieldedBonusDurationFactor());
      System.out.println(
          "ShieldedDirectDamageAbsorptionFactor = "
              + game.getShieldedDirectDamageAbsorptionFactor());
      System.out.println("AuraSkillRange = " + game.getAuraSkillRange());
      System.out.println("RangeBonusPerSkillLevel = " + game.getRangeBonusPerSkillLevel());
      System.out.println(
          "MagicalDamageBonusPerSkillLevel = " + game.getMagicalDamageBonusPerSkillLevel());
      System.out.println(
          "StaffDamageBonusPerSkillLevel = " + game.getStaffDamageBonusPerSkillLevel());
      System.out.println(
          "MovementBonusFactorPerSkillLevel = " + game.getMovementBonusFactorPerSkillLevel());
      System.out.println(
          "MagicalDamageAbsorptionPerSkillLevel = "
              + game.getMagicalDamageAbsorptionPerSkillLevel());
    }

    public void move(Wizard self, World world, Game game, Move move) {
      long startTime = System.nanoTime();

      updateObservers(self, world, game);

      if (true) {
        if (debug != null) {
          debug.showText(
              self.getX(),
              self.getY(),
              String.valueOf(self.getRemainingActionCooldownTicks()),
              Color.black);
          debug.drawAfterScene();
        }

        if (target == null
            || world.getTickIndex() % 200 == 0 && oldPos.getDistanceTo(self) < 15
            || target.getDistanceTo(self) < self.getRadius()) {
          System.out.println("new target");
          while (true) {
            target =
                new Point(
                    self.getX() + (random.nextDouble() * 2 - 1) * 4000,
                    self.getY() + (random.nextDouble() * 2 - 1) * 4000);
            if (!field.isSquareBlocked(target)
                && !(target.getX() < 0
                    || target.getY() < 0
                    || target.getX() > 4000
                    || target.getY() > 4000)) {
              break;
            }
          }
        }

        if (world.getTickIndex() % 200 == 0) {
          oldPos = new Point(self);
        }

        Point walkingTarget = target;

        if (debug != null) {
          debug.drawLine(self.getX(), self.getY(), target.getX(), target.getY(), Color.blue);
          debug.drawBeforeScene();
        }

        List<Square> path = field.findPath(Square.containing(self), Square.containing(target));
        if (path != null) {
          if (debug != null) {
            drawPath(path, Color.pink);
            debug.drawBeforeScene();
          }

          int i = 0;
          while (i + 1 < path.size() && !field.isLineBlocked(path.get(0), path.get(i + 1))) {
            ++i;
          }

          walkingTarget = path.get(i).getCenter();

          if (debug != null) {
            debug.drawLine(
                self.getX(),
                self.getY(),
                path.get(i).getCenterX(),
                path.get(i).getCenterY(),
                Color.red);
            debug.drawAfterScene();
          }
        }

        Point selfPoint = new Point(self);
        Point p1 =
            Point.fromPolar(game.getStaffRange(), self.getAngle() + game.getStaffSector() / 2)
                .add(selfPoint);
        Point p2 = Point.fromPolar(game.getStaffRange(), self.getAngle()).add(selfPoint);
        Point p3 =
            Point.fromPolar(game.getStaffRange(), self.getAngle() - game.getStaffSector() / 2)
                .add(selfPoint);
        if (debug != null) {
          debug.fillCircle(p1.getX(), p1.getY(), 2, Color.red);
          debug.fillCircle(p2.getX(), p2.getY(), 2, Color.red);
          debug.fillCircle(p3.getX(), p3.getY(), 2, Color.red);
          debug.drawAfterScene();
        }

        walker.goTo(walkingTarget, move);

        Tree targetTree =
            field
                .getSquaresOnLine(selfPoint, walkingTarget)
                .map(s -> field.squareToWeakTree.get(s))
                .filter(t -> t != null)
                .findFirst()
                .orElse(null);
        if (debug != null && targetTree != null) {
          debug.drawLine(
              self.getX(), self.getY(), targetTree.getX(), targetTree.getY(), Color.black);
        }
        walker.turnTo(targetTree != null ? new Point(targetTree) : walkingTarget, move);

        if (targetTree != null
            && Math.abs(self.getAngleTo(targetTree)) < game.getStaffSector() / 2
            && self.getRemainingActionCooldownTicks() == 0) {
          int[] cooldown = self.getRemainingCooldownTicksByAction();

          if (cooldown[ActionType.STAFF.ordinal()] == 0
              && (p1.getDistanceTo(targetTree) < targetTree.getRadius()
                  || p2.getDistanceTo(targetTree) < targetTree.getRadius()
                  || p3.getDistanceTo(targetTree) < targetTree.getRadius())) {
            move.setAction(ActionType.STAFF);
          } else if (cooldown[ActionType.MAGIC_MISSILE.ordinal()] == 0) {
            double distance = self.getDistanceTo(targetTree);
            if (distance <= self.getCastRange()) {
              double angle = self.getAngleTo(targetTree);
              if (StrictMath.abs(angle) < game.getStaffSector() / 2) {
                move.setAction(ActionType.MAGIC_MISSILE);
                move.setCastAngle(angle);
                move.setMinCastDistance(
                    distance - targetTree.getRadius() + game.getMagicMissileRadius());
              }
            }
          }
        }

        if (debug != null) {
          for (LivingUnit unit : field.getAllObstacles()) {
            debug.showText(unit.getX(), unit.getY(), String.valueOf(unit.getLife()), Color.black);
          }
          debug.drawAfterScene();
          debug.sync();
        }

        System.out.println("TIME " + (double) (System.nanoTime() - startTime) / 1000000);
        return;
      }

      if (world.getTickIndex() < IDLE_TICKS) {
        move.setTurn(2 * Math.PI / IDLE_TICKS);
        if (debug != null) {
          debug.sync();
        }
        return;
      }

      boolean lowHP = self.getLife() < 60;
      boolean reallyLowHP = self.getLife() < 40;

      BiPredicate<Unit, Double> endangeredBy =
          (u, attackRange) ->
              isEnemy(u) && self.getDistanceTo(u) < attackRange + self.getRadius() * 1.5;
      boolean inDanger =
          Arrays.asList(world.getWizards())
                  .stream()
                  .anyMatch(w -> endangeredBy.test(w, w.getCastRange()))
              || Arrays.asList(world.getBuildings())
                  .stream()
                  .anyMatch(b -> endangeredBy.test(b, b.getAttackRange()))
              || Arrays.asList(world.getMinions())
                  .stream()
                  .anyMatch(m -> endangeredBy.test(m, getMinionAttackRange(m)));
      if (inDanger && debug != null) {
        debug.drawCircle(self.getX(), self.getY(), self.getRadius() / 2, Color.red);
        debug.drawBeforeScene();
      }

      Point bonus = maybeGetBonus();
      Point walkingTarget = bonus != null ? bonus : field.getNextWaypoint();
      LivingUnit shootingTarget =
          shooter.getTarget(lowHP ? self.getCastRange() : self.getVisionRange());

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

      if (!lowHP && shootingTarget == null) {
        walker.turnTo(walkingTarget, move);
        walker.goTo(walkingTarget, move);
      } else if (!lowHP && shootingTarget != null) {
        walker.turnTo(shootingTarget, move);
        if (bonus != null) {
          walker.goTo(walkingTarget, move);
        } else if (self.getDistanceTo(shootingTarget) > self.getCastRange()) {
          walker.goTo(shootingTarget, move);
        }
      } else if (lowHP) {
        walker.turnTo(shootingTarget != null ? new Point(shootingTarget) : walkingTarget, move);
        if (reallyLowHP || inDanger) {
          walker.goTo(field.getPreviousWaypoint(), move);
        } else {
          walker.goTo(walkingTarget, move);
        }
      }

      int[] cooldown = self.getRemainingCooldownTicksByAction();
      if (self.getRemainingActionCooldownTicks() == 0
          && cooldown[ActionType.STAFF.ordinal()] == 0
          && cooldown[ActionType.MAGIC_MISSILE.ordinal()] >= game.getWizardActionCooldownTicks()) {
        move.setAction(ActionType.STAFF);
      }

      if (debug != null) {
        if (false) {
          int N = 5;
          double R = self.getCastRange() * 2 / 3;
          for (double dx = -R; dx < R; dx += R / N) {
            for (double dy = -R; dy < R; dy += R / N) {
              Point point = new Point(self.getX() + dx, self.getY() + dy);
              debug.drawCircle(point.getX(), point.getY(), 3, Color.lightGray);
              for (Building building : world.getBuildings()) {
                if (isEnemy(building)) {}
              }
            }
          }
          debug.drawBeforeScene();
        }

        if (walkingTarget != null) {
          debug.fillCircle(walkingTarget.getX(), walkingTarget.getY(), 5, Color.blue);
          debug.drawBeforeScene();
        }

        if (shootingTarget != null) {
          debug.fillCircle(shootingTarget.getX(), shootingTarget.getY(), 5, Color.red);
          debug.drawLine(
              self.getX(), self.getY(), shootingTarget.getX(), shootingTarget.getY(), Color.red);
          debug.drawBeforeScene();
        }

        for (Building building : world.getBuildings()) {
          debug.drawCircle(
              building.getX(), building.getY(), building.getVisionRange(), Color.lightGray);
          debug.drawCircle(building.getX(), building.getY(), building.getAttackRange(), Color.pink);
          debug.drawBeforeScene();

          debug.showText(
              building.getX(),
              building.getY() + 20,
              String.valueOf(building.getRemainingActionCooldownTicks()),
              Color.black);
          debug.drawAfterScene();
        }

        debug.showText(
            self.getX(),
            self.getY(),
            String.valueOf(self.getRemainingActionCooldownTicks()),
            Color.black);
        debug.showText(
            self.getX(),
            self.getY() + 20,
            String.valueOf(
                self.getRemainingCooldownTicksByAction()[ActionType.MAGIC_MISSILE.ordinal()]),
            Color.black);
        debug.drawAfterScene();

        for (LivingUnit unit : field.getAllObstacles()) {
          debug.showText(unit.getX(), unit.getY(), String.valueOf(unit.getLife()), Color.black);
        }
        debug.drawAfterScene();

        debug.sync();
      }
      System.out.println("TIME " + (double) (System.nanoTime() - startTime) / 1000000);
    }

    private void updateObservers(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;
      for (WorldObserver observer : observers) {
        observer.update(self, world, game);
      }
    }

    private Point maybeGetBonus() {
      Point bonus = bonusFinder.findBonus();

      // Restrict bonus chasing area.
      Point a1 = new Point(game.getMapSize() * 0.2, game.getMapSize() * 0.1);
      Point b1 = new Point(game.getMapSize() * (1 - 0.1), game.getMapSize() * (1 - 0.2));
      Point a2 = new Point(game.getMapSize() * 0.1, game.getMapSize() * 0.2);
      Point b2 = new Point(game.getMapSize() * (1 - 0.2), game.getMapSize() * (1 - 0.1));
      Point center = new Point(game.getMapSize() * 0.5, game.getMapSize() * 0.5);

      double BONUS_CHASE_RADIUS = 300;
      Point selfPoint = new Point(self);
      if (bonus != null
          && center.getDistanceTo(self) > BONUS_CHASE_RADIUS
          && Point.isClockwise(a1, b1, selfPoint) == Point.isClockwise(a2, b2, selfPoint)) {
        bonus = null;
      }

      if (debug != null) {
        debug.drawLine(a1.getX(), a1.getY(), b1.getX(), b1.getY(), Color.lightGray);
        debug.drawLine(a2.getX(), a2.getY(), b2.getX(), b2.getY(), Color.lightGray);
        debug.drawCircle(center.getX(), center.getY(), BONUS_CHASE_RADIUS, Color.lightGray);
        debug.drawBeforeScene();

        if (bonus != null) {
          debug.fillCircle(self.getX(), self.getY(), 10, Color.green);
          debug.drawAfterScene();
        }
      }

      return bonus;
    }

    private double getMinionAttackRange(Minion m) {
      return m.getType() == MinionType.FETISH_BLOWDART
          ? game.getFetishBlowdartAttackRange()
          : game.getOrcWoodcutterAttackRange();
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

    Point predictPosition(Unit unit, double ticksFromNow) {
      return new Point(
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

    void drawPath(List<Square> path, Color color) {
      for (int i = 1; i < path.size(); ++i) {
        debug.drawLine(
            path.get(i - 1).getCenterX(),
            path.get(i - 1).getCenterY(),
            path.get(i).getCenterX(),
            path.get(i).getCenterY(),
            color);
      }
    }

    public Minion[] getFetishes() {
      return Arrays.stream(world.getMinions())
          .filter(m -> m.getType() == MinionType.FETISH_BLOWDART)
          .toArray(size -> new Minion[size]);
    }

    public Minion[] getWoodcutters() {
      return Arrays.stream(world.getMinions())
          .filter(m -> m.getType() == MinionType.ORC_WOODCUTTER)
          .toArray(size -> new Minion[size]);
    }
  }

  private abstract static class WorldObserver {

    protected final Brain brain;
    protected final Visualizer debug;
    protected Wizard self;
    protected World world;
    protected Game game;

    public WorldObserver(Brain brain, Visualizer debug) {
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

  private static class BonusFinder extends WorldObserver {

    private Point bonus;
    private Point oldPosition;

    public BonusFinder(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    @Override
    public void update() {
      if (world.getTickIndex() != 0
          && world.getTickIndex() % game.getBonusAppearanceIntervalTicks() == 0) {
        bonus = new Point(game.getMapSize() * 0.7, game.getMapSize() * 0.7);
        oldPosition = null;
        if (debug != null) {
          System.out.println("bonus at " + bonus);
        }
      }

      if (world.getTickIndex() % 200 == 0) {
        if (oldPosition != null
            && bonus != null
            && oldPosition.getDistanceTo(self) < self.getRadius()) {
          if (debug != null) {
            System.out.println("stuck chasing a bonus");
          }
          bonus = null;
        }
        oldPosition = new Point(self);
      }
      if (debug != null && oldPosition != null) {
        debug.drawCircle(oldPosition.getX(), oldPosition.getY(), self.getRadius(), Color.gray);
        debug.drawBeforeScene();
      }

      if (bonus != null && bonus.getDistanceTo(self) < self.getVisionRange()) {
        boolean bonusExists =
            Arrays.stream(world.getBonuses())
                .anyMatch(b -> b.getDistanceTo(self) < self.getVisionRange());
        if (!bonusExists) {
          if (debug != null) {
            System.out.println("bonus disappeared");
          }
          bonus = null;
        }
      }
    }

    public Point findBonus() {
      return bonus;
    }
  }

  private static class Field extends WorldObserver {

    private final Point[] waypoints;
    private Set<Square> blockedSquares = new HashSet<>();
    private Map<Square, Tree> squareToWeakTree = new HashMap<>();

    public Field(Brain brain, Visualizer debug, Wizard self, Game game) {
      super(brain, debug);

      double mapSize = game.getMapSize();

      waypoints =
          new Point[] {
            new Point(100.0D, mapSize - 100.0D),
            self.getId() == 1 || self.getId() == 2 || self.getId() == 6 || self.getId() == 7
                ? new Point(200.0D, mapSize - 600.0D)
                : new Point(600.0D, mapSize - 200.0D),
            new Point(800.0D, mapSize - 800.0D),
            new Point(mapSize * 0.35, mapSize * 0.65),
            new Point(mapSize * 0.45, mapSize * 0.55),
            new Point(mapSize * 0.55, mapSize * 0.45),
            new Point(mapSize * 0.65, mapSize * 0.35),
            new Point(mapSize * 0.75, mapSize * 0.25),
          };
    }

    @Override
    public void update() {
      updateBlockedSquares();
      updateWeakTrees();

      if (debug != null) {
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
        debug.drawBeforeScene();
      }
    }

    private void updateWeakTrees() {
      squareToWeakTree.clear();
      for (Tree tree : world.getTrees()) {
        if (tree.getLife() <= game.getMagicMissileDirectDamage()) {
          for (Square square : getSquaresBlockedBy(tree)) {
            squareToWeakTree.put(square, tree);
          }
        }
      }
    }

    private void updateBlockedSquares() {
      blockedSquares.clear();
      for (LivingUnit unit : getAllObstacles()) {
        if (unit.getLife() > game.getMagicMissileDirectDamage()) {
          blockedSquares.addAll(getSquaresBlockedBy(unit));
        }
      }
      blockedSquares.remove(Square.containing(self));

      if (debug != null) {
        for (Square square : blockedSquares) {
          debug.fillRect(
              square.getLeftX(),
              square.getTopY(),
              square.getRightX(),
              square.getBottomY(),
              Color.pink);
          debug.drawBeforeScene();
        }
      }
    }

    private List<Square> getSquaresBlockedBy(LivingUnit unit) {
      List<Square> result = new ArrayList<>();
      double r = unit.getRadius() + SQUARE_CRUDENESS / 2 * 1.5 + self.getRadius();
      Square topLeft = Square.containing(unit.getX() - r, unit.getY() - r);
      Square bottomRight = Square.containing(unit.getX() + r, unit.getY() + r);
      for (int p = topLeft.getP(); p <= bottomRight.getP(); ++p) {
        for (int q = topLeft.getQ(); q <= bottomRight.getQ(); ++q) {
          Square square = new Square(p, q);
          if (unit.getDistanceTo(square.getCenterX(), square.getCenterY()) < r) {
            result.add(square);
          }
        }
      }
      return result;
    }

    public boolean isSquareBlocked(Square square) {
      return blockedSquares.contains(square);
    }

    public boolean isSquareBlocked(Point point) {
      return blockedSquares.contains(Square.containing(point));
    }

    public boolean isLineBlocked(Square a, Square b) {
      return isLineBlocked(a.getCenter(), b.getCenter());
    }

    public boolean isLineBlocked(Point a, Point b) {
      return getSquaresOnLine(a, b).anyMatch(s -> isSquareBlocked(s));
    }

    public Stream<Point> getPointsOnLine(Point a, Point b) {
      Point ab = b.sub(a);
      int n = (int) Math.ceil(ab.length() / SQUARE_CRUDENESS) + 1;
      return IntStream.rangeClosed(0, n).mapToObj(i -> a.add(ab.mul((double) i / n)));
    }

    public Stream<Square> getSquaresOnLine(Point a, Point b) {
      return getPointsOnLine(a, b).map(p -> Square.containing(p));
    }

    public Point getNextWaypoint() {
      int lastWaypointIndex = waypoints.length - 1;
      Point lastWaypoint = waypoints[lastWaypointIndex];

      for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
        Point waypoint = waypoints[waypointIndex];

        if (waypoint.getDistanceTo(self) <= self.getRadius() * 2) {
          return waypoints[waypointIndex + 1];
        }

        if (lastWaypoint.getDistanceTo(waypoint) < lastWaypoint.getDistanceTo(self)) {
          return waypoint;
        }
      }

      return lastWaypoint;
    }

    public Point getPreviousWaypoint() {
      Point firstWaypoint = waypoints[0];

      for (int waypointIndex = waypoints.length - 1; waypointIndex > 0; --waypointIndex) {
        Point waypoint = waypoints[waypointIndex];

        if (waypoint.getDistanceTo(self) <= self.getRadius() * 2) {
          return waypoints[waypointIndex - 1];
        }

        if (firstWaypoint.getDistanceTo(waypoint) < firstWaypoint.getDistanceTo(self)) {
          return waypoint;
        }
      }

      return firstWaypoint;
    }

    public List<Square> findPath(Square start, Square end) {
      if (start == null || end == null) {
        return null;
      }

      Map<Square, Square> cameFrom = new HashMap<>();
      Map<Square, Integer> distance = new HashMap<>();
      Map<Square, Integer> distanceGuess = new HashMap<>();
      SortedSet<Square> queue =
          new TreeSet<>((Square a, Square b) -> distanceGuess.get(a) - distanceGuess.get(b));
      Set<Square> done = new HashSet<>();

      cameFrom.put(start, null);
      distance.put(start, 0);
      distanceGuess.put(start, 0);
      queue.add(start);

      final int MAX_STEPS = 10000;
      for (int i = 0; !queue.isEmpty() && i < MAX_STEPS; ++i) {
        Square point = queue.first();
        queue.remove(point);

        if (point.equals(end)) {
          break;
        }
        done.add(point);

        int distanceToPoint = distance.get(point);
        for (Square neighbor : getNeighbors(point)) {
          if (isSquareBlocked(neighbor) || done.contains(neighbor)) {
            continue;
          }

          Integer oldDistance = distance.get(neighbor);
          int newDistance = distanceToPoint + squaredDistance(point, neighbor);
          if (oldDistance == null || newDistance < oldDistance) {
            int newGuess = newDistance + squaredDistance(neighbor, end);
            cameFrom.put(neighbor, point);
            distance.put(neighbor, newDistance);
            distanceGuess.put(neighbor, newGuess);
            queue.remove(neighbor);
            queue.add(neighbor);
          }
        }
      }

      List<Square> path = new ArrayList<>();
      for (Square point = end; point != null; point = cameFrom.get(point)) {
        path.add(point);
      }
      Collections.reverse(path);
      return path.size() != 1 ? path : null;
    }

    private Square[] getNeighbors(Square square) {
      int p = square.getP();
      int q = square.getQ();
      return new Square[] {
        new Square(p - 1, q),
        new Square(p, q - 1),
        new Square(p + 1, q),
        new Square(p, q + 1),
        new Square(p - 1, q - 1),
        new Square(p + 1, q - 1),
        new Square(p - 1, q + 1),
        new Square(p + 1, q + 1),
      };
    }

    private int squaredDistance(Square a, Square b) {
      return (a.getP() - b.getP()) * (a.getP() - b.getP())
          + (a.getQ() - b.getQ()) * (a.getQ() - b.getQ());
    }

    List<LivingUnit> getAllObstacles() {
      List<LivingUnit> units = new ArrayList<>();
      units.addAll(
          Arrays.stream(world.getWizards()).filter(w -> !w.isMe()).collect(Collectors.toList()));
      units.addAll(Arrays.asList(world.getMinions()));
      units.addAll(Arrays.asList(world.getBuildings()));
      units.addAll(Arrays.asList(world.getTrees()));
      return units;
    }
  }

  private static class Walker extends WorldObserver {

    public Walker(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    public void goTo(Point target, Move move) {
      double angle = self.getAngleTo(target.getX(), target.getY());

      boolean hastened =
          Arrays.stream(self.getStatuses()).anyMatch(s -> s.getType() == StatusType.HASTENED);
      double speedMultiplier = hastened ? 1 + game.getHastenedMovementBonusFactor() : 1;

      int aSign = Math.abs(angle) < Math.PI / 2 ? 1 : -1;
      Point a =
          aSign == 1
              ? Point.fromPolar(speedMultiplier * game.getWizardForwardSpeed(), self.getAngle())
              : Point.fromPolar(speedMultiplier * game.getWizardBackwardSpeed(), self.getAngle())
                  .negate();

      int bSign = angle > 0 ? 1 : -1;
      Point b =
          bSign == 1
              ? Point.fromPolar(
                  speedMultiplier * game.getWizardStrafeSpeed(), self.getAngle() + Math.PI / 2)
              : Point.fromPolar(
                  speedMultiplier * game.getWizardStrafeSpeed(), self.getAngle() - Math.PI / 2);

      Point ba = a.sub(b);
      boolean aIsClockwiseToB = a.isClockwiseTo(b);
      Point direction = target.sub(new Point(self));
      double k =
          binarySearch(
              0,
              1,
              m -> {
                Point v = direction.mul(m);
                Point bv = v.sub(b);
                return bv.isClockwiseTo(ba) == aIsClockwiseToB;
              });

      // Speed.
      Point v = direction.mul(k);

      // Try avoiding collisions.
      final int T = 8; // Look-ahead ticks.
      final int N = 4; // Alternatives to consider.

      /*
      if (debug != null) {
        for (int i = 1; i <= N * 2; ++i) {
          int j = i / 2;
          int sign = i % 2 == 0 ? 1 : -1;
          Point newV = v.rotate(sign * Math.PI * 2 * j / N);
          debug.drawCircle(
              self.getX() + T * newV.getX(), self.getY() + T * newV.getY(), 3, Color.orange);
        }
        debug.fillCircle(self.getX() + T * v.getX(), self.getY() + T * v.getY(), 4, Color.cyan);
        debug.drawAfterScene();
      }

      List<LivingUnit> obstacles = brain.field.getAllObstacles();
      for (int i = 1; i <= N * 2; ++i) {
        int j = i / 2;
        int sign = i % 1 == 0 ? 1 : -1;
        Point newV = v.rotate(sign * Math.PI * 2 * j / N);
        Point newSelf = newV.mul(T).add(new Point(self));
        boolean noCollisions =
            obstacles
                .stream()
                .allMatch(u -> newSelf.getDistanceTo(u) > u.getRadius() + self.getRadius());
        if (noCollisions) {
          v = newV;
          break;
        }
      }
      */

      move.setSpeed(aSign * v.project(a));
      move.setStrafeSpeed(bSign * v.project(b));

      if (debug != null) {
        debug.fillCircle(self.getX() + T * v.getX(), self.getY() + T * v.getY(), 5, Color.orange);

        boolean DISPLAY_BOX = false;
        if (DISPLAY_BOX) {
          List<Point> box = new ArrayList<>();
          box.add(
              new Point(
                  self.getX() + T * game.getWizardForwardSpeed() * Math.cos(self.getAngle()),
                  self.getY() + T * game.getWizardForwardSpeed() * Math.sin(self.getAngle())));
          box.add(
              new Point(
                  self.getX()
                      + T * game.getWizardStrafeSpeed() * Math.cos(self.getAngle() + Math.PI / 2),
                  self.getY()
                      + T * game.getWizardStrafeSpeed() * Math.sin(self.getAngle() + Math.PI / 2)));
          box.add(
              new Point(
                  self.getX()
                      + T * game.getWizardBackwardSpeed() * Math.cos(self.getAngle() + Math.PI),
                  self.getY()
                      + T * game.getWizardBackwardSpeed() * Math.sin(self.getAngle() + Math.PI)));
          box.add(
              new Point(
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
            debug.drawLine(
                box.get(i).getX(),
                box.get(i).getY(),
                box.get(j).getX(),
                box.get(j).getY(),
                Color.blue);
          }
        }

        debug.drawAfterScene();
      }
    }

    public void turnTo(Point point, Move move) {
      double angle = self.getAngleTo(point.getX(), point.getY());
      move.setTurn(angle);
    }

    public void goTo(Unit unit, Move move) {
      goTo(new Point(unit), move);
    }

    public void turnTo(Unit unit, Move move) {
      turnTo(new Point(unit), move);
    }
  }

  private static class Shooter extends WorldObserver {

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
        debug.drawBeforeScene();
      }
    }

    public LivingUnit getTarget(double range) {
      LivingUnit buildingTarget = getTargetHomo(world.getBuildings(), range);
      LivingUnit wizardTarget = getTargetHomo(world.getWizards(), range);
      LivingUnit closestWoodcutter = getClosestWoodcutter();
      if (closestWoodcutter != null
          && self.getDistanceTo(closestWoodcutter)
              < self.getRadius() * 1.5
                  + closestWoodcutter.getRadius()
                  + game.getOrcWoodcutterAttackRange()) {
        return closestWoodcutter;
      }
      if (buildingTarget == null && wizardTarget == null) {
        LivingUnit fetishTarget = getTargetHomo(brain.getFetishes(), range);
        LivingUnit woodcutterTarget = getTargetHomo(brain.getWoodcutters(), range);
        if (fetishTarget != null
            && woodcutterTarget != null
            && woodcutterTarget.getLife() <= game.getMagicMissileDirectDamage()) {
          return woodcutterTarget;
        }
        return fetishTarget != null ? fetishTarget : woodcutterTarget;
      }
      if (buildingTarget == null) {
        return wizardTarget;
      }
      return buildingTarget;
    }

    private LivingUnit getClosestWoodcutter() {
      LivingUnit closestWoodcutter = null;
      double closestWoodcutterDistance = 0;
      for (Minion minion : world.getMinions()) {
        if (!brain.isEnemy(minion) || minion.getType() != MinionType.ORC_WOODCUTTER) {
          continue;
        }
        double distance = self.getDistanceTo(minion);
        if (closestWoodcutter == null || distance < closestWoodcutterDistance) {
          closestWoodcutter = minion;
          closestWoodcutterDistance = distance;
        }
      }
      return closestWoodcutter;
    }

    private LivingUnit getTargetHomo(LivingUnit[] units, double range) {
      LivingUnit bestTarget = null;
      for (LivingUnit target : units) {
        if (!brain.isEnemy(target)) {
          continue;
        }
        if (self.getDistanceTo(target) > range) {
          continue;
        }
        if (bestTarget == null || target.getLife() < bestTarget.getLife()) {
          bestTarget = target;
        }
      }
      return bestTarget;
    }
  }

  private static class Point {

    private final double x;
    private final double y;

    public Point(double x, double y) {
      this.x = x;
      this.y = y;
    }

    public Point(Unit unit) {
      this(unit.getX(), unit.getY());
    }

    public static Point fromPolar(double radius, double angle) {
      return new Point(radius * Math.cos(angle), radius * Math.sin(angle));
    }

    public static boolean isClockwise(Point base, Point first, Point second) {
      return first.sub(base).isClockwiseTo(second.sub(base));
    }

    public static double getAngle(Point base, Point a, Point b) {
      return getAngle(a.sub(base), b.sub(base));
    }

    public static double getAngle(Point a, Point b) {
      double angle = b.getAngle() - a.getAngle();

      while (angle > Math.PI) {
        angle -= 2.0D * Math.PI;
      }

      while (angle < -Math.PI) {
        angle += 2.0D * Math.PI;
      }

      return angle;
    }

    @Override
    public String toString() {
      return "Point{" + x + ", " + y + '}';
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

    public double getDistanceTo(Point point) {
      return getDistanceTo(point.x, point.y);
    }

    public double getDistanceTo(Unit unit) {
      return getDistanceTo(unit.getX(), unit.getY());
    }

    public Point negate() {
      return new Point(-x, -y);
    }

    public Point unit() {
      double len = length();
      return new Point(x / len, y / len);
    }

    public double length() {
      return Math.sqrt(x * x + y * y);
    }

    public Point mul(double k) {
      return new Point(k * x, k * y);
    }

    public Point add(Point other) {
      return new Point(this.x + other.x, this.y + other.y);
    }

    public Point sub(Point other) {
      return new Point(this.x - other.x, this.y - other.y);
    }

    public double dot(Point other) {
      return this.x * other.x + this.y * other.y;
    }

    public double cross(Point other) {
      return this.x * other.y - this.y * other.x;
    }

    public boolean isClockwiseTo(Point other) {
      return cross(other) < 0;
    }

    public double project(Point other) {
      return this.dot(other.unit());
    }

    public Point rotate(double angle) {
      double sin = Math.sin(angle);
      double cos = Math.cos(angle);
      return new Point(x * cos - y * sin, y * cos + x * sin);
    }

    public double getAngle() {
      return Math.atan2(y, x);
    }
  }

  private static class Square {

    private final int p;
    private final int q;

    public Square(int p, int q) {
      this.p = p;
      this.q = q;
    }

    public static Square containing(double x, double y) {
      int centerP = (int) Math.floor(x / SQUARE_CRUDENESS);
      int centerQ = (int) Math.floor(y / SQUARE_CRUDENESS);
      return new Square(centerP, centerQ);
    }

    public static Square containing(Point point) {
      return Square.containing(point.getX(), point.getY());
    }

    public static Square containing(Unit unit) {
      return Square.containing(unit.getX(), unit.getY());
    }

    @Override
    public String toString() {
      return "Square{" + p + ", " + q + '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Square square = (Square) o;
      if (p != square.p) return false;
      return q == square.q;
    }

    @Override
    public int hashCode() {
      return p * 100000 + q;
    }

    public int getP() {
      return p;
    }

    public int getQ() {
      return q;
    }

    public double getCenterX() {
      return (p + 0.5) * SQUARE_CRUDENESS;
    }

    public double getCenterY() {
      return (q + 0.5) * SQUARE_CRUDENESS;
    }

    public Point getCenter() {
      return new Point(getCenterX(), getCenterY());
    }

    public double getLeftX() {
      return p * SQUARE_CRUDENESS;
    }

    public double getRightX() {
      return (p + 1) * SQUARE_CRUDENESS;
    }

    public double getTopY() {
      return q * SQUARE_CRUDENESS;
    }

    public double getBottomY() {
      return (q + 1) * SQUARE_CRUDENESS;
    }
  }
}
