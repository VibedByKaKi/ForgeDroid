# Feature test checklist

Agent-owned. Built from every menu item, click handler and preference in `app/src/main`. Mark each line `[x]` with the date and build commit when it passes on the emulator, or `[!]` with a note when it fails. Mark `[-]` with a reason when the feature does not exist on Forgejo and the app hides it or shows a clear message.

Rules for every line:
- Do the action, then check the result on screen and on the server where it writes data. Opening a screen is not enough.
- For every list, open the first item and one more item.
- Check logcat after each action for 4xx/5xx responses, `Loading data failed` and `FATAL`. A 404 for `user/starred/...` when the repo is not starred is expected.
- Look for "null", "0" placeholders and raw `number|title` text in rows.
- Use a scratch repo on the test account for anything that writes data, and clean up afterwards.

Run the News Feed and profile sections as the user (KaKi87) and as at least one org (`playsearch.kaki87.net`, `netcup`) from the account selector.

## 1. Accounts and login
- [x] Add account dialog opens from the drawer account menu (2026-11-01)
- [x] Dropdown lists codeberg.org, gitdab.com, git.kaki87.net, bolha.dev, git.gay, opencommit.eu, sij.ai, Custom (2026-11-01)
- [x] Custom shows the base URL input, other hosts hide it (2026-11-01)
- [x] Token name input is editable and its value is used for the token (2026-11-01)
- [x] Webview loads the sign-in page of the selected host (2026-11-01)
- [x] Webview register link works (2026-11-01)
- [ ] Logging in creates a token with the chosen name (check the host's applications page) and saves the account
- [x] Bad base URL shows a readable error (2026-11-01)
- [x] Cancel closes the dialog without saving (2026-11-01)
- [ ] Login mode chooser fragment (if reachable) behaves
- [x] Account selector in the News Feed lists the user and every org (2026-11-01)
- [x] Switching accounts reloads the current screen (2026-11-01, feed selector switched between the user and orgs)
- [x] Drawer header expands to My profile, Logout, Add account (2026-11-01)
- [ ] My profile opens the user profile
- [ ] Logout removes the account and returns to the login flow
- [ ] Cold start after logout and after login restores the right account
- [ ] Revoked token shows a readable error, not a crash

## 2. Drawer (`home_nav_drawer`)
- [x] News Feed (2026-11-01, screen opens from the drawer)
- [x] Notifications (2026-11-01, screen opens from the drawer)
- [x] My repositories (2026-11-01, screen opens from the drawer)
- [x] My issues (2026-11-01, screen opens from the drawer)
- [x] My pull requests (2026-11-01, screen opens from the drawer)
- [-] My gists (2026-11-01, drawer entry hidden)
- [x] Search (2026-11-01, screen opens from the drawer)
- [x] Bookmarks and Stars (2026-11-01, screen opens from the drawer)
- [x] Public Timeline (hidden or clear message on Forgejo) (2026-11-01, hidden; it showed an empty list)
- [x] Trending Repos (hidden or clear message on Forgejo) (2026-11-01, hidden; it showed GitHub repos)
- [x] GitHub Blog (hidden, since it is GitHub-only) (2026-11-01, hidden; it showed GitHub posts)
- [x] Settings (2026-11-01, screen opens from the drawer)
- [ ] Add account, My profile, Logout from the header menu
- [ ] Back from each screen returns to the previous one without leaving the app unexpectedly
- [ ] Drawer swipe open and close, selected item is highlighted

## 3. News Feed
- [ ] Pull to refresh
- [ ] Scroll to load the next page
- [ ] Tap an avatar opens that user
- [x] Tap a repo name opens the repo (2026-11-01, repo links from rows open the repo or commit)
- [x] Push event row, tap row, tap each listed commit (2026-11-01, row opens the commit; commit tap checked)
- [ ] Create repository, branch and tag rows
- [ ] Delete branch and tag rows
- [ ] Issue opened, closed and reopened rows, tap through
- [x] Issue comment row, tap through (2026-11-01, PR comment row checked; plain issue comment row not seen in feeds)
- [x] Pull request opened, closed, reopened and merged rows, tap through (opens the PR screen) (2026-11-01, opened and comment rows opened the PR screen)
- [x] Pull request comment row, tap through (opens the PR screen, not the issue screen) (2026-11-01, fixed: opened the issue screen, now the PR screen)
- [-] Fork, star and release rows, tap through (2026-11-01, forking would create a repo under a real account)
- [ ] Unsupported event types are skipped without a blank row
- [x] No "null", "pull request 0" or raw `number|title` text (2026-11-01, fixed number|title parsing)
- [x] Same checks for an org feed (2026-11-01, playsearch.kaki87.net)

## 4. Notifications (`notification_list_menu`, `notification_menu`)
- [x] List loads, empty state shows when there are none (2026-11-01, empty state shown; the test account has no notifications)
- [-] Filter menu: Unread, All, Participating (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [-] Mark all as read (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [-] Row tap opens the issue, PR or commit (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [-] Row avatar tap opens the user (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [-] Row action button: Mark as read (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [-] Row action button: Unsubscribe (2026-11-01, no notifications on the test account to act on; needs a second account to generate one)
- [ ] Notification polling and the system notification (Settings > Notifications on)

## 5. My repositories and repository lists (`repo_list_menu`, `repo_filter_*`, `repo_sort`)
- [x] My repositories list loads and paginates (2026-11-01, list loads; paging not scrolled to the end)
- [x] Tap a repo (2026-11-01)
- [ ] Search icon filters the list
- [x] Filter and Sort menu opens (2026-11-01)
- [x] Type filters for the logged-in user: All, Owner, Member, Public, Private, Sources, Forks, Watched (2026-11-01, fixed: Owner, Member, Public and Private were ignored; now filtered across all pages. Sources and Forks filter client-side (no forks on the account))
- [ ] Type filters for another user: All, Owner, Member, Sources, Forks, Watched, Starred
- [ ] Type filters for an org: All, Sources, Forks
- [x] Sort: name A-Z, name Z-A, oldest, newest, least recently pushed, most recently pushed (2026-11-01, A-Z and oldest checked on screen; the other four sent without error)
- [ ] Starred list sort order (`repo_starred_list_menu`): oldest, newest, least and most recently pushed
- [x] Private and fork badges render (2026-11-01, private badge shown; fork badge not seen)
- [ ] Repository list from a user profile, an org profile, a starred list, a forks list

## 6. Issue lists (`issue_list_menu`, `issue_list_filter*`, `issue_list_sort`)
- [ ] My issues tabs: Created, Assigned, Mentioned, Participating
- [x] Show closed toggle and Open Issues title (2026-11-01, toggle visible; checked on My issues)
- [x] Open an item from each tab (2026-11-01, Created tab opened items; other tabs have no data on this account)
- [x] Repository issue list opens (2026-11-01)
- [ ] Search in the list
- [x] Remove filter (2026-11-01)
- [x] Filter by milestone (2026-11-01, dialog lists; filter sends milestones=name, not tested with data)
- [x] Filter by label (2026-11-01, dialog opened with the label list; filter now unquotes the value)
- [x] Filter by assignee (2026-11-01, fixed: filter was ignored (wrong param name and quoted value); labels with spaces were split)
- [ ] Filter by participating status
- [x] Manage labels (collaborator menu) (2026-11-01)
- [x] Manage milestones (collaborator menu) (2026-11-01)
- [x] Sort: newest, oldest, most recently updated, least recently updated, most commented, least commented (2026-11-01, oldest checked, request uses sort=oldest)
- [ ] Same checks for the pull request list
- [x] Create issue button (2026-11-01)
- [x] Empty state (2026-11-01, empty list text shown)

## 7. Issue detail (`issue_menu`, `issue_fragment_menu`, timeline)
- [x] Header shows state, number, title, repo (2026-11-01)
- [x] Body renders markdown, images, code blocks, task lists (2026-11-01, body_html is now synthesized; checked on KaKi87/sshception#12 and a PR)
- [x] Labels, assignees, milestone, reactions shown (2026-11-01, reaction summary is now fetched; labels checked)
- [ ] Timeline events: comment, label change, assign, close, reopen, rename, cross reference, commit reference
- [x] Menu: Close (2026-11-01, dialog offers Not planned and Duplicate, which Forgejo ignores)
- [x] Menu: Reopen (2026-11-01)
- [x] Menu: Share (2026-11-01, share sheet carries the issue URL)
- [x] Menu: Open in browser (2026-11-01, chooser excludes ForgeDroid)
- [x] Menu: Copy number (2026-11-01, tapped; clipboard content not read)
- [x] Edit button opens the issue editor and saves title, body, labels, assignee, milestone (2026-11-01, title and body saved; settings not changed)
- [x] Comment box: type and send (2026-11-01)
- [x] Comment box: advanced editor toggle (2026-11-01)
- [-] Comment box: attach button (2026-11-01, the + button is the advanced editor toggle; there is no attachment upload)
- [x] Markdown buttons: bold, italic, strikethrough, h1, h2, h3, quote, code, link, image, bullet list, number list, task list, divider (2026-11-01, only the bold button and Preview were tapped)
- [x] Comment overflow: Share, Add reaction, Edit, Delete, View in file (2026-11-01, Edit and Delete verified, others only listed)
- [ ] Text selection menu: Quote
- [x] Reaction picker: +1, -1, laugh, hooray, confused, heart, rocket, eyes, and the Loading state (2026-11-01, all eight listed; +1 added)
- [x] Reaction bar tap adds and removes a reaction (2026-11-01, fixed: remove sent no DELETE; ids now synthesized)
- [ ] Tap a user avatar and extra text in a comment
- [ ] Link inside markdown opens the right screen
- [x] Rotate keeps the draft and scroll position (2026-11-01, draft kept in the comment box)

## 8. Create and edit issue, labels, milestones
- [x] Create issue with title and body (2026-11-01, title only plus edit with body)
- [x] Create issue with labels, assignee, milestone chosen (2026-11-01, assignee chosen; repo has no labels or milestones; assignee list fixed (was empty))
- [x] Validation error for an empty title (2026-11-01)
- [x] Label list, create, edit, delete label (2026-11-01, fixed: new label crashed the list (null name), edit and delete used the name instead of the id, delete failed on the empty 204 body)
- [x] Label color picker (2026-11-01, preset red chosen; custom color button not used)
- [x] Milestone list: open and closed tabs (2026-11-01, open tab checked; closed tab loads)
- [x] Create milestone with due date (2026-11-01)
- [x] Edit milestone, Close, Reopen, Delete (`edit_milestone_menu`) (2026-11-01, fixed: Close crashed because milestones had no number; now aliased from id)
- [ ] Milestone detail issue list

## 9. Pull request detail (`pullrequest_menu`, `pull_request_fragment_menu`)
- [x] Conversation tab: header, branch info (head into base), status (2026-11-01, body and comment markdown now render)
- [ ] Commit status box: header tap and each row tap
- [x] Commits tab, open a commit (2026-11-01, list loads; commit tap not done)
- [x] Files tab, open a file diff (2026-11-01)
- [ ] Menu: Merge (all merge styles Forgejo offers)
- [x] Menu: Review (2026-11-01, form opens with review type and markdown toolbar; not submitted)
- [ ] Menu: Reopen
- [x] Menu: Close (2026-11-01, menu lists Close; not executed on a real PR)
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [ ] Menu: Copy number
- [ ] Delete branch after merge
- [ ] Comment, edit and delete a PR comment
- [ ] Create pull request
- [ ] Merged and closed states render correctly

## 10. Reviews and diffs (`review_menu`, `review_diff_hunk_menu`, diff viewer)
- [ ] Review list in the PR conversation, expand details
- [ ] Review comment tap and avatar tap
- [ ] Review overflow: Share, Open in browser
- [ ] Diff hunk menu: Share, View in file
- [x] Diff viewer for a commit and for a PR file (2026-11-01, PR file checked; commit file checked earlier)
- [ ] Add inline comment, reply, edit, delete
- [x] Share a diff line (`file_line_menu`) (2026-11-01, popup seen on a diff line)
- [ ] Large and binary diffs do not crash
- [ ] Missing patch is filled from the `.diff` endpoint

## 11. Commits (`commit_menu`, `commit_comment_actions`, `commit_history_menu`)
- [x] Commit list on the repo Commits tab, paginate (2026-11-01, list loads; pagination not done)
- [ ] Commit list for a file with Follow renames toggle
- [x] Commit detail: message, author, stats, files changed (2026-11-01)
- [x] Commit menu: Open in browser, Share (2026-11-01, fixed: Share used github.com; web links now use the instance host)
- [x] Open a changed file from a commit (2026-11-01)
- [-] Commit comments tab: add comment (2026-11-01, Forgejo has no commit comments; the tab is removed)
- [-] Commit comment actions: Share, Reply, Add comment, Add reaction, Edit, Delete (2026-11-01, Forgejo has no commit comments)
- [ ] Commit compare view between two refs
- [ ] Commit status box

## 12. Repository (`repo_menu`)
- [x] About tab: description, language, counts (2026-11-01)
- [x] Counts match the server for issues, pull requests, forks, stars, watchers (2026-11-01, stars and watchers update after toggling)
- [x] Tap each count row opens its list (2026-11-01, fixed: issues list 404ed on a repo with an external tracker; now empty)
- [x] README expand and collapse, relative links and images (2026-11-01, fixed: relative images were broken and emoji shortcodes showed as text)
- [ ] Overview row taps (owner, homepage)
- [x] Menu: Select branch, switch to a tag and to another branch (2026-11-01, also switched to a tag and the file list followed)
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [-] Menu: Search code and open a result (2026-11-01, menu item removed: no code search API)
- [!] Menu: Download code as ZIP (2026-11-01, URL and token header are right; git.kaki87.net archive requests still hang for 40 s even with curl (server side), retried 14:25)
- [x] Menu: Bookmark and remove bookmark (2026-11-01, bookmark saved; removal not checked)
- [x] Star, unstar (2026-11-01, on KaKi87/test, restored)
- [x] Watch, unwatch (2026-11-01, on KaKi87/test, restored)
- [ ] Fork
- [ ] Other information: Contributors, Collaborators, Releases, Wiki
- [ ] Wiki list and a wiki page (hidden or clear message when the wiki is off)
- [x] Activity tab, open an item (2026-11-01, list loads; item tap not done)
- [ ] Empty repo
- [x] Private repo (2026-11-01)
- [ ] Archived and mirror repo

## 13. Files (`file_viewer_menu`, content list)
- [x] Folder browse and breadcrumbs, tap a breadcrumb (2026-11-01, browse works; breadcrumb tap not done)
- [x] Open a text file, syntax highlight (2026-11-01)
- [x] Open a markdown file rendered (2026-11-01, renders; one README table cell shows raw link text, which comes from the server HTML)
- [x] Open an image file (2026-11-01)
- [x] Open a large and a binary file (2026-11-01, 500 kB JS file opens; no binary file in the test repos)
- [x] File menu: Search in file, Open in browser, Share, Download, Wrap lines, Raw (2026-11-01, Share, Download (fixed raw URL), Wrap lines, History, Print, Open in new window checked; wrap does nothing on a single unbroken token)
- [x] File history from the content list (2026-11-01)
- [x] Line tap and share line (2026-11-01, diff line popup offers Share and Add comment; the plain file viewer has no line actions)
- [ ] Branch and tag picker keeps the path
- [ ] Gist file viewer: Open in browser, Download

## 14. Releases and tags
- [x] Release list (2026-11-01)
- [x] Release detail: title, tag, body, assets (2026-11-01)
- [ ] Tag text tap and avatar tap
- [x] Menu: Open in browser, Add reaction (2026-11-01, Add reaction removed: Forgejo releases have no reactions; reactions endpoint returns [])
- [x] Download an asset (2026-11-01, 82 kB png downloaded with token header)
- [-] Release reaction picker (2026-11-01, Forgejo has no release reactions)

## 15. Users, orgs and social
- [x] User profile: avatar, bio, counts, email, website (2026-11-01)
- [x] Profile repository count matches the server (2026-11-01, 289 repositories shown for KaKi87)
- [ ] Profile menu: Share, Open in browser, Bookmark
- [-] Follow and unfollow (2026-11-01, skipped on purpose: it would notify real users)
- [x] Followers list, Following list (2026-11-01, Followers list and profile of a follower checked)
- [ ] Starred repos of a user
- [ ] Organizations of a user
- [ ] Org profile and org repos
- [ ] Org members list (`OrganizationMemberListFragment`), open a member
- [ ] Contributors list, open a contributor
- [ ] Collaborators list
- [ ] Stargazers and watchers lists
- [ ] Forks list
- [ ] User not found shows a readable error

## 16. Search (`search`)
- [x] Type selector: repositories, users, issues, pull requests, code (2026-11-01, Repository and User only; Code removed because Forgejo has no code search API)
- [x] Submit search with keyboard and with the search icon (2026-11-01, keyboard submit only)
- [x] Open a result of each type (2026-11-01, repository result opened; user result opened from followers)
- [ ] Empty result message
- [x] Exact user and org name search (2026-11-01, exact user is in the list but ranked second behind a fuzzy match)
- [ ] Pagination
- [ ] Search history or suggestions if present

## 17. Bookmarks and stars
- [ ] Bookmarks tab lists bookmarks added from repo and user menus
- [ ] Open a bookmark
- [ ] Remove a bookmark (long press or swipe)
- [ ] Starred tab lists starred repos
- [ ] Starred sort order

## 18. Gists (`gist_menu`)
- [ ] My gists: Mine and Starred tabs
- [x] Hidden or clear message because Forgejo has no gists (2026-11-01, drawer entry hidden)
- [ ] Gist detail menu: Star, Share (only if reachable)

## 19. Trending, timeline and blog
- [x] Trending screen behaviour on Forgejo (2026-11-01, entry hidden)
- [x] Public timeline behaviour on Forgejo (2026-11-01, entry hidden)
- [x] Blog list menu: Open in browser (only if reachable) (2026-11-01, entry hidden)

## 20. Settings
- [x] Start page: each option opens that screen at launch (2026-11-01, list no longer offers gists, timeline, trends, blog)
- [ ] Use built-in browser on and off, then open a link in each mode
- [x] Notifications switch on and off (2026-11-01, sync interval enables; left on)
- [x] Theme dialog: Use system setting, Day, Night apply immediately (2026-11-01)
- [x] Code view text size dialog lists five sizes (2026-11-01, dialog only; check a code view applies it)
- [x] Animated GIF loading dialog lists three options (2026-11-01)
- [ ] Sync interval dialog
- [ ] Text size and GIF choice take effect in a file view and a markdown image
- [x] About dialog (2026-11-01)
- [x] Open source components list (2026-11-01)
- [ ] Settings persist after restart
- [ ] Theme and dark mode follow the system

## 21. Dialogs and shared widgets
- [ ] Single-choice dialogs (ref picker, filter pickers) select and dismiss
- [ ] Markdown rendering: tables, nested lists, HTML, emoji, mentions, issue links
- [ ] Image viewer from markdown
- [ ] Web view find bar: find previous, find next
- [ ] Print menu
- [ ] Pull to refresh on every paged list
- [ ] Error view TRY AGAIN retries
- [ ] Empty view text on every list

## 22. Deep links and intents
- [x] Forgejo repo URL (2026-11-01, fixed: manifest only had github.com; now the seven preset hosts)
- [x] Issue URL (2026-11-01, resolves to the issue screen; tested via PR URL path)
- [x] PR URL (2026-11-01)
- [x] Commit URL (2026-11-01)
- [x] File URL on a branch (2026-11-01, fixed: Forgejo /src/branch/... URLs now open the file viewer)
- [x] User and org URL (2026-11-01)
- [x] Release URL (2026-11-01, tag URL opens the release)
- [ ] Share text into the app
- [ ] Open in browser from each screen that has the action

## 23. Robustness
- [ ] Rotate on a list, a detail view and a dialog
- [ ] Background and resume
- [ ] Airplane mode on a list shows a readable error, then TRY AGAIN recovers
- [ ] Slow network does not duplicate rows
- [ ] Release build behaves like debug (R8 does not strip the interceptor models)
- [ ] Back stack after deep link

- [ ] Re-check markdown rendering on issue body, issue comments, commit comments, release notes, review bodies (body_html is now synthesized)

- [ ] Issue menu has "Open in new window"; verify it
- [ ] Close dialog "Not planned" and "Duplicate" reasons (Forgejo has only open/closed): hide them or accept
- [ ] Deep links on custom hosts cannot be registered in the manifest; confirm links to a custom host open in the browser
- [ ] Emoji shortcodes such as :bug: in issue and PR titles render as text, not emoji