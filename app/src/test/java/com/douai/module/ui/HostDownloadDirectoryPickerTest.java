package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HostDownloadDirectoryPickerTest {
    private static final Uri TREE = Uri.parse("content://directories/tree/primary%3ADownload%2FDouAi");
    private static final int TREE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    private DirectoryProvider provider;

    @Before public void registerDirectoryProvider() {
        provider = new DirectoryProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "directories";
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        ShadowContentResolver.registerProviderInternal("directories", provider);
    }

    @Test public void requestsDocumentTreeAndPersistsHostReadWritePermissionAfterSelection() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, TREE, selected::set, failure::set);
        try {
            assertNotNull(picker);
            assertTrue(HostDownloadDirectoryPicker.isPending(activity));
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, activity.registry.intent.getAction());
            assertEquals("androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult",
                    activity.registry.contractName);
            assertEquals(TREE_FLAGS, activity.registry.intent.getFlags() & TREE_FLAGS);
            assertEquals(TREE, activity.registry.intent.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI));
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                    treeResult());
            assertEquals(TREE, selected.get());
            assertNull(failure.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
            assertTrue(activity.getContentResolver().getPersistedUriPermissions().stream().anyMatch(
                    permission -> TREE.equals(permission.getUri()) && permission.isReadPermission() && permission.isWritePermission()));
            picker.close();
        } finally { activity.finish(); }
    }

    @Test public void worksWhenHostHasNoOpenDocumentTreeAndFailsCleanlyWithoutGeneralIntentContract() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        ClassLoader hostWithoutTree = excluding(activity.getClass().getClassLoader(),
                "androidx.activity.result.contract.ActivityResultContracts$OpenDocumentTree");
        try {
            HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null,
                    selected::set, ignored -> fail(), hostWithoutTree);
            assertNotNull(picker);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, activity.registry.intent.getAction());
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, treeResult());
            assertEquals(TREE, selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));

            ClassLoader hostWithoutContract = excluding(activity.getClass().getClassLoader(),
                    "androidx.activity.result.contract.ActivityResultContracts$StartActivityForResult");
            selected.set(null);
            assertNull(HostDownloadDirectoryPicker.launch(activity, null, selected::set,
                    ignored -> fail(), hostWithoutContract));
            assertNull(selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
        } finally { activity.finish(); }
    }

    @Test public void cancellationDoesNotChooseDirectoryAndReleasesPendingRegistration() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>(TREE);
        HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null, selected::set, ignored -> fail());
        try {
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_CANCELED, treeResult());
            assertNull(selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
            picker.close();
        } finally { activity.finish(); }
    }

    @Test public void missingOrUnexpectedActivityResultsDoNotSelectDirectory() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>(TREE);
        try {
            HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null,
                    selected::set, ignored -> fail());
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, null);
            assertNull(selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));

            selected.set(TREE);
            picker = HostDownloadDirectoryPicker.launch(activity, null, selected::set, ignored -> fail());
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.requestCode, new Object());
            assertNull(selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
            assertTrue(activity.getContentResolver().getPersistedUriPermissions().isEmpty());
        } finally { activity.finish(); }
    }

    @Test public void rejectsMissingReadWriteOrPersistableResultGrants() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        try {
            for (int missing : new int[]{Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION}) {
                failed.set(null);
                HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null,
                        selected::set, failed::set);
                assertNotNull(picker);
                activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK,
                        new Intent().setData(TREE).addFlags(TREE_FLAGS & ~missing));
                assertNull(selected.get());
                assertNotNull(failed.get());
                assertFalse(HostDownloadDirectoryPicker.isPending(activity));
                assertTrue(activity.getContentResolver().getPersistedUriPermissions().isEmpty());
            }
        } finally { activity.finish(); }
    }

    @Test public void closePreventsLateSelectionAndUnavailableRegistryLeavesNoPendingPicker() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null, selected::set, ignored -> fail());
        try {
            assertNotNull(picker);
            picker.close();
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, treeResult());
            assertNull(selected.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
        } finally { activity.finish(); }
        Activity plain = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            assertNull(HostDownloadDirectoryPicker.launch(plain, null, ignored -> fail(), ignored -> fail()));
            assertFalse(HostDownloadDirectoryPicker.isPending(plain));
        } finally { plain.finish(); }
    }

    @Test public void rejectsReadOnlyDirectoriesAndFilesBeforePersistingPermission() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        AtomicReference<String> failed = new AtomicReference<>();
        try {
            provider.flags = 0;
            HostDownloadDirectoryPicker picker = HostDownloadDirectoryPicker.launch(activity, null, selected::set, failed::set);
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, treeResult());
            assertNull(selected.get());
            assertNotNull(failed.get());
            assertFalse(HostDownloadDirectoryPicker.isPending(activity));
            assertTrue(activity.getContentResolver().getPersistedUriPermissions().isEmpty());

            failed.set(null);
            provider.mime = "video/mp4";
            provider.flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
            picker = HostDownloadDirectoryPicker.launch(activity, null, selected::set, failed::set);
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.requestCode, Activity.RESULT_OK, treeResult());
            assertNull(selected.get());
            assertNotNull(failed.get());
            assertTrue(activity.getContentResolver().getPersistedUriPermissions().isEmpty());
        } finally { activity.finish(); }
    }

    private static Intent treeResult() { return new Intent().setData(TREE).addFlags(TREE_FLAGS); }

    private static ClassLoader excluding(ClassLoader parent, String excluded) {
        return new ClassLoader(parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (excluded.equals(name)) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test public void settingsWindowSurvivesDirectoryPickerPauseAndClosesAfterOrdinaryPause() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        HostDownloadDirectoryPicker picker = null;
        try {
            controller.setSettingsPageTargets(PickerActivity.class, ScrollView.class);
            controller.onActivityResumed(activity);
            ShadowLooper.idleMainLooper();
            activity.findViewById(android.R.id.content).findViewWithTag("DouAi.HostSettingsEntry").performClick();
            Dialog settings = ShadowDialog.getLatestDialog();
            assertTrue(settings.isShowing());
            picker = HostDownloadDirectoryPicker.launch(activity, null, ignored -> { }, ignored -> { });
            assertNotNull(picker);
            controller.onActivityPaused(activity);
            assertTrue(settings.isShowing());
            picker.close();
            controller.onActivityResumed(activity);
            controller.onActivityPaused(activity);
            assertFalse(settings.isShowing());
        } finally {
            if (picker != null) picker.close();
            controller.close();
            activity.finish();
        }
    }

    public static final class PickerActivity extends Activity {
        final TestRegistry registry = new TestRegistry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            ScrollView scroll = new ScrollView(this);
            android.widget.LinearLayout content = new android.widget.LinearLayout(this);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            scroll.addView(content);
            setContentView(scroll);
        }
    }

    private static final class TestRegistry extends ActivityResultRegistry {
        int requestCode;
        Intent intent;
        String contractName;
        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                                             I input, ActivityOptionsCompat options) {
            requestCode = code;
            contractName = contract.getClass().getName();
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }

    public static final class DirectoryProvider extends ContentProvider {
        String mime = DocumentsContract.Document.MIME_TYPE_DIR;
        int flags = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        @Override public boolean onCreate() { return true; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection);
            Object[] row = new Object[projection.length];
            for (int index = 0; index < projection.length; index++) {
                row[index] = DocumentsContract.Document.COLUMN_MIME_TYPE.equals(projection[index]) ? mime
                        : DocumentsContract.Document.COLUMN_FLAGS.equals(projection[index]) ? flags : "DouAi";
            }
            cursor.addRow(row);
            return cursor;
        }
        @Override public String getType(Uri uri) { return mime; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }
}
