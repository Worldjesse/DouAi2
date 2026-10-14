package com.douai.module.feature;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Writes only inside a tree the user granted to the host through the system picker. */
final class DownloadDocumentTree {
    private DownloadDocumentTree() { }

    static Uri root(String tree) {
        try {
            Uri uri = Uri.parse(tree);
            if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null
                    || !DocumentsContract.isTreeUri(uri)) throw new IllegalArgumentException();
            return DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("保存位置无效，请重新选择目录");
        }
    }

    static Uri create(ContentResolver resolver, Uri parent, String mime, String name) throws Exception {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("文件名无效");
        }
        Uri document = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (document == null) throw new IllegalStateException("无法在所选目录创建文件");
        return document;
    }

    static void delete(ContentResolver resolver, Uri target) {
        if (target == null) return;
        try { DocumentsContract.deleteDocument(resolver, target); }
        catch (Exception ignored) { }
    }
}
