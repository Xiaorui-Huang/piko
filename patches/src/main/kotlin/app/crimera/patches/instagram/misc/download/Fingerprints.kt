/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

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

internal object DirectThreadMediaSaveFingerprint : Fingerprint(
    classFingerprint = GetDirectThreadMediaSaverModuleNameFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "L", "L", "Z"),
    strings = listOf("android.permission.WRITE_EXTERNAL_STORAGE"),
)

internal object MediaOptionsOverflowMenuCreatorConstructorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MediaOptionsOverflowMenuCreator"),
)

// Builds the more (≡) button of the Reels UFI column: the test key of its paged card variant (the
// regular one is read from a name table) and its accessibility class.
internal object ClipsUfiMoreButtonFingerprint : Fingerprint(
    strings = listOf("clips_paged_card_ufi_more_button_component", "android.widget.Button"),
)

// Binds the story viewer footer (reply box, like, share) to a story item.
internal object StoryToolbarBindFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("ReelViewerItemToolbarBinder.bindView"),
    // Not SponsoredReelViewerItemToolbarBinder#bindView.
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)
