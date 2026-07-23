package com.gotrrunetracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

public class GotrRuneTrackerPanel extends PluginPanel
{
    private static final Color ACCENT = new Color(90, 170, 255);
    private static final Color MUTED = new Color(160, 160, 160);
    private static final Color ZERO_VALUE = new Color(120, 120, 120);
    private static final DecimalFormat AVERAGE_FORMAT = new DecimalFormat("0.0");

    private final Map<String, JLabel> currentGameLabels = new LinkedHashMap<>();
    private final Map<String, JLabel> sessionLabels = new LinkedHashMap<>();
    private final Map<String, JLabel> averageLabels = new LinkedHashMap<>();

    private final JLabel currentGameTotalLabel = createAmountLabel();
    private final JLabel sessionTotalLabel = createAmountLabel();
    private final JLabel averageTotalLabel = createAmountLabel();
    private final JLabel currentGameHeaderTotalLabel = new JLabel("0", SwingConstants.CENTER);
    private final JLabel gamesCompletedLabel = new JLabel("0", SwingConstants.CENTER);
    private final JButton newGameButton = new JButton("New Game");
    private final JButton resetSessionButton = new JButton("Reset Session");

    public GotrRuneTrackerPanel()
    {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(12, 12, 12, 12));
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        mainPanel.add(createHeaderPanel());
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(createRuneSection("Current Game", currentGameLabels, currentGameTotalLabel));
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(createRuneSection("Total Session", sessionLabels, sessionTotalLabel));
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(createRuneSection("Average per Completed Game", averageLabels, averageTotalLabel));
        mainPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        mainPanel.add(createButtonPanel());

        add(mainPanel, BorderLayout.NORTH);
        styleButtons();
    }

    private JPanel createHeaderPanel()
    {
        JPanel headerPanel = new JPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));
        headerPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel titleLabel = new JLabel("GOTR Rune Tracker", SwingConstants.CENTER);
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        titleLabel.setForeground(Color.WHITE);

        JPanel statsPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        statsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        statsPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        statsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        statsPanel.add(createHeaderStat("Current Game", currentGameHeaderTotalLabel));
        statsPanel.add(createHeaderStat("Games Completed", gamesCompletedLabel));

        headerPanel.add(titleLabel);
        headerPanel.add(statsPanel);
        return headerPanel;
    }

    private JPanel createHeaderStat(String title, JLabel valueLabel)
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(7, 4, 7, 4)
        ));

        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);
        titleLabel.setForeground(MUTED);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.PLAIN, 11f));

        valueLabel.setAlignmentX(CENTER_ALIGNMENT);
        valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
        valueLabel.setForeground(Color.WHITE);
        valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 16f));

        panel.add(titleLabel);
        panel.add(Box.createRigidArea(new Dimension(0, 3)));
        panel.add(valueLabel);
        return panel;
    }

    private JPanel createRuneSection(String title, Map<String, JLabel> labelMap, JLabel totalLabel)
    {
        JPanel sectionPanel = new JPanel(new BorderLayout(0, 8));
        sectionPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        sectionPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(10, 10, 10, 10)
        ));

        JLabel sectionTitle = new JLabel(title);
        sectionTitle.setFont(sectionTitle.getFont().deriveFont(Font.BOLD, 14f));
        sectionTitle.setForeground(ACCENT);

        JPanel runeGrid = new JPanel(new GridLayout(0, 2, 8, 5));
        runeGrid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        for (String runeName : new String[] {
                "Air", "Mind", "Water", "Earth", "Fire", "Body",
                "Cosmic", "Chaos", "Nature", "Law", "Death", "Blood"
        })
        {
            addRuneRow(runeGrid, labelMap, runeName);
        }

        JPanel totalPanel = new JPanel(new GridLayout(1, 2));
        totalPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        totalPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
                new EmptyBorder(8, 0, 0, 0)
        ));

        JLabel totalTextLabel = new JLabel("Total");
        totalTextLabel.setFont(totalTextLabel.getFont().deriveFont(Font.BOLD));
        totalTextLabel.setForeground(Color.WHITE);
        totalLabel.setFont(totalLabel.getFont().deriveFont(Font.BOLD));
        totalLabel.setForeground(Color.WHITE);

        totalPanel.add(totalTextLabel);
        totalPanel.add(totalLabel);
        sectionPanel.add(sectionTitle, BorderLayout.NORTH);
        sectionPanel.add(runeGrid, BorderLayout.CENTER);
        sectionPanel.add(totalPanel, BorderLayout.SOUTH);
        return sectionPanel;
    }

    private void addRuneRow(JPanel panel, Map<String, JLabel> labelMap, String runeName)
    {
        JLabel nameLabel = new JLabel(runeName);
        nameLabel.setForeground(new Color(220, 220, 220));
        JLabel amountLabel = createAmountLabel();
        labelMap.put(runeName, amountLabel);
        panel.add(nameLabel);
        panel.add(amountLabel);
    }

    private JLabel createAmountLabel()
    {
        JLabel label = new JLabel("0", SwingConstants.RIGHT);
        label.setForeground(ZERO_VALUE);
        return label;
    }

    private JPanel createButtonPanel()
    {
        JPanel buttonPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        buttonPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        buttonPanel.add(newGameButton);
        buttonPanel.add(resetSessionButton);
        return buttonPanel;
    }

    private void styleButtons()
    {
        newGameButton.setFocusPainted(false);
        resetSessionButton.setFocusPainted(false);
    }

    public void setNewGameAction(Runnable action)
    {
        newGameButton.addActionListener(event -> action.run());
    }

    public void setResetSessionAction(Runnable action)
    {
        resetSessionButton.addActionListener(event -> action.run());
    }

    public void updateTotals(
            Map<String, Integer> currentGame,
            Map<String, Integer> session,
            Map<String, Double> averages)
    {
        int currentTotal = updateIntegerLabels(currentGameLabels, currentGame);
        int sessionTotal = updateIntegerLabels(sessionLabels, session);
        double averageTotal = updateAverageLabels(averageLabels, averages);

        setIntegerLabel(currentGameTotalLabel, currentTotal);
        setIntegerLabel(sessionTotalLabel, sessionTotal);
        setAverageLabel(averageTotalLabel, averageTotal);
        currentGameHeaderTotalLabel.setText(Integer.toString(currentTotal));
    }

    public void updateGamesCompleted(int gamesCompleted)
    {
        gamesCompletedLabel.setText(Integer.toString(gamesCompleted));
    }

    private int updateIntegerLabels(Map<String, JLabel> labels, Map<String, Integer> values)
    {
        int total = 0;
        for (Map.Entry<String, JLabel> entry : labels.entrySet())
        {
            int value = values.getOrDefault(entry.getKey(), 0);
            total += value;
            setIntegerLabel(entry.getValue(), value);
        }
        return total;
    }

    private double updateAverageLabels(Map<String, JLabel> labels, Map<String, Double> values)
    {
        double total = 0.0;
        for (Map.Entry<String, JLabel> entry : labels.entrySet())
        {
            double value = values.getOrDefault(entry.getKey(), 0.0);
            total += value;
            setAverageLabel(entry.getValue(), value);
        }
        return total;
    }

    private void setIntegerLabel(JLabel label, int value)
    {
        label.setText(Integer.toString(value));
        label.setForeground(value == 0 ? ZERO_VALUE : Color.WHITE);
    }

    private void setAverageLabel(JLabel label, double value)
    {
        label.setText(AVERAGE_FORMAT.format(value));
        label.setForeground(value == 0.0 ? ZERO_VALUE : Color.WHITE);
    }
}
