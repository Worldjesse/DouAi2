package com.douai.module.feature;

import android.content.ContentValues;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Map;

/** Copies a native SDK receipt into the same policy input used for committed DB rows. */
final class IncomingMessageSnapshot {
    private final Class<?> messageType;
    private final Method sender, id, created, content, type, conversation, conversationType, extension, deleted;

    IncomingMessageSnapshot(Class<?> messageType) throws ReflectiveOperationException {
        this.messageType = messageType;
        sender = messageType.getMethod("getSender");
        id = messageType.getMethod("getMsgId");
        created = messageType.getMethod("getCreatedAt");
        content = messageType.getMethod("getContent");
        type = messageType.getMethod("getMsgType");
        conversation = messageType.getMethod("getConversationId");
        conversationType = messageType.getMethod("getConversationType");
        extension = messageType.getMethod("getExt");
        deleted = messageType.getMethod("getDeleted");
    }

    ContentValues copy(Object message) throws ReflectiveOperationException {
        if (!messageType.isInstance(message) || ((Number) deleted.invoke(message)).intValue() != 0) return null;
        ContentValues values = new ContentValues();
        values.put("sender", ((Number) sender.invoke(message)).longValue());
        values.put("msg_server_id", Long.toString(((Number) id.invoke(message)).longValue()));
        values.put("created_time", ((Number) created.invoke(message)).longValue());
        values.put("content", (String) content.invoke(message));
        values.put("type", ((Number) type.invoke(message)).intValue());
        values.put("conversation_id", (String) conversation.invoke(message));
        values.put("conversation_type", ((Number) conversationType.invoke(message)).intValue());
        Object ext = extension.invoke(message);
        if (ext instanceof Map<?, ?>) values.put("ext", new JSONObject((Map<?, ?>) ext).toString());
        return values;
    }
}
