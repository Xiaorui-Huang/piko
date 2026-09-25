/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.download

import app.crimera.patches.instagram.entity.decoder.CURRENT_MEDIA_FIELD
import app.crimera.patches.instagram.entity.decoder.MEDIA_ADD_INFO_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.crimera.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.crimera.utils.changeFirstString
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.util.findFreeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val POST_UFI_BUTTONS_CLASS = "$DOWNLOAD_DESCRIPTOR/PostUfiButtons;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val SCALE_TYPE = "Landroid/widget/ImageView\$ScaleType;"

private val Instruction.methodRef get() = (this as? ReferenceInstruction)?.reference as? MethodReference
private val Instruction.fieldRef get() = (this as? ReferenceInstruction)?.reference as? FieldReference

val postDownloadButtonsPatch =
    bytecodePatch(
        description = "Adds a download button next to the save button on feed posts.",
    ) {
        dependsOn(decoderEntity, resourceMappingPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            PostUfiCurrentIndexFieldNameFingerprint.changeFirstString(CURRENT_MEDIA_FIELD.name)

            FeedUfiComponentFingerprint.method.apply {
                val insns = instructions.toList()
                fun fail(what: String): Nothing = throw PatchException("Feed UFI component: $what not found")

                val saveId = getResourceId(ResourceType.ID, "row_feed_button_save")
                val saveIdIndex =
                    insns.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == saveId }
                        .takeIf { it >= 0 } ?: fail("save id")

                // The save button's modifier chain:
                // align(empty, A0D) -> width(dimen) -> ... -> onClick(Function1) -> onLongClick(Function1).
                val (clickRef, longClickRef) =
                    insns.drop(saveIdIndex).mapNotNull { insn ->
                        insn.methodRef?.takeIf {
                            insn.opcode == Opcode.INVOKE_STATIC &&
                                it.parameterTypes.size == 2 &&
                                it.parameterTypes[1] == FUNCTION1 &&
                                it.returnType == it.parameterTypes[0]
                        }
                    }.take(2).takeIf { it.size == 2 } ?: fail("click modifiers")
                val modifierType = clickRef.returnType

                fun lastStaticCallBefore(index: Int, predicate: (MethodReference) -> Boolean) =
                    (index - 1 downTo 0).firstOrNull { i ->
                        insns[i].opcode == Opcode.INVOKE_STATIC && insns[i].methodRef?.let(predicate) == true
                    }

                val widthIndex =
                    lastStaticCallBefore(saveIdIndex) {
                        it.parameterTypes.map(CharSequence::toString) == listOf(modifierType, "J") &&
                            it.returnType == modifierType
                    } ?: fail("width modifier")
                val dimenIndex =
                    lastStaticCallBefore(widthIndex) { it.returnType == "J" && it.parameterTypes.size == 2 }
                        ?: fail("dimen lookup")
                val dimenLiteral =
                    (dimenIndex - 1 downTo 0).firstNotNullOfOrNull { (insns[it] as? WideLiteralInstruction)?.wideLiteral }
                        ?: fail("dimen literal")
                val alignIndex =
                    lastStaticCallBefore(dimenIndex) {
                        it.parameterTypes.size == 2 &&
                            it.parameterTypes[0] == modifierType &&
                            it.returnType == modifierType
                    } ?: fail("align modifier")
                val alignEnumField =
                    insns[alignIndex - 1].takeIf { it.opcode == Opcode.SGET_OBJECT }?.fieldRef ?: fail("align enum")
                val emptyModifierField =
                    insns.firstNotNullOfOrNull { insn ->
                        insn.fieldRef?.takeIf { insn.opcode == Opcode.SGET_OBJECT && it.definingClass == modifierType }
                    } ?: fail("empty modifier")

                // new IconComponent(scaleType, modifier, tint, drawable, color) for the save button.
                val saveInitIndex =
                    (saveIdIndex until insns.size).firstOrNull { i ->
                        insns[i].opcode == Opcode.INVOKE_DIRECT_RANGE &&
                            insns[i].methodRef?.let { it.name == "<init>" && it.parameterTypes.firstOrNull() == SCALE_TYPE } == true
                    } ?: fail("save icon constructor")
                val saveInit = insns[saveInitIndex] as RegisterRangeInstruction
                val listRegister =
                    (saveInitIndex until insns.size).firstNotNullOfOrNull { i ->
                        (insns[i] as? FiveRegisterInstruction)?.takeIf {
                            insns[i].methodRef?.name == "add" && insns[i].opcode == Opcode.INVOKE_VIRTUAL
                        }?.registerC
                    } ?: fail("icon list")

                // The bind state holding the media and carousel state is read right before the save button is built.
                val stateAccess =
                    (saveInitIndex - 1 downTo 0).firstNotNullOfOrNull { i ->
                        val field = insns[i].fieldRef ?: return@firstNotNullOfOrNull null
                        val stateClass = classDefByOrNull(field.definingClass) ?: return@firstNotNullOfOrNull null
                        val media = stateClass.fields.firstOrNull { it.type == MEDIA_CLASS_NAME }
                        val carousel = stateClass.fields.firstOrNull { it.type == MEDIA_ADD_INFO_CLASS_NAME }
                        if (media == null || carousel == null || insns[i] !is TwoRegisterInstruction) return@firstNotNullOfOrNull null
                        Triple((insns[i] as TwoRegisterInstruction).registerB, media, carousel)
                    } ?: fail("bind state")
                val (stateRegister, mediaField, carouselField) = stateAccess
                val userSessionField = FeedUfiComponentFingerprint.classDef.fields.firstOrNull { it.type == USER_SESSION_CLASS } ?: fail("user session")

                val insertIndex = saveInitIndex + 1
                val free = mutableListOf<Int>()
                while (free.size < 12) {
                    free += runCatching { findFreeRegister(insertIndex, free + listOf(listRegister, stateRegister)) }
                        .getOrNull() ?: break
                }
                // Non-range instructions need registers below v16: a wide pair plus one more.
                // Two further registers of any number hold values between steps.
                val low = free.filter { it < 16 }
                val wide = low.firstOrNull { it + 1 in low } ?: fail("free register pair")
                val rT = (low - setOf(wide, wide + 1)).firstOrNull() ?: fail("free register")
                val (rMod, rClick) = (free - setOf(wide, wide + 1, rT)).take(2).takeIf { it.size == 2 } ?: fail("free registers")
                if (listRegister > 15 || stateRegister > 15) fail("low list or state register")
                val rA = wide
                val rB = wide + 1

                addInstructionsWithLabels(
                    insertIndex,
                    """
                    invoke-static/range { v${saveInit.startRegister} .. v${saveInit.startRegister + saveInit.registerCount - 1} }, $POST_UFI_BUTTONS_CLASS->captureSaveIcon(Ljava/lang/Object;${SCALE_TYPE}Ljava/lang/Object;Ljava/lang/Integer;II)V
                    move-object/from16 v$rA, p0
                    iget-object v$rA, v$rA, $userSessionField
                    iget-object v$rB, v$stateRegister, $mediaField
                    iget-object v$rT, v$stateRegister, $carouselField
                    invoke-static { v$rA, v$rB, v$rT }, $POST_UFI_BUTTONS_CLASS->getClickHandler(${USER_SESSION_CLASS}Ljava/lang/Object;Ljava/lang/Object;)$FUNCTION1
                    move-result-object v$rT
                    if-eqz v$rT, :piko_skip_download_button
                    move-object/from16 v$rClick, v$rT
                    sget-object v$rA, $emptyModifierField
                    sget-object v$rB, $alignEnumField
                    invoke-static { v$rA, v$rB }, ${insns[alignIndex].methodRef}
                    move-result-object v$rT
                    move-object/from16 v$rMod, v$rT
                    move-object/from16 v$rT, p1
                    const v$rB, $dimenLiteral
                    invoke-static { v$rT, v$rB }, ${insns[dimenIndex].methodRef}
                    move-result-wide v$rA
                    move-object/from16 v$rT, v$rMod
                    invoke-static { v$rT, v$rA, v$rB }, ${insns[widthIndex].methodRef}
                    move-result-object v$rT
                    move-object/from16 v$rA, v$rClick
                    invoke-static { v$rT, v$rA }, $clickRef
                    move-result-object v$rT
                    invoke-static { v$rA }, $POST_UFI_BUTTONS_CLASS->getLongClickHandler($FUNCTION1)$FUNCTION1
                    move-result-object v$rA
                    invoke-static { v$rT, v$rA }, $longClickRef
                    move-result-object v$rT
                    invoke-static { v$listRegister, v$rT }, $POST_UFI_BUTTONS_CLASS->addDownloadButton(Ljava/util/ArrayList;Ljava/lang/Object;)V
                    :piko_skip_download_button
                    nop
                    """.trimIndent(),
                )
            }
        }
    }
