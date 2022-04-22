package net.lamgc.scext.oraclemanager

import com.google.gson.JsonElement
import com.oracle.bmc.core.ComputeClient
import com.oracle.bmc.core.model.Instance
import com.oracle.bmc.core.model.UpdateInstanceDetails
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
        ).items

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

    fun editServerPower(): Reply = callbackQueryOf("oc_server_power_edit") { bot, upd ->
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("开机")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    "power_action" += InstanceAction.START.actionValue
                }))
            }
            .newRow()
            .addButton {
                text("重启（软重启）")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    "power_action" += InstanceAction.SOFT_RESET.actionValue
                }))
            }
            .addButton {
                text("强制重启")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    "power_action" += InstanceAction.RESET.actionValue
                }))
            }
            .newRow()
            .addButton {
                text("关机（软关机）")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    "power_action" += InstanceAction.SOFT_STOP.actionValue
                }))
            }
            .addButton {
                text("强制关机")
                callbackData(upd.callbackQuery.callbackData.next("oc_server_power_query", jsonObjectOf {
                    "power_action" += InstanceAction.STOP.actionValue
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
