/*
 * !!! 警告 !!!
 *
 * 本代码文件内容较多, 阅览时请配合 IDE ”结构“功能
 *
 */

@file:Suppress("FunctionName")

package net.lamgc.scext.oraclemanager

import com.esotericsoftware.kryo.kryo5.Kryo
import com.esotericsoftware.kryo.kryo5.io.Input
import com.esotericsoftware.kryo.kryo5.io.Output
import com.oracle.bmc.auth.AuthenticationDetailsProvider
import com.oracle.bmc.core.BlockstorageClient
import com.oracle.bmc.core.ComputeClient
import com.oracle.bmc.core.VirtualNetworkClient
import com.oracle.bmc.core.model.*
import com.oracle.bmc.core.model.Shape.BillingType
import com.oracle.bmc.core.requests.*
import com.oracle.bmc.identity.IdentityClient
import com.oracle.bmc.identity.model.AvailabilityDomain
import com.oracle.bmc.identity.model.FaultDomain
import com.oracle.bmc.identity.requests.ListAvailabilityDomainsRequest
import com.oracle.bmc.identity.requests.ListCompartmentsRequest
import com.oracle.bmc.identity.requests.ListFaultDomainsRequest
import mu.KotlinLogging
import net.lamgc.scalabot.extension.BotExtensionFactory
import org.mapdb.Atomic.Var
import org.mapdb.DBMaker
import org.mapdb.DataInput2
import org.mapdb.DataOutput2
import org.mapdb.Serializer
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.objects.Reply
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.GetFile
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import org.telegram.telegrambots.meta.api.objects.CallbackQuery
import org.telegram.telegrambots.meta.api.objects.Message
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.regex.Pattern

class OracleCIEFactory : BotExtensionFactory {
    override fun createExtensionInstance(bot: BaseAbilityBot, shareDataFolder: File): AbilityExtension {
        return OracleCreateInstanceExtension(bot)
    }
}

@Suppress("unused")
class OracleCreateInstanceExtension(private val bot: BaseAbilityBot) : AbilityExtension {

    private val db = DBMaker
        .heapDB()
        .make()

    private fun getSessionOptionsVar(chatId: Long, userId: Long): Var<CreateInstanceOptions> =
        db.atomicVar("oc_instance_create::chat_$chatId::user_$userId::session", CreateInstanceOptionsSerializer)
            .createOrOpen()

    /**
     * 获取当前创建会话的参数集合.
     */
    private fun CallbackQuery.sessionOptions(newOptions: CreateInstanceOptions? = null): CreateInstanceOptions {
        val optionsVar = getSessionOptionsVar(message.chatId, from.id)

        if (newOptions != null) {
            optionsVar.set(newOptions)
            return newOptions
        }

        val options = optionsVar.get()
        if (options == null) {
            val newInstance = CreateInstanceOptions()
            optionsVar.set(newInstance)
            return newInstance
        }
        return options
    }

    /**
     * 获取当前创建会话的参数集合.
     */
    private fun Message.sessionOptions(newOptions: CreateInstanceOptions? = null): CreateInstanceOptions {
        val optionsVar = getSessionOptionsVar(chatId, from.id)

        if (newOptions != null) {
            optionsVar.set(newOptions)
            return newOptions
        }

        val options = optionsVar.get()
        if (options == null) {
            val newInstance = CreateInstanceOptions()
            optionsVar.set(newInstance)
            return newInstance
        }
        return options
    }

    private fun CallbackQuery.removeSessionOptions() {
        getSessionOptionsVar(message.chatId, from.id).set(null)
    }

    private fun CallbackQuery.updateSessionOptions(block: CreateInstanceOptions.() -> Unit) {
        val options = sessionOptions()
        options.apply(block)
        sessionOptions(options)
    }

    private fun Message.updateSessionOptions(block: CreateInstanceOptions.() -> Unit) {
        val options = sessionOptions()
        options.apply(block)
        sessionOptions(options)
    }

    fun test() {
        // 部分选项需要提供清除配置的操作, 如果恢复到未配置状态, Oracle 将会根据情况自动配置.

        val client = ComputeClient(null)

        client.launchInstance(
            LaunchInstanceRequest.builder()
                .launchInstanceDetails(
                    LaunchInstanceDetails.builder()
                        .compartmentId("") // 必须
                        .availabilityDomain("") // 必须
                        .faultDomain("")
                        .shape("")  // 必须
                        .isPvEncryptionInTransitEnabled(true)
                        .sourceDetails(
                            InstanceSourceViaImageDetails.builder()
                                .bootVolumeSizeInGBs(20)
                                .imageId("")
                                .build()
                        )
                        .shapeConfig(
                            LaunchInstanceShapeConfigDetails.builder()
                                .ocpus(1F)
                                .memoryInGBs(1F)
                                .build()
                        )
                        .imageId("") // 已弃用
                        .metadata(mapOf {
                            "ssh_authorized_keys" set ""
                            "user_data" set ""
                        })
                        .createVnicDetails(
                            CreateVnicDetails.builder()
                                .displayName("test")
                                .assignPublicIp(true)
                                .subnetId("")
                                .privateIp(null)
                                .build()
                        )
                        .launchOptions(
                            LaunchOptions.builder()
                                .firmware(LaunchOptions.Firmware.Uefi64)
                                .bootVolumeType(LaunchOptions.BootVolumeType.Scsi)
                                .networkType(LaunchOptions.NetworkType.Vfio)
                                .build()
                        )
                        .build()
                )
                .build()
        )
    }

    private fun <R> requestApiOrFailureMsg(
        bot: BaseAbilityBot,
        upd: Update,
        prevAction: String? = null,
        msg: String = "调用接口时发生错误.",
        prevCallback: InlineKeyboardCallback = upd.callbackQuery.callbackData.next(prevAction!!),
        requestBlock: () -> R,
    ): R? {
        try {
            return requestBlock()
        } catch (e: Exception) {
            logger.error(e) { "请求 API 发生错误." }
            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .text(
                    """
                    $msg：
                    ------------------
                    ${e.message}
                    ------------------
                    请稍后重试。
                """.trimIndent()
                )
                .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                    text("<<< 返回上一级")
                    callbackData(prevCallback)
                }.build())
                .build().execute(bot)
            return null
        }
    }

    fun createSessionMenu(): Reply = callbackQueryOf("oc_instance_create_menu") { bot, upd ->
        val adp = getProfileByCallback(upd.callbackQuery.callbackData).getAuthenticationDetailsProvider()

        val options = upd.callbackQuery.sessionOptions()
        if (options.compartmentId.isEmpty()) {
            upd.callbackQuery.updateSessionOptions {
                compartmentId = adp.tenantId
            }
        }

        if (upd.callbackQuery.callbackData.extraData["requestValidate"]?.asBoolean == true) {
            upd.callbackQuery.callbackData.extraData.addProperty("requestValidate", false)
            val lastMsgId = upd.callbackQuery.callbackData.extraData["lastMsgId"]?.asInt
            if (lastMsgId != null) {
                DeleteMessage.builder()
                    .chatId(upd.callbackQuery.message.chatId.toString())
                    .messageId(lastMsgId)
                    .build().execute(bot)
            }
            upd.callbackQuery.callbackData.extraData.addProperty("lastMsgId", upd.callbackQuery.message.messageId)
            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .text(
                    """
                        正在验证实例创建配置中...
                        （过程比较慢，请耐心等待）
                    """.trimIndent()
                )
                .build().execute(bot.silent())
            val errors = validateOptions(options, adp)
            if (errors.isEmpty()) {
                EditMessageText.builder()
                    .replyTo(upd.callbackQuery)
                    .text(
                        """
                        ------------ 正在进行实例创建配置 ------------ 
                        点击下面按钮进入相应的配置菜单，完成后点击验证配置。
                        验证通过后将可以确认创建实例。
                        ------------------------------------------
                        实例创建配置已验证成功。
                    """.trimIndent()
                    )
                    .replyMarkup(getCreateMenuKeyboard(options, upd.callbackQuery.callbackData, validated = true))
                    .build().execute(bot.silent())
            } else {
                val msg = StringBuilder("验证配置完成，已发现 ${errors.size} 个问题：\n").apply {
                    for (error in errors) {
                        append("[${error.type}] ${error.message}\n")
                    }
                }.toString()
                EditMessageText.builder()
                    .replyTo(upd.callbackQuery)
                    .text(msg)
                    .build().execute(bot.silent())
                SendMessage().apply {
                    text = """
                        ------------ 正在进行实例创建配置 ------------ 
                        点击下面按钮进入相应的配置菜单，完成后点击验证配置。
                        验证通过后将可以确认创建实例。
                    """.trimIndent()
                    chatId = upd.callbackQuery.message.chatId.toString()
                    replyMarkup = getCreateMenuKeyboard(options, upd.callbackQuery.callbackData)
                }.execute(bot)
            }
            return@callbackQueryOf
        }


        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                ------------ 正在进行实例创建配置 ------------ 
                点击下面按钮进入相应的配置菜单，完成后点击验证配置。
                验证通过后将可以确认创建实例。
            """.trimIndent()
            )
            .replyMarkup(getCreateMenuKeyboard(options, upd.callbackQuery.callbackData))
            .build().execute(bot.silent())
    }

    private fun getCreateMenuKeyboard(
        options: CreateInstanceOptions,
        callbackData: InlineKeyboardCallback,
        validated: Boolean = false,
    ): InlineKeyboardMarkup {
        val keyboardBuilder = InlineKeyboardGroupBuilder()
        keyboardBuilder.rowButton {
            // 选择可用域及容错域，没其他的操作
            val region = options.region
            if (region != null) {
                text("区域：${region.availabilityDomain.name} --> ${region.faultDomain?.name ?: "（自动选择）"}")
            } else {
                text("区域：未选择")
            }

            callbackData(callbackData.next("oc_instance_create_region"))
        }
            .rowButton {
                // 选择配置，可能是固定配置，也可能是可调整配置（比如 ARM）
                val shape = options.shape
                if (shape != null) {
                    val textBuilder = StringBuilder("规格：${shape.name}")
                    if (shape.info.isFlexible && shape.details != null) {
                        textBuilder.append("（")
                            .append(shape.details.cpuCores).append(" Core / ").append(shape.details.memories)
                            .append(" GB）")
                    } else {
                        textBuilder.append("（")
                            .append(shape.info.ocpus).append(" Core / ").append(shape.info.memoryInGBs).append(" GB）")
                    }
                    text(textBuilder.toString())
                } else {
                    text("规格：未配置")
                }
                callbackData(callbackData.next("oc_instance_create_shape"))
            }
            .rowButton {
                // 可用系统会根据配置的变化而不同，需要验证
                val source = options.source
                if (source == null) {
                    text("系统：未配置")
                } else {
                    text(
                        "系统：${
                            when (source.type) {
                                InstanceSourceType.Image -> "（系统镜像）"
                                InstanceSourceType.BootVolume -> "（现有引导卷）"
                            }
                        } ${source.name}"
                    )
                }
                callbackData(callbackData.next("oc_instance_create_source_menu"))
            }
            .rowButton {
                // 需要通过指定 VCN 和 Subnet 选择，可指定是否分配公网 IP（临时）
                // 可要求指定私有 IP，指定网络硬件类型
                // 可以指定网络安全组（NSG），这个比较麻烦，暂时不弄。
                val vnic = options.vnic
                if (vnic?.subnetInfo != null) {
                    text(
                        "网络：${vnic.subnetInfo.vcnName} -> ${vnic.subnetInfo.name}" +
                                "（分配公网 IP：${if (vnic.assignPublicIp) "是" else "否"}）"
                    )
                } else {
                    text("网络：未配置")
                }
                callbackData(callbackData.next("oc_instance_create_network_menu"))
            }
            .rowButton {
                // Cloud-Init 包括了 SSH 密钥和启动脚本（User-data）
                text(
                    "Cloud-Init：${
                        if (options.cloudInit?.userData == null && options.cloudInit?.sshKeys == null)
                            "未配置" else "已配置"
                    }"
                )
                callbackData(callbackData.next("oc_instance_create_cloudinit"))
            }
            .rowButton {
                text("高级选项")
                emptyData()
            }
            .rowButton {
                if (!validated) {
                    text("*** 验证配置信息 ***")
                    callbackData(callbackData.next("oc_instance_create_menu", jsonObjectOf {
                        "requestValidate" += true
                    }))
                } else {
                    text("***** 确认创建 *****")
                    callbackData(callbackData.next("oc_instance_create_execute"))
                }
            }
            .rowButton {
                text("<<< 取消创建过程并返回")
                callbackData(callbackData.next("oc_instance_create_abort"))
            }

        return keyboardBuilder.build()
    }

    fun executeCreate(): Reply = callbackQueryHandleOf("oc_instance_create_execute") {
        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("正在创建实例中...")
            .build().execute(bot)

        val adp = getProfileByCallback(callbackData).getAuthenticationDetailsProvider()
        val options = upd.callbackQuery.sessionOptions()
        val computeClient = ComputeClient(adp)

        val request = LaunchInstanceRequest.builder()
            .launchInstanceDetails(LaunchInstanceDetails.builder().apply {
                compartmentId(options.compartmentId)
                availabilityDomain(options.region!!.availabilityDomain.name)
                val faultDomain = options.region!!.faultDomain
                if (faultDomain != null) {
                    faultDomain(faultDomain.name)
                }
                shape(options.shape!!.info.shape)
                val shapeDetails = options.shape!!.details
                if (options.shape!!.info.isFlexible) {
                    shapeConfig(
                        LaunchInstanceShapeConfigDetails.builder()
                            .ocpus(shapeDetails!!.cpuCores)
                            .memoryInGBs(shapeDetails.memories)
                            .build()
                    )
                }
                sourceDetails(options.source!!.details)
                val networkOpt = options.vnic!!
                createVnicDetails(CreateVnicDetails.builder().apply {
                    if (networkOpt.name != null && networkOpt.name.length in (1..255)) {
                        displayName(networkOpt.name)
                    }
                    if (networkOpt.privateIp != null) {
                        privateIp(networkOpt.privateIp)
                    }
                    assignPublicIp(networkOpt.assignPublicIp)
                    subnetId(networkOpt.subnetInfo!!.id)
                }.build())
                metadata(options.cloudInit?.toMetadata() ?: emptyMap())
            }.build())
            .build()

        val launchInstanceResponse = computeClient.launchInstance(request)
        val instance = launchInstanceResponse.instance

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                实例创建成功！
                OCID：${instance.id}
                名称：${instance.displayName}
            """.trimIndent()
            )
            .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                text("<<< 返回服务器列表")
                callbackData(callbackData.next("oc_server_list"))
            }.build())
            .build().execute(bot)
    }

    fun abortCreate(): Reply = callbackQueryOf("oc_instance_create_abort") { bot, upd ->
        upd.callbackQuery.removeSessionOptions()
        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("已取消创建流程。")
            .replyMarkup(
                InlineKeyboardGroupBuilder()
                    .addBackButton(
                        callback = upd.callbackQuery.callbackData.next("oc_account_manager"),
                        message = "<<< 返回账号管理"
                    )
                    .build()
            )
            .build().execute(bot)
    }

    fun chooseRegion(): Reply = callbackQueryOf("oc_instance_create_region") { bot, upd ->
        val adp = getProfileByCallback(upd.callbackQuery.callbackData).getAuthenticationDetailsProvider()
        val availabilityDomains =
            requestApiOrFailureMsg(bot, upd, prevAction = "oc_instance_create_menu", "查询可用域信息时发生错误.") {
                IdentityClient(adp).listAvailabilityDomains(
                    ListAvailabilityDomainsRequest.builder()
                        .compartmentId(adp.tenantId)
                        .build()
                ).items.toList()
            } ?: return@callbackQueryOf

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (domain in availabilityDomains) {
            keyboardBuilder.rowButton {
                text(domain.name)
                callbackData(
                    upd.callbackQuery.callbackData.next("oc_instance_create_region::choose_fault",
                        jsonObjectOf {
                            JsonFields.AvailabilityDomainId += domain.id
                            JsonFields.AvailabilityDomainName += domain.name
                        })
                )
            }
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                请选择可用域
            """.trimIndent()
            )
            .replyMarkup(
                keyboardBuilder.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_instance_create_menu"),
                    message = "<<< 取消并返回配置菜单"
                ).build()
            )
            .build().execute(bot)
    }

    fun chooseRegion_fault(): Reply = callbackQueryOf("oc_instance_create_region::choose_fault") { bot, upd ->
        val adp = getProfileByCallback(upd.callbackQuery.callbackData).getAuthenticationDetailsProvider()
        val availabilityDomains =
            requestApiOrFailureMsg(bot, upd, prevAction = "oc_instance_create_menu", "查询容错域信息时发生错误.") {
                IdentityClient(adp).listFaultDomains(
                    ListFaultDomainsRequest.builder()
                        .compartmentId(adp.tenantId)
                        .availabilityDomain(upd.callbackQuery.callbackData.extraData[JsonFields.AvailabilityDomainName].asString)
                        .build()
                ).items.toList()
            } ?: return@callbackQueryOf

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (domain in availabilityDomains) {
            keyboardBuilder.rowButton {
                text(domain.name)
                callbackData(upd.callbackQuery.callbackData.next("oc_instance_create_region::execute",
                    jsonObjectOf {
                        JsonFields.FaultDomainId += domain.id
                        JsonFields.FaultDomainName += domain.name
                    }
                ))
            }
        }

        keyboardBuilder.rowButton {
            text("*** 自动选择 ***")
            callbackData(upd.callbackQuery.callbackData.next("oc_instance_create_region::execute"))
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                可用域 ${upd.callbackQuery.callbackData.extraData[JsonFields.AvailabilityDomainName].asString} 有以下容错域：
            """.trimIndent()
            )
            .replyMarkup(
                keyboardBuilder.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_instance_create_menu"),
                    message = "<<< 取消并返回配置菜单"
                ).build()
            )
            .build().execute(bot)
    }

    fun chooseRegion_execute(): Reply = callbackQueryOf("oc_instance_create_region::execute") { bot, upd ->
        val availabilityDomainName =
            upd.callbackQuery.callbackData.extraData[JsonFields.AvailabilityDomainName].asString
        val faultDomainName = upd.callbackQuery.callbackData.extraData[JsonFields.FaultDomainName]?.asString
        upd.callbackQuery.updateSessionOptions {
            val ad = AvailabilityDomain.builder()
                .compartmentId(compartmentId)
                .id(upd.callbackQuery.callbackData.extraData[JsonFields.AvailabilityDomainId].asString)
                .name(availabilityDomainName)
                .build()

            val faultDomain = if (upd.callbackQuery.callbackData.extraData[JsonFields.FaultDomainId] != null) {
                FaultDomain.builder()
                    .availabilityDomain(ad.id)
                    .compartmentId(compartmentId)
                    .id(upd.callbackQuery.callbackData.extraData[JsonFields.FaultDomainId].asString)
                    .name(faultDomainName!!)
                    .build()
            } else null
            region = InstanceRegionConfig(availabilityDomain = ad, faultDomain = faultDomain)
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                服务器实例区域已更新！
                可用域：$availabilityDomainName
                容错域：${faultDomainName ?: "（自动选择）"}
            """.trimIndent()
            )
            .replyMarkup(
                InlineKeyboardGroupBuilder().addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_instance_create_menu",
                        jsonObjectOf(upd.callbackQuery.callbackData.extraData) {
                            JsonFields.AvailabilityDomainId.delete()
                            JsonFields.AvailabilityDomainName.delete()
                            JsonFields.FaultDomainId.delete()
                            JsonFields.FaultDomainName.delete()
                        }), message = "<<< 返回配置菜单"
                ).build()
            )
            .build().execute(bot)
    }

    fun configShape(): Reply = callbackQueryOf("oc_instance_create_shape") { bot, upd ->
        val adp = getProfileByCallback(upd.callbackQuery.callbackData).getAuthenticationDetailsProvider()
        val options = upd.callbackQuery.sessionOptions()
        val shapesResponse = requestApiOrFailureMsg(bot, upd, "oc_instance_create_menu", "查询规格时发生错误") {
            ComputeClient(adp).listShapes(
                ListShapesRequest.builder().apply {
                    compartmentId(upd.callbackQuery.sessionOptions().compartmentId)
                    val region = options.region
                    if (region != null) {
                        availabilityDomain(region.availabilityDomain.name)
                    }
                }.build()
            )
        } ?: return@callbackQueryOf

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (shape in shapesResponse.items) {
            keyboardBuilder.rowButton {
                val text = "${shape.shape} " + (if (!shape.isFlexible) {
                    "(${shape.ocpus} / ${shape.memoryInGBs})"
                } else "（灵活）") + when (shape.billingType) {
                    BillingType.AlwaysFree -> "[A-Free]"
                    BillingType.LimitedFree -> "[L-Free]"
                    BillingType.Paid -> "[Paid]"
                    else -> "[Unknown]"
                }

                text(text)
                callbackData(upd.callbackQuery.callbackData.next("oc_instance_create_shape::confirm", jsonObjectOf {
                    JsonFields.Shape += shape
                }))
            }
        }

        // TODO: 通过 API 实现翻页不太现实, 建议首次访问即缓存, 然后通过缓存数据进行翻页查询.

        val configShape = options.shape
        val msgContent = if (configShape != null) {

            """
                -------------- 已配置的规格信息 -------------- 
                规格名称：${configShape.name}
                付费类型：${configShape.info.billingType.name}
                灵活配置：${if (configShape.details != null) "是" else "否"}
                CPU：${configShape.details?.cpuCores ?: configShape.info.ocpus}
                内存（GB）：${configShape.details?.memories ?: configShape.info.memoryInGBs}
                GPU：${configShape.info.gpus}（${configShape.info.gpuDescription}）
                网络带宽：${configShape.info.networkingBandwidthInGbps}
                ------------------------------------
                该地区（可用域）有以下可用规格：
            """.trimIndent()
        } else "该地区（可用域）有以下可用规格："

        keyboardBuilder.addBackButton(
            upd.callbackQuery.callbackData.next("oc_instance_create_menu"),
            message = "<<< 返回创建菜单"
        )
        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(msgContent)
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot)
    }

    fun configShape_confirm(): Reply = callbackQueryHandleOf("oc_instance_create_shape::confirm") {
        val shape = gson.fromJson(callbackData.extraData[JsonFields.Shape], Shape::class.java)

        val cpuText = if (!shape.isFlexible) {
            "${shape.ocpus} Core（${shape.processorDescription}）"
        } else {
            "${shape.ocpuOptions.min} ~ ${shape.ocpuOptions.max}（${shape.processorDescription}）"
        }

        val memText = if (!shape.isFlexible) {
            "${shape.memoryInGBs} GB"
        } else {
            "${shape.memoryOptions.minInGBs} ~ ${shape.memoryOptions.maxInGBs}" +
                    "（${shape.memoryOptions.minPerOcpuInGBs} --[${shape.memoryOptions.defaultPerOcpuInGBs}]-- ${shape.memoryOptions.maxPerOcpuInGBs}）"
        }

        val bandwidthText = if (!shape.isFlexible) {
            "${shape.networkingBandwidthInGbps} Gbps"
        } else {
            "${shape.networkingBandwidthOptions.minInGbps} ~ ${shape.networkingBandwidthOptions.maxInGbps}" +
                    "${shape.networkingBandwidthOptions.defaultPerOcpuInGbps} Gbps/Core"
        }

        val nextAction = if (shape.isFlexible) {
            "oc_instance_create_shape::config_flexible"
        } else {
            "oc_instance_create_shape::execute"
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                -------------- 规格信息 -------------- 
                规格名称：${shape.shape}
                付费类型：${shape.billingType.name}
                灵活配置：${if (shape.isFlexible) "是" else "否"}
                CPU：$cpuText
                内存（GB）：$memText
                GPU：${shape.gpus}（${shape.gpuDescription}）
                网络带宽：$bandwidthText
                ------------------------------------
                确定选择这个规格吗？
                （如果该规格为“灵活配置”，那么确认后将询问你所需调整的配置信息）
            """.trimIndent()
            )
            .replyMarkup(
                createPromptKeyboard(
                    yesCallback = callbackData.next(nextAction),
                    noCallback = callbackData.next("oc_instance_create_shape")
                )
            )
            .build()
            .execute(bot)
    }

    fun configShape_flexible(): Reply = callbackQueryHandleOf("oc_instance_create_shape::config_flexible") {
        val message = SendMessage.builder()
            .replyMarkup(
                ForceReplyKeyboard.builder()
                    .selective(true)
                    .forceReply(true)
                    .inputFieldPlaceholder("{CPU核心数}_{内存(GB)}")
                    .build()
            )
            .chatId(upd.callbackQuery.message.chatId.toString())
            .text("请输入本次创建所需要的 CPU 和内存使用量（格式“{CPU核心数}_{内存(GB)}”：")
            .build().execute(bot)

        bot.db()
            .getVar<String>("oc::ics::cf::c_${upd.callbackQuery.message.chatId}::u_${upd.callbackQuery.from.id}::reply")
            .set(message.messageId.toString())
        bot.db()
            .getVar<String>("oc::ics::cf::c_${upd.callbackQuery.message.chatId}::u_${upd.callbackQuery.from.id}::cbData")
            .set(callbackData.toJson())
    }

    private val flexibleConfigPattern = Pattern.compile("^(\\d+(\\.\\d+)?)_(\\d+(\\.\\d+)?)\$")

    fun configShape_flexible_input(): Reply = Reply.of({ bot, upd ->
        val matcher = flexibleConfigPattern.matcher(upd.message.text)
        val needReplyMsgId = bot.db().getVar<String>(
            "oc::ics::cf::c_${upd.message.chatId}::u_${upd.message.from.id}::reply"
        )
        if (!matcher.matches()) {
            DeleteMessage.builder()
                .chatId(upd.message.chatId.toString())
                .messageId(needReplyMsgId.get().toInt())
                .build().execute(bot)
            val msg = SendMessage.builder()
                .chatId(upd.message.chatId.toString())
                .text("错误的格式，应该为“{CPU核心数}_{内存(GB)}”，请重新回复。")
                .replyMarkup(
                    ForceReplyKeyboard.builder()
                        .selective(true)
                        .forceReply(true)
                        .inputFieldPlaceholder("{CPU核心数}_{内存(GB)}")
                        .build()
                )
                .build().execute(bot)
            needReplyMsgId.set(msg.messageId.toString())
            return@of
        }

        val callbackData = InlineKeyboardCallback.fromJson(
            bot.db().getVar<String>(
                "oc::ics::cf::c_${upd.message.chatId}::u_${upd.message.from.id}::cbData"
            )
                .get()
        )
        val selectShape = gson.fromJson(callbackData.extraData[JsonFields.Shape], Shape::class.java)

        val configDetails = InstanceShapeConfigDetails(
            cpuCores = matcher.group(1).toFloat(),
            memories = matcher.group(3).toFloat()
        )

        SendMessage.builder()
            .chatId(upd.message.chatId.toString())
            .text(
                """
                是否确认规格配置？
                ${selectShape.shape}
                CPU：${configDetails.cpuCores}
                内存：${configDetails.memories}
                
                如果不正确，可以重新发送 CPU 与内存的配置数量（格式：“{CPU核心数}_{内存(GB)}”）
                也可以取消配置。
            """.trimIndent()
            )
            .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                text("确认")
                callbackData(callbackData.next("oc_instance_create_shape::execute", jsonObjectOf {
                    JsonFields.ShapeCpus += configDetails.cpuCores
                    JsonFields.ShapeMemories += configDetails.memories
                }))
            }.rowButton {
                text("取消")
                callbackData(callbackData.next("oc_instance_create_menu"))
            }.build())
            .build().execute(bot)
    }, {
        it.hasMessage() && it.message.isReply && it.message.hasText() &&
                it.message.replyToMessage.messageId.toString() == bot.db().getVar<String>(
            "oc::ics::cf::c_${it.message.chatId}::u_${it.message.from.id}::reply"
        ).get()
    })

    fun configShape_flexible_clear(): Reply = callbackQueryHandleOf("oc_instance_create_menu") {
        bot.db().getVar<String>(
            "oc::ics::cf::c_${upd.callbackQuery.message.chatId}::u_${upd.callbackQuery.from.id}::reply"
        ).set(null)
    }

    fun configShape_execute(): Reply = callbackQueryHandleOf("oc_instance_create_shape::execute") {
        val selectShape = gson.fromJson(callbackData.extraData[JsonFields.Shape], Shape::class.java)
        val configDetails = if (callbackData.extraData.has(JsonFields.ShapeCpus) &&
            callbackData.extraData.has(JsonFields.ShapeMemories)
        ) {
            InstanceShapeConfigDetails(
                cpuCores = callbackData.extraData[JsonFields.ShapeCpus].asFloat,
                memories = callbackData.extraData[JsonFields.ShapeMemories].asFloat
            )
        } else null

        upd.callbackQuery.updateSessionOptions {
            shape = InstanceShapeConfig(
                name = selectShape.shape,
                info = selectShape,
                details = configDetails
            )
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                成功配置规格。
            """.trimIndent()
            )
            .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                text("<<< 返回配置菜单")
                callbackData(callbackData.next("oc_instance_create_menu", jsonObjectOf {
                    JsonFields.Shape.delete()
                    JsonFields.ShapeMemories.delete()
                    JsonFields.ShapeCpus.delete()
                }))
            }.build())
            .build().execute(bot)
    }

    fun cloudInit_menu(): Reply = callbackQueryHandleOf("oc_instance_create_cloudinit") {
        val cloudInitConfig = upd.callbackQuery.sessionOptions().cloudInit
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("SSH 密钥：${if (cloudInitConfig?.sshKeys != null) "已配置" else "未配置"}")
                callbackData(callbackData.next("oc_instance_create_cloudinit_sshkeys"))
            }
            .rowButton {
                text("启动脚本：${if (cloudInitConfig?.userData != null) "已配置" else "未配置"}")
                callbackData(callbackData.next("oc_instance_create_cloudinit_userdata"))
            }
            .rowButton {
                text("<<< 返回创建菜单")
                callbackData(callbackData.next("oc_instance_create_menu"))
            }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                ---------------- CloudInit ---------------- 
                SSH 密钥：${if (cloudInitConfig?.sshKeys != null) "已配置" else "未配置"}
                启动脚本：${if (cloudInitConfig?.userData != null) "已配置" else "未配置"}
                -------------------------------------------
                请选择要更改的配置项：
            """.trimIndent()
            )
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot)
    }

    fun cloudInit_sshKeys(): Reply = callbackQueryHandleOf("oc_instance_create_cloudinit_sshkeys") {
        val message = SendMessage.builder()
            .chatId(chatId.toString())
            .text("请发送需要添加的 SSH 公钥，公钥按 `ssh_authorized_keys` 的格式发送（一行一个公钥）。（如需清除，请发送“.clear”）")
            .replyMarkup(
                ForceReplyKeyboard.builder()
                    .forceReply(true)
                    .selective(true)
                    .inputFieldPlaceholder("SSH 公钥（一行一个）")
                    .build()
            )
            .build().execute(bot)

        val msgIdVar =
            bot.db().getVar<String>("oc::create_instance::cloud_init::ssh_keys::c_${chatId}::u_${user.id}::reply")
        val dataVar =
            bot.db().getVar<String>("oc::create_instance::cloud_init::ssh_keys::c_${chatId}::u_${user.id}::data")
        msgIdVar.set(message.messageId.toString())
        dataVar.set(callbackData.toJson())
    }

    private val sshPubKeyPattern = Pattern.compile("^[\\da-zA-Z-]+ [a-zA-Z\\d+/=]+( *([^ ]+))?\$")

    fun cloudInit_sshKeys_input(): Reply = Reply.of({ bot, upd ->
        val replyMsgVar = bot.db().getVar<String>(
            "oc::create_instance::cloud_init::ssh_keys::c_${upd.message.chatId}::u_${upd.message.from.id}::reply"
        )
        if (upd.message.text.trim().lowercase() == ".clear") {
            upd.message.updateSessionOptions {
                cloudInit = CloudInitConfig(
                    userData = cloudInit?.userData,
                    sshKeys = null
                )
            }
        } else {
            var checkResult = true
            for (line in upd.message.text.lines()) {
                if (!sshPubKeyPattern.matcher(line).matches()) {
                    checkResult = false
                    break
                }
            }
            if (!checkResult) {
                val message = SendMessage.builder()
                    .chatId(upd.message.chatId.toString())
                    .text(
                        """
                    输入的格式不符合，请重新回复。
                    （格式与 "ssh_authorized_keys" 文件一致，SSH 密钥一行一个）
                    （如需清除，请发送“.clear”）
                """.trimIndent()
                    )
                    .replyMarkup(
                        ForceReplyKeyboard.builder()
                            .forceReply(true)
                            .selective(true)
                            .inputFieldPlaceholder("SSH 公钥（一行一个）")
                            .build()
                    )
                    .build().execute(bot)
                replyMsgVar.set(message.messageId.toString())
                return@of
            }

            upd.message.updateSessionOptions {
                cloudInit = CloudInitConfig(
                    userData = cloudInit?.userData,
                    sshKeys = upd.message.text
                )
            }
        }
        val callbackData = InlineKeyboardCallback.fromJson(
            bot.db().getVar<String>(
                "oc::create_instance::cloud_init::ssh_keys::c_" +
                        "${upd.message.chatId}::u_${upd.message.from.id}::data"
            ).get()
        )

        replyMsgVar.set(null)
        SendMessage.builder()
            .chatId(upd.message.chatId.toString())
            .text("成功配置 SSH 密钥。")
            .replyMarkup(InlineKeyboardGroupBuilder()
                .rowButton {
                    text("<<< 返回 CloudInit 配置菜单")
                    callbackData(callbackData.next("oc_instance_create_cloudinit"))
                }
                .build())
            .build().execute(bot)
    }, {
        it.hasMessage() && it.message.isReply && it.message.hasText() &&
                it.message.replyToMessage.messageId.toString() == bot.db().getVar<String>(
            "oc::create_instance::cloud_init::ssh_keys::c_${it.message.chatId}::u_${it.message.from.id}::reply"
        ).get()
    })

    fun cloudInit_userData(): Reply = callbackQueryHandleOf("oc_instance_create_cloudinit_userdata") {
        val message = SendMessage.builder()
            .chatId(chatId.toString())
            .text("请发送脚本文件（文件以 “.sh” 结尾）")
            .replyMarkup(
                ForceReplyKeyboard.builder()
                    .selective(true)
                    .forceReply(true)
                    .build()
            )
            .build().execute(bot)

        val msgIdVar =
            bot.db().getVar<String>("oc::create_instance::cloud_init::user_data::c_${chatId}::u_${user.id}::reply")
        val dataVar =
            bot.db().getVar<String>("oc::create_instance::cloud_init::user_data::c_${chatId}::u_${user.id}::data")
        msgIdVar.set(message.messageId.toString())
        dataVar.set(callbackData.toJson())
    }

    fun cloudInit_userData_input(): Reply = Reply.of({ bot, upd ->
        val replyMsgVar = bot.db().getVar<String>(
            "oc::create_instance::cloud_init::user_data::c_${upd.message.chatId}::u_${upd.message.from.id}::reply"
        )

        if (upd.message.hasText()) {
            if (upd.message.text.trim().lowercase() == ".clear") {
                upd.message.updateSessionOptions {
                    cloudInit = CloudInitConfig(
                        userData = null,
                        sshKeys = cloudInit?.sshKeys
                    )
                }
            }
        } else if (upd.message.hasDocument()) {
            if (upd.message.document.fileSize > 10485760) {
                val msg = SendMessage.builder()
                    .chatId(upd.message.chatId.toString())
                    .text("脚本文件过大，请限制文件大小为 10 MB 以内（文件名以 “.sh” 结尾），或者发送 .clear 清除脚本。")
                    .replyMarkup(
                        ForceReplyKeyboard.builder()
                            .selective(true)
                            .forceReply(true)
                            .build()
                    )
                    .build().execute(bot)
                replyMsgVar.set(msg.messageId.toString())
                return@of
            }

            val file = bot.downloadFile(GetFile(upd.message.document.fileId).execute(bot))
            Base64.getEncoder().encodeToString(file.readBytes())
            upd.message.updateSessionOptions {
                cloudInit = CloudInitConfig(
                    userData = Base64.getEncoder().encodeToString(file.readBytes()),
                    sshKeys = cloudInit?.sshKeys
                )
            }
        } else {
            val msg = SendMessage.builder()
                .chatId(upd.message.chatId.toString())
                .text("错误的消息，请发送脚本文件（文件名以 “.sh” 结尾），或者发送 .clear 清除脚本。")
                .replyMarkup(
                    ForceReplyKeyboard.builder()
                        .selective(true)
                        .forceReply(true)
                        .build()
                )
                .build().execute(bot)
            replyMsgVar.set(msg.messageId.toString())
            return@of
        }

        val callbackData = InlineKeyboardCallback.fromJson(
            bot.db().getVar<String>(
                "oc::create_instance::cloud_init::user_data::c_" +
                        "${upd.message.chatId}::u_${upd.message.from.id}::data"
            ).get()
        )

        replyMsgVar.set(null)
        SendMessage.builder()
            .chatId(upd.message.chatId.toString())
            .text("成功配置启动脚本。")
            .replyMarkup(InlineKeyboardGroupBuilder()
                .rowButton {
                    text("<<< 返回 CloudInit 配置菜单")
                    callbackData(callbackData.next("oc_instance_create_cloudinit"))
                }
                .build())
            .build().execute(bot)
        replyMsgVar.set(null)
    }, {
        it.hasMessage() && it.message.isReply && (
                (it.message.hasDocument() && it.message.document.fileName.endsWith(".sh", true))
                        || it.message.hasText())
                &&
                it.message.replyToMessage.messageId.toString() == bot.db().getVar<String>(
            "oc::create_instance::cloud_init::user_data::c_${it.message.chatId}::u_${it.message.from.id}::reply"
        ).get()
    })

    fun source_menu(): Reply = callbackQueryHandleOf("oc_instance_create_source_menu") {
        val keyboardBuilder = InlineKeyboardGroupBuilder()

        keyboardBuilder.rowButton {
            text("选择系统镜像")
            callbackData(callbackData.next("oc_instance_create_source_image"))
        }.rowButton {
            text("选择现有的引导卷")
            callbackData(callbackData.next("oc_instance_create_source_bootvolume"))
        }.rowButton {
            text("<<< 返回创建菜单")
            callbackData(callbackData.next("oc_instance_create_menu"))
        }

        val options = upd.callbackQuery.sessionOptions().source
        val msg = if (options == null) {
            """
                ----------------- 系统 -----------------
                尚未配置任何系统来源。
                请选择系统来源类型：
            """.trimIndent()
        } else {
            """
                ----------------- 系统 -----------------
                
            """.trimIndent()
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .replyMarkup(keyboardBuilder.build())
            .text(msg)
            .build().execute(bot)
    }

    fun source_bootvolume_selector(): Reply = callbackQueryHandleOf("oc_instance_create_source_bootvolume") {
        val adp = getProfileByCallback(callbackData).getAuthenticationDetailsProvider()
        val options = upd.callbackQuery.sessionOptions()
        val storageClient = BlockstorageClient(adp)
        val computeClient = ComputeClient(adp)

        if (options.region == null) {
            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .text("请先选择实例所在区。")
                .replyMarkup(
                    InlineKeyboardGroupBuilder()
                        .addBackButton(callbackData.next("oc_instance_create_menu"))
                        .build()
                )
                .build().execute(bot)
            return@callbackQueryHandleOf
        }

        val bootVolumeResp = requestApiOrFailureMsg(bot, upd, "oc_instance_create_source_menu") {
            storageClient.listBootVolumes(ListBootVolumesRequest.builder()
                .apply {
                    compartmentId(options.compartmentId)
                    availabilityDomain(options.region!!.availabilityDomain.name)
                    limit(100)
                }
                .build())
        } ?: return@callbackQueryHandleOf

        val keyboardBuilder = InlineKeyboardGroupBuilder()

        val availableVolumes = bootVolumeResp.items.filter {
            logger.debug { "卷 ${it.displayName} 状态: ${it.lifecycleState}" }
            if (it.lifecycleState == BootVolume.LifecycleState.Available) {
                val bootVolumeAttachments = computeClient.listBootVolumeAttachments(
                    ListBootVolumeAttachmentsRequest.builder()
                        .compartmentId(options.compartmentId)
                        .bootVolumeId(it.id)
                        .availabilityDomain(it.availabilityDomain)
                        .build()
                ).items
                logger.debug { "卷 ${it.displayName} 已经被 ${bootVolumeAttachments.size} 个实例使用." }
                bootVolumeAttachments.isEmpty()
            } else {
                false
            }
        }

        if (availableVolumes.isEmpty()) {
            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .text("没有可用的引导卷。")
                .replyMarkup(
                    keyboardBuilder
                        .addBackButton(callbackData.next("oc_instance_create_source_menu"))
                        .build()
                )
                .build().execute(bot)
            return@callbackQueryHandleOf
        }

        for (bootVolume in availableVolumes) {
            keyboardBuilder.rowButton {
                text("${bootVolume.displayName}（${bootVolume.sizeInGBs} GB）")
                callbackData(callbackData.next("oc_instance_create_source_bootvolume:execute", jsonObjectOf {
                    JsonFields.BootVolume += bootVolume
                }))
            }
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("请选择一个引导卷：")
            .replyMarkup(
                keyboardBuilder
                    .addBackButton(callbackData.next("oc_instance_create_source_menu"))
                    .build()
            )
            .build().execute(bot)
    }

    fun source_bootvolume_execute(): Reply = callbackQueryHandleOf("oc_instance_create_source_bootvolume:execute") {
        val targetBootVolume = gson.fromJson(callbackData.extraData[JsonFields.BootVolume], BootVolume::class.java)
        upd.callbackQuery.updateSessionOptions {
            source = InstanceSourceConfig(
                name = targetBootVolume.displayName,
                type = InstanceSourceType.BootVolume,
                details = InstanceSourceViaBootVolumeDetails.builder()
                    .bootVolumeId(targetBootVolume.id)
                    .build()
            )
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                已选择现有引导卷作为实例启动源。
                ---------------------------------
                名称：${targetBootVolume.displayName}
                容量：${targetBootVolume.sizeInGBs} GB
                状态：${targetBootVolume.lifecycleState}
                可用域：${targetBootVolume.availabilityDomain}
            """.trimIndent()
            )
            .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                text("<<< 返回创建菜单")
                callbackData(callbackData.next("oc_instance_create_menu", jsonObjectOf(callbackData.extraData) {
                    JsonFields.BootVolume.delete()
                }, replaceData = true))
            }.build())
            .build().execute(bot)
    }

    fun source_image_selector(): Reply = callbackQueryHandleOf("oc_instance_create_source_image") {
        val adp = getProfileByCallback(callbackData).getAuthenticationDetailsProvider()
        val options = upd.callbackQuery.sessionOptions()
        val client = ComputeClient(adp)

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        val shapeConfig = options.shape
        val msg = if (shapeConfig != null) {
            val imagesResp = requestApiOrFailureMsg(bot, upd, "oc_instance_create_source_menu") {
                client.listImages(ListImagesRequest.builder()
                    .apply {
                        compartmentId(options.compartmentId)
                        shape(shapeConfig.name)
                        lifecycleState(Image.LifecycleState.Available)
                    }
                    .build())
            } ?: return@callbackQueryHandleOf

            for (image in imagesResp.items) {
                keyboardBuilder.rowButton {
                    text("${image.displayName} [${image.operatingSystem} - ${image.operatingSystemVersion}]")
                    callbackData(callbackData.next("oc_instance_create_source_image::execute", jsonObjectOf {
                        JsonFields.ImageId += image.id
                        JsonFields.ImageDisplayName += image.displayName
                    }))
                }
            }

            """
                已查找到以下可用的系统镜像：
                （查找条件包括选定的地区和实例规格，通过调整规格可以查找不同规格可用的镜像）
            """.trimIndent()
        } else {
            """
                请先选择实例规格后再选择镜像。
            """.trimIndent()
        }

        keyboardBuilder.rowButton {
            text("<<< 返回系统配置菜单")
            callbackData(callbackData.next("oc_instance_create_source_menu"))
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(msg)
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot)
    }

    fun source_image_execute(): Reply = callbackQueryHandleOf("oc_instance_create_source_image::execute") {
        val imageId = callbackData.extraData[JsonFields.ImageId].asString
        val imageName = callbackData.extraData[JsonFields.ImageDisplayName].asString
        upd.callbackQuery.updateSessionOptions {
            source = InstanceSourceConfig(
                name = imageName,
                type = InstanceSourceType.Image,
                details = InstanceSourceViaImageDetails.builder()
                    .imageId(imageId)
                    .build()
            )
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                已选择现有镜像作为实例启动源。
                ---------------------------------
                名称：${imageName}
            """.trimIndent()
            )
            .replyMarkup(InlineKeyboardGroupBuilder().rowButton {
                text("<<< 返回创建菜单")
                callbackData(callbackData.next("oc_instance_create_menu", jsonObjectOf(callbackData.extraData) {
                    JsonFields.ImageId.delete()
                }, replaceData = true))
            }.build())
            .build().execute(bot)
    }

    fun network_menu(): Reply = callbackQueryHandleOf("oc_instance_create_network_menu") {
        val keyboardBuilder = InlineKeyboardGroupBuilder()
        if (upd.callbackQuery.sessionOptions().vnic == null) {
            upd.callbackQuery.updateSessionOptions {
                vnic = InstanceCreateVnicConfig()
            }
        }

        if (callbackData.extraData["SwitchPublicIp"]?.asBoolean == true) {
            callbackData.extraData.remove("SwitchPublicIp")
            upd.callbackQuery.updateSessionOptions {
                vnic = vnic!!.copy(assignPublicIp = !vnic!!.assignPublicIp)
            }
        }

        val networkOpt = upd.callbackQuery.sessionOptions().vnic!!
        keyboardBuilder.rowButton {
            text("名称：${networkOpt.name ?: "（自动创建）"}")
            callbackData(callbackData.next("oc_instance_create_network_name"))
        }.rowButton {
            val subnetInfo = networkOpt.subnetInfo
            if (subnetInfo == null) {
                text("子网：（未指定）")
            } else {
                text("子网：${subnetInfo.name}（属于 ${subnetInfo.vcnName}）")
            }
            callbackData(callbackData.next("oc_instance_create_network_subnet::vcn_select"))
        }.rowButton {
            text("公网 IPv4：${if (networkOpt.assignPublicIp) "分配" else "不分配"}")
            callbackData(callbackData.next("oc_instance_create_network_menu", jsonObjectOf {
                "SwitchPublicIp" += true
            }))
        }.rowButton {
            text("私有 IPv4：${networkOpt.privateIp ?: "（自动分配）"}")
            callbackData(callbackData.next("oc_instance_create_network_private_ip"))
        }.rowButton {
            text("<<< 返回到创建菜单")
            callbackData(callbackData.next("oc_instance_create_menu"))
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text(
                """
                正在编辑新实例所使用的网络配置。
            """.trimIndent()
            )
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot)
    }

    fun network_change_subnet_list(): Reply = callbackQueryHandleOf("oc_instance_create_network_subnet::vcn_select") {
        val adp = getProfileByCallback(callbackData).getAuthenticationDetailsProvider()
        val options = upd.callbackQuery.sessionOptions()
        val client = VirtualNetworkClient(adp)

        val listVcnResponse = requestApiOrFailureMsg(bot, upd, prevAction = "oc_instance_create_network_menu") {
            client.listVcns(
                ListVcnsRequest.builder()
                    .compartmentId(options.compartmentId)
                    .build()
            )
        } ?: return@callbackQueryHandleOf

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        val vcns = listVcnResponse.items
        for (vcn in vcns) {
            keyboardBuilder.rowButton {
                text(vcn.displayName)
                callbackData(callbackData.next("oc_instance_create_network_subnet::subnet_select", jsonObjectOf {
                    JsonFields.VcnId += vcn.id
                    JsonFields.VcnDisplayName += vcn.displayName
                }))
            }
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("请选择新实例所加入的 VCN：")
            .replyMarkup(
                keyboardBuilder
                    .addBackButton(callbackData.next("oc_instance_create_network_menu"))
                    .build()
            )
            .build().execute(bot)
    }

    fun network_change_subnet_select_subnet(): Reply =
        callbackQueryHandleOf("oc_instance_create_network_subnet::subnet_select") {
            val adp = getProfileByCallback(callbackData).getAuthenticationDetailsProvider()
            val options = upd.callbackQuery.sessionOptions()
            val client = VirtualNetworkClient(adp)
            val vcnName = callbackData.extraData[JsonFields.VcnDisplayName].asString

            val listSubnetResponse = requestApiOrFailureMsg(bot, upd, prevAction = "oc_instance_create_network_menu") {
                client.listSubnets(
                    ListSubnetsRequest.builder()
                        .compartmentId(options.compartmentId)
                        .vcnId(callbackData.extraData[JsonFields.VcnId].asString)
                        .build()
                )
            } ?: return@callbackQueryHandleOf

            val keyboardBuilder = InlineKeyboardGroupBuilder()
            for (subnet in listSubnetResponse.items) {
                keyboardBuilder.rowButton {
                    text("${subnet.displayName}（${subnet.cidrBlock}）")
                    callbackData(callbackData.next("oc_instance_create_network_subnet::confirm_subnet", jsonObjectOf {
                        JsonFields.SubnetId += subnet.id
                        JsonFields.SubnetDisplayName += subnet.displayName
                    }))
                }
            }

            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .replyMarkup(
                    keyboardBuilder
                        .addBackButton(callbackData.next("oc_instance_create_network_subnet::vcn_select"))
                        .build()
                )
                .text(
                    """
                VCN $vcnName 有以下子网，请选择实例加入的子网：
            """.trimIndent()
                )
                .build().execute(bot)
        }

    fun network_change_subnet_confirm_subnet(): Reply =
        callbackQueryHandleOf("oc_instance_create_network_subnet::confirm_subnet") {
            val subnet = SubnetInfo(
                id = callbackData.extraData[JsonFields.SubnetId].asString,
                name = callbackData.extraData[JsonFields.SubnetDisplayName].asString,
                vcnId = callbackData.extraData[JsonFields.VcnId].asString,
                vcnName = callbackData.extraData[JsonFields.VcnDisplayName].asString
            )
            upd.callbackQuery.updateSessionOptions {
                vnic = vnic?.copy(subnetInfo = subnet) ?: InstanceCreateVnicConfig(subnetInfo = subnet)
            }

            EditMessageText.builder()
                .replyTo(upd.callbackQuery)
                .text(
                    """
                已成功配置, 新实例将加入网络：
                ${subnet.vcnName} -> ${subnet.name}
            """.trimIndent()
                )
                .replyMarkup(
                    InlineKeyboardGroupBuilder()
                        .addBackButton(callbackData.next("oc_instance_create_network_menu"))
                        .build()
                )
                .build().execute(bot)
        }


    fun network_change_vnic_name(): Reply = callbackQueryHandleOf("oc_instance_create_network_name") {
        val message = SendMessage.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .text("请发送新的 Vnic 名称。")
            .replyMarkup(
                ForceReplyKeyboard.builder()
                    .forceReply(true)
                    .inputFieldPlaceholder("新的 Vnic 名称.")
                    .build()
            )
            .build().execute(bot)

    }

    fun network_change_vnic_name_confirm(): Reply = Reply.of({ bot, upd ->

    },
        { it.hasMessage() && it.message.isReply }
    )

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}

private val logger = KotlinLogging.logger { }

fun validateOptions(options: CreateInstanceOptions, adp: AuthenticationDetailsProvider): List<ErrorOfOptionsDetails> {
    val errors = mutableListOf<ErrorOfOptionsDetails>()
    logger.debug { "Options: $options" }
    val identityClient = IdentityClient(adp)
    // 由于根区间无法获取, 就只能假定是成功的了.
    if (options.compartmentId != adp.tenantId) {
        val compartment = identityClient.listCompartments(
            ListCompartmentsRequest.builder()
                .compartmentId(adp.tenantId)
                .accessLevel(ListCompartmentsRequest.AccessLevel.Accessible)
                .build()
        ).items.find {
            logger.debug { it }
            it.id == options.compartmentId
        }
        // 如果区间 Id 已经出错了, 那么后续的检查就没有意义了.
        if (compartment == null) {
            errors.add(ErrorOfOptionsDetails("compartment", "The specified compartment cannot be found."))
            return errors.toList()
        }
    }

    val region = options.region
    if (region == null) {
        errors.add(ErrorOfOptionsDetails("region", "region not set."))
        return errors.toList()
    }

    val availabilityDomain = identityClient.listAvailabilityDomains(
        ListAvailabilityDomainsRequest.builder()
            .compartmentId(options.compartmentId)
            .build()
    ).items.find { it == region.availabilityDomain }
    if (availabilityDomain == null) {
        errors.add(ErrorOfOptionsDetails("availabilityDomain", "AvailableDomain does not exist."))
        return errors.toList()
    }

    if (region.faultDomain != null) {
        identityClient.listFaultDomains(
            ListFaultDomainsRequest.builder()
                .compartmentId(options.compartmentId)
                .availabilityDomain(region.availabilityDomain.name)
                .build()
        ).items.find { it.id == region.faultDomain.id }
            ?: errors.add(ErrorOfOptionsDetails("faultDomain", "FaultDomain does not exist."))
    }

    val computeClient = ComputeClient(adp)

    val shape = options.shape
    if (shape == null) {
        errors.add(ErrorOfOptionsDetails("shape", "shape not set."))
        return errors.toList()
    }

    val shapeInfo = computeClient.listShapes(
        ListShapesRequest.builder()
            .compartmentId(options.compartmentId)
            .availabilityDomain(region.availabilityDomain.name)
            .build()
    ).items.find { it == shape.info }
    if (shapeInfo == null) {
        errors.add(ErrorOfOptionsDetails("shape", "shape does not exist."))
        return errors.toList()
    }

    if (shapeInfo.isFlexible) {
        if (shape.details == null) {
            errors.add(ErrorOfOptionsDetails("shape", "The shape is flexible, but no detailed configuration is set."))
        } else {
            if (shape.details.cpuCores !in (shapeInfo.ocpuOptions.min..shapeInfo.ocpuOptions.max)) {
                errors.add(ErrorOfOptionsDetails("shape", "The specified number of CPUs exceeds the shape limit."))
            }

            if (shape.details.memories !in (shapeInfo.memoryOptions.minInGBs..shapeInfo.memoryOptions.maxInGBs)) {
                errors.add(ErrorOfOptionsDetails("shape", "The specified number of memories exceeds the shape limit."))
            } else {
                val memoriesPreCpu = shape.details.memories / shape.details.cpuCores
                if (memoriesPreCpu !in (shapeInfo.memoryOptions.minPerOcpuInGBs..shapeInfo.memoryOptions.maxPerOcpuInGBs)) {
                    errors.add(
                        ErrorOfOptionsDetails(
                            "shape",
                            "The specified amount of memory does not meet the specification constraints (available memory per CPU)."
                        )
                    )
                }
            }
        }
    }

    val source = options.source
    if (source == null) {
        errors.add(ErrorOfOptionsDetails("source", "source not set."))
        return errors.toList()
    }

    when (source.type) {
        InstanceSourceType.Image -> {
            computeClient.listImages(
                ListImagesRequest.builder()
                    .compartmentId(options.compartmentId)
                    .shape(shape.info.shape)
                    .lifecycleState(Image.LifecycleState.Available)
                    .build()
            ).items.find { it.id == (source.details as InstanceSourceViaImageDetails).imageId }
                ?: errors.add(ErrorOfOptionsDetails("source", "The specified image does not exist."))
        }

        InstanceSourceType.BootVolume -> {
            val blockstorageClient = BlockstorageClient(adp)

            val bootVolume = blockstorageClient.listBootVolumes(
                ListBootVolumesRequest.builder()
                    .compartmentId(options.compartmentId)
                    .availabilityDomain(region.availabilityDomain.name)
                    .build()
            ).items.find { it.id == (source.details as InstanceSourceViaBootVolumeDetails).bootVolumeId }
            if (bootVolume == null) {
                errors.add(ErrorOfOptionsDetails("source", "The specified boot volume does not exist."))
            } else {
                val hasAttachments = computeClient.listBootVolumeAttachments(
                    ListBootVolumeAttachmentsRequest.builder()
                        .compartmentId(options.compartmentId)
                        .availabilityDomain(region.availabilityDomain.name)
                        .bootVolumeId(bootVolume.id)
                        .build()
                ).items.isNotEmpty()
                if (hasAttachments) {
                    errors.add(
                        ErrorOfOptionsDetails(
                            "source",
                            "The specified boot volume has been mounted by another instance."
                        )
                    )
                }
            }
        }
    }

    val vnic = options.vnic
    if (vnic == null) {
        errors.add(ErrorOfOptionsDetails("vnic", "vnic not set."))
        return errors.toList()
    }

    val ipv4Validator = Pattern.compile("^((25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\\.){3}(25[0-5]|(2[0-4]|1\\d|[1-9]|)\\d)\$")
    if (vnic.privateIp != null && !ipv4Validator.matcher(vnic.privateIp).matches()) {
        errors.add(
            ErrorOfOptionsDetails(
                "vnic",
                "The specified private IP does not conform to the standard IPv4 format."
            )
        )
    }

    val subnetInfo = vnic.subnetInfo
    if (subnetInfo == null) {
        errors.add(ErrorOfOptionsDetails("vnic", "Subnet to which vnic belongs is not specified."))
    } else {
        val networkClient = VirtualNetworkClient(adp)
        val vcn = networkClient.listVcns(
            ListVcnsRequest.builder()
                .compartmentId(options.compartmentId)
                .lifecycleState(Vcn.LifecycleState.Available)
                .build()
        ).items.find { it.id == subnetInfo.vcnId }

        if (vcn == null) {
            errors.add(ErrorOfOptionsDetails("vnic", "The specified VCN does not exist."))
        } else {
            networkClient.listSubnets(
                ListSubnetsRequest.builder()
                    .compartmentId(options.compartmentId)
                    .vcnId(subnetInfo.vcnId)
                    .lifecycleState(Subnet.LifecycleState.Available)
                    .build()
            ).items.find { it.id == subnetInfo.id }
                ?: errors.add(ErrorOfOptionsDetails("vnic", "The specified Subnet does not exist."))
        }
    }
    return errors.toList()
}

data class ErrorOfOptionsDetails(
    val type: String,
    val message: String,
)

data class CreateInstanceOptions(
    var compartmentId: String = "",
    var region: InstanceRegionConfig? = null,
    var shape: InstanceShapeConfig? = null,
    var source: InstanceSourceConfig? = null,
    var vnic: InstanceCreateVnicConfig? = null,
    var cloudInit: CloudInitConfig? = null,
)

private object CreateInstanceOptionsSerializer : Serializer<CreateInstanceOptions> {

    private val kryoLocal = ThreadLocal<Kryo>()

    private val kryo: Kryo
        get() {
            val kryoLocalInstance: Kryo? = kryoLocal.get()
            if (kryoLocalInstance == null) {
                val kryoInstance = Kryo()
                kryoInstance.register(CreateInstanceOptions::class.java)
                kryoLocal.set(kryoInstance)
                return kryoInstance
            }
            return kryoLocalInstance
        }

    override fun serialize(out: DataOutput2, value: CreateInstanceOptions) {
        val buffer = ByteArrayOutputStream()
        kryo.writeObject(Output(buffer), value)
        out.writeBytes(buffer.toByteArray().toString(StandardCharsets.UTF_8))
    }

    override fun deserialize(input: DataInput2, available: Int): CreateInstanceOptions {
        val buffer = ByteArrayInputStream(input.readUTF().toByteArray(StandardCharsets.UTF_8))
        return kryo.readObject(Input(buffer), CreateInstanceOptions::class.java)
    }
}

/**
 * 实例地区配置, 包括可用域和容错域.
 */
data class InstanceRegionConfig(
    val availabilityDomain: AvailabilityDomain,
    val faultDomain: FaultDomain? = null,
)

/**
 * 实例配置参数.
 * @property details 当 Shape 的 isFlexible 为 true 时, 必须配置该参数, 如果为 false, 则配置了也没用.
 */
data class InstanceShapeConfig(
    val name: String,
    val info: Shape,
    val details: InstanceShapeConfigDetails? = null,
)

/**
 * 实例规格详细配置.
 *
 * @property cpuCores CPU 核心数.
 * @property memories 所需内存量, 单位 GB.
 */
data class InstanceShapeConfigDetails(
    val cpuCores: Float,
    val memories: Float,
)

enum class InstanceSourceType {
    BootVolume,
    Image
}

/**
 * 实例启动介质配置.
 */
data class InstanceSourceConfig(
    val name: String,
    val type: InstanceSourceType,
    val details: InstanceSourceDetails,
)

data class InstanceCreateVnicConfig(
    val name: String? = null,
    val subnetInfo: SubnetInfo? = null,
    val privateIp: String? = null,
    val assignPublicIp: Boolean = true,
)

data class SubnetInfo(
    val id: String,
    val name: String,
    val vcnId: String,
    val vcnName: String,
)

data class CloudInitConfig(
    val userData: String? = null,
    val sshKeys: String? = null,
) {
    fun toMetadata(): Map<String, String> = mapOf {
        if (sshKeys != null) {
            "ssh_authorized_keys" set sshKeys
        }
        if (userData != null) {
            "user_data" set Base64.getEncoder().encodeToString(userData.toByteArray(StandardCharsets.UTF_8))
        }
    }
}
