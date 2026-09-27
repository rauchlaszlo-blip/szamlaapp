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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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

    @PluginMethod
    public void restoreAttachments(PluginCall call) {
        if (call.getString("content") == null) { call.reject("Hiányzó mentési tartalom."); return; }
        authorize(call, "restoreAttachments");
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
                } else if ("restoreAttachments".equals(operation)) {
                    result = restoreArchive(accessToken, call.getString("content"));
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
        JSONObject payload = new JSONObject(content);
        if (!"Számlakezelő".equals(payload.optString("app")) || payload.optInt("schemaVersion") != 2)
            throw new IOException("Érvénytelen mentési adatok.");
        Set<String> names = referencedFiles(payload);
        String folderId = findFolder(token);
        if (folderId == null) folderId = createFolder(token);
        File archive = null;
        try {
            if (!names.isEmpty()) {
                archive = buildArchive(names);
                String sha = digest(archive);
                String archiveName = "szamlakezelo_files_" + sha + ".zip";
                String existing = findNamedFile(token, folderId, archiveName);
                if (existing == null) {
                    JSONObject metadata = new JSONObject();
                    metadata.put("name", archiveName);
                    metadata.put("mimeType", "application/zip");
                    metadata.put("parents", new JSONArray().put(folderId));
                    existing = requestJson("POST", DRIVE_API + "?fields=id", token, metadata.toString(), "application/json").getString("id");
                }
                // A complete content-addressed archive need not be uploaded again.
                JSONObject remote = requestJson("GET", DRIVE_API + "/" + existing + "?fields=size,md5Checksum", token, null, null);
                if (remote.optLong("size", -1) != archive.length()
                    || !remote.optString("md5Checksum").equals(digest(archive, "MD5")))
                    uploadMedia(token, existing, archive, "application/zip");
                JSONObject manifest = new JSONObject();
                manifest.put("name", archiveName);
                manifest.put("sha256", sha);
                manifest.put("size", archive.length());
                manifest.put("count", names.size());
                payload.put("attachmentArchive", manifest);
            } else {
                payload.put("attachmentArchive", JSONObject.NULL);
            }
            String fileId = findBackupFile(token, folderId);
            if (fileId == null) {
                JSONObject metadata = new JSONObject();
                metadata.put("name", BACKUP_FILE_NAME);
                metadata.put("mimeType", "application/json");
                metadata.put("parents", new JSONArray().put(folderId));
                fileId = requestJson("POST", DRIVE_API + "?fields=id", token, metadata.toString(), "application/json").getString("id");
            }
            // Update the reference only after the complete archive is on Drive.
            request("PATCH", DRIVE_UPLOAD_API + "/" + fileId + "?uploadType=media", token,
                payload.toString(), "application/json; charset=UTF-8");
            JSObject result = new JSObject();
            result.put("fileId", fileId);
            result.put("attachmentCount", names.size());
            return result;
        } finally {
            if (archive != null) archive.delete();
        }
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
        String url = DRIVE_API + "?spaces=drive&fields=nextPageToken,files(id)&pageSize=100&q=" +
            URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String first = null;
        while (url != null) {
            JSONObject page = requestJson("GET", url, token, null, null);
            JSONArray files = page.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                String id = files.getJSONObject(i).getString("id");
                if (first == null) first = id;
                if (findBackupFile(token, id) != null) return id;
            }
            String next = page.optString("nextPageToken", "");
            url = next.isEmpty() ? null : DRIVE_API + "?spaces=drive&fields=nextPageToken,files(id)&pageSize=100&pageToken=" +
                URLEncoder.encode(next, StandardCharsets.UTF_8.name()) + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        }
        return first;
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

    private String findNamedFile(String token, String folderId, String name) throws Exception {
        String query = "name='" + name + "' and '" + folderId + "' in parents and trashed=false";
        return firstFileId(token, query);
    }

    private Set<String> referencedFiles(JSONObject payload) throws IOException {
        Set<String> names = new TreeSet<>();
        JSONArray data = payload.optJSONArray("data");
        if (data == null) throw new IOException("Hiányzó számlalista.");
        for (int i = 0; i < data.length(); i++) {
            JSONObject record = data.optJSONObject(i);
            if (record == null) throw new IOException("Érvénytelen számlabejegyzés.");
            JSONArray files = record.optJSONArray("attachments");
            if (files == null) continue;
            for (int j = 0; j < files.length(); j++) addName(names, files.optJSONObject(j));
        }
        JSONArray inbox = payload.optJSONArray("inbox");
        if (inbox != null) for (int i = 0; i < inbox.length(); i++) {
            JSONObject record = inbox.optJSONObject(i);
            if (record == null) throw new IOException("Érvénytelen feldolgozatlan számla.");
            addName(names, record.optJSONObject("file"));
        }
        return names;
    }

    private void addName(Set<String> names, JSONObject info) throws IOException {
        String name = info == null ? "" : info.optString("fileName", "");
        if (name.isEmpty() || !name.equals(new File(name).getName()) || name.contains("\\") || name.contains(".."))
            throw new IOException("Érvénytelen csatolmányhivatkozás.");
        names.add(name);
    }

    private File attachmentFile(String name) throws IOException {
        if (name.isEmpty() || !name.equals(new File(name).getName()) || name.contains("\\") || name.contains(".."))
            throw new IOException("Érvénytelen csatolmányfájlnév.");
        File directory = new File(getContext().getFilesDir(), "invoice_attachments");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("A csatolmányok mappája nem hozható létre.");
        return new File(directory, name);
    }

    private File buildArchive(Set<String> names) throws Exception {
        File zip = File.createTempFile("szamlakezelo-", ".zip", getContext().getCacheDir());
        long total = 0;
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : names) {
                File file = attachmentFile(name);
                if (!file.isFile()) throw new IOException("Hiányzó csatolmány: " + name);
                total += file.length();
                if (file.length() > 20L * 1024 * 1024 || total > 512L * 1024 * 1024)
                    throw new IOException("A csatolmánymentés méretkorlátját elérte.");
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                try (InputStream input = new FileInputStream(file)) { copy(input, output); }
                output.closeEntry();
            }
        } catch (Exception error) { zip.delete(); throw error; }
        return zip;
    }

    private String digest(File file) throws Exception { return digest(file, "SHA-256"); }

    private String digest(File file, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
    }

    private void uploadMedia(String token, String fileId, File file, String type) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(DRIVE_UPLOAD_API + "/" + fileId + "?uploadType=media").openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", type);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(file.length());
        try (InputStream input = new FileInputStream(file); OutputStream output = connection.getOutputStream()) { copy(input, output); }
        int status = connection.getResponseCode();
        String response = readFully(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        connection.disconnect();
        if (status < 200 || status >= 300) throw new IOException("Drive csatolmányfeltöltés HTTP " + status + ": " + response);
    }

    private void downloadMedia(String token, String fileId, File target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(DRIVE_API + "/" + fileId + "?alt=media").openConnection();
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(120000);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String response = readFully(connection.getErrorStream()); connection.disconnect();
            throw new IOException("Drive csatolmányletöltés HTTP " + status + ": " + response);
        }
        try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192]; int count; long size = 0;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > 512L * 1024 * 1024 + 1024 * 1024) throw new IOException("A csatolmányarchívum túl nagy.");
                output.write(buffer, 0, count);
            }
        }
        finally { connection.disconnect(); }
    }

    private JSObject restoreArchive(String token, String content) throws Exception {
        JSONObject payload = new JSONObject(content);
        Set<String> expected = referencedFiles(payload);
        JSONObject manifest = payload.optJSONObject("attachmentArchive");
        if (expected.isEmpty()) { JSObject done = new JSObject(); done.put("restored", 0); return done; }
        if (manifest == null || !manifest.optString("sha256").matches("[0-9a-f]{64}")
            || !manifest.optString("name").equals("szamlakezelo_files_" + manifest.optString("sha256") + ".zip")
            || manifest.optInt("count") != expected.size()
            || manifest.optLong("size") <= 0 || manifest.optLong("size") > 512L * 1024 * 1024 + 1024 * 1024)
            throw new IOException("A mentés nem tartalmazza az összes csatolmányt.");
        String folderId = findFolder(token);
        String id = folderId == null ? null : findNamedFile(token, folderId, manifest.getString("name"));
        if (id == null) throw new IOException("A mentés csatolmányarchívuma hiányzik.");
        File zip = File.createTempFile("szamlakezelo-restore-", ".zip", getContext().getCacheDir());
        File staging = new File(getContext().getCacheDir(), "szamlakezelo-stage-" + UUID.randomUUID());
        if (!staging.mkdirs()) throw new IOException("A helyreállítási mappa nem hozható létre.");
        List<File> installed = new ArrayList<>();
        try {
            downloadMedia(token, id, zip);
            if (zip.length() != manifest.getLong("size") || !digest(zip).equals(manifest.getString("sha256")))
                throw new IOException("A letöltött csatolmányarchívum sérült.");
            Set<String> extracted = new TreeSet<>();
            long total = 0;
            try (ZipInputStream input = new ZipInputStream(new FileInputStream(zip))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (!expected.contains(name) || entry.isDirectory() || !extracted.add(name))
                        throw new IOException("Érvénytelen fájl az archívumban.");
                    File staged = new File(staging, name);
                    try (OutputStream output = new FileOutputStream(staged)) {
                        byte[] buffer = new byte[8192]; int count; long size = 0;
                        while ((count = input.read(buffer)) != -1) {
                            size += count; total += count;
                            if (size > 20L * 1024 * 1024 || total > 512L * 1024 * 1024)
                                throw new IOException("A csatolmánymentés méretkorlátját elérte.");
                            output.write(buffer, 0, count);
                        }
                    }
                    input.closeEntry();
                }
            }
            if (!extracted.equals(expected)) throw new IOException("Hiányzó csatolmány az archívumban.");
            // Check every collision before changing any local file.
            for (String name : expected) {
                File target = attachmentFile(name);
                if (target.exists() && !digest(target).equals(digest(new File(staging, name))))
                    throw new IOException("Eltérő helyi csatolmány: " + name);
            }
            for (String name : expected) {
                File target = attachmentFile(name);
                if (target.exists()) continue;
                if (!new File(staging, name).renameTo(target)) throw new IOException("A csatolmány helyreállítása nem sikerült: " + name);
                installed.add(target);
            }
            JSObject done = new JSObject(); done.put("restored", expected.size()); return done;
        } catch (Exception error) {
            for (File file : installed) file.delete();
            throw error;
        } finally {
            zip.delete();
            File[] leftovers = staging.listFiles();
            if (leftovers != null) for (File file : leftovers) file.delete();
            staging.delete();
        }
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
