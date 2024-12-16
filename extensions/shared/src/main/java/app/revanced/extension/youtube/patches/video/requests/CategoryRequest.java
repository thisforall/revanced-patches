package app.revanced.extension.youtube.patches.video.requests;

import static app.revanced.extension.shared.patches.spoof.requests.PlayerRoutes.GET_CATEGORY;

import android.annotation.SuppressLint;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.revanced.extension.shared.patches.client.AppClient.ClientType;
import app.revanced.extension.shared.patches.spoof.requests.PlayerRoutes;
import app.revanced.extension.shared.requests.Requester;
import app.revanced.extension.shared.utils.Logger;
import app.revanced.extension.shared.utils.Utils;

public class CategoryRequest {

    /**
     * How long to keep fetches until they are expired.
     */
    private static final long CACHE_RETENTION_TIME_MILLISECONDS = 60 * 1000; // 1 Minute

    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000; // 20 seconds

    @GuardedBy("itself")
    private static final Map<String, CategoryRequest> cache = new HashMap<>();

    @SuppressLint("ObsoleteSdkInt")
    public static void fetchRequestIfNeeded(@Nullable String videoId) {
        Objects.requireNonNull(videoId);
        synchronized (cache) {
            final long now = System.currentTimeMillis();

            cache.values().removeIf(request -> {
                final boolean expired = request.isExpired(now);
                if (expired) Logger.printDebug(() -> "Removing expired stream: " + request.videoId);
                return expired;
            });

            if (!cache.containsKey(videoId)) {
                cache.put(videoId, new CategoryRequest(videoId));
            }
        }
    }

    @Nullable
    public static CategoryRequest getRequestForVideoId(@Nullable String videoId) {
        synchronized (cache) {
            return cache.get(videoId);
        }
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static JSONObject send(ClientType clientType, String videoId) {
        Objects.requireNonNull(clientType);
        Objects.requireNonNull(videoId);

        final long startTime = System.currentTimeMillis();
        String clientTypeName = clientType.name();
        Logger.printDebug(() -> "Fetching category request for: " + videoId + " using client: " + clientTypeName);

        try {
            HttpURLConnection connection = PlayerRoutes.getPlayerResponseConnectionFromRoute(GET_CATEGORY, clientType);

            String innerTubeBody = String.format(PlayerRoutes.createInnertubeBody(clientType), videoId);

            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == 200) return Requester.parseJSONObject(connection);

            handleConnectionError(clientTypeName + " not available with response code: "
                            + responseCode + " message: " + connection.getResponseMessage(),
                    null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "send failed", ex);
        } finally {
            Logger.printDebug(() -> "video: " + videoId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    private static Boolean fetch(@NonNull String videoId) {
        final JSONObject microFormatJson = send(ClientType.WEB, videoId);
        if (microformatJson != null) {
            try {
                return microFormatJson.getJSONObject("microformat")
                                      .getJSONObject("playerMicroformatRenderer")
                                      .getString("category")
                                      .equals("Music");
            } catch (JSONException e) {
                Logger.printDebug(() -> "Fetch failed while processing response data for response: " + microFormatJson);
            }
        }

        return false;
    }

    /**
     * Time this instance and the fetch future was created.
     */
    private final long timeFetched;
    private final String videoId;
    private final Future<Boolean> future;

    private CategoryRequest(String videoId) {
        this.timeFetched = System.currentTimeMillis();
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId));
    }

    public boolean isExpired(long now) {
        final long timeSinceCreation = now - timeFetched;
        if (timeSinceCreation > CACHE_RETENTION_TIME_MILLISECONDS) {
            return true;
        }

        // Only expired if the fetch failed (API null response).
        return (fetchCompleted() && getStream() == null);
    }

    /**
     * @return if the fetch call has completed.
     */
    public boolean fetchCompleted() {
        return future.isDone();
    }

    public Boolean getStream() {
        try {
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getStream timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getStream interrupted", ex);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getStream failure", ex);
        }

        return null;
    }
}
