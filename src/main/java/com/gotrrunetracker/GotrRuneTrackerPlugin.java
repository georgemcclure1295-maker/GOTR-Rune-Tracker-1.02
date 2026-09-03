package com.gotrrunetracker;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

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

    private int gamesCompleted;
    private int lastCompletedGameTick = -1;

    private boolean gotrGameActive;
    private boolean waitingForNextGameStart;

    /*
     * Tracks whether the GOTR HUD was visible on the previous tick.
     *
     * The HUD disappears while inside rune altars, so HUD disappearance does
     * NOT mean the game has ended. When the HUD returns, we take a fresh
     * inventory snapshot to avoid odd inventory changes being treated as
     * newly crafted runes.
     */
    private boolean gotrHudWasVisible;

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
            snapshotCurrentInventory();
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

        gamesCompleted = 0;
        lastCompletedGameTick = -1;
        gotrGameActive = false;
        waitingForNextGameStart = false;
        gotrHudWasVisible = false;
    }

    /*
     * Current RuneLite GOTR game display.
     *
     * This display exists during the active minigame but disappears while
     * travelling through rune portals to an altar.
     */
    private boolean isGotrHudVisible()
    {
        Widget gotrDisplay = client.getWidget(WidgetInfo.GOTR_MAIN_DISPLAY);
        return gotrDisplay != null && !gotrDisplay.isHidden();
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        boolean hudVisible = isGotrHudVisible();

        /*
         * MID-GAME JOIN FIX
         *
         * Previously this fallback was blocked while
         * waitingForNextGameStart == true.
         *
         * That meant a player who missed "the rift becomes active" could
         * enter an already-running game and never start tracking.
         *
         * Now, if the GOTR game HUD is visible and tracking isn't active,
         * we begin tracking regardless of whether the start chat message
         * was seen.
         */
        if (!gotrGameActive && hudVisible)
        {
            /*
             * Avoid immediately reopening the game on the same couple of
             * ticks as the completion message.
             */
            int currentTick = client.getTickCount();

            if (lastCompletedGameTick == -1
                    || currentTick - lastCompletedGameTick > 2)
            {
                beginGameTracking();
            }
        }

        /*
         * If we have returned to the GOTR arena after the HUD was absent,
         * refresh our inventory baseline.
         *
         * Rune gains produced at the altar have already fired inventory
         * events and therefore have already been counted.
         */
        if (gotrGameActive && hudVisible && !gotrHudWasVisible)
        {
            snapshotCurrentInventory();
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

        ItemContainer inventory = event.getItemContainer();

        if (inventory == null)
        {
            return;
        }

        Map<Integer, Integer> newRuneCounts = countRunes(inventory);

        if (!gotrGameActive)
        {
            previousRuneCounts.clear();
            previousRuneCounts.putAll(newRuneCounts);
            return;
        }

        for (Map.Entry<Integer, String> rune : runeNames.entrySet())
        {
            int itemId = rune.getKey();
            String runeName = rune.getValue();

            int previousAmount = previousRuneCounts.getOrDefault(itemId, 0);
            int newAmount = newRuneCounts.getOrDefault(itemId, 0);
            int gained = newAmount - previousAmount;

            if (gained > 0)
            {
                currentGameTotals.merge(runeName, gained, Integer::sum);
                sessionTotals.merge(runeName, gained, Integer::sum);
            }
        }

        previousRuneCounts.clear();
        previousRuneCounts.putAll(newRuneCounts);

        refreshPanel();
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

        /*
         * Normal game start.
         */
        if (message.contains("the rift becomes active"))
        {
            waitingForNextGameStart = false;
            beginGameTracking();
            return;
        }

        boolean gameCompletedMessage =
                message.contains("the great guardian successfully closed the rift")
                        || message.contains("the rift has been subdued");

        if (!gameCompletedMessage || !gotrGameActive)
        {
            return;
        }

        int currentTick = client.getTickCount();

        /*
         * GOTR can produce more than one completion-related message.
         * Prevent the same game being counted twice.
         */
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
        snapshotCurrentInventory();

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

        snapshotCurrentInventory();
        refreshPanel();

        client.addChatMessage(
                ChatMessageType.GAMEMESSAGE,
                "",
                "GOTR Rune Tracker: Game completed! Total games: " + gamesCompleted,
                null
        );
    }

    private void startNewGame()
    {
        clientThread.invokeLater(() ->
        {
            clearCurrentGameTotals();
            snapshotCurrentInventory();

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

            snapshotCurrentInventory();
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

        // Standard runes
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

        // Combination runes
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

    private void snapshotCurrentInventory()
    {
        ItemContainer inventory =
                client.getItemContainer(InventoryID.INVENTORY);

        previousRuneCounts.clear();

        if (inventory != null)
        {
            previousRuneCounts.putAll(countRunes(inventory));
        }
    }

    private Map<Integer, Integer> countRunes(ItemContainer inventory)
    {
        Map<Integer, Integer> counts = new HashMap<>();

        if (inventory == null)
        {
            return counts;
        }

        for (Item item : inventory.getItems())
        {
            if (item == null || !runeNames.containsKey(item.getId()))
            {
                continue;
            }

            counts.merge(
                    item.getId(),
                    item.getQuantity(),
                    Integer::sum
            );
        }

        return counts;
    }

    private Map<String, Double> calculateAverages()
    {
        Map<String, Double> averages = new LinkedHashMap<>();

        for (String runeName : runeNames.values())
        {
            double average = gamesCompleted == 0
                    ? 0.0
                    : completedGameTotals.getOrDefault(runeName, 0)
                    / (double) gamesCompleted;

            averages.put(runeName, average);
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

            panel.updateGamesCompleted(completed);
        });
    }

    private BufferedImage loadIcon()
    {
        try
        {
            return ImageIO.read(
                    Objects.requireNonNull(
                            getClass().getResourceAsStream(
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
