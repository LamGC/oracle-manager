package net.lamgc.scext.oraclemanager

import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.oracle.bmc.core.BlockstorageClient
import com.oracle.bmc.core.ComputeClient
import com.oracle.bmc.core.VirtualNetworkClient
import com.oracle.bmc.core.model.*
import com.oracle.bmc.core.requests.*
import com.oracle.bmc.model.BmcException
import mu.KotlinLogging
import net.lamgc.scalabot.extension.BotExtensionFactory
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.objects.Reply
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard
import java.io.File

class ServerExtensionFactory : BotExtensionFactory {
    override fun createExtensionInstance(bot: BaseAbilityBot, shareDataFolder: File): AbilityExtension {
        return OracleServerExtension(bot)
    }
}

@Suppress("unused")
class OracleServerExtension(private val bot: BaseAbilityBot) : AbilityExtension {

    fun listServers() = callbackQueryOf("oc_server_list") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = ComputeClient(profile.getAuthenticationDetailsProvider())
        val instances = client.listInstances(
            ListInstancesRequest.builder()
                .compartmentId(profile.tenantId)
                .build()
        ).items.filter {
            it.lifecycleState in (Instance.LifecycleState.Moving..Instance.LifecycleState.CreatingImage)
        }

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (instance in instances) {
            keyboardBuilder
                .rowButton {
                    text("${instance.displayName}【${instance.region}】")
                    callbackData(
                        action = "oc_server_manage",
                        extraData = jsonObjectOf {
                            JsonFields.AccountProfile += profile
                            JsonFields.ServerInstance += ServerInstance.fromBmcInstance(instance)
                        }
                    )
                }
        }

        keyboardBuilder.rowButton {
            text("<<< 返回上一级")
            callbackData(upd.callbackQuery.callbackData.next("oc_account_manager"))
        }

        EditMessageText.builder()
            .text("Oracle 账号 ${profile.name} 有下列服务器")
            .messageId(upd.callbackQuery.message.messageId)
            .chatId(upd.callbackQuery.message.chatId.toString())
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot.silent())
    }

    fun manageServerReply(): Reply = callbackQueryOf("oc_server_manage") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val client = ComputeClient(profile.getAuthenticationDetailsProvider())
        val instance = client.getInstance(GetInstanceRequest.builder().instanceId(instanceInfo.id).build()).instance

        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .newRow()
            .addButton {
                text("电源")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_edit"))
            }
            .addButton {
                text("网络")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_network_edit"))
            }
            .then()
            .rowButton {
                text("服务器设置")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_edit"))
            }
            .rowButton {
                text("*** 刷新 ***")
                callbackData(upd.callbackQuery.callbackData)
            }
            .rowButton {
                text("<<< 返回上一级")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_list"))
            }

        val shape = instance.shapeConfig
        EditMessageText.builder()
            .text(
                """
                ${instanceInfo.displayName}
                当前状态：${instance.lifecycleState}
                配置：${shape.ocpus} Core (CPU：${shape.processorDescription}) / ${shape.memoryInGBs} GB (内存)
                GPU：${shape.gpus}（${shape.gpuDescription}）
                网络带宽：${shape.networkingBandwidthInGbps} Gbps
                区域：${instanceInfo.regionId}
                可用区 / 容错区：${instanceInfo.availabilityDomain} / ${instanceInfo.faultDomain}
            """.trimIndent()
            )
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot.silent())
    }

    fun editServerInstance(): Reply = callbackQueryOf("oc_server_edit") { bot, upd ->
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .newRow()
            .addButton {
                text("更改名称")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_name_change"))
            }
            .then()
            .rowButton {
                text("释放服务器（永久删除）")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_remove"))
            }
            .rowButton {
                text("<<< 返回上一级")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_manage"))
            }

        EditMessageReplyMarkup.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot.silent())
    }

    fun changeServerName(): Reply = callbackQueryOf("oc_server_name_change") { bot, upd ->
        bot.db().getVar<String>(
            "oc_server_name_change::chat_${upd.callbackQuery.message.chatId}::user_${upd.callbackQuery.from.id}::profile"
        )
            .set(upd.callbackQuery.callbackData.toJson())
        val message = SendMessage.builder()
            .text("请发送服务器的新名称。")
            .chatId(upd.callbackQuery.message.chatId.toString())
            .replyMarkup(
                ForceReplyKeyboard.builder()
                    .selective(true)
                    .forceReply(true)
                    .inputFieldPlaceholder("新的服务器名称")
                    .build()
            )
            .build().execute(bot.sender())

        bot.db()
            .getVar<Long>("oc_server_name_change::chat_${upd.callbackQuery.message.chatId}::user_${upd.callbackQuery.from.id}::messageId")
            .set(message.messageId.toLong())
    }

    fun changeServerNameExecute(): Reply = Reply.of({ bot, upd ->
        bot.db()
            .getVar<Long>("oc_server_name_change::chat_${upd.message.chatId}::user_${upd.message.from.id}::messageId")
            .set(null)
        val callbackEntry = bot.db().getVar<String>(
            "oc_server_name_change::chat_${upd.message.chatId}::user_${upd.message.from.id}::profile"
        )
        val callbackJson = callbackEntry.get()
        callbackEntry.set(null)

        val callback = InlineKeyboardCallback.fromJson(callbackJson)
        val profile = getProfileByCallback(callback)
        val client = ComputeClient(profile.getAuthenticationDetailsProvider())
        val instanceInfo = ServerInstance.fromJson(callback.extraData[JsonFields.ServerInstance])
        try {
            val instance = client.updateInstance(
                UpdateInstanceRequest.builder()
                    .instanceId(instanceInfo.id)
                    .updateInstanceDetails(
                        UpdateInstanceDetails.builder()
                            .displayName(upd.message.text.trim())
                            .build()
                    )
                    .build()
            ).instance
            bot.silent().send("服务器改名成功！新的名称为：\n${instance.displayName}", upd.message.chatId)
        } catch (e: Exception) {
            logger.error(e) { "请求更改实例名称时发生错误." }
            bot.silent().send("", upd.message.chatId)
        }
    }, {
        it.hasMessage() && it.message.hasText() && it.message.isReply &&
                bot.db()
                    .getVar<Long>("oc_server_name_change::chat_${it.message.chatId}::user_${it.message.from.id}::messageId")
                    .get() == it.message.replyToMessage.messageId.toLong()
    })

    fun removeServerRequest(): Reply = callbackQueryOf("oc_server_remove") { bot, upd ->
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            // Minecraft
            .text(
                """
                你确定要删除这个实例吗？
                “${instanceInfo.displayName}”将会永久失去！（真的很久！）
            """.trimIndent()
            )
            .replyMarkup(
                createPromptKeyboard(
                    yesCallback = upd.callbackQuery.callbackData.next("oc_server_remove::confirm"),
                    yesMsg = "删除它！",
                    noCallback = upd.callbackQuery.callbackData.next("oc_server_manage"),
                )
            )
            .build().execute(bot.silent())
    }

    fun removeServerConfirm(): Reply = callbackQueryOf("oc_server_remove::confirm") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = ComputeClient(profile.getAuthenticationDetailsProvider())
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        try {
            val bootVolumeAttachments = client.listBootVolumeAttachments(
                ListBootVolumeAttachmentsRequest.builder()
                    .instanceId(instanceInfo.id)
                    .compartmentId(profile.tenantId)
                    .availabilityDomain(instanceInfo.availabilityDomain)
                    .build()
            ).items.map { it.bootVolumeId }.toList()

            client.terminateInstance(
                TerminateInstanceRequest.builder()
                    .instanceId(instanceInfo.id)
                    .build()
            )
            EditMessageText.builder()
                .text("实例 ${instanceInfo.displayName} 已执行释放。")
                .messageId(upd.callbackQuery.message.messageId)
                .chatId(upd.callbackQuery.message.chatId.toString())
                .replyMarkup(InlineKeyboardGroupBuilder()
                    .rowButton {
                        text("删除引导卷")
                        callbackData(
                            upd.callbackQuery.callbackData.next(
                                "oc_server_remove_bootvolume",
                                jsonObjectOf {
                                    JsonFields.AccountProfile += profile
                                    JsonFields.BootVolumeIds += bootVolumeAttachments
                                },
                                replaceData = true
                            )
                        )
                    }
                    .rowButton {
                        text("<<< 回到服务器列表")
                        callbackData(upd.callbackQuery.callbackData.next("oc_server_list", jsonObjectOf {
                            JsonFields.AccountProfile += profile
                        }, replaceData = true))
                    }
                    .build())
                .build().execute(bot.silent())
        } catch (e: BmcException) {
            logger.error(e) { "请求释放实例时发生错误." }
            bot.silent().send("请求释放实例时发生错误，请稍后重试。", upd.callbackQuery.message.chatId)
        }
    }

    fun removeBootVolumePrompt(): Reply = callbackQueryOf("oc_server_remove_bootvolume") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = BlockstorageClient(profile.getAuthenticationDetailsProvider())
        val bootVolumeIds =
            gson.fromJson<List<String>>(
                upd.callbackQuery.callbackData.extraData[JsonFields.BootVolumeIds],
                object : TypeToken<List<String>>() {}.type
            )

        if (bootVolumeIds.isEmpty()) {
            bot.silent().send("没有需要删除的引导卷。", upd.callbackQuery.message.chatId)
            return@callbackQueryOf
        }

        val msgBuilder = StringBuilder(
            """
            确定要删除以下引导卷？
        """.trimIndent()
        ).append('\n')

        for (bootVolumeId in bootVolumeIds) {
            val bootVolume = client.getBootVolume(
                GetBootVolumeRequest.builder()
                    .bootVolumeId(bootVolumeId)
                    .build()
            ).bootVolume
            msgBuilder.append("- ${bootVolume.displayName}（${bootVolume.sizeInGBs} GB）")
        }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                createPromptKeyboard(
                    yesCallback = upd.callbackQuery.callbackData.next("oc_server_remove_bootvolume::execute"),
                    noCallback = upd.callbackQuery.callbackData.next("oc_server_list")
                )
            )
            .text(msgBuilder.toString())
            .build().execute(bot)
    }

    fun removeBootVolumeExecute(): Reply = callbackQueryOf("oc_server_remove_bootvolume::execute") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = BlockstorageClient(profile.getAuthenticationDetailsProvider())
        val bootVolumeIds =
            gson.fromJson<List<String>>(
                upd.callbackQuery.callbackData.extraData[JsonFields.BootVolumeIds],
                object : TypeToken<List<String>>() {}.type
            )

        if (bootVolumeIds.isEmpty()) {
            bot.silent().send("没有需要删除的引导卷。", upd.callbackQuery.message.chatId)
            return@callbackQueryOf
        }

        // FIXME: 由于实例仍未释放完成, 引导卷依然挂载在实例上, 会导致引导卷删除失败.

        val msgBuilder = StringBuilder("引导卷删除结果：").append('\n')
        var hasError = false
        var errorVolume: BootVolume? = null
        var error: BmcException? = null
        for (bootVolumeId in bootVolumeIds) {
            val bootVolume = client.getBootVolume(
                GetBootVolumeRequest.builder()
                    .bootVolumeId(bootVolumeId)
                    .build()
            ).bootVolume
            if (hasError) {
                msgBuilder.append("[跳过] ${bootVolume.displayName}").append('\n')
                continue
            }
            try {
                client.deleteBootVolume(
                    DeleteBootVolumeRequest.builder()
                        .bootVolumeId(bootVolumeId)
                        .build()
                )
                msgBuilder.append("[成功] ${bootVolume.displayName}").append('\n')
            } catch (e: BmcException) {
                hasError = true
                errorVolume = bootVolume
                error = e
                msgBuilder.append("[错误] ${bootVolume.displayName}").append('\n')
            }
        }

        if (hasError && errorVolume != null) {
            msgBuilder.append("\n\n").append("引导卷 ${errorVolume.displayName} (${errorVolume.sizeInGBs} GB) 删除失败：\n")
                .append(error!!.message)
        }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                InlineKeyboardGroupBuilder()
                    .addBackButton(upd.callbackQuery.callbackData.next("oc_server_list"))
                    .build()
            )
            .text(msgBuilder.toString())
            .build().execute(bot)
    }

    fun editServerPower(): Reply = callbackQueryOf("oc_server_power_edit") { bot, upd ->
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("开机")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    JsonFields.PowerAction += InstanceAction.START.actionValue
                }))
            }
            .newRow()
            .addButton {
                text("重启（软重启）")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    JsonFields.PowerAction += InstanceAction.SOFT_RESET.actionValue
                }))
            }
            .addButton {
                text("强制重启")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    JsonFields.PowerAction += InstanceAction.RESET.actionValue
                }))
            }
            .newRow()
            .addButton {
                text("关机（软关机）")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    JsonFields.PowerAction += InstanceAction.SOFT_STOP.actionValue
                }))
            }
            .addButton {
                text("强制关机")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    JsonFields.PowerAction += InstanceAction.STOP.actionValue
                }))
            }
            .then()
            .rowButton {
                text("<<< 返回上一级")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_manage"))
            }

        EditMessageReplyMarkup.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot.silent())
    }

    fun queryServerPowerAction(): Reply = callbackQueryOf("oc_server_power_query") { bot, upd ->
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val powerAction = upd.callbackQuery.callbackData.extraData["power_action"]
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .text(
                """
                实例 ${instanceInfo.displayName} 将要执行电源操作：$powerAction
                确定执行操作吗？
            """.trimIndent()
            )
            .replyMarkup(createPromptKeyboard(
                yesCallback = upd.callbackQuery.callbackData.next("oc_server_power_execute"),
                noCallback = upd.callbackQuery.callbackData.next(
                    newAction = "oc_server_manage",
                    replaceData = true,
                    newExtraData = jsonObjectOf {
                        JsonFields.AccountProfile += getProfileByCallback(upd.callbackQuery.callbackData)
                        JsonFields.ServerInstance += upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance]
                    }
                )
            ))
            .build().execute(bot.silent())
    }

    fun executeServerPowerAction(): Reply = callbackQueryOf("oc_server_power_execute") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val powerAction = upd.callbackQuery.callbackData.extraData["power_action"].asString
        val client = ComputeClient(profile.getAuthenticationDetailsProvider())

        try {
            client.instanceAction(
                InstanceActionRequest.builder()
                    .action(powerAction)
                    .instanceId(instanceInfo.id)
                    .build()
            )

            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text("操作已请求执行。")
                .replyMarkup(InlineKeyboardGroupBuilder()
                    .rowButton {
                        text("<<< 返回服务器")
                        callbackData(upd.callbackQuery.callbackData.next(
                            newAction = "oc_server_manage",
                            replaceData = true,
                            newExtraData = jsonObjectOf {
                                JsonFields.AccountProfile += getProfileByCallback(upd.callbackQuery.callbackData)
                                JsonFields.ServerInstance += upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance]
                            }
                        ))
                    }
                    .build())
                .build().execute(bot.silent())
        } catch (e: Exception) {
            logger.error(e) { "请求执行实例电源操作时发生错误." }
            bot.silent().send("请求电源操作时发生错误，请重试一次。", upd.callbackQuery.message.chatId)
        }
    }

    fun editServerNetworkMenu(): Reply = callbackQueryOf("oc_server_network_edit") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val adp = profile.getAuthenticationDetailsProvider()
        val computeClient = ComputeClient(adp)
        val networkClient = VirtualNetworkClient(adp)
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val vnicAttachments = computeClient.listVnicAttachments(
            ListVnicAttachmentsRequest.builder()
                .compartmentId(profile.tenantId)
                .instanceId(instanceInfo.id)
                .build()
        ).items

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (vnicAttachment in vnicAttachments) {

            keyboardBuilder.rowButton {
                val vnic = networkClient.getVnic(GetVnicRequest.builder().vnicId(vnicAttachment.vnicId).build()).vnic
                text("${vnic.displayName}【${vnic.privateIp}】${if (vnic.isPrimary) "（主要）" else ""}")
                callbackData(
                    upd.callbackQuery.callbackData.next(
                        newAction = "oc_server_network_vnic_edit",
                        newExtraData = jsonObjectOf {
                            JsonFields.VnicId += vnicAttachment.vnicId
                        })
                )
            }
        }

        keyboardBuilder.addBackButton(upd.callbackQuery.callbackData.next("oc_server_manage"))
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .text(
                """
                服务器实例 ${instanceInfo.displayName} 有以下 VNIC（网卡）：
            """.trimIndent()
            )
            .build().execute(bot)
    }

    fun editServerVnicMenu(): Reply = callbackQueryOf("oc_server_network_vnic_edit") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val vnicId = upd.callbackQuery.callbackData.extraData[JsonFields.VnicId].asString
        val adp = profile.getAuthenticationDetailsProvider()
        val networkClient = VirtualNetworkClient(adp)

        val vnic = networkClient.getVnic(GetVnicRequest.builder().vnicId(vnicId).build()).vnic

        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("更换公网 IP（保留 IP）")
                callbackData(
                    upd.callbackQuery.callbackData.next(
                        "oc_server_network_change_ipv4pub_reserved"
                    )
                )
            }
            .rowButton {
                text("更换公网 IP（临时 IP）")
                callbackData(
                    upd.callbackQuery.callbackData.next(
                        "oc_server_network_change_ipv4pub_random"
                    )
                )
            }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardBuilder
                    .addBackButton(upd.callbackQuery.callbackData.next("oc_server_network_edit"))
                    .build()
            )
            .text(
                """
                ----------- [ VNIC ] -----------
                ${vnic.displayName}
                私有 IP：${vnic.privateIp}
                公网 IP：${if (vnic.publicIp == null) "无" else vnic.publicIp}
                MAC 地址：${vnic.macAddress}
                主要网卡：${if (vnic.isPrimary) "是" else "否"}
                当前状态：${vnic.lifecycleState}
            """.trimIndent()
            )
            .build().execute(bot)
    }

    fun changeReservedPublicIpList(): Reply = callbackQueryOf("oc_server_network_change_ipv4pub_reserved") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val adp = profile.getAuthenticationDetailsProvider()
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val networkClient = VirtualNetworkClient(adp)

        val publicIps = networkClient.listPublicIps(
            ListPublicIpsRequest.builder()
                .compartmentId(profile.tenantId)
                .scope(ListPublicIpsRequest.Scope.Region)
                .lifetime(ListPublicIpsRequest.Lifetime.Reserved)
                .build()
        ).items.filter {
            it.privateIpId == null &&
                    (
                            it.lifecycleState == PublicIp.LifecycleState.Available ||
                                    it.lifecycleState == PublicIp.LifecycleState.Unassigned
                            )
        }

        if (publicIps.isEmpty()) {
            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .replyMarkup(
                    InlineKeyboardGroupBuilder()
                        .addBackButton(upd.callbackQuery.callbackData.next("oc_server_network_vnic_edit"))
                        .build()
                )
                .text(
                    """
                你还没有可以绑定到实例的公共 IP。
            """.trimIndent()
                )
                .build().execute(bot)
            return@callbackQueryOf
        }

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (publicIp in publicIps) {
            keyboardBuilder.rowButton {
                text(publicIp.ipAddress)
                callbackData(
                    upd.callbackQuery.callbackData.next(
                        newAction = "oc_server_network_change_ipv4pub_reserved::prompt",
                        newExtraData = jsonObjectOf {
                            JsonFields.PublicIpId += publicIp.id
                        })
                )
            }
        }

        bot.db().getVar<Boolean>(
            "oc_server_network_change_ipv4pub::" +
                    "chat_${upd.callbackQuery.message.chatId}::user_${upd.callbackQuery.from.id}::flag"
        ).set(false)
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardBuilder
                    .addBackButton(upd.callbackQuery.callbackData.next("oc_server_network_vnic_edit"))
                    .build()
            )
            .text(
                """
                选择一个绑定到 ${instanceInfo.displayName} 的公共 IP：
            """.trimIndent()
            )
            .build().execute(bot)
    }

    fun changeReservedPublicIpListPrompt(): Reply =
        callbackQueryOf("oc_server_network_change_ipv4pub_reserved::prompt") { bot, upd ->
            val instanceInfo =
                ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .replyMarkup(
                    createPromptKeyboard(
                        yesCallback = upd.callbackQuery.callbackData.next("oc_server_network_change_ipv4pub_reserved::execute"),
                        noCallback = upd.callbackQuery.callbackData.next("oc_server_network_change_ipv4pub_reserved")
                    )
                )
                .text(
                    "你真的要为实例 ${instanceInfo.displayName} 更换成预留公共 IP？\n" +
                            "（如果原来的公共 IP 是预留 IP 的话，将不会删除该 IP）"
                )
                .build().execute(bot)
        }

    fun changeReservedPublicIpListExecute(): Reply =
        callbackQueryOf("oc_server_network_change_ipv4pub_reserved::execute") { bot, upd ->
            val profile = getProfileByCallback(upd.callbackQuery.callbackData)
            val vnicId = upd.callbackQuery.callbackData.extraData[JsonFields.VnicId].asString
            val adp = profile.getAuthenticationDetailsProvider()
            val networkClient = VirtualNetworkClient(adp)
            val afterPublicIpId = upd.callbackQuery.callbackData.extraData[JsonFields.PublicIpId].asString
            val instanceInfo =
                ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])

            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text("正在准备更换公共 IP（预留）...")
                .build()
                .execute(bot)

            val vnic = networkClient.getVnic(
                GetVnicRequest.builder()
                    .vnicId(vnicId).build()
            ).vnic
            val privateIp = networkClient.listPrivateIps(
                ListPrivateIpsRequest.builder().vnicId(vnicId).subnetId(vnic.subnetId).build()
            ).items.first()
            val beforePublicIp = try {
                networkClient.getPublicIpByPrivateIpId(
                    GetPublicIpByPrivateIpIdRequest.builder()
                        .getPublicIpByPrivateIpIdDetails(
                            GetPublicIpByPrivateIpIdDetails.builder()
                                .privateIpId(privateIp.id)
                                .build()
                        )
                        .build()
                ).publicIp
            } catch (e: BmcException) {
                if (e.statusCode != 404) {
                    logger.error(e) { "获取原公共 IP 时发生错误." }
                    bot.silent().send("获取原公共 IP 时发生错误。\n${e.message}", upd.callbackQuery.message.chatId)
                    return@callbackQueryOf
                }
                null
            }

            if (beforePublicIp != null) {
                EditMessageText.builder()
                    .chatId(upd.callbackQuery.message.chatId.toString())
                    .messageId(upd.callbackQuery.message.messageId)
                    .text("正在解绑旧的公共 IP...")
                    .build()
                    .execute(bot)

                if (beforePublicIp.lifetime == PublicIp.Lifetime.Reserved) {
                    networkClient.updatePublicIp(
                        UpdatePublicIpRequest.builder()
                            .publicIpId(beforePublicIp.id)
                            .updatePublicIpDetails(
                                UpdatePublicIpDetails.builder()
                                    .privateIpId("")
                                    .build()
                            )
                            .build()
                    )
                } else {
                    networkClient.deletePublicIp(
                        DeletePublicIpRequest.builder()
                            .publicIpId(beforePublicIp.id)
                            .build()
                    )
                }
            }

            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text("正在绑定新的预留公共 IP...")
                .build()
                .execute(bot)

            val afterPublicIp = networkClient.updatePublicIp(
                UpdatePublicIpRequest.builder()
                    .publicIpId(afterPublicIpId)
                    .updatePublicIpDetails(
                        UpdatePublicIpDetails.builder()
                            .privateIpId(privateIp.id)
                            .build()
                    )
                    .build()
            ).publicIp

            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text(
                    """
                实例 ${instanceInfo.displayName} 的 VNIC ${vnic.displayName} 已成功更换公共 IP（保留）
                新的公共 IP：${afterPublicIp.ipAddress}
            """.trimIndent()
                )
                .replyMarkup(
                    InlineKeyboardGroupBuilder().addBackButton(
                        callback = upd.callbackQuery.callbackData.next("oc_server_network_vnic_edit")
                    ).build()
                )
                .build()
                .execute(bot)

        }

    fun changePublicIpRandomPrompt(): Reply = callbackQueryOf("oc_server_network_change_ipv4pub_random") { bot, upd ->
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                createPromptKeyboard(
                    yesCallback = upd.callbackQuery.callbackData.next("oc_server_network_change_ipv4pub_random::execute"),
                    noCallback = upd.callbackQuery.callbackData.next("oc_server_network_vnic_edit")
                )
            )
            .text(
                "你真的要为实例 ${instanceInfo.displayName} 更换成临时公共 IP？\n" +
                        "（如果原来的公共 IP 是预留 IP 的话，将不会删除该 IP）"
            )
            .build().execute(bot)
    }

    fun changePublicIpRandomExecute(): Reply = callbackQueryOf("oc_server_network_change_ipv4pub_random::execute")
    { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val adp = profile.getAuthenticationDetailsProvider()
        val vnicId = upd.callbackQuery.callbackData.extraData[JsonFields.VnicId].asString
        val instanceInfo = ServerInstance.fromJson(upd.callbackQuery.callbackData.extraData[JsonFields.ServerInstance])
        val networkClient = VirtualNetworkClient(adp)

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .text("正在准备更换公共 IP...")
            .build()
            .execute(bot)

        val vnic = networkClient.getVnic(
            GetVnicRequest.builder()
                .vnicId(vnicId)
                .build()
        ).vnic
        val privateIp = networkClient.listPrivateIps(
            ListPrivateIpsRequest.builder()
                .vnicId(vnicId)
                .subnetId(vnic.subnetId)
                .build()
        ).items.first()

        val beforePublicIp = try {
            networkClient.getPublicIpByPrivateIpId(
                GetPublicIpByPrivateIpIdRequest.builder()
                    .getPublicIpByPrivateIpIdDetails(
                        GetPublicIpByPrivateIpIdDetails.builder()
                            .privateIpId(privateIp.id)
                            .build()
                    )
                    .build()
            ).publicIp
        } catch (e: BmcException) {
            if (e.statusCode != 404) {
                logger.error(e) { "获取原公共 IP 时发生错误." }
                bot.silent().send("获取原公共 IP 时发生错误。\n${e.message}", upd.callbackQuery.message.chatId)
                return@callbackQueryOf
            }
            null
        }

        if (beforePublicIp != null) {
            EditMessageText.builder()
                .chatId(upd.callbackQuery.message.chatId.toString())
                .messageId(upd.callbackQuery.message.messageId)
                .text("正在解绑旧的公共 IP...")
                .build()
                .execute(bot)

            if (beforePublicIp.lifetime == PublicIp.Lifetime.Reserved) {
                networkClient.updatePublicIp(
                    UpdatePublicIpRequest.builder()
                        .publicIpId(beforePublicIp.id)
                        .updatePublicIpDetails(
                            UpdatePublicIpDetails.builder()
                                .privateIpId("")
                                .build()
                        )
                        .build()
                )
            } else {
                networkClient.deletePublicIp(
                    DeletePublicIpRequest.builder()
                        .publicIpId(beforePublicIp.id)
                        .build()
                )
            }
        }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .text("正在创建新的临时公共 IP...")
            .build()
            .execute(bot)

        val afterPublicIp = networkClient.createPublicIp(
            CreatePublicIpRequest.builder()
                .createPublicIpDetails(
                    CreatePublicIpDetails.builder()
                        .lifetime(CreatePublicIpDetails.Lifetime.Ephemeral)
                        .privateIpId(privateIp.id)
                        .compartmentId(profile.tenantId)
                        .build()
                )
                .build()
        ).publicIp

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .text(
                """
                实例 ${instanceInfo.displayName} 的 VNIC ${vnic.displayName} 已成功更换公共 IP（临时）
                新的公共 IP：${afterPublicIp.ipAddress}
            """.trimIndent()
            )
            .replyMarkup(
                InlineKeyboardGroupBuilder().addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_server_network_vnic_edit")
                ).build()
            )
            .build()
            .execute(bot)
    }

    companion object {
        val logger = KotlinLogging.logger { }
    }
}

data class ServerInstance(
    val id: String,
    val displayName: String,
    val compartmentId: String,
    val availabilityDomain: String,
    val faultDomain: String,
    val regionId: String,
    val imageId: String
) {

    companion object {
        @JvmStatic
        fun fromJson(jsonStr: String): ServerInstance =
            gson.fromJson(jsonStr, ServerInstance::class.java)

        @JvmStatic
        fun fromJson(json: JsonElement): ServerInstance =
            gson.fromJson(json, ServerInstance::class.java)

        @JvmStatic
        fun fromBmcInstance(bmcInstance: Instance): ServerInstance = ServerInstance(
            id = bmcInstance.id,
            displayName = bmcInstance.displayName,
            compartmentId = bmcInstance.compartmentId,
            availabilityDomain = bmcInstance.availabilityDomain,
            faultDomain = bmcInstance.faultDomain,
            regionId = bmcInstance.region,
            imageId = bmcInstance.imageId
        )

    }
}

/**
 * 实例动作.
 *
 * 可对实例执行的操作.
 * @author LamGC
 */
enum class InstanceAction(
    /**
     * 获取动作的 API 调用值.
     * @return 返回 API 所规定的对应值.
     */
    val actionValue: String
) {
    /**
     * 启动实例.
     */
    START("start"),

    /**
     * 硬停止实例.
     */
    STOP("stop"),

    /**
     * 硬重启实例.
     */
    RESET("reset"),

    /**
     * 软重启实例, 操作系统将按照正常的重启过程进行.
     */
    SOFT_RESET("softreset"),

    /**
     * 软停止实例, 操作系统将按照正常的关机过程进行.
     */
    SOFT_STOP("softstop");
}
