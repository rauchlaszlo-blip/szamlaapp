package hu.rauch.szamlakezelo;

import android.app.Activity;
import android.content.Intent;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(name = "DriveBackup")
public class DriveBackupPlugin extends Plugin {
    private static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    private static final String DRIVE_API = "https://www.googleapis.com/drive/v3/files";
    private static final String DRIVE_UPLOAD_API = "https://www.googleapis.com/upload/drive/v3/files";
    private static final String FOLDER_NAME = "Számlakezelő";
    private static final String BACKUP_FILE_NAME = "szamlakezelo_backup.json";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile String pendingOperation;

    @PluginMethod
    public void backup(PluginCall call) {
        String content = call.getString("content");
        if (content == null) {
            call.reject("Hiányzó mentési tartalom.", "INVALID_BACKUP");
            return;
        }
        authorize(call, "backup");
    }

    @PluginMethod
    public void restore(PluginCall call) {
        authorize(call, "restore");
    }

    private void authorize(PluginCall call, String operation) {
        if (pendingOperation != null) {
            call.reject("Egy Drive művelet már folyamatban van.", "DRIVE_BUSY");
            return;
        }
        pendingOperation = operation;
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(Collections.singletonList(new Scope(DRIVE_SCOPE)))
            .build();
        AuthorizationClient client = Identity.getAuthorizationClient(getActivity());
        client.authorize(request)
            .addOnSuccessListener(result -> {
                if (result.hasResolution()) {
                    Intent intent = new Intent(getContext(), DriveAuthorizationActivity.class);
                    intent.putExtra(DriveAuthorizationActivity.EXTRA_PENDING_INTENT, result.getPendingIntent());
                    startActivityForResult(call, intent, "authorizationResult");
                } else {
                    runOperation(call, result.getAccessToken());
                }
            })
            .addOnFailureListener(error -> reject(call, "Google Drive engedélyezési hiba.", error));
    }

    @ActivityCallback
    private void authorizationResult(PluginCall call, ActivityResult activityResult) {
        if (call == null) return;
        if (activityResult.getResultCode() != Activity.RESULT_OK || activityResult.getData() == null) {
            reject(call, "A Google Drive hozzáférés nem lett engedélyezve.", null);
            return;
        }
        try {
            AuthorizationResult result = Identity.getAuthorizationClient(getActivity())
                .getAuthorizationResultFromIntent(activityResult.getData());
            runOperation(call, result.getAccessToken());
        } catch (ApiException error) {
            reject(call, "Google Drive engedélyezési hiba.", error);
        }
    }

    private void runOperation(PluginCall call, String accessToken) {
        if (accessToken == null || accessToken.isEmpty()) {
            reject(call, "Nem érkezett Google hozzáférési token.", null);
            return;
        }
        String operation = pendingOperation;
        executor.execute(() -> {
            try {
                JSObject result;
                if ("backup".equals(operation)) {
                    result = uploadBackup(accessToken, call.getString("content"));
                } else {
                    result = downloadBackup(accessToken);
                }
                pendingOperation = null;
                call.resolve(result);
            } catch (Exception error) {
                reject(call, "Google Drive műveleti hiba: " + error.getMessage(), error);
            }
        });
    }

    private JSObject uploadBackup(String token, String content) throws Exception {
        String folderId = findFolder(token);
        if (folderId == null) folderId = createFolder(token);
        String fileId = findBackupFile(token, folderId);
        if (fileId == null) {
            JSONObject metadata = new JSONObject();
            metadata.put("name", BACKUP_FILE_NAME);
            metadata.put("mimeType", "application/json");
            metadata.put("parents", new JSONArray().put(folderId));
            JSONObject created = requestJson("POST", DRIVE_API + "?fields=id", token, metadata.toString(), "application/json");
            fileId = created.getString("id");
        }
        request("PATCH", DRIVE_UPLOAD_API + "/" + fileId + "?uploadType=media", token, content, "application/json; charset=UTF-8");
        JSObject result = new JSObject();
        result.put("fileId", fileId);
        return result;
    }

    private JSObject downloadBackup(String token) throws Exception {
        String folderId = findFolder(token);
        if (folderId == null) throw new IOException("A Számlakezelő mappa nem található.");
        String fileId = findBackupFile(token, folderId);
        if (fileId == null) throw new IOException("A szamlakezelo_backup.json fájl nem található.");
        String content = request("GET", DRIVE_API + "/" + fileId + "?alt=media", token, null, null);
        new JSONObject(content);
        JSObject result = new JSObject();
        result.put("content", content);
        result.put("fileId", fileId);
        return result;
    }

    private String findFolder(String token) throws Exception {
        String query = "name='" + FOLDER_NAME + "' and mimeType='application/vnd.google-apps.folder' and 'root' in parents and trashed=false";
        return firstFileId(token, query);
    }

    private String createFolder(String token) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("name", FOLDER_NAME);
        metadata.put("mimeType", "application/vnd.google-apps.folder");
        metadata.put("parents", new JSONArray().put("root"));
        return requestJson("POST", DRIVE_API + "?fields=id", token, metadata.toString(), "application/json").getString("id");
    }

    private String findBackupFile(String token, String folderId) throws Exception {
        String query = "name='" + BACKUP_FILE_NAME + "' and '" + folderId + "' in parents and trashed=false";
        return firstFileId(token, query);
    }

    private String firstFileId(String token, String query) throws Exception {
        String url = DRIVE_API + "?spaces=drive&fields=files(id)&pageSize=1&q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        JSONArray files = requestJson("GET", url, token, null, null).getJSONArray("files");
        return files.length() == 0 ? null : files.getJSONObject(0).getString("id");
    }

    private JSONObject requestJson(String method, String url, String token, String body, String contentType) throws Exception {
        return new JSONObject(request(method, url, token, body, contentType));
    }

    private String request(String method, String url, String token, String body, String contentType) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        if ("PATCH".equals(method)) {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            connection.setRequestMethod(method);
        }
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readFully(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("Drive API HTTP " + status + (response.isEmpty() ? "" : ": " + response));
        }
        return response;
    }

    private String readFully(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void reject(PluginCall call, String message, Exception error) {
        pendingOperation = null;
        if (error == null) call.reject(message);
        else call.reject(message, error);
    }

    @Override
    protected void handleOnDestroy() {
        executor.shutdownNow();
    }
}
