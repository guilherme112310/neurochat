package com.neurochat.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;

public class MainActivity extends AppCompatActivity {

    private static final int RC_SIGN_IN           = 200;
    private static final int FILE_CHOOSER_REQUEST = 100;
    private static final int PERMISSION_REQUEST   = 101;

    private WebView webView;
    private ProgressBar loader;
    private WebViewAssetLoader assetLoader;
    private GoogleSignInClient googleSignInClient;
    private FirebaseAuth firebaseAuth;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean wantVideo = false;
    private String latestFcmToken = null;
    private com.google.firebase.firestore.ListenerRegistration chatListenerReg;

    @SuppressLint({"SetJavaScriptEnabled"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // Firebase
        FirebaseApp.initializeApp(this);
        firebaseAuth = FirebaseAuth.getInstance();

        // FCM Token para notificações
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null) {
                String token = task.getResult();
                latestFcmToken = token;
                // Envia token para o WebView
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (webView != null) {
                        webView.evaluateJavascript(
                            "window.fcmToken = '" + token + "';", null);
                    }
                }, 3000);
                // Se já tem usuário logado, salva o token nele agora
                FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
                if (u != null) saveFcmTokenToFirestore(u.getUid(), token);
            }
        });

        // Google Sign-In
        GoogleSignInOptions gso = new GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("735568438223-jm8bl9cua9063nlnjraibrlhgl4ndu35.apps.googleusercontent.com")
            .requestEmail()
            .requestProfile()
            .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);

        // Asset loader
        assetLoader = new WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/",
                new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        // Layout
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0e1117"));
        setContentView(root);

        loader = new ProgressBar(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(120, 120);
        lp.gravity = android.view.Gravity.CENTER;
        root.addView(loader, lp);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0e1117"));
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        root.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.INVISIBLE);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);
        ws.setTextZoom(100);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        webView.addJavascriptInterface(new NativeInterface(), "NativeApp");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.equals("neurochat://googleSignIn")) {
                    triggerGoogleSignIn();
                    return new WebResourceResponse("text/plain","utf-8",null);
                }
                WebResourceResponse r =
                    assetLoader.shouldInterceptRequest(request.getUrl());
                return r != null ? r : super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.equals("neurochat://googleSignIn")) {
                    triggerGoogleSignIn();
                    return true;
                }
                if (url.startsWith("https://appassets")) return false;
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                new Handler().postDelayed(() -> {
                    loader.setVisibility(View.GONE);
                    webView.setVisibility(View.VISIBLE);
                }, 300);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v,
                    ValueCallback<Uri[]> cb, FileChooserParams p) {
                // cancela um seletor anterior que ficou pendente
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = cb;
                Intent intent;
                try {
                    // respeita accept="image/*" ou "video/*" e usa o seletor do
                    // sistema (Android 13+: seletor de fotos), que não pede permissão
                    intent = p.createIntent();
                } catch (Throwable t) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Throwable t) {
                    fileChooserCallback = null;
                    cb.onReceiveValue(null);
                    return false;
                }
                return true;
            }
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                req.grant(req.getResources());
            }
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });

        webView.loadUrl(
            "https://appassets.androidplatform.net/assets/index.html");
    }

    // JavaScript Interface
    class NativeInterface {
        @JavascriptInterface
        public void startGoogleSignIn() {
            try { triggerGoogleSignIn(); } catch (Throwable t) { t.printStackTrace(); }
        }

        @JavascriptInterface
        public void saveUserData(String uid, String data) {
            try {
                if (uid == null || uid.isEmpty()) return;
                FirebaseFirestore db = FirebaseFirestore.getInstance();
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                if (data != null && !data.isEmpty()) {
                    org.json.JSONObject json = new org.json.JSONObject(data);
                    java.util.Iterator<String> keys = json.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object val = json.get(key);
                        if (val == org.json.JSONObject.NULL) val = null;
                        map.put(key, val);
                    }
                }
                if (latestFcmToken != null) map.put("fcmToken", latestFcmToken);
                db.collection("users").document(uid).set(map,
                    com.google.firebase.firestore.SetOptions.merge());
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        @JavascriptInterface
        public String getCurrentUser() {
            try {
                FirebaseUser user = firebaseAuth.getCurrentUser();
                if (user == null) return "null";
                return "{\"uid\":\"" + user.getUid() + "\","
                    + "\"email\":\"" + (user.getEmail()!=null?user.getEmail():"") + "\","
                    + "\"name\":\"" + (user.getDisplayName()!=null?user.getDisplayName():"") + "\","
                    + "\"photo\":\"" + (user.getPhotoUrl()!=null?user.getPhotoUrl().toString():"") + "\"}";
            } catch (Throwable t) {
                t.printStackTrace();
                return "null";
            }
        }

        @JavascriptInterface
        public void signOut() {
            try {
                firebaseAuth.signOut();
                googleSignInClient.signOut();
            } catch (Throwable t) { t.printStackTrace(); }
        }

        // ── Chat em tempo real (Firestore) ──────────────────────
        // Envia uma mensagem para outro usuário (usa o UID dele)
        @JavascriptInterface
        public void sendChatMessage(String toUid, String toName, String text) {
            try {
                FirebaseUser me = firebaseAuth.getCurrentUser();
                if (me == null || toUid == null || toUid.isEmpty()) return;
                String chatId = chatIdFor(me.getUid(), toUid);
                java.util.Map<String, Object> msg = new java.util.HashMap<>();
                msg.put("fromUid", me.getUid());
                msg.put("fromName", me.getDisplayName() != null ? me.getDisplayName() : "Usuário");
                msg.put("toUid", toUid);
                msg.put("text", text);
                msg.put("read", false);
                msg.put("timestamp", com.google.firebase.firestore.FieldValue.serverTimestamp());
                FirebaseFirestore db = FirebaseFirestore.getInstance();
                db.collection("chats").document(chatId)
                    .collection("messages").add(msg);
                db.collection("chats").document(chatId).set(
                    mapOf("participants", java.util.Arrays.asList(me.getUid(), toUid),
                          "lastMessage", text,
                          "lastTimestamp", com.google.firebase.firestore.FieldValue.serverTimestamp()),
                    com.google.firebase.firestore.SetOptions.merge());
                // Registra o evento para a futura notificação push (Cloud Function)
                queueNotification(toUid, "message", me.getDisplayName(), text, chatId);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        // Começa a "ouvir" novas mensagens trocadas com outro usuário em tempo real
        @JavascriptInterface
        public void listenMessages(String withUid) {
            try {
                FirebaseUser me = firebaseAuth.getCurrentUser();
                if (me == null || withUid == null || withUid.isEmpty()) return;
                String chatId = chatIdFor(me.getUid(), withUid);
                if (chatListenerReg != null) chatListenerReg.remove();
                chatListenerReg = FirebaseFirestore.getInstance()
                    .collection("chats").document(chatId)
                    .collection("messages")
                    .orderBy("timestamp")
                    .addSnapshotListener((snap, err) -> {
                        try {
                            if (err != null || snap == null || webView == null) return;
                            org.json.JSONArray arr = new org.json.JSONArray();
                            for (com.google.firebase.firestore.DocumentSnapshot d : snap.getDocuments()) {
                                try {
                                    org.json.JSONObject o = new org.json.JSONObject();
                                    o.put("id", d.getId());
                                    o.put("fromUid", d.getString("fromUid"));
                                    o.put("fromName", d.getString("fromName"));
                                    o.put("text", d.getString("text"));
                                    arr.put(o);
                                } catch (Exception ignored) {}
                            }
                            String js = "window.onMessagesUpdate && window.onMessagesUpdate(" + arr.toString() + ")";
                            webView.evaluateJavascript(js, null);
                        } catch (Throwable t) { t.printStackTrace(); }
                    });
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }

        @JavascriptInterface
        public void stopListeningMessages() {
            try {
                if (chatListenerReg != null) { chatListenerReg.remove(); chatListenerReg = null; }
            } catch (Throwable t) { t.printStackTrace(); }
        }

        // ── Notificações (curtida / comentário) ────────────────
        // Chamar quando alguém curte um post: toUid = dono do post
        @JavascriptInterface
        public void notifyLike(String toUid, String postId) {
            try {
                FirebaseUser me = firebaseAuth.getCurrentUser();
                if (me == null || toUid == null || toUid.isEmpty() || toUid.equals(me.getUid())) return;
                queueNotification(toUid, "like", me.getDisplayName(), null, postId);
            } catch (Throwable t) { t.printStackTrace(); }
        }

        // Chamar quando alguém comenta um post: toUid = dono do post
        @JavascriptInterface
        public void notifyComment(String toUid, String postId, String commentText) {
            try {
                FirebaseUser me = firebaseAuth.getCurrentUser();
                if (me == null || toUid == null || toUid.isEmpty() || toUid.equals(me.getUid())) return;
                queueNotification(toUid, "comment", me.getDisplayName(), commentText, postId);
            } catch (Throwable t) { t.printStackTrace(); }
        }
    }

    private static java.util.Map<String, Object> mapOf(Object... kv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // Gera um ID de conversa estável e igual pros dois participantes,
    // não importa quem começou (ordena os dois UIDs em ordem alfabética)
    private String chatIdFor(String uidA, String uidB) {
        return uidA.compareTo(uidB) < 0 ? uidA + "_" + uidB : uidB + "_" + uidA;
    }

    // Grava um "pedido de notificação" no Firestore. Assim que a Cloud
    // Function estiver publicada (depende do plano Blaze), ela vai
    // detectar esse documento novo e disparar o push automaticamente.
    private void queueNotification(String toUid, String type, String fromName, String extraText, String refId) {
        try {
            java.util.Map<String, Object> n = new java.util.HashMap<>();
            n.put("toUid", toUid);
            n.put("type", type); // "like" | "comment" | "message"
            n.put("fromName", fromName != null ? fromName : "Alguém");
            n.put("text", extraText != null ? extraText : "");
            n.put("refId", refId != null ? refId : "");
            n.put("read", false);
            n.put("timestamp", com.google.firebase.firestore.FieldValue.serverTimestamp());
            FirebaseFirestore.getInstance().collection("notifications").add(n);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void saveFcmTokenToFirestore(String uid, String token) {
        try {
            FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(mapOf("fcmToken", token), com.google.firebase.firestore.SetOptions.merge());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private void triggerGoogleSignIn() {
        runOnUiThread(() ->
            googleSignInClient.signOut().addOnCompleteListener(t -> {
                startActivityForResult(
                    googleSignInClient.getSignInIntent(), RC_SIGN_IN);
            }));
    }

    private void sendToJS(String name, String email, String photo, String uid) {
        String n = name.replace("'","\\'");
        String e = email.replace("'","\\'");
        String p = photo.replace("'","\\'");
        String js = "window.onGoogleSignIn('" + n + "','" + e + "','" + p + "','" + uid + "')";
        new Handler(Looper.getMainLooper()).post(
            () -> webView.evaluateJavascript(js, null));
    }

    @Override
    protected void onActivityResult(int req, int res, @Nullable Intent data) {
        super.onActivityResult(req, res, data);
        if (req == RC_SIGN_IN) {
            Task<GoogleSignInAccount> task =
                GoogleSignIn.getSignedInAccountFromIntent(data);
            try {
                GoogleSignInAccount acct = task.getResult(ApiException.class);
                if (acct.getIdToken() == null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                        webView.evaluateJavascript(
                            "window.onGoogleSignInError && window.onGoogleSignInError(-1)", null));
                    return;
                }
                // Autentica no Firebase com credencial Google
                AuthCredential credential =
                    GoogleAuthProvider.getCredential(acct.getIdToken(), null);
                firebaseAuth.signInWithCredential(credential)
                    .addOnSuccessListener(authResult -> {
                        FirebaseUser user = authResult.getUser();
                        String uid = user != null ? user.getUid() : "";
                        if (!uid.isEmpty() && latestFcmToken != null) {
                            saveFcmTokenToFirestore(uid, latestFcmToken);
                        }
                        sendToJS(
                            acct.getDisplayName() != null ? acct.getDisplayName() : "Usuário",
                            acct.getEmail() != null ? acct.getEmail() : "",
                            acct.getPhotoUrl() != null ? acct.getPhotoUrl().toString() : "",
                            uid);
                    })
                    .addOnFailureListener(e -> {
                        // Fallback sem Firebase auth
                        sendToJS(
                            acct.getDisplayName() != null ? acct.getDisplayName() : "Usuário",
                            acct.getEmail() != null ? acct.getEmail() : "",
                            acct.getPhotoUrl() != null ? acct.getPhotoUrl().toString() : "",
                            "");
                    });
            } catch (ApiException ex) {
                new Handler(Looper.getMainLooper()).post(() ->
                    webView.evaluateJavascript(
                        "window.onGoogleSignInError(" + ex.getStatusCode() + ")", null));
            }
        }
        if (req == FILE_CHOOSER_REQUEST) {
            if (fileChooserCallback == null) return;
            Uri[] results = null;
            try {
                results = WebChromeClient.FileChooserParams.parseResult(res, data);
            } catch (Throwable t) { results = null; }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    private void requestPermissionsAndOpenChooser() {
        String perm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? (wantVideo ? Manifest.permission.READ_MEDIA_VIDEO : Manifest.permission.READ_MEDIA_IMAGES)
            : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, perm)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{perm}, PERMISSION_REQUEST);
        } else { openFileChooser(); }
    }

    private void openFileChooser() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType(wantVideo ? "video/*" : "image/*");
        startActivityForResult(
            Intent.createChooser(i, wantVideo ? "Escolher vídeo" : "Escolher imagem"), FILE_CHOOSER_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int req,
            @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == PERMISSION_REQUEST && r.length > 0
                && r[0] == PackageManager.PERMISSION_GRANTED) openFileChooser();
        else if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
    @Override protected void onResume()  { super.onResume();  if (webView!=null) webView.onResume(); }
    @Override protected void onPause()   { if (webView!=null) webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (chatListenerReg != null) { chatListenerReg.remove(); chatListenerReg = null; }
        if (webView!=null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
