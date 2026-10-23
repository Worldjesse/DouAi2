# 日志 Hook 注册索引（2026-10-23）

从本次源码抽取的注册表达式。动态组装的ID按表达式原样保留；它们是安装/回调诊断入口，不是功能数量，也不是实际装机成功列表。实际生效以hook安装事件和feature_state为准，功能归属见[覆盖矩阵](logging-coverage.md)。

复核：在项目根目录执行 `rg -n "setId\(|group\.add\(|hook\.add\(" app/src/main/java/com/douai/module`。

| 源码位置 | 注册表达式 |
| --- | --- |
| `app/src/main/java/com/douai/module/DouAiModule.java:363` | `.setId("DouAi.Host.ApplicationUi")` |
| `app/src/main/java/com/douai/module/DouAiModule.java:407` | `.setId("DouAi.Host.ActivityUi")` |
| `app/src/main/java/com/douai/module/feature/AiMessageEntryHook.java:327` | `wrapper.setId(View.generateViewId());` |
| `app/src/main/java/com/douai/module/feature/AutoCommentHook.java:127` | `XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.AutoComment." + target.getName())` |
| `app/src/main/java/com/douai/module/feature/AutoCommentHook.java:139` | `XposedInterface.HookHandle handle = framework.hook(playing).setId("DouAi.AutoComment.Playing")` |
| `app/src/main/java/com/douai/module/feature/AutoFollowHook.java:70` | `.setId("DouAi.Automation.AutoFollow")` |
| `app/src/main/java/com/douai/module/feature/AutoLikeHook.java:76` | `.setId("DouAi.Automation.AutoLike")` |
| `app/src/main/java/com/douai/module/feature/AutoMessageDraftHook.java:154` | `.setId("DouAi.Automation.MessageDraftPrefill")` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:149` | `hook.group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:156` | `hook.group.add(framework, pause, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:164` | `hook.group.add(framework, touch, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:171` | `hook.group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:183` | `hook.group.add(framework, completed, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:191` | `hook.group.add(framework, current, chain -> {` |
| `app/src/main/java/com/douai/module/feature/AutoPlayHook.java:196` | `hook.group.add(framework, aweme, chain -> {` |
| `app/src/main/java/com/douai/module/feature/BehaviorHookGroup.java:35` | `.setId("DouAi.Advanced." + id + "." + handles.size())` |
| `app/src/main/java/com/douai/module/feature/CommentAudioDiagnostics.java:59` | `add(framework.hook(read).setId(namespace + ".AudioTrace.Read")` |
| `app/src/main/java/com/douai/module/feature/CommentAudioDiagnostics.java:85` | `add(framework.hook(bind).setId(namespace + ".AudioTrace.Row")` |
| `app/src/main/java/com/douai/module/feature/CommentAudioDiagnostics.java:99` | `add(framework.hook(bind).setId(namespace + ".AudioTrace.Component")` |
| `app/src/main/java/com/douai/module/feature/CommentAudioDiagnostics.java:123` | `add(framework.hook(apply).setId(namespace + ".AudioTrace.Apply")` |
| `app/src/main/java/com/douai/module/feature/CommentEnhancementsHook.java:58` | `hook.handle = framework.hook(request).setId("DouAi.Comment.RequestFields")` |
| `app/src/main/java/com/douai/module/feature/CommentToolsHook.java:103` | `.setId("DouAi.Comment.Tools")` |
| `app/src/main/java/com/douai/module/feature/CommentVoiceHook.java:66` | `hook.add(framework, input, "Input", chain -> {` |
| `app/src/main/java/com/douai/module/feature/CommentVoiceHook.java:90` | `hook.add(framework, path, "Path", chain -> {` |
| `app/src/main/java/com/douai/module/feature/CommentVoiceHook.java:103` | `hook.add(framework, listener, "Listener", chain -> {` |
| `app/src/main/java/com/douai/module/feature/CommentVoiceHook.java:118` | `hook.add(framework, notify, "Notify", chain -> {` |
| `app/src/main/java/com/douai/module/feature/CommentVoiceHook.java:245` | `XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.CommentAudio." + id)` |
| `app/src/main/java/com/douai/module/feature/DebugHookTrace.java:253` | `@Override public HookBuilder setId(String value) { delegate = delegate.setId(value); id = label(value); return this; }` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:112` | `hook.add(framework, HostTargets.method(report, id, loader), id, chain -> {` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:125` | `hook.add(framework, HostTargets.method(report, "dm_speech_send", loader), "Send", hook::send);` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:126` | `hook.add(framework, HostTargets.method(report, "dm_speech_audio_create", loader), "AudioCreate", chain -> {` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:133` | `hook.add(framework, hook.recordEvent, "RecordComplete", hook::replaceRecording);` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:134` | `hook.add(framework, HostTargets.method(report, "dm_speech_audio_destroy", loader), "AudioDestroy", chain -> {` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:143` | `hook.add(framework, HostTargets.method(report, "dm_speech_upload_enqueue", loader), "Queue", chain -> {` |
| `app/src/main/java/com/douai/module/feature/DirectMessageSpeechHook.java:591` | `XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.PrivateSpeech." + id)` |
| `app/src/main/java/com/douai/module/feature/DownloadActionHook.java:86` | `.setId("DouAi.DownloadAction.LongPressMenu")` |
| `app/src/main/java/com/douai/module/feature/DownloadActionHook.java:92` | `.setId("DouAi.DownloadAction.LocalSave")` |
| `app/src/main/java/com/douai/module/feature/FeedAdFilterHook.java:160` | `.setId("DouAi.Feed.ResponseFilter")` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:243` | `group.add(framework, current, chain -> {` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:248` | `group.add(framework, awemeAccessor, chain -> {` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:335` | `group.add(framework, dispatchEvent, XposedInterface.PRIORITY_HIGHEST, this::dispatchFinderTouch);` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:336` | `group.add(framework, touchEvent, XposedInterface.PRIORITY_HIGHEST, chain -> {` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:373` | `group.add(framework, run, chain -> {` |
| `app/src/main/java/com/douai/module/feature/FeedGestureToolsHook.java:434` | `group.add(framework, target, XposedInterface.PRIORITY_HIGHEST, chain -> {` |
| `app/src/main/java/com/douai/module/feature/FeedUiHideHook.java:239` | `.setId(paused ? "DouAi.FeedUi.PlayerPause" : "DouAi.FeedUi.PlayerPlaying")` |
| `app/src/main/java/com/douai/module/feature/FeedUiHideHook.java:386` | `.setId(hookId)` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:93` | `hook.add(framework, drawerBind, "BindMessage", chain -> {` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:105` | `hook.add(framework, editorBind, "BindCommentEditor", chain -> {` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:118` | `hook.add(framework, method, "BindCommentSend." + method.getName(), chain -> {` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:136` | `hook.add(framework, method, "LongPress." + method.getParameterCount(), hook::longClick);` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:138` | `hook.add(framework, View.class.getDeclaredMethod("setLongClickable", boolean.class),` |
| `app/src/main/java/com/douai/module/feature/HashLongPressVoiceHook.java:393` | `XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.HashLongPress." + id)` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:145` | `group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:213` | `group.add(framework, baseDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:214` | `group.add(framework, fullDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:215` | `group.add(framework, currentTask, chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:231` | `group.add(framework, backgroundTask, chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:278` | `group.add(framework, Dialog.class.getDeclaredMethod("show"), chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:292` | `group.add(framework, fragment.getDeclaredMethod(name, manager, String.class), chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:300` | `group.add(framework, fragment.getDeclaredMethod("show", transaction, String.class), chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:322` | `group.add(framework, method, chain -> {` |
| `app/src/main/java/com/douai/module/feature/HostBehaviorHooks.java:346` | `group.add(framework, attach, chain -> {` |
| `app/src/main/java/com/douai/module/feature/LocalAnalyticsHook.java:109` | `for (Method callback : callbacks) group.add(framework, callback, chain -> {` |
| `app/src/main/java/com/douai/module/feature/MediaModelHook.java:41` | `.setId("DouAi.MediaModel." + id)` |
| `app/src/main/java/com/douai/module/feature/NativeAudioMessageSender.java:101` | `.setId("DouAi.AutoAudio." + handles.size())` |
| `app/src/main/java/com/douai/module/feature/NativeCommentPublisher.java:98` | `XposedInterface.HookHandle uploadHandle = framework.hook(upload).setId(namespace + ".AudioUpload")` |
| `app/src/main/java/com/douai/module/feature/NativeCommentPublisher.java:126` | `.setId(namespace + ".AudioPublishModel")` |
| `app/src/main/java/com/douai/module/feature/NativeCommentPublisher.java:144` | `XposedInterface.HookHandle handle = framework.hook(target).setId(namespace + "." + rule)` |
| `app/src/main/java/com/douai/module/feature/NativeCommentReplyInbox.java:207` | `inbox.handle = framework.hook(receive).setId("DouAi.CommentReply.Incoming")` |
| `app/src/main/java/com/douai/module/feature/NativeCommentSpeechHook.java:71` | `XposedInterface.HookHandle handle = framework.hook(method).setId("DouAi.CommentSpeech." + id)` |
| `app/src/main/java/com/douai/module/feature/PlaybackBehaviorHooks.java:95` | `group.add(framework, setSpeed, chain -> interceptSpeedWrite(chain, group, key, speeds));` |
| `app/src/main/java/com/douai/module/feature/PlaybackBehaviorHooks.java:97` | `group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/PlaybackBehaviorHooks.java:103` | `group.add(framework, managerSetSpeed,` |
| `app/src/main/java/com/douai/module/feature/PlaybackBehaviorHooks.java:106` | `group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/RefreshConfirmHook.java:59` | `.setId("DouAi.HomeTab.RefreshConfirm")` |
| `app/src/main/java/com/douai/module/feature/ShortVideoProgressHook.java:41` | `group.add(framework, target, chain -> {` |
| `app/src/main/java/com/douai/module/feature/TopTabFilterHook.java:58` | `.setId("DouAi.HomeTab.Filter")` |
| `app/src/main/java/com/douai/module/feature/VersionInfoHook.java:54` | `.setId("DouAi.VersionInfo." + method.getParameterTypes()[1].getSimpleName())` |
