package com.example.mangaupdatestracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarHalf
import androidx.compose.material.icons.outlined.StarOutline
import com.example.mangaupdatestracker.ui.theme.MangaupdatesTrackerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val sharedTextState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedTextState.value = extractSharedText(intent)
        enableEdgeToEdge()
        setContent {
            MangaupdatesTrackerTheme {
                MangaupdatesTrackerApp(
                    sharedText = sharedTextState.value,
                    onSharedSaveComplete = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedTextState.value = extractSharedText(intent)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MangaupdatesTrackerApp(
    sharedText: String?,
    onSharedSaveComplete: () -> Unit = {}
) {
    val context = LocalContext.current
    val api = remember { MangaUpdatesApi() }
    val credentials = remember { CredentialsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val openedFromShare = !sharedText.isNullOrBlank()

    var selectedTab by rememberSaveable { mutableIntStateOf(if (sharedText.isNullOrBlank()) 1 else 0) }
    var username by rememberSaveable { mutableStateOf(credentials.username) }
    var keepSignedIn by rememberSaveable { mutableStateOf(credentials.keepSignedIn) }
    var password by rememberSaveable {
        mutableStateOf(if (credentials.keepSignedIn) credentials.savedPassword else "")
    }
    var token by rememberSaveable { mutableStateOf(credentials.token) }
    var accountMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var accountLoading by rememberSaveable { mutableStateOf(false) }

    var sourceText by rememberSaveable { mutableStateOf(sharedText.orEmpty()) }
    var query by rememberSaveable { mutableStateOf(sharedText?.let(::titleQueryFromSharedText).orEmpty()) }
    var series by remember { mutableStateOf<SeriesResult?>(null) }
    var lists by remember { mutableStateOf<List<UserList>>(emptyList()) }
    var existingEntry by remember { mutableStateOf<ListEntry?>(null) }
    var existingComment by remember { mutableStateOf<SeriesComment?>(null) }
    var selectedListId by rememberSaveable { mutableIntStateOf(0) }
    var chapter by rememberSaveable { mutableStateOf("") }
    var comment by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableStateOf("") }
    var shareMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var shareLoading by rememberSaveable { mutableStateOf(false) }

    fun listLabel(listId: Int, fallbackType: String = ""): String =
        lists.firstOrNull { it.id == listId }?.displayTitle
            ?: DefaultTrackerList.fromApiType(fallbackType)?.label
            ?: "a list"

    fun quickSaveToList(list: UserList) {
        val foundSeries = series
        if (foundSeries == null) {
            shareMessage = "Search and select a title first."
            return
        }
        if (token.isBlank()) {
            shareMessage = "Sign in before updating your lists."
            return
        }
        scope.launch {
            shareLoading = true
            shareMessage = null
            runCatching {
                val previousListId = existingEntry?.listId
                selectedListId = list.id
                api.saveListEntry(
                    series = foundSeries,
                    listId = list.id,
                    chapter = chapter.toIntOrNull() ?: existingEntry?.chapter,
                    token = token,
                    updateExisting = existingEntry != null
                )
                existingEntry = api.retrieveListSeries(foundSeries.id, token)
                    ?: ListEntry(listId = list.id, listType = list.type, chapter = chapter.toIntOrNull())
                shareMessage = when (previousListId) {
                    null -> "Added to ${list.displayTitle}."
                    list.id -> "Saved in ${list.displayTitle}."
                    else -> "Moved to ${list.displayTitle}."
                }
                if (openedFromShare) {
                    onSharedSaveComplete()
                }
            }.onFailure {
                shareMessage = it.message ?: "Unable to update this list."
            }
            shareLoading = false
        }
    }

    fun loadFromQuery(nextQuery: String) {
        if (nextQuery.isBlank()) {
            shareMessage = "Enter a Mangago URL or title first."
            return
        }
        scope.launch {
            shareLoading = true
            shareMessage = null
            runCatching {
                val userLists = if (token.isNotBlank()) {
                    api.retrieveLists(token).sortedBy { DefaultTrackerList.fromApiType(it.type)?.ordinal ?: 99 }
                } else {
                    emptyList()
                }
                lists = userLists
                val foundSeries = api.searchSeries(nextQuery, token.takeIf { it.isNotBlank() })
                series = foundSeries
                if (token.isNotBlank()) {
                    existingEntry = api.retrieveListSeries(foundSeries.id, token)
                    existingComment = api.retrieveMyComment(foundSeries.id, token)
                    val existingRating = api.retrieveRating(foundSeries.id, token)
                    selectedListId = existingEntry?.listId
                        ?: lists.firstOrNull { it.type == DefaultTrackerList.READING.apiType }?.id
                        ?: lists.firstOrNull()?.id
                        ?: 0
                    chapter = existingEntry?.chapter?.toString().orEmpty()
                    comment = existingComment?.content.orEmpty()
                    rating = existingRating?.toString().orEmpty()
                }
            }.onFailure {
                shareMessage = it.message ?: "Unable to load this title."
            }
            shareLoading = false
        }
    }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) {
            selectedTab = 0
            sourceText = sharedText
            query = titleQueryFromSharedText(sharedText)
            loadFromQuery(query)
        }
    }

    LaunchedEffect(token) {
        if (token.isNotBlank() && lists.isEmpty()) {
            runCatching {
                api.retrieveLists(token).sortedBy { DefaultTrackerList.fromApiType(it.type)?.ordinal ?: 99 }
            }.onSuccess {
                lists = it
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Share") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Account") })
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("MangaUpdates Tracker", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = {
                            sourceText = it
                            query = titleQueryFromSharedText(it)
                        },
                        label = { Text("Mangago URL or title") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                query = titleQueryFromSharedText(sourceText)
                                loadFromQuery(query)
                            },
                            enabled = !shareLoading
                        ) {
                            Text("Search")
                        }
                        lists.forEach { list ->
                            FilterChip(
                                selected = existingEntry?.listId == list.id,
                                onClick = { quickSaveToList(list) },
                                enabled = !shareLoading && token.isNotBlank() && series != null,
                                label = { Text(list.displayTitle) }
                            )
                        }
                        if (shareLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    AssistChip(onClick = {}, label = { Text("Search: ${query.ifBlank { "none" }}") })

                    series?.let { foundSeries ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CoverImage(api = api, url = foundSeries.coverUrl)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        foundSeries.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("Series ID ${foundSeries.id}", style = MaterialTheme.typography.bodyMedium)
                                    existingEntry?.let {
                                        Text("Already in ${listLabel(it.listId, it.listType)}")
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = chapter,
                            onValueChange = { chapter = it.filter(Char::isDigit) },
                            label = { Text("Chapter progress") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        TenStarRating(
                            rating = rating,
                            onRatingChange = { rating = it },
                            onClear = { rating = "" }
                        )

                        OutlinedTextField(
                            value = comment,
                            onValueChange = { comment = it },
                            label = { Text("Comment") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )

                        Text("List", style = MaterialTheme.typography.titleMedium)
                        if (token.isBlank()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "Add credentials on the Account tab to load and update your MangaUpdates lists.",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                lists.forEach { list ->
                                    FilterChip(
                                        selected = selectedListId == list.id,
                                        onClick = { selectedListId = list.id },
                                        label = { Text(list.displayTitle) }
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    shareLoading = true
                                    shareMessage = null
                                    runCatching {
                                        val chapterNumber = chapter.toIntOrNull()
                                        val ratingNumber = rating.toDoubleOrNull()
                                        api.saveListEntry(
                                            series = foundSeries,
                                            listId = selectedListId,
                                            chapter = chapterNumber,
                                            token = token,
                                            updateExisting = existingEntry != null
                                        )
                                        api.saveRating(foundSeries.id, ratingNumber, token)
                                        api.saveComment(foundSeries.id, foundSeries.title, comment, existingComment?.id, token)
                                        existingEntry = api.retrieveListSeries(foundSeries.id, token)
                                        existingComment = api.retrieveMyComment(foundSeries.id, token)
                                        shareMessage = "Saved to MangaUpdates."
                                        if (openedFromShare) {
                                            onSharedSaveComplete()
                                        }
                                    }.onFailure {
                                        shareMessage = it.message ?: "Unable to save this entry."
                                    }
                                    shareLoading = false
                                }
                            },
                            enabled = !shareLoading && token.isNotBlank() && selectedListId > 0
                        ) {
                            Text("Save")
                        }
                    }

                    shareMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("MangaUpdates Account", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = keepSignedIn,
                            onCheckedChange = {
                                keepSignedIn = it
                                if (!it) credentials.clearSavedPassword()
                            }
                        )
                        Text("Keep password saved on this device")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    accountLoading = true
                                    accountMessage = null
                                    runCatching {
                                        val nextToken = api.login(username.trim(), password)
                                        credentials.saveSession(
                                            username = username.trim(),
                                            token = nextToken,
                                            password = password,
                                            keepPassword = keepSignedIn
                                        )
                                        token = nextToken
                                        if (!keepSignedIn) password = ""
                                        lists = api.retrieveLists(nextToken)
                                        accountMessage = if (keepSignedIn) {
                                            "Signed in. Password is saved with Android Keystore encryption."
                                        } else {
                                            "Signed in. Password was not saved."
                                        }
                                    }.onFailure {
                                        accountMessage = it.message ?: "Unable to sign in."
                                    }
                                    accountLoading = false
                                }
                            },
                            enabled = !accountLoading && username.isNotBlank() && password.isNotBlank()
                        ) {
                            Text("Sign in")
                        }
                        TextButton(
                            onClick = {
                                credentials.clear()
                                username = ""
                                password = ""
                                keepSignedIn = false
                                token = ""
                                lists = emptyList()
                                accountMessage = "Session cleared."
                            }
                        ) {
                            Text("Clear")
                        }
                        if (accountLoading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                    Text(
                        when {
                            token.isBlank() -> "No saved MangaUpdates session."
                            keepSignedIn -> "Saved session and encrypted password for ${username.ifBlank { "MangaUpdates" }}."
                            else -> "Saved session for ${username.ifBlank { "MangaUpdates" }}."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    accountMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoverImage(api: MangaUpdatesApi, url: String?) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = url?.let { api.loadImage(it) }
    }
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 140.dp)
            .aspectRatio(2f / 3f),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No cover", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TenStarRating(
    rating: String,
    onRatingChange: (String) -> Unit,
    onClear: () -> Unit
) {
    val ratingValue = rating.toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 0.0
    val activeColor = MaterialTheme.colorScheme.tertiary
    val inactiveColor = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Rating", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClear, enabled = ratingValue > 0.0) {
                Text("Clear")
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            repeat(10) { index ->
                val starNumber = index + 1
                val icon = when {
                    ratingValue >= starNumber -> Icons.Outlined.Star
                    ratingValue > index -> Icons.Outlined.StarHalf
                    else -> Icons.Outlined.StarOutline
                }
                IconButton(
                    onClick = { onRatingChange(starNumber.toString()) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = "$starNumber out of 10",
                        tint = if (ratingValue >= starNumber || ratingValue > index) activeColor else inactiveColor
                    )
                }
            }
        }
        Text(
            text = if (ratingValue > 0.0) "${ratingValue.cleanRatingLabel()} / 10" else "No rating",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun Double.cleanRatingLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

@Preview(showBackground = true)
@Composable
fun AppPreview() {
    MangaupdatesTrackerTheme {
        MangaupdatesTrackerApp("https://www.mangago.me/read-manga/gyoumugai_renai/")
    }
}
