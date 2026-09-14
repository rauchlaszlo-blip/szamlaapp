package hu.rauch.szamlakezelo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

@CapacitorPlugin(name = "InvoiceAttachment")
public class InvoiceAttachmentPlugin extends Plugin {
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private File pendingCameraFile;

    @PluginMethod
    public void pickFiles(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(call, intent, "pickFilesResult");
    }

    @ActivityCallback
    private void pickFilesResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("A fájlválasztás megszakadt.");
            return;
        }
        try {
            Intent data = result.getData();
            JSArray files = new JSArray();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) {
                    files.put(copyIntoAppStorage(clip.getItemAt(i).getUri()));
                }
            } else if (data.getData() != null) {
                files.put(copyIntoAppStorage(data.getData()));
            }
            JSObject resultData = new JSObject();
            resultData.put("files", files);
            call.resolve(resultData);
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "A fájl mentése nem sikerült." : error.getMessage(), error);
        }
    }

    @PluginMethod
    public void capturePhoto(PluginCall call) {
        try {
            File directory = attachmentDirectory();
            pendingCameraFile = new File(directory, UUID.randomUUID().toString() + ".jpg");
            if (!pendingCameraFile.createNewFile()) {
                call.reject("Nem sikerült létrehozni a fényképfájlt.");
                return;
            }
            Uri uri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                pendingCameraFile
            );
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (intent.resolveActivity(getContext().getPackageManager()) == null) {
                pendingCameraFile.delete();
                pendingCameraFile = null;
                call.reject("Nem található kameraalkalmazás.");
                return;
            }
            startActivityForResult(call, intent, "capturePhotoResult");
        } catch (Exception error) {
            call.reject("A kamera nem indítható el.", error);
        }
    }

    @ActivityCallback
    private void capturePhotoResult(PluginCall call, ActivityResult result) {
        if (result.getResultCode() != Activity.RESULT_OK || pendingCameraFile == null) {
            if (pendingCameraFile != null) pendingCameraFile.delete();
            pendingCameraFile = null;
            call.reject("A fényképezés megszakadt.");
            return;
        }
        JSObject file = fileInfo(pendingCameraFile, "Számla " + System.currentTimeMillis() + ".jpg", "image/jpeg");
        pendingCameraFile = null;
        call.resolve(file);
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        try {
            File file = resolveStoredFile(call.getString("fileName"));
            if (!file.exists()) {
                call.reject("A csatolmány nem található.");
                return;
            }
            Uri uri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                file
            );
            String mimeType = call.getString("mimeType", "*/*");
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(getContext().getPackageManager()) == null) {
                call.reject("Nincs alkalmazás, amely meg tudná nyitni ezt a fájlt.");
                return;
            }
            getContext().startActivity(intent);
            call.resolve();
        } catch (Exception error) {
            call.reject("A csatolmány nem nyitható meg.", error);
        }
    }

    @PluginMethod
    public void deleteFile(PluginCall call) {
        try {
            File file = resolveStoredFile(call.getString("fileName"));
            if (!file.exists() || file.delete()) {
                call.resolve();
            } else {
                call.reject("A csatolmány törlése nem sikerült.");
            }
        } catch (Exception error) {
            call.reject("A csatolmány törlése nem sikerült.", error);
        }
    }

    private JSObject copyIntoAppStorage(Uri uri) throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        String originalName = displayName(uri);
        String mimeType = resolver.getType(uri);
        if (mimeType == null) mimeType = "application/octet-stream";
        if (!mimeType.startsWith("image/") && !"application/pdf".equals(mimeType)) {
            throw new Exception("Csak kép vagy PDF csatolható.");
        }
        String extension = extensionOf(originalName, mimeType);
        File target = new File(attachmentDirectory(), UUID.randomUUID().toString() + extension);
        long copied = 0;
        try (InputStream input = resolver.openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target)) {
            if (input == null) throw new Exception("A kiválasztott fájl nem olvasható.");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > MAX_FILE_SIZE) {
                    throw new Exception("Egy csatolmány legfeljebb 20 MB lehet.");
                }
                output.write(buffer, 0, count);
            }
        } catch (Exception error) {
            target.delete();
            throw error;
        }
        return fileInfo(target, originalName, mimeType);
    }

    private File attachmentDirectory() throws Exception {
        File directory = new File(getContext().getFilesDir(), "invoice_attachments");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new Exception("A csatolmányok mappája nem hozható létre.");
        }
        return directory;
    }

    private File resolveStoredFile(String fileName) throws Exception {
        if (fileName == null || !fileName.equals(new File(fileName).getName())) {
            throw new Exception("Érvénytelen fájlnév.");
        }
        return new File(attachmentDirectory(), fileName);
    }

    private JSObject fileInfo(File file, String displayName, String mimeType) {
        JSObject info = new JSObject();
        info.put("fileName", file.getName());
        info.put("name", displayName);
        info.put("mimeType", mimeType);
        info.put("size", file.length());
        return info;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor cursor = getContext().getContentResolver().query(
            uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
        }
        return name == null || name.trim().isEmpty() ? "csatolmany" : name;
    }

    private String extensionOf(String name, String mimeType) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot).toLowerCase();
            if (ext.matches("\\.[a-z0-9]{1,8}")) return ext;
        }
        if ("application/pdf".equals(mimeType)) return ".pdf";
        if ("image/png".equals(mimeType)) return ".png";
        if ("image/webp".equals(mimeType)) return ".webp";
        return ".jpg";
    }
}
