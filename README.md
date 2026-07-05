# Mangaupdates Tracker

Mangaupdates Tracker is a small Android app for sending manga pages from a browser or reader into your MangaUpdates lists. It currently only supports Mangago, but future sources will be added soon.

## Features

- Android share target for text URLs.
- Direct link handling for Mangago URLs.
- MangaUpdates account sign-in through the MangaUpdates API.
- Optional saved password using Android Keystore-backed encryption.
- Checks whether the series is already in one of your MangaUpdates lists.
- Shows all of your MangaUpdates lists, including custom lists.
- Quick-add list chips for fast add/move actions.
- Full save form for chapter progress, comment, 10-star rating, and list selection.
- Auto-closes after a successful quick add or save when opened from the Android share sheet.

## URL Parsing

Mangago URLs are converted into MangaUpdates search queries. For longer chapter URLs, only the manga slug before `/uu/` is used. MangaUpdates series links are also understood.

## Credentials

Use your normal MangaUpdates website username and password. There is no separate API key for this app.

The app signs in with MangaUpdates and stores the returned session token. If you choose to keep the password saved, the password is encrypted with an Android Keystore key before being stored in app preferences.

## Development

This is a native Android/Kotlin app using Jetpack Compose and Material 3.

Open the project in Android Studio, or build from the command line:

```sh
./gradlew :app:assembleDebug
```

## Troubleshooting

If a shared URL fails to add to a list, the app shows the MangaUpdates API method and path that failed. That detail is useful because MangaUpdates has separate endpoints for searching, list item lookup, list item add/update, rating, and comments.

If MangaUpdates returns a different title than expected, edit the search text in the Share tab and press `Search` again before saving.
