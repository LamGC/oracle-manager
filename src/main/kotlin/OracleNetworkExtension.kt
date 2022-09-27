package net.lamgc.scext.oraclemanager

import com.oracle.bmc.core.VirtualNetworkClient
import com.oracle.bmc.core.model.CreatePublicIpDetails
import com.oracle.bmc.core.model.CreateVcnDetails
import com.oracle.bmc.core.model.Vcn
import com.oracle.bmc.core.requests.*
import com.oracle.bmc.model.BmcException
import mu.KotlinLogging
import net.lamgc.scalabot.extension.BotExtensionFactory
import org.telegram.abilitybots.api.bot.BaseAbilityBot
import org.telegram.abilitybots.api.objects.Reply
import org.telegram.abilitybots.api.util.AbilityExtension
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText
import java.io.File

class OracleNetworkExtensionFactory : BotExtensionFactory {
    override fun createExtensionInstance(bot: BaseAbilityBot, shareDataFolder: File): AbilityExtension {
        return OracleNetworkExtension(bot)
    }
}


@Suppress("unused")
class OracleNetworkExtension(private val bot: BaseAbilityBot) : AbilityExtension {

    fun networkMenu(): Reply = callbackQueryOf("oc_network_menu") { bot, upd ->
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("VCN 列表")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_list"))
            }
            .newRow()
            .addButton {
                text("IPv4 管理")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_ipv4pub_manage"))
            }
            .addButton {
                text("IPv6 管理")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_ipv4pub_manage"))
            }
            .then()
            .addBackButton(upd.callbackQuery.callbackData.next("oc_account_manager"))
        EditMessageReplyMarkup.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .build()
            .execute(bot.silent())
    }


    fun listVCNs(): Reply = callbackQueryOf("oc_network_vcn_list") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())

        val vcns = client.listVcns(
            ListVcnsRequest.builder()
                .compartmentId(profile.tenantId)
                .build()
        ).items
        vcns.removeIf { it.lifecycleState == Vcn.LifecycleState.Terminated }
        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (vcn in vcns) {
            logger.debug { "VCN: $vcn" }
            keyboardBuilder.rowButton {
                text(vcn.displayName)
                callbackData(upd.callbackQuery.callbackData.next(
                    newAction = "oc_network_vcn_manage",
                    newExtraData = jsonObjectOf {
                        JsonFields.VcnId += vcn.id
                        JsonFields.VcnDisplayName += vcn.displayName
                    }
                ))
            }
        }

        keyboardBuilder.rowButton {
            text("*** 新建 VCN ***")
            callbackData(
                upd.callbackQuery.callbackData.next(
                    newAction = "oc_network_vcn_create"
                )
            )
        }

        keyboardBuilder.addBackButton(upd.callbackQuery.callbackData.next("oc_account_manager"))
        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(keyboardBuilder.build())
            .text(
                """
                Oracle 账号 ${profile.name} 有以下 VCN：
            """.trimIndent()
            )
            .build()
            .execute(bot.sender())
    }

    fun createVcn(): Reply = callbackQueryHandleOf("oc_network_vcn_create") {
        val profile = getProfileByCallback(callbackData)
        val createVcnRequest = CreateVcnRequest.builder()
            .createVcnDetails(
                CreateVcnDetails.builder()
                    .compartmentId(profile.tenantId)
                    .build()
            )
            .build()

        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        // 使用 try 代码块包括 client 请求
        try {
            val vcn = client.createVcn(createVcnRequest).vcn
            EditMessageText.builder()
                .chatId(chatId)
                .messageId(upd.callbackQuery.message.messageId)
                .text("创建 VCN ${vcn.displayName} 成功！")
                .replyMarkup(
                    InlineKeyboardGroupBuilder().addBackButton(callbackData.next("oc_network_vcn_list")).build()
                )
                .build()
                .execute(bot.sender())
        } catch (e: BmcException) {
            EditMessageText.builder()
                .chatId(chatId)
                .messageId(upd.callbackQuery.message.messageId)
                .text("创建 VCN 失败！\n错误信息：${e.message}")
                .replyMarkup(
                    InlineKeyboardGroupBuilder().addBackButton(callbackData.next("oc_network_vcn_list")).build()
                )
                .build()
                .execute(bot.sender())
        }

    }

    fun manageVcn(): Reply = callbackQueryOf("oc_network_vcn_manage") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        val vcnId = upd.callbackQuery.callbackData.extraData[JsonFields.VcnId].asString
        val vcn = try {
            client.getVcn(
                GetVcnRequest.builder()
                    .vcnId(vcnId)
                    .build()
            ).vcn
        } catch (e: Exception) {
            logger.error(e) { "获取 VCN 信息时发生错误." }
            bot.silent().send("获取 VCN 信息时发生错误，请重试一次。", upd.callbackQuery.message.chatId)
            return@callbackQueryOf
        }

        val keyboardGroup = InlineKeyboardGroupBuilder()
            .rowButton {
                text("子网列表")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_subnet_list"))
            }
            .newRow()
            .addButton {
                text("网络安全组")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_nsg_manage"))
            }
            .addButton {
                text("安全列表")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_sl_manage"))
            }
            .then()
            .rowButton {
                text("设置")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_edit"))
            }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardGroup.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_network_vcn_list")
                ).build()
            )
            .text(
                """
                --------- [ VCN ] ---------
                ${vcn.displayName}
                状态：${vcn.lifecycleState}
                私有 IPv4 段：${vcn.cidrBlock}（共 ${vcn.cidrBlocks.size} 段）
                IPv6 段：${vcn.ipv6CidrBlocks.firstOrNull()}（共 ${vcn.ipv6CidrBlocks.size} 段）
            """.trimIndent()
            )
            .build().execute(bot)
    }

    fun listSubnet(): Reply = callbackQueryOf("oc_network_vcn_subnet_list") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        val vcnId = upd.callbackQuery.callbackData.extraData[JsonFields.VcnId].asString
        val vcnDisplayName = upd.callbackQuery.callbackData.extraData[JsonFields.VcnDisplayName].asString
        val subnets = client.listSubnets(
            ListSubnetsRequest.builder()
                .compartmentId(profile.tenantId)
                .vcnId(vcnId)
                .build()
        ).items

        val keyboardBuilder = InlineKeyboardGroupBuilder()

        for (subnet in subnets) {
            keyboardBuilder.rowButton {
                text("${subnet.displayName}【${subnet.cidrBlock}】")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_subnet_edit",
                    newExtraData = jsonObjectOf {
                        JsonFields.SubnetId += subnet.id
                    }
                ))
            }
        }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardBuilder.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_network_vcn_manage")
                ).build()
            )
            .text("VCN $vcnDisplayName 有以下子网（Subnet)：")
            .build().execute(bot)
    }

    fun editSubnet(): Reply = callbackQueryOf("oc_network_vcn_subnet_edit") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        val subnetId = upd.callbackQuery.callbackData.extraData[JsonFields.SubnetId].asString
        val subnet = client.getSubnet(GetSubnetRequest.builder().subnetId(subnetId).build()).subnet

        val keyboardGroup = InlineKeyboardGroupBuilder()
            .newRow()
            .addButton {
                text("私网 IPv4")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_subnet_ipv4pri_manage"))
            }
            .then()
            .rowButton {
                text("IPv6")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_subnet_ipv6_manage"))
            }
            .rowButton {
                text("绑定网络安全组")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_subnet_bind_nsg"))
            }
            .rowButton {
                text("设置")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_subnet_edit"))
            }

        EditMessageText.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardGroup.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_network_vcn_subnet_list")
                ).build()
            )
            .text(
                """
                ${subnet.displayName} 
                私有 IPv4 段：${subnet.cidrBlock}
                IPv6 段：${subnet.ipv6CidrBlock}
                已激活安全列表数：${subnet.securityListIds.size}
                所属 VCN 名称：${upd.callbackQuery.callbackData.extraData[JsonFields.VcnDisplayName].asString}
            """.trimIndent()
            )
            .build().execute(bot)

    }

    fun listSubnetPublicIpv4(): Reply = callbackQueryOf("oc_network_ipv4pub_manage") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        val publicIps = client.listPublicIps(
            ListPublicIpsRequest.builder()
                .compartmentId(profile.tenantId)
                .scope(ListPublicIpsRequest.Scope.Region)
                .build()
        ).items

        val keyboardBuilder = InlineKeyboardGroupBuilder()
        for (publicIp in publicIps) {
            keyboardBuilder.rowButton {
                text("${publicIp.displayName}【${publicIp.ipAddress}】")
                callbackData(
                    upd.callbackQuery.callbackData.next("oc_network_vcn_ipv4pub_edit",
                        newExtraData = jsonObjectOf {
                            JsonFields.PublicIpId += publicIp.id
                        })
                )
            }
        }

        keyboardBuilder.rowButton {
            text("*** 新建公网 IP ***")
            callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_ipv4pub_add"))
        }.addBackButton(
            callback = upd.callbackQuery.callbackData.next("oc_network_vcn_subnet_edit")
        )

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("Oracle账号 ${profile.name} 当前区域有以下预留的公共 IP：")
            .replyMarkup(keyboardBuilder.build())
            .build().execute(bot)
    }

    fun createNewIpv4Public(): Reply = callbackQueryOf("oc_network_vcn_ipv4pub_add") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())

        val newPublicIp = try {
            client.createPublicIp(
                CreatePublicIpRequest.builder()
                    .createPublicIpDetails(
                        CreatePublicIpDetails.builder()
                            .compartmentId(profile.tenantId)
                            .lifetime(CreatePublicIpDetails.Lifetime.Reserved)
                            .build()
                    )
                    .build()
            ).publicIp
        } catch (e: BmcException) {
            logger.error(e) { "请求创建 PublicIp 时发生错误." }
            bot.silent().send("创建 Ip 时发生错误。\n${e.message}", upd.callbackQuery.message.chatId)
            return@callbackQueryOf
        }

        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .text("已创建新的保留公共 IP：${newPublicIp.ipAddress}")
            .replyMarkup(
                InlineKeyboardGroupBuilder()
                    .addBackButton(upd.callbackQuery.callbackData.next("oc_network_ipv4pub_manage"))
                    .build()
            )
            .build().execute(bot)
    }

    fun editIpv4Public(): Reply = callbackQueryOf("oc_network_vcn_ipv4pub_edit") { bot, upd ->
        val profile = getProfileByCallback(upd.callbackQuery.callbackData)
        val client = VirtualNetworkClient(profile.getAuthenticationDetailsProvider())
        val publicIp = client.getPublicIp(
            GetPublicIpRequest.builder()
                .publicIpId(upd.callbackQuery.callbackData.extraData[JsonFields.PublicIpId].asString)
                .build()
        ).publicIp

        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("更改显示名称")
                callbackData("EMPTY")
            }
            .rowButton {
                text("删除 IP 地址")
                callbackData("EMPTY")
            }
            .addBackButton(upd.callbackQuery.callbackData.next("oc_network_ipv4pub_manage"))
        EditMessageText.builder()
            .replyTo(upd.callbackQuery)
            .replyMarkup(keyboardBuilder.build())
            .text(
                """
                ---------- [ 公共 IP ] ----------
                ${publicIp.displayName}
                IP 地址：${publicIp.ipAddress}
                当前状态：${publicIp.lifecycleState}
                可用范围：${publicIp.scope}
                可用域：${publicIp.availabilityDomain}
            """.trimIndent()
            )
            .build().execute(bot)
    }

    fun editVcn(): Reply = callbackQueryOf("oc_network_vcn_edit") { bot, upd ->
        val keyboardBuilder = InlineKeyboardGroupBuilder()
            .rowButton {
                text("更改名称")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_change_name"))
            }
            .rowButton {
                text("删除 VCN")
                callbackData(upd.callbackQuery.callbackData.next("oc_network_vcn_delete"))
            }

        EditMessageReplyMarkup.builder()
            .chatId(upd.callbackQuery.message.chatId.toString())
            .messageId(upd.callbackQuery.message.messageId)
            .replyMarkup(
                keyboardBuilder.addBackButton(
                    callback = upd.callbackQuery.callbackData.next("oc_network_vcn_manage")
                ).build()
            )
            .build().execute(bot)
    }


    companion object {
        private val logger = KotlinLogging.logger { }
    }
}

