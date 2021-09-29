package biz.princeps.landlordmap.bluemap.integration;

import biz.princeps.landlord.api.IOwnedLand;
import biz.princeps.landlordmap.bluemap.LLBlueMap;
import biz.princeps.landlordmap.bluemap.config.Configuration;
import biz.princeps.landlordmap.bluemap.utils.Constants;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.marker.MarkerAPI;
import de.bluecolored.bluemap.api.marker.MarkerSet;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class BlueMapIntegration {

    private final LLBlueMap plugin;
    private final Configuration config;

    private final Map<IOwnedLand, UpdateReason> queue;
    private BukkitTask updateTask;

    public BlueMapIntegration(LLBlueMap plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfiguration();

        this.queue = new ConcurrentHashMap<>();
    }

    private MarkerSet buildMarkerSet(MarkerAPI markerAPI) {
        return markerAPI.createMarkerSet(Constants.MARKER_SET_ID);
    }

    public void hookBlueMap(BlueMapAPI blueMapAPI) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, bukkitTask -> {
            try {
                final MarkerAPI markerAPI = blueMapAPI.getMarkerAPI();
                plugin.logToConsole(Level.INFO, "Loading Landlord markers...");

                final MarkerSet markerSet = buildMarkerSet(markerAPI);
                final int size = markerSet.getMarkers().size();

                plugin.logToConsole(Level.INFO, "Updating Landlord markers...");
                markerSet.setLabel(config.getMarkerSetLabel());
                markerSet.setDefaultHidden(config.isMarkerSetDefaultHidden());
                markerSet.setToggleable(config.isMarkerSetToggleable());

                if (size == 0) {
                    plugin.logToConsole(Level.WARNING, "Landlord markers not found!");
                    importLands(blueMapAPI, markerAPI, markerSet);
                } else {
                    plugin.logToConsole(Level.INFO, size + " Landlord markers found!");
                    markerAPI.save();
                }
                plugin.logToConsole(Level.INFO, "Loading update task...");
                initUpdateTask();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void unhookBlueMap(BlueMapAPI blueMapAPI) {
        try {
            updateTask.cancel();
            final MarkerAPI markerAPI = blueMapAPI.getMarkerAPI();

            final Set<IOwnedLand> ownedLands = plugin.getLandLordAPI().getWGManager().getRegions();
            plugin.logToConsole(Level.WARNING, "Checking " + ownedLands.size() + " lands and processing " + queue.size() + " remaining updates, this could take a while...");

            for (IOwnedLand ownedLand : plugin.getLandLordAPI().getWGManager().getRegions()) {
                final OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownedLand.getOwner());
                final Instant lastPlayed = Instant.ofEpochMilli(offlinePlayer.getLastPlayed());

                if (lastPlayed.isBefore(Instant.now().minus(config.getMarkerSetLifetime(), ChronoUnit.DAYS))) {
                    enqueueLand(ownedLand, UpdateReason.UNCLAIM);
                }
            }
            processQueue(blueMapAPI, markerAPI, buildMarkerSet(markerAPI), Integer.MAX_VALUE);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void initUpdateTask() {
        if (updateTask != null) {
            updateTask.cancel();
        }
        updateTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () ->
                BlueMapAPI.getInstance().ifPresent(blueMapAPI -> {
                    try {
                        final MarkerAPI markerAPI = blueMapAPI.getMarkerAPI();
                        processQueue(blueMapAPI, markerAPI, buildMarkerSet(markerAPI), config.getMaxProcessedPerUpdate());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }), config.getUpdateTaskFrequency(), config.getUpdateTaskFrequency());
    }

    private void importLands(BlueMapAPI blueMapAPI, MarkerAPI markerAPI, MarkerSet markerSet) {
        final Set<IOwnedLand> ownedLands = plugin.getLandLordAPI().getWGManager().getRegions();
        plugin.logToConsole(Level.WARNING, "Importing " + ownedLands.size() + " lands, this could take a while...");

        for (IOwnedLand ownedLand : plugin.getLandLordAPI().getWGManager().getRegions()) {
            final OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(ownedLand.getOwner());
            final Instant lastPlayed = Instant.ofEpochMilli(offlinePlayer.getLastPlayed());

            if (lastPlayed.isAfter(Instant.now().minus(config.getMarkerSetLifetime(), ChronoUnit.DAYS))) {
                enqueueLand(ownedLand, UpdateReason.CLAIM);
            }
        }

        processQueue(blueMapAPI, markerAPI, markerSet, Integer.MAX_VALUE);
    }

    public void enqueueLand(IOwnedLand ownedLand, UpdateReason updateReason) {
        if (ownedLand == null || ownedLand.getOwner() == null)
            return;

        queue.compute(ownedLand, (queuedOwnedLand, queuedUpdateReason) -> {
            if (queuedUpdateReason == null) {
                return updateReason;
            }

            switch (queuedUpdateReason) {
                case CLAIM:
                case UNCLAIM:
                    if (updateReason == UpdateReason.MANAGE) {
                        return queuedUpdateReason;
                    }
                default:
                    return updateReason;
            }
        });
    }

    private void processQueue(BlueMapAPI blueMapAPI, MarkerAPI markerAPI, MarkerSet markerSet, int limit) {
        int iterations = 0;

        for (Iterator<Map.Entry<IOwnedLand, UpdateReason>> iterator = queue.entrySet().iterator(); iterator.hasNext() && iterations < limit; ) {
            final Map.Entry<IOwnedLand, UpdateReason> entry = iterator.next();

            new BlueMapLand(plugin, entry.getKey()).process(blueMapAPI, markerSet, entry.getValue());

            iterator.remove();
            iterations++;
        }

        if (iterations == 0)
            return;
        try {
            markerAPI.save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}