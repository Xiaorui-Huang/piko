/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
*/


package app.morphe.extension.crimera.downloader;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.crimera.constants.ExtensionStrings;

public class FolderPickerActivity extends AppCompatActivity {

    private static final int FOLDER_REQUEST_CODE = 43;
    private static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A recreated activity is still waiting on the picker it already launched.
        if (savedInstanceState == null) {
            requestFolderPermission();
        }
    }

    public void requestFolderPermission() {
        try {
            startActivityForResult(buildTreeIntent(), FOLDER_REQUEST_CODE);
        } catch (ActivityNotFoundException e) {
            Logger.printException(() -> "No folder picker available", e);
            toast(ExtensionStrings.DOWNLOAD_GRANT_PERMISSION_FAILED);
            finish();
        }
    }

    private Intent buildTreeIntent() {
        Intent intent = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            StorageManager storageManager = getSystemService(StorageManager.class);
            if (storageManager != null) {
                // The platform-built intent also enables the picker's advanced (device storage) roots.
                intent = storageManager.getPrimaryStorageVolume().createOpenDocumentTreeIntent();
            }
        }
        if (intent == null) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }

        String initialDocumentId = "primary:" + StorageUtils.ensureDefaultDownloadFolder();
        intent.putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, initialDocumentId)
        );
        return intent;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FOLDER_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                try {
                    int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (flags == 0) {
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    }
                    getContentResolver().takePersistableUriPermission(treeUri, flags);

                    StorageUtils.saveCustomTreeUri(treeUri);
                    StorageUtils.saveCustomPath(DocumentsContract.getTreeDocumentId(treeUri));
                    toast(ExtensionStrings.DOWNLOAD_SET_PATH_SUCCESS);
                } catch (Exception e) {
                    Logger.printException(() -> "setting path failure", e);
                    toast(ExtensionStrings.DOWNLOAD_SET_PATH_FAILED);
                }
            }
        }
        // Always finish the activity after the result is handled to return to the previous screen
        finish();
    }

    private void toast(String msg) {
        Utils.showToastShort(msg);
    }
}
