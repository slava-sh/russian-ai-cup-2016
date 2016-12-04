import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import model.ActionType;
import model.Building;
import model.BuildingType;
import model.Faction;
import model.Game;
import model.LivingUnit;
import model.Minion;
import model.MinionType;
import model.Move;
import model.SkillType;
import model.StatusType;
import model.Tree;
import model.Unit;
import model.Wizard;
import model.World;

public final class MyStrategy implements Strategy {

  private static final int SQUARE_CRUDENESS = 20;
  private static final double SAFETY_EPS = 15;
  private static final int LOOKAHEAD_TICKS = 50;

  private static final boolean PRINT_MOVE_TIME = false;
  private static final boolean LOAD_DEBUG_VISUALIZER = true;
  private static final boolean DEBUG_DRAW_PATH = true;
  private static final boolean DEBUG_FIND_PATH = false;
  private static final boolean DEBUG_DRAW_WALLS = false;
  private static final boolean DEBUG_DRAW_WEAK_TREES = false;
  private static final boolean DEBUG_CIRCLE_OBSTACLES = false;
  private static final boolean DEBUG_DRAW_MOVING_UNITS = false;
  private static final boolean DEBUG_SHOW_OBSTACLE_HP = false;
  private static final boolean DEBUG_SHOW_COOLDOWN_TICKS = true;

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

  private static boolean distanceLessThan(Unit a, Unit b, double distance) {
    double dx = a.getX() - b.getX();
    double dy = a.getY() - b.getY();
    return dx * dx + dy * dy < distance * distance;
  }

  private static boolean distanceLessThan(Unit a, Point b, double distance) {
    double dx = a.getX() - b.getX();
    double dy = a.getY() - b.getY();
    return dx * dx + dy * dy < distance * distance;
  }

  private static boolean distanceLessThan(Point a, Unit b, double distance) {
    double dx = a.getX() - b.getX();
    double dy = a.getY() - b.getY();
    return dx * dx + dy * dy < distance * distance;
  }

  private static boolean distanceLessThan(Point a, Point b, double distance) {
    double dx = a.getX() - b.getX();
    double dy = a.getY() - b.getY();
    return dx * dx + dy * dy < distance * distance;
  }

  private static double lerp(double a, double b, double t) {
    return a + (b - a) * t;
  }

  @Override
  public void move(Wizard self, World world, Game game, Move move) {
    long startTime = PRINT_MOVE_TIME ? System.nanoTime() : 0;

    if (brain == null) {
      brain = new Brain(self, world, game);
    }
    brain.move(self, world, game, move);

    if (PRINT_MOVE_TIME) {
      long elapsedMilliseconds = (System.nanoTime() - startTime) / 1000000;
      System.out.println("tick " + world.getTickIndex() + " took " + elapsedMilliseconds + " ms");
    }
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

    private final Faction ALLY_FRACTION;
    private final Faction ENEMY_FRACTION;

    private final Random random;
    private final Visualizer debug;
    private final List<WorldObserver> observers;
    private final Stuck stuck;
    private final BonusFinder bonusFinder;
    private final Field field;
    private final Walker walker;
    private final Shooter shooter;
    private final Skiller skiller;
    protected Wizard self;
    protected World world;
    protected Game game;

    public Brain(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;

      ALLY_FRACTION = self.getFaction();
      ENEMY_FRACTION = ALLY_FRACTION == Faction.ACADEMY ? Faction.RENEGADES : Faction.ACADEMY;

      Visualizer debugVisualizer = null;
      if (LOAD_DEBUG_VISUALIZER) {
        try {
          Class<?> clazz = Class.forName("DebugVisualizer");
          Object instance = clazz.getConstructor().newInstance();
          debugVisualizer = (Visualizer) instance;
        } catch (Exception e) {
          throw new RuntimeException("Cannot load DebugVisualizer");
        }
      }
      debug = debugVisualizer;

      random = new Random(game.getRandomSeed());

      observers = new ArrayList<>();

      stuck = new Stuck(this, debug, random);
      observers.add(stuck);

      bonusFinder = new BonusFinder(this, debug, game);
      observers.add(bonusFinder);

      field = new Field(this, debug, self, world, game);
      observers.add(field);

      walker = new Walker(this, debug);
      observers.add(walker);

      shooter = new Shooter(this, debug);
      observers.add(shooter);

      skiller = new Skiller(this, debug);
      observers.add(skiller);

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
      updateObservers(self, world, game);

      boolean lowHP = self.getLife() < 60;
      boolean reallyLowHP = self.getLife() < 40;

      boolean inDanger = isAllyInDanger(self);
      if (debug != null && inDanger) {
        debug.drawCircle(self.getX(), self.getY(), self.getRadius() / 2, Color.red);
        debug.drawBeforeScene();
      }

      Building factionBase = getAllyFactionBase();
      boolean factionBaseInDanger = factionBase != null && isAllyInDanger(factionBase);

      Point bonus = bonusFinder.findBonus();

      LivingUnit shootingTarget =
          shooter.getTarget(lowHP ? self.getCastRange() : self.getVisionRange());

      boolean inHomeArea =
          self.getX() < world.getWidth() * 0.40 && self.getY() > world.getHeight() * 0.60;

      Point selfPoint = new Point(self);

      Point walkingTarget;
      if (bonus != null && !(inHomeArea && inDanger)) {
        walkingTarget = bonus;
      } else if (!lowHP && shootingTarget == null) {
        walkingTarget = field.getNextWaypoint();
      } else if (!lowHP && self.getDistanceTo(shootingTarget) > self.getCastRange()) {
        walkingTarget = new Point(shootingTarget);
      } else if (reallyLowHP || inDanger) {
        walkingTarget = field.getPreviousWaypoint();
      } else if (factionBaseInDanger) {
        walkingTarget = field.getFactionBaseWaypoint();
      } else {
        walkingTarget = field.getNextWaypoint();
      }

      Point shortWalkingTarget = getShortWalkingTarget(walkingTarget);

      Tree targetTree = getTargetTree(selfPoint, shortWalkingTarget);
      if (targetTree != null) {
        shootingTarget = targetTree;
      }

      walker.goTo(shortWalkingTarget, move);
      stuck.unstuck(move);

      if (shootingTarget != null) {
        walker.turnTo(shootingTarget, move);
      } else if (walkingTarget != selfPoint) {
        walker.turnTo(shortWalkingTarget, move);
      } else {
        LivingUnit nextShootingTarget = shooter.getTarget(self.getVisionRange() * 2);
        if (nextShootingTarget != null) {
          walker.turnTo(nextShootingTarget, move);
        } else {
          walker.turnTo(field.getNextWaypoint(), move);
        }
      }

      if (self.getRemainingActionCooldownTicks() == 0) {
        int[] cooldown = self.getRemainingCooldownTicksByAction();
        if (shootingTarget != null
            && cooldown[ActionType.STAFF.ordinal()] == 0
            && shooter.staffCanReach(shootingTarget)) {
          move.setAction(ActionType.STAFF);
        } else if (skiller.hasSkill(SkillType.SHIELD)
            && cooldown[ActionType.SHIELD.ordinal()] == 0
            && self.getMana() >= game.getShieldManacost()
            && Arrays.stream(self.getStatuses())
                .noneMatch(s -> s.getType() == StatusType.SHIELDED)) {
          move.setAction(ActionType.SHIELD);
        } else if (skiller.hasSkill(SkillType.HASTE)
            && cooldown[ActionType.HASTE.ordinal()] == 0
            && self.getMana() >= game.getHasteManacost()
            && Arrays.stream(self.getStatuses())
                .noneMatch(s -> s.getType() == StatusType.HASTENED)) {
          move.setAction(ActionType.HASTE);
        } else if (shootingTarget != null
            && cooldown[ActionType.MAGIC_MISSILE.ordinal()] == 0
            && self.getMana() >= game.getMagicMissileManacost()
            && shooter.missileCanReach(shootingTarget)) {
          move.setAction(ActionType.MAGIC_MISSILE);
          move.setCastAngle(self.getAngleTo(shootingTarget));
          move.setMinCastDistance(
              self.getDistanceTo(shootingTarget)
                  - shootingTarget.getRadius()
                  + game.getMagicMissileRadius());
        }
      }

      skiller.maybeLearnSkill(move);

      if (debug != null) {
        debug.showText(
            self.getX(),
            self.getY(),
            String.valueOf(self.getRemainingActionCooldownTicks()),
            Color.black);
        debug.drawAfterScene();

        if (DEBUG_CIRCLE_OBSTACLES) {
          field
              .getAllObstacles()
              .stream()
              .forEach(
                  u ->
                      debug.drawCircle(
                          u.getX(), u.getY(), u.getRadius() + self.getRadius(), Color.yellow));
          debug.drawAfterScene();
        }

        if (DEBUG_SHOW_OBSTACLE_HP) {
          for (LivingUnit unit : field.getAllObstacles()) {
            debug.showText(unit.getX(), unit.getY(), String.valueOf(unit.getLife()), Color.black);
          }
          debug.drawAfterScene();
        }

        if (stuck.state == Stuck.State.STUCK) {
          debug.showText(self.getX() - 20, self.getY() + 20, "Stuck", Color.red);
          debug.drawAfterScene();
        }

        if (shortWalkingTarget != null) {
          debug.drawLine(
              self.getX(),
              self.getY(),
              shortWalkingTarget.getX(),
              shortWalkingTarget.getY(),
              Color.green);
          debug.drawBeforeScene();
        }

        if (walkingTarget != null) {
          debug.fillCircle(walkingTarget.getX(), walkingTarget.getY(), 5, Color.green);
          debug.drawBeforeScene();
        }

        if (shootingTarget != null) {
          debug.fillCircle(shootingTarget.getX(), shootingTarget.getY(), 5, Color.red);
          debug.drawLine(
              self.getX(), self.getY(), shootingTarget.getX(), shootingTarget.getY(), Color.red);
          debug.drawBeforeScene();
        }

        if (DEBUG_SHOW_COOLDOWN_TICKS) {
          for (Building building : world.getBuildings()) {
            debug.drawCircle(
                building.getX(), building.getY(), building.getVisionRange(), Color.lightGray);
            debug.drawCircle(
                building.getX(), building.getY(), building.getAttackRange(), Color.pink);
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
        }

        debug.sync();
      }
    }

    Building getAllyFactionBase() {
      return Arrays.stream(world.getBuildings())
          .filter(b -> isAlly(b) && b.getType() == BuildingType.FACTION_BASE)
          .findFirst()
          .orElse(null);
    }

    boolean isAllyInDanger(LivingUnit ally) {
      return Arrays.stream(world.getWizards()).anyMatch(w -> isAllyEndangeredBy(ally, w))
          || Arrays.stream(world.getBuildings()).anyMatch(b -> isAllyEndangeredBy(ally, b))
          || Arrays.stream(world.getMinions()).anyMatch(m -> isAllyEndangeredBy(ally, m));
    }

    boolean isAllyEndangeredBy(LivingUnit ally, LivingUnit attacker) {
      return isEnemy(attacker)
          && distanceLessThan(
              ally, attacker, getAttackRange(attacker) + ally.getRadius() + SAFETY_EPS);
    }

    private Tree getTargetTree(Point selfPoint, Point walkingTarget) {
      return field
          .getSquaresOnLine(selfPoint, walkingTarget)
          .map(s -> field.weakTrees.get(s))
          .filter(t -> t != null)
          .findFirst()
          .orElse(null);
    }

    Stream<LivingUnit> getAllies() {
      return Stream.of(
              Arrays.stream(world.getWizards()),
              Arrays.stream(world.getBuildings()),
              Arrays.stream(world.getMinions()))
          .flatMap(Function.identity())
          .filter(u -> isAlly(u));
    }

    boolean canSee(Point point) {
      return getAllies().anyMatch(ally -> distanceLessThan(ally, point, getVisionRange(ally)));
    }

    double getVisionRange(LivingUnit unit) {
      if (unit instanceof Wizard) {
        return ((Wizard) unit).getVisionRange();
      }
      if (unit instanceof Minion) {
        return ((Minion) unit).getVisionRange();
      }
      if (unit instanceof Building) {
        return ((Building) unit).getVisionRange();
      }
      throw new IllegalArgumentException("unit does not have vision range");
    }

    double getAttackRange(LivingUnit unit) {
      if (unit instanceof Wizard) {
        return ((Wizard) unit).getCastRange();
      }
      if (unit instanceof Minion) {
        return ((Minion) unit).getType() == MinionType.FETISH_BLOWDART
            ? game.getFetishBlowdartAttackRange()
            : game.getOrcWoodcutterAttackRange();
      }
      if (unit instanceof Building) {
        return ((Building) unit).getAttackRange();
      }
      throw new IllegalArgumentException("unit does not have vision range");
    }

    private Point getShortWalkingTarget(Point walkingTarget) {
      List<Square> path = field.findPath(Square.containing(self), Square.containing(walkingTarget));

      if (path == null) {
        return walkingTarget;
      }

      int shortcut = 0;
      while (shortcut + 1 < path.size()
          && field
              .getSquaresOnLine(path.get(0), path.get(shortcut + 1))
              .noneMatch(s -> field.walls.contains(s) || field.movingUnits.containsKey(s))) {
        ++shortcut;
      }
      if (shortcut == 0 && 1 < path.size()) {
        shortcut = 1;
      }

      if (debug != null && DEBUG_DRAW_PATH) {
        for (int i = 1; i < path.size(); ++i) {
          debug.drawLine(
              path.get(i - 1).getCenterX(),
              path.get(i - 1).getCenterY(),
              path.get(i).getCenterX(),
              path.get(i).getCenterY(),
              DEBUG_FIND_PATH ? Color.black : Color.lightGray);
        }
        debug.drawBeforeScene();
      }

      return path.get(shortcut).getCenter();
    }

    private void updateObservers(Wizard self, World world, Game game) {
      this.self = self;
      this.world = world;
      this.game = game;
      for (WorldObserver observer : observers) {
        observer.update(self, world, game);
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

    Minion[] getFetishes() {
      return Arrays.stream(world.getMinions())
          .filter(m -> m.getType() == MinionType.FETISH_BLOWDART)
          .toArray(size -> new Minion[size]);
    }

    Minion[] getWoodcutters() {
      return Arrays.stream(world.getMinions())
          .filter(m -> m.getType() == MinionType.ORC_WOODCUTTER)
          .toArray(size -> new Minion[size]);
    }

    boolean isMe(Unit unit) {
      return unit.getId() == self.getId();
    }

    double getAttackDamage(Wizard wizard) {
      boolean empowered =
          Arrays.stream(wizard.getStatuses()).anyMatch(s -> s.getType() == StatusType.EMPOWERED);
      double multiplier = empowered ? game.getEmpoweredDamageFactor() : 1;
      return multiplier * game.getMagicMissileDirectDamage();
    }

    double getAttackDamage(Building building) {
      return building.getType() == BuildingType.GUARDIAN_TOWER
          ? game.getGuardianTowerDamage()
          : game.getFactionBaseDamage();
    }

    double getAttackDamage(Minion minion) {
      return minion.getType() == MinionType.FETISH_BLOWDART
          ? game.getDartDirectDamage()
          : game.getOrcWoodcutterDamage();
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

  private static class Stuck extends WorldObserver {

    private static final int STUCK_DETECTION_TICKS = 2;
    private static final double SPEED_EPS = 0.01;
    private static final double MIN_WALKING_SPEED = 1;

    private Random random;

    private State state;
    private boolean lastMoveSucceeded;

    private List<Point> positionHistory = new LinkedList<>();
    private Point requestedSpeed;
    private Point unstuckDirection;
    private int stuckTicks;

    public Stuck(Brain brain, Visualizer debug, Random random) {
      super(brain, debug);
      this.random = random;
    }

    @Override
    protected void update() {
      positionHistory.add(0, new Point(self));
      if (positionHistory.size() > STUCK_DETECTION_TICKS) {
        positionHistory.remove(STUCK_DETECTION_TICKS);
      }
    }

    public void unstuck(Move move) {
      updateStuckState();

      if (state == State.STUCK) {
        ++stuckTicks;
        if (stuckTicks == 1) {
          unstuckDirection = Point.fromPolar(100, requestedSpeed.getAngle() + Math.PI / 2);
        } else if (stuckTicks == 2) {
          unstuckDirection = unstuckDirection.negate();
        } else if (!lastMoveSucceeded) {
          unstuckDirection = Point.fromPolar(100, (random.nextDouble() * 2 - 1) * Math.PI);
        }
        brain.walker.goTo(unstuckDirection.add(new Point(self)), move);
      }

      Point strafeSpeed = Point.fromPolar(move.getStrafeSpeed(), self.getAngle() + Math.PI / 2);
      requestedSpeed = Point.fromPolar(move.getSpeed(), self.getAngle()).add(strafeSpeed);
    }

    private void updateStuckState() {
      if (positionHistory.size() < STUCK_DETECTION_TICKS) {
        state = State.STANDING;
        return;
      }
      Point position = positionHistory.get(0);
      Point lastPosition = positionHistory.get(1);
      Point speed = position.sub(lastPosition);
      lastMoveSucceeded = speed.getDistanceTo(requestedSpeed) < SPEED_EPS;
      if (!lastMoveSucceeded) {
        state = State.STUCK;
      } else {
        state = speed.length() > MIN_WALKING_SPEED ? State.WALKING : State.STANDING;
        stuckTicks = 0;
      }
    }

    private enum State {
      STANDING,
      WALKING,
      STUCK,
    }
  }

  private static class BonusFinder extends WorldObserver {

    private static final int BONUS_ANTICIPATION_TICKS = 250;

    private int nextBonusTick;
    private Point bonus;

    public BonusFinder(Brain brain, Visualizer debug, Game game) {
      super(brain, debug);
      nextBonusTick = game.getBonusAppearanceIntervalTicks() + 1;
    }

    @Override
    public void update() {
      int tick = world.getTickIndex();
      if (bonus == null
          && tick >= nextBonusTick - BONUS_ANTICIPATION_TICKS
          && nextBonusTick < game.getTickCount()) {
        bonus =
            new Point(
                game.getMapSize() * 0.7 - game.getBonusRadius() - self.getRadius(),
                game.getMapSize() * 0.7 - game.getBonusRadius() - self.getRadius());
        if (debug != null) {
          System.out.println("anticipating a bonus");
        }
      }

      if (tick >= nextBonusTick) {
        nextBonusTick += game.getBonusAppearanceIntervalTicks();
        bonus = new Point(game.getMapSize() * 0.7, game.getMapSize() * 0.7);
      }

      if (bonus != null && tick < nextBonusTick - BONUS_ANTICIPATION_TICKS && brain.canSee(bonus)) {
        boolean bonusExists =
            Arrays.stream(world.getBonuses()).anyMatch(b -> brain.canSee(new Point(b)));
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

    private static final int FIND_PATH_MAX_STEPS = 200;
    private static final int WEAK_TREE_PRIORITY = -100;
    private static final int MOVING_UNIT_PRIORITY = -1000;
    private static final int FORWARD_SQUARE_PRIORITY = 1000;

    private final int worldW;
    private final int worldH;
    private final Point[] waypoints;
    private final List<Building> enemyBuildings;

    private Set<Square> walls = new HashSet<>();
    private Map<Square, Tree> weakTrees = new HashMap<>();
    private Map<Square, LivingUnit> movingUnits = new HashMap<>();
    private Map<Square, Integer> priority = new HashMap<>();

    private double[][] damageMap;
    private double[][] supportMap;
    private double[][] xpMap;

    public Field(Brain brain, Visualizer debug, Wizard self, World world, Game game) {
      super(brain, debug);

      Square maxSquare = Square.containing(world.getWidth(), world.getHeight());
      worldW = maxSquare.getW() + 1;
      worldH = maxSquare.getH() + 1;

      double mapSize = game.getMapSize();

      waypoints =
          new Point[] {
            new Point(mapSize * 0.05, mapSize * 0.95),
            new Point(mapSize * 0.25, mapSize * 0.82),
            new Point(mapSize * 0.35, mapSize * 0.70),
            new Point(mapSize * 0.60, mapSize * 0.60),
            new Point(mapSize * 0.65, mapSize * 0.40),
            new Point(mapSize * 0.75, mapSize * 0.25),
          };

      enemyBuildings =
          Arrays.stream(world.getBuildings())
              .filter(brain::isAlly)
              .map(
                  b ->
                      new Building(
                          0,
                          world.getWidth() - b.getX(),
                          world.getHeight() - b.getY(),
                          b.getSpeedX(),
                          b.getSpeedY(),
                          b.getAngle(),
                          brain.ENEMY_FRACTION,
                          b.getRadius(),
                          b.getLife(),
                          b.getMaxLife(),
                          b.getStatuses(),
                          b.getType(),
                          b.getVisionRange(),
                          b.getAttackRange(),
                          b.getDamage(),
                          b.getCooldownTicks(),
                          b.getRemainingActionCooldownTicks()))
              .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public void update() {
      updateWalls();
      updateWeakTrees();
      updateMovingUnits();
      updateMaps();
      updatePriorities();

      if (debug != null) {
        double[][] impactMap = new double[worldW][worldH];
        for (int w = 0; w < worldW; ++w) {
          for (int h = 0; h < worldH; ++h) {
            impactMap[w][h] = supportMap[w][h] - 2 * damageMap[w][h] + 0.25 * xpMap[w][h];
          }
        }
        drawMap(impactMap, 5);
        drawMiniMap(impactMap, 4, 3, 141, 579);

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

    private void drawMiniMap(double[][] map, int zoom, double squareSize, double x0, double y0) {
      double min = -100;
      double max = 100;
      for (int w = 0; w < worldW; ++w) {
        for (int h = 0; h < worldH; ++h) {
          min = Math.min(min, map[w][h]);
          max = Math.max(max, map[w][h]);
        }
      }
      double valueRange = Math.max(Math.abs(min), Math.abs(max));

      for (int w = zoom - 1; w < worldW; w += zoom) {
        for (int h = zoom - 1; h < worldH; h += zoom) {
          double avg = 0;
          for (int dw = 1 - zoom; dw <= 0; ++dw) {
            for (int dh = 1 - zoom; dh <= 0; ++dh) {
              avg += map[w + dw][h + dh];
            }
          }
          avg /= zoom * zoom;

          double alpha = avg / valueRange;
          debug.fillRect(
              x0 + (w / zoom) * squareSize,
              y0 + (h / zoom) * squareSize,
              x0 + (w / zoom + 1) * squareSize,
              y0 + (h / zoom + 1) * squareSize,
              Color.getHSBColor(alpha < 0 ? 0f : 0.3f, (float) Math.abs(alpha), 1f));
        }
      }
      debug.drawAbsolute();
    }

    private void drawMap(double[][] map, int zoom) {
      double min = -100;
      double max = 100;
      for (int w = 0; w < worldW; ++w) {
        for (int h = 0; h < worldH; ++h) {
          min = Math.min(min, map[w][h]);
          max = Math.max(max, map[w][h]);
        }
      }
      double valueRange = Math.max(Math.abs(min), Math.abs(max));

      for (int w = zoom - 1; w < worldW; w += zoom) {
        for (int h = zoom - 1; h < worldH; h += zoom) {
          double avg = 0;
          for (int dw = 1 - zoom; dw <= 0; ++dw) {
            for (int dh = 1 - zoom; dh <= 0; ++dh) {
              avg += map[w + dw][h + dh];
            }
          }
          avg /= zoom * zoom;

          if (avg == 0) {
            continue;
          }

          double alpha = avg / valueRange;
          Square topLeft = new Square(w - zoom + 1, h - zoom + 1);
          Square bottomRight = new Square(w, h);
          debug.fillRect(
              topLeft.getLeftX(),
              topLeft.getTopY(),
              bottomRight.getRightX(),
              bottomRight.getBottomY(),
              Color.getHSBColor(alpha < 0 ? 0f : 0.3f, (float) Math.abs(alpha), 1f));
        }
      }
      debug.drawBeforeScene();
    }

    private void updateMaps() {
      damageMap = new double[worldW][worldH];
      supportMap = new double[worldW][worldH];
      xpMap = new double[worldW][worldH];

      Arrays.stream(world.getBuildings())
          .forEach(
              b -> {
                double damage =
                    discountDamage(brain.getAttackDamage(b), b.getRemainingActionCooldownTicks());

                if (brain.isEnemy(b)) {
                  brain
                      .field
                      .getSquares(b, b.getAttackRange() + self.getRadius())
                      .forEach(s -> damageMap[s.getW()][s.getH()] += damage);

                  double xp = b.getLife() * game.getBuildingEliminationScoreFactor();
                  brain
                      .field
                      .getSquares(b, self.getCastRange() / 2, self.getCastRange())
                      .forEach(s -> xpMap[s.getW()][s.getH()] += xp);
                }

                if (brain.isAlly(b)) {
                  brain
                      .field
                      .getSquares(b, b.getAttackRange() + self.getRadius())
                      .forEach(s -> supportMap[s.getW()][s.getH()] += damage);
                }
              });

      Arrays.stream(world.getWizards())
          .forEach(
              w -> {
                int[] cooldown = w.getRemainingCooldownTicksByAction();
                double missileDamage =
                    discountDamage(
                        game.getMagicMissileDirectDamage(),
                        Math.max(
                            w.getRemainingActionCooldownTicks(),
                            cooldown[ActionType.MAGIC_MISSILE.ordinal()]));
                double staffDamage =
                    discountDamage(
                        game.getStaffDamage(),
                        Math.max(
                            w.getRemainingActionCooldownTicks(),
                            cooldown[ActionType.STAFF.ordinal()]));

                if (brain.isEnemy(w)) {
                  brain
                      .field
                      .getSquares(w, w.getCastRange() + self.getRadius())
                      .forEach(s -> damageMap[s.getW()][s.getH()] += missileDamage);
                  brain
                      .field
                      .getSquares(w, game.getStaffRange() + self.getRadius())
                      .forEach(s -> damageMap[s.getW()][s.getH()] += staffDamage);

                  double xp = w.getLife() * game.getWizardEliminationScoreFactor();
                  brain
                      .field
                      .getSquares(w, self.getCastRange() / 2, self.getCastRange())
                      .forEach(s -> xpMap[s.getW()][s.getH()] += xp);
                }

                if (brain.isAlly(w) && !w.isMe()) {
                  brain
                      .field
                      .getSquares(w, w.getCastRange() + self.getRadius())
                      .forEach(s -> supportMap[s.getW()][s.getH()] += missileDamage);
                  brain
                      .field
                      .getSquares(w, game.getStaffRange() + self.getRadius())
                      .forEach(s -> supportMap[s.getW()][s.getH()] += staffDamage);
                }
              });

      Arrays.stream(world.getMinions())
          .forEach(
              m -> {
                double damage =
                    discountDamage(brain.getAttackDamage(m), m.getRemainingActionCooldownTicks());

                if (brain.isEnemy(m)) {
                  brain
                      .field
                      .getSquares(m, brain.getAttackRange(m) + self.getRadius())
                      .forEach(s -> damageMap[s.getW()][s.getH()] += damage);

                  double xp = m.getLife() * game.getMinionEliminationScoreFactor();
                  brain
                      .field
                      .getSquares(m, self.getCastRange() / 2, self.getCastRange())
                      .forEach(s -> xpMap[s.getW()][s.getH()] += xp);
                }

                if (brain.isAlly(m)) {
                  brain
                      .field
                      .getSquares(m, brain.getAttackRange(m) + self.getRadius())
                      .forEach(s -> supportMap[s.getW()][s.getH()] += damage);
                }
              });

      Stream.of(brain.bonusFinder.findBonus())
          .filter(Objects::nonNull)
          .forEach(
              b -> {
                Square s = Square.containing(b);
                xpMap[s.getW()][s.getH()] += game.getBonusScoreAmount();
              });
    }

    private double discountDamage(double maxDamage, int remainingCooldownTicks) {
      return remainingCooldownTicks <= LOOKAHEAD_TICKS ? maxDamage : 0;
    }

    private void updatePriorities() {
      priority.clear();
      weakTrees.keySet().forEach(square -> priority.put(square, WEAK_TREE_PRIORITY));
      Arrays.stream(getNeighbors(Square.containing(self)))
          .forEach(
              square -> {
                double angle = self.getAngleTo(square.getCenterX(), square.getCenterY());
                if (Math.abs(angle) < Math.PI / 2) {
                  priority.put(square, FORWARD_SQUARE_PRIORITY);
                }
              });
      movingUnits.keySet().stream().forEach(square -> priority.put(square, MOVING_UNIT_PRIORITY));
    }

    private void updateWalls() {
      walls.clear();
      Stream.of(
              Arrays.stream(world.getTrees()),
              Arrays.stream(world.getBuildings()),
              Arrays.stream(world.getMinions()).filter(m -> m.getFaction() == Faction.NEUTRAL))
          .flatMap(Function.identity())
          .forEach(
              unit -> {
                if (unit.getLife() > game.getMagicMissileDirectDamage() || brain.isAlly(unit)) {
                  walls.addAll(getSquares(unit, unit.getRadius() + self.getRadius()));
                }
              });

      Square selfSquare = Square.containing(self);
      walls.remove(selfSquare);
      walls.removeAll(Arrays.asList(getNeighbors(selfSquare)));

      if (debug != null && DEBUG_DRAW_WALLS) {
        for (Square square : walls) {
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

    private void updateWeakTrees() {
      weakTrees.clear();
      for (Tree tree : world.getTrees()) {
        if (tree.getLife() <= game.getMagicMissileDirectDamage()) {
          for (Square square : getSquares(tree, tree.getRadius() + self.getRadius())) {
            weakTrees.put(square, tree);
          }
        }
      }

      if (debug != null && DEBUG_DRAW_WEAK_TREES) {
        for (Square square : weakTrees.keySet()) {
          debug.drawRect(
              square.getLeftX(),
              square.getTopY(),
              square.getRightX(),
              square.getBottomY(),
              Color.pink);
          debug.drawBeforeScene();
        }
      }
    }

    private void updateMovingUnits() {
      movingUnits.clear();
      Stream.of(Arrays.stream(world.getWizards()), Arrays.stream(world.getMinions()))
          .flatMap(Function.identity())
          .filter(
              unit ->
                  !brain.isMe(unit)
                      && (brain.isAlly(unit)
                          || unit.getLife() > game.getMagicMissileDirectDamage()))
          .forEach(
              unit ->
                  getSquares(unit, unit.getRadius() + self.getRadius())
                      .stream()
                      .forEach(s -> movingUnits.put(s, unit)));

      if (debug != null && DEBUG_DRAW_MOVING_UNITS) {
        movingUnits
            .keySet()
            .stream()
            .forEach(
                square -> {
                  debug.drawRect(
                      square.getLeftX(),
                      square.getTopY(),
                      square.getRightX(),
                      square.getBottomY(),
                      Color.orange);
                  debug.drawBeforeScene();
                });
      }
    }

    private List<Square> getSquares(LivingUnit unit, double minR, double maxR) {
      Set<Square> squaresToExclude = new HashSet<>(getSquares(unit, minR));
      return getSquares(unit, maxR)
          .stream()
          .filter(s -> !squaresToExclude.contains(s))
          .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<Square> getSquares(LivingUnit unit, double r) {
      List<Square> result = new ArrayList<>();
      Square topLeft = Square.containing(unit.getX() - r, unit.getY() - r);
      Square bottomRight = Square.containing(unit.getX() + r, unit.getY() + r);
      for (int p = Math.max(0, topLeft.getW()); p <= bottomRight.getW() && p < worldW; ++p) {
        for (int q = Math.max(0, topLeft.getH()); q <= bottomRight.getH() && q < worldH; ++q) {
          Square square = new Square(p, q);
          if (distanceLessThan(unit, square.getCenter(), r)) {
            result.add(square);
          }
        }
      }
      return result;
    }

    public Stream<Point> getPointsOnLine(Point a, Point b) {
      Point ab = b.sub(a);
      int n = (int) Math.ceil(ab.length() / SQUARE_CRUDENESS) + 1;
      return IntStream.rangeClosed(0, n).mapToObj(i -> a.add(ab.mul((double) i / n)));
    }

    public Stream<Square> getSquaresOnLine(Point a, Point b) {
      final Square[] prev = {null};
      return getPointsOnLine(a, b)
          .map(p -> Square.containing(p))
          .filter(
              square -> {
                if (square.equals(prev[0])) {
                  return false;
                }
                prev[0] = square;
                return true;
              });
    }

    public Stream<Square> getSquaresOnLine(Square a, Square b) {
      return getSquaresOnLine(a.getCenter(), b.getCenter());
    }

    public Point getNextWaypoint() {
      int lastWaypointIndex = waypoints.length - 1;
      Point lastWaypoint = waypoints[lastWaypointIndex];

      for (int waypointIndex = 0; waypointIndex < lastWaypointIndex; ++waypointIndex) {
        Point waypoint = waypoints[waypointIndex];

        if (distanceLessThan(waypoint, self, self.getRadius() * 4)) {
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

        if (distanceLessThan(waypoint, self, self.getRadius() * 3)) {
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

      for (int i = 0; !queue.isEmpty() && i < FIND_PATH_MAX_STEPS; ++i) {
        Square point = queue.first();
        queue.remove(point);
        done.add(point);

        if (point.equals(end)) {
          break;
        }

        int distanceToPoint = distance.get(point);
        for (Square neighbor : getNeighbors(point)) {
          if (walls.contains(neighbor) || done.contains(neighbor)) {
            continue;
          }

          Integer oldDistance = distance.get(neighbor);
          int newDistance = distanceToPoint + squaredDistance(point, neighbor);
          if (oldDistance == null || newDistance < oldDistance) {
            int newGuess =
                newDistance + squaredDistance(neighbor, end) - priority.getOrDefault(neighbor, 0);
            cameFrom.put(neighbor, point);
            distance.put(neighbor, newDistance);
            distanceGuess.put(neighbor, newGuess);
            queue.remove(neighbor);
            queue.add(neighbor);
          }
        }
      }

      if (!done.contains(end)) {
        Square bestEnd = null;
        double bestSquaredDistance = 0;
        for (Square option : done) {
          double optionSquaredDistance = squaredDistance(option, end);
          if (bestEnd == null || optionSquaredDistance < bestSquaredDistance) {
            bestEnd = option;
            bestSquaredDistance = optionSquaredDistance;
          }
        }
        end = bestEnd;
      }

      List<Square> path = new ArrayList<>();
      for (Square point = end; point != null; point = cameFrom.get(point)) {
        path.add(point);
      }
      Collections.reverse(path);

      if (debug != null && DEBUG_FIND_PATH) {
        for (Map.Entry<Square, Square> entry : cameFrom.entrySet()) {
          if (entry.getValue() == null) {
            continue;
          }
          if (!done.contains(entry.getKey())) {
            debug.fillCircle(
                entry.getKey().getCenterX(), entry.getKey().getCenterY(), 3, Color.lightGray);
          }
          debug.drawLine(
              entry.getKey().getCenterX(),
              entry.getKey().getCenterY(),
              entry.getValue().getCenterX(),
              entry.getValue().getCenterY(),
              Color.lightGray);
        }
        debug.drawBeforeScene();
      }

      return path;
    }

    private Square[] getNeighbors(Square square) {
      int p = square.getW();
      int q = square.getH();
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
      return (a.getW() - b.getW()) * (a.getW() - b.getW())
          + (a.getH() - b.getH()) * (a.getH() - b.getH());
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

    public Point getFactionBaseWaypoint() {
      return new Point(world.getWidth() * 0.15, world.getHeight() * 0.85);
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
      Point v = direction.mul(k);

      move.setSpeed(aSign * v.project(a));
      move.setStrafeSpeed(bSign * v.project(b));
    }

    public void turnTo(Point point, Move move) {
      double angle = self.getAngleTo(point.getX(), point.getY());
      move.setTurn(angle);
    }

    public void turnTo(Unit unit, Move move) {
      turnTo(new Point(unit), move);
    }
  }

  private static class Skiller extends WorldObserver {

    private SkillType[] MISSILE = {
      SkillType.RANGE_BONUS_PASSIVE_1,
      SkillType.RANGE_BONUS_AURA_1,
      SkillType.RANGE_BONUS_PASSIVE_2,
      SkillType.RANGE_BONUS_AURA_2,
      SkillType.ADVANCED_MAGIC_MISSILE,
    };

    private SkillType[] FROST_BOLD = {
      SkillType.MAGICAL_DAMAGE_BONUS_AURA_1,
      SkillType.MAGICAL_DAMAGE_BONUS_PASSIVE_2,
      SkillType.MAGICAL_DAMAGE_BONUS_AURA_2,
      SkillType.FROST_BOLT,
    };

    private SkillType[] FIREBALL = {
      SkillType.STAFF_DAMAGE_BONUS_PASSIVE_1,
      SkillType.STAFF_DAMAGE_BONUS_AURA_1,
      SkillType.STAFF_DAMAGE_BONUS_PASSIVE_2,
      SkillType.STAFF_DAMAGE_BONUS_AURA_2,
      SkillType.FIREBALL,
    };

    private SkillType[] HASTE = {
      SkillType.MOVEMENT_BONUS_FACTOR_PASSIVE_1,
      SkillType.MOVEMENT_BONUS_FACTOR_AURA_1,
      SkillType.MOVEMENT_BONUS_FACTOR_PASSIVE_2,
      SkillType.MOVEMENT_BONUS_FACTOR_AURA_2,
      SkillType.HASTE,
    };

    private SkillType[] SHIELD = {
      SkillType.MAGICAL_DAMAGE_ABSORPTION_PASSIVE_1,
      SkillType.MAGICAL_DAMAGE_ABSORPTION_AURA_1,
      SkillType.MAGICAL_DAMAGE_ABSORPTION_PASSIVE_2,
      SkillType.MAGICAL_DAMAGE_ABSORPTION_AURA_2,
      SkillType.SHIELD,
    };

    private HashSet<SkillType> mySkills = new HashSet<>();

    public Skiller(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    @Override
    protected void update() {
      mySkills.clear();
      Arrays.stream(self.getSkills()).forEach(s -> mySkills.add(s));
    }

    public SkillType getSkillToLearn() {
      return Stream.of(SHIELD, HASTE, FROST_BOLD, FIREBALL, MISSILE)
          .flatMap(Arrays::stream)
          .filter(s -> !mySkills.contains(s))
          .findFirst()
          .orElse(null);
    }

    public void maybeLearnSkill(Move move) {
      SkillType nextSkill = getSkillToLearn();
      move.setSkillToLearn(nextSkill);

      if (debug != null) {
        debug.showText(10, 10, "Next skill: " + nextSkill, Color.black);
        debug.drawAbsolute();
      }
    }

    public boolean hasSkill(SkillType skill) {
      return mySkills.contains(skill);
    }
  }

  private static class Shooter extends WorldObserver {

    private Point staff1;
    private Point staff2;
    private Point staff3;

    public Shooter(Brain brain, Visualizer debug) {
      super(brain, debug);
    }

    @Override
    protected void update() {
      Point selfPoint = new Point(self);
      staff1 =
          Point.fromPolar(game.getStaffRange(), self.getAngle() + game.getStaffSector() / 2)
              .add(selfPoint);
      staff2 = Point.fromPolar(game.getStaffRange(), self.getAngle()).add(selfPoint);
      staff3 =
          Point.fromPolar(game.getStaffRange(), self.getAngle() - game.getStaffSector() / 2)
              .add(selfPoint);

      if (debug != null) {
        brain.drawWaves(
            self.getX(),
            self.getY(),
            self.getCastRange(),
            self.getAngle() - game.getStaffSector() / 2,
            game.getStaffSector(),
            Color.pink);
        debug.drawBeforeScene();

        brain.drawWaves(
            self.getX(),
            self.getY(),
            game.getStaffRange(),
            self.getAngle() - game.getStaffSector() / 2,
            game.getStaffSector(),
            Color.red);
        debug.drawAfterScene();
      }
    }

    public boolean missileCanReach(LivingUnit target) {
      return Math.abs(self.getAngleTo(target)) < game.getStaffSector() / 2
          && distanceLessThan(self, target, self.getCastRange());
    }

    public boolean staffCanReach(LivingUnit target) {
      return Math.abs(self.getAngleTo(target)) < game.getStaffSector() / 2
          && (distanceLessThan(staff1, target, target.getRadius())
              || distanceLessThan(staff2, target, target.getRadius())
              || distanceLessThan(staff3, target, target.getRadius()));
    }

    public LivingUnit getTarget(double range) {
      LivingUnit buildingTarget = getTargetHomo(world.getBuildings(), range);
      LivingUnit wizardTarget = getTargetHomo(world.getWizards(), range);
      LivingUnit closestWoodcutter = getClosestWoodcutter();
      if (closestWoodcutter != null
          && distanceLessThan(
              self,
              closestWoodcutter,
              SAFETY_EPS
                  + self.getRadius()
                  + closestWoodcutter.getRadius()
                  + game.getOrcWoodcutterAttackRange())) {
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
      if (wizardTarget != null && wizardTarget.getLife() < brain.getAttackDamage(self)) {
        return wizardTarget;
      }
      return buildingTarget != null ? buildingTarget : wizardTarget;
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
        if (!distanceLessThan(self, target, range)) {
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
      return Math.hypot(this.x - x, this.y - y);
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

    private final int w;
    private final int h;

    public Square(int w, int h) {
      this.w = w;
      this.h = h;
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
      return "Square{" + w + ", " + h + '}';
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Square square = (Square) o;
      if (w != square.w) return false;
      return h == square.h;
    }

    @Override
    public int hashCode() {
      return w * 100000 + h;
    }

    public int getW() {
      return w;
    }

    public int getH() {
      return h;
    }

    public double getCenterX() {
      return (w + 0.5) * SQUARE_CRUDENESS;
    }

    public double getCenterY() {
      return (h + 0.5) * SQUARE_CRUDENESS;
    }

    public Point getCenter() {
      return new Point(getCenterX(), getCenterY());
    }

    public double getLeftX() {
      return w * SQUARE_CRUDENESS;
    }

    public double getRightX() {
      return (w + 1) * SQUARE_CRUDENESS;
    }

    public double getTopY() {
      return h * SQUARE_CRUDENESS;
    }

    public double getBottomY() {
      return (h + 1) * SQUARE_CRUDENESS;
    }
  }
}
