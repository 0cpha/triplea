package games.strategy.triplea.ai.proAI;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GameStep;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Resource;
import games.strategy.engine.data.TechnologyFrontier;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.framework.GameDataUtils;
import games.strategy.net.GUID;
import games.strategy.triplea.Constants;
import games.strategy.triplea.ai.AbstractAI;
import games.strategy.triplea.ai.proAI.data.ProBattleResult;
import games.strategy.triplea.ai.proAI.data.ProPurchaseTerritory;
import games.strategy.triplea.ai.proAI.data.ProTerritory;
import games.strategy.triplea.ai.proAI.logging.ProLogUI;
import games.strategy.triplea.ai.proAI.logging.ProLogger;
import games.strategy.triplea.ai.proAI.simulate.ProDummyDelegateBridge;
import games.strategy.triplea.ai.proAI.simulate.ProSimulateTurnUtils;
import games.strategy.triplea.ai.proAI.util.ProBattleUtils;
import games.strategy.triplea.ai.proAI.util.ProMatches;
import games.strategy.triplea.ai.proAI.util.ProMoveOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProMoveUtils;
import games.strategy.triplea.ai.proAI.util.ProPurchaseUtils;
import games.strategy.triplea.ai.proAI.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.proAI.util.ProTransportUtils;
import games.strategy.triplea.ai.proAI.util.ProUtils;
import games.strategy.triplea.ai.strongAI.SUtils;
import games.strategy.triplea.attatchments.PoliticalActionAttachment;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.delegate.BattleCalculator;
import games.strategy.triplea.delegate.BattleDelegate;
import games.strategy.triplea.delegate.DelegateFinder;
import games.strategy.triplea.delegate.DiceRoll;
import games.strategy.triplea.delegate.IBattle;
import games.strategy.triplea.delegate.IBattle.BattleType;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.PoliticsDelegate;
import games.strategy.triplea.delegate.TechAdvance;
import games.strategy.triplea.delegate.dataObjects.CasualtyDetails;
import games.strategy.triplea.delegate.dataObjects.CasualtyList;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.delegate.remote.ITechDelegate;
import games.strategy.triplea.ui.TripleAFrame;
import games.strategy.util.Match;
import games.strategy.util.Tuple;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pro AI.
 */
public class ProAI extends AbstractAI {

  private final static Logger s_logger = Logger.getLogger(ProAI.class.getName());

  // Utilities
  private final ProTransportUtils transportUtils;
  private final ProMoveOptionsUtils attackOptionsUtils;
  private final ProMoveUtils moveUtils;
  private final ProTerritoryValueUtils territoryValueUtils;
  private final ProSimulateTurnUtils simulateTurnUtils;

  // Phases
  private final ProCombatMoveAI combatMoveAI;
  private final ProNonCombatMoveAI nonCombatMoveAI;
  private final ProPurchaseAI purchaseAI;
  private final ProRetreatAI retreatAI;
  private final ProScrambleAI scrambleAI;
  private final ProPoliticsAI politicsAI;

  // Data
  private Map<Territory, ProTerritory> storedCombatMoveMap;
  private Map<Territory, ProTerritory> storedFactoryMoveMap;
  private Map<Territory, ProPurchaseTerritory> storedPurchaseTerritories;
  private List<PoliticalActionAttachment> storedPoliticalActions;
  private List<Territory> storedStrafingTerritories;

  public ProAI(final String name, final String type) {
    super(name, type);
    transportUtils = new ProTransportUtils();
    attackOptionsUtils = new ProMoveOptionsUtils(transportUtils);
    moveUtils = new ProMoveUtils();
    territoryValueUtils = new ProTerritoryValueUtils();
    simulateTurnUtils = new ProSimulateTurnUtils();
    combatMoveAI = new ProCombatMoveAI(this, transportUtils, attackOptionsUtils, moveUtils, territoryValueUtils);
    nonCombatMoveAI = new ProNonCombatMoveAI(transportUtils, attackOptionsUtils, moveUtils, territoryValueUtils);
    purchaseAI = new ProPurchaseAI(transportUtils, attackOptionsUtils, territoryValueUtils);
    retreatAI = new ProRetreatAI();
    scrambleAI = new ProScrambleAI(attackOptionsUtils);
    politicsAI = new ProPoliticsAI(attackOptionsUtils);
    storedCombatMoveMap = null;
    storedPurchaseTerritories = null;
    storedStrafingTerritories = new ArrayList<Territory>();
  }

  public static void Initialize(final TripleAFrame frame) {
    ProLogUI.initialize(frame); // Must be done first
    ProLogger.info("Initialized Hard AI");
  }

  public static void ShowSettingsWindow() {
    ProLogger.info("Showing Hard AI settings window");
    ProLogUI.showSettingsWindow();
  }

  public static Logger getLogger() {
    return s_logger;
  }

  public static void gameOverClearCache() {
    // Is static, set to null so that we don't keep the data around after a game is exited
    ProBattleUtils.setData(null);
    ProLogUI.clearCachedInstances();
  }

  @Override
  public void stopGame() {
    super.stopGame(); // absolutely MUST call super.stopGame() first
    ProBattleUtils.cancel(); // cancel any current calcing
  }

  public void setStoredStrafingTerritories(final List<Territory> strafingTerritories) {
    storedStrafingTerritories = strafingTerritories;
  }

  private void initializeData() {
    ProData.setData(getGameData());
    ProData.setProAI(this);
  }

  @Override
  protected void move(final boolean nonCombat, final IMoveDelegate moveDel, final GameData data, final PlayerID player) {
    final long start = System.currentTimeMillis();
    BattleCalculator.clearOOLCache();
    ProLogUI.notifyStartOfRound(data.getSequence().getRound(), player.getName());
    initializeData();
    ProBattleUtils.setData(data);
    if (nonCombat) {
      nonCombatMoveAI.doNonCombatMove(storedFactoryMoveMap, storedPurchaseTerritories, moveDel, data, player, false);
      storedFactoryMoveMap = null;
    } else {
      if (storedCombatMoveMap == null) {
        combatMoveAI.doCombatMove(moveDel, data, player, false);
      } else {
        combatMoveAI.doMove(storedCombatMoveMap, moveDel, data, player, false);
        storedCombatMoveMap = null;
      }
    }
    ProLogger.info(player.getName() + " time for nonCombat=" + nonCombat + " time="
        + (System.currentTimeMillis() - start));
  }

  @Override
  protected void purchase(final boolean purchaseForBid, int PUsToSpend, final IPurchaseDelegate purchaseDelegate,
      final GameData data, final PlayerID player) {
    final long start = System.currentTimeMillis();
    BattleCalculator.clearOOLCache();
    ProLogUI.notifyStartOfRound(data.getSequence().getRound(), player.getName());
    initializeData();
    if (PUsToSpend <= 0) {
      return;
    }
    if (purchaseForBid) {
      purchaseAI.bid(PUsToSpend, purchaseDelegate, data, player);
    } else {

      // Repair factories
      PUsToSpend = purchaseAI.repair(PUsToSpend, purchaseDelegate, data, player);

      // Check if any place territories exist
      final Map<Territory, ProPurchaseTerritory> purchaseTerritories = ProPurchaseUtils.findPurchaseTerritories(player);
      final List<Territory> possibleFactoryTerritories =
          Match.getMatches(data.getMap().getTerritories(),
              ProMatches.territoryHasNoInfraFactoryAndIsNotConqueredOwnedLand(player, data));
      if (purchaseTerritories.isEmpty() && possibleFactoryTerritories.isEmpty()) {
        ProLogger.info("No possible place or factory territories owned so exiting purchase logic");
        return;
      }
      ProLogger.info("Starting simulation for purchase phase");

      // Setup data copy and delegates
      GameData dataCopy;
      try {
        data.acquireReadLock();
        dataCopy = GameDataUtils.cloneGameData(data, true);
      } catch (final Throwable t) {
        ProLogger.log(Level.WARNING, "Error trying to clone game data for simulating phases", t);
        return;
      } finally {
        data.releaseReadLock();
      }
      ProData.setData(dataCopy);
      ProBattleUtils.setData(dataCopy);
      final PlayerID playerCopy = dataCopy.getPlayerList().getPlayerID(player.getName());
      final IMoveDelegate moveDel = DelegateFinder.moveDelegate(dataCopy);
      final IDelegateBridge bridge = new ProDummyDelegateBridge(this, playerCopy, dataCopy);
      moveDel.setDelegateBridgeAndPlayer(bridge);

      // Determine turn sequence
      final List<GameStep> gameSteps = new ArrayList<GameStep>();
      for (final GameStep gameStep : dataCopy.getSequence()) {
        gameSteps.add(gameStep);
      }

      // Simulate the next phases until place/end of turn is reached then use simulated data for purchase
      final int nextStepIndex = dataCopy.getSequence().getStepIndex() + 1;
      final Map<Unit, Territory> unitTerritoryMap = ProUtils.createUnitTerritoryMap(playerCopy);
      for (int i = nextStepIndex; i < gameSteps.size(); i++) {
        final GameStep step = gameSteps.get(i);
        if (!playerCopy.equals(step.getPlayerID())) {
          continue;
        }
        dataCopy.getSequence().setRoundAndStep(dataCopy.getSequence().getRound(), step.getDisplayName(),
            step.getPlayerID());
        final String stepName = step.getName();
        ProLogger.info("Simulating phase: " + stepName);
        if (stepName.endsWith("NonCombatMove")) {
          final Map<Territory, ProTerritory> factoryMoveMap =
              nonCombatMoveAI.doNonCombatMove(null, null, moveDel, dataCopy, playerCopy, true);
          if (storedFactoryMoveMap == null) {
            storedFactoryMoveMap =
                simulateTurnUtils.transferMoveMap(factoryMoveMap, unitTerritoryMap, dataCopy, data, player);
          }
        } else if (stepName.endsWith("CombatMove") && !stepName.endsWith("AirborneCombatMove")) {
          final Map<Territory, ProTerritory> moveMap = combatMoveAI.doCombatMove(moveDel, dataCopy, playerCopy, true);
          if (storedCombatMoveMap == null) {
            storedCombatMoveMap = simulateTurnUtils.transferMoveMap(moveMap, unitTerritoryMap, dataCopy, data, player);
          }
        } else if (stepName.endsWith("Battle")) {
          simulateTurnUtils.simulateBattles(dataCopy, playerCopy, bridge);
        } else if (stepName.endsWith("Place") || stepName.endsWith("EndTurn")) {
          storedPurchaseTerritories = purchaseAI.purchase(purchaseDelegate, dataCopy, data, player);
          ProData.setData(data);
          break;
        } else if (stepName.endsWith("Politics")) {
          final PoliticsDelegate politicsDelegate = DelegateFinder.politicsDelegate(dataCopy);
          politicsDelegate.setDelegateBridgeAndPlayer(bridge);
          final List<PoliticalActionAttachment> actions = politicsAI.politicalActions();
          if (storedPoliticalActions == null) {
            storedPoliticalActions = actions;
          }
        }
      }
    }
    ProLogger.info(player.getName() + " time for purchase=" + (System.currentTimeMillis() - start));
  }

  @Override
  protected void place(final boolean bid, final IAbstractPlaceDelegate placeDelegate, final GameData data,
      final PlayerID player) {
    final long start = System.currentTimeMillis();
    BattleCalculator.clearOOLCache();
    ProLogUI.notifyStartOfRound(data.getSequence().getRound(), player.getName());
    initializeData();
    if (bid) {
      purchaseAI.bidPlace(placeDelegate, data, player);
    } else {
      purchaseAI.place(storedPurchaseTerritories, placeDelegate, data, player);
      storedPurchaseTerritories = null;
    }
    ProLogger.info(player.getName() + " time for place=" + (System.currentTimeMillis() - start));
  }

  @Override
  protected void tech(final ITechDelegate techDelegate, final GameData data, final PlayerID player) {
    if (!games.strategy.triplea.Properties.getWW2V3TechModel(data)) {
      return;
    }
    long last, now;
    last = System.currentTimeMillis();
    s_logger.fine("Doing Tech ");
    final Territory myCapitol = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    final float eStrength = SUtils.getStrengthOfPotentialAttackers(myCapitol, data, player, false, true, null);
    float myStrength = SUtils.strength(myCapitol.getUnits().getUnits(), false, false, false);
    final List<Territory> areaStrength = SUtils.getNeighboringLandTerritories(data, player, myCapitol);
    for (final Territory areaTerr : areaStrength) {
      myStrength += SUtils.strength(areaTerr.getUnits().getUnits(), false, false, false) * 0.75F;
    }
    final boolean capDanger = myStrength < (eStrength * 1.25F + 3.0F);
    final Resource pus = data.getResourceList().getResource(Constants.PUS);
    final int PUs = player.getResources().getQuantity(pus);
    final Resource techtokens = data.getResourceList().getResource(Constants.TECH_TOKENS);
    final int TechTokens = player.getResources().getQuantity(techtokens);
    int TokensToBuy = 0;
    if (!capDanger && TechTokens < 3 && PUs > Math.random() * 160) {
      TokensToBuy = 1;
    }
    if (TechTokens > 0 || TokensToBuy > 0) {
      final List<TechnologyFrontier> cats = TechAdvance.getPlayerTechCategories(data, player);
      // retaining 65% chance of choosing land advances using basic ww2v3 model.
      if (data.getTechnologyFrontier().isEmpty()) {
        if (Math.random() > 0.35) {
          techDelegate.rollTech(TechTokens + TokensToBuy, cats.get(1), TokensToBuy, null);
        } else {
          techDelegate.rollTech(TechTokens + TokensToBuy, cats.get(0), TokensToBuy, null);
        }
      } else {
        final int rand = (int) (Math.random() * cats.size());
        techDelegate.rollTech(TechTokens + TokensToBuy, cats.get(rand), TokensToBuy, null);
      }
    }
    now = System.currentTimeMillis();
    s_logger.finest("Time Taken " + (now - last));
  }

  @Override
  public Territory retreatQuery(final GUID battleID, final boolean submerge, final Territory battleTerritory,
      final Collection<Territory> possibleTerritories, final String message) {
    initializeData();

    // Get battle data
    final GameData data = getGameData();
    final PlayerID player = getPlayerID();
    final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
    final IBattle battle = delegate.getBattleTracker().getPendingBattle(battleID);

    // If battle is null or amphibious then don't retreat
    if (battle == null || battleTerritory == null || battle.isAmphibious()) {
      return null;
    }

    // If attacker with more unit strength or strafing and isn't land battle with only air left then don't retreat
    final boolean isAttacker = player.equals(battle.getAttacker());
    final List<Unit> attackers = (List<Unit>) battle.getAttackingUnits();
    final List<Unit> defenders = (List<Unit>) battle.getDefendingUnits();
    final double strengthDifference = ProBattleUtils.estimateStrengthDifference(battleTerritory, attackers, defenders);
    boolean isStrafing = false;
    if (isAttacker && storedStrafingTerritories.contains(battleTerritory)) {
      isStrafing = true;
    }
    ProLogger.info(player.getName() + " checking retreat from territory " + battleTerritory + ", attackers="
        + attackers.size() + ", defenders=" + defenders.size() + ", submerge=" + submerge + ", attacker=" + isAttacker
        + ", isStrafing=" + isStrafing);
    if ((isStrafing || (isAttacker && strengthDifference > 50))
        && (battleTerritory.isWater() || Match.someMatch(attackers, Matches.UnitIsLand))) {
      return null;
    }
    ProBattleUtils.setData(getGameData());
    return retreatAI.retreatQuery(battleID, submerge, battleTerritory, possibleTerritories, message);
  }

  @Override
  public boolean shouldBomberBomb(final Territory territory) {
    return false;
  }

  @Override
  public Collection<Unit> getNumberOfFightersToMoveToNewCarrier(final Collection<Unit> fightersThatCanBeMoved,
      final Territory from) {
    final List<Unit> rVal = new ArrayList<Unit>();

    return rVal;
  }

  @Override
  public CasualtyDetails selectCasualties(final Collection<Unit> selectFrom,
      final Map<Unit, Collection<Unit>> dependents, final int count, final String message, final DiceRoll dice,
      final PlayerID hit, final Collection<Unit> friendlyUnits, final PlayerID enemyPlayer,
      final Collection<Unit> enemyUnits, final boolean amphibious, final Collection<Unit> amphibiousLandAttackers,
      final CasualtyList defaultCasualties, final GUID battleID, final Territory battlesite,
      final boolean allowMultipleHitsPerUnit) {
    initializeData();

    if (defaultCasualties.size() != count) {
      throw new IllegalStateException(
          "Select Casualties showing different numbers for number of hits to take vs total size of default casualty selections");
    }
    if (defaultCasualties.getKilled().size() <= 0) {
      return new CasualtyDetails(defaultCasualties, false);
    }

    // Consider unit cost
    final CasualtyDetails myCasualties = new CasualtyDetails(false);
    myCasualties.addToDamaged(defaultCasualties.getDamaged());
    final List<Unit> selectFromSorted = new ArrayList<Unit>(selectFrom);
    if (enemyUnits.isEmpty()) {
      Collections.sort(selectFromSorted, ProPurchaseUtils.getCostComparator());
    } else {

      // Get battle data
      final GameData data = getGameData();
      final PlayerID player = getPlayerID();
      final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
      final IBattle battle = delegate.getBattleTracker().getPendingBattle(battleID);

      // If defender and could lose battle then don't consider unit cost as just trying to survive
      boolean needToCheck = true;
      final boolean isAttacker = player.equals(battle.getAttacker());
      if (!isAttacker) {
        final List<Unit> attackers = (List<Unit>) battle.getAttackingUnits();
        final List<Unit> defenders = (List<Unit>) battle.getDefendingUnits();
        defenders.removeAll(defaultCasualties.getKilled());
        final double strengthDifference = ProBattleUtils.estimateStrengthDifference(battlesite, attackers, defenders);
        int minStrengthDifference = 60;
        if (!games.strategy.triplea.Properties.getLow_Luck(data)) {
          minStrengthDifference = 55;
        }
        if (strengthDifference > minStrengthDifference) {
          needToCheck = false;
        }
      }

      // Use bubble sort to save expensive units
      while (needToCheck) {
        needToCheck = false;
        for (int i = 0; i < selectFromSorted.size() - 1; i++) {
          final Unit unit1 = selectFromSorted.get(i);
          final Unit unit2 = selectFromSorted.get(i + 1);
          final double unitCost1 = ProPurchaseUtils.getCost(unit1.getType(), unit1.getOwner(), unit1.getData());
          final double unitCost2 = ProPurchaseUtils.getCost(unit2.getType(), unit2.getOwner(), unit2.getData());
          if (unitCost1 > 1.5 * unitCost2) {
            selectFromSorted.set(i, unit2);
            selectFromSorted.set(i + 1, unit1);
            needToCheck = true;
          }
        }
      }
    }

    // Interleave carriers and planes
    final List<Unit> interleavedTargetList =
        new ArrayList<Unit>(ProTransportUtils.InterleaveUnits_CarriersAndPlanes(selectFromSorted, 0));
    for (int i = 0; i < defaultCasualties.getKilled().size(); ++i) {
      myCasualties.addToKilled(interleavedTargetList.get(i));
    }
    if (count != myCasualties.size()) {
      throw new IllegalStateException("AI chose wrong number of casualties");
    }
    return myCasualties;
  }

  /**
   * Ask the player which units, if any, they want to scramble to defend against the attacker.
   *
   * @param scrambleTo
   *        - the territory we are scrambling to defend in, where the units will end up if scrambled
   * @param possibleScramblers
   *        - possible units which we could scramble, with where they are from and how many allowed from that location
   * @return a list of units to scramble mapped to where they are coming from
   */
  @Override
  public HashMap<Territory, Collection<Unit>> scrambleUnitsQuery(final Territory scrambleTo,
      final Map<Territory, Tuple<Collection<Unit>, Collection<Unit>>> possibleScramblers) {
    initializeData();

    // Get battle data
    final GameData data = getGameData();
    final PlayerID player = getPlayerID();
    final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
    final IBattle battle = delegate.getBattleTracker().getPendingBattle(scrambleTo, false, BattleType.NORMAL);

    // If battle is null then don't scramble
    if (battle == null) {
      return null;
    }
    final List<Unit> attackers = (List<Unit>) battle.getAttackingUnits();
    final List<Unit> defenders = (List<Unit>) battle.getDefendingUnits();
    ProLogger.info(player.getName() + " checking scramble to " + scrambleTo + ", attackers=" + attackers.size()
        + ", defenders=" + defenders.size() + ", possibleScramblers=" + possibleScramblers);
    ProBattleUtils.setData(getGameData());
    return scrambleAI.scrambleUnitsQuery(scrambleTo, possibleScramblers);
  }

  @Override
  public boolean selectAttackSubs(final Territory unitTerritory) {
    initializeData();

    // Get battle data
    final GameData data = getGameData();
    final PlayerID player = getPlayerID();
    final BattleDelegate delegate = DelegateFinder.battleDelegate(data);
    final IBattle battle = delegate.getBattleTracker().getPendingBattle(unitTerritory, false, BattleType.NORMAL);

    // If battle is null then don't attack
    if (battle == null) {
      return false;
    }
    final List<Unit> attackers = (List<Unit>) battle.getAttackingUnits();
    final List<Unit> defenders = (List<Unit>) battle.getDefendingUnits();
    ProLogger.info(player.getName() + " checking sub attack in " + unitTerritory + ", attackers=" + attackers
        + ", defenders=" + defenders);
    ProBattleUtils.setData(getGameData());

    // Calculate battle results
    final ProBattleResult result =
        ProBattleUtils.calculateBattleResults(player, unitTerritory, attackers, defenders, new HashSet<Unit>(), true);
    ProLogger.debug(player.getName() + " sub attack TUVSwing=" + result.getTUVSwing());
    if (result.getTUVSwing() > 0) {
      return true;
    }
    return false;
  }

  @Override
  public void politicalActions() {
    initializeData();

    if (storedPoliticalActions == null) {
      politicsAI.politicalActions();
    } else {
      politicsAI.doActions(storedPoliticalActions);
      storedPoliticalActions = null;
    }
  }
}
