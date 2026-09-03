package com.gotrrunetracker;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Varbits;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;
import net.runelite.api.TileItem;

@PluginDescriptor(
        name = "GOTR Rune Tracker",
        description = "Tracks runes crafted during Guardians of the Rift",
        tags = {"gotr", "guardians of the rift", "runecraft", "runes", "tracker"}
)
public class GotrRuneTrackerPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    private GotrRuneTrackerPanel panel;
    private NavigationButton navigationButton;

    private final Map<Integer, String> runeNames = new LinkedHashMap<>();
    private final Map<Integer, Integer> previousRuneCounts = new HashMap<>();

    private final Map<String, Integer> currentGameTotals = new LinkedHashMap<>();
    private final Map<String, Integer> sessionTotals = new LinkedHashMap<>();
    private final Map<String, Integer> completedGameTotals = new LinkedHashMap<>();

    /*
     * Ground-rune pickups that should be ignored.
     *
     * Value = quantity currently available to ignore.
     */
    private final Map<Integer, Integer> pendingGroundPickups = new HashMap<>();

    /*
     * Expiry tick for each pending ground-rune pickup.
     *
     * This prevents a rune despawning somewhere nearby from suppressing
     * a legitimate crafted gain much later.
     */
    private final Map<Integer, Integer> pendingGroundPickupExpiry = new HashMap<>();

    private static final int GROUND_PICKUP_EXPIRY_TICKS = 3;

    private int gamesCompleted;
    private int lastCompletedGameTick = -1;

    private boolean gotrGameActive;
    private boolean waitingForNextGameStart;
    private boolean gotrHudWasVisible;

    private static final int[] RUNE_POUCH_TYPE_VARBITS =
            {
                    Varbits.RUNE_POUCH_RUNE1,
                    Varbits.RUNE_POUCH_RUNE2,
                    Varbits.RUNE_POUCH_RUNE3,
                    Varbits.RUNE_POUCH_RUNE4,
                    Varbits.RUNE_POUCH_RUNE5,
                    Varbits.RUNE_POUCH_RUNE6
            };

    private static final int[] RUNE_POUCH_AMOUNT_VARBITS =
            {
                    Varbits.RUNE_POUCH_AMOUNT1,
                    Varbits.RUNE_POUCH_AMOUNT2,
                    Varbits.RUNE_POUCH_AMOUNT3,
                    Varbits.RUNE_POUCH_AMOUNT4,
                    Varbits.RUNE_POUCH_AMOUNT5,
                    Varbits.RUNE_POUCH_AMOUNT6
            };

    @Provides
    GotrRuneTrackerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GotrRuneTrackerConfig.class);
    }

    @Override
    protected void startUp()
    {
        initialiseRunes();
        initialiseTotals();

        SwingUtilities.invokeLater(() ->
        {
            panel = new GotrRuneTrackerPanel();
            panel.setNewGameAction(this::startNewGame);
            panel.setResetSessionAction(this::resetSession);

            refreshPanel();

            navigationButton = NavigationButton.builder()
                    .tooltip("GOTR Rune Tracker")
                    .icon(loadIcon())
                    .priority(5)
                    .panel(panel)
                    .build();

            clientToolbar.addNavigation(navigationButton);
        });

        clientThread.invokeLater(() ->
        {
            gotrGameActive = false;
            waitingForNextGameStart = false;
            gotrHudWasVisible = isGotrHudVisible();

            snapshotCurrentRunes();
        });
    }

    @Override
    protected void shutDown()
    {
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
        }

        navigationButton = null;
        panel = null;

        previousRuneCounts.clear();
        currentGameTotals.clear();
        sessionTotals.clear();
        completedGameTotals.clear();

        pendingGroundPickups.clear();
        pendingGroundPickupExpiry.clear();

        gamesCompleted = 0;
        lastCompletedGameTick = -1;

        gotrGameActive = false;
        waitingForNextGameStart = false;
        gotrHudWasVisible = false;
    }

    private boolean isGotrHudVisible()
    {
        Widget gotrDisplay = client.getWidget(InterfaceID.GotrHud.CONTENT);

        return gotrDisplay != null
                && !gotrDisplay.isHidden();
    }

    private boolean isBankOpen()
    {
        return client.getWidget(InterfaceID.BANKMAIN, 0) != null;
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        expireOldGroundPickups();

        boolean hudVisible = isGotrHudVisible();

        if (!gotrGameActive && hudVisible)
        {
            int currentTick = client.getTickCount();

            if (lastCompletedGameTick == -1
                    || currentTick - lastCompletedGameTick > 2)
            {
                beginGameTracking();
            }
        }

        if (gotrGameActive
                && hudVisible
                && !gotrHudWasVisible)
        {
            snapshotCurrentRunes();
        }

        /*
         * Rune-pouch quantities can change without the inventory container
         * itself changing, so check once per game tick while active.
         */
        if (gotrGameActive)
        {
            processRuneChanges();
        }

        gotrHudWasVisible = hudVisible;
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (event.getContainerId() != InventoryID.INVENTORY.getId())
        {
            return;
        }

        if (isBankOpen())
        {
            snapshotCurrentRunes();
            return;
        }

        if (!gotrGameActive)
        {
            snapshotCurrentRunes();
            return;
        }

        processRuneChanges();
    }

    /*
     * RuneLite fires ItemDespawned when a ground item disappears.
     *
     * If that rune then appears in our combined inventory+pouch total within
     * the next few ticks, treat that increase as a pickup rather than crafting.
     */
    @Subscribe
    public void onItemDespawned(ItemDespawned event)
    {
        if (!gotrGameActive)
        {
            return;
        }

        TileItem item = event.getItem();

        if (item == null)
        {
            return;
        }

        int itemId = item.getId();

        if (!runeNames.containsKey(itemId))
        {
            return;
        }

        int quantity = item.getQuantity();

        if (quantity <= 0)
        {
            return;
        }

        pendingGroundPickups.merge(
                itemId,
                quantity,
                Integer::sum
        );

        pendingGroundPickupExpiry.put(
                itemId,
                client.getTickCount() + GROUND_PICKUP_EXPIRY_TICKS
        );
    }

    private void processRuneChanges()
    {
        Map<Integer, Integer> newRuneCounts = getCombinedRuneCounts();

        if (isBankOpen())
        {
            previousRuneCounts.clear();
            previousRuneCounts.putAll(newRuneCounts);
            return;
        }

        for (Map.Entry<Integer, String> rune : runeNames.entrySet())
        {
            int itemId = rune.getKey();
            String runeName = rune.getValue();

            int previousAmount =
                    previousRuneCounts.getOrDefault(itemId, 0);

            int newAmount =
                    newRuneCounts.getOrDefault(itemId, 0);

            int gained = newAmount - previousAmount;

            if (gained <= 0)
            {
                continue;
            }

            /*
             * Remove any amount that appears to have come from a recently
             * despawned ground-rune stack.
             */
            int pendingPickup =
                    pendingGroundPickups.getOrDefault(itemId, 0);

            if (pendingPickup > 0)
            {
                int ignored = Math.min(
                        gained,
                        pendingPickup
                );

                gained -= ignored;
                pendingPickup -= ignored;

                if (pendingPickup <= 0)
                {
                    pendingGroundPickups.remove(itemId);
                    pendingGroundPickupExpiry.remove(itemId);
                }
                else
                {
                    pendingGroundPickups.put(
                            itemId,
                            pendingPickup
                    );
                }
            }

            if (gained > 0)
            {
                currentGameTotals.merge(
                        runeName,
                        gained,
                        Integer::sum
                );

                sessionTotals.merge(
                        runeName,
                        gained,
                        Integer::sum
                );
            }
        }

        previousRuneCounts.clear();
        previousRuneCounts.putAll(newRuneCounts);

        refreshPanel();
    }

    private void expireOldGroundPickups()
    {
        int currentTick = client.getTickCount();

        Iterator<Map.Entry<Integer, Integer>> iterator =
                pendingGroundPickupExpiry.entrySet().iterator();

        while (iterator.hasNext())
        {
            Map.Entry<Integer, Integer> entry =
                    iterator.next();

            if (currentTick > entry.getValue())
            {
                pendingGroundPickups.remove(entry.getKey());
                iterator.remove();
            }
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.GAMEMESSAGE)
        {
            return;
        }

        String message = Text.removeTags(event.getMessage())
                .toLowerCase()
                .trim();

        if (message.contains("the rift becomes active"))
        {
            waitingForNextGameStart = false;

            beginGameTracking();
            return;
        }

        boolean gameCompletedMessage =
                message.contains(
                        "the great guardian successfully closed the rift"
                )
                        || message.contains(
                        "the rift has been subdued"
                );

        if (!gameCompletedMessage || !gotrGameActive)
        {
            return;
        }

        int currentTick = client.getTickCount();

        if (lastCompletedGameTick != -1
                && currentTick - lastCompletedGameTick <= 2)
        {
            return;
        }

        lastCompletedGameTick = currentTick;

        completeGameAutomatically();
    }

    private void beginGameTracking()
    {
        if (gotrGameActive)
        {
            return;
        }

        gotrGameActive = true;
        waitingForNextGameStart = false;

        clearCurrentGameTotals();
        snapshotCurrentRunes();

        gotrHudWasVisible = isGotrHudVisible();

        refreshPanel();

        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                "GOTR Rune Tracker: Game tracking started.",
                null
        );
    }

    private void completeGameAutomatically()
    {
        for (String runeName : runeNames.values())
        {
            completedGameTotals.merge(
                    runeName,
                    currentGameTotals.getOrDefault(runeName, 0),
                    Integer::sum
            );
        }

        gamesCompleted++;

        gotrGameActive = false;
        waitingForNextGameStart = true;
        gotrHudWasVisible = isGotrHudVisible();

        snapshotCurrentRunes();

        refreshPanel();

        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                "GOTR Rune Tracker: Game completed! Total games: "
                        + gamesCompleted,
                null
        );
    }

    private void startNewGame()
    {
        clientThread.invokeLater(() ->
        {
            clearCurrentGameTotals();

            snapshotCurrentRunes();

            gotrGameActive = isGotrHudVisible();
            waitingForNextGameStart = false;
            gotrHudWasVisible = isGotrHudVisible();

            refreshPanel();

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "GOTR Rune Tracker: Current game manually reset.",
                    null
            );
        });
    }

    private void resetSession()
    {
        clientThread.invokeLater(() ->
        {
            gamesCompleted = 0;
            lastCompletedGameTick = -1;

            clearCurrentGameTotals();

            for (String runeName : runeNames.values())
            {
                sessionTotals.put(runeName, 0);
                completedGameTotals.put(runeName, 0);
            }

            gotrGameActive = false;
            waitingForNextGameStart = false;
            gotrHudWasVisible = isGotrHudVisible();

            snapshotCurrentRunes();

            refreshPanel();

            client.addChatMessage(
                    ChatMessageType.GAMEMESSAGE,
                    "",
                    "GOTR Rune Tracker: Session reset.",
                    null
            );
        });
    }

    private void initialiseRunes()
    {
        runeNames.clear();

        runeNames.put(ItemID.AIR_RUNE, "Air");
        runeNames.put(ItemID.MIND_RUNE, "Mind");
        runeNames.put(ItemID.WATER_RUNE, "Water");
        runeNames.put(ItemID.EARTH_RUNE, "Earth");
        runeNames.put(ItemID.FIRE_RUNE, "Fire");
        runeNames.put(ItemID.BODY_RUNE, "Body");
        runeNames.put(ItemID.COSMIC_RUNE, "Cosmic");
        runeNames.put(ItemID.CHAOS_RUNE, "Chaos");
        runeNames.put(ItemID.NATURE_RUNE, "Nature");
        runeNames.put(ItemID.LAW_RUNE, "Law");
        runeNames.put(ItemID.DEATH_RUNE, "Death");
        runeNames.put(ItemID.BLOOD_RUNE, "Blood");

        runeNames.put(ItemID.MIST_RUNE, "Mist");
        runeNames.put(ItemID.DUST_RUNE, "Dust");
        runeNames.put(ItemID.MUD_RUNE, "Mud");
        runeNames.put(ItemID.SMOKE_RUNE, "Smoke");
        runeNames.put(ItemID.STEAM_RUNE, "Steam");
        runeNames.put(ItemID.LAVA_RUNE, "Lava");
    }

    private void initialiseTotals()
    {
        currentGameTotals.clear();
        sessionTotals.clear();
        completedGameTotals.clear();

        for (String runeName : runeNames.values())
        {
            currentGameTotals.put(runeName, 0);
            sessionTotals.put(runeName, 0);
            completedGameTotals.put(runeName, 0);
        }

        gamesCompleted = 0;
        lastCompletedGameTick = -1;

        gotrGameActive = false;
        waitingForNextGameStart = false;
        gotrHudWasVisible = false;
    }

    private void clearCurrentGameTotals()
    {
        for (String runeName : runeNames.values())
        {
            currentGameTotals.put(runeName, 0);
        }
    }

    private void snapshotCurrentRunes()
    {
        pendingGroundPickups.clear();
        pendingGroundPickupExpiry.clear();

        previousRuneCounts.clear();
        previousRuneCounts.putAll(
                getCombinedRuneCounts()
        );
    }

    private Map<Integer, Integer> getCombinedRuneCounts()
    {
        Map<Integer, Integer> counts = new HashMap<>();

        ItemContainer inventory =
                client.getItemContainer(InventoryID.INVENTORY);

        if (inventory != null)
        {
            for (Item item : inventory.getItems())
            {
                if (item == null
                        || !runeNames.containsKey(item.getId()))
                {
                    continue;
                }

                counts.merge(
                        item.getId(),
                        item.getQuantity(),
                        Integer::sum
                );
            }
        }

        Map<Integer, Integer> pouchCounts =
                countRunePouchRunes();

        for (Map.Entry<Integer, Integer> entry
                : pouchCounts.entrySet())
        {
            counts.merge(
                    entry.getKey(),
                    entry.getValue(),
                    Integer::sum
            );
        }

        return counts;
    }

    private Map<Integer, Integer> countRunePouchRunes()
    {
        Map<Integer, Integer> counts = new HashMap<>();

        for (int i = 0; i < RUNE_POUCH_TYPE_VARBITS.length; i++)
        {
            int runeType =
                    client.getVarbitValue(
                            RUNE_POUCH_TYPE_VARBITS[i]
                    );

            int amount =
                    client.getVarbitValue(
                            RUNE_POUCH_AMOUNT_VARBITS[i]
                    );

            if (runeType <= 0 || amount <= 0)
            {
                continue;
            }

            int itemId =
                    runePouchTypeToItemId(runeType);

            if (itemId != -1
                    && runeNames.containsKey(itemId))
            {
                counts.merge(
                        itemId,
                        amount,
                        Integer::sum
                );
            }
        }

        return counts;
    }

    private int runePouchTypeToItemId(int runeType)
    {
        switch (runeType)
        {
            case 1:
                return ItemID.AIR_RUNE;

            case 2:
                return ItemID.WATER_RUNE;

            case 3:
                return ItemID.EARTH_RUNE;

            case 4:
                return ItemID.FIRE_RUNE;

            case 5:
                return ItemID.MIND_RUNE;

            case 6:
                return ItemID.CHAOS_RUNE;

            case 7:
                return ItemID.DEATH_RUNE;

            case 8:
                return ItemID.BLOOD_RUNE;

            case 9:
                return ItemID.COSMIC_RUNE;

            case 10:
                return ItemID.NATURE_RUNE;

            case 11:
                return ItemID.LAW_RUNE;

            case 12:
                return ItemID.BODY_RUNE;

            case 15:
                return ItemID.MIST_RUNE;

            case 16:
                return ItemID.MUD_RUNE;

            case 17:
                return ItemID.DUST_RUNE;

            case 18:
                return ItemID.LAVA_RUNE;

            case 19:
                return ItemID.STEAM_RUNE;

            case 20:
                return ItemID.SMOKE_RUNE;

            default:
                return -1;
        }
    }

    private Map<String, Double> calculateAverages()
    {
        Map<String, Double> averages =
                new LinkedHashMap<>();

        for (String runeName : runeNames.values())
        {
            double average =
                    gamesCompleted == 0
                            ? 0.0
                            : completedGameTotals.getOrDefault(
                            runeName,
                            0
                    ) / (double) gamesCompleted;

            averages.put(
                    runeName,
                    average
            );
        }

        return averages;
    }

    private void refreshPanel()
    {
        if (panel == null)
        {
            return;
        }

        Map<String, Integer> currentCopy =
                new LinkedHashMap<>(currentGameTotals);

        Map<String, Integer> sessionCopy =
                new LinkedHashMap<>(sessionTotals);

        Map<String, Double> averageCopy =
                calculateAverages();

        int completed = gamesCompleted;

        SwingUtilities.invokeLater(() ->
        {
            panel.updateTotals(
                    currentCopy,
                    sessionCopy,
                    averageCopy
            );

            panel.updateGamesCompleted(
                    completed
            );
        });
    }

    private BufferedImage loadIcon()
    {
        try
        {
            return ImageIO.read(
                    Objects.requireNonNull(
                            getClass()
                                    .getResourceAsStream(
                                            "/gotr_rune_tracker_icon.png"
                                    )
                    )
            );
        }
        catch (IOException e)
        {
            throw new RuntimeException(
                    "Unable to load plugin icon.",
                    e
            );
        }
    }
}
