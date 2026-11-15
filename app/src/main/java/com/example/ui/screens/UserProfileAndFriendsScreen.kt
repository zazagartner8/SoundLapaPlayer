package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.remote.CloudFriend
import com.example.data.remote.CloudPublicProfile
import com.example.data.remote.normalizeUsernameHandle
import com.example.ui.components.AccountAvatarImage
import com.example.ui.i18n.LocalAppStrings
import com.example.ui.shaders.LiquidGlassCard

@Composable
fun DeveloperVerifiedPill(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.20f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Verified,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun UserProfileAndFriendsScreen(
    isSignedIn: Boolean,
    currentUid: String?,
    currentProfile: CloudPublicProfile?,
    inspectedProfile: CloudPublicProfile?,
    friends: List<CloudFriend>,
    searchResults: List<CloudPublicProfile>,
    isSearching: Boolean,
    isClaimingUsername: Boolean,
    statusMessage: String?,
    accent: Color,
    onBack: () -> Unit,
    onClearInspectedProfile: () -> Unit,
    onClaimUsername: (String) -> Unit,
    onSearchUsers: (String) -> Unit,
    onInspectProfile: (CloudPublicProfile) -> Unit,
    onAddFriend: (CloudPublicProfile) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onSignInRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalAppStrings.current
    val social = strings.social

    BackHandler {
        if (inspectedProfile != null) {
            onClearInspectedProfile()
        } else {
            onBack()
        }
    }

    var handleInput by remember(currentProfile?.username) {
        mutableStateOf(currentProfile?.username.orEmpty())
    }
    var searchQuery by remember { mutableStateOf("") }

    val friendUids = remember(friends) { friends.mapTo(HashSet()) { it.friendUid } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (inspectedProfile != null) onClearInspectedProfile() else onBack()
                        },
                        modifier = Modifier.testTag("social_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = strings.close,
                        )
                    }
                    Text(
                        text = if (inspectedProfile != null) {
                            inspectedProfile.displayName.ifBlank { social.profileTitle }
                        } else {
                            social.profileTitle
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (!isSignedIn) {
                item(key = "guest_lock") {
                    LiquidGlassCard(accentTint = accent, shape = RoundedCornerShape(24.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = social.guestLockedHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = onSignInRequest,
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("social_google_signin_button"),
                            ) {
                                Text(strings.settingsSignInGoogleAndSyncButton)
                            }
                        }
                    }
                }
            }

            if (inspectedProfile != null) {
                val isSelf = inspectedProfile.userId == currentUid
                val isAlreadyFriend = inspectedProfile.userId in friendUids
                item(key = "inspected_profile") {
                    LiquidGlassCard(accentTint = accent, shape = RoundedCornerShape(26.dp)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(accent.copy(alpha = 0.14f), Color.Transparent),
                                    ),
                                )
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AccountAvatarImage(
                                avatarUrlOrData = inspectedProfile.photoUrl,
                                contentDescription = inspectedProfile.displayName,
                                size = 86.dp,
                                accent = accent,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = inspectedProfile.displayName.ifBlank { "SoundLap User" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (inspectedProfile.isDeveloper) {
                                    DeveloperVerifiedPill(
                                        label = social.developerBadge,
                                        accent = accent,
                                    )
                                }
                            }
                            inspectedProfile.username?.takeIf { it.isNotBlank() }?.let { handle ->
                                Text(
                                    text = "@$handle",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                text = inspectedProfile.bio?.takeIf { it.isNotBlank() } ?: social.noBioPlaceholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (!isSelf && isSignedIn) {
                                Spacer(Modifier.height(4.dp))
                                if (isAlreadyFriend) {
                                    OutlinedButton(
                                        onClick = { onRemoveFriend(inspectedProfile.userId) },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("inspected_remove_friend_button"),
                                    ) {
                                        Icon(Icons.Rounded.PersonRemove, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(social.removeFriendButton)
                                    }
                                } else {
                                    Button(
                                        onClick = { onAddFriend(inspectedProfile) },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("inspected_add_friend_button"),
                                    ) {
                                        Icon(Icons.Rounded.PersonAdd, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(social.addFriendButton)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Own Profile Card + @username claimer
                if (currentProfile != null) {
                    item(key = "self_profile") {
                        LiquidGlassCard(accentTint = accent, shape = RoundedCornerShape(24.dp)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                ) {
                                    AccountAvatarImage(
                                        avatarUrlOrData = currentProfile.photoUrl,
                                        contentDescription = currentProfile.displayName,
                                        size = 62.dp,
                                        accent = accent,
                                        isGuest = !isSignedIn,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = currentProfile.displayName.ifBlank { "SoundLap User" },
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (currentProfile.isDeveloper) {
                                                DeveloperVerifiedPill(
                                                    label = social.developerBadge,
                                                    accent = accent,
                                                )
                                            }
                                        }
                                        currentProfile.username?.takeIf { it.isNotBlank() }?.let { handle ->
                                            Text(
                                                text = "@$handle",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = accent,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                        currentProfile.bio?.takeIf { it.isNotBlank() }?.let { bio ->
                                            Text(
                                                text = bio,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }

                                if (isSignedIn) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedTextField(
                                            value = handleInput,
                                            onValueChange = { raw ->
                                                handleInput = normalizeUsernameHandle(raw).filter {
                                                    it in 'a'..'z' || it in '0'..'9' || it == '_'
                                                }.take(24)
                                            },
                                            label = { Text(social.usernameLabel) },
                                            placeholder = { Text(social.usernamePlaceholder) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.AlternateEmail,
                                                    contentDescription = null,
                                                    tint = accent,
                                                )
                                            },
                                            singleLine = true,
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("claim_username_input"),
                                        )
                                        Button(
                                            onClick = { onClaimUsername(handleInput) },
                                            enabled = handleInput.length >= 3 && !isClaimingUsername,
                                            shape = RoundedCornerShape(16.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                                            modifier = Modifier
                                                .height(54.dp)
                                                .testTag("claim_username_button"),
                                        ) {
                                            if (isClaimingUsername) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                )
                                            } else {
                                                Text(social.claimUsernameButton, maxLines = 1)
                                            }
                                        }
                                    }
                                    Text(
                                        text = social.usernameHint,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                if (!statusMessage.isNullOrBlank()) {
                    item(key = "social_status") {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                if (isSignedIn) {
                    item(key = "search_friends_bar") {
                        LiquidGlassCard(accentTint = accent, shape = RoundedCornerShape(24.dp)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = social.searchResultsHeader,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        placeholder = { Text(social.searchFriendsPlaceholder) },
                                        leadingIcon = {
                                            Icon(Icons.Rounded.Search, contentDescription = null, tint = accent)
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { onSearchUsers(searchQuery) }),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("search_friends_input"),
                                    )
                                    Button(
                                        onClick = { onSearchUsers(searchQuery) },
                                        enabled = !isSearching,
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                        modifier = Modifier
                                            .height(54.dp)
                                            .testTag("search_friends_button"),
                                    ) {
                                        if (isSearching) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        } else {
                                            Text(social.searchFriendsButton, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        items(searchResults, key = { "search_${it.userId}" }) { user ->
                            val isSelf = user.userId == currentUid
                            val isFriend = user.userId in friendUids
                            PublicUserRowCard(
                                profile = user,
                                isSelf = isSelf,
                                isFriend = isFriend,
                                accent = accent,
                                developerBadgeLabel = social.developerBadge,
                                addFriendLabel = social.addFriendButton,
                                friendBadgeLabel = social.friendAddedBadge,
                                onOpenProfile = { onInspectProfile(user) },
                                onAddFriend = { onAddFriend(user) },
                            )
                        }
                    }

                    item(key = "friends_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Icon(Icons.Rounded.Group, contentDescription = null, tint = accent)
                            Text(
                                text = "${social.myFriendsHeader} (${friends.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    if (friends.isEmpty()) {
                        item(key = "empty_friends") {
                            Text(
                                text = social.noFriendsYet,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        items(friends, key = { "friend_${it.friendUid}" }) { friend ->
                            val asPublic = CloudPublicProfile(
                                docId = friend.friendUid,
                                userId = friend.friendUid,
                                displayName = friend.displayName,
                                username = friend.username,
                                photoUrl = friend.photoUrl,
                                isDeveloper = friend.isDeveloper,
                                isPublic = true,
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { onInspectProfile(asPublic) }
                                    .testTag("friend_row_${friend.friendUid}"),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AccountAvatarImage(
                                        avatarUrlOrData = friend.photoUrl,
                                        contentDescription = friend.displayName,
                                        size = 46.dp,
                                        accent = accent,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = friend.displayName.ifBlank { "SoundLap User" },
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (friend.isDeveloper) {
                                                DeveloperVerifiedPill(
                                                    label = social.developerBadge,
                                                    accent = accent,
                                                )
                                            }
                                        }
                                        friend.username?.takeIf { it.isNotBlank() }?.let { h ->
                                            Text(
                                                text = "@$h",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = accent,
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = { onRemoveFriend(friend.friendUid) },
                                        modifier = Modifier.testTag("remove_friend_${friend.friendUid}"),
                                    ) {
                                        Text(social.removeFriendButton, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicUserRowCard(
    profile: CloudPublicProfile,
    isSelf: Boolean,
    isFriend: Boolean,
    accent: Color,
    developerBadgeLabel: String,
    addFriendLabel: String,
    friendBadgeLabel: String,
    onOpenProfile: () -> Unit,
    onAddFriend: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpenProfile)
            .testTag("public_user_row_${profile.userId}"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccountAvatarImage(
                avatarUrlOrData = profile.photoUrl,
                contentDescription = profile.displayName,
                size = 46.dp,
                accent = accent,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = profile.displayName.ifBlank { "SoundLap User" },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.isDeveloper) {
                        DeveloperVerifiedPill(label = developerBadgeLabel, accent = accent)
                    }
                }
                profile.username?.takeIf { it.isNotBlank() }?.let { h ->
                    Text(
                        text = "@$h",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                    )
                }
            }
            if (!isSelf) {
                if (isFriend) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(accent.copy(alpha = 0.16f))
                            .border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
                        Text(friendBadgeLabel, style = MaterialTheme.typography.labelSmall, color = accent)
                    }
                } else {
                    IconButton(
                        onClick = onAddFriend,
                        modifier = Modifier.testTag("add_friend_${profile.userId}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PersonAdd,
                            contentDescription = addFriendLabel,
                            tint = accent,
                        )
                    }
                }
            }
        }
    }
}
