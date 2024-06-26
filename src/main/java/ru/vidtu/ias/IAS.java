/*
 * In-Game Account Switcher is a mod for Minecraft that allows you to change your logged in account in-game, without restarting Minecraft.
 * Copyright (C) 2015-2022 The_Fireplace
 * Copyright (C) 2021-2024 VidTu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>
 */

package ru.vidtu.ias;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vidtu.ias.auth.microsoft.MSAuth;
import ru.vidtu.ias.config.IASConfig;
import ru.vidtu.ias.config.IASStorage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Main IAS class.
 *
 * @author VidTu
 */
public final class IAS {
    /**
     * IAS static Microsoft application ID.
     */
    public static final String CLIENT_ID = "54fd49e4-2103-4044-9603-2b028c814ec3";

    /**
     * Request timeout.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("ias.timeout", 15L));

    /**
     * Logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("IAS");

    /**
     * Random session. Used in {@link #USER_AGENT_TEMPLATE}.
     */
    private static final UUID SESSION = UUID.randomUUID();

    /**
     * Template for {@link #userAgent}.
     */
    private static final String USER_AGENT_TEMPLATE = "IAS/%s (Session: %s; Loader: %s %s; Minecraft %s; Java %s)";

    /**
     * IAS executor.
     */
    private static ScheduledExecutorService executor = null;

    /**
     * Current IAS user agent.
     */
    private static String userAgent = null;

    /**
     * Current IAS game directory.
     */
    private static Path gameDirectory;

    /**
     * Current IAS config directory.
     */
    private static Path configDirectory;

    /**
     * Whether the mod is disabled remotely.
     */
    @SuppressWarnings("NegativelyNamedBooleanVariable") // <- The negative naming is intended.
    private static boolean disabled = false;

    /**
     * An instance of this class cannot be created.
     *
     * @throws AssertionError Always
     */
    private IAS() {
        throw new AssertionError("No instances.");
    }

    /**
     * Initializes the IAS.
     *
     * @param gamePath      Game directory
     * @param configPath    Config directory
     * @param version       Mod version
     * @param loader        Mod loader
     * @param loaderVersion Mod loader version
     * @param gameVersion   Game version
     */
    public static void init(Path gamePath, Path configPath, String version, String loader, String loaderVersion, String gameVersion) {
        // Initialize the dirs.
        gameDirectory = gamePath;
        configDirectory = configPath;

        // Set up IAS.
        userAgent = USER_AGENT_TEMPLATE.formatted(version, SESSION, loader, loaderVersion, gameVersion, Runtime.version().toString());
        LOGGER.info("IAS user agent: {}", userAgent);

        // Write the disclaimers.
        try {
            disclaimersStorage();
        } catch (Throwable t) {
            LOGGER.error("IAS: Unable to write disclaimers.", t);
        }

        // Read the config.
        try {
            loadConfig();
        } catch (Throwable t) {
            LOGGER.error("IAS: Unable to load IAS config.", t);
        }

        // Read the storage.
        try {
            loadStorage();
        } catch (Throwable t) {
            LOGGER.error("IAS: Unable to load IAS storage.", t);
        }

        // Create the executor.
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "IAS"));

        // Perform initial loading.
        if (Boolean.getBoolean("ias.skipDisableScanning")) return;
        executor.scheduleWithFixedDelay(() -> {
            // Perform scanning, if allowed.
            try {
                // Skip if not allowed or already disabled.
                if (disabled || Boolean.getBoolean("ias.skipDisableScanning")) return;

                // Create the client.
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(TIMEOUT)
                        .version(HttpClient.Version.HTTP_2)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .executor(Runnable::run)
                        .priority(1)
                        .build();

                // Send the request.
                HttpResponse<Stream<String>> response = client.send(HttpRequest.newBuilder()
                        .uri(new URI("https://raw.githubusercontent.com/The-Fireplace-Minecraft-Mods/In-Game-Account-Switcher/main/.ias/disabled_v1"))
                        .header("User-Agent", userAgent)
                        .timeout(TIMEOUT)
                        .GET()
                        .build(), HttpResponse.BodyHandlers.ofLines());

                // Validate the code.
                int code = response.statusCode();
                if (code < 200 || code > 299) return;

                // Check the lines.
                disabled = response.body().anyMatch(line -> {
                    line = line.strip();
                    return "ALL".equalsIgnoreCase(line) || version.equalsIgnoreCase(line);
                });
            } catch (Throwable ignored) {
                // NO-OP
            }
        }, 3L, 3L, TimeUnit.HOURS);
    }

    /**
     * Closes the IAS.
     */
    public static void close() {
        // Shutdown the executor.
        shutdown:
        try {
            if (executor == null) break shutdown;
            executor.shutdown();
            if (executor.awaitTermination(30L, TimeUnit.SECONDS)) break shutdown;
            LOGGER.warn("IAS: Unable to shutdown IAS executor. Shutting down forcefully...");
            executor.shutdownNow();
            if (executor.awaitTermination(30L, TimeUnit.SECONDS)) break shutdown;
            LOGGER.error("IAS: Unable to shutdown IAS executor forcefully.");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;

        // Destroy the UA.
        userAgent = null;

        // Write the disclaimers, if we can.
        if (gameDirectory != null) {
            try {
                disclaimersStorage();
            } catch (Throwable ignored) {
                // NO-OP
            }
        }
    }

    /**
     * Gets the async executor for IAS.
     *
     * @return IAS executor
     * @throws NullPointerException If the executor is not available
     */
    public static ScheduledExecutorService executor() {
        Objects.requireNonNull(executor, "IAS executor is not available.");
        return executor;
    }

    /**
     * Gets the user agent for usage in {@link MSAuth}.
     *
     * @return Current {@code User-Agent} value for HTTP requests
     * @throws NullPointerException If user agent wasn't set
     */
    public static String userAgent() {
        Objects.requireNonNull(userAgent, "IAS user agent is not set.");
        return userAgent;
    }

    /**
     * Gets the disabled state.
     *
     * @return Whether the mod is disabled remotely
     */
    public static boolean disabled() {
        return disabled;
    }

    /**
     * Delegates to {@link IASConfig#load(Path)} with {@link #configDirectory}.
     *
     * @throws RuntimeException If unable to load the config
     */
    public static void loadConfig() {
        IASConfig.load(configDirectory);
    }

    /**
     * Delegates to {@link IASConfig#save(Path)} with {@link #configDirectory}.
     *
     * @throws RuntimeException If unable to save the config
     */
    public static void saveConfig() {
        IASConfig.save(configDirectory);
    }

    /**
     * Delegates to {@link IASStorage#load(Path)} with {@link #gameDirectory}.
     *
     * @throws RuntimeException If unable to load the storage
     */
    public static void loadStorage() {
        IASStorage.load(gameDirectory);
    }

    /**
     * Delegates to {@link IASStorage#save(Path)} with {@link #gameDirectory}.
     *
     * @throws RuntimeException If unable to save the storage
     */
    public static void saveStorage() {
        IASStorage.save(gameDirectory);
    }

    /**
     * Delegates to {@link IASStorage#disclaimers(Path)} with {@link #gameDirectory}.
     *
     * @throws RuntimeException If unable to write the disclaimers
     */
    public static void disclaimersStorage() {
        IASStorage.disclaimers(gameDirectory);
    }

    /**
     * Delegates to {@link IASStorage#gameDisclaimerShown(Path)} with {@link #gameDirectory}.
     *
     * @throws RuntimeException If unable to set or write game disclaimer shown persistent state
     */
    public static void gameDisclaimerShownStorage() {
        IASStorage.gameDisclaimerShown(gameDirectory);
    }
}
