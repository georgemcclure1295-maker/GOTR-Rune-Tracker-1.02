package com.gotrrunetracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GotrRuneTrackerPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(GotrRuneTrackerPlugin.class);
        RuneLite.main(args);
    }
}
