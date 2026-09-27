/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val REEL_UFI_BUTTONS_CLASS = "$DOWNLOAD_DESCRIPTOR/ReelUfiButtons;"
private const val STORY_DOWNLOAD_BUTTON_CLASS = "$DOWNLOAD_DESCRIPTOR/StoryDownloadButton;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
private const val CONSTRAINT_LAYOUT = "Landroidx/constraintlayout/widget/ConstraintLayout;"
private const val DOWNLOAD_BUTTON_METHOD = "pikoDownloadButton"

private val Instruction.methodRef get() = (this as? ReferenceInstruction)?.reference as? MethodReference
private val Instruction.fieldRef get() = (this as? ReferenceInstruction)?.reference as? FieldReference

val reelDownloadButtonPatch =
    bytecodePatch(
        description = "Adds a download button above the more button on Reels.",
    ) {
        dependsOn(decoderEntity)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val moreButton = ClipsUfiMoreButtonFingerprint.method
            val ufiClass = ClipsUfiMoreButtonFingerprint.classDef
            fun fail(what: String): Nothing = throw PatchException("Reels UFI component: $what not found")

            // The download button is built by a copy of the more button's builder with a different
            // icon, click handlers, ids and test keys, so it looks and lines up the same.
            val downloadButton =
                ImmutableMethod(
                    moreButton.definingClass,
                    DOWNLOAD_BUTTON_METHOD,
                    moreButton.parameters,
                    moreButton.returnType,
                    moreButton.accessFlags,
                    null,
                    null,
                    MutableMethodImplementation(moreButton.implementation!!),
                ).toMutable()
            ufiClass.methods.add(downloadButton)

            downloadButton.apply {
                val insns = instructions.toList()
                val modifierType =
                    insns.firstNotNullOfOrNull { insn ->
                        insn.methodRef?.takeIf { it.parameterTypes.size == 2 && it.parameterTypes[1] == FUNCTION1 && it.returnType == it.parameterTypes[0] }
                    }?.returnType ?: fail("modifier type")
                fun isModifierCall(insn: Instruction, vararg rest: String) =
                    insn.opcode == Opcode.INVOKE_STATIC &&
                        insn.methodRef?.let {
                            it.returnType == modifierType && it.parameterTypes.map(CharSequence::toString) == listOf(modifierType, *rest)
                        } == true

                // Edits go from the last instruction to the first, so earlier indices stay valid.
                val edits = sortedMapOf<Int, () -> Unit>(compareByDescending { it })

                // onClick(modifier, this.onMoreClick) -> onClick(modifier, download handler).
                val clickIndex = insns.indexOfFirst { isModifierCall(it, FUNCTION1) }.takeIf { it >= 0 } ?: fail("click modifier")
                val clickHandlerIndex =
                    (clickIndex - 1 downTo 0).firstOrNull { i ->
                        insns[i].opcode == Opcode.IGET_OBJECT && insns[i].fieldRef?.type == FUNCTION1
                    } ?: fail("click handler")
                val clickHandlerRegister = (insns[clickHandlerIndex] as TwoRegisterInstruction).registerA
                edits[clickHandlerIndex] = {
                    replaceInstruction(clickHandlerIndex, "invoke-static {}, $REEL_UFI_BUTTONS_CLASS->getClickHandler()$FUNCTION1")
                    addInstruction(clickHandlerIndex + 1, "move-result-object v$clickHandlerRegister")
                }

                // The more button only gets long press handlers under a flag. Add ours instead and clear the flag.
                val longClickIndex =
                    (clickIndex + 1 until insns.size).firstOrNull { isModifierCall(insns[it], FUNCTION1) } ?: fail("long click modifier")
                val longClickRef = insns[longClickIndex].methodRef!!
                val flagIndex =
                    (longClickIndex - 1 downTo clickIndex).firstOrNull { insns[it].opcode == Opcode.IF_EQZ } ?: fail("long click flag")
                val flagRegister = (insns[flagIndex] as OneRegisterInstruction).registerA
                val modifierRegister = (insns[flagIndex - 1] as? OneRegisterInstruction)
                    ?.takeIf { insns[flagIndex - 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA ?: fail("modifier before long click")
                edits[flagIndex] = {
                    addInstructions(
                        flagIndex,
                        """
                        invoke-static {}, $REEL_UFI_BUTTONS_CLASS->getLongClickHandler()$FUNCTION1
                        move-result-object v$flagRegister
                        invoke-static {v$modifierRegister, v$flagRegister}, $longClickRef
                        move-result-object v$modifierRegister
                        const/4 v$flagRegister, 0x0
                        """.trimIndent(),
                    )
                }

                // Impression logging and tooltip anchors of the more button: modifier(modifier, Function1, float, float).
                (clickIndex + 1 until flagIndex).filter { isModifierCall(insns[it], FUNCTION1, "F", "F") }.forEach { i ->
                    val register = (insns[i] as FiveRegisterInstruction).registerC
                    val result = (insns[i + 1] as OneRegisterInstruction).registerA
                    edits[i] = {
                        replaceInstruction(i, "invoke-static {v$register}, $REEL_UFI_BUTTONS_CLASS->skipModifier(Ljava/lang/Object;)Ljava/lang/Object;")
                        addInstruction(i + 2, "check-cast v$result, $modifierType")
                    }
                }

                // View ids (more_button, clips_ufi_more_button_component): leave the copy without one.
                insns.indices.filter { isModifierCall(insns[it], "I") }.forEach { i ->
                    val idRegister = (insns[i] as FiveRegisterInstruction).registerD
                    val constIndex = (i - 1 downTo 0).first { (insns[it] as? OneRegisterInstruction)?.registerA == idRegister }
                    if (insns[constIndex] !is NarrowLiteralInstruction) fail("view id")
                    edits[constIndex] = { replaceInstruction(constIndex, "const/4 v$idRegister, -0x1") }
                }

                // Test keys.
                insns.indices.filter { i ->
                    insns[i].opcode == Opcode.CONST_STRING &&
                        ((insns[i] as ReferenceInstruction).reference as StringReference).string.contains("more_button")
                }.forEach { i ->
                    val register = (insns[i] as OneRegisterInstruction).registerA
                    edits[i] = { replaceInstruction(i, "const-string v$register, \"piko_download_button\"") }
                }

                // Content description: getString(context, R.string.more) -> "Download current media".
                val descriptionIndex =
                    insns.indexOfFirst { insn ->
                        insn.opcode == Opcode.INVOKE_STATIC &&
                            insn.methodRef?.let { it.returnType == "Ljava/lang/String;" && it.parameterTypes.size == 2 && it.parameterTypes[1] == "I" } == true
                    }.takeIf { it >= 0 } ?: fail("content description")
                edits[descriptionIndex] = {
                    replaceInstruction(descriptionIndex, "invoke-static {}, $REEL_UFI_BUTTONS_CLASS->getContentDescription()Ljava/lang/String;")
                }

                // getDrawable(context, R.drawable.more) -> getDrawable(context, download icon).
                val drawableIndex =
                    insns.indexOfFirst { insn ->
                        insn.opcode == Opcode.INVOKE_STATIC &&
                            insn.methodRef?.let { it.returnType == DRAWABLE && it.parameterTypes.size == 2 && it.parameterTypes[1] == "I" } == true
                    }.takeIf { it >= 0 } ?: fail("drawable")
                val drawableCall = insns[drawableIndex] as FiveRegisterInstruction
                edits[drawableIndex] = {
                    // Replacing keeps the branch into the drawable lookup pointing at it.
                    replaceInstruction(drawableIndex, "invoke-static {}, $REEL_UFI_BUTTONS_CLASS->getDownloadIconId()I")
                    addInstructions(
                        drawableIndex + 1,
                        """
                        move-result v${drawableCall.registerD}
                        invoke-static {v${drawableCall.registerC}, v${drawableCall.registerD}}, ${drawableCall.methodRef}
                        """.trimIndent(),
                    )
                }

                // Skip the button when it's turned off: right after the builder reads the reel item.
                val userSessionField = ufiClass.fields.firstOrNull { it.type == USER_SESSION_CLASS } ?: fail("user session")
                val thisRegister =
                    insns.firstNotNullOfOrNull { insn ->
                        (insn as? TwoRegisterInstruction)?.takeIf { insn.opcode == Opcode.IGET_OBJECT && insn.fieldRef?.definingClass == ufiClass.type }?.registerB
                    } ?: fail("this register")
                val itemIndex =
                    insns.indexOfFirst { insn ->
                        insn.opcode == Opcode.INVOKE_INTERFACE &&
                            insn.methodRef?.returnType?.let { type ->
                                classDefByOrNull(type)?.methods?.any { it.returnType == MEDIA_CLASS_NAME && it.parameterTypes.isEmpty() }
                            } == true
                    }.takeIf { it >= 0 } ?: fail("reel item")
                val itemRegister = (insns[itemIndex + 1] as OneRegisterInstruction).registerA
                val free = findFreeRegister(itemIndex + 2, itemRegister, thisRegister)
                if (free > 15 || itemRegister > 15 || thisRegister > 15) fail("low registers")
                edits[itemIndex + 2] = {
                    addInstructionsWithLabels(
                        itemIndex + 2,
                        """
                        iget-object v$free, v$thisRegister, $userSessionField
                        invoke-static {v$free, v$itemRegister}, $REEL_UFI_BUTTONS_CLASS->prepare(${USER_SESSION_CLASS}Ljava/lang/Object;)Z
                        move-result v$free
                        if-nez v$free, :piko_show_download_button
                        const/4 v$free, 0x0
                        return-object v$free
                        :piko_show_download_button
                        nop
                        """.trimIndent(),
                    )
                }

                edits.values.forEach { it() }
            }

            // Add the download button right before each more button.
            val downloadButtonRef = "${moreButton.definingClass}->$DOWNLOAD_BUTTON_METHOD(${moreButton.parameterTypes.joinToString("")})${moreButton.returnType}"
            var callSites = 0
            ufiClass.methods.filter { it.name != DOWNLOAD_BUTTON_METHOD && it.implementation != null }.forEach { method ->
                val insns = method.instructions.toList()
                insns.indices.reversed().filter { i ->
                    insns[i].opcode == Opcode.INVOKE_DIRECT_RANGE && insns[i].methodRef?.let {
                        it.definingClass == moreButton.definingClass && it.name == moreButton.name && it.parameterTypes == moreButton.parameterTypes
                    } == true
                }.forEach { i ->
                    val call = insns[i] as RegisterRangeInstruction
                    val result = (insns[i + 1] as? OneRegisterInstruction)?.takeIf { insns[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA
                    val add = (insns[i + 2] as? FiveRegisterInstruction)?.takeIf { insns[i + 2].opcode == Opcode.INVOKE_VIRTUAL }
                    if (result == null || add == null || add.registerD != result) fail("more button call site")
                    (method as MutableMethod).addInstructionsAtControlFlowLabel(
                        i,
                        """
                        invoke-direct/range {v${call.startRegister} .. v${call.startRegister + call.registerCount - 1}}, $downloadButtonRef
                        move-result-object v$result
                        invoke-virtual {v${add.registerC}, v$result}, ${insns[i + 2].methodRef}
                        """.trimIndent(),
                    )
                    callSites++
                }
            }
            if (callSites == 0) fail("more button call sites")
        }
    }

val storyDownloadButtonPatch =
    bytecodePatch(
        description = "Adds a download button next to the like button on stories.",
    ) {
        dependsOn(decoderEntity)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            StoryToolbarBindFingerprint.method.apply {
                fun fail(what: String): Nothing = throw PatchException("Story toolbar binder: $what not found")
                val insns = instructions.toList()
                val parameterTypes = parameterTypes.map(CharSequence::toString)

                // p-register of each parameter; the method is static, and wide types take two registers.
                val parameterRegisters =
                    parameterTypes.runningFold(0) { register, type -> register + if (type == "J" || type == "D") 2 else 1 }
                fun parameterOf(type: String) =
                    parameterTypes.indexOf(type).takeIf { it >= 0 }?.let { parameterRegisters[it] }

                // The first reads of the story's media (reelItem.media) and the footer root (holder.root).
                fun parameterField(fieldType: String) =
                    insns.firstNotNullOfOrNull { insn ->
                        insn.fieldRef?.takeIf { insn.opcode == Opcode.IGET_OBJECT && it.type == fieldType && it.definingClass in parameterTypes }
                    }
                val mediaField = parameterField(MEDIA_CLASS_NAME) ?: fail("media field")
                val rootField = parameterField(CONSTRAINT_LAYOUT) ?: fail("footer root field")
                val userSession = parameterOf(USER_SESSION_CLASS) ?: fail("user session")
                val item = parameterOf(mediaField.definingClass.toString())!!
                val holder = parameterOf(rootField.definingClass.toString())!!
                if (implementation!!.registerCount - parameterRegisters.last() < 3) fail("free registers")

                addInstructionsWithLabels(
                    0,
                    """
                    move-object/from16 v0, p$userSession
                    move-object/from16 v1, p$item
                    if-eqz v1, :piko_no_story_media
                    iget-object v1, v1, $mediaField
                    :piko_no_story_media
                    move-object/from16 v2, p$holder
                    iget-object v2, v2, $rootField
                    invoke-static {v0, v1, v2}, $STORY_DOWNLOAD_BUTTON_CLASS->bind(${USER_SESSION_CLASS}Ljava/lang/Object;Landroid/view/ViewGroup;)V
                    """.trimIndent(),
                )
            }
        }
    }
