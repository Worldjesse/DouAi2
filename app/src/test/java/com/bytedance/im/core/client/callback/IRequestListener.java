package com.bytedance.im.core.client.callback;

/** Native callback contract fixture, excluded from the application APK. */
public interface IRequestListener {
    void onSuccess(Object value);
    void onFailure(Object failure);
}
