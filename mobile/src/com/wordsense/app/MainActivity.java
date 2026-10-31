package com.wordsense.app;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_MIC = 1001;
    private static final int REQ_FILE = 1002;
    private static final int REQ_SCORE = 1003;
    private static final int REQ_NOTIFY = 1004;
    private static final int REQ_ASR_ACTIVITY = 1005;
    private static final int PORT = 47821;

    private WebView webView;
    private AssetServer server;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String preferredAccent = "us";
    private String ttsVoiceName = "系统默认";
    private String pendingTts;
    private float pendingRate = 0.9f;
    private PermissionRequest pendingAudio;
    private ValueCallback<Uri[]> pendingFile;
    private final Object saveLock = new Object();
    private ByteArrayOutputStream saveBuffer;
    private String saveName;
    private String saveMime;
    private SpeechRecognizer recognizer;
    private boolean asrActive = false;
    private int pendingReminderHour = 20;
    private int pendingReminderMinute = 30;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            server = new AssetServer(this, PORT);
            server.start();
        } catch (Exception e) {
            Toast.makeText(this, "启动失败：本地端口被占用，请关闭其他词感实例", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF4F2ED);
        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        configureWebView();
        initTts();
        webView.loadUrl("http://127.0.0.1:" + PORT + "/");
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " WordSenseApp/1.2");
        webView.setBackgroundColor(0xFFF4F2ED);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri != null && "127.0.0.1".equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) { }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                boolean wantsAudio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                }
                if (!wantsAudio) {
                    request.deny();
                    return;
                }
                if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingAudio = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFile != null) pendingFile.onReceiveValue(null);
                pendingFile = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFile = null;
                    return false;
                }
            }
        });

        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void speak(final String text, final double rate) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        speakNow(text, (float) rate);
                    }
                });
            }

            @JavascriptInterface
            public void setAccent(final String accent) {
                preferredAccent = "uk".equals(accent) ? "uk" : "us";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tts != null) tts.setLanguage("uk".equals(preferredAccent) ? Locale.UK : Locale.US);
                    }
                });
            }

            @JavascriptInterface
            public void openExternal(final String url) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                        } catch (Exception ignored) { }
                    }
                });
            }

            @JavascriptInterface
            public void stop() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (tts != null) tts.stop();
                    }
                });
            }

            @JavascriptInterface
            public String getTtsVoiceName() {
                return ttsVoiceName;
            }

            @JavascriptInterface
            public void openTtsSettings() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
                            } catch (Exception ignored) { }
                        }
                    }
                });
            }

            @JavascriptInterface
            public void scheduleReminder(final boolean enabled, final int hour, final int minute, final boolean ask) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!enabled) {
                            ReminderScheduler.cancel(MainActivity.this);
                            notifyReminderScheduled();
                            return;
                        }
                        pendingReminderHour = Math.max(0, Math.min(23, hour));
                        pendingReminderMinute = Math.max(0, Math.min(59, minute));
                        if (Build.VERSION.SDK_INT >= 33 &&
                                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            if (!ask) return; // 启动时静默同步，不反复弹权限框
                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
                            return;
                        }
                        ReminderScheduler.schedule(MainActivity.this, pendingReminderHour, pendingReminderMinute);
                        notifyReminderScheduled();
                    }
                });
            }

            @JavascriptInterface
            public String saveTextFile(String name, String base64, String mime) {
                beginSave(name, mime);
                appendSave(base64);
                return endSave();
            }

            @JavascriptInterface
            public void beginSave(String name, String mime) {
                synchronized (saveLock) {
                    saveBuffer = new ByteArrayOutputStream();
                    saveName = name;
                    saveMime = mime;
                }
            }

            @JavascriptInterface
            public void appendSave(String base64) {
                synchronized (saveLock) {
                    if (saveBuffer == null) return;
                    try {
                        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                        saveBuffer.write(bytes, 0, bytes.length);
                    } catch (Exception ignored) { }
                }
            }

            @JavascriptInterface
            public String endSave() {
                synchronized (saveLock) {
                    if (saveBuffer == null) return "保存失败：没有开始写入";
                    byte[] data = saveBuffer.toByteArray();
                    String name = saveName;
                    String mime = saveMime;
                    saveBuffer = null;
                    saveName = null;
                    saveMime = null;
                    return writeBytes(name, data, mime);
                }
            }

            @JavascriptInterface
            public void cancelSave() {
                synchronized (saveLock) {
                    saveBuffer = null;
                    saveName = null;
                    saveMime = null;
                }
            }

            @JavascriptInterface
            public boolean isSpeechAvailable() {
                return SpeechRecognizer.isRecognitionAvailable(MainActivity.this) || hasRecognizerActivity();
            }

            @JavascriptInterface
            public void startSpeechScore() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startRecognition();
                    }
                });
            }

            @JavascriptInterface
            public void stopSpeechScore() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        stopRecognition();
                    }
                });
            }
        }, "AndroidBridge");
    }

    private void startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            if (startExternalRecognizer()) return;
            notifyAsrError("系统语音识别不可用");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_SCORE);
            return;
        }
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(recognitionListener);
            }
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            asrActive = true;
            recognizer.startListening(intent);
        } catch (Exception e) {
            asrActive = false;
            notifyAsrError("无法启动语音识别");
        }
    }

    private boolean hasRecognizerActivity() {
        try {
            Intent probe = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            probe.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            return !getPackageManager().queryIntentActivities(probe, 0).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean startExternalRecognizer() {
        if (!hasRecognizerActivity()) return false;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US");
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "请朗读整句英文");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            startActivityForResult(intent, REQ_ASR_ACTIVITY);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void stopRecognition() {
        asrActive = false;
        try {
            if (recognizer != null) recognizer.cancel();
        } catch (Exception ignored) { }
    }

    private String firstResult(Bundle bundle) {
        if (bundle == null) return "";
        ArrayList<String> list = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list != null && !list.isEmpty()) return list.get(0);
        return "";
    }

    private void evalJs(String script) {
        final WebView view = webView;
        if (view == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.evaluateJavascript(script, null);
            }
        });
    }

    private void notifyAsrError(String message) {
        try {
            evalJs("window.__onAsrError&&window.__onAsrError(" + JSONObject.quote(message) + ")");
        } catch (Exception ignored) { }
    }

    private void notifyReminderScheduled() {
        try {
            evalJs("window.__onReminderScheduled&&window.__onReminderScheduled()");
        } catch (Exception ignored) { }
    }

    private void notifyReminderError(String message) {
        try {
            evalJs("window.__onReminderError&&window.__onReminderError(" + JSONObject.quote(message) + ")");
        } catch (Exception ignored) { }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            evalJs("window.__onAsrReady&&window.__onAsrReady()");
        }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            asrActive = false;
            // Google 语音服务存在但网络不可用时，尝试交给系统里现成的语音输入界面
            if ((error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_CLIENT) && hasRecognizerActivity()) {
                if (startExternalRecognizer()) return;
            }
            String message;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                    message = "没有识别到匹配内容";
                    break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    message = "没有听到声音";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    message = "麦克风权限被拒绝";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    message = "语音识别服务连接失败";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    message = "识别服务正忙，请稍后再试";
                    break;
                default:
                    message = "系统识别失败（" + error + "）";
                    break;
            }
            notifyAsrError(message);
        }

        @Override
        public void onResults(Bundle results) {
            asrActive = false;
            String text = firstResult(results);
            try {
                evalJs("window.__onAsrResult&&window.__onAsrResult(" + JSONObject.quote(text) + ",0)");
            } catch (Exception ignored) { }
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            String text = firstResult(partialResults);
            if (text.length() == 0) return;
            try {
                evalJs("window.__onAsrPartial&&window.__onAsrPartial(" + JSONObject.quote(text) + ")");
            } catch (Exception ignored) { }
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    };

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    tts.setLanguage("uk".equals(preferredAccent) ? Locale.UK : Locale.US);
                    pickTtsVoice();
                    tts.setSpeechRate(0.92f);
                    tts.setPitch(1.0f);
                    ttsReady = true;
                    if (pendingTts != null) {
                        speakNow(pendingTts, pendingRate);
                        pendingTts = null;
                    }
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) { }

            @Override
            public void onDone(String utteranceId) {
                notifyTtsEnd();
            }

            @Override
            public void onError(String utteranceId) {
                notifyTtsEnd();
            }
        });
    }

    private void pickTtsVoice() {
        if (Build.VERSION.SDK_INT < 21 || tts == null) return;
        try {
            java.util.Set<Voice> all = tts.getVoices();
            if (all == null) return;
            Voice best = null;
            int bestScore = -999;
            for (Voice v : all) {
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage())) continue;
                int score = 0;
                if ("US".equals(l.getCountry())) score += 20;
                if (v.getQuality() >= Voice.QUALITY_HIGH) score += 8;
                if (v.getQuality() == Voice.QUALITY_VERY_HIGH) score += 4;
                if (!v.isNetworkConnectionRequired()) score += 5;
                if (v.getLatency() <= Voice.LATENCY_NORMAL) score += 2;
                String n = v.getName() == null ? "" : v.getName().toLowerCase(Locale.US);
                if (n.contains("natural") || n.contains("neural")) score += 10;
                if (n.contains("network")) score += 1;
                if (n.contains("female")) score += 2;
                if (score > bestScore) {
                    bestScore = score;
                    best = v;
                }
            }
            if (best != null) {
                tts.setVoice(best);
                ttsVoiceName = best.getName() + " · " + best.getLocale().toLanguageTag();
            }
        } catch (Exception ignored) { }
    }

    private void speakNow(String text, float rate) {
        if (text == null || text.trim().length() == 0) return;
        if (tts == null) return;
        if (!ttsReady) {
            pendingTts = text;
            pendingRate = rate;
            return;
        }
        float r = Math.max(0.5f, Math.min(1.5f, rate));
        tts.setSpeechRate(r);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ws" + System.nanoTime());
    }

    private void notifyTtsEnd() {
        final WebView view = webView;
        if (view == null) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                view.evaluateJavascript("window.__onNativeTtsEnd&&window.__onNativeTtsEnd()", null);
            }
        });
    }

    private String writeBytes(String name, byte[] bytes, String mime) {
        try {
            String safe = (name == null || name.length() == 0) ? "wordsense.txt" : name;
            safe = safe.replaceAll("[\\\\/:*?\"<>|]", "_");
            String type = (mime == null || mime.length() == 0) ? "text/plain" : mime;
            int semi = type.indexOf(';');
            if (semi > 0) type = type.substring(0, semi);

            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safe);
                values.put(MediaStore.MediaColumns.MIME_TYPE, type);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/WordSense");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                        return "下载/WordSense/" + safe;
                    }
                }
            }

            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "WordSense");
            if (!dir.exists() && !dir.mkdirs()) return "保存失败：无法创建目录";
            File file = new File(dir, safe);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "保存失败：" + e.getMessage();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (pendingAudio != null) {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pendingAudio.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingAudio.deny();
                }
                pendingAudio = null;
            }
        } else if (requestCode == REQ_SCORE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecognition();
            } else {
                notifyAsrError("麦克风权限被拒绝");
            }
        } else if (requestCode == REQ_NOTIFY) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ReminderScheduler.schedule(this, pendingReminderHour, pendingReminderMinute);
                notifyReminderScheduled();
            } else {
                notifyReminderError("通知权限被拒绝，到系统设置里允许「词感」通知后才会提醒");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && pendingFile != null) {
            pendingFile.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            pendingFile = null;
        } else if (requestCode == REQ_ASR_ACTIVITY) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> list = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                String text = (list != null && !list.isEmpty()) ? list.get(0) : "";
                if (text.length() > 0) {
                    try {
                        evalJs("window.__onAsrResult&&window.__onAsrResult(" + JSONObject.quote(text) + ",0)");
                    } catch (Exception ignored) { }
                } else {
                    notifyAsrError("没有识别到内容");
                }
            } else {
                notifyAsrError("已取消语音识别");
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (tts != null) tts.stop();
        stopRecognition();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
