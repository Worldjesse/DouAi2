package com.douai.module.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import com.douai.module.config.AiModelConfig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AiContactAvatarTest {
    @Test public void selectedAvatarIsBoundedAndReadableWithoutOriginalPermission() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        Uri uri = Uri.parse("content://avatar/image/1");
        Bitmap source = Bitmap.createBitmap(960, 480, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.GREEN);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
        source.recycle();
        ShadowContentResolver resolver = Shadow.extract(context.getContentResolver());
        resolver.registerInputStream(uri, new ByteArrayInputStream(output.toByteArray()));
        String encoded = AiContactAvatar.fromSelection(context, uri);
        assertTrue(encoded.startsWith("data:image/jpeg;base64,"));
        assertTrue(AiModelConfig.decodeContactAvatar(encoded).length <= AiModelConfig.MAX_CONTACT_AVATAR_BYTES);
        Bitmap restored = AiContactAvatar.load(encoded);
        assertNotNull(restored);
        try {
            assertEquals(128, restored.getWidth());
            assertEquals(64, restored.getHeight());
        } finally { restored.recycle(); }
    }

    @Test public void absentInvalidAndOversizedDimensionAvatarsFallBackToDefault() {
        assertNull(AiContactAvatar.load(""));
        assertNull(AiContactAvatar.load("content://avatar/image/1"));
        Bitmap source = Bitmap.createBitmap(1024, 1, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output));
        source.recycle();
        String encoded = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(output.toByteArray());
        assertNull(AiContactAvatar.load(encoded));
    }
}
