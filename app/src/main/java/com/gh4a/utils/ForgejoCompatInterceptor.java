package com.gh4a.utils;

import android.net.Uri;
import android.util.Log;

import com.gh4a.Gh4Application;
import com.vdurmont.emoji.EmojiParser;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Adapts Forgejo/Gitea API quirks so the GitHub-oriented SDK keeps working:
 * <ul>
 *   <li>{@code stars_count} → {@code stargazers_count}</li>
 *   <li>{@code watchers_count} → {@code subscribers_count}</li>
 *   <li>String {@code repository.owner} → {@code { "login": "..." }}</li>
 *   <li>{@code is_locked} → {@code locked}</li>
 *   <li>{@code per_page} → {@code limit}</li>
 *   <li>GitHub event endpoints → {@code /activities/feeds}, remapped to GitHub event JSON</li>
 *   <li>Single-commit {@code /commits/{sha}} → {@code /git/commits/{sha}}</li>
 *   <li>Missing per-file {@code patch} on commits/PR files → fill from {@code .diff}</li>
 *   <li>Missing {@code /contributors} → synthesize from recent commit authors</li>
 *   <li>Missing commit-comments API → empty list</li>
 *   <li>ISO-8601 offsets → {@code ...Z} for githubsdk FormattedTimeAdapter</li>
 *   <li>Synthetic {@code Link} pagination from {@code X-Total-Count}</li>
 * </ul>
 */
public class ForgejoCompatInterceptor implements Interceptor {
    private static final String TAG = "ForgejoCompat";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final Pattern USER_RECEIVED =
            Pattern.compile("^/api/v1/users/([^/]+)/received_events(?:/public)?$");
    private static final Pattern USER_PERFORMED =
            Pattern.compile("^/api/v1/users/([^/]+)/events(?:/public)?$");
    private static final Pattern USER_ORG_EVENTS =
            Pattern.compile("^/api/v1/users/([^/]+)/events/orgs/([^/]+)$");
    private static final Pattern REPO_EVENTS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/events$");
    private static final Pattern ORG_EVENTS =
            Pattern.compile("^/api/v1/orgs/([^/]+)/events$");
    private static final Pattern PUBLIC_EVENTS =
            Pattern.compile("^/api/v1/events$");
    private static final Pattern NETWORK_EVENTS =
            Pattern.compile("^/api/v1/networks/([^/]+)/([^/]+)/events$");
    private static final Pattern SEARCH_ISSUES =
            Pattern.compile("^/api/v1/search/issues$");
    private static final Pattern SEARCH_REPOS =
            Pattern.compile("^/api/v1/search/repositories$");
    private static final Pattern SEARCH_USERS =
            Pattern.compile("^/api/v1/search/users$");
    // Exact user profile (not /users/{login}/repos etc.).
    private static final Pattern SINGLE_USER =
            Pattern.compile("^/api/v1/users/([^/]+)$");
    private static final Pattern MY_REPOS =
            Pattern.compile("^/api/v1/user/repos$");
    private static final Pattern USER_REPOS_LIST =
            Pattern.compile("^/api/v1/users/([^/]+)/repos$");
    private static final Pattern ORG_REPOS_LIST =
            Pattern.compile("^/api/v1/orgs/([^/]+)/repos$");
    private static final Pattern SEARCH_CODE =
            Pattern.compile("^/api/v1/search/code$");
    private static final Pattern WIKI_PAGES =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/wiki(/.*)?$");
    private static final Pattern GISTS =
            Pattern.compile("^/api/v1/(gists|users/[^/]+/gists)(/.*)?$");
    private static final Pattern SINGLE_COMMIT =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/commits/([^/]+)$");
    private static final Pattern FORGEJO_GIT_COMMIT =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/git/commits/([^/]+)$");
    private static final Pattern COMMIT_COMMENTS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/commits/([^/]+)/comments$");
    private static final Pattern PULL_FILES =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/pulls/([^/]+)/files$");
    private static final Pattern CONTRIBUTORS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/contributors$");
    private static final Pattern COMPARE =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/compare/(.+)$");
    // GitHub lists PR review comments at /pulls/{n}/comments; Forgejo 404s that path.
    private static final Pattern PULL_REVIEW_COMMENTS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/pulls/([^/]+)/comments$");
    private static final Pattern REQUESTED_REVIEWERS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/pulls/([^/]+)/requested_reviewers$");
    private static final Pattern REACTIONS =
            Pattern.compile("^/api/v1/repos/.+/reactions$");
    private static final Pattern REF_DELETE =
            Pattern.compile("^/api/v1/repos/([^/]+/[^/]+)/git/refs/(?:heads|tags)(?:/|%2F)(.+)$");
    private static final Pattern PULL_REVIEWS =
            Pattern.compile("^/api/v1/repos/[^/]+/[^/]+/pulls/\\d+/reviews(/\\d+)?$");
    private static final Pattern REVIEW_COMMENT_REACTIONS = Pattern.compile(
            "^/api/v1/repos/([^/]+/[^/]+)/pulls/comments/(\\d+)(/reactions(?:/\\d+)?)?$");
    private static final Pattern PULL_MERGE =
            Pattern.compile("^/api/v1/repos/[^/]+/[^/]+/pulls/\\d+/merge$");
    private static final Pattern LABEL_BY_NAME =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/labels/([^/\\d][^/]*|\\d*[^/\\d][^/]*)$");
    private static final Pattern REPO_ISSUES_LIST =
            Pattern.compile("^/api/v1/repos/[^/]+/[^/]+/issues$");
    private static final Pattern QUERY_TOKEN =
            Pattern.compile("[^\\s\"]+:\"[^\"]*\"|\"[^\"]*\"|\\S+");
    private static final Pattern COLLABORATORS_LIST =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/collaborators$");
    private static final Pattern RELEASE_REACTIONS =
            Pattern.compile("^/api/v1/repos/[^/]+/[^/]+/releases/\\d+/reactions$");
    private static final Pattern REACTION_DELETE =
            Pattern.compile("^/api/v1/repos/(.+/reactions)/(\\d+)$");
    private static final String[] REACTION_CONTENTS = {
            "+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes" };
    private static final Pattern ISSUE_TIMELINE =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/issues/([^/]+)/timeline$");
    private static final Pattern CHECK_RUNS =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/commits/([^/]+)/check-runs$");
    private static final Pattern REPO_README =
            Pattern.compile("^/api/v1/repos/([^/]+)/([^/]+)/readme$");

    private static final String[] README_CANDIDATES = {
            "README.md", "readme.md", "README.markdown", "README.rst",
            "README.txt", "README", "readme"
    };

    private enum BodyMode {
        NONE,
        ACTIVITY_FEED,
        ISSUE_SEARCH_ARRAY,
        REPO_SEARCH_OBJECT,
        /** {@code /repos/search} → bare array for GitHub list-repos pagination. */
        REPO_LIST_SEARCH_OBJECT,
        USER_SEARCH_OBJECT,
        ISSUE_TIMELINE,
        JSON_ALIAS_ONLY
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        String originalPath = original.url().encodedPath();
        if (PUBLIC_EVENTS.matcher(originalPath).matches()
                || GISTS.matcher(originalPath).matches()
                || COMMIT_COMMENTS.matcher(originalPath).matches()) {
            // Forgejo has no global public timeline, gists, commit-comments, or
            // GitHub-shaped PR review-comments list API.
            return emptyJsonArray(original);
        }
        Matcher reviewComments = PULL_REVIEW_COMMENTS.matcher(originalPath);
        if (reviewComments.matches()) {
            String method = original.method();
            if ("POST".equalsIgnoreCase(method)) {
                return createInlineReviewComment(chain, original, reviewComments.group(1),
                        reviewComments.group(2), reviewComments.group(3));
            }
            if ("GET".equalsIgnoreCase(method)) {
                return listInlineReviewComments(chain, original, reviewComments.group(1),
                        reviewComments.group(2), reviewComments.group(3));
            }
            return emptyJsonArray(original);
        }
        if (REQUESTED_REVIEWERS.matcher(originalPath).matches()) {
            return emptyJsonObject(original);
        }
        if (CHECK_RUNS.matcher(originalPath).matches()) {
            // Forgejo has no GitHub Checks API; return an empty check-runs page.
            return jsonResponse(original, "{\"total_count\":0,\"check_runs\":[]}");
        }
        if (SEARCH_CODE.matcher(originalPath).matches()) {
            // Forgejo has no GitHub-compatible code search API.
            return jsonResponse(original,
                    "{\"total_count\":0,\"incomplete_results\":false,\"items\":[]}");
        }
        if (RELEASE_REACTIONS.matcher(originalPath).matches()) {
            return emptyJsonArray(original);
        }
        if (WIKI_PAGES.matcher(originalPath).matches()) {
            // Missing wikis 404; treat as empty so the wiki screen can show its empty state.
            return emptyJsonArray(original);
        }
        Matcher contributors = CONTRIBUTORS.matcher(originalPath);
        if (contributors.matches() && "GET".equalsIgnoreCase(original.method())) {
            // Forgejo has no GitHub contributors endpoint; approximate from commit authors.
            return synthesizeContributors(chain, original,
                    contributors.group(1), contributors.group(2));
        }
        Matcher singleUser = SINGLE_USER.matcher(originalPath);
        if (singleUser.matches() && "GET".equalsIgnoreCase(original.method())) {
            // Forgejo serves orgs from /users/{name} without type. Prefer /orgs/{name} so
            // UserActivity shows Members instead of Followers.
            Response orgUser = tryOrgAsGitHubUser(chain, original, singleUser.group(1));
            if (orgUser != null) {
                return orgUser;
            }
        }
        Matcher refDelete = REF_DELETE.matcher(original.url().encodedPath());
        if (refDelete.matches() && "DELETE".equalsIgnoreCase(original.method())) {
            // GitHub deletes refs at git/refs/heads/{name}; Forgejo uses /branches/{name}.
            HttpUrl url = original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + refDelete.group(1) + "/branches/"
                            + refDelete.group(2))
                    .build();
            return chain.proceed(original.newBuilder().url(url).build());
        }
        Matcher reviewCommentReaction = REVIEW_COMMENT_REACTIONS.matcher(originalPath);
        if (reviewCommentReaction.matches() && reviewCommentReaction.group(3) == null
                && "DELETE".equalsIgnoreCase(original.method())) {
            String location = REVIEW_COMMENT_LOCATIONS.get(
                    Long.parseLong(reviewCommentReaction.group(2)));
            if (location != null) {
                int split = location.indexOf('/', location.indexOf('/') + 1);
                HttpUrl url = original.url().newBuilder()
                        .encodedPath("/api/v1/repos/" + location.substring(0, split)
                                + location.substring(split))
                        .build();
                return chain.proceed(original.newBuilder().url(url).build());
            }
        }
        if (reviewCommentReaction.matches()) {
            // Review comments are ordinary comments for reactions on Forgejo.
            original = original.newBuilder().url(original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + reviewCommentReaction.group(1)
                            + "/issues/comments/" + reviewCommentReaction.group(2)
                            + (reviewCommentReaction.group(3) != null
                            ? reviewCommentReaction.group(3) : ""))
                    .build()).build();
            originalPath = original.url().encodedPath();
        }
        Matcher prMerge = PULL_MERGE.matcher(originalPath);
        if (prMerge.matches() && "PUT".equalsIgnoreCase(original.method())) {
            return forwardPullMerge(chain, original);
        }
        Matcher labelByName = LABEL_BY_NAME.matcher(originalPath);
        if (labelByName.matches() && !"GET".equalsIgnoreCase(original.method())) {
            // GitHub addresses labels by name, Forgejo by numeric id. Resolve the id first.
            long labelId = findLabelId(chain, original, labelByName.group(1),
                    labelByName.group(2), Uri.decode(labelByName.group(3)));
            if (labelId > 0) {
                HttpUrl url = original.url().newBuilder()
                        .encodedPath("/api/v1/repos/" + labelByName.group(1) + "/"
                                + labelByName.group(2) + "/labels/" + labelId)
                        .build();
                return chain.proceed(original.newBuilder().url(url).build());
            }
        }
        Matcher reactionDelete = REACTION_DELETE.matcher(originalPath);
        if (reactionDelete.matches() && "DELETE".equalsIgnoreCase(original.method())) {
            // Forgejo reactions have no id; delete by content on the collection URL.
            int index = (int) (Long.parseLong(reactionDelete.group(2)) % 100) - 1;
            if (index >= 0 && index < REACTION_CONTENTS.length) {
                try {
                    String json = new JSONObject().put("content", REACTION_CONTENTS[index]).toString();
                    HttpUrl url = original.url().newBuilder()
                            .encodedPath("/api/v1/repos/" + reactionDelete.group(1))
                            .build();
                    return chain.proceed(original.newBuilder()
                            .url(url)
                            .method("DELETE", okhttp3.RequestBody.create(JSON, json))
                            .build());
                } catch (JSONException ignored) {
                }
            }
        }
        if (MY_REPOS.matcher(originalPath).matches() && "GET".equalsIgnoreCase(original.method())) {
            String repoType = original.url().queryParameter("type");
            if (repoType != null && (repoType.equals("owner") || repoType.equals("member")
                    || repoType.equals("public") || repoType.equals("private"))) {
                return synthesizeFilteredMyRepos(chain, original, repoType);
            }
        }
        Request request;
        Matcher userRepos = USER_REPOS_LIST.matcher(originalPath);
        Matcher orgRepos = ORG_REPOS_LIST.matcher(originalPath);
        if ((userRepos.matches() || orgRepos.matches())
                && "GET".equalsIgnoreCase(original.method())) {
            // /users|orgs/{name}/repos ignores GitHub sort; use /repos/search with uid.
            request = rewriteForeignRepoListRequest(chain, original,
                    userRepos.matches() ? userRepos.group(1) : orgRepos.group(1),
                    orgRepos.matches());
        } else {
            request = rewriteRequest(original);
        }
        Matcher readmeMatcher = REPO_README.matcher(original.url().encodedPath());
        if (readmeMatcher.matches()) {
            return synthesizeReadmeResponse(chain, request, readmeMatcher.group(1),
                    readmeMatcher.group(2));
        }
        BodyMode mode = detectBodyMode(original.url().encodedPath(), request.url().encodedPath());
        Response response = chain.proceed(request);
        if (response.code() == 404 && "GET".equalsIgnoreCase(original.method())
                && REPO_ISSUES_LIST.matcher(request.url().encodedPath()).matches()) {
            // Repos with an external tracker or disabled pull requests answer 404 for issues;
            // show an empty list, shaped like the GitHub search page when that was requested.
            response.close();
            if (SEARCH_ISSUES.matcher(originalPath).matches()) {
                return jsonResponse(original,
                        "{\"total_count\":0,\"incomplete_results\":false,\"items\":[]}");
            }
            return emptyJsonArray(original);
        }

        String path = request.url().encodedPath();
        String bodyString = null;
        MediaType contentType = null;

        ResponseBody body = response.body();
        if (body != null && shouldRewriteBody(response, mode)) {
            contentType = body.contentType();
            bodyString = body.string();
        }

        if (bodyString == null) {
            return response;
        }

        try {
            // Forgejo returns a literal JSON null for empty reaction lists.
            if (response.isSuccessful()
                    && REACTIONS.matcher(path).matches()
                    && "null".equals(bodyString.trim())) {
                bodyString = "[]";
            }
            if (response.isSuccessful() && "GET".equalsIgnoreCase(request.method())
                    && REACTIONS.matcher(path).matches()) {
                bodyString = addReactionIds(bodyString);
            }

            String totalCountHeader = response.header("X-Total-Count");
            String rewritten = bodyString;
            if (response.isSuccessful()) {
                switch (mode) {
                    case ACTIVITY_FEED:
                        rewritten = convertActivityFeedsToEvents(bodyString);
                        break;
                    case ISSUE_SEARCH_ARRAY:
                        String mergedWanted = request.url().queryParameter("x_merged");
                        String arrayBody = mergedWanted == null ? bodyString
                                : filterByMerged(bodyString, "true".equals(mergedWanted));
                        rewritten = wrapArrayAsSearchPage(arrayBody, totalCountHeader);
                        break;
                    case REPO_SEARCH_OBJECT:
                        rewritten = convertRepoSearchToSearchPage(bodyString);
                        break;
                    case REPO_LIST_SEARCH_OBJECT: {
                        JSONObject obj = new JSONObject(bodyString);
                        JSONArray data = obj.optJSONArray("data");
                        if (data == null) {
                            data = new JSONArray();
                        }
                        if (totalCountHeader == null && obj.has("total_count")) {
                            totalCountHeader = String.valueOf(
                                    obj.optLong("total_count", data.length()));
                        }
                        rewritten = data.toString();
                        break;
                    }
                    case USER_SEARCH_OBJECT:
                        rewritten = convertUserSearchToSearchPage(bodyString);
                        rewritten = maybeAugmentUserSearchWithExactMatch(
                                chain, request, rewritten);
                        break;
                    case ISSUE_TIMELINE:
                        rewritten = convertIssueTimeline(bodyString);
                        break;
                    default:
                        break;
                }
            }
            rewritten = aliasStarFields(rewritten);
            if (response.isSuccessful() && PULL_REVIEWS.matcher(path).matches()) {
                rewritten = mapReviewStates(rewritten);
            }
            if (response.isSuccessful()) {
                rewritten = maybeEnrichFilePatches(chain, request, path, rewritten);
                rewritten = maybeAddBodyHtml(chain, request, path, rewritten);
            }

            Response.Builder builder = response.newBuilder()
                    .body(ResponseBody.create(contentType != null ? contentType : JSON, rewritten));
            if (totalCountHeader != null && response.header("X-Total-Count") == null) {
                builder.header("X-Total-Count", totalCountHeader);
            }

            if (response.isSuccessful() && response.header("Link") == null
                    && (mode == BodyMode.ACTIVITY_FEED
                    || mode == BodyMode.ISSUE_SEARCH_ARRAY
                    || mode == BodyMode.REPO_SEARCH_OBJECT
                    || mode == BodyMode.REPO_LIST_SEARCH_OBJECT
                    || mode == BodyMode.USER_SEARCH_OBJECT)) {
                String link = buildLinkHeader(request.url(), totalCountHeader,
                        extractItemsArray(rewritten));
                if (link != null) {
                    builder.header("Link", link);
                }
            }
            return builder.build();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to rewrite Forgejo response for " + path, e);
            return response.newBuilder()
                    .body(ResponseBody.create(contentType != null ? contentType : JSON, bodyString))
                    .build();
        }
    }


    /** Forgejo reactions have no id. Encode user id and content so DELETE can map back. */
    private static String addReactionIds(String body) throws JSONException {
        String trimmed = body.trim();
        if (!trimmed.startsWith("[")) {
            return body;
        }
        JSONArray arr = new JSONArray(trimmed);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null || r.has("id")) {
                continue;
            }
            JSONObject user = r.optJSONObject("user");
            long userId = user != null ? user.optLong("id") : 0;
            int index = Arrays.asList(REACTION_CONTENTS).indexOf(r.optString("content"));
            r.put("id", userId * 100 + index + 1);
        }
        return arr.toString();
    }

    private static long findLabelId(Chain chain, Request original, String owner, String repo,
            String name) throws IOException {
        for (int page = 1; page <= 10; page++) {
            HttpUrl url = original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + owner + "/" + repo + "/labels")
                    .query(null)
                    .addQueryParameter("page", String.valueOf(page))
                    .addQueryParameter("limit", "50")
                    .build();
            Response response = chain.proceed(original.newBuilder().url(url).get().build());
            try {
                if (!response.isSuccessful() || response.body() == null) {
                    return 0;
                }
                JSONArray arr = new JSONArray(response.body().string());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject label = arr.optJSONObject(i);
                    if (label != null && name.equals(label.optString("name"))) {
                        return label.optLong("id");
                    }
                }
                if (arr.length() < 50) {
                    return 0;
                }
            } catch (JSONException e) {
                return 0;
            } finally {
                response.close();
            }
        }
        return 0;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * Forgejo /user/repos ignores GitHub's type filter and filtering one page would leave
     * sparse pages. Read every page, filter, and return the requested page of the result.
     */
    private static Response synthesizeFilteredMyRepos(Chain chain, Request original, String type)
            throws IOException {
        int page = 1;
        int perPage = 30;
        try {
            String pageParam = original.url().queryParameter("page");
            if (pageParam != null) {
                page = Math.max(1, Integer.parseInt(pageParam));
            }
            String perPageParam = original.url().queryParameter("per_page");
            if (perPageParam == null) {
                perPageParam = original.url().queryParameter("limit");
            }
            if (perPageParam != null) {
                perPage = Math.max(1, Integer.parseInt(perPageParam));
            }
        } catch (NumberFormatException ignored) {
        }
        String me = Gh4Application.get().getAuthLogin();
        JSONArray matches = new JSONArray();
        try {
            for (int serverPage = 1; serverPage <= 40; serverPage++) {
                HttpUrl url = original.url().newBuilder()
                        .removeAllQueryParameters("per_page")
                        .removeAllQueryParameters("limit")
                        .removeAllQueryParameters("type")
                        .setQueryParameter("page", String.valueOf(serverPage))
                        .setQueryParameter("limit", "50")
                        .build();
                Response response = chain.proceed(rewriteRequest(
                        original.newBuilder().url(url).build()));
                JSONArray chunk;
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        return response;
                    }
                    chunk = new JSONArray(response.body().string());
                } finally {
                    response.close();
                }
                for (int i = 0; i < chunk.length(); i++) {
                    JSONObject repo = chunk.optJSONObject(i);
                    if (repo != null && matchesRepoType(repo, type, me)) {
                        matches.put(repo);
                    }
                }
                if (chunk.length() < 50) {
                    break;
                }
            }
            JSONArray slice = new JSONArray();
            for (int i = (page - 1) * perPage; i < matches.length() && i < page * perPage; i++) {
                slice.put(matches.get(i));
            }
            String body = aliasStarFields(slice.toString());
            Response.Builder builder = new Response.Builder()
                    .request(original)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("X-Total-Count", String.valueOf(matches.length()))
                    .body(ResponseBody.create(JSON, body));
            String link = buildLinkHeader(original.url(), String.valueOf(matches.length()), body);
            if (link != null) {
                builder.header("Link", link);
            }
            return builder.build();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to filter Forgejo repo list", e);
            return chain.proceed(rewriteRequest(original));
        }
    }

    private static boolean matchesRepoType(JSONObject repo, String type, String me) {
        JSONObject owner = repo.optJSONObject("owner");
        String ownerLogin = owner != null ? owner.optString("login", "") : "";
        boolean mine = me != null && me.equalsIgnoreCase(ownerLogin);
        switch (type) {
            case "owner": return mine;
            case "member": return !mine;
            case "private": return repo.optBoolean("private", false);
            default: return !repo.optBoolean("private", false);
        }
    }

    private static final Pattern IMG_SRC = Pattern.compile("(<img\\b[^>]*?\\bsrc=\")([^\"]+)(\")");
    private static final Pattern SERVER_PATH = Pattern.compile(
            "^(raw|media|attachments|avatars|repo-avatars|assets|issues|pulls|user|commit|src|releases)/.*");

    /**
     * Forgejo renders relative image paths as {@code /path} (raw markdown) or
     * {@code https://host/owner/repo/path} (gfm), neither of which serves the file.
     * Point them at {@code /media/<ref>/path}.
     */
    private static String fixRelativeImages(String html, String host, String owner, String repo,
            String ref) {
        final String repoBase = "https://" + host + "/" + owner + "/" + repo + "/";
        Matcher m = IMG_SRC.matcher(html);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String src = m.group(2);
            String path = null;
            if (src.startsWith(repoBase)) {
                String rest = src.substring(repoBase.length());
                if (!SERVER_PATH.matcher(rest).matches()) {
                    path = rest;
                }
            } else if (src.startsWith("/") && !src.startsWith("//")) {
                path = src.substring(1);
            }
            String replacement = m.group(1)
                    + (path != null ? repoBase + "media/" + ref + "/" + path : src) + m.group(3);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * GitHub merges with PUT {merge_method, commit_title, commit_message} and reads
     * {merged: true}. Forgejo uses POST {Do, MergeTitleField, MergeMessageField} and
     * answers with an empty 200.
     */
    private static Response forwardPullMerge(Chain chain, Request original) throws IOException {
        JSONObject in = new JSONObject();
        okhttp3.RequestBody oldBody = original.body();
        if (oldBody != null) {
            okio.Buffer buffer = new okio.Buffer();
            oldBody.writeTo(buffer);
            try {
                in = new JSONObject(buffer.readUtf8());
            } catch (JSONException ignored) {
            }
        }
        String method = in.optString("merge_method", "merge");
        JSONObject out = new JSONObject();
        try {
            out.put("Do", method.equals("rebase") ? "rebase-merge" : method);
            if (in.has("commit_title") && !in.isNull("commit_title")
                    && !in.optString("commit_title").isEmpty()) {
                out.put("MergeTitleField", in.optString("commit_title"));
            }
            if (in.has("commit_message") && !in.isNull("commit_message")
                    && !in.optString("commit_message").isEmpty()) {
                out.put("MergeMessageField", in.optString("commit_message"));
            }
        } catch (JSONException e) {
            return chain.proceed(original);
        }
        Response response = chain.proceed(original.newBuilder()
                .method("POST", okhttp3.RequestBody.create(JSON, out.toString()))
                .build());
        if (!response.isSuccessful()) {
            return response;
        }
        response.close();
        return new Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(JSON,
                        "{\"merged\":true,\"message\":\"Pull Request successfully merged\",\"sha\":\"\"}"))
                .build();
    }

    /** Map Forgejo review states (COMMENT, REQUEST_CHANGES, ...) onto GitHub's enum values. */
    private static String mapReviewStates(String body) throws JSONException {
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r != null) {
                    mapReviewState(r);
                }
            }
            return arr.toString();
        }
        if (trimmed.startsWith("{")) {
            JSONObject r = new JSONObject(trimmed);
            mapReviewState(r);
            return r.toString();
        }
        return body;
    }

    private static void mapReviewState(JSONObject review) throws JSONException {
        String state = review.optString("state", "");
        switch (state) {
            case "COMMENT": review.put("state", "COMMENTED"); break;
            case "REQUEST_CHANGES": review.put("state", "CHANGES_REQUESTED"); break;
            case "REQUEST_REVIEW": review.put("state", "PENDING"); break;
            default: break;
        }
    }

    /** Convert a GitHub diff position (index after the first @@ line) to a file line number. */
    private static int[] positionToLines(String patch, int position) {
        // returns {newLine, oldLine}; newLine 0 means the target is a removed line
        int idx = -1;
        int oldLine = 0;
        int newLine = 0;
        for (String line : patch.split("\n", -1)) {
            if (line.startsWith("@@")) {
                Matcher m = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)").matcher(line);
                if (m.find()) {
                    oldLine = Integer.parseInt(m.group(1)) - 1;
                    newLine = Integer.parseInt(m.group(2)) - 1;
                }
                if (idx >= 0) {
                    idx++;
                } else {
                    idx = 0;
                }
                continue;
            }
            if (idx < 0) {
                continue;
            }
            idx++;
            char c = line.isEmpty() ? ' ' : line.charAt(0);
            if (c == '-') {
                oldLine++;
            } else if (c == '+') {
                newLine++;
            } else {
                oldLine++;
                newLine++;
            }
            if (idx == position) {
                return c == '-' ? new int[] { 0, oldLine } : new int[] { newLine, 0 };
            }
        }
        return new int[] { Math.max(position, 1), 0 };
    }

    private static Response createInlineReviewComment(Chain chain, Request original, String owner,
            String repo, String number) throws IOException {
        JSONObject in = new JSONObject();
        okhttp3.RequestBody oldBody = original.body();
        if (oldBody != null) {
            okio.Buffer buffer = new okio.Buffer();
            oldBody.writeTo(buffer);
            try {
                in = new JSONObject(buffer.readUtf8());
            } catch (JSONException ignored) {
            }
        }
        String path = in.optString("path", "");
        int position = in.optInt("position", 1);
        int[] lines = { Math.max(position, 1), 0 };
        String diffText = fetchPlainText(chain, original,
                "/api/v1/repos/" + owner + "/" + repo + "/pulls/" + number + ".diff");
        if (diffText != null) {
            ParsedFilePatch patch = parseUnifiedDiffPatches(diffText).get(path);
            if (patch != null) {
                lines = positionToLines(patch.patch, position);
            }
        }
        try {
            JSONObject comment = new JSONObject()
                    .put("body", in.optString("body", ""))
                    .put("path", path)
                    .put("new_position", lines[0])
                    .put("old_position", lines[1]);
            JSONObject review = new JSONObject()
                    .put("event", "COMMENT")
                    .put("body", "")
                    .put("comments", new JSONArray().put(comment));
            HttpUrl url = original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + owner + "/" + repo + "/pulls/" + number
                            + "/reviews")
                    .query(null)
                    .build();
            Response created = chain.proceed(original.newBuilder()
                    .url(url)
                    .method("POST", okhttp3.RequestBody.create(JSON, review.toString()))
                    .build());
            try {
                if (!created.isSuccessful() || created.body() == null) {
                    return created;
                }
                JSONObject r = new JSONObject(created.body().string());
                String reviewId = r.optString("id");
                // Read the new comment back so the app receives its id.
                String listPath = "/api/v1/repos/" + owner + "/" + repo + "/pulls/" + number
                        + "/reviews/" + reviewId + "/comments";
                String listText = fetchPlainText(chain, original, listPath);
                JSONArray arr = listText != null ? new JSONArray(listText) : new JSONArray();
                if (arr.length() == 0) {
                    return emptyJsonObject(original);
                }
                JSONObject c = arr.getJSONObject(0);
                rememberReviewComment(c, owner, repo, number);
                JSONObject converted = toGithubReviewComment(c, patchFor(diffText, path));
                addBodyHtml(chain, original, owner + "/" + repo, converted,
                        new int[] { MAX_RENDERED_BODIES }, false);
                return jsonResponse(original, aliasStarFields(converted.toString()));
            } finally {
                created.close();
            }
        } catch (JSONException e) {
            return emptyJsonObject(original);
        }
    }

    /** Forgejo deletes code comments through their review, so remember where each one lives. */
    private static final Map<Long, String> REVIEW_COMMENT_LOCATIONS =
            Collections.synchronizedMap(new HashMap<>());

    private static void rememberReviewComment(JSONObject comment, String owner, String repo,
            String number) {
        REVIEW_COMMENT_LOCATIONS.put(comment.optLong("id"), owner + "/" + repo + "/pulls/" + number
                + "/reviews/" + comment.optLong("pull_request_review_id") + "/comments/"
                + comment.optLong("id"));
    }

    private static String patchFor(String diffText, String path) {
        if (diffText == null) {
            return null;
        }
        ParsedFilePatch p = parseUnifiedDiffPatches(diffText).get(path);
        return p != null ? p.patch : null;
    }

    /** Forgejo comment position is a file line number; GitHub's is a diff index. */
    private static JSONObject toGithubReviewComment(JSONObject c, String patch)
            throws JSONException {
        JSONObject out = new JSONObject(c.toString());
        int newLine = c.optInt("position", 0);
        int oldLine = c.optInt("original_position", 0);
        int diffIndex = newLine;
        if (patch != null) {
            int idx = -1;
            int o = 0;
            int n = 0;
            for (String line : patch.split("\n", -1)) {
                if (line.startsWith("@@")) {
                    Matcher m = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)").matcher(line);
                    if (m.find()) {
                        o = Integer.parseInt(m.group(1)) - 1;
                        n = Integer.parseInt(m.group(2)) - 1;
                    }
                    idx = idx >= 0 ? idx + 1 : 0;
                    continue;
                }
                if (idx < 0) {
                    continue;
                }
                idx++;
                char ch = line.isEmpty() ? ' ' : line.charAt(0);
                if (ch == '-') {
                    o++;
                    if (newLine == 0 && o == oldLine) {
                        diffIndex = idx;
                        break;
                    }
                } else {
                    n++;
                    if (ch != '+') {
                        o++;
                    }
                    if (newLine != 0 && n == newLine) {
                        diffIndex = idx;
                        break;
                    }
                }
            }
        }
        out.put("position", diffIndex);
        out.put("original_position", diffIndex);
        JSONObject reactions = new JSONObject().put("url", "").put("total_count", 0);
        for (String content : REACTION_CONTENTS) {
            reactions.put(content, 0);
        }
        out.put("reactions", reactions);
        if (!out.has("original_commit_id") || out.optString("original_commit_id").isEmpty()) {
            out.put("original_commit_id", out.optString("commit_id", ""));
        }
        return out;
    }

    private static Response listInlineReviewComments(Chain chain, Request original, String owner,
            String repo, String number) throws IOException {
        String base = "/api/v1/repos/" + owner + "/" + repo + "/pulls/" + number;
        String reviewsText = fetchPlainText(chain, original, base + "/reviews");
        if (reviewsText == null) {
            return emptyJsonArray(original);
        }
        try {
            JSONArray reviews = new JSONArray(reviewsText);
            String diffText = null;
            JSONArray out = new JSONArray();
            for (int i = 0; i < reviews.length(); i++) {
                JSONObject review = reviews.optJSONObject(i);
                if (review == null || review.optInt("comments_count", 0) == 0) {
                    continue;
                }
                String text = fetchPlainText(chain, original,
                        base + "/reviews/" + review.optString("id") + "/comments");
                if (text == null) {
                    continue;
                }
                if (diffText == null) {
                    diffText = fetchPlainText(chain, original, base + ".diff");
                    if (diffText == null) {
                        diffText = "";
                    }
                }
                JSONArray comments = new JSONArray(text);
                for (int j = 0; j < comments.length(); j++) {
                    JSONObject c = comments.optJSONObject(j);
                    if (c != null) {
                        rememberReviewComment(c, owner, repo, number);
                        out.put(toGithubReviewComment(c,
                                patchFor(diffText, c.optString("path"))));
                    }
                }
            }
            addBodyHtml(chain, original, owner + "/" + repo, out, new int[] { MAX_RENDERED_BODIES },
                    false);
            return jsonResponse(original, aliasStarFields(out.toString()));
        } catch (JSONException e) {
            return emptyJsonArray(original);
        }
    }

    private static String filterByMerged(String body, boolean merged) throws JSONException {
        JSONArray in = new JSONArray(body);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject issue = in.optJSONObject(i);
            JSONObject pr = issue != null ? issue.optJSONObject("pull_request") : null;
            if (pr != null && pr.optBoolean("merged", false) == merged) {
                out.put(issue);
            }
        }
        return out.toString();
    }

    private static final Pattern BODY_PATHS = Pattern.compile(
            "^/api/v1/repos/([^/]+)/([^/]+)/(issues|pulls|releases|commits|git/commits)(/.*)?$");
    private static final int MAX_RENDERED_BODIES = 40;
    private static final Map<String, String> RENDERED_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 200;
                }
            });

    /**
     * GitHub clients read rendered markdown from {@code body_html}. Forgejo only sends
     * {@code body}, so descriptions and comments come out blank. Render each body with
     * {@code POST /markdown} and add the result as {@code body_html}.
     */
    private static String maybeAddBodyHtml(Chain chain, Request request, String path,
            String body) throws IOException, JSONException {
        Matcher m = BODY_PATHS.matcher(path);
        if (!m.matches() || body == null || !body.contains("\"body\"")) {
            return body;
        }
        String trimmed = body.trim();
        Object root = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
        int[] budget = { MAX_RENDERED_BODIES };
        addBodyHtml(chain, request, m.group(1) + "/" + m.group(2), root, budget,
                root instanceof JSONObject);
        return root.toString();
    }

    private static void addBodyHtml(Chain chain, Request request, String context, Object node,
            int[] budget, boolean single) throws IOException, JSONException {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                addBodyHtml(chain, request, context, arr.opt(i), budget, false);
            }
        } else if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            Object text = obj.opt("body");
            if (text instanceof String && !((String) text).isEmpty() && !obj.has("body_html")) {
                obj.put("body_html", renderMarkdown(chain, request, context, (String) text, budget));
            }
            if (!obj.has("reactions") && budget[0] > 0) {
                String reactionsPath = null;
                if (obj.has("id") && obj.has("body")
                        && (obj.has("issue_url") || obj.has("pull_request_url"))) {
                    reactionsPath = "issues/comments/" + obj.optLong("id");
                } else if (single && obj.has("number") && obj.has("title")
                        && obj.has("state")) {
                    reactionsPath = "issues/" + obj.optLong("number");
                }
                if (reactionsPath != null) {
                    budget[0]--;
                    obj.put("reactions", fetchReactionSummary(chain, request,
                            "/api/v1/repos/" + context + "/" + reactionsPath + "/reactions"));
                }
            }
            Iterator<String> keys = obj.keys();
            List<Object> children = new ArrayList<>();
            while (keys.hasNext()) {
                Object child = obj.opt(keys.next());
                if (child instanceof JSONObject || child instanceof JSONArray) {
                    children.add(child);
                }
            }
            for (Object child : children) {
                addBodyHtml(chain, request, context, child, budget, false);
            }
        }
    }

    /** GitHub embeds reaction counts in issue and comment JSON. Forgejo does not. */
    private static JSONObject fetchReactionSummary(Chain chain, Request request, String path)
            throws IOException, JSONException {
        JSONObject summary = new JSONObject().put("url", "").put("total_count", 0);
        for (String content : REACTION_CONTENTS) {
            summary.put(content, 0);
        }
        Response response = chain.proceed(request.newBuilder()
                .url(request.url().newBuilder().encodedPath(path).query(null).build())
                .get()
                .build());
        try {
            if (!response.isSuccessful() || response.body() == null) {
                return summary;
            }
            String text = response.body().string().trim();
            if (!text.startsWith("[")) {
                return summary;
            }
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null || !summary.has(r.optString("content"))) {
                    continue;
                }
                summary.put(r.optString("content"), summary.optInt(r.optString("content")) + 1);
                summary.put("total_count", summary.optInt("total_count") + 1);
            }
        } finally {
            response.close();
        }
        return summary;
    }

    private static String renderMarkdown(Chain chain, Request request, String context,
            String text, int[] budget) throws IOException, JSONException {
        String cacheKey = context + "\n" + text;
        String cached = RENDERED_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String html = null;
        if (budget[0] > 0) {
            budget[0]--;
            JSONObject payload = new JSONObject()
                    .put("Text", text)
                    .put("Mode", "gfm")
                    .put("Context", context);
            HttpUrl url = request.url().newBuilder()
                    .encodedPath("/api/v1/markdown")
                    .query(null)
                    .build();
            Response rendered = chain.proceed(request.newBuilder()
                    .url(url)
                    .post(okhttp3.RequestBody.create(JSON, payload.toString()))
                    .header("Accept", "text/html")
                    .build());
            try {
                if (rendered.isSuccessful() && rendered.body() != null) {
                    html = rendered.body().string();
                }
            } finally {
                rendered.close();
            }
        }
        if (html == null) {
            html = "<p>" + text.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\n", "<br>") + "</p>";
            return html;
        }
        html = EmojiParser.parseToUnicode(html);
        int slash = context.indexOf('/');
        if (slash > 0) {
            html = fixRelativeImages(html, request.url().host(), context.substring(0, slash),
                    context.substring(slash + 1), "HEAD");
        }
        RENDERED_CACHE.put(cacheKey, html);
        return html;
    }

    /**
     * Forgejo's commit and PR file JSON lists filenames/status but omit GitHub's
     * {@code patch} field. Without it, OctoDroid leaves file rows non-clickable.
     * Fill patches (and missing add/del counts) from the matching {@code .diff}.
     */
    private static String maybeEnrichFilePatches(Chain chain, Request request, String path,
            String body) throws IOException, JSONException {
        Matcher commit = FORGEJO_GIT_COMMIT.matcher(path);
        Matcher pullFiles = PULL_FILES.matcher(path);
        Matcher compare = COMPARE.matcher(path);
        final String diffPath;
        final JSONArray files;
        final JSONObject wrapperObj;
        if (commit.matches()) {
            JSONObject obj = new JSONObject(body);
            files = obj.optJSONArray("files");
            if (files == null || files.length() == 0 || !filesNeedPatches(files)) {
                return body;
            }
            diffPath = path + ".diff";
            wrapperObj = obj;
        } else if (pullFiles.matches()) {
            String trimmed = body.trim();
            if (!trimmed.startsWith("[")) {
                return body;
            }
            files = new JSONArray(body);
            if (files.length() == 0 || !filesNeedPatches(files)) {
                return body;
            }
            diffPath = "/api/v1/repos/" + pullFiles.group(1) + "/" + pullFiles.group(2)
                    + "/pulls/" + pullFiles.group(3) + ".diff";
            wrapperObj = null;
        } else if (compare.matches()) {
            JSONObject obj = new JSONObject(body);
            // GitHub uses total_commits via Moshi; keep Forgejo's snake_case aliasing.
            if (!obj.has("total_commits") && obj.has("totalCommits")) {
                obj.put("total_commits", obj.opt("totalCommits"));
            }
            if (!obj.has("status") || obj.isNull("status")) {
                int total = obj.optInt("total_commits",
                        obj.optJSONArray("commits") != null
                                ? obj.optJSONArray("commits").length() : 0);
                obj.put("status", total > 0 ? "ahead" : "identical");
                obj.put("ahead_by", total);
                obj.put("behind_by", 0);
                obj.put("total_commits", total);
            }
            files = obj.optJSONArray("files");
            if (files == null || files.length() == 0 || !filesNeedPatches(files)) {
                return obj.toString();
            }
            // API .../compare/{base}...{head}.diff often 500s; the HTML compare.diff works.
            diffPath = "/" + compare.group(1) + "/" + compare.group(2)
                    + "/compare/" + compare.group(3) + ".diff";
            wrapperObj = obj;
        } else {
            return body;
        }

        String diffText = fetchPlainText(chain, request, diffPath);
        if (diffText == null || diffText.isEmpty()) {
            return wrapperObj != null ? wrapperObj.toString() : body;
        }
        Map<String, ParsedFilePatch> patches = parseUnifiedDiffPatches(diffText);
        if (patches.isEmpty()) {
            return wrapperObj != null ? wrapperObj.toString() : body;
        }
        applyParsedPatches(files, patches);
        if (wrapperObj != null) {
            wrapperObj.put("files", files);
            return wrapperObj.toString();
        }
        return files.toString();
    }

    /**
     * Build a GitHub-shaped contributors list from recent commit authors.
     * Forgejo has no {@code /repos/{owner}/{repo}/contributors} endpoint.
     */
    private static Response synthesizeContributors(Chain chain, Request original,
            String owner, String repo) throws IOException {
        String pageParam = original.url().queryParameter("page");
        int page = 1;
        if (pageParam != null) {
            try {
                page = Integer.parseInt(pageParam);
            } catch (NumberFormatException ignored) {
            }
        }
        // Approximate a single page: page>1 is empty so the pager stops.
        if (page > 1) {
            return emptyJsonArray(original);
        }

        Map<String, JSONObject> byLogin = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        final int pageSize = 50;
        final int maxPages = 4;
        for (int p = 1; p <= maxPages; p++) {
            HttpUrl commitsUrl = original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + owner + "/" + repo + "/commits")
                    .query(null)
                    .addQueryParameter("limit", String.valueOf(pageSize))
                    .addQueryParameter("page", String.valueOf(p))
                    .build();
            Response resp = chain.proceed(original.newBuilder().url(commitsUrl).get().build());
            String body;
            try {
                if (!resp.isSuccessful() || resp.body() == null) {
                    break;
                }
                body = resp.body().string();
            } finally {
                resp.close();
            }
            if (body == null || body.trim().isEmpty() || "null".equals(body.trim())) {
                break;
            }
            try {
                JSONArray commits = new JSONArray(body);
                if (commits.length() == 0) {
                    break;
                }
                for (int i = 0; i < commits.length(); i++) {
                    JSONObject commit = commits.optJSONObject(i);
                    if (commit == null) {
                        continue;
                    }
                    JSONObject author = commit.optJSONObject("author");
                    if (author == null) {
                        continue;
                    }
                    String login = author.optString("login", "");
                    if (login.isEmpty()) {
                        login = author.optString("username", "");
                    }
                    if (login.isEmpty()) {
                        continue;
                    }
                    counts.put(login, counts.containsKey(login) ? counts.get(login) + 1 : 1);
                    if (!byLogin.containsKey(login)) {
                        byLogin.put(login, author);
                    }
                }
                if (commits.length() < pageSize) {
                    break;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Failed to parse commits while synthesizing contributors", e);
                break;
            }
        }

        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(counts.entrySet());
        Collections.sort(ranked, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });

        JSONArray out = new JSONArray();
        try {
            for (Map.Entry<String, Integer> entry : ranked) {
                JSONObject user = byLogin.get(entry.getKey());
                if (user == null) {
                    continue;
                }
                JSONObject copy = aliasInObject(new JSONObject(user.toString()));
                copy.put("contributions", entry.getValue());
                if (!copy.has("type") || copy.isNull("type")) {
                    copy.put("type", "User");
                }
                out.put(copy);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Failed to build contributors list", e);
            return emptyJsonArray(original);
        }
        return jsonResponse(original, out.toString());
    }

    private static boolean filesNeedPatches(JSONArray files) throws JSONException {
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) {
                continue;
            }
            String patch = file.optString("patch", "");
            if (patch.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static void applyParsedPatches(JSONArray files, Map<String, ParsedFilePatch> patches)
            throws JSONException {
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) {
                continue;
            }
            String name = file.optString("filename", "");
            ParsedFilePatch parsed = patches.get(name);
            if (parsed == null && file.has("previous_filename")) {
                parsed = patches.get(file.optString("previous_filename", ""));
            }
            if (parsed == null) {
                continue;
            }
            boolean filledPatch = false;
            if (file.optString("patch", "").isEmpty()) {
                file.put("patch", parsed.patch);
                filledPatch = true;
            }
            // Commit file entries usually lack counts; PR files often already have them.
            if (file.optInt("additions", 0) == 0 && file.optInt("deletions", 0) == 0) {
                file.put("additions", parsed.additions);
                file.put("deletions", parsed.deletions);
            }
            int additions = file.optInt("additions", 0);
            int deletions = file.optInt("deletions", 0);
            if (file.optInt("changes", 0) == 0) {
                file.put("changes", additions + deletions);
            }
            if (filledPatch && Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Filled patch for " + name + " (+" + additions + "/-" + deletions + ")");
            }
        }
    }

    private static final class ParsedFilePatch {
        final String patch;
        final int additions;
        final int deletions;

        ParsedFilePatch(String patch, int additions, int deletions) {
            this.patch = patch;
            this.additions = additions;
            this.deletions = deletions;
        }
    }

    /**
     * Split a unified diff into GitHub-style per-file {@code patch} blobs (hunks only).
     */
    static Map<String, ParsedFilePatch> parseUnifiedDiffPatches(String diffText) {
        Map<String, ParsedFilePatch> out = new HashMap<>();
        String[] lines = diffText.split("\n", -1);
        String currentPath = null;
        StringBuilder hunks = null;
        int additions = 0;
        int deletions = 0;
        boolean inHunks = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("diff --git ")) {
                finishParsedPatch(out, currentPath, hunks, additions, deletions);
                currentPath = null;
                hunks = new StringBuilder();
                additions = 0;
                deletions = 0;
                inHunks = false;
                // Prefer the b/ path: "diff --git a/foo b/foo"
                int bIdx = line.lastIndexOf(" b/");
                if (bIdx >= 0) {
                    currentPath = line.substring(bIdx + 3);
                }
                continue;
            }
            if (currentPath == null) {
                continue;
            }
            if (!inHunks && line.startsWith("+++ ")) {
                String plus = line.substring(4).trim();
                if (plus.startsWith("b/")) {
                    currentPath = plus.substring(2);
                } else if (!"/dev/null".equals(plus)) {
                    currentPath = plus;
                }
                continue;
            }
            if (line.startsWith("@@")) {
                inHunks = true;
            }
            if (!inHunks) {
                continue;
            }
            if (hunks.length() > 0) {
                hunks.append('\n');
            }
            hunks.append(line);
            if (line.startsWith("+") && !line.startsWith("+++")) {
                additions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        finishParsedPatch(out, currentPath, hunks, additions, deletions);
        return out;
    }

    private static void finishParsedPatch(Map<String, ParsedFilePatch> out, String path,
            StringBuilder hunks, int additions, int deletions) {
        if (path == null || hunks == null || hunks.length() == 0) {
            return;
        }
        out.put(path, new ParsedFilePatch(hunks.toString(), additions, deletions));
    }

    private static String fetchPlainText(Chain chain, Request template, String encodedPath)
            throws IOException {
        HttpUrl url = template.url().newBuilder()
                .encodedPath(encodedPath)
                .query(null)
                .build();
        Response resp = chain.proceed(template.newBuilder().url(url).get().build());
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            return resp.body().string();
        } finally {
            resp.close();
        }
    }

    private static Response emptyJsonArray(Request original) {
        return new Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(JSON, "[]"))
                .build();
    }

    private static Response emptyJsonObject(Request original) {
        return jsonResponse(original, "{}");
    }

    private static Response jsonResponse(Request original, String body) {
        return new Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(JSON, body))
                .build();
    }

    /**
     * Forgejo timeline items use {@code type} (comment/close/pull_push/…) while the GitHub
     * SDK expects {@code event} (commented/closed/head_ref_force_pushed/…). Also copy
     * {@code user} → {@code actor} for event items.
     */
    private static String convertIssueTimeline(String body) throws JSONException {
        JSONArray in = new JSONArray(body);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            JSONObject item = in.getJSONObject(i);
            String type = item.optString("type", item.optString("event", ""));
            String event = mapTimelineTypeToEvent(type);
            if (event == null) {
                continue;
            }
            item.put("event", event);
            if ("renamed".equals(event) && item.has("old_title")) {
                item.put("rename", new JSONObject()
                        .put("from", item.optString("old_title", ""))
                        .put("to", item.optString("new_title", "")));
            }
            if (!item.has("actor") || item.isNull("actor")) {
                Object user = item.opt("user");
                if (user instanceof JSONObject) {
                    item.put("actor", user);
                }
            }
            out.put(item);
        }
        return out.toString();
    }

    private static String mapTimelineTypeToEvent(String type) {
        if (type == null || type.isEmpty()) {
            return null;
        }
        switch (type) {
            case "comment":
                return "commented";
            case "close":
            case "closed":
                return "closed";
            case "reopen":
            case "reopened":
                return "reopened";
            case "merge":
            case "merged":
                return "merged";
            case "label":
            case "labeled":
                return "labeled";
            case "unlabel":
            case "unlabeled":
                return "unlabeled";
            case "milestone":
            case "milestoned":
                return "milestoned";
            case "demilestone":
            case "demilestoned":
                return "demilestoned";
            case "assignees":
            case "assigned":
                return "assigned";
            case "unassigned":
                return "unassigned";
            case "lock":
            case "locked":
                return "locked";
            case "unlock":
            case "unlocked":
                return "unlocked";
            case "rename":
            case "renamed":
                return "renamed";
            case "review_requested":
                return "review_requested";
            case "review_request_removed":
                return "review_request_removed";
            case "pull_push":
            case "commit_ref":
                // Forgejo push markers lack GitHub commit metadata; skip them.
                return null;
            case "change_title":
                return "renamed";
            // Already GitHub-shaped
            case "commented":
            case "head_ref_force_pushed":
            case "head_ref_deleted":
            case "head_ref_restored":
            case "ready_for_review":
            case "convert_to_draft":
            case "review_dismissed":
            case "cross-referenced":
            case "transferred":
                return type;
            default:
                // Unknown Forgejo types: drop rather than fail Moshi parsing.
                return null;
        }
    }

    /**
     * GitHub's {@code GET /repos/.../readme} (HTML via Accept) has no Forgejo twin.
     * Fetch a README candidate as raw markdown, render via {@code /markdown/raw}, and
     * return the HTML so {@code getReadmeHtml} keeps working.
     */
    private static Response synthesizeReadmeResponse(Chain chain, Request original,
            String owner, String repo) throws IOException {
        if ("HEAD".equalsIgnoreCase(original.method())) {
            for (String name : README_CANDIDATES) {
                HttpUrl.Builder probe = original.url().newBuilder()
                        .encodedPath("/api/v1/repos/" + owner + "/" + repo + "/raw/" + name);
                Response head = chain.proceed(original.newBuilder()
                        .url(probe.build())
                        .head()
                        .build());
                int code = head.code();
                head.close();
                if (code >= 200 && code < 300) {
                    return new Response.Builder()
                            .request(original)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(ResponseBody.create(JSON, ""))
                            .build();
                }
            }
            return new Response.Builder()
                    .request(original)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(ResponseBody.create(JSON, "{\"message\":\"Not Found\"}"))
                    .build();
        }

        String ref = original.url().queryParameter("ref");
        String markdown = null;
        for (String name : README_CANDIDATES) {
            HttpUrl.Builder rawUrl = original.url().newBuilder()
                    .encodedPath("/api/v1/repos/" + owner + "/" + repo + "/raw/" + name);
            if (ref != null) {
                rawUrl.setQueryParameter("ref", ref);
            } else {
                rawUrl.removeAllQueryParameters("ref");
            }
            Response rawResp = chain.proceed(original.newBuilder()
                    .url(rawUrl.build())
                    .get()
                    .build());
            try {
                if (rawResp.isSuccessful() && rawResp.body() != null) {
                    markdown = rawResp.body().string();
                    break;
                }
            } finally {
                rawResp.close();
            }
        }
        if (markdown == null) {
            return new Response.Builder()
                    .request(original)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body(ResponseBody.create(JSON, "{\"message\":\"Not Found\"}"))
                    .build();
        }

        HttpUrl markdownUrl = original.url().newBuilder()
                .encodedPath("/api/v1/markdown/raw")
                .query(null)
                .build();
        Request markdownReq = original.newBuilder()
                .url(markdownUrl)
                .post(okhttp3.RequestBody.create(
                        MediaType.parse("text/plain; charset=utf-8"), markdown))
                .header("Content-Type", "text/plain; charset=utf-8")
                .build();
        Response rendered = chain.proceed(markdownReq);
        if (!rendered.isSuccessful() || rendered.body() == null) {
            // Fall back to wrapping the raw markdown in a pre tag so the UI still shows
            // something rather than "No README".
            rendered.close();
            String escaped = markdown
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            String html = "<pre>" + escaped + "</pre>";
            return new Response.Builder()
                    .request(original)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(MediaType.parse("text/html; charset=utf-8"), html))
                    .build();
        }
        String html = EmojiParser.parseToUnicode(rendered.body().string());
        rendered.close();
        html = fixRelativeImages(html, original.url().host(), owner, repo,
                ref != null ? ref : "HEAD");
        return new Response.Builder()
                .request(original)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("text/html; charset=utf-8"), html))
                .build();
    }

    private static BodyMode detectBodyMode(String originalPath, String rewrittenPath) {
        if (SEARCH_ISSUES.matcher(originalPath).matches()
                || rewrittenPath.contains("/repos/issues/search")) {
            return BodyMode.ISSUE_SEARCH_ARRAY;
        }
        // User/org repo lists rewritten to /repos/search need a bare array, not SearchPage.
        if ((USER_REPOS_LIST.matcher(originalPath).matches()
                || ORG_REPOS_LIST.matcher(originalPath).matches())
                && rewrittenPath.equals("/api/v1/repos/search")) {
            return BodyMode.REPO_LIST_SEARCH_OBJECT;
        }
        if (SEARCH_REPOS.matcher(originalPath).matches()
                || rewrittenPath.equals("/api/v1/repos/search")) {
            return BodyMode.REPO_SEARCH_OBJECT;
        }
        if (SEARCH_USERS.matcher(originalPath).matches()
                || rewrittenPath.equals("/api/v1/users/search")) {
            return BodyMode.USER_SEARCH_OBJECT;
        }
        if (rewrittenPath.contains("/activities/feeds")) {
            return BodyMode.ACTIVITY_FEED;
        }
        if (ISSUE_TIMELINE.matcher(originalPath).matches()
                || ISSUE_TIMELINE.matcher(rewrittenPath).matches()) {
            return BodyMode.ISSUE_TIMELINE;
        }
        return BodyMode.JSON_ALIAS_ONLY;
    }

    private static boolean shouldRewriteBody(Response response, BodyMode mode) {
        if (!response.isSuccessful()) {
            return false;
        }
        if (mode != BodyMode.JSON_ALIAS_ONLY && mode != BodyMode.NONE) {
            return true;
        }
        String ct = response.header("Content-Type");
        return ct != null && ct.contains("json");
    }

    private static Request rewriteRequest(Request original) {
        HttpUrl url = original.url();
        String path = url.encodedPath();

        HttpUrl.Builder urlBuilder = url.newBuilder();
        boolean changed = false;

        // Forgejo uses limit instead of per_page
        String perPage = url.queryParameter("per_page");
        if (perPage != null) {
            urlBuilder.removeAllQueryParameters("per_page");
            if (url.queryParameter("limit") == null) {
                urlBuilder.addQueryParameter("limit", perPage);
            }
            changed = true;
        }

        Matcher m;
        if ((m = USER_RECEIVED.matcher(path)).matches()) {
            urlBuilder.encodedPath("/api/v1/users/" + m.group(1) + "/activities/feeds");
            changed = true;
        } else if ((m = USER_ORG_EVENTS.matcher(path)).matches()) {
            urlBuilder.encodedPath("/api/v1/orgs/" + m.group(2) + "/activities/feeds");
            changed = true;
        } else if ((m = USER_PERFORMED.matcher(path)).matches()) {
            urlBuilder.encodedPath("/api/v1/users/" + m.group(1) + "/activities/feeds");
            if (url.queryParameter("only-performed-by") == null) {
                urlBuilder.addQueryParameter("only-performed-by", "true");
            }
            changed = true;
        } else if ((m = REPO_EVENTS.matcher(path)).matches()
                || (m = NETWORK_EVENTS.matcher(path)).matches()) {
            urlBuilder.encodedPath(
                    "/api/v1/repos/" + m.group(1) + "/" + m.group(2) + "/activities/feeds");
            changed = true;
        } else if ((m = ORG_EVENTS.matcher(path)).matches()) {
            urlBuilder.encodedPath("/api/v1/orgs/" + m.group(1) + "/activities/feeds");
            changed = true;
        } else if ((m = COLLABORATORS_LIST.matcher(path)).matches()
                && "GET".equalsIgnoreCase(original.method())) {
            // Forgejo /collaborators omits the owner and org members. /assignees has them,
            // which is what issue assignee pickers need.
            urlBuilder.encodedPath(
                    "/api/v1/repos/" + m.group(1) + "/" + m.group(2) + "/assignees");
            changed = true;
        } else if (MY_REPOS.matcher(path).matches()) {
            // GitHub sort=pushed|updated|created|full_name + direction → Forgejo order_by.
            applyMyReposOrderBy(url, urlBuilder);
            changed = true;
        } else if (SEARCH_ISSUES.matcher(path).matches()) {
            rewriteIssueSearch(url, urlBuilder);
            changed = true;
        } else if (SEARCH_REPOS.matcher(path).matches()) {
            urlBuilder.encodedPath("/api/v1/repos/search");
            applyRepoSearchSort(url, urlBuilder);
            String q = url.queryParameter("q");
            if (q != null) {
                // OctoDroid appends "fork:true"; Forgejo treats unknown qualifiers as text.
                String cleaned = q.replace("fork:true", "")
                        .replace("fork:only", "")
                        .replaceAll("\\s+", " ")
                        .trim();
                urlBuilder.setQueryParameter("q", cleaned);
            }
            if (url.queryParameter("limit") == null) {
                urlBuilder.addQueryParameter("limit", "30");
            }
            changed = true;
        } else if (SEARCH_USERS.matcher(path).matches()) {
            urlBuilder.encodedPath("/api/v1/users/search");
            // Forgejo users/search has no GitHub-compatible sort.
            urlBuilder.removeAllQueryParameters("sort");
            urlBuilder.removeAllQueryParameters("order");
            if (url.queryParameter("limit") == null) {
                urlBuilder.addQueryParameter("limit", "30");
            }
            changed = true;
        } else if ((m = SINGLE_COMMIT.matcher(path)).matches()) {
            // Forgejo serves single commits under /git/commits/{sha}, not /commits/{sha}.
            urlBuilder.encodedPath("/api/v1/repos/" + m.group(1) + "/" + m.group(2)
                    + "/git/commits/" + m.group(3));
            changed = true;
        }

        if (!changed) {
            return original;
        }
        return original.newBuilder().url(urlBuilder.build()).build();
    }

    /**
     * Map GitHub issue search query {@code is:issue is:open author:user} onto
     * Forgejo. Repo-scoped queries use {@code /repos/{owner}/{repo}/issues};
     * cross-repo ("My issues") keep {@code /repos/issues/search}.
     * Putting {@code repo:owner/name} into Forgejo's {@code q} returns nothing —
     * it is a text keyword, not a qualifier.
     */
    private static void rewriteIssueSearch(HttpUrl url, HttpUrl.Builder urlBuilder) {
        String q = url.queryParameter("q");
        String ghSort = url.queryParameter("sort");
        String ghOrder = url.queryParameter("order");
        if (ghOrder == null) {
            ghOrder = url.queryParameter("direction");
        }
        urlBuilder.removeAllQueryParameters("q");
        urlBuilder.removeAllQueryParameters("sort");
        urlBuilder.removeAllQueryParameters("order");
        urlBuilder.removeAllQueryParameters("direction");
        urlBuilder.removeAllQueryParameters("created");
        urlBuilder.removeAllQueryParameters("assigned");
        urlBuilder.removeAllQueryParameters("mentioned");
        urlBuilder.removeAllQueryParameters("type");
        urlBuilder.removeAllQueryParameters("state");
        urlBuilder.removeAllQueryParameters("assignee");
        urlBuilder.removeAllQueryParameters("labels");

        String type = "issues";
        String state = "open";
        boolean created = false;
        boolean assigned = false;
        boolean mentioned = false;
        StringBuilder leftover = new StringBuilder();

        String owner = null;
        String repo = null;
        String assignee = null;
        String label = null;
        String author = null;
        String mentionedUser = null;
        String milestone = null;
        String mergedFilter = null;
        if (q != null) {
            Matcher tokens = QUERY_TOKEN.matcher(q.trim());
            while (tokens.find()) {
                String token = tokens.group();
                if (token.equals("is:issue") || token.equals("is:issues")
                        || token.equals("type:issue") || token.equals("type:issues")) {
                    type = "issues";
                } else if (token.equals("is:pr") || token.equals("is:pull-request")
                        || token.equals("is:pulls") || token.equals("type:pr")
                        || token.equals("type:pulls")) {
                    type = "pulls";
                } else if (token.equals("is:merged")) {
                    mergedFilter = "true";
                    state = "closed";
                } else if (token.equals("is:unmerged")) {
                    mergedFilter = "false";
                } else if (token.equals("is:open") || token.equals("state:open")) {
                    state = "open";
                } else if (token.equals("is:closed") || token.equals("state:closed")) {
                    state = "closed";
                } else if (token.startsWith("author:")) {
                    created = true;
                    author = unquote(token.substring("author:".length()));
                } else if (token.startsWith("assignee:")) {
                    assigned = true;
                    assignee = unquote(token.substring("assignee:".length()));
                } else if (token.startsWith("mentions:")) {
                    mentioned = true;
                    mentionedUser = unquote(token.substring("mentions:".length()));
                } else if (token.startsWith("involves:") || token.startsWith("-involves:")) {
                    // Closest single-flag approximation on Forgejo cross-repo search.
                    created = true;
                } else if (token.startsWith("label:")) {
                    label = unquote(token.substring("label:".length()));
                } else if (token.startsWith("milestone:")) {
                    milestone = unquote(token.substring("milestone:".length()));
                } else if (token.startsWith("no:")) {
                    // Forgejo has no "unset" filter for assignee, label or milestone.
                    continue;
                } else if (token.startsWith("repo:")) {
                    String full = token.substring("repo:".length());
                    int slash = full.indexOf('/');
                    if (slash > 0) {
                        owner = full.substring(0, slash);
                        repo = full.substring(slash + 1);
                    }
                } else if (!token.isEmpty()) {
                    if (leftover.length() > 0) {
                        leftover.append(' ');
                    }
                    leftover.append(token);
                }
            }
        }

        if (mergedFilter != null) {
            // Forgejo cannot filter by merge state; the response is filtered in wrapArrayAsSearchPage.
            urlBuilder.setQueryParameter("x_merged", mergedFilter);
        }
        String forgejoSort = mapGithubIssueSort(ghSort, ghOrder);
        if (owner != null && repo != null) {
            // Per-repo list: Forgejo search owner/repo params do not scope to one repo.
            urlBuilder.encodedPath("/api/v1/repos/" + owner + "/" + repo + "/issues");
            urlBuilder.setQueryParameter("type", type);
            urlBuilder.setQueryParameter("state", state);
            if (assignee != null && !assignee.isEmpty()) {
                urlBuilder.setQueryParameter("assigned_by", assignee);
            }
            if (author != null && !author.isEmpty()) {
                urlBuilder.setQueryParameter("created_by", author);
            }
            if (mentionedUser != null && !mentionedUser.isEmpty()) {
                urlBuilder.setQueryParameter("mentioned_by", mentionedUser);
            }
            if (milestone != null && !milestone.isEmpty()) {
                urlBuilder.setQueryParameter("milestones", milestone);
            }
            if (label != null && !label.isEmpty()) {
                urlBuilder.setQueryParameter("labels", label);
            }
            if (leftover.length() > 0) {
                urlBuilder.setQueryParameter("q", leftover.toString());
            }
            if (forgejoSort != null) {
                urlBuilder.setQueryParameter("sort", forgejoSort);
            }
        } else {
            urlBuilder.encodedPath("/api/v1/repos/issues/search");
            urlBuilder.setQueryParameter("type", type);
            urlBuilder.setQueryParameter("state", state);
            urlBuilder.setQueryParameter("created", String.valueOf(created));
            urlBuilder.setQueryParameter("assigned", String.valueOf(assigned));
            urlBuilder.setQueryParameter("mentioned", String.valueOf(mentioned));
            if (leftover.length() > 0) {
                urlBuilder.setQueryParameter("q", leftover.toString());
            }
            // Cross-repo search sort support is spotty; still pass Forgejo enums when known.
            if (forgejoSort != null) {
                urlBuilder.setQueryParameter("sort", forgejoSort);
            }
        }
        if (url.queryParameter("limit") == null && url.queryParameter("per_page") == null) {
            urlBuilder.setQueryParameter("limit", "30");
        }
    }

    /**
     * GitHub issue search uses {@code sort=created|updated|comments} + {@code order/direction}.
     * Forgejo wants a single {@code sort} enum: newest, oldest, recentupdate, …
     */
    private static String mapGithubIssueSort(String sort, String order) {
        boolean asc = "asc".equalsIgnoreCase(order);
        if (sort == null || sort.isEmpty() || "created".equals(sort)) {
            return asc ? "oldest" : "newest";
        }
        if ("updated".equals(sort)) {
            return asc ? "leastupdate" : "recentupdate";
        }
        if ("comments".equals(sort)) {
            return asc ? "leastcomment" : "mostcomment";
        }
        return null;
    }

    /**
     * Map GitHub {@code /user/repos?sort=&direction=} onto Forgejo {@code order_by}.
     * Forgejo ignores GitHub's sort/direction and otherwise returns an arbitrary default.
     */
    private static void applyMyReposOrderBy(HttpUrl url, HttpUrl.Builder urlBuilder) {
        String sort = url.queryParameter("sort");
        String direction = url.queryParameter("direction");
        urlBuilder.removeAllQueryParameters("sort");
        urlBuilder.removeAllQueryParameters("direction");
        urlBuilder.removeAllQueryParameters("order_by");
        String orderBy = mapGithubRepoSortToOrderBy(sort, direction);
        if (orderBy != null) {
            urlBuilder.setQueryParameter("order_by", orderBy);
        }
    }

    private static String mapGithubRepoSortToOrderBy(String sort, String direction) {
        boolean asc = "asc".equalsIgnoreCase(direction);
        if (sort == null || sort.isEmpty()) {
            return asc ? "alphabetically" : "reversealphabetically";
        }
        switch (sort) {
            case "full_name":
                return asc ? "alphabetically" : "reversealphabetically";
            case "created":
                return asc ? "oldest" : "newest";
            case "pushed":
            case "updated":
                // Forgejo has no separate pushed_at; updated_unix tracks pushes.
                return asc ? "leastupdate" : "recentupdate";
            default:
                return null;
        }
    }

    /**
     * Map GitHub repo-search {@code sort}/{@code order} onto Forgejo {@code /repos/search}.
     */
    private static void applyRepoSearchSort(HttpUrl url, HttpUrl.Builder urlBuilder) {
        String sort = url.queryParameter("sort");
        String order = url.queryParameter("order");
        if (order == null) {
            order = url.queryParameter("direction");
        }
        urlBuilder.removeAllQueryParameters("sort");
        urlBuilder.removeAllQueryParameters("order");
        urlBuilder.removeAllQueryParameters("direction");
        String fjSort = "alpha";
        if ("stars".equals(sort)) {
            fjSort = "stars";
        } else if ("forks".equals(sort)) {
            fjSort = "forks";
        } else if ("updated".equals(sort) || "pushed".equals(sort)) {
            fjSort = "updated";
        } else if ("created".equals(sort)) {
            fjSort = "created";
        }
        urlBuilder.setQueryParameter("sort", fjSort);
        urlBuilder.setQueryParameter("order",
                "asc".equalsIgnoreCase(order) ? "asc" : "desc");
    }

    /**
     * Rewrite {@code /users|orgs/{name}/repos} (no server-side GitHub sort) to
     * {@code /repos/search?uid=&exclusive=true} with Forgejo sort/order.
     */
    private static Request rewriteForeignRepoListRequest(Chain chain, Request original,
            String login, boolean isOrg) throws IOException {
        JSONObject account = fetchJsonObject(chain, original,
                isOrg ? "/api/v1/orgs" : "/api/v1/users", login);
        if (account == null && !isOrg) {
            account = fetchJsonObject(chain, original, "/api/v1/orgs", login);
        }
        long id = account != null ? account.optLong("id", -1) : -1;
        if (id < 0) {
            return original;
        }

        HttpUrl url = original.url();
        String sort = url.queryParameter("sort");
        String direction = url.queryParameter("direction");
        if (direction == null) {
            direction = url.queryParameter("order");
        }
        if (direction == null) {
            direction = "full_name".equals(sort) || sort == null || sort.isEmpty() ? "asc" : "desc";
        }

        HttpUrl.Builder urlBuilder = url.newBuilder()
                .encodedPath("/api/v1/repos/search");
        // Drop GitHub list-repos filters Forgejo search does not understand.
        urlBuilder.removeAllQueryParameters("sort");
        urlBuilder.removeAllQueryParameters("direction");
        urlBuilder.removeAllQueryParameters("order");
        urlBuilder.removeAllQueryParameters("type");
        urlBuilder.removeAllQueryParameters("affiliation");
        urlBuilder.removeAllQueryParameters("visibility");
        urlBuilder.removeAllQueryParameters("uid");
        urlBuilder.removeAllQueryParameters("exclusive");
        urlBuilder.removeAllQueryParameters("q");

        String fjSort = "alpha";
        if ("pushed".equals(sort) || "updated".equals(sort)) {
            fjSort = "updated";
        } else if ("created".equals(sort)) {
            fjSort = "created";
        }
        urlBuilder.setQueryParameter("sort", fjSort);
        urlBuilder.setQueryParameter("order",
                "asc".equalsIgnoreCase(direction) ? "asc" : "desc");
        urlBuilder.setQueryParameter("uid", String.valueOf(id));
        urlBuilder.setQueryParameter("exclusive", "true");
        if (url.queryParameter("limit") == null && url.queryParameter("per_page") == null) {
            urlBuilder.setQueryParameter("limit", "30");
        }
        return original.newBuilder().url(urlBuilder.build()).build();
    }

    private static String wrapArrayAsSearchPage(String body, String totalHeader)
            throws JSONException {
        JSONArray items = new JSONArray(body);
        for (int i = 0; i < items.length(); i++) {
            normalizeIssueForGitHub(items.getJSONObject(i));
        }
        long total = items.length();
        if (totalHeader != null) {
            try {
                total = Long.parseLong(totalHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return new JSONObject()
                .put("total_count", total)
                .put("incomplete_results", false)
                .put("items", items)
                .toString();
    }

    /**
     * Forgejo issue search embeds {@code repository.owner} as a login string; GitHub uses a User
     * object. Expand it so Moshi can parse {@link com.meisolsson.githubsdk.model.Issue}.
     */
    private static void normalizeIssueForGitHub(JSONObject issue) throws JSONException {
        JSONObject repo = issue.optJSONObject("repository");
        if (repo != null) {
            Object owner = repo.opt("owner");
            if (owner instanceof String) {
                repo.put("owner", new JSONObject().put("login", owner));
            }
        }
        // Forgejo may omit pull_request for issues; leave as-is. For PRs ensure object exists.
        if (!issue.isNull("pull_request") && issue.opt("pull_request") instanceof JSONObject) {
            // already fine
        } else if (issue.optString("html_url", "").contains("/pulls/")) {
            issue.put("pull_request", new JSONObject());
        }
    }

    private static String convertRepoSearchToSearchPage(String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        JSONArray data = obj.optJSONArray("data");
        if (data == null) {
            data = new JSONArray();
        }
        long total = obj.optLong("total_count", data.length());
        return new JSONObject()
                .put("total_count", total)
                .put("incomplete_results", false)
                .put("items", data)
                .toString();
    }

    private static String convertUserSearchToSearchPage(String body) throws JSONException {
        JSONObject obj = new JSONObject(body);
        JSONArray data = obj.optJSONArray("data");
        if (data == null) {
            data = new JSONArray();
        }
        long total = obj.optLong("total_count", data.length());
        return new JSONObject()
                .put("total_count", total)
                .put("incomplete_results", false)
                .put("items", data)
                .toString();
    }

    /**
     * Forgejo {@code /users/search} often misses exact org/user logins that still resolve via
     * {@code /users/{name}} or {@code /orgs/{name}}. When search is empty and {@code q} looks like
     * a single login, try those lookups so the Users search screen can open them.
     */
    private static String maybeAugmentUserSearchWithExactMatch(Chain chain, Request searchReq,
            String searchPageJson) throws IOException, JSONException {
        JSONObject page = new JSONObject(searchPageJson);
        JSONArray items = page.optJSONArray("items");
        if (items != null && items.length() > 0) {
            return searchPageJson;
        }
        String q = searchReq.url().queryParameter("q");
        if (q == null) {
            return searchPageJson;
        }
        q = q.trim();
        if (q.isEmpty() || q.contains(" ") || q.contains(":")) {
            return searchPageJson;
        }

        // Prefer /orgs so org accounts are not mis-typed as User via /users/{name}.
        JSONObject user = fetchJsonObject(chain, searchReq, "/api/v1/orgs", q);
        boolean fromOrg = user != null;
        if (user == null) {
            user = fetchJsonObject(chain, searchReq, "/api/v1/users", q);
        }
        if (user == null) {
            return searchPageJson;
        }
        if ((!user.has("login") || user.isNull("login") || user.optString("login").isEmpty())
                && user.has("username") && !user.isNull("username")) {
            user.put("login", user.optString("username"));
        }
        if (fromOrg || user.has("repo_admin_change_team_access")) {
            user.put("type", "Organization");
        } else if (!user.has("type") || user.isNull("type")) {
            user.put("type", "User");
        }
        return new JSONObject()
                .put("total_count", 1)
                .put("incomplete_results", false)
                .put("items", new JSONArray().put(user))
                .toString();
    }

    /**
     * If {@code login} is an organization, return it shaped like a GitHub User with
     * {@code type=Organization}. Otherwise null so the normal /users/{login} call proceeds.
     */
    private static Response tryOrgAsGitHubUser(Chain chain, Request original, String login)
            throws IOException {
        JSONObject org = fetchJsonObject(chain, original, "/api/v1/orgs", login);
        if (org == null) {
            return null;
        }
        try {
            if ((!org.has("login") || org.isNull("login") || org.optString("login").isEmpty())) {
                if (org.has("username") && !org.isNull("username")) {
                    org.put("login", org.optString("username"));
                } else if (org.has("name") && !org.isNull("name")) {
                    org.put("login", org.optString("name"));
                } else {
                    org.put("login", login);
                }
            }
            org.put("type", "Organization");
            if (org.has("full_name") && !org.isNull("full_name")) {
                // Prefer display name; keep org "name" as login when that is all Forgejo sent.
                String fullName = org.optString("full_name", "");
                if (!fullName.isEmpty()) {
                    org.put("name", fullName);
                }
            }
            return jsonResponse(original, aliasStarFields(org.toString()));
        } catch (JSONException e) {
            Log.w(TAG, "Failed to rewrite org " + login + " as GitHub user", e);
            return null;
        }
    }

    private static JSONObject fetchJsonObject(Chain chain, Request template,
            String encodedPath, String pathSegment) throws IOException {
        HttpUrl url = template.url().newBuilder()
                .encodedPath(encodedPath)
                .addPathSegment(pathSegment)
                .query(null)
                .build();
        Response resp = chain.proceed(template.newBuilder().url(url).get().build());
        try {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            String body = resp.body().string();
            if (body == null || body.trim().isEmpty() || "null".equals(body.trim())) {
                return null;
            }
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        } finally {
            resp.close();
        }
    }

    private static String extractItemsArray(String body) {
        String trimmed = body.trim();
        if (trimmed.startsWith("[")) {
            return body;
        }
        try {
            JSONArray items = new JSONObject(body).optJSONArray("items");
            return items != null ? items.toString() : "[]";
        } catch (JSONException e) {
            return "[]";
        }
    }

    private static String convertActivityFeedsToEvents(String body) throws JSONException {
        JSONArray feeds = new JSONArray(body);
        JSONArray events = new JSONArray();
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject event = toGitHubEvent(feeds.getJSONObject(i));
            if (event != null) {
                events.put(event);
            }
        }
        return events.toString();
    }

    private static JSONObject toGitHubEvent(JSONObject feed) throws JSONException {
        String opType = feed.optString("op_type", "");
        JSONObject actor = feed.optJSONObject("act_user");
        JSONObject repo = feed.optJSONObject("repo");
        if (actor == null || repo == null) {
            return null;
        }
        // Forgejo act_user omits GitHub's type; SDK switches NPE on null UserType.
        if (!actor.has("type") || actor.isNull("type")) {
            actor.put("type", "User");
        }

        String fullName = repo.optString("full_name", "");
        if (fullName.isEmpty()) {
            String owner = "";
            JSONObject ownerObj = repo.optJSONObject("owner");
            if (ownerObj != null) {
                owner = ownerObj.optString("login", "");
            }
            fullName = owner + "/" + repo.optString("name", "");
        }

        String type;
        JSONObject payload;
        switch (opType) {
            case "commit_repo":
            case "push_commit":
            case "mirror_sync_push":
                type = "PushEvent";
                payload = pushPayload(feed);
                break;
            case "create_repo":
                type = "CreateEvent";
                payload = createPayload("repository", null);
                break;
            case "create_branch":
                type = "CreateEvent";
                payload = createPayload("branch", shortRef(feed.optString("ref_name", "")));
                break;
            case "create_tag":
                type = "CreateEvent";
                payload = createPayload("tag", shortRef(feed.optString("ref_name", "")));
                break;
            case "mirror_sync_create": {
                // Mirror sync create covers both branches and tags; inspect the ref.
                String syncRef = feed.optString("ref_name", "");
                boolean isTag = syncRef.startsWith("refs/tags/");
                type = "CreateEvent";
                payload = createPayload(isTag ? "tag" : "branch", shortRef(syncRef));
                break;
            }
            case "delete_branch":
                type = "DeleteEvent";
                payload = deletePayload("branch", shortRef(feed.optString("ref_name", "")));
                break;
            case "delete_tag":
                type = "DeleteEvent";
                payload = deletePayload("tag", shortRef(feed.optString("ref_name", "")));
                break;
            case "mirror_sync_delete": {
                String syncRef = feed.optString("ref_name", "");
                boolean isTag = syncRef.startsWith("refs/tags/");
                type = "DeleteEvent";
                payload = deletePayload(isTag ? "tag" : "branch", shortRef(syncRef));
                break;
            }
            case "fork":
            case "fork_repo":
                type = "ForkEvent";
                payload = new JSONObject().put("forkee", repo);
                break;
            case "create_issue":
                type = "IssuesEvent";
                payload = issueActionPayload(feed, "opened");
                break;
            case "close_issue":
                type = "IssuesEvent";
                payload = issueActionPayload(feed, "closed");
                break;
            case "reopen_issue":
                type = "IssuesEvent";
                payload = issueActionPayload(feed, "reopened");
                break;
            case "comment_issue":
                type = "IssueCommentEvent";
                payload = issueCommentPayload(feed, false);
                break;
            case "create_pull_request":
                type = "PullRequestEvent";
                payload = pullRequestPayload(feed, "opened");
                break;
            case "close_pull_request":
                type = "PullRequestEvent";
                payload = pullRequestPayload(feed, "closed");
                break;
            case "reopen_pull_request":
                type = "PullRequestEvent";
                payload = pullRequestPayload(feed, "reopened");
                break;
            case "merge_pull_request":
            case "auto_merge_pull_request":
                type = "PullRequestEvent";
                payload = pullRequestPayload(feed, "closed");
                JSONObject mergedPr = payload.optJSONObject("pull_request");
                if (mergedPr != null) {
                    mergedPr.put("merged", true);
                }
                break;
            case "comment_pull":
                type = "IssueCommentEvent";
                payload = issueCommentPayload(feed, true);
                break;
            case "star":
            case "star_repo":
                type = "WatchEvent";
                payload = new JSONObject().put("action", "started");
                break;
            case "publish_release":
            case "release":
                type = "ReleaseEvent";
                payload = releasePayload(feed);
                break;
            default:
                Log.d(TAG, "Skipping unsupported activity op_type=" + opType);
                return null;
        }

        if (payload == null) {
            return null;
        }

        JSONObject repoId = new JSONObject()
                .put("id", repo.optLong("id"))
                .put("name", fullName)
                .put("url", repo.optString("url",
                        repo.optString("html_url", "")));

        return new JSONObject()
                .put("id", String.valueOf(feed.optLong("id")))
                .put("type", type)
                .put("actor", actor)
                .put("repo", repoId)
                .put("payload", payload)
                .put("public", !feed.optBoolean("is_private", false))
                .put("created_at", feed.optString("created", null));
    }

    private static JSONObject pushPayload(JSONObject feed) throws JSONException {
        JSONObject payload = new JSONObject();
        String ref = feed.optString("ref_name", "refs/heads/main");
        payload.put("ref", ref);

        JSONArray commits = new JSONArray();
        String head = "";
        String content = feed.optString("content", "");
        if (content.startsWith("{")) {
            JSONObject parsed = new JSONObject(content);
            JSONArray fjCommits = parsed.optJSONArray("Commits");
            if (fjCommits != null) {
                for (int i = 0; i < fjCommits.length(); i++) {
                    JSONObject c = fjCommits.getJSONObject(i);
                    String sha = c.optString("Sha1", "");
                    if (head.isEmpty()) {
                        head = sha;
                    }
                    JSONObject author = new JSONObject()
                            .put("name", c.optString("AuthorName", ""))
                            .put("email", c.optString("AuthorEmail", ""));
                    commits.put(new JSONObject()
                            .put("sha", sha)
                            .put("message", c.optString("Message", ""))
                            .put("author", author)
                            .put("distinct", true));
                }
            }
            JSONObject headCommit = parsed.optJSONObject("HeadCommit");
            if (headCommit != null && head.isEmpty()) {
                head = headCommit.optString("Sha1", "");
            }
        }
        payload.put("head", head);
        payload.put("before", "");
        payload.put("size", commits.length());
        payload.put("distinct_size", commits.length());
        payload.put("commits", commits);
        return payload;
    }

    private static JSONObject createPayload(String refType, String ref) throws JSONException {
        JSONObject payload = new JSONObject().put("ref_type", refType);
        if (ref != null) {
            payload.put("ref", ref);
        } else {
            payload.put("ref", JSONObject.NULL);
        }
        payload.put("master_branch", "main");
        payload.put("description", "");
        return payload;
    }

    private static JSONObject deletePayload(String refType, String ref) throws JSONException {
        return new JSONObject()
                .put("ref_type", refType)
                .put("ref", ref != null ? ref : "");
    }

    private static JSONObject issueActionPayload(JSONObject feed, String action)
            throws JSONException {
        String[] parts = parseContentPair(feed.optString("content", ""));
        JSONObject issue = minimalIssue(parts[0], parts[1], false, feed);
        return new JSONObject()
                .put("action", action)
                .put("issue", issue);
    }

    private static JSONObject pullRequestPayload(JSONObject feed, String action)
            throws JSONException {
        String[] parts = parseContentPair(feed.optString("content", ""));
        int number = parseIntSafe(parts[0]);
        JSONObject pr = minimalPullRequest(parts[0], parts[1], feed);
        return new JSONObject()
                .put("action", action)
                .put("number", number)
                .put("pull_request", pr);
    }

    private static JSONObject issueCommentPayload(JSONObject feed, boolean isPull)
            throws JSONException {
        String[] parts = parseContentPair(feed.optString("content", ""));
        JSONObject issue = minimalIssue(parts[0], parts[1], isPull, feed);
        JSONObject commentObj = feed.optJSONObject("comment");
        JSONObject comment;
        if (commentObj != null) {
            comment = new JSONObject()
                    .put("id", commentObj.optLong("id"))
                    .put("body", commentObj.optString("body", parts[1]))
                    .put("html_url", commentObj.optString("html_url", ""))
                    .put("user", feed.optJSONObject("act_user"));
        } else {
            comment = new JSONObject()
                    .put("id", feed.optLong("comment_id"))
                    .put("body", parts[1])
                    .put("user", feed.optJSONObject("act_user"));
        }
        return new JSONObject()
                .put("action", "created")
                .put("issue", issue)
                .put("comment", comment);
    }

    private static JSONObject releasePayload(JSONObject feed) throws JSONException {
        String content = feed.optString("content", "");
        String tag = shortRef(feed.optString("ref_name", ""));
        String name = content;
        if (content.startsWith("[")) {
            String[] parts = parseContentPair(content);
            name = parts[1];
            if (tag.isEmpty()) {
                tag = parts[0];
            }
        }
        JSONObject repo = feed.optJSONObject("repo");
        String html = repo != null ? repo.optString("html_url", "") + "/releases/tag/" + tag : "";
        JSONObject release = new JSONObject()
                .put("tag_name", tag)
                .put("name", name)
                .put("html_url", html)
                .put("draft", false)
                .put("prerelease", false)
                .put("assets", new JSONArray());
        return new JSONObject()
                .put("action", "published")
                .put("release", release);
    }

    private static JSONObject minimalIssue(String numberStr, String title, boolean isPull,
            JSONObject feed) throws JSONException {
        JSONObject repo = feed.optJSONObject("repo");
        String html = "";
        if (repo != null) {
            String base = repo.optString("html_url", "");
            html = base + (isPull ? "/pulls/" : "/issues/") + numberStr;
        }
        JSONObject issue = new JSONObject()
                .put("number", parseIntSafe(numberStr))
                .put("title", title)
                .put("html_url", html)
                .put("state", "open")
                .put("user", feed.optJSONObject("act_user"));
        if (isPull) {
            // Feed clicks only open the PR screen when pull_request has an html_url.
            issue.put("pull_request", new JSONObject().put("html_url", html));
        }
        return issue;
    }

    private static JSONObject minimalPullRequest(String numberStr, String title, JSONObject feed)
            throws JSONException {
        JSONObject repo = feed.optJSONObject("repo");
        String html = "";
        if (repo != null) {
            html = repo.optString("html_url", "") + "/pulls/" + numberStr;
        }
        return new JSONObject()
                .put("number", parseIntSafe(numberStr))
                .put("title", title)
                .put("html_url", html)
                .put("state", "open")
                .put("merged", false)
                .put("user", feed.optJSONObject("act_user"));
    }

    /** Forgejo content for issues/PRs is often {@code ["123","title"]}. */
    private static String[] parseContentPair(String content) {
        String number = "";
        String title = "";
        if (content != null && content.startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(content);
                if (arr.length() > 0) {
                    number = String.valueOf(arr.get(0));
                }
                if (arr.length() > 1) {
                    title = String.valueOf(arr.get(1));
                }
            } catch (JSONException ignored) {
            }
        } else if (content != null) {
            // Forgejo feeds use "number|title" for issue and pull request events.
            int sep = content.indexOf('|');
            if (sep > 0 && content.substring(0, sep).matches("\\d+")) {
                number = content.substring(0, sep);
                title = content.substring(sep + 1);
            } else {
                title = content;
            }
        }
        return new String[] { number, title };
    }

    private static String shortRef(String ref) {
        if (ref == null) {
            return "";
        }
        final String prefixHeads = "refs/heads/";
        final String prefixTags = "refs/tags/";
        if (ref.startsWith(prefixHeads)) {
            return ref.substring(prefixHeads.length());
        }
        if (ref.startsWith(prefixTags)) {
            return ref.substring(prefixTags.length());
        }
        return ref;
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Deep-copy JSON renaming Forgejo count fields onto GitHub names the SDK expects.
     */
    static String aliasStarFields(String body) throws JSONException {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) {
            return body;
        }
        if (trimmed.charAt(0) == '[') {
            return aliasInArray(new JSONArray(body)).toString();
        }
        if (trimmed.charAt(0) == '{') {
            return aliasInObject(new JSONObject(body)).toString();
        }
        return body;
    }

    private static String aliasKey(String key) {
        if ("stars_count".equals(key)) {
            return "stargazers_count";
        }
        // Forgejo's watchers_count is GitHub's subscribers_count (actual watchers).
        if ("watchers_count".equals(key)) {
            return "subscribers_count";
        }
        if ("is_locked".equals(key)) {
            return "locked";
        }
        // Forgejo user objects use *_count / full_name / website / description / created.
        if ("followers_count".equals(key)) {
            return "followers";
        }
        if ("following_count".equals(key)) {
            return "following";
        }
        // Do not map full_name→name here: repos use full_name as owner/name.
        if ("website".equals(key)) {
            return "blog";
        }
        if ("created".equals(key)) {
            return "created_at";
        }
        return key;
    }

    /**
     * Convert Forgejo timestamps like {@code 2026-10-31T01:10:50+01:00} to the
     * {@code yyyy-MM-dd'T'HH:mm:ss'Z'} form githubsdk's FormattedTimeAdapter accepts.
     */
    private static boolean looksLikeGitSha(String value) {
        int len = value.length();
        if (len < 7 || len > 64) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    static String normalizeIso8601ToZulu(String value) {
        if (value == null || value.length() < 20 || value.endsWith("Z")) {
            return value;
        }
        // Cheap shape check: YYYY-MM-DDT...
        if (value.charAt(4) != '-' || value.charAt(7) != '-' || value.charAt(10) != 'T') {
            return value;
        }
        try {
            return OffsetDateTime.parse(value)
                    .toInstant()
                    .toString(); // always ...Z
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private static JSONObject aliasInObject(JSONObject obj) throws JSONException {
        JSONObject out = new JSONObject();
        Iterator<String> keys = obj.keys();
        List<String> keyList = new ArrayList<>();
        while (keys.hasNext()) {
            keyList.add(keys.next());
        }
        for (String key : keyList) {
            Object value = obj.get(key);
            String outKey = aliasKey(key);
            if (value instanceof JSONObject) {
                out.put(outKey, aliasInObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                out.put(outKey, aliasInArray((JSONArray) value));
            } else if (value instanceof String) {
                // githubsdk FormattedTimeAdapter only accepts ...Z, not +01:00 offsets.
                out.put(outKey, normalizeIso8601ToZulu((String) value));
            } else {
                out.put(outKey, value);
            }
        }
        // Forgejo commit objects (e.g. under /branches) use id; GitHub uses sha.
        if ((!out.has("sha") || out.isNull("sha"))
                && obj.has("id") && !obj.isNull("id")
                && obj.opt("id") instanceof String) {
            String id = obj.optString("id", "");
            if (looksLikeGitSha(id)) {
                out.put("sha", id);
            }
        }
        // Forgejo milestones have only an id; GitHub clients address them by number.
        if (!out.has("number") && obj.has("open_issues") && obj.has("closed_issues")
                && obj.has("title") && obj.opt("id") instanceof Number) {
            out.put("number", obj.get("id"));
        }
        // Prefer canonical GitHub field names when both are present.
        if (obj.has("stargazers_count") && !obj.isNull("stargazers_count")) {
            out.put("stargazers_count", obj.get("stargazers_count"));
        }
        if (obj.has("subscribers_count") && !obj.isNull("subscribers_count")) {
            out.put("subscribers_count", obj.get("subscribers_count"));
        }
        if (obj.has("locked") && !obj.isNull("locked")) {
            out.put("locked", obj.get("locked"));
        }
        // Forgejo embeds repository.owner as a bare login string.
        if (out.has("owner") && out.opt("owner") instanceof String) {
            String login = out.optString("owner", "");
            out.put("owner", new JSONObject()
                    .put("login", login)
                    .put("type", "User"));
        }
        // Forgejo users/orgs expose username (and orgs often omit login entirely).
        if ((!out.has("login") || out.isNull("login") || out.optString("login").isEmpty())
                && obj.has("username") && !obj.isNull("username")) {
            out.put("login", obj.optString("username"));
        }
        // Forgejo user/org objects often omit GitHub's type field; SDK switches NPE on null.
        if (out.has("login") && (!out.has("type") || out.isNull("type"))) {
            // Org list payloads include repo_admin_change_team_access; treat those as orgs.
            if (obj.has("repo_admin_change_team_access")) {
                out.put("type", "Organization");
            } else {
                out.put("type", "User");
            }
        }
        // Notification threads: numeric id + "Pull" subject type + missing reason.
        if (out.has("subject") && out.has("unread")) {
            Object idVal = out.opt("id");
            if (idVal instanceof Number) {
                out.put("id", String.valueOf(((Number) idVal).longValue()));
            }
            if (!out.has("reason") || out.isNull("reason")) {
                out.put("reason", "subscribed");
            }
            JSONObject subject = out.optJSONObject("subject");
            if (subject != null) {
                String subjectType = subject.optString("type", "");
                if ("Pull".equals(subjectType)) {
                    subject.put("type", "PullRequest");
                    // Forgejo keeps the API url under /issues/N even for PRs. Rewrite to
                    // /pulls/N so normalizeUri → /pull/N and BrowseFilter opens the PR screen.
                    String apiUrl = subject.optString("url", "");
                    if (apiUrl.contains("/issues/")) {
                        subject.put("url", apiUrl.replace("/issues/", "/pulls/"));
                    } else {
                        String htmlUrl = subject.optString("html_url", "");
                        if (!htmlUrl.isEmpty()) {
                            subject.put("url", htmlUrl);
                        }
                    }
                }
            }
        }
        // Users: full_name is the display name. Repos already have their own name field.
        if (out.has("login") && obj.has("full_name")
                && (!out.has("name") || out.isNull("name") || out.optString("name").isEmpty())
                && !obj.has("fork") && !obj.has("stars_count") && !obj.has("stargazers_count")) {
            out.put("name", obj.optString("full_name", ""));
        }
        // User bios live in description on Forgejo; keep repo.description intact.
        if (out.has("login") && out.has("description")
                && (!out.has("bio") || out.isNull("bio"))
                && !obj.has("fork") && !obj.has("stars_count") && !obj.has("stargazers_count")) {
            out.put("bio", out.opt("description"));
        }
        if (obj.has("created_at") && !obj.isNull("created_at")) {
            out.put("created_at", normalizeIso8601ToZulu(String.valueOf(obj.get("created_at"))));
        }
        if (obj.has("followers") && !obj.isNull("followers")) {
            out.put("followers", obj.get("followers"));
        }
        if (obj.has("following") && !obj.isNull("following")) {
            out.put("following", obj.get("following"));
        }
        // Nested commit objects from Forgejo omit comment_count.
        if (out.has("message") && !out.has("comment_count")) {
            out.put("comment_count", 0);
        }
        // Forgejo repo payloads often omit pushed_at; fall back to updated_at so
        // "most recently pushed" UIs and timestamps have something to show.
        if (out.has("full_name") && out.has("updated_at")
                && (!out.has("pushed_at") || out.isNull("pushed_at")
                || out.optString("pushed_at", "").isEmpty())) {
            out.put("pushed_at", out.opt("updated_at"));
        }
        // Pull requests: Forgejo may omit mergeable_state while still sending mergeable.
        if (out.has("mergeable") && (!out.has("mergeable_state") || out.isNull("mergeable_state")
                || out.optString("mergeable_state", "").isEmpty())) {
            out.put("mergeable_state", out.optBoolean("mergeable", false) ? "clean" : "dirty");
        }
        // Single-commit / PR file entries: Forgejo uses status "changed" for GitHub "modified".
        if (out.has("filename")) {
            if ("changed".equals(out.optString("status", ""))) {
                out.put("status", "modified");
            }
            if (!out.has("additions") || out.isNull("additions")) {
                out.put("additions", 0);
            }
            if (!out.has("deletions") || out.isNull("deletions")) {
                out.put("deletions", 0);
            }
            if (!out.has("changes") || out.isNull("changes")) {
                out.put("changes",
                        out.optInt("additions", 0) + out.optInt("deletions", 0));
            }
        }
        return out;
    }

    private static JSONArray aliasInArray(JSONArray arr) throws JSONException {
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            Object value = arr.get(i);
            if (value instanceof JSONObject) {
                out.put(aliasInObject((JSONObject) value));
            } else if (value instanceof JSONArray) {
                out.put(aliasInArray((JSONArray) value));
            } else {
                out.put(value);
            }
        }
        return out;
    }

    private static String buildLinkHeader(HttpUrl url, String totalCountHeader, String body) {
        int page = 1;
        String pageParam = url.queryParameter("page");
        if (pageParam != null) {
            try {
                page = Integer.parseInt(pageParam);
            } catch (NumberFormatException ignored) {
            }
        }
        int limit = 30;
        String limitParam = url.queryParameter("limit");
        if (limitParam == null) {
            limitParam = url.queryParameter("per_page");
        }
        if (limitParam != null) {
            try {
                limit = Integer.parseInt(limitParam);
            } catch (NumberFormatException ignored) {
            }
        }

        int itemCount = 0;
        try {
            itemCount = new JSONArray(body).length();
        } catch (JSONException ignored) {
        }

        Integer total = null;
        if (totalCountHeader != null) {
            try {
                total = Integer.parseInt(totalCountHeader.trim());
            } catch (NumberFormatException ignored) {
            }
        }

        boolean hasNext = total != null
                ? (page * limit) < total
                : itemCount >= limit;
        if (!hasNext) {
            return null;
        }

        HttpUrl nextUrl = url.newBuilder()
                .setQueryParameter("page", String.valueOf(page + 1))
                .build();
        // Pagination interceptor expects GitHub-style page query param.
        return String.format(Locale.US, "<%s>; rel=\"next\"", nextUrl);
    }
}
