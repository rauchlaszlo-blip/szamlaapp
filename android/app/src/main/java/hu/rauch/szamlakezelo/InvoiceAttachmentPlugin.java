package hu.rauch.szamlakezelo;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "InvoiceAttachment")
public class InvoiceAttachmentPlugin extends Plugin {
    private static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final ExecutorService OCR_EXECUTOR = Executors.newSingleThreadExecutor();
    private File pendingCameraFile;

    @PluginMethod
    public void getPreview(PluginCall call) {
        OCR_EXECUTOR.execute(() -> {
            try {
                File file = resolveStoredFile(call.getString("fileName"));
                String mimeType = call.getString("mimeType", "");
                Bitmap bitmap = "application/pdf".equals(mimeType)
                    ? renderPdfPreview(file) : decodePreviewImage(file);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output);
                bitmap.recycle();
                JSObject result = new JSObject();
                result.put("dataUrl", "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
                call.resolve(result);
            } catch (Exception error) {
                call.reject("A számla előnézete nem készíthető el.", error);
            }
        });
    }

    private Bitmap decodePreviewImage(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 2200 || bounds.outHeight / sample > 2200) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new Exception("A kép nem olvasható.");
        return bitmap;
    }

    private Bitmap renderPdfPreview(File file) throws Exception {
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            if (renderer.getPageCount() == 0) throw new Exception("A PDF üres.");
            try (PdfRenderer.Page page = renderer.openPage(0)) {
                float scale = Math.min(3f, 2200f / Math.max(page.getWidth(), page.getHeight()));
                Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)), Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(android.graphics.Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bitmap;
            }
        }
    }

    @PluginMethod
    public void recognizeText(PluginCall call) {
        final String fileName = call.getString("fileName");
        final String mimeType = call.getString("mimeType", "");
        OCR_EXECUTOR.execute(() -> {
            TextRecognizer recognizer = null;
            try {
                File file = resolveStoredFile(fileName);
                if (!file.exists()) throw new Exception("A csatolmány nem található.");
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                String text = "application/pdf".equals(mimeType)
                    ? recognizePdf(file, recognizer)
                    : recognizeImage(file, recognizer);
                JSObject result = new JSObject();
                result.put("text", text.trim());
                call.resolve(result);
            } catch (Exception error) {
                call.reject(error.getMessage() == null ? "A szövegfelismerés nem sikerült." : error.getMessage(), error);
            } finally {
                if (recognizer != null) recognizer.close();
            }
        });
    }

    private String recognizeImage(File file, TextRecognizer recognizer) throws Exception {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) throw new Exception("A kép nem olvasható.");
        try {
            return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
        } finally {
            bitmap.recycle();
        }
    }

    private String recognizePdf(File file, TextRecognizer recognizer) throws Exception {
        StringBuilder output = new StringBuilder();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int pages = Math.min(renderer.getPageCount(), 10);
            for (int index = 0; index < pages; index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    float scale = Math.min(2.5f, 2200f / Math.max(page.getWidth(), page.getHeight()));
                    int width = Math.max(1, Math.round(page.getWidth() * scale));
                    int height = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String pageText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
                        if (!pageText.trim().isEmpty()) {
                            if (output.length() > 0) output.append("\n\n");
                            output.append(pageText.trim());
                        }
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
            if (renderer.getPageCount() > 10) output.append("\n\n[Az első 10 oldal szövege]");
        }
        return output.toString();
    }

    @PluginMethod
    @SuppressWarnings("deprecation")
    public void consumeSharedFiles(PluginCall call) {
        Intent intent = getActivity().getIntent();
        JSArray files = new JSArray();
        if (intent == null) {
            JSObject empty = new JSObject();
            empty.put("files", files);
            call.resolve(empty);
            return;
        }

        String action = intent.getAction();
        Set<Uri> uris = new LinkedHashSet<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> shared = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (shared != null) uris.addAll(shared);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        }

        if (uris.isEmpty()) {
            JSObject empty = new JSObject();
            empty.put("files", files);
            call.resolve(empty);
            return;
        }

        try {
            for (Uri uri : uris) files.put(copyIntoAppStorage(uri));
            getActivity().setIntent(new Intent());
            JSObject result = new JSObject();
            result.put("files", files);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage() == null ? "A megosztott számla mentése nem sikerült." : error.getMessage(), error);
        }
    }

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
