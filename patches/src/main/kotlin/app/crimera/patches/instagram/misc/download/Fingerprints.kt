/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral

internal object FeedButtonOnClickFingerprint : Fingerprint(
    parameters = listOf("Lcom/instagram/feed/media/mediaoption/MediaOption\$Option;"),
    strings = listOf("MediaOptionsOverflowHelper"),
    returnType = "V",
)

internal object AddReelButtonFingerprint : Fingerprint(
    strings = listOf("ClipsOrganicMediaItemViewMoreOptionsController", "reels"),
)

internal object GetDirectThreadMediaSaverModuleNameFingerprint : Fingerprint(
    strings = listOf("DirectThreadMediaSaver"),
    name = "getModuleName",
    returnType = "Ljava/lang/String;",
)

internal object MediaOptionsOverflowMenuCreatorConstructorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MediaOptionsOverflowMenuCreator"),
)

// Builds the like/comment/share/save row of feed posts as a component tree.
internal object FeedUfiComponentFingerprint : Fingerprint(
    returnType = "L",
    filters =
        listOf(
            resourceLiteral(ResourceType.ID, "row_feed_view_group_buttons"),
            resourceLiteral(ResourceType.ID, "row_feed_button_comment"),
            resourceLiteral(ResourceType.ID, "row_feed_button_share"),
            resourceLiteral(ResourceType.ID, "row_feed_button_save"),
        ),
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

internal object PostUfiCurrentIndexFieldNameFingerprint : Fingerprint(
    definingClass = "$DOWNLOAD_DESCRIPTOR/PostUfiButtons;",
    name = "getCurrentIndexFieldName",
)
