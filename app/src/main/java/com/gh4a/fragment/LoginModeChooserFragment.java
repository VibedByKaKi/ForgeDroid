package com.gh4a.fragment;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.gh4a.Gh4Application;
import com.gh4a.R;
import com.gh4a.ServiceFactory;
import com.gh4a.utils.ApiHelpers;
import com.gh4a.utils.RxUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.meisolsson.githubsdk.model.User;
import com.meisolsson.githubsdk.service.users.UserService;

import java.io.IOException;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class LoginModeChooserFragment extends DialogFragment {
    public interface ParentCallback {
        void onLoginFinished(String token, User user, String instanceBaseUrl);
        void onLoginFailed(Throwable error);
        void onLoginCanceled();
    }

    private static final String[] PRESET_HOSTS = {
            "codeberg.org",
            "gitdab.com",
            "git.kaki87.net",
            "bolha.dev",
            "git.gay",
            "opencommit.eu",
            "sij.ai",
    };

    private static final String CUSTOM_LABEL = "Custom";
    private static final String TAG = "ForgeDroidLogin";

    public static LoginModeChooserFragment newInstance() {
        return new LoginModeChooserFragment();
    }

    private ParentCallback mCallback;
    private TextView mInstanceLabel;
    private AutoCompleteTextView mInstanceDropdown;
    private TextInputLayout mCustomUrlWrapper;
    private TextInputEditText mCustomUrl;
    private TextInputEditText mTokenName;
    private WebView mWebView;
    private ProgressBar mProgress;
    private String mSelectedInstanceLabel;

    private boolean mLoginInProgress;
    private boolean mCheckingSession;
    private String mCurrentInstanceUrl;
    private String mLastLoadedUrl;
    private boolean mCookiesCleared;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final CompositeDisposable mDisposables = new CompositeDisposable();

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof ParentCallback)) {
            throw new IllegalArgumentException("Activity must implement ParentCallback");
        }
        mCallback = (ParentCallback) context;
    }

    @NonNull
    @Override
    @SuppressLint("SetJavaScriptEnabled")
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        LayoutInflater inflater = LayoutInflater.from(getActivity());
        View view = inflater.inflate(R.layout.login_dialog, null);

        mInstanceLabel = view.findViewById(R.id.instance_label);
        applyInstanceLabelSize();

        mInstanceDropdown = view.findViewById(R.id.instance_dropdown);
        mCustomUrlWrapper = view.findViewById(R.id.custom_url_wrapper);
        mCustomUrl = view.findViewById(R.id.custom_url);
        mTokenName = view.findViewById(R.id.token_name);
        mWebView = view.findViewById(R.id.login_webview);
        mProgress = view.findViewById(R.id.login_progress);

        String[] labels = new String[PRESET_HOSTS.length + 1];
        System.arraycopy(PRESET_HOSTS, 0, labels, 0, PRESET_HOSTS.length);
        labels[PRESET_HOSTS.length] = CUSTOM_LABEL;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_dropdown_item_1line, labels);
        mInstanceDropdown.setAdapter(adapter);
        mSelectedInstanceLabel = PRESET_HOSTS[0];
        mInstanceDropdown.setText(mSelectedInstanceLabel, false);
        mInstanceDropdown.setOnItemClickListener((parent, itemView, position, id) -> {
            mSelectedInstanceLabel = labels[position];
            boolean custom = isCustomSelected();
            mCustomUrlWrapper.setVisibility(custom ? View.VISIBLE : View.GONE);
            reloadWebViewIfReady();
        });

        mCustomUrl.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (isCustomSelected()) {
                    scheduleReloadWebView();
                }
            }
        });

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // The WebView is shared by the whole app, so an earlier session would sign the user in
        // again and close this dialog. Load the instance only once the old session is gone.
        cookieManager.removeAllCookies(removed -> {
            WebStorage.getInstance().deleteAllData();
            mCookiesCleared = true;
            reloadWebViewIfReady();
        });

        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(mWebView, true);
        }

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                mLastLoadedUrl = url;
                CookieManager.getInstance().flush();
                maybeHandleSession();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.add_account)
                .setView(view)
                .setNegativeButton(R.string.cancel, (d, which) -> d.cancel())
                .create();

        dialog.setOnShowListener(d -> {
            applyInstanceLabelSize();
            Window window = dialog.getWindow();
            if (window != null && getActivity() != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
                window.setLayout(
                        (int) (metrics.widthPixels * 0.96f),
                        (int) (metrics.heightPixels * 0.92f));
            }
            if (mCurrentInstanceUrl == null) {
                reloadWebViewIfReady();
            }
        });

        return dialog;
    }

    private void applyInstanceLabelSize() {
        if (mInstanceLabel != null) {
            // AlertDialog themes re-apply a small text appearance after inflate.
            mInstanceLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        applyInstanceLabelSize();
        Dialog dialog = getDialog();
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacksAndMessages(null);
        mDisposables.clear();
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        mCallback.onLoginCanceled();
    }

    private boolean isCustomSelected() {
        return CUSTOM_LABEL.equals(mSelectedInstanceLabel);
    }

    private String resolveInstanceBaseUrl() {
        if (isCustomSelected()) {
            Editable editable = mCustomUrl.getText();
            String raw = editable != null ? editable.toString().trim() : "";
            if (TextUtils.isEmpty(raw)) {
                return null;
            }
            if (!raw.contains("://")) {
                raw = "https://" + raw;
            }
            return Gh4Application.normalizeInstanceUrl(raw);
        }
        if (TextUtils.isEmpty(mSelectedInstanceLabel)) {
            return null;
        }
        return "https://" + mSelectedInstanceLabel;
    }

    private String getTokenName() {
        Editable editable = mTokenName.getText();
        String name = editable != null ? editable.toString().trim() : "";
        if (TextUtils.isEmpty(name)) {
            name = getString(R.string.login_token_name_default);
        }
        return name;
    }

    private void scheduleReloadWebView() {
        mHandler.removeCallbacks(mReloadRunnable);
        mHandler.postDelayed(mReloadRunnable, 600);
    }

    private final Runnable mReloadRunnable = this::reloadWebViewIfReady;

    private void reloadWebViewIfReady() {
        if (mWebView == null || mLoginInProgress || !mCookiesCleared) {
            return;
        }
        String instance = resolveInstanceBaseUrl();
        if (instance == null) {
            return;
        }
        if (instance.equals(mCurrentInstanceUrl)) {
            return;
        }
        Uri uri = Uri.parse(instance);
        if (TextUtils.isEmpty(uri.getHost())) {
            mCustomUrlWrapper.setError(getString(R.string.credentials_error_empty));
            return;
        }
        mCustomUrlWrapper.setErrorEnabled(false);
        mCurrentInstanceUrl = instance;
        mLastLoadedUrl = null;
        mLoginInProgress = false;
        mCheckingSession = false;
        mWebView.loadUrl(instance + "/user/login");
    }

    private void maybeHandleSession() {
        if (!mCookiesCleared || mLoginInProgress || mCheckingSession || mWebView == null) {
            return;
        }
        String instance = mCurrentInstanceUrl;
        if (instance == null) {
            return;
        }
        // Anonymous visits also get a session cookie; wait until the user leaves the login page.
        if (isAuthPage(mLastLoadedUrl)) {
            return;
        }
        String cookies = CookieManager.getInstance().getCookie(instance);
        if (!hasSessionCookie(cookies)) {
            return;
        }
        mCheckingSession = true;
        mLoginInProgress = true;
        setBusy(true);
        final String tokenName = getTokenName();
        Log.i(TAG, "Session detected on " + mLastLoadedUrl + "; creating token via web form");
        mDisposables.add(
                Single.fromCallable(() -> createTokenViaWebForm(instance, cookies, tokenName))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(token -> {
                            Log.i(TAG, "Token created, fetching user");
                            finishWithToken(instance, token);
                        }, error -> {
                            Log.e(TAG, "Token creation failed", error);
                            mCheckingSession = false;
                            // Still on a soft pre-login redirect, or transient failure — retry later
                            if (isLikelyPreLogin(error)) {
                                mLoginInProgress = false;
                                setBusy(false);
                            } else {
                                failLogin(error);
                            }
                        })
        );
    }

    private static boolean isAuthPage(String url) {
        if (TextUtils.isEmpty(url)) {
            return true;
        }
        String path = Uri.parse(url).getPath();
        if (path == null) {
            return true;
        }
        return path.contains("/user/login")
                || path.contains("/user/sign_up")
                || path.contains("/user/forgot_password")
                || path.contains("/login/oauth");
    }

    private static boolean isLikelyPreLogin(Throwable error) {
        String msg = error.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.US);
        return lower.contains("not logged in")
                || lower.contains("login required")
                || lower.contains("401")
                || lower.contains("403");
    }

    private static boolean hasSessionCookie(String cookies) {
        if (TextUtils.isEmpty(cookies)) {
            return false;
        }
        for (String part : cookies.split(";")) {
            String name = part.trim();
            int eq = name.indexOf('=');
            if (eq > 0) {
                name = name.substring(0, eq);
            }
            name = name.toLowerCase(Locale.US);
            // The session cookie name differs per instance (i_like_gitea, i_like_codeberg, ...),
            // so accept any cookie that is not a known non-session one. A false positive only
            // costs one failed token request, which is retried on the next page load.
            if (!name.isEmpty() && !"_csrf".equals(name) && !"lang".equals(name)
                    && !"redirect_to".equals(name) && !"theme".equals(name)
                    && !name.startsWith("_ga") && !name.startsWith("__cf")) {
                return true;
            }
        }
        return false;
    }

    private static String getCookieValue(String cookies, String cookieName) {
        if (TextUtils.isEmpty(cookies)) {
            return null;
        }
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (trimmed.substring(0, eq).equalsIgnoreCase(cookieName)) {
                return trimmed.substring(eq + 1);
            }
        }
        return null;
    }

    private void setBusy(boolean busy) {
        if (mProgress != null) {
            mProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        }
        if (mWebView != null) {
            mWebView.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        }
        mInstanceDropdown.setEnabled(!busy);
        mCustomUrl.setEnabled(!busy);
        mTokenName.setEnabled(!busy);
    }

    /**
     * Forgejo/Gitea reject session cookies on the REST API, so mint the token through the
     * logged-in web UI form at /user/settings/applications, then use that token for the API.
     */
    private String createTokenViaWebForm(String instance, String cookies, String tokenName)
            throws Exception {
        OkHttpClient client = ServiceFactory.getHttpClientBuilder()
                .followRedirects(false)
                .build();

        Request appsGet = new Request.Builder()
                .url(instance + "/user/settings/applications")
                .header("Cookie", cookies)
                .header("Accept", "text/html")
                .build();

        String csrf;
        String formUrl = instance + "/user/settings/applications";
        String updatedCookies = cookies;
        try (Response response = client.newCall(appsGet).execute()) {
            if (response.code() == 401 || response.code() == 403) {
                throw new IOException("Not logged in (HTTP " + response.code() + ")");
            }
            if (response.isRedirect()) {
                String location = response.header("Location");
                if (location != null && location.contains("/user/login")) {
                    throw new IOException("Not logged in (redirected to login)");
                }
            }
            String body = response.body() != null ? response.body().string() : "";
            if (body.contains("/user/login") && body.contains("user_name")) {
                throw new IOException("Not logged in (login page returned)");
            }
            updatedCookies = mergeSetCookies(updatedCookies, response);
            formUrl = findTokenFormUrl(body, instance, formUrl);
            csrf = extractCsrf(body, updatedCookies);
            // Newer Forgejo (Codeberg) protects forms with Origin and Sec-Fetch-Site checks
            // instead of a token, so a missing token is fine as long as the page is a real one.
            if (TextUtils.isEmpty(csrf) && !body.contains("name=\"scope\"")
                    && !body.contains("/user/settings/applications")) {
                throw new IOException("Could not find the applications form (not logged in?)");
            }
        }

        // Unique name avoids "already used" collisions across re-logins.
        String uniqueName = tokenName + "-" + Long.toHexString(System.currentTimeMillis());
        // Newer Forgejo (Codeberg) moved the form to /user/settings/applications/tokens/new
        // and answers 405 on the old URL, so try the URL the page declares and then the other.
        String newStyleUrl = instance + "/user/settings/applications/tokens/new";
        java.util.List<String[]> attempts = new java.util.ArrayList<>();
        attempts.add(new String[] { formUrl, "old" });
        attempts.add(new String[] { newStyleUrl, "new" });
        IOException last = null;
        for (String[] attempt : attempts) {
            try {
                return postTokenForm(client, attempt[0], "new".equals(attempt[1]), instance, csrf,
                        updatedCookies, uniqueName);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private String postTokenForm(OkHttpClient client, String url, boolean newStyle,
            String instance, String csrf, String updatedCookies, String uniqueName)
            throws IOException {
        okhttp3.FormBody.Builder formBuilder = new okhttp3.FormBody.Builder();
        if (!TextUtils.isEmpty(csrf)) {
            formBuilder.add("_csrf", csrf);
        }
        formBuilder.add("name", uniqueName);
        if (newStyle) {
            formBuilder.add("resource", "all");
        } else {
            formBuilder.add("scope", ""); // all orgs/repos (public + private + limited)
        }
        // Omit admin so non-admin accounts can mint tokens.
        for (String scope : new String[] { "write:activitypub", "write:issue", "write:misc",
                "write:notification", "write:organization", "write:package",
                "write:repository", "write:user" }) {
            formBuilder.add("scope", scope);
        }

        Request create = new Request.Builder()
                .url(url)
                .header("Cookie", updatedCookies)
                .header("Origin", instance)
                .header("Referer", instance + "/user/settings/applications")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Dest", "document")
                .post(formBuilder.build())
                .build();
        if (!TextUtils.isEmpty(csrf)) {
            create = create.newBuilder().header("X-Csrf-Token", csrf).build();
        }

        try (Response response = client.newCall(create).execute()) {
            String token = parseTokenFromFlashCookie(response);
            if (!TextUtils.isEmpty(token)) {
                return token;
            }
            // Follow redirect manually and parse flash-info from HTML
            String location = response.header("Location");
            if (location != null) {
                String nextCookies = mergeSetCookies(updatedCookies, response);
                String absolute = location.startsWith("http") ? location : instance + location;
                Request follow = new Request.Builder()
                        .url(absolute)
                        .header("Cookie", nextCookies)
                        .header("Accept", "text/html")
                        .build();
                try (Response followed = client.newCall(follow).execute()) {
                    String html = followed.body() != null ? followed.body().string() : "";
                    token = parseTokenFromHtml(html);
                    if (!TextUtils.isEmpty(token)) {
                        return token;
                    }
                }
            }
            String body = response.body() != null ? response.body().string() : "";
            token = parseTokenFromHtml(body);
            if (!TextUtils.isEmpty(token)) {
                return token;
            }
            throw new IOException("Token create failed: HTTP " + response.code()
                    + " at " + url + " (no token in response)");
        }
    }

    private static String mergeSetCookies(String existing, Response response) {
        java.util.List<String> setCookies = response.headers("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) {
            return existing;
        }
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        if (!TextUtils.isEmpty(existing)) {
            for (String part : existing.split(";")) {
                String trimmed = part.trim();
                int eq = trimmed.indexOf('=');
                if (eq > 0) {
                    map.put(trimmed.substring(0, eq), trimmed.substring(eq + 1));
                }
            }
        }
        for (String setCookie : setCookies) {
            String first = setCookie.split(";", 2)[0].trim();
            int eq = first.indexOf('=');
            if (eq > 0) {
                map.put(first.substring(0, eq), first.substring(eq + 1));
            }
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * The access token form posts to /user/settings/applications on older Gitea and Forgejo,
     * but newer Forgejo (Codeberg) posts elsewhere and answers 405 on the old URL. Read the
     * action of the form that holds the scope fields.
     */
    private static String findTokenFormUrl(String html, String instance, String fallback) {
        java.util.regex.Matcher forms = java.util.regex.Pattern
                .compile("<form\\b([^>]*)>([\\s\\S]*?)</form>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html != null ? html : "");
        while (forms.find()) {
            if (!forms.group(2).contains("name=\"scope\"")) {
                continue;
            }
            java.util.regex.Matcher action = java.util.regex.Pattern
                    .compile("action=\"([^\"]*)\"").matcher(forms.group(1));
            if (action.find()) {
                String url = action.group(1).replace("&amp;", "&");
                if (url.startsWith("http")) {
                    return url;
                }
                if (url.startsWith("/")) {
                    return instance + url;
                }
                if (!url.isEmpty()) {
                    return fallback + "/" + url;
                }
            }
        }
        return fallback;
    }

    private static String extractCsrf(String html, String cookies) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("name=\"_csrf\"\\s+value=\"([^\"]+)\"")
                .matcher(html != null ? html : "");
        if (m.find()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile("csrfToken:\\s*'([^']+)'")
                .matcher(html != null ? html : "");
        if (m.find()) {
            return m.group(1);
        }
        String fromCookie = getCookieValue(cookies, "_csrf");
        return fromCookie != null ? Uri.decode(fromCookie) : null;
    }

    private static String parseTokenFromFlashCookie(Response response) {
        for (String setCookie : response.headers("Set-Cookie")) {
            String nameValue = setCookie.split(";", 2)[0];
            int eq = nameValue.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = nameValue.substring(0, eq).trim().toLowerCase(Locale.US);
            if (!name.contains("flash")) {
                continue;
            }
            String decoded = Uri.decode(nameValue.substring(eq + 1));
            // info=<token>&success=<message>
            for (String part : decoded.split("&")) {
                if (part.startsWith("info=")) {
                    String token = Uri.decode(part.substring(5));
                    if (token.matches("[a-fA-F0-9]{40,}")) {
                        return token;
                    }
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\b([a-fA-F0-9]{40})\\b")
                    .matcher(decoded);
            if (m.find()) {
                return m.group(1);
            }
        }
        return null;
    }

    private static String parseTokenFromHtml(String html) {
        if (TextUtils.isEmpty(html)) {
            return null;
        }
        java.util.regex.Matcher flash = java.util.regex.Pattern
                .compile("flash-info[\\s\\S]{0,200}?<p>\\s*([a-fA-F0-9]{40,})\\s*</p>",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
        if (flash.find()) {
            return flash.group(1);
        }
        java.util.regex.Matcher hex = java.util.regex.Pattern
                .compile("\\b([a-fA-F0-9]{40})\\b")
                .matcher(html);
        if (hex.find()) {
            return hex.group(1);
        }
        return null;
    }

    private void finishWithToken(String instance, String token) {
        mDisposables.add(
                Single.fromCallable(() -> {
                            UserService userService = ServiceFactory.get(UserService.class, true,
                                    null, token, null, instance);
                            return userService.getUser()
                                    .map(ApiHelpers::throwOnFailure)
                                    .blockingGet();
                        })
                        .compose(RxUtils::doInBackground)
                        .subscribe(user -> {
                            mCallback.onLoginFinished(token, user, instance);
                            dismissAllowingStateLoss();
                        }, this::failLogin)
        );
    }

    private void failLogin(Throwable error) {
        mLoginInProgress = false;
        mCheckingSession = false;
        setBusy(false);
        mCallback.onLoginFailed(error);
        dismissAllowingStateLoss();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}
